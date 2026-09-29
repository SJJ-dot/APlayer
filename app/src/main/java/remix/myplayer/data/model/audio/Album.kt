package remix.myplayer.data.model.audio

import android.content.ContentUris
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import java.io.Serial

/**
 * Created by Remix on 2017/10/22.
 */

@Serializable
data class Album(val albumID: Long,
                 val album: String,
                 val artistID: Long,
                 val artist: String,
                 var count: Int = 0,
                 /**
                  * 专辑名为空时按文件夹分组的文件夹路径（本地为绝对路径 / 远程为 url 目录部分）；
                  * 非空表示这是一个「无专辑标签」的文件夹分组，详情页据此查询歌曲。
                  */
                 val folderPath: String? = null) : APlayerModel {
  val artUri: Uri
    get() = ContentUris.withAppendedId("content://media/external/audio/albumart/".toUri(), albumID)

  override fun getKey(): String {
    return albumID.toString()
  }

  companion object {
    @Serial
    private const val serialVersionUID: Long = 1214925651243602701L
  }
}
