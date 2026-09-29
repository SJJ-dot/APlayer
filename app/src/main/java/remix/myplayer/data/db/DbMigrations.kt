package remix.myplayer.data.db

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import remix.myplayer.data.db.room.entity.WebDav

internal object DbMigrations {

  val migration3to4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("ALTER TABLE `PlayQueue` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE `PlayQueue` ADD COLUMN `data` TEXT NOT NULL DEFAULT ''")
      db.execSQL("ALTER TABLE `PlayQueue` ADD COLUMN `account` TEXT")
      db.execSQL("ALTER TABLE `PlayQueue` ADD COLUMN `pwd` TEXT")

      db.execSQL("CREATE TABLE IF NOT EXISTS `WebDav` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alias` TEXT NOT NULL, `account` TEXT, `pwd` TEXT, `server` TEXT NOT NULL, `lastPath` TEXT, `createAt` INTEGER NOT NULL)")
    }
  }

  val migration4to5 = object : Migration(4, 5) {
    @SuppressLint("Range")
    override fun migrate(db: SupportSQLiteDatabase) {
      val temp = ArrayList<WebDav>()
      val cursor = db.query("select * from `Webdav`")
      while (cursor.moveToNext()) {
        temp.add(
          WebDav(
            cursor.getString(cursor.getColumnIndex("alias")),
            cursor.getString(cursor.getColumnIndex("account")),
            cursor.getString(cursor.getColumnIndex("pwd")),
            cursor.getString(cursor.getColumnIndex("server")),
            cursor.getString(cursor.getColumnIndex("server")),
            cursor.getLong(cursor.getColumnIndex("createAt"))
          ).apply {
            id = cursor.getInt(cursor.getColumnIndex("id"))
          })
      }
      print(temp)
      db.execSQL("DROP TABLE `WebDav`")
      db.execSQL("CREATE TABLE IF NOT EXISTS `WebDav` (`alias` TEXT NOT NULL, `account` TEXT NOT NULL, `pwd` TEXT NOT NULL, `server` TEXT NOT NULL, `lastUrl` TEXT NOT NULL, `createAt` INTEGER NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
      temp.forEach { webDav ->
        db.insert("WebDav", SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply {
          put("alias", webDav.alias)
          put("account", webDav.account)
          put("pwd", webDav.pwd)
          put("server", webDav.server)
          put("lastUrl", webDav.lastUrl)
          put("createAt", webDav.createAt)
          put("id", webDav.id)
        })
      }
    }
  }

  val migration5to6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS `MetaDataCache` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL, `album` TEXT NOT NULL, `duration` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `lastModified` INTEGER NOT NULL, `year` TEXT NOT NULL, `genre` TEXT NOT NULL, `track` TEXT NOT NULL, `updateTime` INTEGER NOT NULL, PRIMARY KEY (`url`))")
    }
  }

  val migration6to7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL("CREATE TABLE IF NOT EXISTS `Smb` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `alias` TEXT NOT NULL, `domain` TEXT, `account` TEXT NOT NULL, `pwd` TEXT NOT NULL, `server` TEXT NOT NULL, `share` TEXT NOT NULL, `lastUrl` TEXT NOT NULL, `createAt` INTEGER NOT NULL)")
    }
  }

  val migration7to8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS `SourceConfig` (" +
          "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
          "`type` TEXT NOT NULL, `alias` TEXT NOT NULL, " +
          "`enabled` INTEGER NOT NULL DEFAULT 1, `recursive` INTEGER NOT NULL DEFAULT 1, " +
          "`rootPath` TEXT, `server` TEXT, `share` TEXT, `domain` TEXT, " +
          "`account` TEXT, `pwd` TEXT, `lastUrl` TEXT, `createAt` INTEGER NOT NULL)"
      )
    }
  }

  val migration8to9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
      db.execSQL(
        "CREATE TABLE IF NOT EXISTS `remote_song_cache` (" +
          "`url` TEXT NOT NULL, `sourceKey` TEXT NOT NULL, `sourceType` INTEGER NOT NULL, " +
          "`account` TEXT NOT NULL, `pwd` TEXT NOT NULL, `title` TEXT NOT NULL, " +
          "`album` TEXT NOT NULL, `artist` TEXT NOT NULL, `duration` INTEGER NOT NULL, " +
          "`size` INTEGER NOT NULL, `dateModified` INTEGER NOT NULL, `year` TEXT NOT NULL, " +
          "`genre` TEXT NOT NULL, `track` TEXT NOT NULL, PRIMARY KEY (`url`))"
      )
      db.execSQL("CREATE INDEX IF NOT EXISTS `index_remote_song_cache_sourceKey` ON `remote_song_cache` (`sourceKey`)")
    }
  }

  val migration9to10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
      // 旧代码会把空 album/artist 写进 MetaDataCache，导致新版始终命中缓存、不再网络解析
      db.execSQL("DELETE FROM MetaDataCache WHERE artist = '' AND album = '' AND (url LIKE 'http%' OR url LIKE 'smb%')")
      // 同时清理可能已写入 remote_song_cache 的空元数据，强制下次刷新重新解析
      db.execSQL("DELETE FROM remote_song_cache WHERE artist = '' AND album = ''")
    }
  }

  val migration10to11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
      // 远程音源支持指定导入根目录，null 表示整个服务器/共享
      db.execSQL("ALTER TABLE `WebDav` ADD COLUMN `rootDir` TEXT")
      db.execSQL("ALTER TABLE `Smb` ADD COLUMN `rootDir` TEXT")
    }
  }

  val migration11to12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
      // 音源支持禁用：禁用后不参与枚举与曲库
      db.execSQL("ALTER TABLE `WebDav` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
      db.execSQL("ALTER TABLE `Smb` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
    }
  }

  val migration12to13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
      // 远程元数据改为「文件标签优先，在线结果只补空」。
      // 旧版"在线优先"会把 title/artist/album 覆盖成在线匹配结果（可能命中同名不同版本），
      // 导致列表元数据错乱、歌词抓到别的版本。清掉远程元数据缓存与远程歌曲缓存，
      // 让其按新策略重新解析（收藏/历史用到的负 id 由 URL 决定，重新枚举后不变）。
      db.execSQL("DELETE FROM MetaDataCache WHERE url LIKE 'http%' OR url LIKE 'smb%'")
      db.execSQL("DELETE FROM remote_song_cache")
    }
  }
}