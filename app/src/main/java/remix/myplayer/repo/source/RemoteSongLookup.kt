package remix.myplayer.repo.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import remix.myplayer.data.db.room.dao.MetaDataCacheDao
import remix.myplayer.data.db.room.dao.PlayQueueDao
import remix.myplayer.data.db.room.dao.RemoteSongCacheDao
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.data.db.room.entity.RemoteSongCache
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.source.SourceType
import remix.myplayer.repo.SmbRepository
import remix.myplayer.repo.WebDavRepository
import remix.myplayer.service.MusicServiceRemote
import remix.myplayer.repo.usecase.FetchMetaDataUseCase
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 单个远程音源枚举失败的信息：音源别名 + 失败原因 */
data class RemoteSourceFailure(val alias: String, val cause: Throwable)

/**
 * 远程曲库刷新结果。
 *
 * - [Success.songs]：刷新后（取自持久缓存）的完整远程歌曲列表；
 *   [Success.failures]：本次枚举失败、其缓存被**原样保留**的音源列表。
 * - [Failure]：刷新整体失败（如读取音源配置异常）。此时调用方应**保留原有列表**，仅提示失败原因。
 */
sealed interface RemoteRefreshResult {
  data class Success(
    val songs: List<Song.Remote>,
    val failures: List<RemoteSourceFailure>
  ) : RemoteRefreshResult

  data class Failure(val cause: Throwable) : RemoteRefreshResult
}

/**
 * 远程音源（WebDAV / SMB）歌曲的持久缓存与增量刷新中心。
 *
 * - [loadCached]：从 [RemoteSongCache] 表离线读取全部远程歌曲，启动即可立即展示，不触网。
 * - [refresh]：后台递归枚举各远程源，按 [RemoteSongCache.sourceKey] 做差异合并——
 *   新增或文件大小/修改时间变化的才重新解析元数据并回写缓存；远端已删除的清理缓存。
 *   枚举失败的音源整体跳过 diff，其缓存原样保留，失败原因随结果返回。
 * - [get]/[getCached]：作为负 id 的稳定解析器，供收藏 / 历史 / 歌单读取还原完整 [Song.Remote]。
 */
