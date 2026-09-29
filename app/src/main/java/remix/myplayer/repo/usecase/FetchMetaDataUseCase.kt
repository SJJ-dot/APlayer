package remix.myplayer.repo.usecase

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import remix.myplayer.data.db.room.dao.MetaDataCacheDao
import remix.myplayer.data.db.room.entity.MetaDataCache
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.CoverPrefs
import remix.myplayer.service.playback.SmbMediaDataSource
import remix.myplayer.util.NetworkWatcher
import remix.myplayer.util.RemoteMediaHeadFetcher
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 远程（WebDAV / SMB）歌曲的元数据解析。
 *
 * 优先级（与本地歌曲保持一致，避免远程元数据错乱）：
 * 1. **音频文件自身的标签**（http 只拉 1MB 文件头解析；smb 按需读取）——这是"文件真实元数据"，
 *    title/artist/album 与本地播放同一文件时完全相同，歌词检索因此能命中同一版本；
 * 2. 文件缺标签（或部分字段缺失）时，才用**在线曲库**结果**补空**，绝不覆盖文件里的已有值；
 * 3. 文件完全没有标签时，在线结果作为兜底。
 *
 * 之所以不再"在线优先"，是因为在线搜索会按标题模糊匹配，容易命中同名不同版本，
 * 把 title/artist/album 改成别的版本 → 歌词、专辑/歌手聚合全部跟着错。
 */
