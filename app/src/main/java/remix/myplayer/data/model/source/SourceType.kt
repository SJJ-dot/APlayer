package remix.myplayer.data.model.source

/**
 * 音源类型。LOCAL=本地文件夹（受管根目录），WEBDAV/SMB=远程。
 * 与 [remix.myplayer.data.db.room.entity.SourceConfig.type] 对应。
 */
enum class SourceType {
  LOCAL,
  WEBDAV,
  SMB
}
