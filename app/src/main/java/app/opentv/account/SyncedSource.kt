package app.opentv.account

import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import kotlinx.serialization.Serializable

/** What a synced playlist carries: everything needed to add it on another device, credentials included (sealed). */
@Serializable
data class SyncedSource(
    val name: String, val kind: String, val url: String, val username: String? = null, val password: String? = null,
    val macAddress: String? = null, val epgUrl: String? = null, val userAgent: String = Source.DEFAULT_USER_AGENT,
    val liveFormat: String = LiveStreamFormat.HLS.name, val enabled: Boolean = true,
) {
    fun toSource(id: Long = 0, keep: Source? = null): Source = (keep ?: Source(name = name, kind = SourceKind.valueOf(kind), url = url)).copy(
        id = id, name = name, kind = SourceKind.valueOf(kind), url = url, username = username, password = password, macAddress = macAddress,
        epgUrl = epgUrl, userAgent = userAgent, liveFormat = runCatching { LiveStreamFormat.valueOf(liveFormat) }.getOrDefault(LiveStreamFormat.HLS),
        enabled = enabled)

    companion object {
        fun of(s: Source) = SyncedSource(s.name, s.kind.name, s.url, s.username, s.password, s.macAddress, s.epgUrl, s.userAgent, s.liveFormat.name, s.enabled)
    }
}
