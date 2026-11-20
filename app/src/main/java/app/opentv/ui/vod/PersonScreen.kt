/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.vod

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.R
import app.opentv.data.model.Movie
import app.opentv.data.model.Series
import app.opentv.data.parser.displayTitle
import app.opentv.data.parser.sourceTag
import app.opentv.data.repo.PersonTitle
import app.opentv.ui.VodViewModel

/**
 * Everything in the library featuring one person — the Plex-style payoff of tapping a cast member or
 * the director on a detail page. Movies (they act in or directed) and series (they act in) come back
 * merged from [VodViewModel.titlesWithPerson] and are shown as one poster grid; a click opens that
 * title's own detail page. Reuses [PosterCard], so focus, scaling and rating badges match the rest
 * of the VOD surfaces.
 */
@Composable
fun PersonScreen(
    name: String,
    onOpenMovie: (Movie) -> Unit,
    onOpenSeries: (Series) -> Unit,
    onBack: () -> Unit,
    viewModel: VodViewModel = viewModel(),
    /** TMDB's person id (from a cast row): photo, bio and their titles, split by what's on your sources. 0: by name only. */
    tmdbId: Int = 0,
    onOpenMovieId: (Long) -> Unit = {},
    onOpenSeriesId: (Long) -> Unit = {},
) {
    if (tmdbId > 0) {
        val graph = app.opentv.core.ServiceLocator.get(androidx.compose.ui.platform.LocalContext.current)
        // Pair(answered, details): until TMDB answers, a loading page (not the by-name grid flashing first); the
        // by-name grid only when TMDB has nothing for them.
        val person by androidx.compose.runtime.produceState<Pair<Boolean, app.opentv.catalogue.PersonDetails?>>(false to null, tmdbId) {
            value = true to runCatching { graph.titleMeta.person(tmdbId) }.getOrNull()
        }
        val p = person.second
        if (p != null) { PersonTmdbPage(p, viewModel, TitleNav(onOpenMovieId, onOpenSeriesId) { _, _ -> }, onBack); return }
        if (!person.first) {
            BackHandler { onBack() }
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, strokeWidth = 3.dp)
            }
            return
        }
    }
    // null = still loading; empty = nothing matched. Keyed on name so a hop to another person reloads.
    var titles by remember(name) { mutableStateOf<List<PersonTitle>?>(null) }
    LaunchedEffect(name) { titles = viewModel.titlesWithPerson(name) }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        // An on-screen back button: full-screen windows (tablets, DeX) may have no system Back.
        androidx.compose.foundation.layout.Row(Modifier.statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            app.opentv.ui.JaskGlassCircle(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back), size = 44.dp, onClick = onBack)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.vod_person_title, name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Weighted so the grid/empty state fills the space under the fixed header, exactly — the same
        // remaining-space pattern the Movies/Shows home uses.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val list = titles) {
                null -> Centered { CircularProgressIndicator() }
                else -> if (list.isEmpty()) {
                    Centered {
                        Text(
                            stringResource(R.string.vod_person_empty, name),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 140.dp),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp +
                            androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(list, key = { it.gridKey() }) { item ->
                            when (item) {
                                is PersonTitle.MovieItem -> PosterCard(
                                    title = item.movie.displayTitle,
                                    tagBadge = item.movie.sourceTag,
                                    posterUrl = item.movie.posterUrl,
                                    subtitle = item.movie.year?.toString(),
                                    rating = item.movie.rating,
                                    onClick = { onOpenMovie(item.movie) },
                                )
                                is PersonTitle.SeriesItem -> PosterCard(
                                    title = item.series.displayTitle,
                                    tagBadge = item.series.sourceTag,
                                    posterUrl = item.series.posterUrl,
                                    subtitle = item.series.year?.toString(),
                                    rating = item.series.rating,
                                    onClick = { onOpenSeries(item.series) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Grid key that stays unique across the two tables (a movie and a series can share a row id). */
private fun PersonTitle.gridKey(): String = when (this) {
    is PersonTitle.MovieItem -> "m${movie.id}"
    is PersonTitle.SeriesItem -> "s${series.id}"
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/** A bio without Wikipedia's page furniture ("From Wikipedia, the free encyclopedia."); null when nothing's left. */
private fun cleanBio(raw: String): String? =
    raw.replace(Regex("""^\s*From Wikipedia, the free encyclopedia\.?\s*""", RegexOption.IGNORE_CASE), "").trim().takeIf { it.isNotEmpty() }

/** A person from TMDB: photo, what they do, born, bio, then their titles on your sources and the rest. */
@Composable
private fun PersonTmdbPage(p: app.opentv.catalogue.PersonDetails, viewModel: VodViewModel, nav: TitleNav, onBack: () -> Unit) {
    BackHandler { onBack() }
    val local = rememberOnSources(p.credits, viewModel)
    val (yours, others) = p.credits.partition { it.key in local }
    // The media pages' backdrop: on a phone the dithered waves behind it (JaskMediaAmbient, in its route) show
    // through; elsewhere the media page canvas.
    val waves = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.PHONE && app.opentv.ui.LocalJaskAmbient.current != null
    val bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().then(if (waves) Modifier else Modifier.background(JaskPageCanvas)),
        contentPadding = PaddingValues(bottom = 40.dp + bottom)) {
        item(key = "head") {
            androidx.compose.foundation.layout.Row(Modifier.statusBarsPadding().padding(start = 12.dp, end = jaskSide(), top = 12.dp),
                verticalAlignment = Alignment.Top) {
                app.opentv.ui.JaskGlassCircle(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.common_back), size = 44.dp, onClick = onBack)
                androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF2A2826))) {
                    if (p.photo != null) coil.compose.AsyncImage(p.photo, p.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(18.dp))
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    val facts = listOfNotNull(p.department?.let { if (it == "Acting") "Actor" else it }, p.born?.let { b -> "Born " + runCatching { java.time.LocalDate.parse(b).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(b) }, p.place)
                    if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.labelLarge,
                        color = androidx.compose.ui.graphics.Color(0xFFBDBDBD), modifier = Modifier.padding(top = 4.dp))
                    p.bio?.let(::cleanBio)?.let { bio ->
                        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                        Text(bio, style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color(0xFFDADADA),
                            maxLines = if (open) 40 else 5, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp).offset(x = (-14).dp).widthIn(max = 788.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        item(key = "yours") {
            if (yours.isEmpty()) Text("Nothing of theirs is on your sources.", color = androidx.compose.ui.graphics.Color(0xFFBDBDBD),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = jaskSide(), top = 24.dp))
            else JaskTitleShelf("On your sources · ${yours.size}", yours, local, nav, focusFirst = true)
        }
        item(key = "others") { JaskTitleShelf("Also known for", others.take(30), local, nav) }
    }
}
