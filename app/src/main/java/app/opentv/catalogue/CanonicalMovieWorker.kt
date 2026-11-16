package app.opentv.catalogue

import android.content.Context
import androidx.work.*
import app.opentv.core.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Checkpoints after each asset. Failed HTTP responses leave the asset pending. */
class CanonicalMovieWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = ServiceLocator.get(applicationContext)
        if (graph.settings.tmdbApiKey.value.isBlank()) return@withContext Result.success()
        val dao = graph.database.canonicalMovies()
        val resolver = CanonicalMovieResolver(graph.httpClient, graph.settings)
        val writes = mutableListOf<Pair<CanonicalMovie?, MovieIdentityMatch>>()
        suspend fun checkpoint(title: CanonicalMovie?, link: MovieIdentityMatch) {
            writes += title to link
            if (writes.size >= 8) { dao.recordBatch(writes.toList()); writes.clear() }
        }
        try {
            val batch = dao.pending(80)
            for (movie in batch) {
                val evidence = movie.identityEvidence()
                val cached = dao.byEvidence(evidence)
                if (cached != null) {
                    checkpoint(null, cached.copy(sourceId = movie.sourceId, streamId = movie.streamId))
                    continue
                }
                val existingTitle = movie.tmdbId?.toIntOrNull()?.takeIf { it > 0 }?.let { id ->
                    writes.firstNotNullOfOrNull { it.first?.takeIf { title -> title.tmdbId == id } } ?: dao.title(id)
                }
                val (title, decision) = if (existingTitle != null) {
                    existingTitle to MovieMatching.explicit(MovieCandidate(existingTitle.tmdbId,
                        existingTitle.title, existingTitle.originalTitle, existingTitle.year),
                        MovieMatching.releaseYear(movie.name, movie.year), cached = true)
                } else resolver.resolve(movie)
                val link = when (decision) {
                    is MovieMatchDecision.Accepted -> MovieIdentityMatch(movie.sourceId, movie.streamId, evidence,
                        decision.candidate.id, decision.confidence, decision.provenance, false, "")
                    is MovieMatchDecision.Ambiguous -> MovieIdentityMatch(movie.sourceId, movie.streamId, evidence,
                        null, 0.0, "ambiguous", false, decision.candidateIds.joinToString(","))
                    MovieMatchDecision.Unmatched -> MovieIdentityMatch(movie.sourceId, movie.streamId, evidence,
                        null, 0.0, "unmatched", false, "")
                }
                checkpoint(title, link)
            }
            if (writes.isNotEmpty()) { dao.recordBatch(writes.toList()); writes.clear() }
            if (dao.pending(1).isNotEmpty()) {
                WorkManager.getInstance(applicationContext).enqueueUniqueWork("jask-canonical-movies",
                    ExistingWorkPolicy.APPEND_OR_REPLACE, request())
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (writes.isNotEmpty()) runCatching { dao.recordBatch(writes.toList()) }
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("jask-canonical-movies", ExistingWorkPolicy.KEEP,
                request())
        }
        private fun request() = OneTimeWorkRequestBuilder<CanonicalMovieWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
}
