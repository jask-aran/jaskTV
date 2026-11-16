package app.opentv.ui

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
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

/** Debug A/B switch: `adb shell setprop debug.jasktv.noblur 1`, then restart the app. */
private fun debugNoBlur(): Boolean = app.opentv.BuildConfig.DEBUG && runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.jasktv.noblur") == "1"
}.getOrDefault(false)

/** One source layer is recorded per frame, shared by floating chrome. No bitmap readback. */
internal class JaskBackdrop(val layer: androidx.compose.ui.graphics.layer.GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}
private val LocalJaskBackdrop = staticCompositionLocalOf<JaskBackdrop?> { null }
/** True inside a glass host's recorded page, where frost renders without blur (see [JaskGlassHost]). */
private val LocalJaskInRecording = staticCompositionLocalOf { false }

@Composable
internal fun JaskChromeFrame(
    current: JaskDestination, onSelect: (JaskDestination) -> Unit,
    query: String, onQuery: (String) -> Unit, onFilter: () -> Unit,
    accountMenu: List<JaskMenuItem>,
    searchEnabled: Boolean = true,
    filterIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Tune,
    filterLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var searching by rememberSaveable { mutableStateOf(false) }
    // Edge to edge: pages draw under the status and navigation bars; chrome and content padding add the bar heights.
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    CompositionLocalProvider(LocalJaskOpenSearch provides { if (searchEnabled) searching = true }, LocalJaskTouchShell provides true,
        LocalJaskSearchFocus provides searchFocus,
        LocalJaskContentPadding provides PaddingValues(top = statusBar + 72.dp, bottom = navBar + 84.dp)) {
        JaskSurfaceHost(Modifier.fillMaxSize().background(Color(0xFF141312)), background = { content() }) {
            // Dim behind the status bar and header so the brand and clock read over artwork; cheap, no blur.
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusBar + 88.dp).background(Brush.verticalGradient(
                listOf(Color(0xFF141312).copy(alpha = .9f), Color(0xFF141312).copy(alpha = .55f), Color.Transparent))))
            JaskBarScrim(Modifier.align(Alignment.BottomCenter), top = false)
            var accountOpen by remember { mutableStateOf(false) }
            JaskHeader(onFilter, { accountOpen = true },
                Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                filterIcon = filterIcon, filterLabel = filterLabel) {
                JaskMenu(accountOpen, { accountOpen = false }, accountMenu)
            }
            JaskBottomBar(current, onSelect, query, onQuery, searchEnabled, searching, { searching = it },
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
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

internal data class JaskMenuItem(val label: String, val checked: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

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
            JaskFrostSurface(Modifier.widthIn(min = 180.dp, max = 280.dp), RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(vertical = 6.dp).width(IntrinsicSize.Max)) {
                    items.forEach { item ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = item.enabled) { onDismiss(); item.onClick() }
                            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.label, color = if (item.enabled) Color(0xFFF4F4F4) else Color(0x80F4F4F4),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            if (item.checked) Icon(Icons.Default.Check, null, Modifier.padding(start = 12.dp).size(18.dp), tint = Color.White)
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
        items.forEach { item ->
            DropdownMenuItem(text = { Text(item.label) }, enabled = item.enabled, onClick = { onDismiss(); item.onClick() },
                trailingIcon = if (item.checked) ({ Icon(Icons.Default.Check, null) }) else null)
        }
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
            JaskFrostSurface(Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
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
 * Glass host: [background] is recorded into one layer per frame and drawn; every [JaskFrostSurface]
 * inside [content] blurs that layer. Content is drawn on top and never recorded, so glass can't
 * sample itself. Used by the shell (pages behind the rails) and by media-page heroes (art behind buttons).
 */
@Composable
internal fun JaskGlassHost(modifier: Modifier = Modifier, background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    val useBackdrop = remember(context) { Build.VERSION.SDK_INT >= 31 && !debugNoBlur() &&
        !(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { JaskBackdrop(layer) }
    CompositionLocalProvider(LocalJaskBackdrop provides if (useBackdrop) backdrop else null) {
        Box(modifier) {
            Box(Modifier.matchParentSize().graphicsLayer().onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    if (useBackdrop) {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    } else drawContent()
                }) {
                // Glass inside the recorded page must not sample this layer: it would draw itself
                // recursively (a native stack overflow in the renderer). It gets the unblurred look.
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
    JaskFrostSurface(modifier.size(size), CircleShape) {
        IconButton(onClick, Modifier.fillMaxSize()) {
            Icon(icon, contentDescription, Modifier.size(22.dp), tint = if (active) Color.White else Color(0xFFF0F0F0))
        }
    }
}

/** Actual backdrop blur on API 31+, translucent material on low-RAM/API 23–30 devices. */
@Composable
internal fun JaskFrostSurface(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit) {
    val source = LocalJaskBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val context = LocalContext.current
    val cheap = remember(context) { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    val effect = remember(radius, cheap) {
        if (Build.VERSION.SDK_INT >= 31 && !cheap) {
            // Native GPU Gaussian, then a mild saturation lift (as Apple's materials do) so the
            // blurred colour reads as glass rather than grey and fine detail flickers less.
            val blur = android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP)
            val saturate = android.graphics.ColorMatrix().apply { setSaturation(1.5f) }
            android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(saturate)), blur,
            ).asComposeRenderEffect()
        } else null
    }
    val glass = source != null && effect != null
    // Blur a region larger than the surface by the blur radius, then clip: the edges always have
    // real content to sample, so artwork scrolling past no longer shimmers at the rim.
    val margin = (radius * 1.5f).toInt()
    val inRecording = LocalJaskInRecording.current
    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }.clip(shape)
        .background(when { glass -> Color(0xFF141312); inRecording -> Color(0x8C2C2A28); else -> Color(0xE62A2A2A) })) {
        if (glass) Canvas(Modifier.matchParentSize().layout { measurable, constraints ->
                val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(
                    constraints.maxWidth + 2 * margin, constraints.maxHeight + 2 * margin))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-margin, -margin) }
            }.graphicsLayer { renderEffect = effect }) {
            translate(source!!.origin.x - origin.x + margin, source.origin.y - origin.y + margin) { drawLayer(source.layer) }
        }
        // Glass: a thin neutral tint keeps the blurred artwork visible; the fallback stays opaque enough to read.
        if (glass || inRecording) Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .14f), Color.Black.copy(alpha = .28f)))))
        // Specular rim: bright along the top edge, fading out down the sides.
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .38f), Color.White.copy(alpha = .06f), Color.White.copy(alpha = .12f))), shape))
        content()
    }
}

