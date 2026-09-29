package remix.myplayer.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.prefs.SettingPrefs
import remix.myplayer.repo.source.RemoteSongLookup
import javax.inject.Inject

/**
 * 统一曲库：聚合所有音源的歌曲，输出单一 [Song] 列表，使本地 / WebDAV / SMB 在歌曲列表、
 * 播放、收藏、历史上拥有一致的体验。
 *
 * 组成：
 * 1. 本地全量扫描（系统 MediaStore，自动扫描 + 手动扫描），可在音源管理页整体禁用
 * 2. 启用的远程音源（WebDAV / SMB，实时递归枚举，失败跳过）
 *
 * Album / Artist / Folder / Genre 等聚合视图在 LibraryViewModel 中合并远程歌曲。
 */
class UnifiedLibraryRepository @Inject constructor(
  private val songRepository: SongRepository,
  private val remoteLookup: RemoteSongLookup,
  private val settingPrefs: SettingPrefs
) {
  /**
   * 本地歌曲：系统 MediaStore 全量；本地音乐被禁用时返回空（不读取 MediaStore）。
   * 不访问网络，速度只受本地数据库影响，可立即返回给 UI。
   */
  suspend fun localSongs(): List<Song> = withContext(Dispatchers.IO) {
    if (!settingPrefs.localMusicEnabled) {
      return@withContext emptyList()
    }
    songRepository.allSongs()
  }

  /**
   * 远程歌曲：直接读持久缓存（[RemoteSongLookup.loadCached]），启动即刻返回，不触网。
   * 实际变化由 [refreshRemote] 在后台增量检查。
   */
  suspend fun remoteSongs(): List<Song> = withContext(Dispatchers.IO) {
    val result = ArrayList<Song>()
    runCatching { result.addAll(remoteLookup.loadCached()) }
    result.distinctBy { it.id }
  }

  /**
   * 后台增量刷新远程歌曲（枚举 + 仅对新增/变更文件解析元数据 + 清理已删除项）。
   * 返回刷新后的完整远程歌曲列表，供 UI 合并刷新。
   */
  suspend fun refreshRemote(): List<Song> = withContext(Dispatchers.IO) {
    val result = ArrayList<Song>()
    runCatching { result.addAll(remoteLookup.refresh()) }
    result.distinctBy { it.id }
  }

  /**
   * 完整曲库：本地 + 远程。调用方会同步等待远程枚举与元数据解析完成，
   * 仅在不关心启动速度或已明确需要完整数据时使用。
   */
  suspend fun allSongs(): List<Song> = withContext(Dispatchers.IO) {
    val result = ArrayList<Song>()
    result.addAll(localSongs())
    result.addAll(remoteSongs())
    // 去重：本地按路径，远程按稳定 id
    result.distinctBy { if (it.isLocal()) it.data else it.id }
  }
}
