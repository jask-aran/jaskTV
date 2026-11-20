package app.opentv.catalogue

import android.content.Context
import androidx.work.*
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks provider TV hints in batches of 60 (and fills detail for list-validated rows), writing every 10; resumable like [CanonicalMovieWorker]. */
class CanonicalShowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(app.opentv.core.BackgroundWork.matching) {
        if (app.opentv.core.SyncPause.isPaused(applicationContext)) return@withContext Result.success()
        val graph = ServiceLocator.get(applicationContext)
        if (graph.settings.tmdbApiKey.value.isBlank()) return@withContext Result.success()
        // See CanonicalMovieWorker: a core does the matching.
        if (MatchingProgress.copyFromCore(graph)) return@withContext Result.success()
        val dao = graph.database.canonicalShows()
        val resolver = CanonicalShowResolver(graph.httpClient, graph.settings)
        val writes = mutableListOf<CanonicalShow>()
        var lastWrite = System.currentTimeMillis()
        app.opentv.core.StatusBus.start("tmdb-shows", "Matching shows to TMDB", app.opentv.core.StatusBus.Origin.TMDB, "Posters, ratings and details")
        try {
            // Inside the try: cancelled here (Pause syncing) or failing, the job must still end (finally).
            MatchingProgress.fromNearby(graph)
            MatchingProgress.report("tmdb-shows", MatchingProgress.shows(graph))
            // Titles the home already showed (validated from a list entry) get their detail first; then the provider's
            // hints. In parallel through TmdbGate (~35 requests a second); written every 40.
            val lock = kotlinx.coroutines.sync.Mutex()
            suspend fun put(show: CanonicalShow) = lock.withLock {
                writes += show
                if (MatchingProgress.due(writes.size, lastWrite)) { dao.putAll(writes.toList()); writes.clear(); lastWrite = System.currentTimeMillis() }
            }
            kotlinx.coroutines.coroutineScope {
                dao.needsDetail(200).map { show -> async { put(resolver.detail(show)) } }.awaitAll()
                dao.pending(600).map { hint -> async { put(resolver.resolve(hint)) } }.awaitAll()
            }
            if (writes.isNotEmpty()) { dao.putAll(writes.toList()); writes.clear() }
            if (dao.pending(1).isNotEmpty() || dao.needsDetail(1).isNotEmpty()) WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (writes.isNotEmpty()) runCatching { dao.putAll(writes.toList()) }
            Result.retry()
        } finally { app.opentv.core.StatusBus.end("tmdb-shows") }
    }

    companion object {
        private const val NAME = "jask-canonical-shows"
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request())
        }
        private fun request() = OneTimeWorkRequestBuilder<CanonicalShowWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}
