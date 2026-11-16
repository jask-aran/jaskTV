package app.opentv.livetv

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Personal identity survives loss of every playable provider asset. No URLs or credentials. */
@Entity(tableName = "saved_tv_channels", primaryKeys = ["profileId", "identity"])
data class SavedTvChannel(
    val profileId: Long,
    val identity: String,
    val label: String,
    val preferredSourceId: Long?,
    val preferredStreamId: String?,
    val sortIndex: Int,
)

@Dao
interface SavedTvDao {
    @Query("SELECT * FROM saved_tv_channels WHERE profileId = :profile ORDER BY sortIndex, label")
    fun observe(profile: Long): Flow<List<SavedTvChannel>>
    @Upsert suspend fun save(channel: SavedTvChannel)
    @Query("DELETE FROM saved_tv_channels WHERE profileId = :profile AND identity = :identity")
    suspend fun remove(profile: Long, identity: String)
    @Query("SELECT * FROM saved_tv_channels") suspend fun all(): List<SavedTvChannel>
}
