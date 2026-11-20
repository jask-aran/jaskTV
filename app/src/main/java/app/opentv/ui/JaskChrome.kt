package app.opentv.ui

import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.opentv.R
import androidx.compose.ui.res.stringResource

internal enum class JaskDestination(val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector, val scope: String) {
    LIVE(R.string.nav_live_tv, Icons.Default.LiveTv, "channels"),
    /** Games from the sports schedules you follow, and where they're on: a part of live TV led by the schedule. */
    SPORTS(R.string.nav_sports, Icons.Default.EmojiEvents, "channels"),
    MOVIES(R.string.nav_movies, Icons.Default.Theaters, "movies"),
    SHOWS(R.string.nav_shows, Icons.Default.VideoLibrary, "series"),
    LIBRARY(R.string.nav_saved_simple, Icons.Default.Bookmarks, "all"),
}
internal val LocalJaskTouchShell = staticCompositionLocalOf { false }
internal val LocalJaskFilterRequest = staticCompositionLocalOf { 0 }
internal val LocalJaskContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }
/** Lets a destination (e.g. My TV's "Find channels") put the cursor in the header search field. */
internal val LocalJaskSearchFocus = staticCompositionLocalOf { androidx.compose.ui.focus.FocusRequester() }
/** Opens the bottom search bar (e.g. My TV's "Find channels"). */
internal val LocalJaskOpenSearch = staticCompositionLocalOf<() -> Unit> { {} }
/** The selected tab in the navigation, where the d-pad lands when it leaves a page upwards. */
internal val LocalJaskNavFocus = staticCompositionLocalOf<FocusRequester?> { null }

/**
 * D-pad movement between the chrome (header and navigation) and the page under it, and within pages.
 *
 * Pages draw edge to edge, under the header, so Compose's own focus search can't move between the two (to it the
 * page starts at the top of the screen, never "below" the navigation). The chrome routes it instead:
 *  - Down from the header enters the page: the item you were last on ([JaskFocusMemory]), else its first item.
 *  - Up off the top of a page, holding Up, or Back land on the selected tab.
 *  - When the page swaps what's on screen under the focused item (opening a category, Back from it, a search), focus
 *    goes to the new content, or back to the item it was on before, never to the navigation by accident.
 *  - Scrolling keeps the focused item clear of the header ([JaskTvBringIntoView]).
 */
/** The page's way back to its top (see [jaskPageTop]); set by the page's scrolling list. */
internal class JaskPageTop { var scrollToTop: (suspend () -> Unit)? = null }
internal val LocalJaskPageTop = staticCompositionLocalOf<JaskPageTop?> { null }

/** Registers [scroll] as how this page returns to its top when focus goes up to the header. */
@Composable
internal fun JaskPageTopEffect(key: Any, scroll: suspend () -> Unit) {
    val top = LocalJaskPageTop.current ?: return
    DisposableEffect(top, key) { top.scrollToTop = scroll; onDispose { if (top.scrollToTop === scroll) top.scrollToTop = null } }
}

private class JaskChromeFocus {
    val page = FocusRequester()
    val nav = FocusRequester()
    /** True only while entering: the page box takes focus for a moment so it can hand it to a child. */
    var entering = false
    var pageHasFocus by mutableStateOf(false)
    /** The page box itself holds focus (mid-entry, or nothing on the page could take it). */
    var pageItself = false
    var headerHasFocus = false
    var barHasFocus = false
    val chromeHasFocus get() = headerHasFocus || barHasFocus
    /** Bumped when focus drops out of the page without going anywhere (its item was removed). */
    var lost by mutableIntStateOf(0)
    /** Holding Up jumped to the navigation; swallow the rest of that press. */
    var holdingUp = false
    /**
     * Focus left the page because you asked (Up, Back, holding Up). Otherwise focus reaching the header right after
     * the page lost its item is Android's doing (it refocuses the window, and Compose picks the top-left control).
     */
    var leftOnPurpose = false
}

