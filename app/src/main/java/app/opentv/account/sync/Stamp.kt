package app.opentv.account.sync

/**
 * Record stamps: "<13-digit ms>-<4 hex counter>-<8-char device tag>". They compare as text in time order and every
 * device breaks ties the same way, so the newest write wins everywhere without the devices talking it over.
 *
 * Time comes from this device's clock corrected by the server's ([offset], updated from every server reply), and
 * never goes backwards. A stamp can be given a time (a resume point is stamped when it was watched, not when it
 * synced). [SEED] stamps (time 1) lose to any real edit: a device joining an account uploads what only it has
 * with them, so its defaults never override settings made elsewhere.
 */
class StampClock(private val tag: String, private val wall: () -> Long = System::currentTimeMillis) {
    init { require(TAG.matches(tag)) }
    @Volatile var offset: Long = 0
    private var lastMs = 0L
    private var counter = 0

    fun now(): Long = wall() + offset

    @Synchronized fun next(at: Long? = null): String {
        val ms = maxOf(at ?: now(), 1)
        if (at == null) {
            if (ms > lastMs) { lastMs = ms; counter = 0 } else counter++
            return format(lastMs, counter)
        }
        return format(ms, 0)
    }

    fun seed(): String = format(1, 0)

    /** Learns the server's time from a reply (smoothed, so one slow reply doesn't jolt it). */
    fun observeServer(serverNow: Long) {
        val measured = serverNow - wall()
        offset = if (offset == 0L) measured else (offset * 3 + measured) / 4
    }

    private fun format(ms: Long, c: Int) = "%013d-%04x-%s".format(ms.coerceAtMost(9_999_999_999_999), c.coerceAtMost(0xffff), tag)

    companion object {
        val TAG = Regex("^[A-Za-z0-9_-]{8}$")
        fun timeOf(stamp: String): Long = stamp.substring(0, 13).toLong()
    }
}
