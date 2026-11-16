/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

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

/*
 * D-pad focus, shown the same way everywhere:
 *  - [JaskIndication] (installed by JaskTheme) gives every clickable a white ring and a light
 *    wash while focused, on top of the touch ripple. Small controls (≤ 72dp on their short side:
 *    circles, pills, tabs) get a fully rounded ring; larger ones (cards, rows) a 12dp-cornered one.
 *  - [jaskFocusLift] scales an element up while it or a child has focus: posters and the white
 *    Play buttons, where a white ring alone would not show.
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
                    is FocusInteraction.Focus -> count++
                    is FocusInteraction.Unfocus -> count--
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
        val stroke = 3.dp.toPx()
        val short = minOf(size.width, size.height)
        val radius = if (short <= 72.dp.toPx()) short / 2 else 12.dp.toPx()
        drawRoundRect(Color.White.copy(alpha = .14f), cornerRadius = CornerRadius(radius))
        drawRoundRect(Color.White, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((radius - stroke / 2).coerceAtLeast(0f)), style = Stroke(stroke))
    }
}

/** Scales the element up while it (or something inside it) has focus. Put it before clip/background. */
internal fun Modifier.jaskFocusLift(scale: Float = 1.06f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focusLift")
    onFocusChanged { focused = it.hasFocus }.graphicsLayer { scaleX = s; scaleY = s }
}