/** Icon-only capsule (Apple-style tab bar); tab names remain as accessibility labels. */
@Composable
internal fun JaskFloatingNavigation(current: JaskDestination, onSelect: (JaskDestination) -> Unit, modifier: Modifier = Modifier) {
    JaskFrostSurface(modifier.testTag("jask.navigation").height(JaskRailHeight), RoundedCornerShape(50)) {
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
    filterIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Tune, filterLabel: String? = null,
    accountMenu: @Composable BoxScope.() -> Unit = {}) {
    Row(modifier.testTag("jask.header").height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        JaskWordmark(Modifier.height(20.dp))
        Spacer(Modifier.weight(1f))
        JaskFrostSurface(Modifier.size(48.dp), RoundedCornerShape(50)) {
            IconButton(onFilter, Modifier.fillMaxSize()) {
                Icon(filterIcon, filterLabel ?: stringResource(R.string.catalogue_filters), Modifier.size(20.dp), tint = Color(0xFFF0F0F0))
            }
        }
        Box {
            JaskFrostSurface(Modifier.size(48.dp), RoundedCornerShape(50)) {
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
    LaunchedEffect(searching) { if (searching) runCatching { focus.requestFocus(); keyboard?.show() } }
    Row(modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (searching) {
            JaskFrostSurface(Modifier.weight(1f).height(JaskRailHeight), RoundedCornerShape(50)) {
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
                        androidx.compose.foundation.text.BasicTextField(query, { onQuery(it.take(60)) },
                            Modifier.fillMaxWidth().testTag("jask.search").focusRequester(focus), singleLine = true, textStyle = style,
                            cursorBrush = SolidColor(Color(0xFFF5F5F5)),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide(); focusManager.clearFocus() }))
                    }
                }
            }
            JaskFrostSurface(Modifier.size(JaskRailHeight), RoundedCornerShape(50)) {
                IconButton(::close, Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_cancel), Modifier.size(22.dp), tint = Color(0xFFF5F5F5))
                }
            }
        } else {
            JaskFloatingNavigation(current, onSelect, Modifier.weight(1f))
            JaskFrostSurface(Modifier.size(JaskRailHeight), RoundedCornerShape(50)) {
                IconButton({ setSearching(true) }, Modifier.fillMaxSize().testTag("jask.searchButton"), enabled = searchEnabled) {
                    Icon(Icons.Default.Search, stringResource(R.string.nav_search), Modifier.size(24.dp),
                        tint = if (searchEnabled) Color(0xFFF5F5F5) else Color(0x55F5F5F5))
                }
            }
        }
    }
}
