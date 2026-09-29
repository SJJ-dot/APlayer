package remix.myplayer.glide

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore.Audio
import androidx.core.net.toUri
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import remix.myplayer.data.db.room.entity.PlayList
import remix.myplayer.data.model.audio.Album
import remix.myplayer.data.model.audio.Artist
import remix.myplayer.data.model.audio.Genre
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.lastfm.Image
import remix.myplayer.data.prefs.CoverPrefs
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.data.prefs.SettingPrefs.Companion.DOWNLOAD_COVER_ALWAYS
import remix.myplayer.data.prefs.SettingPrefs.Companion.DOWNLOAD_COVER_WIFI_ONLY
import remix.myplayer.data.prefs.SettingPrefs.Companion.DOWNLOAD_LASTFM
import remix.myplayer.lyric.provider.SearchScorer
import remix.myplayer.misc.cache.DiskCache
import remix.myplayer.repo.SongRepository
import remix.myplayer.repo.source.RemoteSongLookup
import remix.myplayer.repo.usecase.FetchMetaDataUseCase
import remix.myplayer.request.netease.NetEaseClient
import remix.myplayer.request.network.LastFMApi
import remix.myplayer.util.Constants
import remix.myplayer.util.SearchKeyUtil
import remix.myplayer.util.Util
import remix.myplayer.util.ext.checkWorkerThread
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** 单个封面来源的取图结果 */
private sealed interface SourceResult {
  data class Found(val uri: Uri) : SourceResult

  /** 确认该来源没有封面（可标记，不再重复尝试） */
  data object NotExist : SourceResult

  /** 网络错误 / 解析错误 / 超时等：**不标记**，下次展示（启动/刷新/网络变化）重新尝试 */
  data object Failed : SourceResult
}

private typealias CoverSource = Pair<CoverSourceState.Source, suspend () -> SourceResult>

/**
 * created by Remix on 2021/4/20
 */