/**
 * Where scrolling puts the focused item. The page's own vertical scroller (it fills the page) keeps the focused
 * row at a fixed line just under the header, as Netflix does, so the title above a row stays readable and nothing
 * focused is ever hidden behind the header; rows scroll sideways only as far as needed, leaving room for the
 * enlarged card.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class JaskTvBringIntoView(val pageHeight: () -> Float, val lead: Float, val edge: Float) :
    androidx.compose.foundation.gestures.BringIntoViewSpec {
    // Short and decisive, so the page keeps up with the d-pad; the default spring took ~400 ms to settle.
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val vertical = kotlin.math.abs(containerSize - pageHeight()) < 4f
        val start = if (vertical) lead else edge
        val end = containerSize - edge
        return when {
            size > end - start -> offset - start
            vertical -> if (offset < start || offset + size > end) offset - start else 0f
            offset < start -> offset - start
            offset + size > end -> offset + size - end
            else -> 0f
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun JaskChromeFrame(
    current: JaskDestination, onSelect: (JaskDestination) -> Unit,
    query: String, onQuery: (String) -> Unit, onFilter: () -> Unit,
    accountMenu: List<JaskMenuItem>,
    searchEnabled: Boolean = true,
    filterIcon: androidx.compose.ui.graphics.vector.ImageVector? = Icons.Default.Tune,
    filterLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var searching by rememberSaveable { mutableStateOf(false) }
    val chromeFocus = remember { JaskChromeFocus() }
    // When focus goes from the page up to the header (Up off the top, holding Up, pressing Up faster than the page can
    // scroll), the page goes back to its top, so the header never sits over a half-scrolled page.
    val pageTop = remember { JaskPageTop() }
    LaunchedEffect(chromeFocus.pageHasFocus) {
        if (chromeFocus.pageHasFocus) return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        if (chromeFocus.chromeHasFocus) runCatching { pageTop.scrollToTop?.invoke() }
    }
    // Touch: the page has been scrolled down (the header steps back), until it scrolls up again or the header is tapped.
    var scrolledAway by remember { mutableStateOf(false) }
    val scrollWatch = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // Down the page: dense band. Back only once the page has reached its top (scroll left over upward), not on
                // any upward nudge mid-page (the end of a fling), which let titles show through under the header.
                if (consumed.y < -4f) scrolledAway = true else if (available.y > 4f) scrolledAway = false
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val overlayLayers = remember { mutableStateOf<JaskOverlay?>(null) }
    fun nothingFocusedNow() = !chromeFocus.pageHasFocus && !chromeFocus.chromeHasFocus && overlayLayers.value?.layers.isNullOrEmpty()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val isTv = LocalLayoutClass.current == LayoutClass.TV
    // Per tab, and outliving this screen: coming back from a title's page or the player finds your place again.
    val memory = JaskFocusMemory.forTab(current.name)
    val scope = rememberCoroutineScope()
    fun toNav() { chromeFocus.leftOnPurpose = true; if (runCatching { chromeFocus.nav.requestFocus() }.isFailure) runCatching { searchFocus.requestFocus() } }
    /** Puts focus on the page: where you were, else its entry or first item. Focus events arrive a frame later. */
    fun enterPage() {
        if (memory.restore()) return
        chromeFocus.entering = true
        runCatching { chromeFocus.page.requestFocus(); focusManager.moveFocus(FocusDirection.Enter) }
        chromeFocus.entering = false
    }
    suspend fun landedOnPage(): Boolean { androidx.compose.runtime.withFrameNanos { }; return chromeFocus.pageHasFocus && !chromeFocus.pageItself }
    /** Down from the header: enter the page; if it has nothing to focus yet (still loading), stay on the tab. */
    fun toPage() { enterPage(); scope.launch { if (!landedOnPage() && (chromeFocus.pageItself || nothingFocusedNow())) toNav() } }
    // Edge to edge: pages draw under the status and navigation bars; chrome and content padding add the bar heights.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ambient = remember { JaskAmbient() }
    LaunchedEffect(current) { ambient.art = null; scrolledAway = false }
    // Tablet, desktop and TV have width to spare: the pill navigation moves up into the header.
    val wide = LocalLayoutClass.current.isWide
    fun nothingFocused() = nothingFocusedNow()
    // Focus fell out of the page (its item was removed: a category opened, Back closed one, results changed). Give
    // the new content a moment to appear, then put focus back on the page: the item you came from if it's back,
    // else the page's entry or first item. On launch focus starts on the tab, so the d-pad always has somewhere to start.
    if (wide) LaunchedEffect(chromeFocus.lost, current) {
        repeat(12) { attempt ->
            androidx.compose.runtime.withFrameNanos { }
            val accidental = chromeFocus.chromeHasFocus && !chromeFocus.leftOnPurpose && chromeFocus.lost > 0
            if (!nothingFocused() && !chromeFocus.pageItself && !accidental) return@LaunchedEffect
            // On launch (and on a new tab with nothing focused) start on the tab: entering the page could land on a
            // text field and pop the keyboard up unasked.
            if (chromeFocus.lost == 0 && !memory.hasHistory) { toNav(); return@LaunchedEffect }
            enterPage()
            if (landedOnPage()) return@LaunchedEffect
            if (attempt == 11) { toNav(); return@LaunchedEffect }
            kotlinx.coroutines.delay(40)
        }
    }
    CompositionLocalProvider(LocalJaskOpenSearch provides { if (searchEnabled) searching = true }, LocalJaskTouchShell provides true,
        LocalJaskAmbient provides ambient,
        LocalJaskSearchFocus provides searchFocus, LocalJaskNavFocus provides chromeFocus.nav,
        LocalJaskContentPadding provides if (wide) PaddingValues(top = statusBar + 80.dp, bottom = navBar + 16.dp)
            else PaddingValues(top = statusBar + 72.dp, bottom = navBar + 84.dp)) {
        // TV convention: Back on a page goes to the navigation first (pages' own Back handlers, composed later, win).
        androidx.activity.compose.BackHandler(isTv && chromeFocus.pageHasFocus) { toNav() }
        // The waves are part of the recorded page, so the header and rail glass blur them too.
        // The remote's search key (or a voice remote's) opens search, as everywhere on Android TV.
        JaskSurfaceHost(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
            val native = e.nativeKeyEvent
            when {
                e.type == KeyEventType.KeyDown && e.key == Key.Search && searchEnabled -> {
                    searching = true; JaskTvKeyboardOpen.value = true; true
                }
                // Holding Up on a page jumps to the navigation, however far down the page you are.
                wide && e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown && chromeFocus.pageHasFocus &&
                    native.repeatCount >= 1 -> { chromeFocus.holdingUp = true; toNav(); true }
                chromeFocus.holdingUp && e.key == Key.DirectionUp -> { if (e.type == KeyEventType.KeyUp) chromeFocus.holdingUp = false; true }
                else -> false
            }
        }.onKeyEvent { e ->
            // Up that the page didn't use: move as usual; if that leaves the page, or there's nothing above, land on
            // the selected tab rather than whichever header button happens to be nearest.
            if (wide && e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && chromeFocus.pageHasFocus) {
                chromeFocus.leftOnPurpose = true
                if (!focusManager.moveFocus(FocusDirection.Up)) toNav()
                // Focus events arrive a frame later: if that move left the page, land on the selected tab.
                else scope.launch { androidx.compose.runtime.withFrameNanos { }; if (!chromeFocus.pageHasFocus) toNav() }
                true
            } else false
        }, background = {
            Box(Modifier.matchParentSize().jaskWavesBackground(ambient))
            // The page as one focus area, with its focus memory and header-aware scrolling.
            val density = LocalDensity.current
            var pageHeight by remember { mutableFloatStateOf(0f) }
            val bringIntoView = remember(density, statusBar, wide) {
                with(density) { JaskTvBringIntoView({ pageHeight }, lead = (statusBar + if (wide) 80.dp + 40.dp else 72.dp + 24.dp).toPx(), edge = 48.dp.toPx()) }
            }
            Box(Modifier.matchParentSize().onSizeChanged { pageHeight = it.height.toFloat() }
                .then(if (isTv) Modifier else Modifier.nestedScroll(scrollWatch))
                .onFocusChanged {
                    val had = chromeFocus.pageHasFocus
                    // Only on re-entering: focus reports arrive a frame late, and a late "still on the page" from the
                    // move before holding Up must not cancel that deliberate exit.
                    if (it.hasFocus && !had) chromeFocus.leftOnPurpose = false
                    chromeFocus.pageHasFocus = it.hasFocus; chromeFocus.pageItself = it.isFocused
                    if (had && !it.hasFocus) chromeFocus.lost++
                }
                .focusRequester(chromeFocus.page).focusProperties {
                    canFocus = chromeFocus.entering
                    // Only Up leaves the page (to the selected tab, see onKeyEvent): sideways moves near the top
                    // would otherwise slide into the header's buttons.
                    // (Programmatic moves to the tab, from Back or holding Up, arrive as Enter and pass.)
                    if (wide) exit = { direction ->
                        if (direction == FocusDirection.Left || direction == FocusDirection.Right || direction == FocusDirection.Down)
                            FocusRequester.Cancel else FocusRequester.Default
                    }
                }
                .focusTarget()) {
                CompositionLocalProvider(LocalJaskFocusMemory provides memory, LocalJaskPageTop provides pageTop,
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (isTv) bringIntoView
                        else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current)) {
                    content()
                }
            }
        }) {
            overlayLayers.value = LocalJaskOverlay.current
            // While you're down the page (TV: focus on the page; touch: scrolled down) the header steps aside so rows and
            // art scrolling up don't show through it, and comes back on return (TV: Up off the top, holding Up, Back;
            // touch: scrolling up, or tapping where it was). The navigation stays, dimmed: it's how you know which
            // page you're on.
            // Touch keeps the header as it is, live glass at full strength: dimming it there only made it look washed out.
            val away = isTv && chromeFocus.pageHasFocus
            val chromeAlpha by androidx.compose.animation.core.animateFloatAsState(if (away) 0f else 1f,
                androidx.compose.animation.core.tween(220), label = "chromeAlpha")
            // The whole header stays, dimmed (the brand, the navigation, sync and account): it's how you know where you
            // are and that it's there. Still fully usable by touch while dimmed.
            val chromeFade = Modifier.graphicsLayer { alpha = .72f + .28f * chromeAlpha }
            val navFade = Modifier.graphicsLayer { alpha = .72f + .28f * chromeAlpha }
            // Dim behind the status bar and header so the brand and clock read over artwork; cheap, no blur. Stays
            // partly while the header is away, so the dimmed navigation still reads over art scrolling under it.
            // Touch, scrolled down: content runs under the header, so its band is denser and reaches past the buttons
            // (titles like "What's on" read through the light one). At the top, and on TV, the light band over the art.
            Box(Modifier.graphicsLayer { alpha = .7f + .3f * chromeAlpha }.align(Alignment.TopCenter).fillMaxWidth()
                .height(statusBar + if (wide) (if (away || scrolledAway) 128.dp else 88.dp) else 112.dp).background(
                // TV with focus down the page is the same case: rows scroll up under the header.
                // scrolledAway is only ever set on touch (phones and tablets); away is the TV case.
                if (scrolledAway || away) Brush.verticalGradient(0f to Color(0xFF0E0D0C).copy(alpha = .97f), .6f to Color(0xFF0E0D0C).copy(alpha = .9f),
                    .8f to Color(0xFF0E0D0C).copy(alpha = .5f), 1f to Color.Transparent)
                else Brush.verticalGradient(
                if (wide) listOf(Color(0xFF141312).copy(alpha = .9f), Color(0xFF141312).copy(alpha = .55f), Color.Transparent)
                // At the top of a page (the hero's art under the header): the light band.
                else listOf(Color(0xFF141312).copy(alpha = .9f), Color(0xFF141312).copy(alpha = .55f), Color.Transparent))))
            if (!wide) JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            var accountOpen by remember { mutableStateOf(false) }
            // Down from anything in the header (tabs, search, filters, devices, account) enters the page; not while
            // a popover or sheet is open (the account menu, drawn as an overlay layer) — Down moves into that instead.
            val overlay = LocalJaskOverlay.current
            val downToPage = if (wide) Modifier.onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionDown && !(searching && JaskTvKeyboardOpen.value) && overlay?.layers.isNullOrEmpty()) {
                    if (e.type == KeyEventType.KeyDown) toPage()
                    true
                } else false
            } else Modifier
            JaskHeader(onFilter, { accountOpen = true },
                downToPage.then(chromeFade).onFocusChanged { chromeFocus.headerHasFocus = it.hasFocus }.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 8.dp),
                filterIcon = filterIcon, filterLabel = filterLabel) {
                JaskMenu(accountOpen, { accountOpen = false }, accountMenu)
            }
            if (wide) JaskBottomBar(current, onSelect, query, onQuery, searchEnabled, searching, { searching = it },
                downToPage.then(navFade).onFocusChanged { chromeFocus.barHasFocus = it.hasFocus }.align(Alignment.TopCenter).statusBarsPadding().padding(top = 7.dp).widthIn(max = 460.dp))
            else JaskBottomBar(current, onSelect, query, onQuery, searchEnabled, searching, { searching = it },
                navFade.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 12.dp))
            // TV: search's in-app keyboard along the bottom while searching; Back puts it away (then Back ends the search).
            if (LocalLayoutClass.current == LayoutClass.TV) {
                LaunchedEffect(searching) { JaskTvKeyboardOpen.value = searching }
                if (searching && JaskTvKeyboardOpen.value) {
                    // Back with nothing typed leaves search altogether; with text it only puts the keyboard away.
                    androidx.activity.compose.BackHandler {
                        JaskTvKeyboardOpen.value = false
                        if (query.isEmpty()) { searching = false; scope.launch { androidx.compose.runtime.withFrameNanos { }; toPage() } }
                    }
                    JaskTvKeyboard(onType = { onQuery((query + it).take(60)) }, onBackspace = { onQuery(query.dropLast(1)) }, onClear = { onQuery("") },
                        onHide = { JaskTvKeyboardOpen.value = false },
                        onDownOut = { JaskTvKeyboardOpen.value = false; scope.launch { androidx.compose.runtime.withFrameNanos { }; toPage() } },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                }
            }
        }
    }
}

