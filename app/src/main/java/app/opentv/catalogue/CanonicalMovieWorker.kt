package app.opentv.catalogue

import android.content.Context
import androidx.work.*
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Movie
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Matches the provider's movies to TMDB, in the order they matter (see [nextBatch]): the provider's copies of what
 * the home shows (trending, new, popular) first, then titles the provider gave a TMDB id, then the newest. Never
 * fetches TMDB titles the provider doesn't have. Before looking anything up it asks nearby devices for their matches
 * ([MatchingProgress.fromNearby]). Copies that clean to the same name and year are looked up once, lookups run in
 * parallel through [TmdbGate] (~35 a second), and results are written every 40 titles. Failed HTTP leaves titles pending.
 */
class CanonicalMovieWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(app.opentv.core.BackgroundWork.matching) {
        if (app.opentv.core.SyncPause.isPaused(applicationContext)) return@withContext Result.success()
        val graph = ServiceLocator.get(applicationContext)
        if (graph.settings.tmdbApiKey.value.isBlank()) return@withContext Result.success()
        // A core matches for the whole network: copy its results (only what's new) and do no matching here. Titles it
        // hasn't reached yet arrive with the next copy.
        if (MatchingProgress.copyFromCore(graph)) return@withContext Result.success()
        val dao = graph.database.canonicalMovies()
        val resolver = CanonicalMovieResolver(graph.httpClient, graph.settings)
        val id = "tmdb-movies"
        app.opentv.core.StatusBus.start(id, "Matching movies to TMDB", app.opentv.core.StatusBus.Origin.TMDB, "Posters, ratings and details")
        val writes = mutableListOf<Pair<CanonicalMovie?, MovieIdentityMatch>>()
        val lock = Mutex()
        var lastWrite = System.currentTimeMillis()
        try {
            MatchingProgress.fromNearby(graph)
            val counts = MatchingProgress.movies(graph)
            var checked = counts.first
            MatchingProgress.report(id, counts)
            suspend fun record(title: CanonicalMovie?, links: List<MovieIdentityMatch>) = lock.withLock {
                links.forEachIndexed { i, link -> writes += (if (i == 0) title else null) to link }
                // Every write reloads what's on screen (see MatchingProgress.due): a few seconds apart, not every 40 titles.
                if (MatchingProgress.due(writes.size, lastWrite)) {
                    dao.recordBatch(writes.toList()); checked += writes.size; writes.clear(); lastWrite = System.currentTimeMillis()
                    MatchingProgress.report(id, checked to counts.second)
                }
            }
            val batch = nextBatch(graph, dao)
            // Copies of one film (languages, qualities, categories) clean to the same key: one lookup for all of them.
            val groups = batch.groupBy(::groupKey).values
            coroutineScope {
                groups.map { group ->
                    async {
                        val first = group.first()
                        val cached = dao.byEvidence(first.identityEvidence())
                        val (title, decision) = if (cached != null) null to null else resolve(dao, resolver, first)
                        record(title, group.map { movie ->
                            val evidence = movie.identityEvidence()
                            cached?.copy(sourceId = movie.sourceId, streamId = movie.streamId, evidence = evidence)
                                ?: link(movie, evidence, decision!!)
                        })
                    }
                }.awaitAll()
            }
            lock.withLock { if (writes.isNotEmpty()) { dao.recordBatch(writes.toList()); writes.clear() } }
            if (dao.pending(1).isNotEmpty()) {
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            lock.withLock { if (writes.isNotEmpty()) runCatching { dao.recordBatch(writes.toList()) } }
            Result.retry()
        } finally { app.opentv.core.StatusBus.end(id) }
    }

