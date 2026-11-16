/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.repo.providerBrowseKey
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.map
import app.opentv.catalogue.tmdbHint
import app.opentv.catalogue.TmdbRankings
import app.opentv.catalogue.RankedTitle
import app.opentv.catalogue.CanonicalShow
import app.opentv.catalogue.ShowMatching
import app.opentv.data.repo.VodBrowseRepository
import app.opentv.data.repo.VodBrowseFilter
import app.opentv.data.repo.VodCategoryKey
import app.opentv.data.repo.providerBrowseKey
import app.opentv.catalogue.CatalogueSort
import app.opentv.catalogue.identityEvidence
import app.opentv.catalogue.withCanonical
import app.opentv.R
import app.opentv.core.AppText
import app.opentv.core.ServiceLocator
import app.opentv.core.StatusBus
import app.opentv.data.model.Category
import app.opentv.data.model.Movie
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Series
import app.opentv.data.model.Source
import app.opentv.data.model.StremioStream
import app.opentv.data.model.StreamKind
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.VodTitleCleaner
import app.opentv.data.repo.MovieVariantGroup
import app.opentv.data.repo.PersonTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class VodViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = ServiceLocator.get(app)
    private val settings = graph.settings
    val metadataConfigured get() = settings.tmdbApiKey.value.isNotBlank()
    private val canonical = graph.database.canonicalMovies()
    private val cataloguePrefs = app.getSharedPreferences("jask_catalogue", android.content.Context.MODE_PRIVATE)
    data class MovieOptions(val languages: Set<String> = emptySet(), val includeUnknown: Boolean = true,
        val sort: CatalogueSort = CatalogueSort.TITLE)
    val movieOptions = MutableStateFlow(MovieOptions(
        cataloguePrefs.getStringSet("languages", emptySet())!!.toSet(),
        cataloguePrefs.getBoolean("unknown", true),
        runCatching { CatalogueSort.valueOf(cataloguePrefs.getString("sort", "TITLE")!!) }
            .getOrDefault(CatalogueSort.TITLE)))
    val catalogueLanguages = canonical.languages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun applyMovieOptions(options: MovieOptions) {
        cataloguePrefs.edit().putStringSet("languages", options.languages).putBoolean("unknown", options.includeUnknown)
            .putString("sort", options.sort.name).apply()
        movieOptions.value = options
    }
    suspend fun canonicalMovie(id: Long): Movie? {
        val raw = graph.database.movies().byId(id) ?: return null
        val link = canonical.match(raw.sourceId, raw.streamId) ?: return raw
        if (!link.manual && link.evidence != raw.identityEvidence()) return raw
        return link.tmdbId?.let { canonical.title(it) }?.let { raw.withCanonical(it) } ?: raw
    }
    suspend fun rawMovieName(id: Long): String = graph.database.movies().byId(id)?.name.orEmpty()
    suspend fun canonicalVersions(id: Long): List<Movie> {
        val raw = graph.database.movies().byId(id) ?: return emptyList()
        val link = canonical.match(raw.sourceId, raw.streamId) ?: return emptyList()
        if (!link.manual && link.evidence != raw.identityEvidence()) return emptyList()
        val title = link.tmdbId?.let { canonical.title(it) } ?: return emptyList()
        return canonical.versions(title.tmdbId).map { it.withCanonical(title) }
    }

    /** One card in the Continue Watching shelf — a movie or episode with somewhere left to go. */
    data class ResumeItem(
        val mediaKey: String,
        val title: String,
        val posterUrl: String?,
        val streamUrl: String,
        val progress: Float,
        val sourceId: Long,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val continueWatching: StateFlow<List<ResumeItem>> =
        settings.activeProfileId
            .flatMapLatest { pid -> graph.playbackPositions.observeRecent(pid) }
            .mapLatest { positions -> positions.filter { !it.isFinished }.mapNotNull { resolveResume(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun resolveResume(pos: PlaybackPosition): ResumeItem? {
        val parts = pos.mediaKey.split(":", limit = 2)
        if (parts.size != 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val progress =
            if (pos.durationMillis > 0) (pos.positionMillis.toFloat() / pos.durationMillis).coerceIn(0f, 1f) else 0f
        return when (parts[0]) {
            "movie" -> graph.catalogRepository.movie(id)?.let {
                ResumeItem(pos.mediaKey, it.displayTitle, it.posterUrl, it.streamUrl, progress, it.sourceId)
            }
            "ep" -> graph.catalogRepository.episode(id)?.let {
                // "Show · S1E1 · Episode" reads cleanly; the raw provider title
                // ("A+ - Show - S01E01 - Episode") doesn't fit a card.
                val show = graph.catalogRepository.seriesByProviderId(it.sourceId, it.seriesId)
                val episodeTitle = app.opentv.data.parser.episodeDisplayTitle(it.title, show?.name)
                ResumeItem(
                    pos.mediaKey,
                    listOfNotNull(
                        show?.let { s -> VodTitleCleaner.clean(s.name).replace(Regex("\\s*\\(\\d{4}\\)$"), "") },
                        "S${it.season}E${it.episodeNumber}",
                        episodeTitle.takeIf { t -> t.isNotBlank() },
                    ).joinToString(" · "),
                    it.stillUrl,
                    it.streamUrl,
                    progress,
                    it.sourceId,
                )
            }
            else -> null
        }
    }

    private val movieCategory = MutableStateFlow<String?>(null)
    private val seriesCategory = MutableStateFlow<String?>(null)
    val selectedMovieCategory = movieCategory.asStateFlow()
    val selectedSeriesCategory = seriesCategory.asStateFlow()
    private val browseRepository = VodBrowseRepository(graph.database.movies(), graph.database.series())

    /** Provider filter for Movies/Shows. null = every source. Surfaced only when there's >1 source. */
    private val vodSource = MutableStateFlow<Long?>(null)
    val selectedVodSource = vodSource.asStateFlow()

    /** Providers, so Movies/Shows can offer a source filter (shown only when there's more than one). */
    val sources: StateFlow<List<Source>> =
        graph.sourceRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The category token includes its provider even when browsing "All sources".
    val movieCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.MOVIE),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seriesCategories: StateFlow<List<Category>> =
        combine(
            graph.catalogRepository.observeCategories(StreamKind.SERIES),
            selectedVodSource,
        ) { raw, sourceId -> if (sourceId == null) raw else raw.filter { it.sourceId == sourceId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val browseInput = MutableStateFlow("")
    val browseQuery = browseInput.asStateFlow()
    fun setBrowseQuery(text: String) { browseInput.value = text }

    @OptIn(FlowPreview::class)
    private val settledBrowseQuery = browseInput.map { it.trim() }.debounce(200).distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val canonicalMoviePages = combine(selectedVodSource, settledBrowseQuery, movieOptions) { source, query, options ->
        Triple(source, query, options)
    }.flatMapLatest { (source, query, options) ->
        androidx.paging.Pager(androidx.paging.PagingConfig(pageSize = 48, initialLoadSize = 96,
            enablePlaceholders = false, maxSize = 480)) {
            canonical.browse(source, query, options.languages.sorted().joinToString("", transform = { "|$it|" }),
                options.includeUnknown, options.sort.name)
        }.flow
    }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val moviePages = combine(selectedVodSource, movieCategory, settledBrowseQuery) { source, token, query ->
        VodBrowseFilter(source, token?.let(VodCategoryKey::fromToken)
            ?.takeIf { source == null || it.sourceId == source }, query)
    }.distinctUntilChanged().flatMapLatest(browseRepository::movies).cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesPages = combine(selectedVodSource, seriesCategory, settledBrowseQuery) { source, token, query ->
        VodBrowseFilter(source, token?.let(VodCategoryKey::fromToken)
            ?.takeIf { source == null || it.sourceId == source }, query)
    }.distinctUntilChanged().flatMapLatest(browseRepository::groupedSeries)
        .map { page -> page.map { group ->
            val show = group.primary.tmdbHint()?.let { canonicalShows.validated(it) }
            ShowCard(group.copy(primary = show?.let(group.primary::withCanonical) ?: group.primary), show)
        } }
        .cachedIn(viewModelScope)

    private val canonicalShows = graph.database.canonicalShows()

    /** A Shows grid card: the provider group, with TMDB metadata when its hint was validated. */
    data class ShowCard(val group: app.opentv.data.repo.SeriesBrowseGroup, val show: CanonicalShow?)

    /** Validated TMDB metadata (network, status) for a show page; null when unknown. */
    suspend fun canonicalShow(series: Series): CanonicalShow? = series.tmdbHint()?.let { canonicalShows.validated(it) }

    // ---- Movies/Shows home: TMDB rankings limited to titles available here --------------------

    data class HomeTitle(val key: String, val title: String, val poster: String?, val backdrop: String?, val year: Int?,
        val rating: Double?, val language: String?, val overview: String?, val rank: Int? = null,
        val movieId: Long? = null, val groupKey: String? = null, val variantCount: Int = 1,
        val network: String? = null, val status: String? = null, val networkLogo: String? = null, val quality: Int = 0)
    enum class HomeStyle { POSTERS, WIDE }
    data class HomeSection(val key: String, val title: String, val style: HomeStyle, val items: List<HomeTitle>)
    data class CatalogueHome(val featured: List<HomeTitle>, val sections: List<HomeSection>)

    private val rankings = TmdbRankings(graph.httpClient, settings, java.io.File(getApplication<Application>().filesDir, "rankings"))

    /** Cached rankings first (instant), then refreshed ones when the cache is older than six hours. */
    val movieHome: StateFlow<CatalogueHome?> = kotlinx.coroutines.flow.flow {
        rankings.cached(TmdbRankings.Kind.MOVIES)?.let { emit(buildMovieHome(it)) }
        rankings.lists(TmdbRankings.Kind.MOVIES)?.let { emit(buildMovieHome(it)) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Lazily, null)

    val showHome: StateFlow<CatalogueHome?> = kotlinx.coroutines.flow.flow {
        rankings.cached(TmdbRankings.Kind.SHOWS)?.let { emit(buildShowHome(it)) }
        rankings.lists(TmdbRankings.Kind.SHOWS)?.let { emit(buildShowHome(it)) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Lazily, null)

    private fun arrange(lists: Map<String, List<RankedTitle>>, local: Map<Int, HomeTitle>,
        newTitle: String, kindTitle: String): CatalogueHome {
        val used = HashSet<String>()
        fun pick(list: String, count: Int, need: (HomeTitle) -> Boolean = { true }) = lists[list].orEmpty()
            .mapNotNull { local[it.id] }.distinctBy { it.key }.withIndex()
            .map { (i, t) -> if (list == TmdbRankings.TRENDING) t.copy(rank = i + 1) else t }
            .filter { it.key !in used && need(it) }.take(count).also { picked -> used += picked.map { it.key } }
        val featured = pick(TmdbRankings.TRENDING, 5) { it.backdrop != null }
        val sections = listOf(
            HomeSection("trending", "Trending this week", HomeStyle.POSTERS, pick(TmdbRankings.TRENDING, 6)),
            HomeSection("new", newTitle, HomeStyle.WIDE, pick(TmdbRankings.NEW, 4) { it.backdrop != null }),
            HomeSection("popular", "Popular", HomeStyle.POSTERS, pick(TmdbRankings.POPULAR, 6)),
        ).filter { it.items.isNotEmpty() }
        return CatalogueHome(featured, sections + HomeSection("all", kindTitle, HomeStyle.POSTERS, emptyList()))
    }

    private suspend fun buildMovieHome(lists: Map<String, List<RankedTitle>>): CatalogueHome {
        val ids = lists.values.flatten().map { it.id }.distinct()
        val local = ids.chunked(500).flatMap { canonical.availableByIds(it) }.associate { card ->
            val t = card.title
            t.tmdbId to HomeTitle("movie:${t.tmdbId}", t.title, t.posterUrl, t.backdropUrl, t.year, t.rating, t.originalLanguage,
                t.synopsis, movieId = card.movieId, quality = card.bestQuality)
        }
        return arrange(lists, local, "New releases", "All movies")
    }

    private suspend fun buildShowHome(lists: Map<String, List<RankedTitle>>): CatalogueHome {
        val ranked = lists.values.flatten().distinctBy { it.id }.associateBy { it.id }
        val ids = ranked.keys.toList()
        val groups = ids.chunked(500).flatMap { graph.database.series().groupsForHints(it) }
            .associateBy { it.primary.tmdbId!!.trim().toInt() }
        val known = ids.chunked(500).flatMap { canonicalShows.byIds(it) }.associateBy { it.tmdbId }
        val checked = mutableListOf<CanonicalShow>()
        val local = groups.mapNotNull { (id, group) ->
            val item = ranked[id] ?: return@mapNotNull null
            val show = known[id] ?: run {
                // Same name check as CanonicalShowWorker, using the list entry instead of a detail request.
                val ok = ShowMatching.accepts(group.names.orEmpty().split(',').map(String::trim),
                    group.primary.year, item.title, item.originalTitle, item.year)
                CanonicalShow(id, ok, item.title.takeIf { ok }, item.originalTitle.takeIf { ok && it.isNotBlank() },
                    item.year.takeIf { ok }, item.language.takeIf { ok }, item.poster.takeIf { ok }, item.backdrop.takeIf { ok },
                    item.overview.takeIf { ok }, null, item.rating.takeIf { ok }, null, System.currentTimeMillis()).also { checked += it }
            }
            if (!show.validated) return@mapNotNull null
            id to HomeTitle("show:$id", show.title ?: item.title, show.posterUrl ?: item.poster, show.backdropUrl ?: item.backdrop,
                show.year, show.rating ?: item.rating, show.originalLanguage, show.synopsis ?: item.overview,
                groupKey = group.groupKey, variantCount = group.variantCount, network = show.network, status = show.statusLabel,
                networkLogo = show.networkLogo, quality = group.bestQuality)
        }.toMap()
        if (checked.isNotEmpty()) runCatching { canonicalShows.putAll(checked) }
        return arrange(lists, local, "Airing now", "All shows")
    }

    /** Opens what a home card points at, choosing the version the same way the grid does. */
    suspend fun openHomeMovie(title: HomeTitle): Movie? = title.movieId?.let { defaultMovieVersion(canonicalVersions(it)) }
    suspend fun openHomeShow(title: HomeTitle): Series? = title.groupKey?.let { key ->
        defaultSeriesVersion(seriesBrowseVariants(key))?.let { withCanonicalShow(it) }
    }

    /** TMDB title/art for a show whose provider hint was validated; the provider row otherwise. */
    suspend fun withCanonicalShow(series: Series): Series =
        series.tmdbHint()?.let { canonicalShows.validated(it) }?.let(series::withCanonical) ?: series

    fun selectMovieCategory(id: String?) { movieCategory.value = id }

    fun selectSeriesCategory(id: String?) { seriesCategory.value = id }

    /** On-demand variants for a selected card; never invoked once per browse row. */
    suspend fun movieBrowseVariants(groupKey: String): List<Movie> = browseRepository.variants(
        groupKey, VodBrowseFilter(selectedVodSource.value,
            movieCategory.value?.let(VodCategoryKey::fromToken)),
    )

    suspend fun seriesBrowseVariants(groupKey: String) = graph.database.series().browseVariants(groupKey, selectedVodSource.value)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun seriesPositions(series: Series): kotlinx.coroutines.flow.Flow<List<PlaybackPosition>> = settings.activeProfileId
        .flatMapLatest { graph.playbackPositions.observeSeries(it, series.sourceId, series.seriesId) }

    private val seasonPosterCache = java.util.concurrent.ConcurrentHashMap<Long, Map<Int, String>>()
    suspend fun seasonPosters(series: Series): Map<Int, String> =
        seasonPosterCache[series.id] ?: graph.catalogRepository.seasonPosters(series).also { seasonPosterCache[series.id] = it }

    /** Every provider version of this show (all sources), for the detail page's Source control. */
    suspend fun seriesVersions(series: Series): List<Series> =
        graph.database.series().browseVariants(series.providerBrowseKey(), null)
            .ifEmpty { listOf(series) }

    /**
     * Which version a tap opens: the one you were watching, then the provider chosen in filters,
     * then the highest quality inferred from the provider's title, then provider order.
     */
    suspend fun defaultMovieVersion(versions: List<Movie>): Movie? {
        if (versions.isEmpty()) return null
        versions.firstOrNull { resumePosition("movie:${it.id}")?.let { p -> !p.isFinished } == true }?.let { return it }
        val pool = selectedVodSource.value?.let { s -> versions.filter { it.sourceId == s } }?.ifEmpty { null } ?: versions
        val names = pool.associate { it.id to rawMovieName(it.id) }
        return pool.maxWithOrNull(compareBy<Movie> { languageFit(names[it.id].orEmpty()) }
            .thenBy { app.opentv.data.parser.MovieBrowseKey.from(names[it.id].orEmpty(), it.year).qualityRank }) ?: pool.first()
    }

    suspend fun defaultSeriesVersion(versions: List<Series>): Series? {
        if (versions.isEmpty()) return null
        val profile = settings.activeProfileId.value
        versions.mapNotNull { v -> graph.playbackPositions.lastSeriesActivity(profile, v.sourceId, v.seriesId)?.let { v to it } }
            .maxByOrNull { it.second }?.let { return it.first }
        val pool = selectedVodSource.value?.let { s -> versions.filter { it.sourceId == s } }?.ifEmpty { null } ?: versions
        return pool.maxByOrNull { languageFit(it.name) } ?: pool.first()
    }

    /**
     * Inferred from the provider's name tags only ("(DE)", "EN -", "|FR|"): 2 = tagged with the
     * device language, 1 = no language tag, 0 = tagged with another language.
     */
    private fun languageFit(name: String): Int {
        val device = java.util.Locale.getDefault().language.uppercase(java.util.Locale.ROOT)
        val tags = LANGUAGE_TAG.findAll(name).map { it.groupValues.drop(1).first(String::isNotEmpty).uppercase(java.util.Locale.ROOT) }
            .filter { it in KNOWN_LANGUAGE_TAGS }.toList()
        return when { tags.isEmpty() -> 1; device in tags || (device == "EN" && tags.any { it == "UK" || it == "US" }) -> 2; else -> 0 }
    }

    /** True when at least one Stremio add-on is configured — gates the "add-on sources" button. */
    val hasAddons: StateFlow<Boolean> =
        settings.stremioAddons
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.stremioAddons.value.isNotEmpty())

    /**
     * The streams the configured add-ons offer for [movie], resolved through the user's TMDB key for
     * the IMDb id. Empty when there's no key, no add-ons, no IMDb match, or nothing came back. Each
     * add-on is queried independently so one failing doesn't sink the rest.
     */
    suspend fun addonStreams(movie: Movie): List<StremioStream> {
        val addons = settings.stremioAddons.value
        if (addons.isEmpty()) return emptyList()
        val imdb = graph.catalogRepository.imdbIdFor(movie) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            addons.flatMap { addon ->
                runCatching { graph.stremioClient.streams(addon.manifestUrl, addon.name, "movie", imdb) }
                    .getOrDefault(emptyList())
            }
        }
    }

    /** Switch the Movies/Shows provider filter; category selections reset since they differ per source. */
    fun selectVodSource(sourceId: Long?) {
        if (selectedVodSource.value == sourceId) return
        movieCategory.value = null
        seriesCategory.value = null
        vodSource.value = sourceId
    }

    /**
     * Whether any films / series are on disk: null until the first answer. The tabs only claim
     * "no films" on a confirmed false — reading the empty initial state as "nothing here" is what
     * showed "no movies" right after an update, while a busy database was still answering.
     */
    val hasMovies: StateFlow<Boolean?> = graph.catalogRepository.observeHasMovies()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hasSeries: StateFlow<Boolean?> = graph.catalogRepository.observeHasSeries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Titles per category, for the phone category sheet (read on open). */
    suspend fun movieCategoryCounts(): Map<String, Int> =
        graph.database.movies().browseCategoryCounts(selectedVodSource.value)
            .associate { VodCategoryKey(it.sourceId, it.categoryId).token to it.count }
    suspend fun seriesCategoryCounts(): Map<String, Int> =
        graph.database.series().browseCategoryCounts(selectedVodSource.value)
            .associate { VodCategoryKey(it.sourceId, it.categoryId).token to it.count }

    // Movies + series load on demand — the first time the user opens Movies or Shows — rather than
    // up front at login. A provider's 40,000-title VOD list is exactly what makes a first sync
    // crawl, and most sessions only ever watch live TV. Loaded once per app run.
    private val _vodLoading = MutableStateFlow(false)
    val vodLoading: StateFlow<Boolean> = _vodLoading.asStateFlow()

    @Volatile private var vodRequested = false
    private val vodSyncMutex = Mutex()
    private val _vodFailed = MutableStateFlow(false)
    val vodFailed = _vodFailed.asStateFlow()

    /** How long a fetched VOD catalogue is trusted before a warm launch re-syncs it. */
    private val VOD_TTL_MILLIS = 12L * 60 * 60 * 1000  // 12 hours

    fun ensureVodLoaded() {
        app.opentv.catalogue.CanonicalMovieWorker.enqueue(getApplication())
        app.opentv.catalogue.CanonicalShowWorker.enqueue(getApplication())
        if (vodRequested) return
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = false) }
    }

    /**
     * Force a fresh download of the movies/series catalogue, ignoring the freshness cache. Wired
     * to the pull-to-refresh / refresh action so the user always has a way to pull new titles in
     * before the TTL lapses.
     */
    fun refreshVod() {
        vodRequested = true
        viewModelScope.launch { syncVodIfStale(force = true) }
    }

    private suspend fun syncVodIfStale(force: Boolean) = vodSyncMutex.withLock {
        val now = System.currentTimeMillis()

        // Warm-launch fast path. The catalogue is persisted in Room, so once it has been fetched
        // there is no reason to re-download and re-upsert a 40k-title list on every launch — that
        // was both the multi-minute "Loading movies & shows…" and the bandwidth hog that starved
        // the live preview into buffering. Reuse the local catalogue only when every enabled
        // content kind has rows; movies alone must not mask a missing Shows catalogue.
        if (!force) {
            val haveCatalogue = runCatching {
                (!settings.moviesEnabled.value || graph.catalogRepository.movieCount() > 0) &&
                    (!settings.seriesEnabled.value || graph.catalogRepository.seriesCount() > 0)
            }.getOrDefault(false)
            val last = settings.vodSyncedAtMillis
            val fresh = last > 0 && now - last < VOD_TTL_MILLIS
            if (haveCatalogue && fresh) {
                return@withLock
            }
        }

        _vodLoading.value = true
        _vodFailed.value = false
        val synced = try {
            StatusBus.during(AppText.get(R.string.msg_loading_vod)) {
                var complete = true
                for (source in graph.sourceRepository.enabled()) {
                    if (!graph.catalogRepository.syncVod(source, now)) complete = false
                }
                complete
            }
        } finally {
            _vodLoading.value = false
        }
        _vodFailed.value = !synced
        // An automatic attempt stays latched even on failure. Navigation must not start
        // another full provider import; the explicit Retry/Refresh action remains available.
        vodRequested = true
        // A partial import must never suppress retries for the next twelve hours.
        if (synced) settings.vodSyncedAtMillis = now
        app.opentv.catalogue.CanonicalMovieWorker.enqueue(getApplication())
        app.opentv.catalogue.CanonicalShowWorker.enqueue(getApplication())
    }

    // ---- VOD search (shared by the unified search screen) --------------------------------------
    private val vodSearchInput = MutableStateFlow("")

    fun setVodSearchQuery(text: String) { vodSearchInput.value = text }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val movieResults: StateFlow<List<Movie>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else canonical.search(null, q).mapLatest { cards ->
                    val assets = graph.database.movies().byIds(cards.map { it.movieId }).associateBy { it.id }
                    cards.mapNotNull { card -> assets[card.movieId]?.withCanonical(card.title) }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val seriesResults: StateFlow<List<Series>> =
        vodSearchInput.map { it.trim() }.debounce(200).distinctUntilChanged()
            .flatMapLatest { q ->
                if (q.length < 2) flowOf(emptyList()) else graph.catalogRepository.searchSeries(q).map { shows -> shows.distinctBy { it.providerBrowseKey() } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleMovieFavourite(movie: Movie) {
        viewModelScope.launch {
            graph.catalogRepository.setMovieFavourite(movie.id, !movie.favourite)
        }
    }

    /**
     * Episodes are fetched on demand rather than during catalogue sync.
     *
     * Pulling every episode of every series up front is what makes a first sync take twenty
     * minutes on a large provider, and most of it is never looked at.
     */
    fun loadEpisodes(series: Series) {
        viewModelScope.launch {
            val source = graph.sourceRepository.byId(series.sourceId) ?: return@launch
            graph.catalogRepository.ensureEpisodes(source, series.seriesId)
        }
    }

    fun episodes(series: Series) =
        graph.catalogRepository.observeEpisodes(series.sourceId, series.seriesId)

    /** For the series detail screen: look the series up by row id. */
    fun seriesById(id: Long): kotlinx.coroutines.flow.Flow<Series?> =
        kotlinx.coroutines.flow.flow { emit(graph.catalogRepository.series(id)) }

    /** An always-empty episode flow, so the detail screen has something before a series loads. */
    val noEpisodes: kotlinx.coroutines.flow.Flow<List<app.opentv.data.model.Episode>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun movieById(id: Long): Movie? = graph.catalogRepository.movie(id)

    // ---- Detail screens -------------------------------------------------------------------------
    // Thin wrappers over the repository's detail feeds. The screens drive them from `produceState`
    // keyed on the id, so each detail instance owns its own state and a "More like this" hop to
    // another title loads cleanly without two screens fighting over one shared StateFlow.

    /** Loads a movie, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.movieDetail. */
    suspend fun movieDetail(id: Long): Movie? {
        val versions = canonicalVersions(id)
        return versions.firstOrNull { it.id == id } ?: graph.catalogRepository.movieDetail(id)
    }

    /** The provider's own detail call; canonical titles don't store cast or director, the provider row may. */
    suspend fun movieCredits(id: Long): Movie? = graph.catalogRepository.movieDetail(id)

    /** Loads a series, lazily enriching backdrop/cast/genre on first open. See CatalogRepository.seriesDetail. */
    suspend fun seriesDetail(id: Long): Series? = graph.catalogRepository.seriesDetail(id)?.let { withCanonicalShow(it) }

    /** "More like this" for the movie detail screen. */
    suspend fun moreLikeThis(movie: Movie): List<Movie> = graph.catalogRepository.moreLikeThis(movie)

    /** "More like this" for the series detail screen. */
    suspend fun moreLikeThisSeries(series: Series): List<Series> =
        graph.catalogRepository.moreLikeThisSeries(series)

    /** Every library title (movies + series) featuring a person — the Person screen's grid. */
    suspend fun titlesWithPerson(name: String): List<PersonTitle> =
        graph.catalogRepository.titlesWithPerson(name)

    /** The active profile's saved resume position for a movie/episode, or null — drives Resume vs Watch now. */
    suspend fun resumePosition(mediaKey: String): PlaybackPosition? =
        graph.playbackPositions.get(settings.activeProfileId.value, mediaKey)

    /** Collapses quality variants of a movie list for a browse grid. See CatalogRepository.collapseVariants. */
    fun collapseVariants(movies: List<Movie>): List<MovieVariantGroup> =
        graph.catalogRepository.collapseVariants(movies)

    /** Star / un-star a whole series. Wraps the series DAO through the shared database — no data-layer change. */
    fun toggleSeriesFavourite(series: Series) {
        viewModelScope.launch {
            graph.database.series().setFavourite(series.id, !series.favourite)
        }
    }

    /** The user-agent to play a movie/episode with (per source). */
    suspend fun userAgentForSource(sourceId: Long): String =
        graph.sourceRepository.byId(sourceId)?.userAgent ?: "OpenTV/0.1 (Android)"
}

private val LANGUAGE_TAG = Regex("""\(([A-Za-z]{2})\)|^([A-Za-z]{2})\s*[-|:]|\|([A-Za-z]{2})\|""")
private val KNOWN_LANGUAGE_TAGS = setOf("EN", "UK", "US", "DE", "FR", "ES", "IT", "NL", "PT", "PL", "TR", "AR", "RU", "SE", "SV", "NO", "DK", "DA", "FI",
    "GR", "EL", "HU", "CZ", "CS", "RO", "BG", "HR", "SR", "AL", "IN", "HI", "JP", "JA", "KR", "KO", "CN", "ZH", "TH", "VI", "IR", "FA", "LAT", "MX", "BR")
