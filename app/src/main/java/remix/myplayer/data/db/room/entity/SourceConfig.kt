package remix.myplayer.data.db.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import remix.myplayer.data.model.source.SourceType

/**
 * 统一的音源配置（本地文件夹 / WebDAV / SMB 共用一张表）。
 * 仅保存"配置"，不保存歌曲文件；歌曲由阶段2的 UnifiedLibraryRepository 按 type 递归枚举。
 *
 * - LOCAL：使用 [rootPath]（通过系统目录选择器得到的真实路径）
 * - WEBDAV / SMB：使用 [server]/[account]/[pwd]，SMB 额外用 [share]/[domain]
 */
@Entity(tableName = "SourceConfig")
data class SourceConfig(
  @PrimaryKey(autoGenerate = true)
  var id: Int = 0,
  var type: String,
  var alias: String,
  var enabled: Boolean = true,
  var recursive: Boolean = true,
  var rootPath: String? = null,
  var server: String? = null,
  var share: String? = null,
  var domain: String? = null,
  var account: String? = null,
  var pwd: String? = null,
  var lastUrl: String? = null,
  var createAt: Long = System.currentTimeMillis()
) {
  val sourceType: SourceType
    get() = SourceType.valueOf(type)
}
