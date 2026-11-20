/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the app is doing in the background right now: syncing the jaskTV account, downloading a provider's
 * channels or movies, building the guide, matching titles to TMDB. Each job says where its data comes from
 * ([Origin]) so the sync indicator (`JaskSyncIndicator`) can show it. Any job announces itself with [during]
 * (or [start]/[end]); several can run at once.
 */
object StatusBus {
    enum class Origin(val label: String) {
        ACCOUNT("jaskTV account"),
        PROVIDER("Playlist provider"),
        GUIDE("TV guide"),
        TMDB("TMDB"),
        NEARBY("Nearby device"),
    }

    data class Job(val id: String, val title: String, val origin: Origin, val detail: String? = null,
        val progress: Float? = null, val startedAt: Long = System.currentTimeMillis())

    private val _jobs = MutableStateFlow<List<Job>>(emptyList())

    /** Running jobs, oldest first. Empty when the app is idle. */
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    fun start(id: String, title: String, origin: Origin, detail: String? = null, progress: Float? = null) =
        _jobs.update { list -> list.filterNot { it.id == id } + Job(id, title, origin, detail, progress) }

    /** Change a running job's detail line and/or 0..1 progress. Does nothing if it has ended. */
    fun update(id: String, detail: String? = null, progress: Float? = null) =
        _jobs.update { list -> list.map { if (it.id == id) it.copy(detail = detail ?: it.detail, progress = progress ?: it.progress) else it } }

    fun end(id: String) = _jobs.update { list -> list.filterNot { it.id == id } }

    /** Announce a job while [block] runs, then clear it, even if the work throws. */
    suspend fun <T> during(id: String, title: String, origin: Origin, detail: String? = null, block: suspend () -> T): T {
        start(id, title, origin, detail)
        return try { block() } finally { end(id) }
    }

    // ---- Older single-line API (channel list build): one job under a fixed id. ----

    private const val LINE = "line"

    fun set(message: String?, progress: Float? = null) {
        if (message == null) end(LINE) else start(LINE, message, Origin.PROVIDER, progress = progress)
    }

    fun setProgress(progress: Float?) = update(LINE, progress = progress)

    suspend fun <T> during(message: String, block: suspend () -> T): T = during(message, message, Origin.PROVIDER, block = block)
}
