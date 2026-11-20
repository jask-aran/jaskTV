/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/*
 * D-pad focus, one model on every screen:
 *  - [JaskIndication] (installed by JaskTheme) marks every focused clickable with a white ring and a light
 *    wash, on top of the touch ripple, and plays the system navigation sound when focus lands there (so a
 *    press that goes nowhere stays silent, as on Android TV). Small controls (≤ 72dp on their short side)
 *    get a fully rounded ring, larger ones 12dp corners.
 *  - [jaskFocusLift] enlarges cards, posters, tiles and buttons while focused (as Netflix does), draws them
 *    above their neighbours, and records them with the page's [JaskFocusMemory] so the d-pad can come back
 *    to them: from the navigation, and after a page swaps its content (opening a category, going Back).
 *  - Focus lands on controls (buttons, cards, rows), never on full-bleed art or plain text.
 */

internal object JaskIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = JaskIndicationNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x6a61736b
}

private class JaskIndicationNode(private val source: InteractionSource) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init { delegate(ripple().create(source)) }

    override fun onAttach() {
        coroutineScope.launch {
            var count = 0
            source.interactions.collect {
                when (it) {
                    is FocusInteraction.Focus -> { count++; JaskSounds.focusLanded() }
                    is FocusInteraction.Unfocus -> count--
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> { JaskSounds.pressed(); return@collect }
                    else -> return@collect
                }
                val now = count > 0
                if (now != focused) { focused = now; invalidateDraw() }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val short = minOf(size.width, size.height)
        // Pills and round buttons get a pill ring; wide rows and cards get the 12dp card corner.
        val pill = short <= 56.dp.toPx() && size.width < size.height * 4.5f
        val radius = if (pill) short / 2 else 12.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = .10f), cornerRadius = CornerRadius(radius))
        // The ring sits inside the bounds, so clipped cards and rows show it too.
        drawJaskRing(radius)
    }
}

/**
 * Navigation sounds: a soft tick when d-pad focus lands somewhere and a warmer pop on select (res/raw/jask_nav,
 * jask_select, synthesised for jaskTV and kept quiet). Only for d-pad input ([MainActivity] reports each key), so
 * touch stays silent, and a press that goes nowhere makes no sound. Off when the system's sound effects are off.
 */
internal object JaskSounds {
    private var pool: android.media.SoundPool? = null
    private var resolver: android.content.ContentResolver? = null
    private var nav = 0
    private var select = 0
    @Volatile private var lastMoveAt = 0L
    @Volatile private var lastSelectAt = 0L

    fun attach(context: android.content.Context) {
        if (pool != null) return
        val ctx = context.applicationContext
        resolver = ctx.contentResolver
        pool = android.media.SoundPool.Builder().setMaxStreams(3).setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also {
            nav = it.load(ctx, app.opentv.R.raw.jask_nav, 1)
            select = it.load(ctx, app.opentv.R.raw.jask_select, 1)
        }
    }

    private fun enabled() = runCatching {
        android.provider.Settings.System.getInt(resolver, android.provider.Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
    }.getOrDefault(true)

    /** Called for every key the activity sees, before the UI handles it. */
    fun key(event: android.view.KeyEvent) {
        if (event.action != android.view.KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> lastMoveAt = event.eventTime
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A -> lastSelectAt = event.eventTime
        }
    }

    private fun play(id: Int) { if (id != 0 && enabled()) pool?.play(id, 1f, 1f, 1, 0, 1f) }

    fun focusLanded() {
        if (lastMoveAt != 0L && android.os.SystemClock.uptimeMillis() - lastMoveAt < 400) { lastMoveAt = 0; play(nav) }
    }

    fun pressed() {
        if (lastSelectAt != 0L && android.os.SystemClock.uptimeMillis() - lastSelectAt < 400) { lastSelectAt = 0; play(select) }
    }
}

/**
 * Where d-pad focus has been on one page, so it can come back: the most recently focused items first. Items
 * register while they're on screen ([jaskFocusLift]); [restore] focuses the most recent one still there.
 */
internal class JaskFocusMemory {
    companion object {
        private val tabs = HashMap<String, JaskFocusMemory>()
        /** One memory per tab for the life of the app (only stable keys, never stream data, are kept). */
        fun forTab(tab: String): JaskFocusMemory = tabs.getOrPut(tab) { JaskFocusMemory() }
        /** Tests start each case with no remembered places. */
        @androidx.annotation.VisibleForTesting fun forgetAll() = tabs.clear()
    }
    val hasHistory get() = recent.isNotEmpty()

    private val attached = LinkedHashMap<Any, androidx.compose.ui.focus.FocusRequester>()
    private val recent = ArrayDeque<Any>()

    fun attach(key: Any, requester: androidx.compose.ui.focus.FocusRequester) { attached[key] = requester }
    fun detach(key: Any, requester: androidx.compose.ui.focus.FocusRequester) { if (attached[key] === requester) attached.remove(key) }
    fun focused(key: Any) { recent.remove(key); recent.addFirst(key); while (recent.size > 64) recent.removeLast() }

