/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.opentv.data.model.Channel
import kotlinx.coroutines.flow.Flow

private const val UPSERT_CHUNK = 500

@Dao
interface ChannelDao {
    /** Only variants of this exact regional group, never the whole inventory. */
    @Query("SELECT c.* FROM channels c JOIN sources s ON s.id=c.sourceId AND s.enabled=1 WHERE c.hidden=0 AND c.groupKey=:key ORDER BY c.qualityRank DESC LIMIT 100")
    suspend fun myTvVariants(key: String): List<Channel>

    @Query("SELECT c.* FROM channels c JOIN channel_alias_overrides a ON a.sourceId=c.sourceId AND a.streamId=c.streamId JOIN sources s ON s.id=c.sourceId AND s.enabled=1 WHERE c.hidden=0 AND a.iptvOrgId=:identity LIMIT 100")
    suspend fun myTvMappedVariants(identity: String): List<Channel>

    @Query("SELECT * FROM channels WHERE streamUrl = :url LIMIT 1")
    suspend fun byStreamUrl(url: String): Channel?

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamId = :streamId LIMIT 1")
    suspend fun byProviderKey(sourceId: Long, streamId: String): Channel?

    /** A matching guide ID is evidence of a programme listing, not verified stream content. */
    @Query("""
        SELECT c.* FROM channels c
        INNER JOIN sources s ON s.id = c.sourceId AND s.enabled = 1
        WHERE c.hidden = 0 AND (
            c.epgOverrideId IN (:ids) OR c.epgChannelId IN (:ids) OR c.matchedEpgId IN (:ids))
        ORDER BY c.favourite DESC, c.qualityRank DESC LIMIT 300
    """)
    suspend fun matchingEpgIds(ids: List<String>): List<Channel>

    /**
     * Everything Live TV's Find page classifies: a slim row per playable live stream, without stream
     * URLs. Disabled sources and hidden channels are excluded, as in every other live list.
     */
    @Query("""
        SELECT c.id, c.sourceId, c.name, c.groupKey, c.qualityRank, c.categoryId, c.logoUrl,
               cat.name AS categoryName, c.epgChannelId
        FROM channels c
        INNER JOIN sources s ON s.id = c.sourceId AND s.enabled = 1
        LEFT JOIN categories cat ON cat.sourceId = c.sourceId
            AND cat.id = c.categoryId AND cat.kind = 'LIVE'
        WHERE c.hidden = 0
    """)
    suspend fun liveFindRows(): List<LiveFindRow>

    /** Event-slot rename only: never inserts or deletes, so a partial refresh cannot drop other channels. */
    @Query("UPDATE channels SET name = :name, displayName = :displayName WHERE sourceId = :sourceId AND streamId = :streamId")
    suspend fun rename(sourceId: Long, streamId: String, name: String, displayName: String)

    @Query("SELECT streamId, name FROM channels WHERE sourceId = :sourceId AND categoryId = :categoryId")
    suspend fun namesInCategory(sourceId: Long, categoryId: String): List<StreamName>

    @Query("SELECT * FROM channels WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Channel>

    /** Bounded widget/external-launch read: deliberately excludes stream URLs and source secrets. */
    @Query("""
        SELECT c.id, c.displayName, c.customName, c.groupKey, c.qualityRank,
               c.categoryId, c.logoUrl, cat.name AS categoryName
        FROM channels c
        INNER JOIN sources s ON s.id = c.sourceId AND s.enabled = 1
        LEFT JOIN categories cat ON cat.sourceId = c.sourceId
            AND cat.id = c.categoryId AND cat.kind = 'LIVE'
        WHERE c.id IN (:ids) AND c.hidden = 0
    """)
    suspend fun widgetCandidates(ids: List<Long>): List<app.opentv.widget.RecentChannelCandidate>

    /** Visible channel counts for the phone category sheet. */
    @Query("SELECT categoryId AS categoryId, COUNT(*) AS count FROM channels WHERE hidden = 0 AND categoryId IS NOT NULL GROUP BY categoryId")
    fun observeCountsByCategory(): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (:sourceId IS NULL OR sourceId = :sourceId)
          AND (:categoryId IS NULL OR categoryId = :categoryId)
        ORDER BY sortIndex, displayName
        """
    )
    fun observe(sourceId: Long?, categoryId: String?): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE favourite = 1 AND hidden = 0 ORDER BY sortIndex, displayName")
    fun observeFavourites(): Flow<List<Channel>>

    /** The handful of codec-split ids behind a logical category, below SQLite's bind limit. */
    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0 AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategories(categoryIds: List<String>): Flow<List<Channel>>

    /** The channel manager must include hidden rows so the user can unhide them. */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId IN (:categoryIds)
        ORDER BY sortIndex, displayName
        """
    )
    fun observeInCategoriesIncludingHidden(sourceId: Long?, categoryIds: List<String>): Flow<List<Channel>>

