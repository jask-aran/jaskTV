/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import javax.imageio.ImageIO

/** Same waves dither as the phone (`JaskAmbient.kt`), in SkSL for Compose Desktop's Skia. */
private const val WAVES = """
uniform float2 view;
uniform float grain;
uniform float time;
uniform float fade;
uniform half4 ink0;
uniform half4 ink1;
uniform half4 ink2;
uniform half4 ink3;
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
    return half4(ink.rgb * (1.0 - fade * smoothstep(0.25, 1.0, down)), 1.0);
}
"""

private val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(WAVES) }.onFailure { it.printStackTrace() }.getOrNull() }

/** Dithered waves in the vivid hue of [art] (TMDB image URL); black until one is decoded. */
@Composable
fun Modifier.wavesBackground(art: String?): Modifier {
    var target by remember { mutableStateOf(Color.Transparent) }
    LaunchedEffect(art) {
        if (art == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { accentOf(art) }?.let { target = it }
    }
    val accent by animateColorAsState(target, tween(300), label = "accent")
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now -> if (now - last > 33_000_000L) { last = now; time = (now / 1_000_000L % 10_000_000L) / 1000f } }
    }
    val fx = effect ?: return drawBehind { drawRect(Color.Black) }
    return drawBehind {
        val builder = RuntimeShaderBuilder(fx)
        builder.uniform("view", size.width, size.height)
        builder.uniform("grain", 2f)
        builder.uniform("time", time)
        builder.uniform("fade", 0.6f)
        inks(accent).forEachIndexed { i, c -> builder.uniform("ink$i", c.red, c.green, c.blue, 1f) }
        drawRect(ShaderBrush(builder.makeShader()))
    }
}

private fun inks(accent: Color): List<Color> {
    if (accent.alpha < 0.01f) return List(4) { Color.Black }
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB((accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt(), hsv)
    fun tone(dh: Float, s: Float, v: Float): Color {
        val rgb = java.awt.Color.HSBtoRGB(((hsv[0] * 360f + dh + 360f) % 360f) / 360f, s.coerceIn(0f, 1f), v)
        return lerp(Color.Black, Color(rgb or (0xFF shl 24)), accent.alpha)
    }
    val s = hsv[1]
    return listOf(tone(0f, s, 0.03f), tone(-6f, s, 0.17f), tone(4f, s * 0.95f, 0.36f), tone(14f, s * 0.7f, 0.62f))
}

private val accents = java.util.concurrent.ConcurrentHashMap<String, Color>()

/** The art's most vivid hue (saturation² × brightness weighted), from a small TMDB rendition. */
private fun accentOf(url: String): Color? = accents[url] ?: runCatching {
    val small = url.replace(Regex("/t/p/[a-z0-9]+/"), "/t/p/w185/")
    val bytes = DesktopGraph.http.newCall(Request.Builder().url(small).build()).execute().use { it.body?.bytes() } ?: return null
    val img = ImageIO.read(bytes.inputStream()) ?: return null
    val buckets = Array(18) { FloatArray(4) }
    val hsv = FloatArray(3)
    val sx = maxOf(1, img.width / 32); val sy = maxOf(1, img.height / 32)
    for (y in 0 until img.height step sy) for (x in 0 until img.width step sx) {
        val c = img.getRGB(x, y); val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        java.awt.Color.RGBtoHSB(r, g, b, hsv)
        if (hsv[2] < 0.15f || hsv[1] < 0.12f) continue
        val w = hsv[1] * hsv[1] * hsv[2]
        val k = buckets[(hsv[0] * 18).toInt().coerceIn(0, 17)]
        k[0] += w; k[1] += w * r; k[2] += w * g; k[3] += w * b
    }
    val best = buckets.maxByOrNull { it[0] }?.takeIf { it[0] > 0.4f } ?: return Color(0xFF3A5A8C)
    java.awt.Color.RGBtoHSB((best[1] / best[0]).toInt(), (best[2] / best[0]).toInt(), (best[3] / best[0]).toInt(), hsv)
    Color(java.awt.Color.HSBtoRGB(hsv[0], hsv[1].coerceIn(0.55f, 0.9f), 1f) or (0xFF shl 24))
}.getOrNull()?.also { accents[url] = it }
