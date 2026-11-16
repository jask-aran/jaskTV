/*
 * jaskTV user-controlled canonical channel aliases — GPL-3.0-or-later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.opentv.data.model.ChannelAliasOverride
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelAliasDao {
    @Query("SELECT * FROM channel_alias_overrides")
    fun observeAll(): Flow<List<ChannelAliasOverride>>

    @Query("SELECT * FROM channel_alias_overrides WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun find(sourceId: Long, streamId: String): ChannelAliasOverride?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(value: ChannelAliasOverride)

    @Query("DELETE FROM channel_alias_overrides WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun remove(sourceId: Long, streamId: String)
}
