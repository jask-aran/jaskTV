package app.opentv.catalogue

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Transaction
import androidx.paging.PagingSource
import app.opentv.data.model.Movie
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CanonicalMovieDao {
    /** The title index can stop after a page; no inventory-wide GROUP BY on default browsing. */
    @Query("""
        SELECT c.*,
            (SELECT MIN(m.id) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId)) AS movieId,
            (SELECT COUNT(*) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId)) AS versionCount,
            (SELECT MAX(m.browseQualityRank) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId)) AS bestQuality
        FROM canonical_movies c
        WHERE EXISTS (SELECT 1 FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId))
          AND (:query = '' OR instr(lower(c.title), lower(:query)) > 0 OR instr(lower(c.originalTitle), lower(:query)) > 0 OR EXISTS (SELECT 1 FROM movie_identity_matches aliasLink JOIN movies raw ON raw.sourceId = aliasLink.sourceId AND raw.streamId = aliasLink.streamId WHERE aliasLink.tmdbId = c.tmdbId AND instr(lower(raw.name), lower(:query)) > 0))
          AND ((:languages = '' AND (:includeUnknown OR c.originalLanguage IS NOT NULL)) OR instr(:languages, '|' || c.originalLanguage || '|') > 0 OR (:includeUnknown AND c.originalLanguage IS NULL))
        ORDER BY c.title, c.tmdbId LIMIT 100
    """)
    abstract fun search(sourceId: Long?, query: String, languages: String = "", includeUnknown: Boolean = true): Flow<List<CanonicalMovieCard>>

    /**
     * Provider movies named like [query] that have no confident TMDB match yet (not looked up, ambiguous, or not
     * found): without this, search could only find matched films, and most of a big catalogue isn't matched yet.
     */
    @Query("""SELECT m.* FROM movies m LEFT JOIN movie_identity_matches l ON l.sourceId = m.sourceId AND l.streamId = m.streamId
        WHERE instr(lower(m.name), lower(:query)) > 0
          AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
          AND (:sourceId IS NULL OR m.sourceId = :sourceId)
          AND NOT (l.tmdbId IS NOT NULL AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, ''))))
        ORDER BY m.name LIMIT :limit""")
    abstract fun searchUnmatched(sourceId: Long?, query: String, limit: Int = 120): Flow<List<Movie>>

    @Query("""
        SELECT c.*,
            (SELECT MIN(m.id) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId)) AS movieId,
            (SELECT COUNT(*) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId)) AS versionCount,
            (SELECT MAX(m.browseQualityRank) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId)) AS bestQuality
        FROM canonical_movies c
        WHERE EXISTS (SELECT 1 FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
                  AND (:sourceId IS NULL OR m.sourceId = :sourceId))
          AND (:query = '' OR instr(lower(c.title), lower(:query)) > 0 OR instr(lower(c.originalTitle), lower(:query)) > 0 OR EXISTS (SELECT 1 FROM movie_identity_matches aliasLink JOIN movies raw ON raw.sourceId = aliasLink.sourceId AND raw.streamId = aliasLink.streamId WHERE aliasLink.tmdbId = c.tmdbId AND instr(lower(raw.name), lower(:query)) > 0))
          AND ((:languages = '' AND (:includeUnknown OR c.originalLanguage IS NOT NULL)) OR instr(:languages, '|' || c.originalLanguage || '|') > 0 OR (:includeUnknown AND c.originalLanguage IS NULL))
        ORDER BY c.title, c.tmdbId
    """)
    abstract fun browseTitle(sourceId: Long?, query: String, languages: String, includeUnknown: Boolean): PagingSource<Int, CanonicalMovieCard>

    fun browse(sourceId: Long?, query: String, languages: String, includeUnknown: Boolean, sort: String): PagingSource<Int, CanonicalMovieCard> =
        if (sort == "TITLE") browseTitle(sourceId, query, languages, includeUnknown)
        else browseRanked(sourceId, query, languages, includeUnknown, sort)

    @Query("""
        SELECT c.*, MIN(m.id) AS movieId, COUNT(*) AS versionCount, MAX(m.browseQualityRank) AS bestQuality
        FROM canonical_movies c
        JOIN movie_identity_matches l ON l.tmdbId = c.tmdbId
        JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
        WHERE 1 = 1 AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
          AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
          AND (:sourceId IS NULL OR m.sourceId = :sourceId)
          AND (:query = '' OR instr(lower(c.title), lower(:query)) > 0 OR instr(lower(c.originalTitle), lower(:query)) > 0 OR EXISTS (SELECT 1 FROM movie_identity_matches aliasLink JOIN movies raw ON raw.sourceId = aliasLink.sourceId AND raw.streamId = aliasLink.streamId WHERE aliasLink.tmdbId = c.tmdbId AND instr(lower(raw.name), lower(:query)) > 0))
          AND ((:languages = '' AND (:includeUnknown OR c.originalLanguage IS NOT NULL)) OR instr(:languages, '|' || c.originalLanguage || '|') > 0 OR (:includeUnknown AND c.originalLanguage IS NULL))
        GROUP BY c.tmdbId
        ORDER BY
          CASE WHEN :sort = 'YEAR' THEN c.year END DESC,
          CASE WHEN :sort = 'RATING' THEN c.rating END DESC,
          CASE WHEN :sort = 'ADDED' THEN MAX(m.addedMillis) END DESC,
          c.title COLLATE NOCASE, c.tmdbId
    """)
    abstract fun browseRanked(sourceId: Long?, query: String, languages: String, includeUnknown: Boolean, sort: String): PagingSource<Int, CanonicalMovieCard>

    @Query("SELECT DISTINCT originalLanguage FROM canonical_movies WHERE originalLanguage IS NOT NULL ORDER BY originalLanguage")
    abstract fun languages(): Flow<List<String>>

    @Query("""SELECT m.* FROM movies m LEFT JOIN movie_identity_matches l
        ON l.sourceId = m.sourceId AND l.streamId = m.streamId
        WHERE (l.sourceId IS NULL OR (l.manual = 0 AND l.evidence != m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
        AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
        ORDER BY m.id LIMIT :limit""")
    abstract suspend fun pending(limit: Int): List<Movie>

    /** [pending], best order for matching: titles the provider gave a TMDB id first (one exact request), then newest. */
    @Query("""SELECT m.* FROM movies m LEFT JOIN movie_identity_matches l
        ON l.sourceId = m.sourceId AND l.streamId = m.streamId
        WHERE (l.sourceId IS NULL OR (l.manual = 0 AND l.evidence != m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
        AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
        ORDER BY (m.tmdbId IS NOT NULL AND trim(m.tmdbId) != '') DESC, m.addedMillis DESC, m.id LIMIT :limit""")
    abstract suspend fun pendingByPriority(limit: Int): List<Movie>

    @Query("SELECT * FROM movies WHERE id IN (:ids)")
    abstract suspend fun moviesByIds(ids: List<Long>): List<Movie>

    /** Ranked TMDB ids that are available locally (any enabled, present, confidently matched version). */
    @Query("""
        SELECT * FROM (SELECT c.*,
            (SELECT MIN(m.id) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId
                  AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))) AS movieId,
            1 AS versionCount,
            (SELECT MAX(m.browseQualityRank) FROM movie_identity_matches l JOIN movies m ON m.sourceId = l.sourceId AND m.streamId = l.streamId
                WHERE l.tmdbId = c.tmdbId) AS bestQuality
        FROM canonical_movies c WHERE c.tmdbId IN (:ids)) WHERE movieId IS NOT NULL""")
    abstract suspend fun availableByIds(ids: List<Int>): List<CanonicalMovieCard>

    @Query("SELECT * FROM movie_identity_matches WHERE sourceId = :sourceId AND streamId = :streamId")
    abstract suspend fun match(sourceId: Long, streamId: String): MovieIdentityMatch?

    @Query("SELECT * FROM canonical_movies WHERE tmdbId = :id")
    abstract suspend fun title(id: Int): CanonicalMovie?

    /** Best-rated catalogue titles whose TMDB genres include [genre] ("More like this"). */
    @Query("""SELECT tmdbId FROM canonical_movies WHERE tmdbId != :id AND genres IS NOT NULL AND instr(genres, :genre) > 0
        ORDER BY rating DESC LIMIT :limit""")
    abstract suspend fun idsWithGenre(id: Int, genre: String, limit: Int): List<Int>

    @Query("""SELECT m.* FROM movies m JOIN movie_identity_matches l ON m.sourceId = l.sourceId AND m.streamId = l.streamId
        WHERE l.tmdbId = :id AND EXISTS (SELECT 1 FROM sources provider WHERE provider.id = m.sourceId AND provider.enabled = 1)
                  AND (NOT EXISTS (SELECT 1 FROM movie_inventory_snapshots snap WHERE snap.sourceId = m.sourceId)
                    OR EXISTS (SELECT 1 FROM movie_inventory_snapshots snap JOIN movie_inventory_assets present
                        ON present.sourceId = snap.sourceId AND present.generation = snap.generation
                        WHERE snap.sourceId = m.sourceId AND present.streamId = m.streamId))
                  AND (l.manual = 1 OR (l.confidence >= 0.95 AND l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')))
        ORDER BY m.browseQualityRank DESC, m.sourceId, m.streamId""")
    abstract suspend fun versions(id: Int): List<Movie>

    @Query("SELECT * FROM movie_identity_matches WHERE evidence = :evidence AND manual = 0 LIMIT 1")
    abstract suspend fun byEvidence(evidence: String): MovieIdentityMatch?

    @Upsert abstract suspend fun stageInventory(assets: List<MovieInventoryAsset>)

    @Query("SELECT generation FROM movie_inventory_snapshots WHERE sourceId = :sourceId")
    abstract suspend fun activeGeneration(sourceId: Long): String?

    @Query("SELECT streamId FROM movie_inventory_assets WHERE sourceId = :sourceId AND generation = :generation")
    abstract suspend fun inventoryIds(sourceId: Long, generation: String): List<String>

    @Query("DELETE FROM movie_inventory_assets WHERE sourceId = :sourceId AND generation = :generation AND streamId IN (:streamIds)")
    abstract suspend fun removeInventory(sourceId: Long, generation: String, streamIds: List<String>)

    /** Only what changed: [changed] films added to the active snapshot, [removed] taken out (none yet: nothing to do). */
    @Transaction
    open suspend fun changeInventory(sourceId: Long, changed: Set<String>, removed: List<String>) {
        val generation = activeGeneration(sourceId) ?: return
        changed.chunked(500).forEach { ids -> stageInventory(ids.map { MovieInventoryAsset(sourceId, generation, it) }) }
        removed.chunked(500).forEach { removeInventory(sourceId, generation, it) }
    }

    /**
     * The active snapshot made to hold exactly [present], changing only the difference (a refresh where a few films
     * came and went writes a few rows, not all of them). No snapshot yet: a new one with everything.
     */
    @Transaction
    open suspend fun syncInventory(sourceId: Long, present: Set<String>) {
        val generation = activeGeneration(sourceId)
        if (generation == null) {
            val fresh = java.util.UUID.randomUUID().toString()
            present.chunked(500).forEach { ids -> stageInventory(ids.map { MovieInventoryAsset(sourceId, fresh, it) }) }
            activateSnapshot(sourceId, fresh)
            return
        }
        val held = inventoryIds(sourceId, generation).toHashSet()
        present.filter { it !in held }.chunked(500).forEach { ids -> stageInventory(ids.map { MovieInventoryAsset(sourceId, generation, it) }) }
        held.filter { it !in present }.chunked(500).forEach { removeInventory(sourceId, generation, it) }
    }
    @Upsert protected abstract suspend fun putSnapshot(snapshot: MovieInventorySnapshot)
    @Query("DELETE FROM movie_inventory_assets WHERE sourceId = :sourceId AND generation != :generation")
    protected abstract suspend fun pruneSnapshots(sourceId: Long, generation: String)

    @Transaction
    open suspend fun activateSnapshot(sourceId: Long, generation: String) {
        putSnapshot(MovieInventorySnapshot(sourceId, generation))
        pruneSnapshots(sourceId, generation)
    }

    @Upsert abstract suspend fun putTitle(title: CanonicalMovie)
    @Upsert protected abstract suspend fun putMatch(match: MovieIdentityMatch)

    @Transaction
    open suspend fun recordBatch(records: List<Pair<CanonicalMovie?, MovieIdentityMatch>>) {
        records.forEach { (title, link) -> record(title, link) }
    }

    @Transaction
    open suspend fun record(title: CanonicalMovie?, link: MovieIdentityMatch) {
        require(link.confidence in 0.0..1.0)
        require(!link.manual || link.tmdbId != null)
        require(link.tmdbId == null || (title?.tmdbId ?: this.title(link.tmdbId)?.tmdbId) == link.tmdbId)
        // A concurrent/manual correction always wins over background work.
        if (!link.manual && match(link.sourceId, link.streamId)?.manual == true) return
        if (title != null) putTitle(title)
        putMatch(link)
    }
}
