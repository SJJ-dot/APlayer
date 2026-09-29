package remix.myplayer.repo.usecase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import remix.myplayer.data.model.audio.Song
import remix.myplayer.lyric.provider.SearchScorer
import remix.myplayer.request.netease.NetEaseClient
import remix.myplayer.util.NetworkWatcher
import remix.myplayer.util.SearchKeyUtil
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在线（互联网）元数据查询的结果。**严格区分「确实没有」与「网络错误」**：
 * - [Found]：查到匹配项；
 * - [NotExist]：请求成功但没有达到评分阈值的匹配项（真的没有）→ 调用方可以回退读取音源文件；
 * - [Failed]：无网络 / 请求异常 / 接口报错（网络问题）→ 调用方**不应**回退音源，应等网络变化后重试。
 */
sealed interface OnlineMetaLookup {
  data class Found(val meta: NetworkMetaDataLookup.Result) : OnlineMetaLookup
  data object NotExist : OnlineMetaLookup
  data object Failed : OnlineMetaLookup
}

/**
 * 远程（WebDAV / SMB）歌曲的**在线**元数据查询。
 *
 * 走互联网曲库（网易云），**不消耗 WebDAV/SMB 流量**，因此远程歌曲的元数据解析
 * 一律先来这里查，只有确认「在线没有数据」时才回退到读取远程文件解析内嵌标签。
 */
@Singleton
class NetworkMetaDataLookup @Inject constructor(
  private val neClient: NetEaseClient,
  private val networkWatcher: NetworkWatcher
) {

  data class Result(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val coverUrl: String?
  )

  /**
   * 按歌曲名/歌手搜索在线曲库。
   * 无网络直接返回 [OnlineMetaLookup.Failed]（避免白跑一次请求）。
   */
  suspend fun lookup(song: Song.Remote): OnlineMetaLookup = withContext(Dispatchers.IO) {
    if (!networkWatcher.isOnline) {
      return@withContext OnlineMetaLookup.Failed
    }

    val keys = SearchKeyUtil.getSearchKeys(song).take(MAX_SEARCH_KEYS)
    if (keys.isEmpty()) {
      // 歌曲名无效（纯数字等），在线检索没有意义：算「没有数据」，允许回退音源
      return@withContext OnlineMetaLookup.NotExist
    }

    // 只要有一次请求成功返回，就说明网络没问题，最终无命中即视为「确实没有」
    var requestSucceeded = false
    for (key in keys) {
      val candidates = try {
        neClient.searchSongList(key.value).also { requestSucceeded = true }
      } catch (e: Exception) {
        Timber.v(e, "online meta search failed: ${key.value}")
        continue
      }
      if (candidates.isEmpty()) {
        continue
      }
      val best = candidates
        .map { ne ->
          ne to SearchScorer.calculateSongScoreWithKeyKind(
            targetSong = song,
            candidateTitle = ne.name,
            candidateArtist = ne.ar?.joinToString(", ") { it.name ?: "" },
            candidateAlbum = ne.al?.name,
            candidateDuration = ne.dt,
            keyword = key.value,
            keyKind = key.kind
          )
        }
        .filter { it.second.isValid }
        .maxByOrNull { it.second.score }
        ?.first
        ?: continue

      return@withContext OnlineMetaLookup.Found(
        Result(
          title = best.name.orEmpty(),
          artist = best.ar?.joinToString(", ") { it.name.orEmpty() }.orEmpty(),
          album = best.al?.name.orEmpty(),
          duration = best.dt.coerceAtLeast(0L),
          coverUrl = best.al?.picUrl?.takeIf { it.isNotEmpty() }
        )
      )
    }

    if (requestSucceeded) OnlineMetaLookup.NotExist else OnlineMetaLookup.Failed
  }

  companion object {
    /** 搜索关键词数量上限（每个关键词一次在线请求，不产生音源流量） */
    private const val MAX_SEARCH_KEYS = 2
  }
}
