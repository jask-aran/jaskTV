package app.opentv.ui.vod

import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.catalogue.CreditPerson
import app.opentv.catalogue.ReviewExcerpt
import app.opentv.catalogue.ReviewScores
import app.opentv.catalogue.TitleDetails
import app.opentv.catalogue.TitleRef
import app.opentv.core.ServiceLocator
import app.opentv.ui.jaskFocusLift
import app.opentv.ui.jaskCard
import app.opentv.ui.jaskCardArt
import androidx.compose.ui.focus.focusRequester
import coil.compose.AsyncImage

/** Opens another title or a person from the media page: (on your sources? local id) and (TMDB id, name). */
internal class TitleNav(val openMovie: (Long) -> Unit, val openSeries: (Long) -> Unit, val openPerson: (Int, String) -> Unit)

/** The media page's extra details (cast, logo, scores, collection, related), loaded once per title. */
@Composable
internal fun rememberTitleDetails(movie: Boolean, tmdbId: Int?): TitleDetails? {
    val graph = ServiceLocator.get(LocalContext.current)
    // Already known this run (coming Back): there from the first frame, so the page's rows exist when its saved
    // scroll position is restored (it was clamped to the top while they loaded).
    var details by remember(movie, tmdbId) { mutableStateOf(tmdbId?.let { graph.titleMeta.known(movie, it) }) }
    LaunchedEffect(movie, tmdbId) { if (tmdbId != null) details = if (movie) graph.titleMeta.movie(tmdbId) else graph.titleMeta.show(tmdbId) }
    return details
}

/** IMDb / Rotten Tomatoes / Metacritic for [imdb] (null until known, or when there's no OMDb key anywhere). */
@Composable
internal fun rememberScores(imdb: String?): ReviewScores? {
    val graph = ServiceLocator.get(LocalContext.current)
    var scores by remember(imdb) { mutableStateOf<ReviewScores?>(null) }
    LaunchedEffect(imdb) { if (imdb != null) scores = graph.titleMeta.scores(imdb) }
    return scores
}

/** Which of [refs] are on your sources: "m:<tmdb>"/"t:<tmdb>" → the local film or show id. */
@Composable
internal fun rememberOnSources(refs: List<TitleRef>, viewModel: app.opentv.ui.VodViewModel): Map<String, Long> {
    // Kept for the run (keyed by the titles asked about), so a page coming Back has its shelves on the first frame.
    val cacheKey = remember(refs) { refs.joinToString(",") { it.key } }
    var local by remember(refs) { mutableStateOf(OnSourcesMemory[cacheKey] ?: emptyMap()) }
    LaunchedEffect(refs) {
        if (refs.isNotEmpty()) local = runCatching { viewModel.onYourSources(refs) }.getOrDefault(emptyMap()).also { OnSourcesMemory[cacheKey] = it }
    }
    return local
}