@Singleton
class FetchMetaDataUseCase @Inject constructor(
  @param:ApplicationContext private val context: Context,
  private val metaDataCacheDao: MetaDataCacheDao,
  private val networkMetaDataLookup: NetworkMetaDataLookup,
  private val networkWatcher: NetworkWatcher,
  private val coverPrefs: CoverPrefs
) {

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val jobs = ConcurrentHashMap<String, Deferred<Unit>>()
  private val lock = Mutex()

  /** 在线查询失败（网络错误）的记录：同一网络世代内先不读音源，等网络变化后重试 */
  private val networkFailures = ConcurrentHashMap<String, FailRecord>()

  suspend operator fun invoke(song: Song.Remote) = withContext(Dispatchers.IO) {
    if (song.metaFetchState.get() >= 2) return@withContext

    val key = song.data
    val job = lock.withLock {
      jobs[key] ?: scope.async {
        try {
          performFetch(song)
        } finally {
          jobs.remove(key)
        }
      }.also { jobs[key] = it }
    }

    job.await()
    // 尝试读一次缓存，处理可能多个Song实例共享同一个URL的情况
    if (song.metaFetchState.get() == 0) {
      loadFromCache(song)
    }
  }

  private suspend fun loadFromCache(song: Song.Remote): Boolean {
    val cache = metaDataCacheDao.get(song.data)
    if (cache != null) {
      // 旧版本可能把空元数据写进缓存；空缓存视为无效，删除并重新解析
      if (cache.title.isEmpty() && cache.artist.isEmpty() && cache.album.isEmpty()) {
        metaDataCacheDao.delete(song.data)
        return false
      }
      Timber.v("fetchMeta getCache: $cache")
      song.updateMetaData(
        cache.title,
        cache.album,
        cache.artist,
        cache.duration,
        cache.year,
        cache.genre,
        cache.track,
        cache.lastModified
      )
      song.metaFetchState.set(2)
      return true
    }
    return false
  }

  private suspend fun performFetch(song: Song.Remote) {
    Timber.v("fetchMeta performFetch: ${song.data}")

    if (song.metaFetchState.get() >= 2) return
    if (!song.metaFetchState.compareAndSet(0, 1)) {
      return
    }

    if (loadFromCache(song)) {
      return
    }

    val start = System.currentTimeMillis()
    try {
      // 1. 先读音频文件自身的标签（http 只拉 1MB 文件头，smb 按需读取）
      val fileMeta = fetchFromRemoteFile(song)
      val hasFileTags = fileMeta != null && fileMeta.hasTags

      if (hasFileTags) {
        // 文件有标签：以文件为准，在线结果只用于补空
        val needFill = fileMeta.artist.isEmpty() || fileMeta.album.isEmpty()
        val online = if (needFill) {
          (networkMetaDataLookup.lookup(song) as? OnlineMetaLookup.Found)?.meta
        } else {
          null
        }
        networkFailures.remove(song.data)
        applyFileMetaData(song, fileMeta!!, online)
        return
      }

      // 2. 文件没有标签：用在线曲库补全
      when (val online = networkMetaDataLookup.lookup(song)) {
        is OnlineMetaLookup.Found -> {
          networkFailures.remove(song.data)
          applyOnlineMetaData(song, online.meta)
          return
        }

        OnlineMetaLookup.NotExist -> {
          networkFailures.remove(song.data)
        }

        OnlineMetaLookup.Failed -> {
          // 网络错误：不读音频源（避免白耗 WebDAV/SMB 流量），等网络变化后重试
          if (waitForNetwork(song)) {
            song.metaFetchState.set(0)
            return
          }
          // 同一网络下持续失败：不再无限等待，用文件名兜底，避免元数据永久空白
        }
      }

      // 3. 文件无标签且在线也没有：保留文件名解析出的标题，其余留空
      applyFallbackTitle(song, fileMeta)
    } catch (e: Exception) {
      Timber.v("fetchMeta failed, data: ${song.data} detail: $e")
      song.metaFetchState.set(3)
    } finally {
      Timber.v("fetchMeta spend:${System.currentTimeMillis() - start} ${song.data}")
    }
  }

  /**
   * 网络错误时是否应等待网络变化再重试（true = 等待，本次不读音频源）。
   *
   * 同一网络世代内最多等待 [MAX_NETWORK_FAIL_WAIT] 次；超过或跨世代（网络变化）会重新计数，
   * 超过上限则返回 false，由调用方兜底，避免在线服务长期不可用时元数据永久空白。
   */
  private fun waitForNetwork(song: Song.Remote): Boolean {
    val now = System.currentTimeMillis()
    val record = networkFailures[song.data]
    val sameEpoch = record != null && record.epoch == networkWatcher.epoch
    val inWindow = record != null && now - record.time < NETWORK_FAIL_WINDOW_MS
    if (!sameEpoch || !inWindow) {
      networkFailures[song.data] = FailRecord(networkWatcher.epoch, 1, now)
      return true
    }
    if (record.count >= MAX_NETWORK_FAIL_WAIT) {
      networkFailures.remove(song.data)
      return false
    }
    networkFailures[song.data] = record.copy(count = record.count + 1, time = now)
    return true
  }

  /**
   * 以**文件标签**为准写入元数据；[online] 仅用于补齐文件里缺失的字段（不覆盖已有值）。
   */
  private suspend fun applyFileMetaData(
    song: Song.Remote,
    fileMeta: ParsedMeta,
    online: NetworkMetaDataLookup.Result?
  ) {
    val title = fileMeta.title.ifEmpty { song.title }
    val artist = fileMeta.artist.ifEmpty { online?.artist.orEmpty() }
    val album = fileMeta.album.ifEmpty { online?.album.orEmpty() }
    val duration = if (fileMeta.duration > 0) fileMeta.duration else online?.duration ?: 0L
    val dateModified = if (song.dateModified > 0) song.dateModified else fileMeta.dateModified

    song.bitRate = fileMeta.bitRate
    song.sampleRate = fileMeta.sampleRate
    song.updateMetaData(
      title,
      album,
      artist,
      duration,
      fileMeta.year,
      fileMeta.genre,
      fileMeta.track,
      dateModified
    )
    song.metaFetchState.set(2)
    metaDataCacheDao.insert(
      MetaDataCache(
        url = song.data,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        fileSize = song.size,
        lastModified = dateModified,
        year = fileMeta.year,
        genre = fileMeta.genre,
        track = fileMeta.track
      )
    )
    // 只在"文件缺封面信息、且还没有封面记录"时记录在线封面地址
    if (online?.coverUrl != null && coverPrefs.getCover(CoverPrefs.songCoverKey(song)).isEmpty()) {
      coverPrefs.putCover(CoverPrefs.songCoverKey(song), online.coverUrl)
    }
    Timber.v("fetchMeta from file tags: $title / $artist / $album")
  }

  /** 文件无标签时：以在线结果为准（此时在线标题比文件名更干净） */
  private suspend fun applyOnlineMetaData(song: Song.Remote, meta: NetworkMetaDataLookup.Result) {
    val title = meta.title.ifEmpty { song.title }
    val dateModified = song.dateModified
    song.bitRate = ""
    song.sampleRate = ""
    song.updateMetaData(
      title,
      meta.album,
      meta.artist,
      meta.duration,
      "",
      "",
      "",
      dateModified
    )
    song.metaFetchState.set(2)
    metaDataCacheDao.insert(
      MetaDataCache(
        url = song.data,
        title = title,
        artist = meta.artist,
        album = meta.album,
        duration = meta.duration,
        fileSize = song.size,
        lastModified = dateModified,
        year = "",
        genre = "",
        track = ""
      )
    )
    // 封面地址直接落盘：UriFetcher 第一步就会命中，不再搜索网络、也不会回退到远程内嵌封面
    meta.coverUrl?.let { coverPrefs.putCover(CoverPrefs.songCoverKey(song), it) }
    Timber.v("fetchMeta from online: $title / ${meta.artist} / ${meta.album}")
  }

  /** 文件无标签、在线也没有数据：只保留文件名解析出的标题，其余留空并定案 */
  private suspend fun applyFallbackTitle(song: Song.Remote, fileMeta: ParsedMeta?) {
    val title = song.title
    val duration = fileMeta?.duration ?: 0L
    val dateModified = song.dateModified
    song.updateMetaData(title, "", "", duration, "", "", "", dateModified)
    song.metaFetchState.set(2)
    metaDataCacheDao.insert(
      MetaDataCache(
        url = song.data,
        title = title,
        artist = "",
        album = "",
        duration = duration,
        fileSize = song.size,
        lastModified = dateModified,
        year = "",
        genre = "",
        track = ""
      )
    )
  }

  /**
   * 读取远程文件标签：
   * - http(s)：只拉取头部 [RemoteMediaHeadFetcher.META_HEAD_BYTES]（ID3 标签在文件开头）；
   * - smb：走 SMB 按需读取。
   * 仅当"头部解析抛异常"时才回退一次完整解析；**解析成功但标签为空**不回退，
   * 否则无标签文件会整文件下载（这是流量大头）。
   */
  private fun fetchFromRemoteFile(song: Song.Remote): ParsedMeta? {
    val canUseHead = song.data.startsWith("http", ignoreCase = true) &&
      song.size > RemoteMediaHeadFetcher.META_HEAD_BYTES
    var headFile: File? = null
    return try {
      if (canUseHead) {
        headFile = RemoteMediaHeadFetcher.fetch(
          context = context,
          url = song.data,
          headers = song.headers,
          maxBytes = RemoteMediaHeadFetcher.META_HEAD_BYTES
        )
      }
      if (headFile != null) {
        parse(song, headFile.absolutePath) ?: parse(song, null)
      } else {
        parse(song, null)
      }
    } finally {
      runCatching { headFile?.delete() }
    }
  }

  /** 解析元数据；[localPath] 非空时解析本地（临时）文件，否则直接解析远程地址 */
  private fun parse(song: Song.Remote, localPath: String?): ParsedMeta? {
    val retriever = MediaMetadataRetriever()
    return try {
      when {
        localPath != null -> retriever.setDataSource(localPath)

        song.data.startsWith("smb://") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
          retriever.setDataSource(SmbMediaDataSource(song.data))

        else -> retriever.setDataSource(song.data, song.headers)
      }

      ParsedMeta(
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: "",
        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "",
        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
        duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
          ?.toLongOrNull() ?: 0L,
        year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: "",
        genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: "",
        track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS) ?: "",
        dateModified = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
          ?.toLongOrNull() ?: 0L,
        bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) ?: "",
        sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) ?: ""
        } else {
          ""
        }
      )
    } catch (e: Exception) {
      Timber.v(e, "parse meta failed: ${song.data}")
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  private data class FailRecord(val epoch: Long, val count: Int, val time: Long)

  private data class ParsedMeta(
    val title: String,
    val album: String,
    val artist: String,
    val duration: Long,
    val year: String,
    val genre: String,
    val track: String,
    val dateModified: Long,
    val bitRate: String,
    val sampleRate: String
  ) {
    /** 是否含有效标签（有标题/歌手/专辑/流派任一即视为已标注） */
    val hasTags: Boolean
      get() = title.isNotEmpty() || artist.isNotEmpty() || album.isNotEmpty() || genre.isNotEmpty()
  }

  companion object {
    /** 同一网络世代内因网络错误最多等待的重试次数，超过后回退兜底 */
    private const val MAX_NETWORK_FAIL_WAIT = 3

    /** 失败记录的时效：超过该时长即使网络没变化也允许重试 */
    private const val NETWORK_FAIL_WINDOW_MS = 10 * 60 * 1000L
  }
}