/** The artwork the touch shell takes its page colour from; pages set it with [JaskAmbientFrom]. */
internal class JaskAmbient {
    var art by mutableStateOf<String?>(null)
    /** Flat colour of the dithered background; heroes fade into it so no dots show over artwork. */
    var wash by mutableStateOf(Color.Black)
}
internal val LocalJaskAmbient = staticCompositionLocalOf<JaskAmbient?> { null }
/** The artwork the shell was last coloured from: pages outside the shell (Settings) take the same colour. */
@Volatile internal var JaskLastArt: String? = null

/**
 * Phone media pages (film, show) draw outside the touch shell, so they get their own dithered waves here, coloured
 * from the title's art ([JaskAmbientFrom] on the page sets it), and the hero fades into the waves' own average colour.
 * A page already inside the shell uses the shell's. Wide screens: nothing (their pages draw their own backdrop).
 */
@Composable
internal fun JaskMediaAmbient(content: @Composable () -> Unit) {
    val outer = LocalJaskAmbient.current
    if (outer != null) { content(); return }
    // Tablets: their pages draw their own backdrop, but the clock still needs shading over text scrolling under it.
    if (LocalLayoutClass.current != LayoutClass.PHONE) {
        Box(Modifier.fillMaxSize()) { content(); JaskBarScrim(Modifier.align(Alignment.TopCenter), top = true) }
        return
    }
    val ambient = remember { JaskAmbient().also { it.art = JaskLastArt } }
    CompositionLocalProvider(LocalJaskAmbient provides ambient) {
        Box(Modifier.fillMaxSize().background(Color.Black).jaskWavesBackground(ambient)) {
            content()
            // The clock and battery stay legible over buttons and text scrolling up under them.
            JaskBarScrim(Modifier.align(Alignment.TopCenter), top = true)
        }
    }
}

