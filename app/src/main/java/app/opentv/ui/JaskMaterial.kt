/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.opentv.core.AppSettings
import app.opentv.core.get
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * jaskTV materials
 * ================
 * Every glass element ([JaskGlassSurface] and the controls built on it) draws what is behind it
 * from the nearest [JaskBackdrop]:
 *
 *  - [JaskLiveBackdrop]: a page that changes (scrolling content, the animated waves). Recorded by
 *    [JaskGlassHost] and blurred every frame on the GPU, at quarter resolution. LIVE material only.
 *  - [JaskArtBackdrop]: a still image (hero art, a poster). Glass draws its slice of a tiny,
 *    pre-blurred copy of the image, which looks the same as a live blur of a picture that doesn't
 *    move and costs one small texture draw. Used by every material except SOLID.
 *  - none: a tinted translucent fill (taking the page's ambient colour where there is one).
 *
 * The material is chosen per device ([rememberJaskMaterial]) and can be overridden in Settings.
 */

/** How glass is drawn on this device; see the file comment. */
internal enum class JaskMaterial {
    /** Live blur of the page under chrome; pre-blurred art under controls on artwork. */
    LIVE,
    /** No live blur: tinted chrome, pre-blurred art under controls on artwork. */
    FROSTED,
    /** No blur at all and a still background: the cheapest, for weak TV sticks and low-RAM devices. */
    SOLID,
}

internal val LocalJaskMaterial = staticCompositionLocalOf { JaskMaterial.FROSTED }

/**
 * Debug-build A/B switches, read at start: `adb shell setprop debug.jasktv.material live|frosted|solid`
 * overrides the setting; `debug.jasktv.noblur 1` disables live blur.
 */
internal fun debugProp(name: String): String? = if (!app.opentv.BuildConfig.DEBUG) null else runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
}.getOrNull()?.takeIf { it.isNotEmpty() }

private fun debugNoBlur(): Boolean = debugProp("debug.jasktv.noblur") == "1"

@Composable
internal fun rememberJaskMaterial(isTelevision: Boolean): JaskMaterial {
    val context = LocalContext.current
    val mode by remember(context) { AppSettings.get(context).glassMode }.collectAsState()
    return remember(mode, isTelevision) { resolveMaterial(context, mode, isTelevision) }
}

/** "auto": live blur on capable phones and tablets, frosted on TVs, solid on low-RAM devices. */
internal fun resolveMaterial(context: Context, mode: String, isTelevision: Boolean): JaskMaterial {
    val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    val canLive = Build.VERSION.SDK_INT >= 31 && !lowRam && !debugNoBlur()
    return when (debugProp("debug.jasktv.material") ?: mode) {
        "live" -> if (canLive) JaskMaterial.LIVE else JaskMaterial.FROSTED
        "frosted" -> JaskMaterial.FROSTED
        "solid" -> JaskMaterial.SOLID
        else -> when {
            lowRam -> JaskMaterial.SOLID
            isTelevision || !canLive -> JaskMaterial.FROSTED
            else -> JaskMaterial.LIVE
        }
    }
}

// ---- Backdrops -----------------------------------------------------------------------------------

/** What glass in this part of the tree sees through. */
internal sealed interface JaskBackdrop

/**
 * A changing page. [JaskGlassHost] records it once per frame, then records [blurred]: the page at
 * 1/[down] size with the blur and saturation applied. Android renders a layer with an effect once
 * per frame however many places draw it, so every glass element just draws its slice of [blurred]:
 * one blur per page instead of one blur pipeline per element.
 */
internal class JaskLiveBackdrop(val blurred: GraphicsLayer, val down: Float) : JaskBackdrop {
    var origin by mutableStateOf(Offset.Zero)
}

/** The live-glass effect: a wide Gaussian, then a mild saturation lift (as Apple's materials do). */
internal fun jaskLiveEffect(radiusPx: Float) = android.graphics.RenderEffect.createChainEffect(
    android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(
        android.graphics.ColorMatrix().apply { setSaturation(1.5f) })),
    android.graphics.RenderEffect.createBlurEffect(radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP),
).asComposeRenderEffect()

/**
 * A still image. [coordinates] are the image's bounds (set by [jaskArtSource]); [frost] is its
 * pre-blurred copy, drawn [bleedPx] larger on every side so glass overhanging the edge still
 * shows the image's colour. [scrim] repeats any gradients drawn over the image, in its own space.
 */
internal class JaskArtBackdrop(frost: ImageBitmap?, val bleedPx: Float, scrim: (DrawScope.(Size) -> Unit)?) : JaskBackdrop {
    var frost by mutableStateOf(frost)
    var scrim by mutableStateOf(scrim)
    var coordinates: LayoutCoordinates? = null
}

internal val LocalJaskBackdrop = staticCompositionLocalOf<JaskBackdrop?> { null }

/** True inside a glass host's recorded page: glass there can't sample the page it is part of. */
internal val LocalJaskInRecording = staticCompositionLocalOf { false }

/** Marks this element's bounds as the image [backdrop] stands for. */
internal fun Modifier.jaskArtSource(backdrop: JaskArtBackdrop?): Modifier =
    if (backdrop == null) this else onPlaced { backdrop.coordinates = it }

/**
 * A [JaskArtBackdrop] for [url], or null on SOLID. [cells] is the width of the blurred copy in
 * pixels; about one cell per half blur radius looks identical to a live blur (a 120dp poster with a
 * 10dp blur: 24 cells).
 */
