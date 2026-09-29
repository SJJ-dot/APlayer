package remix.myplayer.repo.source

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.data.db.room.entity.Smb
import remix.myplayer.data.db.room.entity.WebDav
import remix.myplayer.data.model.audio.Song
import remix.myplayer.data.model.smb.SmbClientDelegate
import remix.myplayer.util.WebDavSardineFactory
import remix.myplayer.util.ext.isAudio
import remix.myplayer.data.model.smb.SmbClientDelegateProvider
import remix.myplayer.data.model.smb.SmbFile
import javax.inject.Inject

/**
 * 递归枚举远程音源（WebDAV / SMB）目录下的音频文件，构造 [Song.Remote]。
 * 失败（网络错误、模块未安装等）由调用方捕获，不影响本地曲库。
 */
class RemoteFileEnumerator @Inject constructor(
  private val delegateProvider: SmbClientDelegateProvider
) {

  suspend fun enumerateWebDav(webDav: WebDav, root: String, recursive: Boolean): List<Song.Remote> =
    withContext(Dispatchers.IO) {
      // 预认证客户端，避免 401 挑战重试在某些服务器上偶发失败
      val sardine = WebDavSardineFactory.create(webDav.account, webDav.pwd)
      val out = ArrayList<Song.Remote>()
      listWebDav(sardine, webDav, root, recursive, out)
      out
    }

  private fun listWebDav(
    sardine: OkHttpSardine,
    webDav: WebDav,
    url: String,
    recursive: Boolean,
    out: MutableList<Song.Remote>
  ) {
    val resources = runCatching { sardine.list(url) }.getOrNull() ?: return
    // 第一项是当前目录自身，跳过
    resources.drop(1).forEach { res ->
      val path = res.path ?: return@forEach
      if (res.isDirectory) {
        if (recursive) {
          listWebDav(sardine, webDav, webDav.generateUrl(path), recursive, out)
        }
      } else if (res.isAudio()) {
        val name = res.name ?: ""
        out.add(
          Song.Remote(
            title = name.substringBeforeLast('.', name),
            album = "",
            artist = "",
            duration = 0L,
            data = webDav.generateUrl(path),
            size = res.contentLength ?: 0L,
            year = "",
            genre = "",
            track = null,
            dateModified = res.creation?.time ?: 0L,
            account = webDav.account,
            pwd = webDav.pwd
          )
        )
      }
    }
  }

  suspend fun enumerateSmb(smb: Smb, root: String, recursive: Boolean): List<Song.Remote> {
    val delegate = delegateProvider.getDelegate() ?: return emptyList()
    val out = ArrayList<Song.Remote>()
    listSmb(delegate, smb, root, recursive, out)
    return out
  }

  private suspend fun listSmb(
    delegate: SmbClientDelegate,
    smb: Smb,
    url: String,
    recursive: Boolean,
    out: MutableList<Song.Remote>
  ) {
    val files = runCatching { delegate.listFiles(smb, url) }.getOrNull() ?: return
    for (f in files) {
      if (f.isDirectory) {
        if (recursive) {
          listSmb(delegate, smb, f.path, recursive, out)
        }
      } else if (f.isAudio) {
        out.add(
          Song.Remote(
            title = f.name.substringBeforeLast('.', f.name),
            album = "",
            artist = "",
            duration = 0L,
            data = smb.generateUri(f.path),
            size = f.size,
            year = "",
            genre = "",
            track = null,
            dateModified = f.lastModified,
            account = smb.account,
            pwd = smb.pwd
          )
        )
      }
    }
  }
}