    /**
     * What to match next. Once per app run, the provider's copies of the ranked titles the home shows (by TMDB id or
     * by cleaned name), so its "on your sources" marks are right first; then the rest in [CanonicalMovieDao.pendingByPriority] order.
     */
    private suspend fun nextBatch(graph: ServiceLocator.Graph, dao: CanonicalMovieDao): List<Movie> {
        val ranked = if (rankedDone) emptyList() else runCatching { rankedPending(graph, dao) }.getOrDefault(emptyList()).also { rankedDone = true }
        val rest = dao.pendingByPriority(BATCH)
        return (ranked + rest).distinctBy { it.id }
    }

    private suspend fun rankedPending(graph: ServiceLocator.Graph, dao: CanonicalMovieDao): List<Movie> {
        val lists = TmdbRankings(graph.httpClient, graph.settings, java.io.File(applicationContext.filesDir, "rankings"))
            .cached(TmdbRankings.Kind.MOVIES) ?: return emptyList()
        val titles = lists.values.flatten()
        val ids = titles.map { it.id.toString() }.toSet()
        val keys = titles.flatMap { listOf(it.title, it.originalTitle) }.map(MovieMatching::titleKey).filter(String::isNotBlank).toSet()
        val wanted = ArrayList<Long>()
        // In chunks by row id: one query over ~200k rows overflows Android's 2 MB cursor window and re-runs per page.
        var after = 0L
        val db = graph.database.openHelper.readableDatabase
        while (true) {
            var last = after
            var n = 0
            db.query("""SELECT m.id, m.name, m.tmdbId FROM movies m
                LEFT JOIN movie_identity_matches l ON l.sourceId = m.sourceId AND l.streamId = m.streamId
                WHERE m.id > ? AND l.sourceId IS NULL ORDER BY m.id LIMIT 5000""", arrayOf<Any>(after)).use { c ->
                while (c.moveToNext()) {
                    n++; last = c.getLong(0)
                    val hint = c.getString(2)?.trim()
                    if ((hint != null && hint in ids) || MovieMatching.titleKey(MovieMatching.query(c.getString(1))) in keys) wanted += last
                }
            }
            if (n < 5000) break
            after = last
        }
        return wanted.chunked(500).flatMap { dao.moviesByIds(it) }
    }

    private suspend fun resolve(dao: CanonicalMovieDao, resolver: CanonicalMovieResolver, movie: Movie): Pair<CanonicalMovie?, MovieMatchDecision> {
        val existing = movie.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.let { dao.title(it) }
        return if (existing != null) existing to MovieMatching.explicit(MovieCandidate(existing.tmdbId, existing.title,
            existing.originalTitle, existing.year), MovieMatching.releaseYear(movie.name, movie.year), cached = true)
        else resolver.resolve(movie)
    }

    private fun link(movie: Movie, evidence: String, decision: MovieMatchDecision) = when (decision) {
        is MovieMatchDecision.Accepted -> MovieIdentityMatch(movie.sourceId, movie.streamId, evidence,
            decision.candidate.id, decision.confidence, decision.provenance, false, "")
        is MovieMatchDecision.Ambiguous -> MovieIdentityMatch(movie.sourceId, movie.streamId, evidence,
            null, 0.0, "ambiguous", false, decision.candidateIds.joinToString(","))
        MovieMatchDecision.Unmatched -> MovieIdentityMatch(movie.sourceId, movie.streamId, evidence, null, 0.0, "unmatched", false, "")
    }

    /** Same TMDB id from the provider, or else the same cleaned title and year: the same film. */
    private fun groupKey(m: Movie): String = m.tmdbId?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { "id:$it" }
        ?: "q:${MovieMatching.titleKey(MovieMatching.query(m.name))}|${MovieMatching.releaseYear(m.name, m.year) ?: ""}"

    companion object {
        private const val NAME = "jask-canonical-movies"
        /** Titles per run: about a minute of lookups at full speed, then the next run is queued. */
        private const val BATCH = 1500
        @Volatile private var rankedDone = false

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request())
        }
        private fun request() = OneTimeWorkRequestBuilder<CanonicalMovieWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}
