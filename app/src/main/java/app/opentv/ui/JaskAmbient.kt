/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The touch shell's page background: Conduit's "waves" scene (a slow field of crossing waves cut to
 * four tones by an 8×8 ordered dither), ported from conduit-web `chat-art-draw.ts` to AGSL. The four
 * inks come from the vivid hue of the current artwork ([JaskAmbientFrom]); the field fades to black
 * towards the bottom so lists below the fold sit on black. Black when no page names artwork.
 * API < 33 has no runtime shaders and stays black.
 */
private const val WAVES = """
uniform float2 view;
uniform float grain;
uniform float time;
uniform float fade;
layout(color) uniform half4 ink0;
layout(color) uniform half4 ink1;
layout(color) uniform half4 ink2;
layout(color) uniform half4 ink3;
float bayer2(float2 a) { a = floor(a); return fract(a.x / 2.0 + a.y * a.y * 0.75); }
float bayer8(float2 a) { return bayer2(0.25 * a) * 0.0625 + bayer2(0.5 * a) * 0.25 + bayer2(a); }
half4 main(float2 xy) {
    float2 cell = floor(xy / grain);
    float2 p = cell * grain / view.y;
    float2 q = p * 2.2 + 0.45 * float2(sin(p.y * 2.6 + time * 0.2), cos(p.x * 2.1 - time * 0.16));
    float field = sin(q.x * 1.9 + time * 0.12) + sin(q.y * 2.7 - time * 0.1) + sin((q.x + q.y) * 1.3 + time * 0.07)
        + sin(length(q - float2(1.6, 0.9)) * 3.1 - time * 0.17);
    float tone = min(3.0, floor(clamp(field * 0.125 + 0.5, 0.0, 1.0) * 3.0 + bayer8(cell)));
    half4 ink = tone < 0.5 ? ink0 : tone < 1.5 ? ink1 : tone < 2.5 ? ink2 : ink3;
    float down = xy.y / view.y;
    // Conduit's picture fade: dims towards the bottom by [fade], starting a quarter of the way down.
    return half4(ink.rgb * (1.0 - fade * smoothstep(0.25, 1.0, down)), 1.0);
}
"""

@Composable
internal fun Modifier.jaskWavesBackground(ambient: JaskAmbient): Modifier {
    val target = rememberArtworkAccent(ambient.art)
    val goal = target?.let(JaskColor::inks) ?: List(4) { Color.Black }
    val inks = goal.mapIndexed { i, c -> animateColorAsState(c, tween(300), label = "ink$i").value }
    // The flat colour the dither averages to, so heroes can fade into it before the dots begin.
    ambient.wash = wavesWash(inks)
    if (Build.VERSION.SDK_INT < 33 || remember { debugProp("debug.jasktv.nowaves") == "1" }) return drawBehind { drawRect(inks[1]) }
    // Phones dim the lower page under the bottom navigation, as Conduit does; wide screens have no
    // bottom bar, so the waves run nearly undimmed to the bottom edge.
    val fade = if (LocalLayoutClass.current == LayoutClass.PHONE) 0.6f else 0.2f
    val shader = remember { RuntimeShader(WAVES) }
    val grain = 2f // device pixels, as in Conduit
    var time by remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    // Still when the system has animations off, and on the SOLID material (the cheapest).
    val solid = LocalJaskMaterial.current == JaskMaterial.SOLID
    val still = remember(context, solid) {
        solid || debugProp("debug.jasktv.stillwaves") == "1" || android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    // ~30 updates a second is plenty for a field this slow; only the draw phase re-runs.
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (now - last > 33_000_000L) { last = now; time = (now / 1_000_000L % 10_000_000L) / 1000f }
        }
    }
    return drawBehind {
        shader.setFloatUniform("view", size.width, size.height)
        shader.setFloatUniform("grain", grain)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("fade", fade)
        inks.forEachIndexed { i, c -> shader.setColorUniform("ink$i", c.toArgb()) }
        drawRect(ShaderBrush(shader))
    }
}

/** Roughly the colour the dithered field reads as from a distance. */
private fun wavesWash(inks: List<Color>): Color {
    val w = floatArrayOf(.2f, .35f, .3f, .15f)
    return Color(inks.indices.sumOf { (inks[it].red * w[it]).toDouble() }.toFloat(),
        inks.indices.sumOf { (inks[it].green * w[it]).toDouble() }.toFloat(),
        inks.indices.sumOf { (inks[it].blue * w[it]).toDouble() }.toFloat())
}

/** The artwork's accent (see [JaskColor.accent]); null until decoded or when there is no art. */
@Composable
private fun rememberArtworkAccent(url: String?): JaskColor.Lch? {
    val context = LocalContext.current
    var target by remember { mutableStateOf<JaskColor.Lch?>(null) }
    LaunchedEffect(url) {
        if (url == null) { target = null; return@LaunchedEffect }
        target = artworkAccent(context, url) ?: return@LaunchedEffect
    }
    return target
}

private val accents = java.util.concurrent.ConcurrentHashMap<String, JaskColor.Lch>()

/** Decodes a small copy of [url] (Coil cache) and picks its accent; memoised per URL. */
internal suspend fun artworkAccent(context: android.content.Context, url: String): JaskColor.Lch? = accents[url] ?: run {
    val request = ImageRequest.Builder(context).data(url).size(64).allowHardware(false).build()
    val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
    withContext(Dispatchers.Default) { JaskColor.accent(JaskColor.sample(bitmap)) }.also { accents[url] = it }
}