/** The fact line's extras: age rating, runtime and when it would end if started now ("Ends 10:42 pm"). */
internal fun detailFacts(d: TitleDetails?, runtimeMinutes: Int?, movie: Boolean, networkShown: Boolean = false): List<String> {
    val minutes = runtimeMinutes?.takeIf { it > 0 } ?: d?.runtimeMinutes
    val ends = if (movie && minutes != null) "Ends " + java.time.LocalTime.now().plusMinutes(minutes.toLong())
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")).lowercase() else null
    val show = if (!movie && d != null) listOfNotNull(d.status, d.nextEpisode?.let { "Next $it" }, d.networks.firstOrNull().takeIf { !networkShown }) else emptyList()
    return listOfNotNull(d?.ageRating) + show + listOfNotNull(if (movie) minutes?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" } else null, ends)
}

/**
 * Cast (photos, characters) then the people behind it (director, writers, creators): each opens their page.
 * TV: opening one reports its key through [onReturnTo] (the page keeps it, saveable); when the row comes back with
 * [returnTo] set (Back from that person's page), that person takes focus again, and the key is cleared either way.
 */
@Composable
internal fun JaskCastRow(cast: List<CreditPerson>, crew: List<CreditPerson>, returnTo: String? = null,
    onReturnTo: (String?) -> Unit = {}, onOpen: (CreditPerson) -> Unit) {
    val tv = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV
    // Not taken (that person scrolled out of the row): forget it, so it can't pull focus later.
    // Only when the row is built again with it set (coming Back), not the moment it's clicked (still on this page).
    val returnAtEntry = remember { returnTo }
    if (tv && returnAtEntry != null) LaunchedEffect(Unit) { kotlinx.coroutines.delay(4000); onReturnTo(null) }
    val people = cast + crew.filter { c -> cast.none { it.id == c.id } }
    if (people.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(Modifier.padding(start = jaskSide(), end = jaskSide(), bottom = 10.dp)) { JaskSectionTitle("Cast & crew") }
        LazyRow(Modifier.jaskRowStops(), contentPadding = PaddingValues(horizontal = jaskSide()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(people, key = { "${it.id}/${it.role}" }) { p ->
                val itemKey = "${p.id}/${p.role}"
                val requester = remember { androidx.compose.ui.focus.FocusRequester() }
                // After the page's own opening focus (its Play button), so this one wins.
                if (tv && returnAtEntry == itemKey && returnTo == itemKey) LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(200)
                    runCatching { requester.requestFocus() }
                    onReturnTo(null)
                }
                // One height for every person (names and roles wrap differently): a taller card would catch Down and
                // send focus sideways instead of on to the next row.
                Column(Modifier.width(104.dp).height(172.dp).focusRequester(requester).jaskFocusLift(1.06f, key = "cast:$itemKey").clip(RoundedCornerShape(12.dp))
                    .clickable { if (tv) onReturnTo(itemKey); onOpen(p) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF2E2C2A)), contentAlignment = Alignment.Center) {
                        if (p.photo != null) AsyncImage(p.photo, p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(p.name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString(""), color = Color(0xFFBDBDBD),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.name, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center)
                    p.role?.let { Text(it, color = Color(0xFFA8A8A8), style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/** Ratings: TMDB's (always, with its vote count), then IMDb, Rotten Tomatoes and Metacritic when OMDb knows them. */
@Composable
internal fun JaskScoresRow(d: TitleDetails?, scores: ReviewScores?) {
    val tiles = buildList {
        d?.tmdbScore?.let { add(Triple("TMDB", "%.1f".format(it), d.votes?.let { v -> "${compact(v)} votes" })) }
        scores?.imdb?.let { add(Triple("IMDb", it, scores.imdbVotes?.let { v -> "$v votes" })) }
        scores?.rottenTomatoes?.let { add(Triple("Rotten Tomatoes", it, "critics")) }
        scores?.metacritic?.let { add(Triple("Metacritic", it, "critics")) }
    }
    if (tiles.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(Modifier.padding(start = jaskSide(), end = jaskSide(), bottom = 10.dp)) { JaskSectionTitle("Ratings") }
        LazyRow(contentPadding = PaddingValues(horizontal = jaskSide()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(tiles, key = { it.first }) { (source, value, note) ->
                Column(Modifier.widthIn(min = 120.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(value, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(source, color = Color(0xFFD6D6D6), style = MaterialTheme.typography.labelMedium)
                    if (note != null) Text(note, color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun compact(n: Int) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1_000 -> "%.1fk".format(n / 1e3); else -> "$n" }

/**
 * A review's words for the card, which adds its own “ ”: no links, and no quote marks of its own round it. Quotes round the
 * whole text are dropped; a quoted name at the start ("Dexter Resurrection" is…) keeps single quotes instead.
 */
internal fun reviewExcerpt(raw: String): String {
    val quotes = "\"“”„«»"
    var s = raw.replace(Regex("""\(?\bhttps?://\S+|\(?\bwww\.\S+"""), "").replace(Regex("""[ \t]{2,}"""), " ").trim()
    // Quoted as a whole: its only two quote marks are its first and last characters.
    if (s.length >= 2 && s.first() in quotes && s.last() in quotes && s.count { it in quotes } == 2) s = s.substring(1, s.length - 1).trim()
    // Starts with a quoted name: single quotes, inside the card's own.
    if (s.isNotEmpty() && s.first() in quotes) {
        val close = s.indexOfAny(quotes.toCharArray(), startIndex = 1)
        if (close > 0) s = "‘" + s.substring(1, close) + "’" + s.substring(close + 1)
    }
    return s
}

/** One written review, shortened, with who wrote it. A d-pad stop (so the page's end is reachable); OK opens it in full. */
@Composable
internal fun JaskReviewCard(r: ReviewExcerpt?) {
    r ?: return
    val text = remember(r.text) { reviewExcerpt(r.text) }
    if (text.isBlank()) return
    var open by remember(r.text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = jaskSide(), end = jaskSide(), top = 18.dp)) {
        JaskSectionTitle("A review")
        Spacer(Modifier.height(8.dp))
        Column(Modifier.widthIn(max = 760.dp).jaskFocusLift(1.02f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.05f))
            .clickable { open = !open }.padding(16.dp)) {
            Text("“$text”", color = Color(0xFFE2E2E2), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                maxLines = if (open) 60 else 7, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(listOfNotNull(r.author, r.rating?.let { "%.0f/10".format(it) }).joinToString(" · "), color = Color(0xFFA8A8A8),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A shelf of other titles (a collection's films, related titles, a person's credits). Ones on your sources open their
 * page; the rest are dimmed and say so. [onlyOnSources]: leave those out entirely (related titles).
 */
@Composable
internal fun JaskTitleShelf(title: String, refs: List<TitleRef>, local: Map<String, Long>, nav: TitleNav, currentKey: String? = null,
    onlyOnSources: Boolean = false,
    /** TV: focus the first card once it's up (a page that opens on this shelf). */
    focusFirst: Boolean = false,
    /** TV: the card opened from here, so Back lands on it (see [jaskReturnFocus]). */
    returnTo: String? = null, onReturnTo: (String?) -> Unit = {}) {
    val returnAtEntry = remember { returnTo }
    val shown = refs.filter { it.key != currentKey && (!onlyOnSources || it.key in local) }
    if (shown.isEmpty()) return
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Box(Modifier.padding(start = jaskSide(), end = jaskSide(), bottom = 10.dp)) { JaskSectionTitle(title) }
        LazyRow(Modifier.jaskRowStops(), contentPadding = PaddingValues(horizontal = jaskSide()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(shown, key = { _, it -> it.key }) { index, ref ->
                val id = local[ref.key]
                // TV: every card the same height (the d-pad); touch: no blank lines under short titles.
                val lines = if (app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) 2 else 1
                val first = remember { androidx.compose.ui.focus.FocusRequester() }
                if (focusFirst && index == 0 && app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV) {
                    var done by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                    if (!done) LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); done = true; runCatching { first.requestFocus() } }
                }
                // The ring goes round the poster, not the caption: drawn over the whole card it covered the caption's
                // first letter ("nterstellar"), and the card's clip cut into the lines under the title.
                val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Column(Modifier.width(128.dp).focusRequester(first).jaskReturnFocus(ref.key, returnTo, returnAtEntry)
                    .jaskCard(source, key = "shelf:$title/${ref.key}", scale = 1.05f) {
                    if (id != null) onReturnTo(ref.key)
                    when {
                        id == null -> android.widget.Toast.makeText(context, "${ref.title} isn't on your sources", android.widget.Toast.LENGTH_SHORT).show()
                        ref.movie -> nav.openMovie(id)
                        else -> nav.openSeries(id)
                    }
                }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2826))
                        .jaskCardArt(source, 10.dp).alpha(if (id == null) 0.45f else 1f)) {
                        if (ref.poster != null) AsyncImage(ref.poster, ref.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    // Every card the same height (two title lines, two detail lines): a taller card reaches further down,
                    // and the d-pad's search took it as "below", so Down went sideways along the row.
                    Text(ref.title, color = if (id == null) Color(0xFF9E9E9E) else Color.White, style = MaterialTheme.typography.labelLarge,
                        minLines = lines, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(listOfNotNull(ref.year?.toString(), ref.role, if (id == null) "Not on your sources" else null).joinToString(" · "),
                        color = Color(0xFF9E9E9E), style = MaterialTheme.typography.labelSmall, minLines = lines, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** A sideways row on a page: Left/Right stop at its ends instead of jumping to whatever lies that way (the Play button). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.jaskRowStops(): Modifier = focusProperties {
    exit = { d ->
        if (d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right)
            androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default
    }
}.focusGroup()

/**
 * TV: a card that opened another page takes focus again when its row is built afresh on the way Back. [returnTo] is
 * the card key saved by the page (rememberSaveable), [atEntry] its value when the row was first built: only then, not
 * the moment the card is clicked (still on this page).
 */
@Composable
internal fun Modifier.jaskReturnFocus(key: String, returnTo: String?, atEntry: String?): Modifier {
    val tv = app.opentv.ui.LocalLayoutClass.current == app.opentv.ui.LayoutClass.TV
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    if (tv && atEntry == key && returnTo == key) LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { requester.requestFocus() } }
    return this.focusRequester(requester)
}

private val OnSourcesMemory: MutableMap<String, Map<String, Long>> = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<String, Map<String, Long>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Long>>?) = size > 32
    })
