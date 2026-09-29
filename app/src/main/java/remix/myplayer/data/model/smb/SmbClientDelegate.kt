package remix.myplayer.data.model.smb

import remix.myplayer.data.db.room.entity.Smb
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface SmbClientDelegate {

  suspend fun listFiles(smb: Smb, url: String): List<SmbFile>
  suspend fun checkConnection(smb: Smb)

  /**
   * 删除共享内的文件，[relativePath] 为共享内相对路径（如 `Music/a.mp3`，不带 share 前缀）。
   * 供「删除源文件」统一删除流程使用。
   */
  suspend fun delete(smb: Smb, relativePath: String)
}

@Singleton
class SmbClientDelegateProvider @Inject constructor() {

  private var cachedDelegate: SmbClientDelegate? = null

  fun getDelegate(): SmbClientDelegate? {
    if (cachedDelegate != null) return cachedDelegate

    return try {
      val clazz = Class.forName("remix.myplayer.smb.SmbClientDelegateImpl")
      cachedDelegate = clazz.getDeclaredConstructor().newInstance() as SmbClientDelegate
      cachedDelegate
    } catch (e: Exception) {
      Timber.w(e, "Failed to load SmbClientDelegateImpl (module might not be installed)")
      null
    }
  }
}