    /** One-shot category read for the web channel manager. */
    @Query(
        """
        SELECT * FROM channels
        WHERE (:sourceId IS NULL OR sourceId = :sourceId)
          AND categoryId = :categoryId
        ORDER BY sortIndex, displayName
        """
    )
    suspend fun channelsInCategoryIncludingHidden(sourceId: Long?, categoryId: String): List<Channel>

    @Query(
        """
        SELECT sourceId, categoryId, COUNT(*) AS count
        FROM channels
        WHERE categoryId IS NOT NULL
        GROUP BY sourceId, categoryId
        """
    )
    suspend fun channelCountsByCategory(): List<CategoryChannelCount>

    @Query(
        """
        SELECT * FROM channels
        WHERE hidden = 0
          AND (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun search(query: String, limit: Int = 200): Flow<List<Channel>>

    @Query(
        """
        SELECT * FROM channels
        WHERE (displayName LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%')
        ORDER BY hidden, favourite DESC, sortIndex, displayName
        LIMIT :limit
        """
    )
    fun searchIncludingHidden(query: String, limit: Int = 200): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun byId(id: Long): Channel?

    @Query(
        """
        SELECT * FROM channels
        WHERE groupKey = :groupKey AND groupKey != '' AND hidden = 0
        ORDER BY qualityRank DESC, displayName
        """
    )
    suspend fun variantsInGroup(groupKey: String): List<Channel>

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun countForSource(sourceId: Long): Int

    /** Distinguishes a still-building guide from a confirmed empty catalogue. */
    @Query("SELECT COUNT(*) FROM channels WHERE hidden = 0")
    fun observeVisibleCount(): Flow<Int>

    @Query("UPDATE channels SET favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE channels SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE channels SET customName = :name WHERE id = :id")
    suspend fun setCustomName(id: Long, name: String?)

    @Query("UPDATE channels SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Int)

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: Long): List<Channel>

    @Query("UPDATE channels SET streamUrl = :url WHERE id = :id")
    suspend fun updateStreamUrl(id: Long, url: String)

    // Sync keys are stream URLs, stable across devices.
    @Query("SELECT streamUrl FROM channels WHERE favourite = 1")
    suspend fun favouriteUrls(): List<String>

    @Query("SELECT streamUrl FROM channels WHERE hidden = 1")
    suspend fun hiddenUrls(): List<String>

    @Query("UPDATE channels SET favourite = 1 WHERE streamUrl = :url")
    suspend fun markFavouriteByUrl(url: String)

    @Query("UPDATE channels SET hidden = 1 WHERE streamUrl = :url")
    suspend fun markHiddenByUrl(url: String)

    @Query("UPDATE channels SET matchedEpgId = :epgId WHERE id = :id")
    suspend fun setMatchedEpgId(id: Long, epgId: String?)

    /** Many guide matches in one transaction (channel id to guide id). */
    @Transaction
    suspend fun setMatchedEpgIds(changes: List<Pair<Long, String?>>) { changes.forEach { (id, epgId) -> setMatchedEpgId(id, epgId) } }

    @Query("UPDATE channels SET epgOverrideId = :epgId WHERE id = :id")
    suspend fun setEpgOverride(id: Long, epgId: String?)

    /** What the user set on channels (favourite, hidden, renamed), by provider key, for account sync. */
    @Query("SELECT sourceId, streamId, favourite, hidden, customName FROM channels WHERE favourite = 1 OR hidden = 1 OR customName IS NOT NULL")
    suspend fun userMarks(): List<ChannelMarkRow>

    /** Channels with a guide chosen by hand (account sync carries these to other devices). */
    @Query("SELECT sourceId, streamId, epgOverrideId FROM channels WHERE epgOverrideId IS NOT NULL")
    suspend fun epgOverrides(): List<EpgOverrideRow>

    @Query("SELECT * FROM channels")
    suspend fun allForMatching(): List<Channel>

    @Upsert
    suspend fun upsertAll(channels: List<Channel>)

    /** Sync stamps avoid a NOT IN list exceeding SQLite's 999 bind limit. */
    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND lastSeenMillis < :syncStamp")
    suspend fun deleteStale(sourceId: Long, syncStamp: Long)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: Long)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamId IN (:streamIds)")
    suspend fun byStreamIds(sourceId: Long, streamIds: List<String>): List<Channel>

    /**
     * Only what changed since the last copy (from jasktv-core): [changed] channels merged onto their existing rows the
     * way [replaceCatalogue] does (keeping every user mark and the stored order), and [removedStreamIds] deleted.
     */
    @Transaction
    suspend fun applyChanges(sourceId: Long, changed: List<Channel>, removedStreamIds: List<String>, syncStamp: Long) {
        changed.chunked(UPSERT_CHUNK).forEach { part ->
            val existing = byStreamIds(sourceId, part.map { it.streamId }).associateBy { it.streamId }
            upsertAll(part.map { channel ->
                val previous = existing[channel.streamId] ?: return@map channel.copy(lastSeenMillis = syncStamp)
                channel.copy(id = previous.id, favourite = previous.favourite, hidden = previous.hidden || channel.hidden,
                    sortIndex = previous.sortIndex, customName = previous.customName, epgOverrideId = previous.epgOverrideId,
                    matchedEpgId = previous.matchedEpgId, lastSeenMillis = syncStamp)
            })
        }
        removedStreamIds.chunked(UPSERT_CHUNK).forEach { ids -> deleteIds(byStreamIds(sourceId, ids).map { it.id }) }
    }

    /** Merge rather than wipe; preserve all user state across provider refreshes. */
    @Transaction
    suspend fun replaceCatalogue(sourceId: Long, incoming: List<Channel>, syncStamp: Long, matchUniqueUrls: Boolean = false) {
        if (incoming.isEmpty()) return

        val existing = userStateForSource(sourceId).associateBy { it.streamId }
        // Local lists may change/remove tvg-id while keeping the same stream. Never guess by
        // display/group names, reuse an ID twice, or steal a row matched by its stable streamId.
        val incomingIds = incoming.mapTo(hashSetOf()) { it.streamId }
        val existingUrls = existing.values.groupBy { it.streamUrl }
        val incomingUrls = incoming.groupingBy { it.streamUrl }.eachCount()
        val urlFallback = if (matchUniqueUrls) existingUrls.mapNotNull { (url, rows) ->
            rows.singleOrNull()?.takeIf {
                url.isNotBlank() && incomingUrls[url] == 1 && it.streamId !in incomingIds
            }?.let { url to it }
        }.toMap() else emptyMap()
        val merged = incoming.map { channel ->
            val previous = existing[channel.streamId] ?: urlFallback[channel.streamUrl]
            if (previous == null) {
                channel.copy(lastSeenMillis = syncStamp)
            } else {
                channel.copy(
                    id = previous.id,
                    favourite = previous.favourite,
                    // Importer-hidden separators and user hides both survive the refresh.
                    hidden = previous.hidden || channel.hidden,
                    sortIndex = previous.sortIndex,
                    customName = previous.customName,
                    epgOverrideId = previous.epgOverrideId,
                    matchedEpgId = previous.matchedEpgId,
                    lastSeenMillis = syncStamp,
                )
            }
        }
        // Only rows that changed (apart from the sync stamp) are written; channels no longer listed are removed by id.
        // Stamping every row each refresh rewrote all ~57k channels and their indexes every time.
        val previousById = existing.values.associateBy { it.id }
        val changed = merged.filter { m -> previousById[m.id]?.let { it.copy(lastSeenMillis = 0) != m.copy(lastSeenMillis = 0) } ?: true }
        changed.chunked(UPSERT_CHUNK).forEach { upsertAll(it) }
        val kept = merged.mapNotNullTo(hashSetOf()) { it.id.takeIf { id -> id != 0L } }
        existing.values.filter { it.id !in kept }.map { it.id }.chunked(UPSERT_CHUNK).forEach { deleteIds(it) }
    }

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId")
    suspend fun userStateForSource(sourceId: Long): List<Channel>
}

/** Projection for [ChannelDao.channelCountsByCategory]. */
data class CategoryChannelCount(val sourceId: Long, val categoryId: String, val count: Int)

/** Projection for [ChannelDao.liveFindRows]. */
data class LiveFindRow(
    val id: Long,
    val sourceId: Long,
    val name: String,
    val groupKey: String,
    val qualityRank: Int,
    val categoryId: String?,
    val logoUrl: String?,
    val categoryName: String?,
    /** The provider's guide id for the stream ("CNN.us"): IPTV-org's own ids, so it names the real channel. */
    val epgChannelId: String? = null,
)

/** Projection for [ChannelDao.namesInCategory]. */
data class StreamName(val streamId: String, val name: String)

/** A user's marks on one provider stream. */
data class ChannelMarkRow(val sourceId: Long, val streamId: String, val favourite: Boolean, val hidden: Boolean, val customName: String?)

/** A hand-chosen guide for one provider stream. */
data class EpgOverrideRow(val sourceId: Long, val streamId: String, val epgOverrideId: String)