@Composable
internal fun rememberArtBackdrop(url: String?, cells: Int, bleed: Dp = 0.dp, scrim: (DrawScope.(Size) -> Unit)? = null): JaskArtBackdrop? {
    if (url == null || LocalJaskMaterial.current == JaskMaterial.SOLID) return null
    val context = LocalContext.current
    val bleedPx = with(LocalDensity.current) { bleed.toPx() }
    val backdrop = remember(url, cells, bleedPx) { JaskArtBackdrop(frostCache.get(frostKey(url, cells)), bleedPx, scrim) }
    if (backdrop.frost == null) LaunchedEffect(backdrop) { backdrop.frost = loadFrost(context, url, cells) }
    return backdrop
}

private fun frostKey(url: String, cells: Int) = "$url#$cells"
private val frostCache = android.util.LruCache<String, ImageBitmap>(400)

/** Decodes [url] small (from Coil's caches), scales it to [cells] wide and box-blurs it. */
private suspend fun loadFrost(context: Context, url: String, cells: Int): ImageBitmap? {
    val request = ImageRequest.Builder(context).data(url).size(cells * 3).allowHardware(false).build()
    val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
    return withContext(Dispatchers.Default) {
        val w = cells.coerceAtLeast(4)
        val h = (w * bitmap.height / bitmap.width.toFloat()).toInt().coerceAtLeast(4)
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        repeat(2) { boxBlur(px, w, h) }
        val out = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.prepareToDraw()
        out.asImageBitmap().also { frostCache.put(frostKey(url, cells), it) }
    }
}

/** One 3×3 box-blur pass, edges clamped. */
private fun boxBlur(px: IntArray, w: Int, h: Int) {
    val src = px.copyOf()
    for (y in 0 until h) for (x in 0 until w) {
        var a = 0; var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val c = src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
            a += c ushr 24; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
        }
        px[y * w + x] = (a / 9 shl 24) or (r / 9 shl 16) or (g / 9 shl 8) or (b / 9)
    }
}

// ---- The glass surface ---------------------------------------------------------------------------

/** Tint over the blurred backdrop. CHROME: bars, buttons, sheets. BADGE: small marks on posters. */
internal enum class JaskGlassTone { CHROME, BADGE }

/**
 * The one glass primitive. Draws the nearest [JaskBackdrop] (live blur, pre-blurred art, or a
 * tint), then a top-lit sheen and a specular rim, then [content]. All of it is drawn by modifiers
 * on a single box (no extra layout nodes), so a screen of badged posters stays cheap to build.
 */
@Composable
internal fun JaskGlassSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(24.dp),
    tone: JaskGlassTone = JaskGlassTone.CHROME, content: @Composable BoxScope.() -> Unit) {
    val backdrop = LocalJaskBackdrop.current
    val material = LocalJaskMaterial.current
    val inRecording = LocalJaskInRecording.current
    val wash = LocalJaskAmbient.current?.wash ?: Color.Black
    val badge = tone == JaskGlassTone.BADGE
    // Badges: shadow and clip share one layer.
    val clipped = if (badge) modifier.shadow(3.dp, shape, clip = true) else modifier.clip(shape)
    val fill = fallback(badge, inRecording, wash)
    val behind = when {
        backdrop is JaskLiveBackdrop && material == JaskMaterial.LIVE -> Modifier.liveFrost(backdrop)
        backdrop is JaskArtBackdrop -> Modifier.artFrost(backdrop, fill)
        else -> Modifier.background(fill)
    }
    Box(clipped.then(behind).background(if (badge) BadgeSheen else ChromeSheen).border(1.dp, if (badge) BadgeRim else ChromeRim, shape),
        content = content)
}

private val ChromeSheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = .14f), Color.Black.copy(alpha = .28f)))
private val BadgeSheen = Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.Black.copy(alpha = .38f)))
private val ChromeRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = .38f), Color.White.copy(alpha = .06f), Color.White.copy(alpha = .12f)))
private val BadgeRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = .45f), Color.White.copy(alpha = .08f)))

/** Fill when there is nothing to blur: tinted with the page colour, translucent inside a page. */
private fun fallback(badge: Boolean, inRecording: Boolean, wash: Color): Color = when {
    badge -> Color.Black.copy(alpha = .45f)
    inRecording -> Color(0x8C2C2A28)
    wash == Color.Black -> Color(0xE62A2A2A)
    else -> lerp(wash, Color(0xFF141312), .45f).copy(alpha = .92f)
}

/** This element's slice of the art's pre-blurred copy (and its scrims), aligned to the art. */
private fun Modifier.artFrost(backdrop: JaskArtBackdrop, fallback: Color): Modifier {
    var self: LayoutCoordinates? = null
    return onPlaced { self = it }.drawBehind {
        val frost = backdrop.frost
        val art = backdrop.coordinates
        val me = self
        if (frost == null || art == null || me == null || !art.isAttached || !me.isAttached) { drawRect(fallback); return@drawBehind }
        val at = art.localPositionOf(me, Offset.Zero)
        val b = backdrop.bleedPx
        translate(-at.x, -at.y) {
            drawImage(frost, dstOffset = IntOffset(-b.toInt(), -b.toInt()),
                dstSize = IntSize((art.size.width + 2 * b).toInt(), (art.size.height + 2 * b).toInt()), filterQuality = FilterQuality.Low)
            backdrop.scrim?.invoke(this, Size(art.size.width.toFloat(), art.size.height.toFloat()))
        }
    }
}

/** This element's slice of the page's shared blurred copy (see [JaskLiveBackdrop]). */
@Composable
private fun Modifier.liveFrost(source: JaskLiveBackdrop): Modifier {
    var origin by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { origin = it.positionInRoot() }.drawBehind {
        drawRect(Color(0xFF141312))
        translate(source.origin.x - origin.x, source.origin.y - origin.y) {
            scale(source.down, pivot = Offset.Zero) { drawLayer(source.blurred) }
        }
    }
}
