package remix.myplayer.repo.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.smb.SmbClientDelegateProvider
import remix.myplayer.repo.SmbRepository
import remix.myplayer.repo.WebDavRepository
import remix.myplayer.util.WebDavSardineFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除远程（WebDAV / SMB）音源上的真实文件，供统一的「删除源文件」流程使用。
 *
 * 来源判定优先用缓存表的 sourceKey 反查，避免解析 smb url 中的编码信息。
 * 单个文件删除失败只记录日志并跳过，不影响其他歌曲的删除。
 */
@Singleton
class RemoteFileDeleter @Inject constructor(
  private val webDavRepository: WebDavRepository,
  private val smbRepository: SmbRepository,
  private val remoteSongLookup: RemoteSongLookup,
  private val delegateProvider: SmbClientDelegateProvider
) {

  /**
   * 删除远端文件，返回**成功删除**的歌曲 url；失败项会被调用方加入移除黑名单。
   */
  suspend fun delete(songs: List<Song.Remote>): List<String> = withContext(Dispatchers.IO) {
    val deleted = ArrayList<String>(songs.size)
    songs.forEach { song ->
      val success = runCatching { deleteOne(song) }
        .onFailure { Timber.w(it, "delete remote file failed: ${song.data}") }
        .getOrDefault(false)
      if (success) {
        deleted.add(song.data)
      }
    }
    deleted
  }

  private suspend fun deleteOne(song: Song.Remote): Boolean {
    val sourceKey = remoteSongLookup.sourceKeyOf(song.data)
    return when {
      sourceKey != null && sourceKey.startsWith(SMB_PREFIX) -> deleteSmb(sourceKey, song)
      sourceKey != null && sourceKey.startsWith(WEBDAV_PREFIX) -> deleteWebDav(song)
      song.data.startsWith("smb://", ignoreCase = true) -> deleteSmb(null, song)
      else -> deleteWebDav(song)
    }
  }

  private suspend fun deleteWebDav(song: Song.Remote): Boolean {
    val (account, pwd) = webDavCredentials(song)
    if (account.isBlank()) {
      Timber.w("no webdav credentials for: ${song.data}")
      return false
    }
    WebDavSardineFactory.create(account, pwd).delete(song.data)
    return true
  }

  /** 远程歌曲自身携带凭据；缺失时按 server 前缀反查配置 */
  private suspend fun webDavCredentials(song: Song.Remote): Pair<String, String> {
    if (song.account.isNotBlank()) {
      return song.account to song.pwd
    }
    val config = runCatching {
      webDavRepository.allWebDav().first()
        .firstOrNull { song.data.startsWith(it.getRoot(), ignoreCase = true) }
    }.getOrNull()
    return (config?.account ?: "") to (config?.pwd ?: "")
  }

  private suspend fun deleteSmb(sourceKey: String?, song: Song.Remote): Boolean {
    val delegate = delegateProvider.getDelegate() ?: return false
    val smb = runCatching { smbRepository.allSmb().first() }.getOrNull()
      ?.firstOrNull { config ->
        if (sourceKey != null) {
          sourceKey == "$SMB_PREFIX${config.server}:${config.share}"
        } else {
          song.data.contains("/${config.share}/", ignoreCase = true)
        }
      } ?: return false
    delegate.delete(smb, smb.relativePathOf(song.data))
    return true
  }

  private companion object {
    const val WEBDAV_PREFIX = "webdav:"
    const val SMB_PREFIX = "smb:"
  }
}
