package remix.myplayer.data.db.room.entity

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@kotlinx.serialization.Serializable
@Entity
data class Smb(
  var alias: String,
  var domain: String?,
  var account: String,
  var pwd: String,
  var server: String,
  var share: String,
  var lastUrl: String,
  val createAt: Long = System.currentTimeMillis(),
  /** 音源导入根目录（相对于 [getRoot()] 的共享内路径）。null 表示整个共享。 */
  var rootDir: String? = null,
  /** 是否启用：禁用后不参与曲库枚举，已缓存的歌曲会被清出曲库 */
  var enabled: Boolean = true
) : Serializable {

  @PrimaryKey(autoGenerate = true)
  var id: Int = 0

  fun getRoot(): String {
    val normalizedServer = server.removeSuffix("/")
    val normalizedShare = share.trim { it == '/' || it == '\\' }
    return if (normalizedShare.isEmpty()) {
      normalizedServer
    } else {
      "$normalizedServer/$normalizedShare"
    }
  }

  fun getHost(): String {
    return server.removePrefix("smb://")
  }

  fun getRelativePath(path: String): String {
    val root = getRoot()
    return if (path.startsWith(root)) {
      path.removePrefix(root).trimStart('/')
    } else {
      path.trimStart('/')
    }
  }

  fun buildPathStack(currentUrl: String): List<String> {
    val root = getRoot()
    val current = currentUrl.removeSuffix("/")
    val relative = if (current.startsWith(root)) {
      current.removePrefix(root).trimStart('/')
    } else {
      current.trimStart('/')
    }

    return relative
      .split('/')
      .filter { it.isNotEmpty() }
      .runningFold(root) { acc, part -> "$acc/$part" }
  }

  /**
   * 从 [generateUri] 生成的 smb url 中还原**共享内相对路径**（已解码），
   * 用于删除等需要传给 SMB 客户端的场景。
   * 例：`smb://user:pwd@host/share/a%20b/c.mp3` -> `a b/c.mp3`
   */
  fun relativePathOf(uri: String): String {
    val withoutScheme = uri.removePrefix("smb://")
    // 去掉 userInfo@
    val afterUserInfo = withoutScheme.substringAfter('@', withoutScheme)
    val segments = afterUserInfo.split('/').filter { it.isNotEmpty() }
    // segments[0] = host，segments[1] = share，其余为共享内路径
    return segments.drop(2).joinToString("/") { Uri.decode(it) }
  }

  fun generateUri(path: String): String {
    val relativePath = getRelativePath(path)

    var userInfo = ""
    if (!domain.isNullOrEmpty()) {
      userInfo += "${Uri.encode(domain)};"
    }
    userInfo += Uri.encode(account)
    if (pwd.isNotEmpty()) {
      userInfo += ":${Uri.encode(pwd)}"
    }

    val serverHost = getHost()
    val encodedShare = Uri.encode(share)

    val segments = relativePath.replace('\\', '/').split("/").filter { it.isNotEmpty() }
    val encodedPath = segments.joinToString("/") { Uri.encode(it) }

    return "smb://$userInfo@$serverHost/$encodedShare/$encodedPath"
  }

  companion object {
    fun parseServerAddress(server: String): Pair<String, Int?> {
      var host = server.removePrefix("smb://")
      var port: Int? = null
      if (host.contains(":")) {
        val parts = host.split(":")
        host = parts[0]
        port = parts.getOrNull(1)?.toIntOrNull()
      }
      return host to port
    }
  }
}
