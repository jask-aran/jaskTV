/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import android.app.Application
import android.content.Context
import app.opentv.core.LocaleUtils
import app.opentv.core.ServiceLocator
import app.opentv.data.repo.CatalogRepository
import app.opentv.data.work.SyncWorker
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OpenTvApp : Application(), ImageLoaderFactory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The app-wide Coil loader, tuned for a poster-and-logo heavy UI on a low-end TV box. The
     * default loader keeps a small memory cache and no disk cache, so scrolling back through a
     * shelf — or reopening Movies — re-downloads and re-decodes every image. Here:
     *  - a generous memory cache and a 256 MB disk cache mean art you've already seen paints from
     *    cache, instantly, instead of hitting the network;
     *  - no crossfade — an immediate swap reads as snappier on a d-pad grid than a fade, and skips
     *    a frame of blending per image;
     *  - RGB_565 for opaque art (posters/backdrops) halves the memory per bitmap, so more fits in
     *    cache; Coil keeps ARGB_8888 for anything with transparency, so channel logos are untouched.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .allowRgb565(true)
            // Wikimedia (many IPTV-org channel logos) answers 403 to OkHttp's default User-Agent.
            .okHttpClient {
                // OkHttp allows 5 downloads per host by default; nearly every poster is on image.tmdb.org, so a
                // fast scroll queued behind 5 at a time. 16 keeps up with the grid and its prefetch.
                okhttp3.OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
                    // TMDB art from jasktv-core's cache on the home network when one answers; TMDB itself otherwise.
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val core = if (request.url.host == "image.tmdb.org") app.opentv.account.CoreDirectory.endpoint("images") else null
                        if (core != null) {
                            val viaCore = request.newBuilder().url("${core.base}/img${request.url.encodedPath}")
                                .header("Authorization", "Bearer ${core.secret}").build()
                            val response = runCatching { chain.proceed(viaCore) }.getOrNull()
                            if (response != null && response.isSuccessful) return@addInterceptor response
                            response?.close()
                        }
                        chain.proceed(request)
                    }
                    .addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(if (request.header("User-Agent") != null) request
                        else request.newBuilder().header("User-Agent", IMAGE_USER_AGENT).build())
                }.build()
            }
            .build()

    // So notifications and any app-context resources use the chosen language too, not just the UI.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleUtils.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        val graph = ServiceLocator.get(this)
        app.opentv.player.PlaybackErrors.context = this
        app.opentv.core.AppText.init(this)
        app.opentv.account.CoreDirectory.init(this)
        app.opentv.widget.RecentWidgetUpdates.start(this, appScope)
        SyncWorker.schedule(this)
        // A TV stick matches titles to TMDB slowly in the background (3 a second), leaving its CPU to the UI; a phone or
        // computer on the same account matches at full speed and the TV copies those matches over the LAN.
        if (isRunningOnTelevision(this)) app.opentv.catalogue.TmdbGate.backgroundPace(perSecond = 3, concurrent = 2)
        // jaskTV account (optional): if signed in, sync playlists and preferences.
        app.opentv.account.AccountManager.get(this).start()
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val store = graph.localPlaylistStore
                val sources = graph.database.sources().all()
                store.reconcileStartup(sources.mapNotNull { it.localPlaylistFileName }.toSet())
            }
        }

        // When the normaliser has moved on since the catalogue was last processed, re-clean
        // the stored channels and re-run the guide matcher — locally, no re-download. This
        // is why a name-cleanup fix shows up on the next launch rather than the next 6-hour
        // sync, and it costs nothing when the version has not changed.
        // Cold-start reconciliation: a recording still marked "in progress" at process start is an
        // orphan from a killed capture. Done here, once per process — NOT when the Recordings screen
        // opens — so checking on a recording you just started never marks it interrupted.
        appScope.launch { runCatching { graph.recordingRepository.failInterrupted() } }

        // Re-arm scheduled recordings on every launch, for the same reason as the reminders below:
        // a force-stop or app update drops their exact alarms and only a reboot is covered by the
        // boot receiver. Re-setting the same alarm is idempotent, so this quietly keeps bookings alive.
        appScope.launch { runCatching { graph.recordingEngine.rearmScheduled() } }

        // Re-arm programme reminders on every launch. Alarms are lost on a force-stop or app
        // update (not just a reboot, which the boot receiver already covers), and re-setting an
        // exact alarm for the same reminder is idempotent — so this quietly keeps bells alive.
        appScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                graph.reminderRepository.deleteEndedBefore(now)
                graph.reminderRepository.upcoming(now).forEach {
                    app.opentv.reminders.ReminderScheduler.set(this@OpenTvApp, it.id, it.startUtcMillis)
                }
            }
        }


        // A queue left mid-way (app killed, phone rebooted) picks up where it stopped.
        appScope.launch { runCatching { graph.downloadRepository.pump() } }

        appScope.launch {
            val prefs = getSharedPreferences("opentv", MODE_PRIVATE)
            val seen = prefs.getInt("normalizer_version", 0)
            if (seen < CatalogRepository.NORMALIZER_VERSION) {
                graph.catalogRepository.renormalizeAll()
                prefs.edit().putInt("normalizer_version", CatalogRepository.NORMALIZER_VERSION).apply()
            }
            // Runs on every launch. It is cheap when nothing is stale (feeds within their
            // refresh window are skipped), but it is what makes the free regional guide turn
            // itself on and download the first time — without waiting for the user to find
            // the refresh button. ensureFeeds + auto-enable-by-region + matcher all live here.
            graph.epgRepository.syncAll(System.currentTimeMillis(), force = false)
        }
    }

    companion object {
        /** Generic client name for image requests; no user or device details. */
        const val IMAGE_USER_AGENT = "jaskTV/1.0 (Android)"
    }
}