/** Colours the shell's page from [url] (the featured art, the top search hit, what you're watching). */
@Composable
internal fun JaskAmbientFrom(url: String?) {
    val ambient = LocalJaskAmbient.current ?: return
    LaunchedEffect(url) { if (url != null) { ambient.art = url; JaskLastArt = url } }
}

/** Dims the area behind the status bar (top) or navigation bar (bottom) so content shows through but the bar stays legible. */
@Composable
internal fun JaskBarScrim(modifier: Modifier = Modifier, top: Boolean) {
    val inset = if (top) WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (inset == 0.dp) return
    Box(modifier.fillMaxWidth().height(inset + 20.dp).background(Brush.verticalGradient(
        if (top) listOf(Color.Black.copy(alpha = .82f), Color.Black.copy(alpha = .55f), Color.Transparent) else listOf(Color.Transparent, Color.Black.copy(alpha = .6f)))))
}

// ---- Overlay: frosted popovers and sheets drawn in-window, so they can blur the page ----------------

/** Popovers and sheets register here; a [JaskSurfaceHost] draws them above its content. */
internal class JaskOverlay {
    val layers = mutableStateListOf<Pair<Any, @Composable () -> Unit>>()
    var origin by mutableStateOf(Offset.Zero)
}
internal val LocalJaskOverlay = staticCompositionLocalOf<JaskOverlay?> { null }

