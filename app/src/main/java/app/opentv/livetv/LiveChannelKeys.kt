package app.opentv.livetv

import app.opentv.data.model.Channel
import app.opentv.data.parser.ChannelNameNormalizer

/** Country prefix is deliberately retained: the legacy groupKey alone drops it. */
object LiveChannelKeys {
    fun region(name: String): String = when (val value = ChannelNameNormalizer.normalize(name).region?.uppercase()) {
        "UK" -> "GB"
        "USA" -> "US"
        null -> "unknown"
        else -> value
    }
    fun provisional(channel: Channel): String = if (channel.groupKey.isBlank())
        "provider:${channel.sourceId}:${channel.streamId}" else "group:${region(channel.name)}:${channel.groupKey}"
}
