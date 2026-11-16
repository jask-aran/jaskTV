/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop

import androidx.paging.PagingSource
import app.opentv.catalogue.RankedTitle
import app.opentv.catalogue.TmdbRankings
import app.opentv.catalogue.withCanonical
import app.opentv.catalogue.tmdbHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One title as the desktop UI draws it; mirrors the phone's HomeTitle. */
data class Title(
    val key: String, val title: String, val poster: String?, val backdrop: String?, val year: Int?,
    val rating: Double?, val overview: String?, val rank: Int? = null,
    val movieId: Long? = null, val seriesId: Long? = null,
    val network: String? = null, val networkLogo: String? = null, val status: String? = null, val quality: Int = 0,
    val isShow: Boolean = false,
)

data class Section(val title: String, val wide: Boolean, val items: List<Title>)
data class Home(val featured: List<Title>, val sections: List<Section>, val all: List<Title>)

/** Builds the Movies and Shows homes from TMDB rankings limited to the library, like the phone. */
object DesktopCatalogue {
    private val db get() = DesktopGraph.database

    suspend fun movies(): Home = withContext(Dispatchers.IO) {
        val lists = DesktopGraph.rankings.cached(TmdbRankings.Kind.MOVIES) ?: DesktopGraph.rankings.lists(TmdbRankings.Kind.MOVIES).orEmpty()
        val ids = lists.values.flatten().map { it.id }.distinct()
        val local = ids.chunked(500).flatMap { db.canonicalMovies().availableByIds(it) }.associate { c ->
            val t = c.title
            t.tmdbId to Title("movie:${t.tmdbId}", t.title, t.posterUrl, t.backdropUrl, t.year, t.rating, t.synopsis,
                movieId = c.movieId, quality = c.bestQuality)
        }
        val all = page(db.canonicalMovies().browseTitle(null, "", "", true), 400).map { c ->
            val t = c.title
            Title("movie:${t.tmdbId}", t.title, t.posterUrl, t.backdropUrl, t.year, t.rating, t.synopsis, movieId = c.movieId, quality = c.bestQuality)
        }
        arrange(lists, local, "New releases", all)
    }

    suspend fun shows(): Home = withContext(Dispatchers.IO) {
        val lists = DesktopGraph.rankings.cached(TmdbRankings.Kind.SHOWS) ?: DesktopGraph.rankings.lists(TmdbRankings.Kind.SHOWS).orEmpty()
        val ids = lists.values.flatten().map { it.id }.distinct()
        val groups = ids.chunked(500).flatMap { db.series().groupsForHints(it) }.associateBy { it.primary.tmdbId!!.trim().toInt() }
        val known = ids.chunked(500).flatMap { db.canonicalShows().byIds(it) }.filter { it.validated }.associateBy { it.tmdbId }
        val local = groups.mapNotNull { (id, g) ->
            val show = known[id] ?: return@mapNotNull null
            id to Title("show:$id", show.title ?: g.primary.name, show.posterUrl ?: g.primary.posterUrl, show.backdropUrl, show.year,
                show.rating, show.synopsis, seriesId = g.primary.id, network = show.network, networkLogo = show.networkLogo,
                status = show.statusLabel, quality = g.bestQuality, isShow = true)
        }.toMap()
        val all = page(db.series().browseGroups(null, null, ""), 400).map { g ->
            val show = g.primary.tmdbHint()?.let { db.canonicalShows().validated(it) }
            val s = show?.let(g.primary::withCanonical) ?: g.primary
            Title(g.groupKey, cleanTitle(s.name), s.posterUrl, s.backdropUrl, s.year, s.rating, s.plot, seriesId = g.primary.id,
                network = show?.network, networkLogo = show?.networkLogo, status = show?.statusLabel, quality = g.bestQuality, isShow = true)
        }
        arrange(lists, local, "Airing now", all)
    }

    suspend fun search(query: String): List<Title> = withContext(Dispatchers.IO) {
        val movies = page(db.canonicalMovies().browseTitle(null, query, "", true), 60).map { c ->
            val t = c.title
            Title("movie:${t.tmdbId}", t.title, t.posterUrl, t.backdropUrl, t.year, t.rating, t.synopsis, movieId = c.movieId, quality = c.bestQuality)
        }
        val shows = db.series().searchGroups(null, query, 60).map { g ->
            val show = g.primary.tmdbHint()?.let { db.canonicalShows().validated(it) }
            val s = show?.let(g.primary::withCanonical) ?: g.primary
            Title(g.groupKey, cleanTitle(s.name), s.posterUrl, s.backdropUrl, s.year, s.rating, s.plot, seriesId = g.primary.id,
                network = show?.network, networkLogo = show?.networkLogo, status = show?.statusLabel, quality = g.bestQuality, isShow = true)
        }
        (movies + shows).sortedWith(compareBy<Title>({ !it.title.startsWith(query, ignoreCase = true) }, { -(it.rating ?: 0.0) }))
    }

    private fun arrange(lists: Map<String, List<RankedTitle>>, local: Map<Int, Title>, newTitle: String, all: List<Title>): Home {
        val used = HashSet<String>()
        fun pick(list: String, count: Int, need: (Title) -> Boolean = { true }) = lists[list].orEmpty()
            .mapNotNull { local[it.id] }.distinctBy { it.key }.withIndex()
            .map { (i, t) -> if (list == TmdbRankings.TRENDING) t.copy(rank = i + 1) else t }
            .filter { it.key !in used && need(it) }.take(count).also { p -> used += p.map { it.key } }
        val featured = pick(TmdbRankings.TRENDING, 6) { it.backdrop != null }
        val sections = listOf(
            Section("Trending this week", false, pick(TmdbRankings.TRENDING, 14)),
            Section(newTitle, true, pick(TmdbRankings.NEW, 8) { it.backdrop != null }),
            Section("Popular", false, pick(TmdbRankings.POPULAR, 14)),
        ).filter { it.items.isNotEmpty() }
        return Home(featured, sections, all)
    }

    private suspend fun <T : Any> page(source: PagingSource<Int, T>, size: Int): List<T> =
        (source.load(PagingSource.LoadParams.Refresh(null, size, false)) as? PagingSource.LoadResult.Page)?.data.orEmpty()

    /** Provider names carry tags ("EN - The Office (2005) 4K"); keep the title. */
    fun cleanTitle(raw: String): String = raw
        .replace(Regex("""^\s*[A-Z]{2,3}\s*[-|:]\s*"""), "")
        .replace(Regex("""\s*\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|MULTI|VOSTFR)\b"""), "")
        .replace(Regex("""\s+"""), " ").trim()
}