/** A page with glass: records [background] for blur and hosts in-window popovers and sheets above [content]. */
@Composable
internal fun JaskSurfaceHost(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit = {}) {
    val overlay = remember { JaskOverlay() }
    CompositionLocalProvider(LocalJaskOverlay provides overlay) {
        JaskGlassHost(modifier.onGloballyPositioned { overlay.origin = it.positionInRoot() }, background = background) {
            content()
            overlay.layers.forEach { (key, layer) -> androidx.compose.runtime.key(key) { layer() } }
        }
    }
}

@Composable
internal fun JaskOverlayEntry(visible: Boolean, layer: @Composable () -> Unit): Boolean {
    val overlay = LocalJaskOverlay.current ?: return false
    val key = remember { Any() }
    val current by rememberUpdatedState(layer)
    DisposableEffect(visible, overlay) {
        if (visible) overlay.layers.add(key to { current() })
        onDispose { overlay.layers.removeAll { it.first === key } }
    }
    return true
}

/**
 * A popover row. [header] rows are small section labels (not tappable); [divider] draws a rule
 * above; [keepOpen] leaves the popover open after a tap (for checks and switches).
 */
internal data class JaskMenuItem(val label: String, val checked: Boolean = false, val enabled: Boolean = true,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null, val subtitle: String? = null,
    val header: Boolean = false, val divider: Boolean = false, val keepOpen: Boolean = false,
    val toggle: Boolean? = null, val onClick: () -> Unit = {})

/** Which [JaskMenu] is open, if any. */
private object JaskMenuOpen { var current by mutableStateOf<Any?>(null) }

