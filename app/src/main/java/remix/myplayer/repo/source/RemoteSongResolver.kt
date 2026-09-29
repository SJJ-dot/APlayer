package remix.myplayer.repo.source

import remix.myplayer.data.model.audio.Song
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内存中的远程歌曲解析表：歌曲稳定 id -> 完整 [Song.Remote]。
 * 由 [RemoteSongLookup] 在枚举远程音源时填充，供收藏 / 历史 / 歌单读取时还原远程歌曲。
 */
@Singleton
class RemoteSongResolver @Inject constructor() {
  private val map = ConcurrentHashMap<Long, Song.Remote>()

  fun put(song: Song.Remote) {
    map[song.id] = song
  }

  fun putAll(songs: List<Song.Remote>) {
    songs.forEach { map[it.id] = it }
  }

  /** 严格对齐：仅保留 [datas] 中的歌曲，移除其余（用于目录切换/音源删除后的内存清理） */
  fun retainByData(datas: Set<String>) {
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
      if (iterator.next().value.data !in datas) {
        iterator.remove()
      }
    }
  }

  /** 按歌曲 url 移除（删除远程歌曲后同步内存解析表） */
  fun removeByData(datas: Collection<String>) {
    if (datas.isEmpty()) {
      return
    }
    val targets = datas.toSet()
    val iterator = map.entries.iterator()
    while (iterator.hasNext()) {
      if (iterator.next().value.data in targets) {
        iterator.remove()
      }
    }
  }

  fun get(id: Long): Song.Remote? = map[id]

  fun snapshot(): List<Song.Remote> = ArrayList(map.values)

  fun clear() = map.clear()
}
