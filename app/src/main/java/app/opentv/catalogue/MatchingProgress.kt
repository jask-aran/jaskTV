package app.opentv.catalogue

import app.opentv.account.sync.LanCatalogue
import app.opentv.core.StatusBus
import app.opentv.data.repo.CatalogRepository

/**
 * What the two TMDB matchers ([CanonicalMovieWorker], [CanonicalShowWorker]) share: a real count for the sync
 * indicator ("1,240 of 38,000 checked"), and asking nearby devices for the matches they already have before
 * looking anything up (see [fromNearby]), so only titles no device has matched go to TMDB.
 */
internal object MatchingProgress {
    @Volatile private var askedNearbyAt = 0L

    /**
     * Asks linked devices for their TMDB matches. Only a request that reached a device starts the 2-minute wait
     * before asking again; with nobody linked yet (a fresh app whose link is still coming up) the next batch asks
     * again, and a device linking resets the wait ([deviceLinked]).
     */
    suspend fun fromNearby(graph: app.opentv.core.ServiceLocator.Graph) {
        if (System.currentTimeMillis() - askedNearbyAt < 2 * 60_000) return
        val fill = CatalogRepository.fromNearby ?: return
        var reached = false
        for (source in graph.sourceRepository.enabled()) if (runCatching { fill(source, LanCatalogue.TMDB) }.getOrDefault(false)) reached = true
        if (reached) askedNearbyAt = System.currentTimeMillis()
    }

    /**
     * With a core that matches answering: copies its matches (only what's new since the last copy) and returns true,
     * so the caller does no matching of its own. False: no such core; match here.
     */
    suspend fun copyFromCore(graph: app.opentv.core.ServiceLocator.Graph): Boolean {
        // A core answers, or one builds the account's table somewhere (its snapshot is in the account): copy, don't match.
        val core = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.opentv.account.CoreDirectory.endpoint("tmdb") }
        if (core == null && !app.opentv.account.AccountShared.builderActive()) return false
        val fill = CatalogRepository.fromNearby ?: return false
        for (source in graph.sourceRepository.enabled()) runCatching { fill(source, LanCatalogue.TMDB) }
        return true
    }

    /** A device of this account just linked on this network: ask it now rather than waiting. */
    fun deviceLinked(context: android.content.Context) {
        askedNearbyAt = 0L
        CanonicalMovieWorker.enqueue(context)
        CanonicalShowWorker.enqueue(context)
    }

    /** Movies: (matched, total) over enabled sources. A title counts as matched once it has a current match. */
    fun movies(graph: app.opentv.core.ServiceLocator.Graph): Pair<Int, Int> = count(graph, """
        SELECT COUNT(*), SUM(CASE WHEN l.sourceId IS NOT NULL AND (l.manual = 1 OR
            l.evidence = m.name || char(31) || IFNULL(m.year, '') || char(31) || IFNULL(m.tmdbId, '')) THEN 1 ELSE 0 END)
        FROM movies m LEFT JOIN movie_identity_matches l ON l.sourceId = m.sourceId AND l.streamId = m.streamId
        WHERE EXISTS (SELECT 1 FROM sources s WHERE s.id = m.sourceId AND s.enabled = 1)""")

    /** Shows: (matched, total) distinct TMDB ids the provider names. */
    fun shows(graph: app.opentv.core.ServiceLocator.Graph): Pair<Int, Int> = count(graph, """
        SELECT COUNT(*), SUM(CASE WHEN EXISTS (SELECT 1 FROM canonical_shows c WHERE c.tmdbId = t.id) THEN 1 ELSE 0 END)
        FROM (SELECT DISTINCT CAST(tmdbId AS INTEGER) AS id FROM series WHERE trim(tmdbId) != ''
            AND trim(tmdbId) NOT GLOB '*[^0-9]*' AND CAST(tmdbId AS INTEGER) BETWEEN 1 AND 2147483647) t""")

    private fun count(graph: app.opentv.core.ServiceLocator.Graph, sql: String): Pair<Int, Int> = runCatching {
        graph.database.openHelper.readableDatabase.query(sql).use { c -> if (c.moveToFirst()) c.getInt(1) to c.getInt(0) else 0 to 0 }
    }.getOrDefault(0 to 0)

    fun report(id: String, counts: Pair<Int, Int>) {
        val (done, total) = counts
        if (total > 0) StatusBus.update(id, detail = "%,d of %,d checked".format(done, total), progress = done.toFloat() / total)
    }

    /**
     * Whether matched titles waiting to be written should go in now. Each write makes Room re-run the queries behind
     * whatever is on screen (a browse grid's page, the home's shelves), so matching writes every few seconds rather than
     * every few titles: the page the user is browsing isn't reloaded under them several times a second.
     */
    fun due(waiting: Int, lastWrite: Long): Boolean =
        waiting >= 1500 || (waiting > 0 && System.currentTimeMillis() - lastWrite >= 6_000)
}
