package remix.myplayer.data.db.room.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import remix.myplayer.data.db.room.entity.SourceConfig

@Dao
interface SourceConfigDao {

  @Query("SELECT * FROM SourceConfig ORDER BY createAt ASC")
  fun all(): Flow<List<SourceConfig>>

  @Query("SELECT * FROM SourceConfig WHERE enabled = 1 ORDER BY createAt ASC")
  fun enabled(): Flow<List<SourceConfig>>

  @Query("SELECT * FROM SourceConfig WHERE type = :type ORDER BY createAt ASC")
  fun byType(type: String): Flow<List<SourceConfig>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(config: SourceConfig): Long

  @Update
  suspend fun update(config: SourceConfig)

  @Delete
  suspend fun delete(config: SourceConfig)
}
