package app.opentv.catalogue

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/**
 * Every background TMDB request goes through here. TMDB's documented ceiling (developer.themoviedb.org/docs/
 * rate-limiting, checked 2026-11-18): no fixed quota since December 2019, "upper limits ... somewhere in the 40
 * requests per second range", and a 429 when exceeded. So: at most [PER_SECOND] requests a second, [CONCURRENT] at
 * once, and on a 429 everyone waits for its Retry-After before trying again.
 */
object TmdbGate {
    private const val PER_SECOND = 35
    private const val CONCURRENT = 16
    private val permits = Semaphore(CONCURRENT)
    private val spacing = Mutex()
    private var nextAt = 0L
    @Volatile private var pausedUntil = 0L

    // Background matching's own, lower pace (see [backgroundPace]); something on screen now skips it.
    @Volatile private var backgroundPermits = Semaphore(CONCURRENT)
    @Volatile private var backgroundPerSecond = PER_SECOND
    private val backgroundSpacing = Mutex()
    private var backgroundNextAt = 0L

    /**
     * How fast background matching may go. On a TV stick it is slow on purpose: the stick's CPU is needed for the UI,
     * and a phone or computer on the same account matches far faster and shares its matches over the LAN. Set once at
     * startup.
     */
    fun backgroundPace(perSecond: Int, concurrent: Int) {
        backgroundPerSecond = perSecond.coerceIn(1, PER_SECOND)
        backgroundPermits = Semaphore(concurrent.coerceIn(1, CONCURRENT))
    }

    /**
     * Runs [call], spaced and limited; retries a 429 up to three times. [read] gets the response (null: 404).
     * [urgent]: for something on screen now; otherwise the request also waits for background matching's pace.
     */
    suspend fun <T> request(call: () -> Call, urgent: Boolean = false, read: (Response) -> T): T? {
        if (urgent) return send(call, read)
        return backgroundPermits.withPermit {
            backgroundSpacing.withLock {
                val now = System.currentTimeMillis()
                val at = maxOf(now, backgroundNextAt)
                backgroundNextAt = at + 1000L / backgroundPerSecond
                if (at > now) delay(at - now)
            }
            send(call, read)
        }
    }

    private suspend fun <T> send(call: () -> Call, read: (Response) -> T): T? {
        repeat(4) {
            val result = permits.withPermit {
                spacing.withLock {
                    val now = System.currentTimeMillis()
                    val at = maxOf(now, nextAt, pausedUntil)
                    nextAt = at + 1000L / PER_SECOND
                    if (at > now) delay(at - now)
                }
                call().execute().use { r ->
                    when {
                        r.code == 429 -> {
                            val wait = r.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 2
                            pausedUntil = maxOf(pausedUntil, System.currentTimeMillis() + wait * 1000)
                            Retry
                        }
                        r.code == 404 -> null
                        !r.isSuccessful -> throw IOException("Metadata service HTTP ${r.code}")
                        else -> Done(read(r))
                    }
                }
            }
            when (result) {
                null -> return null
                is Done<*> -> @Suppress("UNCHECKED_CAST") return result.value as T
                else -> Unit // 429: go round again after the pause
            }
        }
        throw IOException("Metadata service busy (429)")
    }

    private object Retry
    private class Done<T>(val value: T)
}
