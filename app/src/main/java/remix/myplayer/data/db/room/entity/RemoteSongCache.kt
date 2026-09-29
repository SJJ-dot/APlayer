package remix.myplayer.data.db.room.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import remix.myplayer.data.model.audio.Song

/**
 * 远程音源歌曲的持久缓存：保存枚举得到的文件列表 + 解析后的元数据，
 * 使启动时可离线立即展示远程歌曲，仅在后台做增量变化检查（新增/删除/变更）。
 *
 * [url] 即 [Song.Remote.data]，作为主键；[account]/[pwd] 用于离线重建播放鉴权头；
 * [sourceKey] 标识来源（如 `webdav:<server>` / `smb:<server>:<share>`），用于按源做增量清理。
 */
@Entity(tableName = "remote_song_cache", indices = [Index(value = ["sourceKey"])])
data class RemoteSongCache(
  @PrimaryKey
  val url: String,
  val sourceKey: String,
  val sourceType: Int,
  val account: String,
  val pwd: String,
  val title: String,
  val album: String,
  val artist: String,
  val duration: Long,
  val size: Long,
  val dateModified: Long,
  val year: String,
  val genre: String,
  val track: String
) {
  fun toRemoteSong(): Song.Remote {
    return Song.Remote(
      title = title,
      album = album,
      artist = artist,
      duration = duration,
      data = url,
      size = size,
      year = year,
      genre = genre,
      track = track.ifEmpty { null },
      dateModified = dateModified,
      account = account,
      pwd = pwd
    ).apply {
      // 标记为已获取完成，避免重复走网络解析
      metaFetchState.set(2)
    }
  }
}
