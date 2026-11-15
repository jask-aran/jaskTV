/*
 * jaskTV saved collections — GPL-3.0-or-later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.opentv.data.model.Channel
import app.opentv.data.model.CollectionEntry
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.model.UserCollection
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM user_collections ORDER BY createdMillis, id")
    fun observeCollections(): Flow<List<UserCollection>>

    @Insert
    suspend fun create(collection: UserCollection): Long

    @Query("UPDATE user_collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM user_collections WHERE id = :id")
    suspend fun deleteCollectionRow(id: Long)

    @Query("DELETE FROM collection_entries WHERE collectionId = :id")
    suspend fun deleteEntries(id: Long)

    @Transaction
    suspend fun delete(id: Long) {
        deleteEntries(id)
        deleteCollectionRow(id)
    }

    @Query("SELECT * FROM collection_entries")
    fun observeAllEntries(): Flow<List<CollectionEntry>>

    @Query("SELECT * FROM collection_entries WHERE collectionId = :id")
    fun observeEntries(id: Long): Flow<List<CollectionEntry>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(entry: CollectionEntry)

    @Query("DELETE FROM collection_entries WHERE collectionId = :collectionId AND kind = :kind AND sourceId = :sourceId AND itemKey = :itemKey")
    suspend fun remove(collectionId: Long, kind: String, sourceId: Long, itemKey: String)

    /** All collected items are part of Library / All even when they are not starred. */
    @Query("""
        SELECT DISTINCT c.* FROM channels c
        INNER JOIN collection_entries e ON e.sourceId = c.sourceId
            AND e.itemKey = c.streamId AND e.kind = 'channel'
        WHERE c.hidden = 0 ORDER BY c.displayName
    """)
    fun observeAllCollectedChannels(): Flow<List<Channel>>

    @Query("""
        SELECT DISTINCT m.* FROM movies m
        INNER JOIN collection_entries e ON e.sourceId = m.sourceId
            AND e.itemKey = m.streamId AND e.kind = 'movie'
        ORDER BY m.name
    """)
    fun observeAllCollectedMovies(): Flow<List<Movie>>

    @Query("""
        SELECT DISTINCT s.* FROM series s
        INNER JOIN collection_entries e ON e.sourceId = s.sourceId
            AND e.itemKey = s.seriesId AND e.kind = 'show'
        ORDER BY s.name
    """)
    fun observeAllCollectedShows(): Flow<List<Series>>

    @Query("""
        SELECT c.* FROM channels c
        INNER JOIN collection_entries e ON e.sourceId = c.sourceId
            AND e.itemKey = c.streamId AND e.kind = 'channel'
        WHERE e.collectionId = :id AND c.hidden = 0
        ORDER BY c.displayName
    """)
    fun observeChannels(id: Long): Flow<List<Channel>>

    @Query("""
        SELECT m.* FROM movies m
        INNER JOIN collection_entries e ON e.sourceId = m.sourceId
            AND e.itemKey = m.streamId AND e.kind = 'movie'
        WHERE e.collectionId = :id ORDER BY m.name
    """)
    fun observeMovies(id: Long): Flow<List<Movie>>

    @Query("""
        SELECT s.* FROM series s
        INNER JOIN collection_entries e ON e.sourceId = s.sourceId
            AND e.itemKey = s.seriesId AND e.kind = 'show'
        WHERE e.collectionId = :id ORDER BY s.name
    """)
    fun observeSeries(id: Long): Flow<List<Series>>
}