/**
 * Frosted popover menu anchored to the enclosing Box (like DropdownMenu). Drawn in the page's own
 * window so it blurs what's behind it; falls back to a Material menu where no [JaskSurfaceHost] exists.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun BoxScope.JaskMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<JaskMenuItem>) {
    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // One menu at a time: opening one closes any other that is open.
    val token = remember { Any() }
    LaunchedEffect(expanded) { if (expanded) JaskMenuOpen.current = token else if (JaskMenuOpen.current === token) JaskMenuOpen.current = null }
    LaunchedEffect(expanded, JaskMenuOpen.current) { if (expanded && JaskMenuOpen.current != null && JaskMenuOpen.current !== token) onDismiss() }
    Box(Modifier.matchParentSize().onGloballyPositioned { anchor = it.boundsInRoot() })
    val hosted = JaskOverlayEntry(expanded) {
        val overlay = LocalJaskOverlay.current!!
        androidx.activity.compose.BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().focusProperties { canFocus = false }
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss))
        val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
        androidx.compose.ui.layout.Layout({
            JaskGlassSurface(Modifier.widthIn(min = 180.dp, max = 280.dp), RoundedCornerShape(16.dp)) {
                // The d-pad stays inside an open menu: it can't wander to the header behind and open another.
                // Read, not looked through: dense enough that the page behind doesn't read through the rows.
                Column(Modifier.background(Color(0xFF0E0D0C).copy(alpha = .6f)).focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }.focusGroup()
                    .padding(vertical = 6.dp).width(IntrinsicSize.Max)
                    .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .75f).dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    // A menu opens with its first row focused, so the d-pad starts inside it.
                    JaskFocusReturn.ReturnOnClose()
                    val first = remember { androidx.compose.ui.focus.FocusRequester() }
                    val firstIndex = items.indexOfFirst { !it.header && it.enabled }
                    LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { first.requestFocus() } }
                    items.forEachIndexed { i, item ->
                        // Keyed by label: when rows come and go (sync jobs ending) the focused row keeps its focus.
                        key(item.label, item.header) {
                            Box(if (i == firstIndex) Modifier.focusRequester(first) else Modifier) {
                                JaskMenuRow(item) { if (!item.keepOpen) onDismiss(); item.onClick() }
                            }
                        }
                    }
                }
            }
        }, Modifier.fillMaxSize()) { measurables, constraints ->
            val menu = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val a = anchor.translate(-overlay.origin)
            val x = (a.right - menu.width).toInt().coerceIn(margin, (constraints.maxWidth - menu.width - margin).coerceAtLeast(margin))
            val below = (a.bottom + margin / 2).toInt()
            val y = if (below + menu.height + margin <= constraints.maxHeight) below else (a.top - menu.height - margin / 2).toInt().coerceAtLeast(margin)
            layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x, y) }
        }
    }
    if (!hosted) DropdownMenu(expanded, onDismissRequest = onDismiss) {
        items.filterNot { it.header }.forEach { item ->
            DropdownMenuItem(text = { Text(item.label) }, enabled = item.enabled, onClick = { onDismiss(); item.onClick() },
                trailingIcon = if (item.checked) ({ Icon(Icons.Default.Check, null) }) else null)
        }
    }
}

@Composable
private fun JaskMenuRow(item: JaskMenuItem, onTap: () -> Unit) {
    if (item.divider) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color.White.copy(alpha = .12f)))
    if (item.header) {
        Text(item.label.uppercase(), color = Color(0x99F4F4F4), style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp))
        return
    }
    val fg = if (item.enabled) Color(0xFFF4F4F4) else Color(0x80F4F4F4)
    // Focus: the same ring and wash as everywhere else (no lift: a menu is a list of choices).
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) JaskSounds.focusLanded() }
    // At least the menu's 180dp minimum (less the 6dp sides): the menu's column is sized to its widest row, so
    // without this short rows left the column narrower than the glass and the focus ring hugged the text.
    Row(Modifier.padding(horizontal = 6.dp).fillMaxWidth().widthIn(min = 168.dp).clip(RoundedCornerShape(10.dp))
        .clickable(source, JaskIndication, enabled = item.enabled, onClick = { JaskSounds.pressed(); onTap() })
        .padding(horizontal = 10.dp, vertical = if (item.subtitle != null) 8.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
        item.icon?.let { Icon(it, null, Modifier.padding(end = 12.dp).size(20.dp), tint = fg) }
        Column(Modifier.weight(1f)) {
            Text(item.label, color = fg, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, color = Color(0xA6F4F4F4), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        when {
            item.toggle != null -> JaskGlassSwitch(item.toggle, Modifier.padding(start = 12.dp))
            item.checked -> Icon(Icons.Default.Check, null, Modifier.padding(start = 12.dp).size(18.dp), tint = Color.White)
        }
    }
}

/** Small on/off pill in the jaskTV style (display only; the row handles the tap). */
@Composable
internal fun JaskGlassSwitch(on: Boolean, modifier: Modifier = Modifier) {
    val x by androidx.compose.animation.core.animateDpAsState(if (on) 16.dp else 0.dp, label = "switch")
    Box(modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(50))
        .background(if (on) Color(0xFFF4F4F4) else Color.White.copy(alpha = .18f)).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(18.dp).clip(CircleShape).background(if (on) Color(0xFF141312) else Color(0xFFE8E8E8)))
    }
}

/**
 * Frosted bottom sheet drawn in-window (tap outside or Back to close). Falls back to a Material
 * sheet where no [JaskSurfaceHost] exists.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun JaskSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val hosted = JaskOverlayEntry(true) {
        androidx.activity.compose.BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)).focusProperties { canFocus = false }
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss)) {
            JaskGlassSurface(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                .focusProperties { canFocus = false }.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {},
                RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
                // The sheet opens with its first control focused, so the d-pad starts inside it.
                JaskFocusReturn.ReturnOnClose()
                val enter = remember { androidx.compose.ui.focus.FocusRequester() }
                var entering by remember { mutableStateOf(false) }
                val sheetFocus = androidx.compose.ui.platform.LocalFocusManager.current
                LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos { }
                    entering = true
                    runCatching { enter.requestFocus(); sheetFocus.moveFocus(FocusDirection.Enter) }
                    entering = false
                }
                // Much denser than header glass: a sheet is read, so the page under it mustn't read through it. Without live
                // blur (TVs, frosted or solid glass, weak GPUs) nothing softens the page behind, so nearly opaque.
                val sheetLive = LocalJaskMaterial.current == JaskMaterial.LIVE
                Column(Modifier.background(Color(0xFF0E0D0C).copy(alpha = if (sheetLive) .7f else .9f)).focusRequester(enter).focusProperties { canFocus = entering }.focusTarget()
                    .navigationBarsPadding().padding(top = 10.dp, bottom = 12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .35f)))
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
    if (!hosted) ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xF0222120)) { Column(content = content) }
}

/**
 * Glass host: on the LIVE material, [background] is recorded into one layer per frame and drawn,
 * and every [JaskGlassSurface] inside [content] blurs that layer. Content is drawn on top and never
 * recorded, so glass can't sample itself. Other materials draw [background] directly: no recording.
 */
