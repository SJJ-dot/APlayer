package remix.myplayer.repo.source

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.smb.SmbException
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
 * 远程目录枚举失败。[alias] 为出错的音源别名，供上层提示「加载失败和原因」。
 *
 * 调用方据此区分处理：
 * - [Failed] / [ModuleMissing]：**本次无法确认远端内容**，必须跳过该音源的缓存 diff，
 *   保留本地已缓存的歌曲列表（不清空），仅提示失败原因；
 * - [AuthFailed]：凭据已不可用，应清空该音源的缓存数据。
 */
sealed class RemoteEnumerationException(
  val alias: String,
  message: String?,
  cause: Throwable?
) : Exception(message, cause) {

  /** SMB 动态模块未安装，无法枚举 */
  class ModuleMissing(alias: String) :
    RemoteEnumerationException(alias, "SMB module not installed", null)

  /** 登录 / 鉴权失败（账号密码错误、账号被禁用、无权访问等）：凭据已不可用，应清空该音源的数据 */
  class AuthFailed(alias: String, cause: Throwable) :
    RemoteEnumerationException(alias, cause.localizedMessage, cause)

  /** 网络 / 超时 / 服务器错误等底层异常：远端内容未知，应保留该音源的缓存 */
  class Failed(alias: String, cause: Throwable) :
    RemoteEnumerationException(alias, cause.localizedMessage, cause)
}

/**
 * 把底层异常归类为 [RemoteEnumerationException]：
 * - 登录 / 鉴权失败 → [RemoteEnumerationException.AuthFailed]（上层清空该音源数据）
 * - 其余（网络 / 超时 / 服务器错误等）→ [RemoteEnumerationException.Failed]（上层保留缓存）
 */
private fun Throwable.toEnumerationException(alias: String): RemoteEnumerationException =
  if (isAuthFailure()) {
    RemoteEnumerationException.AuthFailed(alias, this)
  } else {
    RemoteEnumerationException.Failed(alias, this)
  }

/** 判定异常链中是否存在「登录 / 鉴权失败」：WebDAV 返回 401；SMB 抛出登录失败 / 账号禁用 / 无权限 */
private fun Throwable.isAuthFailure(): Boolean {
  var current: Throwable? = this
  var depth = 0
  while (current != null && depth < 8) {
    when (current) {
      is SardineException -> if (current.statusCode == 401) return true
      is SmbException -> if (current.isAuthFailed) return true
    }
    current = current.cause
    depth++
  }
  return false
}

/**
 * 递归枚举远程音源（WebDAV / SMB）目录下的音频文件，构造 [Song.Remote]。
 *
 * 枚举失败（网络错误、认证失败、模块未安装等）抛出 [RemoteEnumerationException]，
 * 由调用方捕获后保留缓存并提示原因；不再“静默返回空列表”，避免空结果被当成
 * “远端已无文件”而误清本地缓存。
 */
class RemoteFileEnumerator @Inject constructor(
  private val delegateProvider: SmbClientDelegateProvider
) {

  suspend fun enumerateWebDav(webDav: WebDav, root: String, recursive: Boolean): List<Song.Remote> =
    withContext(Dispatchers.IO) {
      try {
        // 预认证客户端，避免 401 挑战重试在某些服务器上偶发失败
        val sardine = WebDavSardineFactory.create(webDav.account, webDav.pwd)
        val out = ArrayList<Song.Remote>()
        listWebDav(sardine, webDav, root, recursive, out)
        out
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw e.toEnumerationException(webDav.alias)
      }
    }

  private fun listWebDav(
    sardine: OkHttpSardine,
    webDav: WebDav,
    url: String,
    recursive: Boolean,
    out: MutableList<Song.Remote>
  ) {
    // 任一层目录 list 失败都直接抛出：宁可整源跳过 diff，也不把本地缓存误判为“远端已删除”
    val resources = sardine.list(url)
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
    return try {
      val delegate = delegateProvider.getDelegate()
        ?: throw RemoteEnumerationException.ModuleMissing(smb.alias)
      val out = ArrayList<Song.Remote>()
      listSmb(delegate, smb, root, recursive, out)
      out
    } catch (e: CancellationException) {
      throw e
    } catch (e: RemoteEnumerationException) {
      throw e
    } catch (e: Exception) {
      throw e.toEnumerationException(smb.alias)
    }
  }

  private suspend fun listSmb(
    delegate: SmbClientDelegate,
    smb: Smb,
    url: String,
    recursive: Boolean,
    out: MutableList<Song.Remote>
  ) {
    val files = delegate.listFiles(smb, url)
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
