package app.opentv.ui.channels

import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Whether a live channel is sending video right now. A provider's event slot (a PPV feed) is often switched on late
 * or never: the player then waits forever on an open connection that sends nothing. This opens the channel's raw
 * stream, reads for a few seconds and closes it again, before anything is chosen to play.
 */
internal object StreamProbe {
    /** Set by MainActivity from the debug-only `jaskAudit` launch extra. */
    @Volatile var auditPending = false
    /** About two seconds of a low-bitrate stream. A dead slot sends nothing at all. */
    private const val ENOUGH = 64 * 1024

    suspend fun sending(graph: ServiceLocator.Graph, channel: Channel, millis: Long = 4_000): Boolean = withContext(Dispatchers.IO) {
        val source = graph.sourceRepository.byId(channel.sourceId)
        // Stalker links are minted per play and can be single-use: don't spend one on a check.
        if (source?.kind == app.opentv.data.model.SourceKind.STALKER) return@withContext true
        val url = runCatching { graph.catalogRepository.previewUrl(channel, source) }.getOrNull() ?: return@withContext true
        // A download holds the provider's connection: don't test (it would fail, or knock the download off); assume fine.
        if (!app.opentv.data.repo.DownloadEngine.mayConnect(url)) return@withContext true
        val request = Request.Builder().url(url).header("User-Agent", source?.userAgent ?: "OpenTV/0.1 (Android)").build()
        // OkHttp's own deadline: a coroutine timeout can't break a blocked connect or read (it once took 22 s).
        val client = graph.streamingHttpClient.newBuilder().callTimeout(millis, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val call = client.newCall(request)
        val ok = kotlinx.coroutines.runInterruptible {
            runCatching {
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use false
                    val stream = response.body?.byteStream() ?: return@use false
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (total < ENOUGH) { val n = stream.read(buffer); if (n < 0) break; total += n }
                    total >= ENOUGH
                }
            }.getOrDefault(false)
        }
        call.cancel()
        ok
    }
}