    private val entries = ArrayList<androidx.compose.ui.focus.FocusRequester>()
    fun entry(requester: androidx.compose.ui.focus.FocusRequester, on: Boolean) { entries.remove(requester); if (on) entries.add(requester) }

    /**
     * Focuses the most recently focused item still on screen, else the page's declared entry ([jaskFocusEntry],
     * newest first: a category page that just opened); false when there's neither.
     */
    fun restore(): Boolean {
        for (key in recent) {
            val requester = attached[key] ?: continue
            if (runCatching { requester.requestFocus() }.isSuccess) return true
        }
        for (requester in entries.asReversed()) if (runCatching { requester.requestFocus() }.isSuccess) return true
        return false
    }
}

internal val LocalJaskFocusMemory = androidx.compose.runtime.staticCompositionLocalOf<JaskFocusMemory?> { null }

/**
 * Enlarges the element while it (or something inside it) has focus, above its neighbours, and remembers it for
 * the page ([JaskFocusMemory]). Pass a stable [key] (a channel id, a category name) so focus can find the same
 * item again after the page rebuilds it, e.g. coming Back from a category. Put it before clip/background.
 */
internal fun Modifier.jaskFocusLift(scale: Float = 1.08f, key: Any? = null): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    // The animated scale is read only while drawing (in the layer block), so the card isn't recomposed on every
    // frame of the animation; composition only sees whether a layer is needed, which flips twice per focus change.
    // Quick, not springy: on a d-pad the highlight has to arrive with the key press, not drift in after it.
    val lift = animateFloatAsState(if (focused) scale else 1f, androidx.compose.animation.core.tween(90), label = "focusLift")
    val lifted by remember { androidx.compose.runtime.derivedStateOf { lift.value != 1f } }
    val memory = LocalJaskFocusMemory.current
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    val id = key ?: requester
    if (memory != null) androidx.compose.runtime.DisposableEffect(memory, id) {
        memory.attach(id, requester)
        onDispose { memory.detach(id, requester) }
    }
    androidx.compose.ui.Modifier.then(if (focused) Modifier.zIndex(1f) else Modifier).focusRequester(requester)
        .onFocusChanged {
            if (it.hasFocus && !focused) JaskSounds.focusLanded()
            focused = it.hasFocus; if (it.hasFocus) { memory?.focused(id); JaskFocusReturn.last = java.lang.ref.WeakReference(requester) }
        }
        // A layer only while enlarged (or easing back): a layer on every card in a shelf costs draw time on a
        // Fire TV, and those idle layers weren't redrawn when a shelf scrolled sideways.
        .then(if (lifted) Modifier.graphicsLayer { val v = lift.value; scaleX = v; scaleY = v } else Modifier)
}

/** The last enlarged item to take focus: where a sheet or menu hands focus back when it closes. */
internal object JaskFocusReturn {
    var last: java.lang.ref.WeakReference<androidx.compose.ui.focus.FocusRequester>? = null

    /** Call in an overlay: remembers the item focused when it opened and refocuses it after the overlay closes. */
    @androidx.compose.runtime.Composable
    fun ReturnOnClose() {
        if (LocalLayoutClass.current != LayoutClass.TV) return
        val back = remember { last?.get() }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                if (back != null) android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { back.requestFocus() } }
            }
        }
    }
}

/**
 * The fill for white buttons (Play): a white ring can't show on white, so on TV the button is dimmed until it
 * has focus and turns full white with it, as Netflix does. Touch screens keep plain white.
 */
internal fun Modifier.jaskWhiteFill(shape: androidx.compose.ui.graphics.Shape): Modifier = composed {
    val tv = LocalLayoutClass.current == LayoutClass.TV
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }.background(
        // Grey glass, not solid white: the main action reads by its size and place, not by glaring over the picture.
        androidx.compose.ui.graphics.Color.White.copy(alpha = if (focused && tv) 0.34f else 0.2f), shape)
}

/** A row whose Left/Right stop at its ends instead of falling diagonally into the next row. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.jaskRowEnds(): Modifier = focusProperties {
    exit = { d ->
        if (d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right)
            androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default
    }
}.focusGroup()

/**
 * Mouse wheel and trackpad scrolling (DeX, Chromebooks): vertical scrolls go to this list even
 * when the pointer is over a sideways shelf or a card, which would otherwise swallow them.
 * Sideways scrolls are left to the shelf under the pointer.
 */
