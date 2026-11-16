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
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Checks provider TV hints in batches of 60 (and fills detail for list-validated rows), writing every 10; resumable like [CanonicalMovieWorker]. */
class CanonicalShowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = ServiceLocator.get(applicationContext)
        if (graph.settings.tmdbApiKey.value.isBlank()) return@withContext Result.success()
        val dao = graph.database.canonicalShows()
        val resolver = CanonicalShowResolver(graph.httpClient, graph.settings)
        val writes = mutableListOf<CanonicalShow>()
        try {
            // Titles the home already showed (validated from a list entry) get their detail first.
            for (show in dao.needsDetail(40)) {
                writes += resolver.detail(show)
                if (writes.size >= 10) { dao.putAll(writes.toList()); writes.clear() }
            }
            for (hint in dao.pending(60)) {
                writes += resolver.resolve(hint)
                if (writes.size >= 10) { dao.putAll(writes.toList()); writes.clear() }
            }
            if (writes.isNotEmpty()) { dao.putAll(writes.toList()); writes.clear() }
            if (dao.pending(1).isNotEmpty() || dao.needsDetail(1).isNotEmpty()) WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (writes.isNotEmpty()) runCatching { dao.putAll(writes.toList()) }
            Result.retry()
        }
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
