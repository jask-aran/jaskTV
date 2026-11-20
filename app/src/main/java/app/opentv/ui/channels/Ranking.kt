package app.opentv.ui.channels

import app.opentv.data.model.Channel
import app.opentv.livetv.StreamHealth
import app.opentv.livetv.find.EventCard
import app.opentv.livetv.find.WatchOption

/*
 * One order for live streams everywhere they're listed or tried: the sheet, the card's named channel, Watch live,
 * the fallback chain, a channel's copies. Known-good first, then the provider's own order, then known-poor, then
 * what has just shown no picture (NoPicture, this session). The learned part is StreamHealth.
 */

private fun bucket(record: StreamHealth.Record, parked: Boolean) = when {
    parked -> 3
    record.poor -> 2
    record.trusted -> 0
    else -> 1
}

/** A game's ways to watch, best first. Ties keep the finder's order (evidence, region, series-named channels). */
internal fun rankWays(sport: String, options: List<WatchOption>): List<WatchOption> =
    options.withIndex().sortedWith(compareBy({ (_, o) ->
        bucket(StreamHealth.ofWay(sport, o.title), o.streamIds.isNotEmpty() && o.streamIds.all(NoPicture::parked))
    }, { it.index })).map { it.value }

internal fun EventCard.rankedOptions(): List<WatchOption> = rankWays(event.sport, options)

/**
 * Copies of one channel (or one way's channels), best first. Copies sharper than this screen go after ones that fit
 * it: a 4K copy on a 1080p phone costs data and decoding for nothing, and a weak decoder (the emulator) can't keep
 * up. Then known-good first (the better record first), the provider's order, known-poor, just shown dead.
 */
internal fun rankCopies(channels: List<Channel>): List<Channel> {
    val screen = screenLines
    return channels.withIndex().map { (i, c) -> Triple(i, c, StreamHealth.of(c)) }
        .sortedWith(compareBy({ (_, c, r) -> bucket(r, NoPicture.parked(c.id)).let { if (it >= 2) it else 0 } },
            { (_, c, _) -> if ((copyLines(c.name) ?: 0) > screen) 1 else 0 },
            { (_, c, r) -> bucket(r, NoPicture.parked(c.id)) },
            { (_, _, r) -> if (r.trusted) -r.score else 0f }, { it.first }))
        .map { it.second }
}

/** A copy's picture height from its name's tags (8K, 4K, 3840P, FHD, HD…); null when it doesn't say. */
internal fun copyLines(name: String): Int? {
    val n = name.uppercase()
    return when {
        "8K" in n.replace("8K EXCLUSIVE", "") -> 4320 // "8K EXCLUSIVE" is a provider's slot label, not a resolution
        Regex("""\b4K\b|UHD|2160|3840|ᵁᴴᴰ|⁴ᵏ|³⁸⁴⁰""").containsMatchIn(n) -> 2160
        Regex("""FHD|1080""").containsMatchIn(n) -> 1080
        else -> null
    }
}

/** The short side of this display's real panel (the Fire TV's 4K output, not its 1080p UI). Set at startup. */
@Volatile internal var screenLines = 2160

internal fun readScreenLines(context: android.content.Context) {
    val mode = runCatching {
        (context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).mode
    }.getOrNull() ?: return
    screenLines = minOf(mode.physicalWidth, mode.physicalHeight)
}
