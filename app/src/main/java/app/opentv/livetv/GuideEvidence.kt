package app.opentv.livetv

import app.opentv.data.model.Channel
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.parser.ChannelNameNormalizer

object GuideEvidence {
    /** Manual mapping wins. Automatic IDs require exact channel-name evidence, never prefixes. */
    fun ids(channel: Channel, aliases: List<EpgChannelAlias>): List<String> {
        channel.epgOverrideId?.takeIf(String::isNotBlank)?.let { return listOf(it) }
        val key = ChannelNameNormalizer.normalize(channel.name).groupKey
        return listOfNotNull(channel.epgChannelId, channel.matchedEpgId).distinct().filter { id ->
            aliases.any { alias -> alias.epgId == id &&
                ChannelNameNormalizer.normalize(alias.displayName).groupKey == key &&
                (LiveChannelKeys.region(alias.displayName) == "unknown" ||
                    LiveChannelKeys.region(channel.name) == "unknown" ||
                    LiveChannelKeys.region(alias.displayName) == LiveChannelKeys.region(channel.name)) }
        }
    }
}