internal fun Modifier.jaskWheelScroll(state: ScrollableState): Modifier = composed {
    val context = LocalContext.current
    val factor = remember(context) {
        androidx.core.view.ViewConfigurationCompat.getScaledVerticalScrollFactor(android.view.ViewConfiguration.get(context), context)
    }
    pointerInput(state, factor) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val delta = event.changes.fold(Offset.Zero) { sum, change -> sum + change.scrollDelta }
                if (abs(delta.y) <= abs(delta.x)) continue
                state.dispatchRawDelta(delta.y * factor)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * A d-pad card (poster, channel tile, game tile): the whole tile is the target and enlarges ([jaskFocusLift]);
 * the ring goes around its picture ([jaskCardArt] with the same [source]) rather than around picture and caption.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.jaskCard(source: androidx.compose.foundation.interaction.MutableInteractionSource, key: Any? = null,
    scale: Float = 1.08f, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    jaskFocusLift(scale, key).then(
        if (onLongClick == null) Modifier.clickable(source, null, onClick = onClick)
        // Hold OK on a remote (or press and hold on a phone) for the card's second action.
        else Modifier.combinedClickable(source, null, onLongClick = onLongClick, onClick = onClick))

/** Draws the focus ring for a [jaskCard] around this element (its picture), [corner] matching the picture's shape. */
internal fun Modifier.jaskCardArt(source: InteractionSource, corner: androidx.compose.ui.unit.Dp = 10.dp): Modifier = composed {
    val focused by source.collectIsFocusedAsState()
    drawWithContent {
        drawContent()
        if (focused) drawJaskRing(corner.toPx())
    }
}

/** Full-bleed art that is tappable on touch screens; on TV it isn't a d-pad stop (its buttons are). */
internal fun Modifier.jaskTouchOnly(): Modifier = composed {
    if (LocalLayoutClass.current == LayoutClass.TV) Modifier.focusProperties { canFocus = false } else Modifier
}

/** Marks where the d-pad starts on this page when it has no remembered place (the first channel of a category). */
internal fun Modifier.jaskFocusEntry(): Modifier = composed {
    val memory = LocalJaskFocusMemory.current ?: return@composed Modifier
    val requester = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.DisposableEffect(memory) {
        memory.entry(requester, true)
        onDispose { memory.entry(requester, false) }
    }
    Modifier.focusRequester(requester)
}

/** The d-pad ring: 3dp white with a thin dark line inside it, so it reads on dark art and on white buttons alike. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawJaskRing(radius: Float) {
    val w = 3.dp.toPx()
    val d = 1.5.dp.toPx()
    drawRoundRect(Color.White, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius((radius - w / 2).coerceAtLeast(0f)), style = Stroke(w))
    drawRoundRect(Color.Black.copy(alpha = .55f), topLeft = Offset(w + d / 2, w + d / 2), size = Size(size.width - 2 * w - d, size.height - 2 * w - d),
        cornerRadius = CornerRadius((radius - w - d / 2).coerceAtLeast(0f)), style = Stroke(d))
}

/**
 * The d-pad ring and lift for controls that draw their own focus instead of using [JaskIndication] (Material
 * buttons): put it first on the button's modifier. [corner] null means fully rounded (pills).
 */
internal fun Modifier.jaskFocusRing(corner: androidx.compose.ui.unit.Dp? = null, scale: Float = 1.05f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    jaskFocusLift(scale).onFocusChanged { focused = it.hasFocus }.drawWithContent {
        drawContent()
        if (focused) drawJaskRing(corner?.toPx() ?: (minOf(size.width, size.height) / 2))
    }
}

/**
 * Focus for a sideways shelf on TV. Coming into the shelf from above or below lands on the card you last had in it
 * (while it's still on screen), else on its leftmost visible card; never on whichever card happens to sit nearest
 * the one you left. Put [jaskShelf] on the LazyRow and [jaskShelfItem] on each item.
 */
internal class JaskShelfFocus(private val state: androidx.compose.foundation.lazy.LazyListState) {
    val entry = androidx.compose.ui.focus.FocusRequester()
    /** The card last focused in this shelf. */
    var last by mutableStateOf<Int?>(null)
    /** Where focus enters: the last card if it's still on screen, else the first card that is mostly on screen. */
    val entryIndex by androidx.compose.runtime.derivedStateOf {
        val items = state.layoutInfo.visibleItemsInfo
        val start = state.layoutInfo.viewportStartOffset
        val end = state.layoutInfo.viewportEndOffset
        last?.takeIf { l -> items.any { it.index == l && it.offset >= start - it.size / 3 && it.offset + it.size <= end + it.size / 3 } }
            ?: (items.firstOrNull { it.offset >= start - it.size / 3 } ?: items.firstOrNull())?.index ?: 0
    }
}

@androidx.compose.runtime.Composable
internal fun rememberJaskShelfFocus(state: androidx.compose.foundation.lazy.LazyListState) = remember(state) { JaskShelfFocus(state) }

/** The shelf: entering goes to [JaskShelfFocus.entryIndex]; Left/Right stop at its ends. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.jaskShelf(focus: JaskShelfFocus): Modifier = focusProperties {
    enter = { focus.entry }
    exit = { d ->
        if (d == androidx.compose.ui.focus.FocusDirection.Left || d == androidx.compose.ui.focus.FocusDirection.Right)
            androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default
    }
}.focusGroup()

internal fun Modifier.jaskShelfItem(focus: JaskShelfFocus, index: Int): Modifier =
    (if (index == focus.entryIndex) focusRequester(focus.entry) else this)
        .onFocusChanged { if (it.hasFocus) focus.last = index }
