package remix.myplayer.glide

import android.net.Uri
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.GlideException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * created by Remix on 2021/4/27
 */
class EmbeddedFetcher(private val fileUri: Uri) : DataFetcher<InputStream> {
  private var stream: InputStream? = null

  override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
    // 远程（WebDAV）封面：url 被编码进 path（embedded:///<encoded url>），这里还原
    val rawPath = fileUri.path ?: ""
    val path = if (rawPath.startsWith("/http", ignoreCase = true)) {
      Uri.decode(rawPath.drop(1))
    } else {
      rawPath
    }
    try {
      // 与 UriFetcher 的存在性判断共用同一份解析结果，避免重复解析文件
      val bytes = EmbeddedCoverCache.cachedArtOf(path)
      stream = if (bytes != null) {
        ByteArrayInputStream(bytes)
      } else {
        AudioFileCoverUtils.fallback(path)
      }
      callback.onDataReady(stream)
    } catch (e: Exception) {
      callback.onLoadFailed(GlideException(e.message, e))
    }
  }

  override fun cleanup() {
    try {
      stream?.close()
    } catch (ignore: IOException) {
    }
  }

  override fun cancel() {

  }

  override fun getDataClass(): Class<InputStream> {
    return InputStream::class.java
  }

  override fun getDataSource(): DataSource {
    return DataSource.LOCAL
  }
}
