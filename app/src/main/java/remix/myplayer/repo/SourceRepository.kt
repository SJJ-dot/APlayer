package remix.myplayer.repo

import kotlinx.coroutines.flow.Flow
import remix.myplayer.data.db.room.dao.SourceConfigDao
import remix.myplayer.data.db.room.entity.SourceConfig
import javax.inject.Inject

interface SourceRepository {
  fun all(): Flow<List<SourceConfig>>

  fun enabled(): Flow<List<SourceConfig>>

  suspend fun save(config: SourceConfig): Long

  suspend fun update(config: SourceConfig)

  suspend fun delete(config: SourceConfig)
}

class SourceRepoImpl @Inject constructor(
  private val dao: SourceConfigDao
) : SourceRepository {
  override fun all(): Flow<List<SourceConfig>> = dao.all()

  override fun enabled(): Flow<List<SourceConfig>> = dao.enabled()

  override suspend fun save(config: SourceConfig): Long = dao.insert(config)

  override suspend fun update(config: SourceConfig) = dao.update(config)

  override suspend fun delete(config: SourceConfig) = dao.delete(config)
}