@Composable
internal fun JaskGlassHost(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit) {
    val live = LocalJaskMaterial.current == JaskMaterial.LIVE
    val page = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val down = remember { debugProp("debug.jasktv.blurdown")?.toFloatOrNull()?.takeIf { it >= 1f } ?: 4f }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    val backdrop = remember(blurred) { JaskLiveBackdrop(blurred, down) }
    if (live) remember(blurred, radius) { blurred.renderEffect = jaskLiveEffect(radius / down) }
    CompositionLocalProvider(LocalJaskBackdrop provides if (live) backdrop else null) {
        Box(modifier) {
            Box(Modifier.matchParentSize().then(if (live) Modifier.onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    page.record { this@drawWithContent.drawContent() }
                    drawLayer(page)
                    val small = androidx.compose.ui.unit.IntSize(kotlin.math.ceil(size.width / down).toInt(), kotlin.math.ceil(size.height / down).toInt())
                    blurred.record(small) { scale(1f / down, pivot = Offset.Zero) { drawLayer(page) } }
                } else Modifier)) {
                // Glass inside the recorded page must not sample this layer: it would draw itself
                // recursively (a native stack overflow in the renderer). It gets the tinted look.
                CompositionLocalProvider(LocalJaskBackdrop provides null, LocalJaskInRecording provides true) { background() }
            }
            content()
        }
    }
}

/** The jaskTV wordmark: Druk Wide Super outlines (res/drawable/jask_wordmark.xml), so no font file ships. */
@Composable
internal fun JaskWordmark(modifier: Modifier = Modifier, tint: Color = Color(0xFFF4F4F4)) {
    Icon(androidx.compose.ui.res.painterResource(R.drawable.jask_wordmark), "jaskTV", modifier.aspectRatio(5.85f), tint = tint)
}

/** A glass circle button (media-page actions, header buttons). */
@Composable
internal fun JaskGlassCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, contentDescription: String,
    modifier: Modifier = Modifier, active: Boolean = false, size: androidx.compose.ui.unit.Dp = 48.dp, onClick: () -> Unit) {
    JaskGlassSurface(modifier.size(size), CircleShape, showFocus = true) {
        IconButton(onClick, Modifier.jaskFocusRing().then(Modifier.fillMaxSize())) {
            Icon(icon, contentDescription, Modifier.size(22.dp), tint = if (active) Color.White else Color(0xFFF0F0F0))
        }
    }
}

/** Icon-only capsule (Apple-style tab bar); tab names remain as accessibility labels. */
@Composable
internal fun JaskFloatingNavigation(current: JaskDestination, onSelect: (JaskDestination) -> Unit, modifier: Modifier = Modifier) {
    JaskGlassSurface(modifier.testTag("jask.navigation").height(JaskRailHeight), RoundedCornerShape(50)) {
        Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val navFocus = LocalJaskNavFocus.current
            JaskDestination.entries.forEach { destination ->
                val selected = current == destination
                var focused by remember { mutableStateOf(false) }
                // D-pad focus is a solid white pill with a dark icon (as on Android TV), clearly unlike "selected".
                val fill by animateColorAsState(when { focused -> Color(0xFFF4F4F4); selected -> Color(0x30FFFFFF); else -> Color.Transparent }, label = "tabFill")
                val tint = when { focused -> Color(0xFF141312); selected -> Color.White; else -> Color(0xFFB0B0B0) }
                val label = stringResource(destination.label)
                Box(Modifier.weight(1f).fillMaxHeight()
                    .then(if (selected && navFocus != null) Modifier.focusRequester(navFocus) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(RoundedCornerShape(50)).background(fill)
                    .selectable(selected, role = Role.Tab, onClick = { onSelect(destination) })
                    .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center) {
                    Icon(destination.icon, null, Modifier.size(24.dp), tint = tint)
                }
            }
        }
    }
}

internal val JaskRailHeight = 54.dp