@Singleton
class UriFetcher @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val neClient: NetEaseClient,
  private val lastFMApi: LastFMApi,
  private val settingPrefs: SettingPrefs,
  private val coverPrefs: CoverPrefs,
  private val songRepo: SongRepository,
  private val remoteSongLookup: RemoteSongLookup,
  private val fetchMetaDataUseCase: FetchMetaDataUseCase
) {

  private var albumVersion = coverPrefs.getAlbumVersion()
    set(value) {
      field = value
      coverPrefs.putAlbumVersion(value)
    }
  private var artistVersion = coverPrefs.getArtistVersion()
    set(value) {
      field = value
      coverPrefs.putArtistVersion(value)
    }
  private var playListVersion = coverPrefs.getPlayListVersion()
    set(value) {
      field = value
      coverPrefs.putPlayListVersion(value)
    }

  fun cacheKey(model: Any): String = when (model) {
    is Song -> CoverPrefs.songCoverKey(model)
    is Album -> "album:${model.albumID}"
    is Artist -> "artist:${model.artistID}"
    is PlayList -> "playlist:${model.id}"
    is Genre -> "genre:${model.id}"
    else -> error("unknown model")
  }

  fun fetch(model: Any): Uri {
    val key = cacheKey(model)

    // 1. 启动/刷新时先看「已成功加载过封面」的持久记录：
    //    命中则直接复用，不再联网搜索、也不重复解析/拉取内嵌封面
    val storedCover = coverPrefs.getCover(key)
    if (storedCover.isNotEmpty()) {
      val storedUri = storedCover.toUri()
      if (isCoverUsable(storedUri)) {
        return storedUri
      }
      // 记录已失效（文件/封面被删、自定义封面被移除等）→ 清掉后重新解析
      coverPrefs.putCover(key, "")
    }

    // 2. 多来源并行解析（按优先级取值）
    val uri = when (model) {
      is Song -> fetch(model)
      is Album -> fetch(model)
      is Artist -> fetch(model)
      is PlayList -> fetch(model)
      is Genre -> fetch(model)
      else -> throw IllegalArgumentException("unknown model: ${model::class.java.simpleName}")
    }

    // 3. 成功后持久化记录，下次直接复用
    if (uri != Uri.EMPTY) {
      coverPrefs.putCover(key, uri.toString())
      Timber.v("uri: $uri")
    }

    return uri
  }

  /** 持久化记录是否仍可用（防止文件被删/自定义封面被移除后仍返回失效封面） */
  private fun isCoverUsable(uri: Uri): Boolean = when (uri.scheme) {
    "embedded" -> {
      // 内嵌封面：内存或磁盘缓存里仍然有解析结果
      val rawPath = uri.path ?: return false
      val sourcePath = if (rawPath.startsWith("/http", ignoreCase = true)) {
        Uri.decode(rawPath.drop(1))
      } else {
        rawPath
      }
      EmbeddedCoverCache.cachedResultOf(sourcePath) is EmbeddedResult.Found
    }

    "content" -> mediaStoreArt(uri) is SourceResult.Found

    "file" -> File(uri.path ?: "").exists()

    else -> true
  }

  fun updateAllVersion() {
    updateAlbumVersion()
    updateArtistVersion()
    updatePlayListVersion()
  }

  fun updateAlbumVersion() {
    albumVersion++
    // 版本变化（标签/自定义封面变更）后，已记录的封面需要重新解析
    coverPrefs.clearCoverUris()
    CoverSourceState.clearAll()
  }

  fun updateArtistVersion() {
    artistVersion++
    coverPrefs.clearCoverUris()
    CoverSourceState.clearAll()
  }

  fun updatePlayListVersion() {
    playListVersion++
    coverPrefs.clearCoverUris()
    CoverSourceState.clearAll()
  }

  /**
   * 清理封面缓存：记录 + 内存缓存。
   * 内嵌封面的**磁盘**缓存位于 cacheDir，随「清理缓存」整体删除，这里不动它，
   * 以免每次刷新列表都要重新拉取远程内嵌封面。
   */
  fun clearAllCache() {
    coverPrefs.clearCoverUris()
    EmbeddedCoverCache.clear()
    CoverSourceState.clearAll()
  }

  init {
    // 初始化内嵌封面的磁盘缓存目录
    EmbeddedCoverCache.init(context)
  }

  /**
   * 多来源取封面，成功后按**优先级**返回：
   * - [sequential] = false（本地歌曲）：各来源**并行**发起，高优先级来源先给一段等待窗口，
   *   窗口内没结果才采用低优先级来源的结果；
   * - [sequential] = true（远程歌曲）：**严格串行**发起，只有前一个来源确认没结果才启动下一个，
   *   从而在网络已取到封面时完全不产生 WebDAV/SMB 流量。
   *
   * 只有 [SourceResult.NotExist]（确认不存在）才会记录状态，失败/超时不会记录，从而保证可重试。
   */
  private fun resolveCover(
    key: String,
    sources: List<CoverSource>,
    sequential: Boolean = false
  ): Uri {
    val active = sources.filter { (source, _) -> !CoverSourceState.isNotExist(key, source) }
    if (active.isEmpty()) {
      return Uri.EMPTY
    }

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    return try {
      if (sequential) {
        // 远程歌曲走**串行**：先在线搜索，确认没有结果后才去拉远程内嵌封面，
        // 避免网络已经取到封面时仍下载 WebDAV/SMB 数据（尽可能少用音源流量）。
        runBlocking {
          active.forEachIndexed { index, (source, block) ->
            val waitMs = if (index == 0) SEQUENTIAL_FIRST_WAIT_MS else NEXT_SOURCE_WAIT_MS
            val defer = scope.async {
              runCatching { block() }.getOrElse { SourceResult.Failed }
            }
            when (val result = withTimeoutOrNull(waitMs) { defer.await() }) {
              is SourceResult.Found -> {
                defer.cancel()
                return@runBlocking result.uri
              }

              SourceResult.NotExist -> CoverSourceState.markNotExist(key, source)

              // 网络错误 / 超时：**不**回退拉取音源数据（避免白耗 WebDAV/SMB 流量），
              // 等网络变化/下次展示时重试
              else -> {
                defer.cancel()
                return@runBlocking Uri.EMPTY
              }
            }
            defer.cancel()
          }
          Uri.EMPTY
        }
      } else {
        runBlocking {
        val deferred = active.map { (source, block) ->
          // 任何未捕获异常都视为该来源“失败”（不标记、下次重试），不影响其它来源
          source to scope.async { runCatching { block() }.getOrElse { SourceResult.Failed } }
        }
        deferred.forEachIndexed { index, (source, defer) ->
          val waitMs = if (index == 0) FIRST_SOURCE_WAIT_MS else NEXT_SOURCE_WAIT_MS
          when (val result = withTimeoutOrNull(waitMs) { defer.await() }) {
            is SourceResult.Found -> {
              deferred.drop(index + 1).forEach { it.second.cancel() }
              return@runBlocking result.uri
            }

            SourceResult.NotExist -> CoverSourceState.markNotExist(key, source)

            else -> Unit // 超时 / 失败：不标记，留给下次重试
          }
        }
          Uri.EMPTY
        }
      }
    } finally {
      scope.cancel()
    }
  }

  private fun fetch(song: Song): Uri {
    checkWorkerThread()
    if (song is Song.Remote) {
      runBlocking {
        fetchMetaDataUseCase(song)
      }
    }

    val sources: List<CoverSource> = buildList {
      if (song.isLocal()) {
        add(CoverSourceState.Source.MEDIA_STORE to { mediaStoreCover(song) })
      }
      add(CoverSourceState.Source.NETWORK to { networkCover(song) })
      // 远程歌曲的“内嵌封面”需要下载远程文件，只有允许下载封面时才作为兜底来源；
      // 本地歌曲直接读本地文件，不受该开关限制。
      if (song.isLocal() || canDownloadCover()) {
        add(CoverSourceState.Source.EMBEDDED to { embeddedCover(song) })
      }
    }
    // 远程歌曲串行取封面（在线优先，避免网络命中时仍拉取远程内嵌封面）
    return resolveCover(cacheKey(song), sources, sequential = song is Song.Remote)
  }

  /** 是否能被 MediaMetadataRetriever 直接读取（本地文件 / WebDAV http） */
  private fun readableDirectly(song: Song): Boolean =
    song.isLocal() || song.data.startsWith("http", ignoreCase = true)

  // ---------------- 各来源：歌曲 ----------------

  /** MediaStore 专辑封面（本地歌曲唯一来源，含用户自定义封面） */
  private fun mediaStoreCover(song: Song): SourceResult {
    if (!song.isLocal() || ignoreMediaStore() || song.albumId <= 0 || song.id <= 0) {
      return SourceResult.NotExist
    }
    return mediaStoreArt(song.artUri)
  }

  /** 文件内嵌封面（本地文件 / WebDAV；SMB 系统无法直读） */
  private suspend fun embeddedCover(song: Song): SourceResult {
    if (song.data.isBlank() || !readableDirectly(song)) {
      return SourceResult.NotExist
    }
    // 本地：直接解析文件；远程 WebDAV：系统解码器不携带认证头，需自行带认证拉取文件头部再解析
    // 远程读取可能较慢，时限由 resolveCover 的等待窗口控制；
    // 若高优先级来源已取到封面则提前中止，避免白下载。
    val jobContext = currentCoroutineContext()
    val result = when {
      song.isLocal() -> EmbeddedCoverCache.resultOf(song.data)
      song is Song.Remote -> RemoteEmbeddedCoverLoader.load(
        context = context,
        url = song.data,
        account = song.account,
        pwd = song.pwd,
        isCancelled = { !jobContext.isActive }
      )

      else -> EmbeddedResult.NotExist
    }
    return when (result) {
      is EmbeddedResult.Found -> SourceResult.Found(embeddedUri(song))
      EmbeddedResult.NotExist -> SourceResult.NotExist
      EmbeddedResult.Failed -> SourceResult.Failed
    }
  }

  /** 联网搜索封面 */
  private fun networkCover(song: Song): SourceResult {
    if (!canDownloadCover()) {
      // 未开启下载：不算“不存在”，开关变化后会重新尝试
      return SourceResult.Failed
    }
    return try {
      if (downloadFromLastFM()) {
        val lastFMAlbum =
          runBlocking { lastFMApi.searchLastFMAlbum(song.album, song.artist, null) }
        val lastFMUri = getLargestImageUrl(lastFMAlbum.album?.image)
        if (!lastFMUri.isNullOrEmpty()) {
          SourceResult.Found(lastFMUri.toUri())
        } else {
          SourceResult.NotExist
        }
      } else {
        val searchKeys = SearchKeyUtil.getSearchKeys(song)
        for (key in searchKeys.take(CANDIDATE_KEY_NUMBER)) {
          val candidates = neClient.searchSongList(key.value)
          val best = candidates
            .map { ne ->
              ne to SearchScorer.calculateSongScoreWithKeyKind(
                targetSong = song,
                candidateTitle = ne.name,
                candidateArtist = ne.ar?.joinToString(", ") { it.name ?: "" },
                candidateAlbum = ne.al?.name,
                candidateDuration = ne.dt,
                keyword = key.value,
                keyKind = key.kind
              )
            }
            .filter { it.second.isValid }
            .maxByOrNull { it.second.score }
            ?.first

          if (best?.al?.picUrl?.isNotEmpty() == true) {
            return SourceResult.Found(best.al.picUrl.toUri())
          }
        }
        SourceResult.NotExist
      }
    } catch (e: Exception) {
      Timber.v(e)
      SourceResult.Failed
    }
  }

  private fun fetch(album: Album): Uri {
    val sources: List<CoverSource> = listOf(
      CoverSourceState.Source.MEDIA_STORE to { mediaStoreCover(album) },
      CoverSourceState.Source.NETWORK to { networkCover(album) },
      CoverSourceState.Source.EMBEDDED to { embeddedCoverFromSongs(songsOfAlbum(album)) }
    )
    return resolveCover(cacheKey(album), sources)
  }

  private fun fetch(artist: Artist): Uri {
    val sources: List<CoverSource> = listOf(
      CoverSourceState.Source.MEDIA_STORE to { mediaStoreCover(artist) },
      CoverSourceState.Source.NETWORK to { networkCover(artist) },
      CoverSourceState.Source.EMBEDDED to { embeddedCoverFromSongs(songsOfArtist(artist)) }
    )
    return resolveCover(cacheKey(artist), sources)
  }

  // ---------------- 各来源：专辑 ----------------

  private fun mediaStoreCover(album: Album): SourceResult {
    if (ignoreMediaStore()) {
      return SourceResult.NotExist
    }
    // 无专辑标签的文件夹分组：取组内本地歌曲的 MediaStore 封面
    album.folderPath?.let { folder ->
      val prefix = folder.trimEnd('/') + "/"
      val songs =
        songRepo.getSongs(Audio.Media.DATA + " LIKE ?", arrayOf("$prefix%"), null)
      songs.take(SONG_COVER_CANDIDATES).forEach { song ->
        if (song.isLocal() && song.albumId > 0) {
          val result = mediaStoreArt(song.artUri)
          if (result is SourceResult.Found) {
            return result
          }
        }
      }
      return SourceResult.NotExist
    }
    // 远程专辑（负 id）没有 MediaStore 记录
    if (album.albumID <= 0) {
      return SourceResult.NotExist
    }
    return mediaStoreArt(album.artUri)
  }

  private fun networkCover(album: Album): SourceResult {
    if (!canDownloadCover()) {
      return SourceResult.Failed
    }
    return try {
      if (downloadFromLastFM()) {
        val lastFMAlbum =
          runBlocking { lastFMApi.searchLastFMAlbum(album.album, album.artist, null) }
        val lastFMUri = getLargestImageUrl(lastFMAlbum.album?.image)
        if (!lastFMUri.isNullOrEmpty()) {
          SourceResult.Found(lastFMUri.toUri())
        } else {
          SourceResult.NotExist
        }
      } else {
        val searchKeys = SearchKeyUtil.getSearchKeys(album)
        for (key in searchKeys.take(CANDIDATE_KEY_NUMBER)) {
          val candidates = neClient.searchAlbumList(key.value)
          val best = candidates
            .map { ne ->
              ne to SearchScorer.calculateAlbumScore(album, ne.name, null)
            }
            .filter { it.second.isValid }
            .maxByOrNull { it.second.score }
            ?.first

          if (best?.picUrl?.isNotEmpty() == true) {
            return SourceResult.Found(best.picUrl.toUri())
          }
        }
        SourceResult.NotExist
      }
    } catch (e: Exception) {
      Timber.v(e)
      SourceResult.Failed
    }
  }

  // ---------------- 各来源：歌手 ----------------

  private fun mediaStoreCover(artist: Artist): SourceResult {
    if (ignoreMediaStore() || artist.artistID <= 0) {
      return SourceResult.NotExist
    }
    val songs =
      songRepo.getSongs(Audio.Media.ARTIST_ID + "=", arrayOf(artist.artistID.toString()), null)
    songs.take(SONG_COVER_CANDIDATES).forEach { song ->
      if (song.albumId > 0) {
        val result = mediaStoreArt(song.artUri)
        if (result is SourceResult.Found) {
          return result
        }
      }
    }
    return SourceResult.NotExist
  }

  private fun networkCover(artist: Artist): SourceResult {
    if (!canDownloadCover()) {
      return SourceResult.Failed
    }
    return try {
      if (downloadFromLastFM()) {
        val lastFMArtist = runBlocking { lastFMApi.searchLastFMArtist(artist.artist, null) }
        val lastFMUri = getLargestImageUrl(lastFMArtist.artist?.image)
        if (!lastFMUri.isNullOrEmpty()) {
          SourceResult.Found(lastFMUri.toUri())
        } else {
          SourceResult.NotExist
        }
      } else {
        val searchKeys = SearchKeyUtil.getSearchKeys(artist)
        for (key in searchKeys.take(CANDIDATE_KEY_NUMBER)) {
          val candidates = neClient.searchArtistList(key.value)
          val best = candidates
            .map { ne -> ne to SearchScorer.calculateArtistScore(artist, ne.name) }
            .filter { it.second.isValid }
            .maxByOrNull { it.second.score }
            ?.first

          if (best?.picUrl?.isNotEmpty() == true) {
            return SourceResult.Found(best.picUrl.toUri())
          }
        }
        SourceResult.NotExist
      }
    } catch (e: Exception) {
      Timber.v(e)
      SourceResult.Failed
    }
  }

  private fun fetch(playList: PlayList): Uri {
    // 自定义封面
//    val customArtFile = getCustomThumbIfExist(playList.id, Constants.PLAYLIST)
//    if (customArtFile != null) {
//      return Uri.fromFile(customArtFile)
//    }

    val songs = runBlocking { songRepo.getSongsByModels(listOf(playList)) }

    // 逐个成员歌曲取封面（每首走完整的多来源优先级流程）
    songs.take(SONG_COVER_CANDIDATES).forEach { song ->
      val uri = fetch(song)
      if (uri != Uri.EMPTY) {
        return uri
      }
    }

    return Uri.EMPTY
  }

  private fun fetch(genre: Genre): Uri {
    val songs = if (genre.id > 0) {
      songRepo.getSongsByGenreId(genre.id)
    } else if (genre.genre.isNotBlank()) {
      runBlocking { remoteSongLookup.cachedByGenre(genre.genre) }
    } else {
      emptyList()
    }

    songs.take(SONG_COVER_CANDIDATES).forEach { song ->
      val uri = fetch(song)
      if (uri != Uri.EMPTY) {
        return uri
      }
    }

    return Uri.EMPTY
  }

  /** 专辑下的歌曲：文件夹分组按文件夹取；本地按 albumId 查 MediaStore，远程按专辑名查缓存 */
  private fun songsOfAlbum(album: Album): List<Song> {
    album.folderPath?.let { folder ->
      val prefix = folder.trimEnd('/') + "/"
      val local = songRepo.getSongs(
        Audio.Media.DATA + " LIKE ?",
        arrayOf("$prefix%"),
        null
      )
      val remote = runBlocking { remoteSongLookup.cachedByFolder(folder) }
      return local + remote
    }
    return if (album.albumID > 0) {
      songRepo.getSongs(Audio.Media.ALBUM_ID + "=", arrayOf(album.albumID.toString()), null)
    } else if (album.album.isNotBlank()) {
      runBlocking { remoteSongLookup.cachedByAlbum(album.album) }
    } else {
      emptyList()
    }
  }

  /** 歌手下的歌曲：本地按 artistId 查 MediaStore，远程按歌手名查缓存 */
  private fun songsOfArtist(artist: Artist): List<Song> = if (artist.artistID > 0) {
    songRepo.getSongs(Audio.Media.ARTIST_ID + "=", arrayOf(artist.artistID.toString()), null)
  } else if (artist.artist.isNotBlank()) {
    runBlocking { remoteSongLookup.cachedByArtist(artist.artist) }
  } else {
    emptyList()
  }

  /**
   * 取歌曲列表中的内嵌封面（供专辑/歌手等分组对象兜底）：
   * 逐个歌曲解析，返回第一个成功的结果；有解析失败的则标记为 [SourceResult.Failed] 以便重试。
   */
  private suspend fun embeddedCoverFromSongs(songs: List<Song>): SourceResult {
    if (songs.isEmpty()) {
      return SourceResult.NotExist
    }
    var anyFailed = false
    songs.take(SONG_COVER_CANDIDATES).forEach { song ->
      when (val result = embeddedCover(song)) {
        is SourceResult.Found -> return result
        SourceResult.Failed -> anyFailed = true
        SourceResult.NotExist -> Unit
      }
    }
    return if (anyFailed) SourceResult.Failed else SourceResult.NotExist
  }

  /** 内嵌封面 uri：本地直接拼接路径；远程（WebDAV）把完整 url 编码进 path，由 EmbeddedFetcher 还原 */
  private fun embeddedUri(song: Song): Uri =
    if (song.isLocal()) {
      (PREFIX_EMBEDDED + song.data).toUri()
    } else {
      (PREFIX_EMBEDDED + "/" + Uri.encode(song.data)).toUri()
    }

  private fun ignoreMediaStore() = settingPrefs.ignoreMediaStore

  private fun downloadFromLastFM() = settingPrefs.downloadSource == DOWNLOAD_LASTFM

  private fun canDownloadCover(): Boolean {
    return when (settingPrefs.autoDownloadCover) {
      DOWNLOAD_COVER_ALWAYS -> true
      DOWNLOAD_COVER_WIFI_ONLY -> Util.isWifi(context)
      else -> false
    }
  }

  /**
   * 读取 MediaStore 专辑封面：
   * - 能打开 -> 存在；
   * - [FileNotFoundException] -> 确认没有封面（可标记）；
   * - 其它异常（权限等）-> 失败，下次重试。
   */
  private fun mediaStoreArt(uri: Uri): SourceResult {
    return try {
      context.contentResolver.openInputStream(uri)?.use {
        SourceResult.Found(uri)
      } ?: SourceResult.Failed
    } catch (e: FileNotFoundException) {
      SourceResult.NotExist
    } catch (e: Exception) {
      Timber.v(e, "open album art failed: $uri")
      SourceResult.Failed
    }
  }

  /**
   * 返回自定义的封面
   */
  private fun getCustomThumbIfExist(id: Long, type: Int): File? {
    val img = File(DiskCache.getDiskCacheDir(context, "thumbnail"), "$type-$id.jpg")
    if (img.exists()) {
      return img
    }
    return null
  }

  private fun getSearchKey(model: Any): String? {
    return SearchKeyUtil.getSearchKeys(model).firstOrNull()?.value
  }

  private enum class ImageSize {
    SMALL, MEDIUM, LARGE, EXTRALARGE, MEGA, UNKNOWN
  }

  private fun getLargestImageUrl(images: List<Image>?): String? {
    if (images.isNullOrEmpty()) {
      return null
    }
    val imageUrls = HashMap<ImageSize, String?>()
    for (image in images) {
      var size: ImageSize? = null
      val attribute = image.size
      if (attribute == null) {
        size = ImageSize.UNKNOWN
      } else {
        try {
          size = ImageSize.valueOf(attribute.uppercase())
        } catch (_: IllegalArgumentException) {
          // if they suddenly again introduce a new image size
        }
      }
      if (size != null) {
        imageUrls.put(size, image.text)
      }
    }
    return getLargestImageUrl(imageUrls)
  }

  private fun getLargestImageUrl(imageUrls: Map<ImageSize, String?>): String? {
    if (imageUrls.containsKey(ImageSize.MEGA)) {
      return imageUrls[ImageSize.MEGA]
    }
    if (imageUrls.containsKey(ImageSize.EXTRALARGE)) {
      return imageUrls[ImageSize.EXTRALARGE]
    }
    if (imageUrls.containsKey(ImageSize.LARGE)) {
      return imageUrls[ImageSize.LARGE]
    }
    if (imageUrls.containsKey(ImageSize.MEDIUM)) {
      return imageUrls[ImageSize.MEDIUM]
    }
    if (imageUrls.containsKey(ImageSize.SMALL)) {
      return imageUrls[ImageSize.SMALL]
    }
    if (imageUrls.containsKey(ImageSize.UNKNOWN)) {
      return imageUrls[ImageSize.UNKNOWN]
    }
    return null
  }

  companion object {
    private const val CANDIDATE_KEY_NUMBER = 1

    /**
     * 兜底取歌曲封面时最多尝试的歌曲数。
     * 远程歌曲每尝试一首都要拉取一份文件头，因此限制为 2 首，避免大专辑/大歌手消耗流量。
     */
    private const val SONG_COVER_CANDIDATES = 2

    /** 串行模式下首个来源（在线搜索）的等待窗口 */
    private const val SEQUENTIAL_FIRST_WAIT_MS = 6000L

    /** 最高优先级来源的等待窗口：窗口内没结果就继续用下一优先级的结果 */
    private const val FIRST_SOURCE_WAIT_MS = 2500L

    /** 后续优先级来源的等待窗口 */
    private const val NEXT_SOURCE_WAIT_MS = 4000L

    const val PREFIX_EMBEDDED = "embedded://"

    const val SCHEME_EMBEDDED = "embedded"
  }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UriFetcherEntryPoint {

  fun uriFetcher(): UriFetcher
}
