package app.opentv.account

import android.content.Context
import android.content.SharedPreferences
import app.opentv.core.AppSettings
import app.opentv.data.model.StremioAddon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The preferences an account syncs: everything you choose about what you watch and how, so a new device opens
 * set up. The latest change wins, per setting. Settings that depend on the screen (interface size, glass,
 * rotation) sync between devices of the same kind ([deviceKind]: a TV's size reaches other TVs, not the phone).
 * Not synced: where recordings go (a USB folder exists on one device), the "show hidden" unlock (so it can't
 * travel to a child's device) and which profile a device is using.
 */
class AccountPrefs(context: Context, private val s: AppSettings, private val deviceKind: String = "phone") {
    private val catalogue: SharedPreferences = context.getSharedPreferences("jask_catalogue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private class Pref(val key: String, val read: () -> JsonElement?, val write: (JsonElement) -> Unit)

    private fun bool(key: String, get: () -> Boolean, set: (Boolean) -> Unit) =
        Pref(key, { JsonPrimitive(get()) }, { e -> e.jsonPrimitive.booleanOrNull?.let(set) })
    private fun int(key: String, get: () -> Int, set: (Int) -> Unit) =
        Pref(key, { JsonPrimitive(get()) }, { e -> e.jsonPrimitive.intOrNull?.let(set) })
    private fun text(key: String, get: () -> String, set: (String) -> Unit) =
        Pref(key, { JsonPrimitive(get()) }, { e -> e.jsonPrimitive.contentOrNull?.let(set) })
    private fun set(key: String, get: () -> Set<String>?, put: (Set<String>) -> Unit) =
        Pref(key, { get()?.sorted()?.let { JsonArray(it.map(::JsonPrimitive)) } }, { e -> put(e.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()) })
    private fun list(key: String, get: () -> List<String>, put: (List<String>) -> Unit) =
        Pref(key, { JsonArray(get().map(::JsonPrimitive)) }, { e -> put(e.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }) })
    private inline fun <reified E : Enum<E>> enum(key: String, crossinline get: () -> E, crossinline set: (E) -> Unit) =
        Pref(key, { JsonPrimitive(get().name) }, { e -> e.jsonPrimitive.contentOrNull?.let { n -> enumValues<E>().firstOrNull { it.name == n } }?.let { set(it) } })

    private val prefs = listOf(
        // Live TV
        set("liveRegions", { s.liveRegions.value }, s::setLiveRegions),
        set("followedSports", { s.followedSports.value }, s::setFollowedSports),
        bool("guidePreviewVideo", { s.guidePreviewVideo.value }, s::setGuidePreviewVideo),
        bool("guidePreviewSound", { s.guidePreviewSound.value }, s::setGuidePreviewSound),
        enum("channelLayout", { s.channelLayout.value }, s::setChannelLayout),
        enum("phoneLiveLayout", { s.phoneLiveLayout.value }, s::setPhoneLiveLayout),
        enum("channelSort", { s.channelSort.value }, s::setChannelSort),
        set("shownCategories", { s.shownCategories.value }, s::setShownCategories),
        bool("livePause", { s.livePauseEnabled.value }, s::setLivePauseEnabled),
        bool("resumeLastChannel", { s.resumeLastChannel.value }, s::setResumeLastChannel),
        // What the app shows
        bool("liveEnabled", { s.liveEnabled.value }, s::setLiveEnabled),
        bool("moviesEnabled", { s.moviesEnabled.value }, s::setMoviesEnabled),
        bool("seriesEnabled", { s.seriesEnabled.value }, s::setSeriesEnabled),
        text("language", { s.languageTag.value }, s::setLanguageTag),
        enum("theme", { s.themeMode.value }, s::setThemeMode),
        set("catalogueLanguages", { catalogue.getStringSet("languages", emptySet())!!.toSet() }, { catalogue.edit().putStringSet("languages", it).apply() }),
        bool("catalogueUnknown", { catalogue.getBoolean("unknown", true) }, { catalogue.edit().putBoolean("unknown", it).apply() }),
        text("catalogueSort", { catalogue.getString("sort", "TITLE")!! }, { catalogue.edit().putString("sort", it).apply() }),
        bool("catalogueUnavailable", { catalogue.getBoolean("unavailable", false) }, { catalogue.edit().putBoolean("unavailable", it).apply() }),
        list("recentSearches", { s.recentSearches.value }, s::setRecentSearches),
        // Playback
        bool("subtitles", { s.subtitlesEnabled.value }, s::setSubtitlesEnabled),
        int("resizeMode", { s.playerResizeMode.value }, s::setPlayerResizeMode),
        // Parental: the PIN (as its hash) and what it hides
        Pref("pinHash", { s.pinHash()?.let(::JsonPrimitive) ?: JsonNull }, { e -> s.setPinHash(e.jsonPrimitive.contentOrNull) }),
        set("hiddenCategories", { s.hiddenCategories.value }, s::setHiddenCategories),
        // Recording and the shared NAS
        int("recordPadStart", { s.recordPadStartMinutes.value }, { s.setRecordPadding(it, s.recordPadEndMinutes.value) }),
        int("recordPadEnd", { s.recordPadEndMinutes.value }, { s.setRecordPadding(s.recordPadStartMinutes.value, it) }),
        bool("recordAutoSwitch", { s.recordAutoSwitch.value }, s::setRecordAutoSwitch),
        Pref("nas", { JsonArray(listOf(s.smbHost.value, s.smbShare.value, s.smbFolder.value, s.smbUser.value, s.smbPassword.value).map(::JsonPrimitive)) }, { e ->
            val v = e.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }
            if (v.size == 5) s.setSmbConfig(v[0], v[1], v[2], v[3], v[4])
        }),
        // Screen settings, per kind of device
        text("$deviceKind.uiScale", { s.uiScale.value }, s::setUiScale),
        text("$deviceKind.glass", { s.glassMode.value }, s::setGlassMode),
        bool("$deviceKind.autoRotate", { s.phoneAutoRotate.value }, s::setPhoneAutoRotate),
        // Services: API keys and add-ons
        text("tmdbKey", { s.tmdbApiKey.value }, s::setTmdbApiKey),
        text("omdbKey", { s.omdbApiKey.value }, s::setOmdbApiKey),
        // Your own torrent services, when you run them yourself rather than through jasktv-core.
        text("prowlarrUrl", { s.prowlarrUrl.value }, s::setProwlarrUrl),
        text("prowlarrKey", { s.prowlarrKey.value }, s::setProwlarrKey),
        text("torrServerUrl", { s.torrServerUrl.value }, s::setTorrServerUrl),
        Pref("stremioAddons", { json.encodeToJsonElement(ListSerializer(StremioAddon.serializer()), s.stremioAddons.value) }, { e ->
            runCatching { s.setStremioAddons(json.decodeFromJsonElement(ListSerializer(StremioAddon.serializer()), e)) }
        }),
    )

    fun read(): Map<String, JsonElement> = prefs.mapNotNull { p -> p.read()?.let { p.key to it } }.toMap()

    /** Applies only what differs, and only keys this version knows (a newer version's keys are ignored). */
    fun apply(values: Map<String, JsonElement>) {
        val now = read()
        for (p in prefs) {
            val v = values[p.key] ?: continue
            if (v != now[p.key]) runCatching { p.write(v) }
        }
    }

    /** Emits whenever any synced preference changes. */
    fun changes(): Flow<Any> = merge<Any>(
        combine(listOf(s.liveRegions, s.followedSports, s.guidePreviewVideo, s.guidePreviewSound, s.subtitlesEnabled, s.channelLayout,
            s.phoneLiveLayout, s.channelSort, s.shownCategories, s.hiddenCategories, s.pinIsSet, s.languageTag, s.themeMode, s.liveEnabled,
            s.moviesEnabled, s.seriesEnabled, s.livePauseEnabled, s.resumeLastChannel, s.playerResizeMode, s.recordPadStartMinutes,
            s.recordPadEndMinutes, s.recordAutoSwitch, s.smbHost, s.smbShare, s.smbFolder, s.smbUser, s.smbPassword,
            s.tmdbApiKey, s.omdbApiKey, s.stremioAddons, s.prowlarrUrl, s.prowlarrKey, s.torrServerUrl, s.recentSearches, s.uiScale, s.glassMode, s.phoneAutoRotate)) { it.toList() },
        callbackFlow<Any> {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
            catalogue.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { catalogue.unregisterOnSharedPreferenceChangeListener(listener) }
        },
    )
}
