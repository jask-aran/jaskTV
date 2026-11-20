package app.opentv.ui

import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy

/**
 * D-pad Up/Down on a TV page built as one lazy grid (Movies, Shows): moves by slot, not by screen geometry.
 *
 * Compose's own focus search picks the nearest focusable rectangle in the pressed direction, so a card with a
 * two-line title, a row still scrolling into place or a row not drawn yet changes where Down lands. Here every
 * focusable grid item is a slot registered under its lazy key ([jaskSlot]); Down goes to the next grid row that has
 * a slot, Up to the previous one. In a row of cards the column is kept; a full-width item (a shelf, the hero) is
 * entered as a whole and decides where focus lands inside it. Rows not drawn yet are scrolled in first. Up from the
 * first slot on the page is left alone, so it reaches the header as before.
 */
internal class JaskSlots(private val grid: LazyGridState, private val scope: CoroutineScope) {
    private val requesters = HashMap<Any, FocusRequester>()
    private var pending: kotlinx.coroutines.Job? = null
    /** The lazy key of the slot holding focus. */
    var focused: Any? = null

    fun register(key: Any, requester: FocusRequester) { requesters[key] = requester }
    fun unregister(key: Any, requester: FocusRequester) { if (requesters[key] === requester) requesters.remove(key) }

    /** Focuses the slot for [key] if it is drawn. */
    fun request(key: Any): Boolean = requesters[key]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false

    /** Handles Down (+1) or Up (-1). False: not ours to handle (let focus move as usual). */
    fun move(dir: Int): Boolean {
        val from = focused ?: return false
        val items = grid.layoutInfo.visibleItemsInfo
        val cur = items.firstOrNull { it.key == from } ?: return false
        target(items, cur, dir)?.let { t -> if (request(t.key)) return true }
        // Nothing drawn in that direction yet: scroll it in, then try again. Up at the very top: the header's.
        val more = if (dir > 0) (items.lastOrNull()?.index ?: 0) < grid.layoutInfo.totalItemsCount - 1
            else grid.firstVisibleItemIndex > 0 || grid.firstVisibleItemScrollOffset > 0
        if (!more) return dir > 0 // Down at the very end: stay put (consume); Up at the top: pass on
        val row = cur.row
        val column = cur.column
        // One smooth motion: start scrolling that way, and the moment the next row is drawn, focus it; the page's
        // own scroll-to-focus animation then carries on from where this one is (no instant half-screen jump).
        pending?.cancel()
        pending = scope.launch {
            val viewport = grid.layoutInfo.viewportSize.height.toFloat()
            val anim = launch {
                grid.animateScrollBy(dir * viewport * 0.6f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.LinearEasing))
            }
            repeat(40) {
                androidx.compose.runtime.withFrameNanos { }
                val now = grid.layoutInfo.visibleItemsInfo
                val anchor = now.firstOrNull { it.key == from }
                val t = if (anchor != null) target(now, anchor, dir) else
                    now.filter { requesters.containsKey(it.key) && if (dir > 0) it.row > row else it.row < row }
                        .let { c -> if (dir > 0) c.minByOrNull { it.row } else c.maxByOrNull { it.row } }
                        ?.let { pick(now.filter { n -> n.row == it.row && requesters.containsKey(n.key) }, column) }
                if (t != null) { anim.cancel(); if (request(t.key)) return@launch }
                if (!anim.isActive && it > 20) return@launch
            }
        }
        return true
    }

    private fun target(items: List<LazyGridItemInfo>, cur: LazyGridItemInfo, dir: Int): LazyGridItemInfo? {
        val slots = items.filter { requesters.containsKey(it.key) }
        val rows = slots.map { it.row }.distinct().sorted()
        val next = if (dir > 0) rows.firstOrNull { it > cur.row } else rows.lastOrNull { it < cur.row }
        return next?.let { r -> pick(slots.filter { it.row == r }, cur.column) }
    }

    /** In a row of cards, the one in [column] (or the last, if the row is shorter); a full-width row has one. */
    private fun pick(row: List<LazyGridItemInfo>, column: Int): LazyGridItemInfo? =
        row.firstOrNull { it.column == column } ?: row.filter { it.column < column }.maxByOrNull { it.column } ?: row.minByOrNull { it.column }
}

internal val LocalJaskSlots = staticCompositionLocalOf<JaskSlots?> { null }

/** The grid side: TV only. Put on the LazyVerticalGrid. */
internal fun Modifier.jaskSlotKeys(slots: JaskSlots?): Modifier = if (slots == null) this else onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.DirectionDown -> slots.move(+1)
        Key.DirectionUp -> slots.move(-1)
        else -> false
    }
}

/**
 * Registers this grid item as a slot under its lazy [key] (the same key given to `item`/`items`). Requesting it
 * enters it as a focus group, so put this on the item's outer focus group (a shelf's [jaskShelf] row, a card).
 */
@Composable
internal fun Modifier.jaskSlot(key: Any): Modifier {
    val slots = LocalJaskSlots.current ?: return this
    val requester = remember { FocusRequester() }
    DisposableEffect(slots, key) { slots.register(key, requester); onDispose { slots.unregister(key, requester) } }
    return focusRequester(requester).onFocusChanged { if (it.hasFocus) slots.focused = key }
}
