package remix.myplayer.glide

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/** 内嵌封面解析结果：区分「确认没有封面」与「读取/解析失败」 */
sealed interface EmbeddedResult {
  data class Found(val bytes: ByteArray) : EmbeddedResult

  /** 文件确实没有内嵌封面（可标记为不存在） */
  data object NotExist : EmbeddedResult

  /** 读取或解析失败（文件被占用、远程读取失败、超时等）：**不缓存**，下次重试 */
  data object Failed : EmbeddedResult
}

/**
 * 内嵌封面缓存（内存 + 磁盘）：同一文件只解析/下载一次。
 *
 * - 内存 LRU：避免同一会话内重复解析；
 * - **磁盘缓存**：把解析出的封面字节写到 `cacheDir/embed_cover/`，进程重启后直接命中，
 *   远程（WebDAV）封面因此不再需要重复拉取文件头部（这才是真正省流量的地方）；
 * - [NotExist] 做内存负缓存（避免反复解析无封面的文件）；
 * - [Failed] 不缓存，保证下次（启动 / 刷新 / 网络变化）能重新尝试。
 */
object EmbeddedCoverCache {

  /** 缓存上限（字节），超出后按 LRU 淘汰；封面一般 50~500KB */
  private const val MAX_CACHE_BYTES = 6 * 1024 * 1024

  /** 负缓存成本，仅用于让 LRU 也能淘汰「已确认无封面」的条目 */
  private const val NEGATIVE_CACHE_WEIGHT = 4 * 1024

  private const val DISK_DIR_NAME = "embed_cover"

  /** 内嵌封面磁盘缓存上限 */
  private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

  private val NO_ART = Any()

  private var currentSize = 0

  @Volatile
  private var diskDir: File? = null

  private val cache = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
      currentSize > MAX_CACHE_BYTES
  }

  /** 由 [UriFetcher] 在初始化时调用，指定磁盘缓存目录 */
  fun init(context: Context) {
    if (diskDir == null) {
      synchronized(this) {
        if (diskDir == null) {
          diskDir = File(context.cacheDir, DISK_DIR_NAME).apply { runCatching { mkdirs() } }
        }
      }
    }
  }

  /** 解析（或复用内存/磁盘缓存）内嵌封面 */
  @Synchronized
  fun resultOf(path: String): EmbeddedResult {
    cachedResultOf(path)?.let { return it }

    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(path)
      val bytes = retriever.embeddedPicture ?: return EmbeddedResult.NotExist.also {
        cacheNotExist(path)
      }
      cacheFound(path, bytes)
      return EmbeddedResult.Found(bytes)
    } catch (e: Exception) {
      // 读取/解析失败：不缓存，下次重试
      Timber.v(e, "read embedded cover failed: $path")
      return EmbeddedResult.Failed
    } finally {
      runCatching { retriever.release() }
    }
  }

  /** 仅取内存或磁盘中已有的结论；都没有返回 null（不触发解析/下载） */
  @Synchronized
  fun cachedResultOf(path: String): EmbeddedResult? {
    when (val cached = cache[path]) {
      is ByteArray -> return EmbeddedResult.Found(cached)
      NO_ART -> return EmbeddedResult.NotExist
      else -> Unit
    }
    val disk = readDisk(path) ?: return null
    // 回填内存
    currentSize += disk.size
    cache[path] = disk
    return EmbeddedResult.Found(disk)
  }

  /** 供 [EmbeddedFetcher] 使用：内存 → 磁盘，取不到返回 null */
  @Synchronized
  fun cachedArtOf(path: String): ByteArray? = (cachedResultOf(path) as? EmbeddedResult.Found)?.bytes

  /** 写入成功结果（内存 + 磁盘） */
  @Synchronized
  fun cacheFound(key: String, bytes: ByteArray) {
    if (cache[key] is ByteArray) {
      return
    }
    currentSize += bytes.size
    cache[key] = bytes
    writeDisk(key, bytes)
  }

  @Synchronized
  fun cacheNotExist(key: String) {
    if (cache.containsKey(key)) {
      return
    }
    currentSize += NEGATIVE_CACHE_WEIGHT
    cache[key] = NO_ART
  }

  /** 清空内存缓存（磁盘缓存由 [clearDisk] 单独清理） */
  @Synchronized
  fun clear() {
    cache.clear()
    currentSize = 0
  }

  /** 清空磁盘缓存 */
  fun clearDisk() {
    diskDir?.listFiles()?.forEach { file -> runCatching { file.delete() } }
  }

  // ---------------- 磁盘读写 ----------------

  private fun diskFile(key: String): File? {
    val dir = diskDir ?: return null
    return File(dir, md5(key) + ".img")
  }

  private fun readDisk(key: String): ByteArray? {
    val file = diskFile(key) ?: return null
    if (!file.exists()) {
      return null
    }
    return runCatching { file.readBytes() }
      .onFailure { Timber.v(it, "read embedded cover disk cache failed") }
      .getOrNull()
  }

  private fun writeDisk(key: String, bytes: ByteArray) {
    val dir = diskDir ?: return
    if (!dir.exists() && !dir.mkdirs()) {
      return
    }
    val file = File(dir, md5(key) + ".img")
    if (file.exists()) {
      return
    }
    runCatching { file.writeBytes(bytes) }
      .onFailure { Timber.v(it, "write embedded cover disk cache failed") }
    trimDisk(dir)
  }

  /** 控制磁盘缓存总量，超出后删除最旧的文件（避免远程封面无限占用空间） */
  private fun trimDisk(dir: File) {
    runCatching {
      val files = dir.listFiles() ?: return
      var total = files.sumOf { it.length() }
      if (total <= DISK_CACHE_BYTES) {
        return
      }
      files.sortedBy { it.lastModified() }.forEach { file ->
        if (total <= DISK_CACHE_BYTES) {
          return@forEach
        }
        val length = file.length()
        if (file.delete()) {
          total -= length
        }
      }
    }.onFailure { Timber.v(it, "trim embedded cover disk cache failed") }
  }

  private fun md5(value: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }
}
