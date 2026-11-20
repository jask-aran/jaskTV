package app.opentv.core

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pause syncing: stops background downloads of playlist providers' catalogues (channels, movies and shows) and TMDB
 * matching until resumed. The TV guide keeps updating, so Live stays current. What is on the device stays usable. Set from the sync indicator's menu
 * (`JaskSyncButton`); kept across restarts. A refresh the user asks for by hand still runs.
 */
object SyncPause {
    private const val PREFS = "jask_sync"
    private const val KEY = "paused"
    private val _paused = MutableStateFlow(false)
    @Volatile private var loaded = false

    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun isPaused(context: Context): Boolean {
        if (!loaded) { _paused.value = prefs(context).getBoolean(KEY, false); loaded = true }
        return _paused.value
    }

    fun set(context: Context, paused: Boolean) {
        val appContext = context.applicationContext
        prefs(appContext).edit().putBoolean(KEY, paused).apply()
        loaded = true
        _paused.value = paused
        val work = WorkManager.getInstance(appContext)
        if (paused) {
            // TMDB matching stops. The channel and guide refresh keeps its schedule: it skips the provider
            // catalogues while paused (stopping one under way) but still updates the TV guide.
            listOf("jask-canonical-movies", "jask-canonical-shows").forEach(work::cancelUniqueWork)
        } else {
            app.opentv.catalogue.CanonicalMovieWorker.enqueue(appContext)
            app.opentv.catalogue.CanonicalShowWorker.enqueue(appContext)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
