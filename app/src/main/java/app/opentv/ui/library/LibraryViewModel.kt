package app.opentv.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.opentv.catalogue.CanonicalMovie
import app.opentv.catalogue.CanonicalShow
import app.opentv.catalogue.identityEvidence
import app.opentv.catalogue.tmdbHint
import app.opentv.catalogue.withCanonical
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Episode
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.parser.episodeDisplayTitle
import app.opentv.ui.vod.cleanEpisodeTitle
import app.opentv.ui.vod.cleanShowTitle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Touch Library data: Continue watching (part-watched titles, plus the next episode of a show whose
 * last episode you finished, whether or not it's on the Watchlist), Watched history, and the
 * Watchlist (starred titles; older collection entries are included so nothing saved disappears).
 * Titles and art come from TMDB when the movie is canonically matched or the show's provider hint
 * was validated; provider data otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val db = graph.database
    private val profile = graph.settings.activeProfileId
    private val prefs = app.getSharedPreferences("jask_library", android.content.Context.MODE_PRIVATE)
    /** Bumped after a background episode refresh so Continue watching re-evaluates. */
    private val refreshed = MutableStateFlow(0)

    data class Activity(
        val mediaKey: String,
        val title: String,
        /** "S1 · E2" for episodes, null for movies. */
        val episodeCode: String?,
        /** The episode's own name, null for movies. */
        val episodeName: String?,
        /** Facts like the grids: movies ★ rating and year · language; shows network and Running/Ended. */
        val chips: List<String>,
        /** "Ep 2 of 10" for shows. */
        val seasonProgress: String?,
        val wideArt: String?,
        val poster: String?,
        val progress: Float,
        val remainingMinutes: Int?,
        val upNext: Boolean,
        /** Inferred: the show is running and the provider updated it after you finished the previous episode. */
        val newEpisode: Boolean,
        val durationMillis: Long,
        val movieId: Long?,
        val seriesRowId: Long?,
        val updatedAt: Long,
    ) {
        val isMovie get() = movieId != null
        /** Search text: title and episode. */
        val searchText get() = listOfNotNull(title, episodeName).joinToString(" ")
    }

    data class LibraryActivity(val continueWatching: List<Activity> = emptyList(), val watched: List<Activity> = emptyList())

    data class WatchItem(val key: String, val movie: Movie?, val series: Series?, val title: String, val poster: String?,
        val year: Int?, val rating: Double?, val language: String?, val overview: String?, val chips: List<String>)

    val activity: StateFlow<LibraryActivity> = profile
        .flatMapLatest { pid -> combine(db.positions().observeRecent(pid, 150), refreshed) { positions, _ -> positions } }
        .mapLatest(::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryActivity())

    val watchlist: StateFlow<List<WatchItem>> = profile.flatMapLatest { pid ->
        val collections = db.collections()
        combine(db.movies().observeFavourites(), db.series().observeFavourites(),
            collections.observeAllCollectedMovies(pid), collections.observeAllCollectedShows(pid)) { fm, fs, cm, cs -> (fm + cm) to (fs + cs) }
    }.mapLatest { (movies, shows) ->
        val movieItems = movies.map { raw ->
            val title = canonicalMovie(raw)
            val m = title?.let(raw::withCanonical) ?: raw
            WatchItem(title?.let { "tmdb:${it.tmdbId}" } ?: "m:${raw.sourceId}:${raw.streamId}", raw, null, title?.title ?: cleanShowTitle(raw.name),
                m.posterUrl, m.year, m.rating, title?.originalLanguage, m.plot, movieChips(m.rating, m.year, title?.originalLanguage, m.durationSeconds))
        }
        val showItems = shows.map { raw ->
            val show = canonicalShow(raw)
            val s = show?.let(raw::withCanonical) ?: raw
            WatchItem(raw.tmdbHint()?.let { "tv:$it" } ?: "s:${raw.sourceId}:${raw.seriesId}", null, raw, cleanShowTitle(s.name),
                s.posterUrl, s.year, s.rating, show?.originalLanguage, s.plot, showChips(show, s))
        }
        (movieItems + showItems).distinctBy { it.key }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloads = graph.downloadRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forget(item: Activity) = viewModelScope.launch {
        db.positions().delete(profile.value, item.mediaKey)
    }

    /** Records the title as finished (duration unknown → 1/1), which moves it to Watched. */
    fun markWatched(item: Activity) = viewModelScope.launch {
        val duration = item.durationMillis.takeIf { it > 0 } ?: 1L
        db.positions().upsert(PlaybackPosition(profile.value, item.mediaKey, duration, duration, System.currentTimeMillis()))
    }

    suspend fun movieRow(id: Long): Movie? = db.movies().byId(id)
    suspend fun seriesRow(id: Long): Series? = db.series().byId(id)

    private suspend fun canonicalMovie(raw: Movie): CanonicalMovie? {
        val dao = db.canonicalMovies()
        val link = dao.match(raw.sourceId, raw.streamId) ?: return null
        if (!link.manual && link.evidence != raw.identityEvidence()) return null
        return link.tmdbId?.let { dao.title(it) }
    }

    private suspend fun canonicalShow(raw: Series): CanonicalShow? = raw.tmdbHint()?.let { db.canonicalShows().validated(it) }

    private fun movieChips(rating: Double?, year: Int?, language: String?, seconds: Int?) = listOfNotNull(
        rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) },
        listOfNotNull(year?.toString(), language?.uppercase(Locale.ROOT)).joinToString(" · ").ifBlank { null },
        seconds?.takeIf { it > 0 }?.let { (it / 60).let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" } },
    )

    private fun showChips(show: CanonicalShow?, s: Series) =
        listOfNotNull(show?.network, show?.statusLabel).ifEmpty { listOfNotNull(s.year?.toString()) }

    /** Shows you finished the latest local episode of; re-checked with the provider for new ones. */
    private val caughtUp = mutableSetOf<Series>()
    /** Shows whose provider refresh found episodes you hadn't had locally: those are new to you. */
    private val discovered = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private suspend fun build(positions: List<PlaybackPosition>): LibraryActivity {
        val resume = mutableListOf<Activity>()
        val watched = mutableListOf<Activity>()
        val seenShows = HashSet<String>()
        val byKey = positions.associateBy { it.mediaKey }
        caughtUp.clear()
        for (pos in positions) { // newest first
            val (kind, id) = pos.mediaKey.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].toLongOrNull() } ?: continue
            id ?: continue
            when (kind) {
                "movie" -> {
                    val raw = db.movies().byId(id) ?: continue
                    val title = canonicalMovie(raw)
                    val m = title?.let(raw::withCanonical) ?: raw
                    val entry = Activity(pos.mediaKey, title?.title ?: cleanShowTitle(raw.name), null, null,
                        movieChips(m.rating, m.year, title?.originalLanguage, null), null,
                        m.backdropUrl ?: m.posterUrl, m.posterUrl, progress(pos), remaining(pos), false, false,
                        pos.durationMillis, raw.id, null, pos.updatedAtMillis)
                    if (pos.isFinished) watched += entry else resume += entry
                }
                "ep" -> {
                    val ep = db.episodes().byId(id) ?: continue
                    if (!seenShows.add("${ep.sourceId}:${ep.seriesId}")) continue // only the latest activity per show counts
                    val raw = db.series().byProviderId(ep.sourceId, ep.seriesId)
                    val show = raw?.let { canonicalShow(it) }
                    val s = raw?.let { r -> show?.let(r::withCanonical) ?: r }
                    val all = db.episodes().forSeries(ep.sourceId, ep.seriesId)
                        .sortedWith(compareBy<Episode>({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episodeNumber }))
                    fun activity(e: Episode, p: PlaybackPosition?, upNext: Boolean, isNew: Boolean): Activity {
                        val inSeason = all.filter { it.season == e.season }
                        return Activity("ep:${e.id}", s?.name?.let(::cleanShowTitle) ?: "", "S${e.season} · E${e.episodeNumber}",
                            cleanEpisodeTitle(episodeDisplayTitle(e.title, raw?.name), e.episodeNumber),
                            if (s != null) showChips(show, s) else emptyList(),
                            inSeason.size.takeIf { it > 1 }?.let { "Ep ${inSeason.indexOfFirst { it.id == e.id } + 1} of $it" },
                            e.stillUrl ?: s?.backdropUrl ?: s?.posterUrl, s?.posterUrl,
                            p?.let(::progress) ?: 0f, p?.let(::remaining), upNext, isNew, p?.durationMillis ?: 0L, null, raw?.id, pos.updatedAtMillis)
                    }
                    if (!pos.isFinished) { resume += activity(ep, pos, false, false); continue }
                    watched += activity(ep, pos, false, false)
                    val next = all.getOrNull(all.indexOfFirst { it.id == ep.id } + 1)
                    if (next != null && byKey["ep:${next.id}"]?.isFinished != true) {
                        val isNew = "${ep.sourceId}:${ep.seriesId}" in discovered ||
                            (show?.statusLabel == "Running" && (raw?.addedMillis ?: 0L) > pos.updatedAtMillis)
                        resume += activity(next, byKey["ep:${next.id}"], true, isNew)
                    } else if (next == null && raw != null && show?.statusLabel != "Ended" && show?.statusLabel != "Cancelled" &&
                        System.currentTimeMillis() - pos.updatedAtMillis < 90L * DAY) caughtUp += raw
                }
            }
        }
        refreshCaughtUp()
        return LibraryActivity(resume, watched)
    }

    private var refreshing = false

    /** Asks the provider for new episodes of up to 8 caught-up shows, each at most every 6 hours. */
    private fun refreshCaughtUp() {
        if (refreshing) return
        val now = System.currentTimeMillis()
        val due = caughtUp.filter { now - prefs.getLong("ep:${it.sourceId}:${it.seriesId}", 0L) > 6 * HOUR }.take(8)
        if (due.isEmpty()) return
        refreshing = true
        viewModelScope.launch {
            try {
                var added = false
                for (series in due) {
                    val source = graph.sourceRepository.byId(series.sourceId) ?: continue
                    val before = db.episodes().forSeries(series.sourceId, series.seriesId).size
                    graph.catalogRepository.ensureEpisodes(source, series.seriesId)
                    prefs.edit().putLong("ep:${series.sourceId}:${series.seriesId}", now).apply()
                    if (db.episodes().forSeries(series.sourceId, series.seriesId).size > before) {
                        added = true; discovered += "${series.sourceId}:${series.seriesId}"
                    }
                }
                if (added) refreshed.value++
            } finally { refreshing = false }
        }
    }

    private fun progress(p: PlaybackPosition) = if (p.durationMillis > 0) (p.positionMillis.toFloat() / p.durationMillis).coerceIn(0f, 1f) else 0f
    private fun remaining(p: PlaybackPosition) = if (p.durationMillis > 0 && !p.isFinished)
        ((p.durationMillis - p.positionMillis) / 60_000L).toInt().coerceAtLeast(1) else null

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
