/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Artwork colour in OKLCH (perceptual lightness, chroma, hue), used for the page tint and the
 * dithered background. Picking by vividness rather than area, and building the dark tones in a
 * perceptual space, avoids the muddy browns that averaging hues and then darkening produced.
 */
internal object JaskColor {
    data class Lch(val l: Float, val c: Float, val h: Float)

    private fun lin(c: Int): Float { val v = c / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
    private fun gam(v: Float): Float { val x = v.coerceIn(0f, 1f); return if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1f / 2.4f) - 0.055f }

    fun toLch(argb: Int): Lch {
        val r = lin(argb shr 16 and 255); val g = lin(argb shr 8 and 255); val b = lin(argb and 255)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        val L = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        val A = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        val B = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        val h = Math.toDegrees(atan2(B, A).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
        return Lch(L, hypot(A, B), h)
    }

    /** OKLCH → sRGB, reducing chroma until the colour is inside the sRGB gamut. */
    fun toColor(lch: Lch): Color {
        var c = lch.c
        repeat(24) {
            val a = c * cos(Math.toRadians(lch.h.toDouble())).toFloat(); val b = c * sin(Math.toRadians(lch.h.toDouble())).toFloat()
            val l = (lch.l + 0.3963377774f * a + 0.2158037573f * b).pow(3)
            val m = (lch.l - 0.1055613458f * a - 0.0638541728f * b).pow(3)
            val s = (lch.l - 0.0894841775f * a - 1.2914855480f * b).pow(3)
            val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
            val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
            val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
            val e = 0.002f
            if (r in -e..1 + e && g in -e..1 + e && bl in -e..1 + e) return Color(gam(r), gam(g), gam(bl))
            c *= 0.9f
        }
        return Color(gam(lch.l), gam(lch.l), gam(lch.l))
    }

    /**
     * The artwork's most characterful colour. Pixels are grouped by hue (15° bins); a bin scores by
     * the sum of chroma² over its pixels, so a vivid red sail beats a large dull sky. The orange/brown
     * band (skin, sand, wood, smoke) is down-weighted unless it is genuinely vivid. Near-greys
     * give a cool neutral blue. Returns a mid-light, saturated version of that colour.
     */
    fun accent(pixels: IntArray): Lch {
        val bins = Array(24) { FloatArray(4) } // score, Σc·w, Σl·w, Σw
        for (p in pixels) {
            val (l, c, h) = toLch(p)
            if (l < 0.18f || l > 0.96f || c < 0.035f) continue
            val bin = bins[(h / 15f).toInt().coerceIn(0, 23)]
            val w = c * c * (1f - kotlin.math.abs(l - 0.6f))
            bin[0] += w; bin[1] += c * w; bin[2] += l * w; bin[3] += w
        }
        var best = -1; var bestScore = 0f
        for (i in bins.indices) {
            val b = bins[i]; if (b[3] <= 0f) continue
            val meanC = b[1] / b[3]
            val hue = i * 15f + 7.5f
            // Orange, brown and yellow band (OKLCH ~30°–112°): only wins when clearly vivid.
            val warm = hue in 30f..112f && meanC < 0.13f
            val score = b[0] * (if (warm) 0.3f else 1f)
            if (score > bestScore) { bestScore = score; best = i }
        }
        if (best < 0 || bestScore < 0.05f) return Lch(0.62f, 0.09f, 255f)
        // Orange/brown darkens to brown: prefer a clearly present non-warm colour (teal sea, blue sky).
        fun warm(i: Int) = (i * 15f + 7.5f) in 30f..112f
        if (warm(best)) bins.indices.filter { !warm(it) }.maxByOrNull { bins[it][0] }
            ?.takeIf { bins[it][0] >= bestScore * 0.4f }?.let { best = it; bestScore = bins[it][0] }
        val b = bins[best]
        // Hue: chroma-weighted circular mean inside the winning bin and its neighbours.
        var x = 0f; var y = 0f
        for (d in -1..1) {
            val i = (best + d + 24) % 24; val w = bins[i][0]
            val hr = Math.toRadians((i * 15f + 7.5f).toDouble())
            x += w * cos(hr).toFloat(); y += w * sin(hr).toFloat()
        }
        val hue = Math.toDegrees(atan2(y, x).toDouble()).toFloat().let { if (it < 0) it + 360f else it }
        return Lch(0.68f, (b[1] / b[3] * 1.15f).coerceIn(0.10f, 0.22f), hue)
    }

    /** Dark page tint of the accent (media pages): rich, not muddy. */
    fun tint(a: Lch): Color = toColor(Lch(0.30f, (a.c * 0.55f).coerceAtMost(0.10f), a.h))

    /** Four dither inks: near-black → light, chroma kept up in the darks so they read as colour. */
    fun inks(a: Lch): List<Color> = listOf(
        toColor(Lch(0.12f, a.c * 0.35f, a.h)),
        toColor(Lch(0.24f, a.c * 0.65f, a.h)),
        toColor(Lch(0.38f, a.c * 0.85f, a.h + 6f)),
        toColor(Lch(0.58f, a.c * 0.80f, a.h + 14f)),
    )

    /** Samples a bitmap down to at most ~1500 pixels. */
    fun sample(bitmap: android.graphics.Bitmap): IntArray {
        val step = maxOf(1, sqrt((bitmap.width * bitmap.height) / 1500f).toInt())
        val out = ArrayList<Int>()
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) out += bitmap.getPixel(x, y)
        return out.toIntArray()
    }
}
