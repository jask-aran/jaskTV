package app.opentv.ui

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
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
    MOVIES(R.string.nav_movies, Icons.Default.Movie, "movies"),
    SHOWS(R.string.nav_shows, Icons.Default.Tv, "series"),
    LIBRARY(R.string.nav_saved_simple, Icons.Default.Bookmarks, "all"),
}
internal val LocalJaskTouchShell = staticCompositionLocalOf { false }
internal val LocalJaskFilterRequest = staticCompositionLocalOf { 0 }
internal val LocalJaskContentPadding = staticCompositionLocalOf { PaddingValues(0.dp) }
/** Lets a destination (e.g. My TV's "Find channels") put the cursor in the header search field. */
internal val LocalJaskSearchFocus = staticCompositionLocalOf { androidx.compose.ui.focus.FocusRequester() }
/** Opens the bottom search bar (e.g. My TV's "Find channels"). */
internal val LocalJaskOpenSearch = staticCompositionLocalOf<() -> Unit> { {} }

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
    // Edge to edge: pages draw under the status and navigation bars; chrome and content padding add the bar heights.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ambient = remember { JaskAmbient() }
    LaunchedEffect(current) { ambient.art = null }
    // Tablet, desktop and TV have width to spare: the pill navigation moves up into the header.
    val wide = LocalLayoutClass.current.isWide
    CompositionLocalProvider(LocalJaskOpenSearch provides { if (searchEnabled) searching = true }, LocalJaskTouchShell provides true,
        LocalJaskAmbient provides ambient,
        LocalJaskSearchFocus provides searchFocus,
        LocalJaskContentPadding provides if (wide) PaddingValues(top = statusBar + 80.dp, bottom = navBar + 16.dp)
            else PaddingValues(top = statusBar + 72.dp, bottom = navBar + 84.dp)) {
        // The waves are part of the recorded page, so the header and rail glass blur them too.
        // The remote's search key (or a voice remote's) opens search, as everywhere on Android TV.
        JaskSurfaceHost(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Search && searchEnabled) {
                searching = true; JaskTvKeyboardOpen.value = true; true
            } else false
        }, background = {
            Box(Modifier.matchParentSize().jaskWavesBackground(ambient))
            content()
        }) {
            // Dim behind the status bar and header so the brand and clock read over artwork; cheap, no blur.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusBar + 88.dp).background(Brush.verticalGradient(
                listOf(Color(0xFF141312).copy(alpha = .9f), Color(0xFF141312).copy(alpha = .55f), Color.Transparent))))
            if (!wide) JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            var accountOpen by remember { mutableStateOf(false) }
            JaskHeader(onFilter, { accountOpen = true },
                Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 8.dp),
                filterIcon = filterIcon, filterLabel = filterLabel) {
                JaskMenu(accountOpen, { accountOpen = false }, accountMenu)
            }
            if (wide) JaskBottomBar(current, onSelect, query, onQuery, searchEnabled, searching, { searching = it },
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 7.dp).widthIn(max = 460.dp))
            else JaskBottomBar(current, onSelect, query, onQuery, searchEnabled, searching, { searching = it },
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 12.dp))
            // TV: search's in-app keyboard along the bottom while searching; Back puts it away (then Back ends the search).
            if (LocalLayoutClass.current == LayoutClass.TV) {
                LaunchedEffect(searching) { JaskTvKeyboardOpen.value = searching }
                if (searching && JaskTvKeyboardOpen.value) {
                    androidx.activity.compose.BackHandler { JaskTvKeyboardOpen.value = false }
                    JaskTvKeyboard(onType = { onQuery((query + it).take(60)) }, onBackspace = { onQuery(query.dropLast(1)) }, onClear = { onQuery("") },
                        onHide = { JaskTvKeyboardOpen.value = false },
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

/** Colours the shell's page from [url] (the featured art, the top search hit, what you're watching). */
@Composable
internal fun JaskAmbientFrom(url: String?) {
    val ambient = LocalJaskAmbient.current ?: return
    LaunchedEffect(url) { if (url != null) ambient.art = url }
}

/** Dims the area behind the status bar (top) or navigation bar (bottom) so content shows through but the bar stays legible. */
@Composable
internal fun JaskBarScrim(modifier: Modifier = Modifier, top: Boolean) {
    val inset = if (top) WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    if (inset == 0.dp) return
    Box(modifier.fillMaxWidth().height(inset + 20.dp).background(Brush.verticalGradient(
        if (top) listOf(Color.Black.copy(alpha = .55f), Color.Transparent) else listOf(Color.Transparent, Color.Black.copy(alpha = .6f)))))
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
private fun JaskOverlayEntry(visible: Boolean, layer: @Composable () -> Unit): Boolean {
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

/**
 * Frosted popover menu anchored to the enclosing Box (like DropdownMenu). Drawn in the page's own
 * window so it blurs what's behind it; falls back to a Material menu where no [JaskSurfaceHost] exists.
 */
@Composable
internal fun BoxScope.JaskMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<JaskMenuItem>) {
    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(Modifier.matchParentSize().onGloballyPositioned { anchor = it.boundsInRoot() })
    val hosted = JaskOverlayEntry(expanded) {
        val overlay = LocalJaskOverlay.current!!
        androidx.activity.compose.BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss))
        val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
        androidx.compose.ui.layout.Layout({
            JaskGlassSurface(Modifier.widthIn(min = 180.dp, max = 280.dp), RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(vertical = 6.dp).width(IntrinsicSize.Max)
                    .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .75f).dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    items.forEach { item -> JaskMenuRow(item) { if (!item.keepOpen) onDismiss(); item.onClick() } }
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
    Row(Modifier.fillMaxWidth().clickable(enabled = item.enabled, onClick = onTap)
        .padding(horizontal = 16.dp, vertical = if (item.subtitle != null) 8.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JaskSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val hosted = JaskOverlayEntry(true) {
        androidx.activity.compose.BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f))
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onDismiss)) {
            JaskGlassSurface(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {},
                RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
                Column(Modifier.navigationBarsPadding().padding(top = 10.dp, bottom = 12.dp)) {
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
        IconButton(onClick, Modifier.fillMaxSize()) {
            Icon(icon, contentDescription, Modifier.size(22.dp), tint = if (active) Color.White else Color(0xFFF0F0F0))
        }
    }
}

/** Icon-only capsule (Apple-style tab bar); tab names remain as accessibility labels. */
@Composable
internal fun JaskFloatingNavigation(current: JaskDestination, onSelect: (JaskDestination) -> Unit, modifier: Modifier = Modifier) {
    JaskGlassSurface(modifier.testTag("jask.navigation").height(JaskRailHeight), RoundedCornerShape(50)) {
        Row(Modifier.fillMaxSize().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            JaskDestination.entries.forEach { destination ->
                val selected = current == destination
                val fill by animateColorAsState(if (selected) Color(0x30FFFFFF) else Color.Transparent, label = "tabFill")
                val tint = if (selected) Color.White else Color(0xFFB0B0B0)
                val label = stringResource(destination.label)
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(fill)
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
        if (filterIcon != null) JaskGlassSurface(Modifier.size(48.dp), RoundedCornerShape(50), showFocus = true) {
            IconButton(onFilter, Modifier.fillMaxSize()) {
                Icon(filterIcon, filterLabel ?: stringResource(R.string.catalogue_filters), Modifier.size(20.dp), tint = Color(0xFFF0F0F0))
            }
        }
        // Your other devices and the remote, beside the account circle (only when signed in with more than one device).
        JaskDevicesButton()
        Box {
            JaskGlassSurface(Modifier.size(48.dp), RoundedCornerShape(50), showFocus = true) {
                IconButton(onAccount, Modifier.fillMaxSize()) {
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
                            JaskDestination.LIVE -> R.string.jask_search_live
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
                IconButton(::close, Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_cancel), Modifier.size(22.dp), tint = Color(0xFFF5F5F5))
                }
            }
        } else {
            JaskFloatingNavigation(current, onSelect, Modifier.weight(1f))
            JaskGlassSurface(Modifier.size(JaskRailHeight), RoundedCornerShape(50), showFocus = true) {
                IconButton({ setSearching(true) }, Modifier.fillMaxSize().testTag("jask.searchButton"), enabled = searchEnabled) {
                    Icon(Icons.Default.Search, stringResource(R.string.nav_search), Modifier.size(24.dp),
                        tint = if (searchEnabled) Color(0xFFF5F5F5) else Color(0x55F5F5F5))
                }
            }
        }
    }
}


/** Whether search's in-app TV keyboard is up (one search bar at a time, so one shared state). */
internal val JaskTvKeyboardOpen = androidx.compose.runtime.mutableStateOf(false)
