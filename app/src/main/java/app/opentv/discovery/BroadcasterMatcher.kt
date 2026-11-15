/*
 * jaskTV — GPL-3.0-or-later.
 * Local broadcaster-name comparison; does not claim a stream carries an event.
 */
package app.opentv.discovery

import java.util.Locale

internal object BroadcasterMatcher {
    private val symbols = Regex("[^a-z0-9]")

    fun matches(channelName: String, broadcasterName: String): Boolean {
        val channel = channelName.lowercase(Locale.ROOT).replace(symbols, "")
        val broadcaster = broadcasterName.lowercase(Locale.ROOT).replace(symbols, "")
        // Treat a complete broadcaster string as one identity, not unrelated matching words.
        // This intentionally does NOT equate "Sky Sports F1" with "Sky Sports Main Event".
        return broadcaster.length >= 3 && channel.contains(broadcaster)
    }
}