/** Brand, filters for the current destination, and account. Search lives in the bottom bar. */
@Composable
internal fun JaskHeader(onFilter: () -> Unit, onAccount: () -> Unit, modifier: Modifier = Modifier,
    filterIcon: androidx.compose.ui.graphics.vector.ImageVector? = Icons.Default.Tune, filterLabel: String? = null,
    accountMenu: @Composable BoxScope.() -> Unit = {}) {
    Row(modifier.testTag("jask.header").height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        JaskWordmark(Modifier.height(20.dp))
        Spacer(Modifier.weight(1f))
        // Background work (account sync, provider downloads, guide, TMDB): a spinner that opens the details. First in the
        // row: it comes and goes, and appearing between the other buttons moved them under your finger.
        JaskSyncButton()
        if (filterIcon != null) JaskGlassSurface(Modifier.size(48.dp), RoundedCornerShape(50), showFocus = true) {
            IconButton(onFilter, Modifier.jaskFocusRing().then(Modifier.fillMaxSize())) {
                Icon(filterIcon, filterLabel ?: stringResource(R.string.catalogue_filters), Modifier.size(20.dp), tint = Color(0xFFF0F0F0))
            }
        }
        // Your other devices and the remote, beside the account circle (only when signed in with more than one device).
        JaskDevicesButton()
        Box {
            JaskGlassSurface(Modifier.size(48.dp), RoundedCornerShape(50), showFocus = true) {
                IconButton(onAccount, Modifier.jaskFocusRing().then(Modifier.fillMaxSize())) {
                    Icon(Icons.Default.Person, stringResource(R.string.jask_account), tint = Color(0xFFF5F5F5), modifier = Modifier.size(23.dp))
                }
            }
            accountMenu()
        }
    }
}

/**
 * Tabs plus a search button at the rail's end. Opening search swaps the tabs for a search bar typed
 * into directly; the current destination filters in place. Greyed when the destination can't search.
 */
@Composable
internal fun JaskBottomBar(current: JaskDestination, onSelect: (JaskDestination) -> Unit,
    query: String, onQuery: (String) -> Unit, searchEnabled: Boolean,
    searching: Boolean, setSearching: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(query) { if (query.isNotEmpty()) setSearching(true) }
    val focus = LocalJaskSearchFocus.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun close() { onQuery(""); focusManager.clearFocus(); setSearching(false) }
    androidx.activity.compose.BackHandler(searching) { close() }
    LaunchedEffect(current) { if (query.isEmpty()) setSearching(false) }
    val tv = LocalLayoutClass.current == LayoutClass.TV
    // A TV types with the in-app keyboard (JaskTvKeyboard, shown by the chrome), not the system one.
    LaunchedEffect(searching) { if (searching && !tv) runCatching { focus.requestFocus(); keyboard?.show() } }
    Row(modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (searching) {
            JaskGlassSurface(Modifier.weight(1f).height(JaskRailHeight), RoundedCornerShape(50)) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, Modifier.padding(start = 16.dp, end = 10.dp).size(21.dp), tint = Color(0xFFF5F5F5))
                    Box(Modifier.weight(1f).padding(end = 16.dp), contentAlignment = Alignment.CenterStart) {
                        val style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFF5F5F5))
                        if (query.isEmpty()) Text(stringResource(when (current) {
                            JaskDestination.LIVE, JaskDestination.SPORTS -> R.string.jask_search_live
                            JaskDestination.MOVIES -> R.string.jask_search_movies
                            JaskDestination.SHOWS -> R.string.jask_search_shows
                            JaskDestination.LIBRARY -> R.string.jask_search_library
                        }), color = Color(0xFFBDBDBD), style = style, maxLines = 1)
                        if (tv) Text(query, Modifier.fillMaxWidth().testTag("jask.search").focusRequester(focus).clickable { JaskTvKeyboardOpen.value = true },
                            style = style, maxLines = 1)
                        else androidx.compose.foundation.text.BasicTextField(query, { onQuery(it.take(60)) },
                            Modifier.fillMaxWidth().testTag("jask.search").focusRequester(focus), singleLine = true, textStyle = style,
                            cursorBrush = SolidColor(Color(0xFFF5F5F5)),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide(); focusManager.clearFocus() }))
                    }
                }
            }
            JaskGlassSurface(Modifier.size(JaskRailHeight), RoundedCornerShape(50), showFocus = true) {
                IconButton(::close, Modifier.jaskFocusRing().then(Modifier.fillMaxSize())) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_cancel), Modifier.size(22.dp), tint = Color(0xFFF5F5F5))
                }
            }
        } else {
            JaskFloatingNavigation(current, onSelect, Modifier.weight(1f))
            JaskGlassSurface(Modifier.size(JaskRailHeight), RoundedCornerShape(50), showFocus = true) {
                IconButton({ setSearching(true) }, Modifier.jaskFocusRing().then(Modifier.fillMaxSize().testTag("jask.searchButton")), enabled = searchEnabled) {
                    Icon(Icons.Default.Search, stringResource(R.string.nav_search), Modifier.size(24.dp),
                        tint = if (searchEnabled) Color(0xFFF5F5F5) else Color(0x55F5F5F5))
                }
            }
        }
    }
}


/** Whether search's in-app TV keyboard is up (one search bar at a time, so one shared state). */
internal val JaskTvKeyboardOpen = androidx.compose.runtime.mutableStateOf(false)