@Singleton
class RemoteSongLookup @Inject constructor(
  private val webDavRepository: WebDavRepository,
  private val smbRepository: SmbRepository,
  private val playQueueDao: PlayQueueDao,
  private val settingPrefs: SettingPrefs,
  private val enumerator: RemoteFileEnumerator,
  private val resolver: RemoteSongResolver,
  private val fetchMetaDataUseCase: FetchMetaDataUseCase,
  private val remoteSongCacheDao: RemoteSongCacheDao,
  private val metaDataCacheDao: MetaDataCacheDao
) {
  @Volatile
  private var loaded = false

  /** 并发解析元数据上限，避免同时发起大量 HTTP 请求压垮 WebDAV/SMB。 */
  private val metaSemaphore = Semaphore(4)

  fun isLoaded(): Boolean = loaded

  /** 离线读取已缓存的远程歌曲（含元数据），启动即用，无网络 IO。 */
  suspend fun loadCached(): List<Song.Remote> = withContext(Dispatchers.IO) {
    val all = visibleCachedRows()
    val list = all.map { it.toRemoteSong() }
    resolver.putAll(list)
    list
  }

  /**
   * 读取「可见」的缓存歌曲：排除黑名单，并排除属于**已禁用音源**的记录。
   *
   * 这是读取侧的兜底不变量：卸载音源时的缓存清理即便因并发/异常未及时完成，
   * 已禁用音源的歌曲也不会出现在曲库里。
   * 查询音源配置失败时返回 null（fail-open），避免误把全部远程歌曲隐藏。
   */
  private suspend fun visibleCachedRows(): List<RemoteSongCache> {
    val blacklist = settingPrefs.deleteRemoteUrls
    val enabledKeys = runCatching {
      val keys = mutableSetOf<String>()
      webDavRepository.allWebDav().first()
        .filter { it.enabled }
        .forEach { keys.add("webdav:${it.server}") }
      smbRepository.allSmb().first()
        .filter { it.enabled }
        .forEach { keys.add("smb:${it.server}:${it.share}") }
      keys
    }.onFailure { Timber.w(it, "load enabled source keys failed") }.getOrNull()

    return remoteSongCacheDao.getAll().filter {
      it.url !in blacklist && (enabledKeys == null || it.sourceKey in enabledKeys)
    }
  }

  /**
   * 后台增量刷新：枚举远程源并与缓存做 diff。仅对“新增/变更”文件解析元数据（网络），
   * 删除远端已不存在的缓存项；未变化的文件直接复用缓存，避免重复网络解析。
   *
   * **枚举失败的音源会被整体跳过**：不解析元数据、也不做 diff，因此其本地缓存保持原样，
   * 不会被误判为“远端已删除”而清空。
   *
   * 区分两类失败：
   * - 网络 / 超时 / 服务器错误（[RemoteEnumerationException.Failed]）→ 保留缓存，仅提示原因；
   * - 登录 / 鉴权失败（[RemoteEnumerationException.AuthFailed]）→ 凭据已不可用，清空该音源缓存。
   *
   * 失败原因随 [RemoteRefreshResult] 返回，供 UI 提示。
   */
  suspend fun refresh(): RemoteRefreshResult = withContext(Dispatchers.IO) {
    val failures = mutableListOf<RemoteSourceFailure>()
    val removedUrls = mutableSetOf<String>()
    val webDavs = try {
      webDavRepository.allWebDav().first()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.e(e, "refresh webdav list failed")
      return@withContext RemoteRefreshResult.Failure(e)
    }
    for (wd in webDavs) {
      // 已禁用的音源：不枚举，并清掉已缓存歌曲（使其立即从曲库消失）
      if (!wd.enabled) {
        removedUrls += applyDiff("webdav:${wd.server}", SourceType.WEBDAV.ordinal, emptyList())
        continue
      }
      // 仅导入所选目录（rootDir）下的文件，未设置时从服务器根导入
      val root = wd.rootDir?.takeIf { it.isNotBlank() } ?: wd.getRoot()
      val songs = try {
        enumerator.enumerateWebDav(wd, root, true)
      } catch (e: RemoteEnumerationException) {
        Timber.w(e, "enumerate webdav failed: ${wd.alias}")
        failures += RemoteSourceFailure(wd.alias, e)
        if (e is RemoteEnumerationException.AuthFailed) {
          // 登录失败（凭据失效 / 无权限）：清空该音源的缓存数据，避免继续展示无法播放的旧列表
          removedUrls += applyDiff("webdav:${wd.server}", SourceType.WEBDAV.ordinal, emptyList())
        }
        continue
      }
      removedUrls += applyDiff("webdav:${wd.server}", SourceType.WEBDAV.ordinal, songs)
    }

    val smbs = try {
      smbRepository.allSmb().first()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.e(e, "refresh smb list failed")
      return@withContext RemoteRefreshResult.Failure(e)
    }
    for (smb in smbs) {
      // 已禁用的音源：不枚举，并清掉已缓存歌曲（使其立即从曲库消失）
      if (!smb.enabled) {
        removedUrls += applyDiff("smb:${smb.server}:${smb.share}", SourceType.SMB.ordinal, emptyList())
        continue
      }
      // 仅导入所选目录（rootDir，共享内相对路径）下的文件，未设置时导入整个共享
      val relativeRoot = smb.rootDir?.trim { it == '/' || it == '\\' }.orEmpty()
      val root = if (relativeRoot.isEmpty()) {
        smb.getRoot()
      } else {
        "${smb.getRoot().removeSuffix("/")}/$relativeRoot"
      }
      val songs = try {
        enumerator.enumerateSmb(smb, root, true)
      } catch (e: RemoteEnumerationException) {
        Timber.w(e, "enumerate smb failed: ${smb.alias}")
        failures += RemoteSourceFailure(smb.alias, e)
        if (e is RemoteEnumerationException.AuthFailed) {
          // 登录失败（凭据失效 / 无权限）：清空该音源的缓存数据，避免继续展示无法播放的旧列表
          removedUrls += applyDiff("smb:${smb.server}:${smb.share}", SourceType.SMB.ordinal, emptyList())
        }
        continue
      }
      removedUrls += applyDiff("smb:${smb.server}:${smb.share}", SourceType.SMB.ordinal, songs)
    }

    loaded = true
    val all = visibleCachedRows()
    val list = all.map { it.toRemoteSong() }
    // 内存解析表严格对齐缓存，移除已被删除/移出目录的歌曲，避免仍可通过内存表播放
    resolver.retainByData(list.map { it.data }.toSet())
    resolver.putAll(list)

    // 同步清理播放队列中已不存在于任何音源的歌曲
    purgeRemovedFromQueue(removedUrls)
    RemoteRefreshResult.Success(list, failures)
  }

  /**
   * 删除音源配置后调用：清理该源的持久缓存与内存解析表。
   * 返回被移除歌曲的稳定 id，供调用方同步清理播放队列。
   */
  suspend fun clearSource(sourceKey: String): List<Long> = withContext(Dispatchers.IO) {
    val cached = remoteSongCacheDao.getBySourceKey(sourceKey)
    if (cached.isEmpty()) {
      return@withContext emptyList()
    }
    val removedIds = cached.map { it.toRemoteSong().id }
    remoteSongCacheDao.deleteBySourceKey(sourceKey)
    resolver.retainByData(
      remoteSongCacheDao.getAll().map { it.url }.toSet()
    )
    removedIds
  }

  /** 查询某首歌 url 所属音源的 sourceKey（用于反查 WebDAV/SMB 配置） */
  suspend fun sourceKeyOf(url: String): String? = withContext(Dispatchers.IO) {
    remoteSongCacheDao.getByUrl(url)?.sourceKey
  }

  /**
   * 删除（或从曲库移除）若干远程歌曲后清理本地痕迹：
   * 缓存表、元数据缓存、内存解析表、播放队列。返回被清理的歌曲 id。
   */
  suspend fun deleteSongs(urls: List<String>): List<Long> = withContext(Dispatchers.IO) {
    if (urls.isEmpty()) {
      return@withContext emptyList()
    }
    val removedIds = urls.mapNotNull { url ->
      remoteSongCacheDao.getByUrl(url)?.toRemoteSong()?.id
    }
    remoteSongCacheDao.deleteByUrls(urls)
    urls.forEach { url -> runCatching { metaDataCacheDao.delete(url) } }
    resolver.removeByData(urls)
    purgeRemovedFromQueue(urls.toSet())
    removedIds
  }

  private suspend fun purgeRemovedFromQueue(removedUrls: Set<String>) {
    if (removedUrls.isEmpty()) {
      return
    }
    runCatching {
      // 直接读队列表，避免依赖 PlayQueueRepository → SongRepository → 本类 形成注入环
      val queue = playQueueDao.selectAll().first()
      val ids = queue.filter { it.data in removedUrls }.map { it.audio_id }
      if (ids.isNotEmpty()) {
        MusicServiceRemote.removeFromQueue(ids)
      }
    }.onFailure { Timber.w(it, "purge removed songs from queue failed") }
  }

  /**
   * 对单个远程源做增量合并：
   * - 新增或 size/dateModified 变化的文件 → 重新解析元数据并 upsert 缓存；
   * - 缓存中存在但本次枚举里没有的文件（远端已删除）→ 删除缓存。
   * 枚举失败的源**不会进入此方法**（[refresh] 已提前跳过），因此其缓存被原样保留，不会被误删。
   */
  private suspend fun applyDiff(
    sourceKey: String,
    sourceType: Int,
    enumerated: List<Song.Remote>
  ): Set<String> {
    val cached = remoteSongCacheDao.getBySourceKey(sourceKey).associateBy { it.url }
    // 已从曲库移除的远程歌曲（黑名单）不再写回，并按“远端已不存在”处理
    val blacklist = settingPrefs.deleteRemoteUrls
    val enumeratedMap = enumerated.filter { it.data !in blacklist }.associateBy { it.data }

    // 仅新增或变化的文件需要重新解析元数据（网络）
    val changed = enumerated.filter { src ->
      val c = cached[src.data]
      c == null || c.size != src.size || c.dateModified != src.dateModified
    }
    fetchMetaData(changed)
    for (src in changed) {
      remoteSongCacheDao.insert(src.toRemoteSongCache(sourceKey, sourceType))
    }

    // 清理远端已不存在的缓存项；目录被切换为不含音频的空目录时，直接清空该源缓存
    val removedUrls: Set<String> = if (enumeratedMap.isEmpty()) {
      remoteSongCacheDao.deleteBySourceKey(sourceKey)
      cached.keys
    } else {
      val gone = cached.keys - enumeratedMap.keys
      if (gone.isNotEmpty()) {
        remoteSongCacheDao.deleteNotIn(sourceKey, enumeratedMap.keys.toList())
      }
      gone
    }
    return removedUrls
  }

  /**
   * 批量解析远程歌曲元数据。
   *
   * - 优先读 [remix.myplayer.data.db.room.entity.MetaDataCache] 缓存，命中只涉及数据库 IO；
   * - 缓存未命中时通过网络（[android.media.MediaMetadataRetriever]）解析并回写缓存；
   * - 解析结果直接回填到传入的 [Song.Remote] 实例，供列表/封面使用。
   */
  private suspend fun fetchMetaData(songs: List<Song.Remote>) = coroutineScope {
    songs.map { song ->
      async {
        metaSemaphore.withPermit {
          try {
            fetchMetaDataUseCase(song)
          } catch (e: Exception) {
            Timber.w(e, "fetch meta failed: ${song.data}")
          }
        }
      }
    }.awaitAll()
  }

  suspend fun get(id: Long): Song.Remote? {
    resolver.get(id)?.let { return it }
    // 冷启动尚未枚举时，先尝试从缓存库恢复（无需网络）
    loadCached()
    return resolver.get(id)
  }

  /**
   * 按专辑 / 歌手 / 流派**名称**匹配已缓存的远程歌曲。
   * 远程歌曲没有 MediaStore 的 id，专辑 / 歌手 / 流派详情页只能按名称反查。
   */
  suspend fun cachedByAlbum(album: String): List<Song.Remote> =
    loadCached().filter { it.album.equals(album, ignoreCase = true) }

  suspend fun cachedByArtist(artist: String): List<Song.Remote> =
    loadCached().filter { it.artist.equals(artist, ignoreCase = true) }

  suspend fun cachedByGenre(genre: String): List<Song.Remote> =
    loadCached().filter { it.genre.equals(genre, ignoreCase = true) }

  /** 按所在文件夹（url 去掉文件名）匹配远程歌曲，用于无专辑标签的文件夹分组 */
  suspend fun cachedByFolder(folderPath: String): List<Song.Remote> =
    loadCached().filter { it.data.substringBeforeLast('/', "") == folderPath }

  /** 仅查内存表（非挂起），供非挂起上下文（如 [remix.myplayer.repo.SongRepository.song]）使用。 */
  fun getCached(id: Long): Song.Remote? = resolver.get(id)

  private fun Song.Remote.toRemoteSongCache(sourceKey: String, sourceType: Int): RemoteSongCache =
    RemoteSongCache(
      url = data,
      sourceKey = sourceKey,
      sourceType = sourceType,
      account = account,
      pwd = pwd,
      title = title,
      album = album,
      artist = artist,
      duration = duration,
      size = size,
      dateModified = dateModified,
      year = year,
      genre = genre,
      track = track ?: ""
    )
}
