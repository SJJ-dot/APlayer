package remix.myplayer.helper

import android.net.Uri
import remix.myplayer.data.model.audio.Song

/**
 * 「专辑名为空」时的文件夹分组规则：本地文件与远程（WebDAV / SMB）文件统一按所在文件夹区分，
 * 使没有专辑标签的歌曲也能像有标签一样分开显示，而不是本地多条、远程一条（或全部混在一起）。
 */
object FolderAlbumGrouping {

  /** 歌曲所在文件夹：本地为绝对路径，远程为 url 去掉文件名后的部分 */
  fun folderPathOf(song: Song): String = folderPathOf(song.data)

  fun folderPathOf(data: String): String = data.substringBeforeLast('/', "")

  /** 文件夹展示名（最后一级，远程做一次 url 解码） */
  fun displayNameOf(folderPath: String): String {
    val name = folderPath.trimEnd('/').substringAfterLast('/')
    return runCatching { Uri.decode(name) }.getOrDefault(name)
  }

  /** 文件夹分组用的稳定负 id（不与本地 MediaStore 正 id 冲突） */
  fun idOf(folderPath: String): Long = Song.stableRemoteId("folder:$folderPath")
}
