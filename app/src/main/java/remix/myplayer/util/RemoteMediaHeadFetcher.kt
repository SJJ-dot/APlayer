package remix.myplayer.util

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 只拉取远程音源文件的**头部**（Range 请求），供元数据解析与内嵌封面解析使用。
 *
 * 为什么需要：直接把 http url 交给 [android.media.MediaMetadataRetriever] 时，
 * 系统实现会顺序读取整个文件（不使用 Range），首次导入远程曲库会产生与曲库同等大小的流量。
 * 这里改为自己带认证只拉前 N 字节写入临时文件，交给解码器解析本地临时文件。
 *
 * 服务器忽略 Range（返回 200）时同样安全：我们读满 [maxBytes] 即主动关闭连接，不会下载整文件。
 */
object RemoteMediaHeadFetcher {

  /** 元数据解析默认拉取大小（ID3v2 标签与 Xing 头都在文件开头） */
  const val META_HEAD_BYTES = 1024L * 1024

  /** 内嵌封面拉取大小（APIC 一般在前几百 KB 内） */
  const val COVER_HEAD_BYTES = 512L * 1024

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20L, TimeUnit.SECONDS)
      .readTimeout(20L, TimeUnit.SECONDS)
      .build()
  }

  /**
   * 拉取文件头部到临时文件，失败返回 null。调用方负责删除返回的文件。
   */
  fun fetch(
    context: Context,
    url: String,
    headers: Map<String, String>,
    maxBytes: Long = META_HEAD_BYTES,
    isCancelled: () -> Boolean = { false }
  ): File? {
    if (maxBytes <= 0) {
      return null
    }
    val tempFile = try {
      File.createTempFile("remote_head_", ".tmp", context.cacheDir)
    } catch (e: Exception) {
      Timber.v(e, "create head temp file failed")
      return null
    }

    return try {
      val request = Request.Builder()
        .url(url)
        .header("Range", "bytes=0-${maxBytes - 1}")
        .apply { headers.forEach { (key, value) -> header(key, value) } }
        .build()

      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          Timber.w("fetch head failed: ${response.code} $url")
          tempFile.delete()
          return null
        }
        val body = response.body ?: run {
          tempFile.delete()
          return null
        }
        body.byteStream().use { input ->
          FileOutputStream(tempFile).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = maxBytes
            while (remaining > 0) {
              if (isCancelled()) {
                tempFile.delete()
                return null
              }
              val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
              if (read <= 0) break
              output.write(buffer, 0, read)
              remaining -= read
            }
          }
        }
      }
      tempFile
    } catch (e: Exception) {
      // 网络/读取失败：不缓存，下次重试
      Timber.v(e, "fetch head failed: $url")
      runCatching { tempFile.delete() }
      null
    }
  }
}
