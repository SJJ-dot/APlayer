package remix.myplayer.glide

import java.util.concurrent.ConcurrentHashMap

/**
 * 记录「某个封面来源已确认不存在」的状态。
 *
 * 关键约定：
 * - **只有确认封面不存在**（例如 MediaStore 里确实没有、文件确实没有内嵌封面、联网搜索确实没有结果）才标记；
 * - 网络错误、解析异常、超时等**不标记**，因此下次展示（启动 / 列表刷新 / 网络变化后重新加载）会重新尝试；
 * - 状态仅存内存，进程重启后自然失效 → 相当于启动即重试。
 */
object CoverSourceState {

  enum class Source {
    /** 本地 MediaStore 专辑封面 */
    MEDIA_STORE,

    /** 联网搜索下载（网易云 / LastFM） */
    NETWORK,

    /** 文件内嵌封面解析 */
    EMBEDDED
  }

  private val notExist = ConcurrentHashMap<String, MutableSet<Source>>()

  /** 标记某来源确认不存在 */
  fun markNotExist(key: String, source: Source) {
    notExist.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }.add(source)
  }

  fun isNotExist(key: String, source: Source): Boolean = notExist[key]?.contains(source) == true

  fun clear(key: String) {
    notExist.remove(key)
  }

  fun clearAll() {
    notExist.clear()
  }
}
