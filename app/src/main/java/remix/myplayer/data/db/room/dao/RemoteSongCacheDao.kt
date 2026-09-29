package remix.myplayer.data.db.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import remix.myplayer.data.db.room.entity.RemoteSongCache

@Dao
interface RemoteSongCacheDao {

  @Query("SELECT * FROM remote_song_cache")
  suspend fun getAll(): List<RemoteSongCache>

  @Query("SELECT * FROM remote_song_cache WHERE sourceKey = :sourceKey")
  suspend fun getBySourceKey(sourceKey: String): List<RemoteSongCache>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(songs: List<RemoteSongCache>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(song: RemoteSongCache)

  @Query("SELECT * FROM remote_song_cache WHERE url = :url")
  suspend fun getByUrl(url: String): RemoteSongCache?

  @Query("DELETE FROM remote_song_cache WHERE url = :url")
  suspend fun delete(url: String)

  @Query("DELETE FROM remote_song_cache WHERE url IN (:urls)")
  suspend fun deleteByUrls(urls: List<String>)

  @Query("DELETE FROM remote_song_cache WHERE sourceKey = :sourceKey AND url NOT IN (:urls)")
  suspend fun deleteNotIn(sourceKey: String, urls: List<String>)

  @Query("DELETE FROM remote_song_cache WHERE sourceKey = :sourceKey")
  suspend fun deleteBySourceKey(sourceKey: String)
}
