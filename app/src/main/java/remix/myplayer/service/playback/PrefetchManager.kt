package remix.myplayer.service.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.audio.Song
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预取管理器：当前曲目开始播放后，把播放队列的下一首远程歌曲提前拉取进 [MediaCache]，
 * 与 ExoPlayer 共用同一缓存 key（contentUri），从而实现“当前曲缓存完成后，下一首已在缓存中”。
 *
 * 本地歌曲无需预取（本机读取），仅对 [Song.Remote] 生效。
 */
@Singleton
class PrefetchManager @Inject constructor() {
  companion object {
    private const val PREFETCH_LIMIT = 8L * 1024 * 1024 // 仅预取前 8MB，避免浪费带宽
  }

  suspend fun prefetch(context: Context, song: Song) = withContext(Dispatchers.IO) {
    if (song !is Song.Remote) return@withContext
    val cache = MediaCache.get(context)
    val key = song.contentUri.toString()
    if (cache.isCached(key, 0, 1)) return@withContext

    val upstreamFactory = if (song.contentUri.scheme == "smb") {
      SmbDataSourceFactory()
    } else {
      DefaultHttpDataSource.Factory().setDefaultRequestProperties(song.headers)
    }
    val cacheFactory = CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(upstreamFactory)
    val dataSource = cacheFactory.createDataSource()
    val dataSpec = DataSpec.Builder().setUri(song.contentUri).build()
    try {
      dataSource.open(dataSpec)
      val buf = ByteArray(64 * 1024)
      var total = 0L
      while (total < PREFETCH_LIMIT) {
        val n = dataSource.read(buf, 0, buf.size)
        if (n == C.RESULT_END_OF_INPUT || n <= 0) break
        total += n
      }
    } catch (e: IOException) {
      Timber.w(e, "prefetch failed: $key")
    } finally {
      try {
        dataSource.close()
      } catch (_: Exception) {
      }
    }
  }
}
