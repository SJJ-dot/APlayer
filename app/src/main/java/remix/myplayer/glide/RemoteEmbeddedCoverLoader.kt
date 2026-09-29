package remix.myplayer.glide

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import okhttp3.Request
import remix.myplayer.util.WebDavSardineFactory
import timber.log.Timber

/**
 * 远程（WebDAV）内嵌封面加载器。
 *
 * 为什么不能直接让系统解码器读 http url：WebDAV（如坚果云）的文件地址需要 Basic 认证，
 * 而 [MediaMetadataRetriever] 发出的请求不带认证头，会因 401 解析失败
 * （表现为：同一首歌下载到本地就有封面，远程始终没有）。
 *
 * 这里改为**自己带着认证**用 Range 请求拉取文件头部（默认最多 4MB），
 * 写入临时文件后用系统解码器解析封面，解析完删除临时文件，结果进入 [EmbeddedCoverCache] 缓存。
 */
object RemoteEmbeddedCoverLoader {

  /**
   * 每个文件最多拉取的字节数。
   * 内嵌封面（ID3v2 APIC）几乎都在文件开头几百 KB 内，限制小一些能显著减少流量，
   * 且服务器忽略 Range 时也只会读这么多。
   */
  private const val MAX_FETCH_BYTES = 512L * 1024

  /**
   * 读取内嵌封面。[url] 为歌曲完整地址，[account]/[pwd] 为 WebDAV 凭据。
   * [isCancelled] 为 true 时提前中止（例如更高优先级的来源已经取到封面，避免白下载）。
   */
  fun load(
    context: Context,
    url: String,
    account: String,
    pwd: String,
    isCancelled: () -> Boolean = { false }
  ): EmbeddedResult {
    EmbeddedCoverCache.init(context)
    // 命中缓存（内存 / 磁盘，含“确认无封面”）→ 不再下载
    EmbeddedCoverCache.cachedResultOf(url)?.let { return it }

    val tempFile = try {
      File.createTempFile("cover_", ".tmp", context.cacheDir)
    } catch (e: Exception) {
      Timber.v(e, "create temp file failed")
      return EmbeddedResult.Failed
    }

    return try {
      val client = WebDavSardineFactory.createClient(account, pwd)
      val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-${MAX_FETCH_BYTES - 1}")
        .build()

      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          // 404/410 说明文件确实不存在；401 等属于失败，下次重试
          return when (response.code) {
            404, 410 -> EmbeddedResult.NotExist.also { EmbeddedCoverCache.cacheNotExist(url) }
            else -> {
              Timber.w("fetch remote cover failed: ${response.code} $url")
              EmbeddedResult.Failed
            }
          }
        }
        response.body?.byteStream()?.use { input ->
          FileOutputStream(tempFile).use { output ->
            // 只读取需要的部分（服务器忽略 Range 时也只取前 MAX_FETCH_BYTES）
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = MAX_FETCH_BYTES
            while (remaining > 0) {
              if (isCancelled()) {
                return EmbeddedResult.Failed
              }
              val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
              if (read <= 0) break
              output.write(buffer, 0, read)
              remaining -= read
            }
          }
        } ?: return EmbeddedResult.Failed
      }

      when (val bytes = readEmbeddedPicture(tempFile.absolutePath)) {
        null -> EmbeddedResult.NotExist.also { EmbeddedCoverCache.cacheNotExist(url) }
        else -> EmbeddedResult.Found(bytes).also { EmbeddedCoverCache.cacheFound(url, bytes) }
      }
    } catch (e: Exception) {
      // 网络异常 / 解析异常：不缓存，下次重试
      Timber.v(e, "load remote embedded cover failed: $url")
      EmbeddedResult.Failed
    } finally {
      runCatching { tempFile.delete() }
    }
  }

  private fun readEmbeddedPicture(path: String): ByteArray? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(path)
      retriever.embeddedPicture
    } catch (e: Exception) {
      null
    } finally {
      runCatching { retriever.release() }
    }
  }
}
