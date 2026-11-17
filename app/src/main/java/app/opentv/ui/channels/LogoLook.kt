package app.opentv.ui.channels

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import app.opentv.ui.JaskColor
import coil.size.Size
import coil.transform.Transformation
import java.util.concurrent.ConcurrentHashMap

/**
 * How a logo is presented on its tile, read from the logo itself: the tile takes the logo's colour,
 * so tiles are as varied as the channels they show.
 */
internal data class LogoLook(val top: Color, val bottom: Color, val light: Boolean, val aspect: Float) {
    companion object {
        val Neutral = LogoLook(Color(0xFF2E2B28), Color(0xFF211F1D), false, 1.6f)
        private val cache = ConcurrentHashMap<String, LogoLook>()
        fun cached(url: String?): LogoLook? = url?.let(cache::get)

        /** Reads [bitmap] (already trimmed) once per [url]. */
        fun of(url: String, bitmap: Bitmap): LogoLook = cache.getOrPut(url) { analyse(bitmap) }

        private fun analyse(bitmap: Bitmap): LogoLook {
            val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
            if (bitmap.config == Bitmap.Config.HARDWARE) return Neutral.copy(aspect = aspect)
            val step = maxOf(1, maxOf(bitmap.width, bitmap.height) / 48)
            val opaque = ArrayList<Int>()
            var total = 0
            for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) {
                total++
                val p = bitmap.getPixel(x, y)
                if (android.graphics.Color.alpha(p) >= 160) opaque += p
            }
            if (opaque.size < 12) return Neutral.copy(aspect = aspect)
            // A logo that fills its frame (a JPEG with its own background): the tile becomes that background.
            if (opaque.size >= total * 0.92f) {
                val edge = edgeColour(bitmap)
                val lch = JaskColor.toLch(edge)
                return LogoLook(Color(edge), Color(edge), lch.l > 0.7f, aspect)
            }
            val lums = opaque.map { JaskColor.toLch(it).l }
            val meanL = lums.average().toFloat()
            val vivid = opaque.map(JaskColor::toLch).filter { it.c > 0.06f && it.l in 0.2f..0.92f }
            val accent = if (vivid.size >= opaque.size * 0.12f) JaskColor.accent(opaque.toIntArray()) else null
            // Contrast decides the plate: a logo's own hue behind it only works when the logo is clearly brighter.
            val vividL = vivid.map { it.l }.average().toFloat().takeIf { !it.isNaN() } ?: meanL
            return when {
                // Dark or mid-tone logos (black wordmarks, navy badges): a light plate with a hint of their colour.
                meanL < 0.45f || (accent != null && vividL < 0.5f) ->
                    LogoLook(JaskColor.toColor(JaskColor.Lch(0.96f, (accent?.c ?: 0f) * 0.04f, accent?.h ?: 0f)),
                        JaskColor.toColor(JaskColor.Lch(0.90f, (accent?.c ?: 0f) * 0.06f, accent?.h ?: 0f)), true, aspect)
                // Bright, colourful logos sit on a deep, quieter shade of their own colour.
                accent != null -> LogoLook(JaskColor.toColor(JaskColor.Lch(0.26f, (accent.c * 0.5f).coerceAtMost(0.08f), accent.h)),
                    JaskColor.toColor(JaskColor.Lch(0.18f, (accent.c * 0.4f).coerceAtMost(0.06f), accent.h + 8f)), false, aspect)
                else -> Neutral.copy(aspect = aspect)
            }
        }

        private fun edgeColour(bitmap: Bitmap): Int {
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            val w = bitmap.width; val h = bitmap.height
            val step = maxOf(1, maxOf(w, h) / 40)
            fun add(x: Int, y: Int) { val p = bitmap.getPixel(x, y); r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); b += android.graphics.Color.blue(p); n++ }
            for (x in 0 until w step step) { add(x, 0); add(x, h - 1) }
            for (y in 0 until h step step) { add(0, y); add(w - 1, y) }
            return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        }
    }
}

/** Crops transparent margins so every logo fills its tile by its real shape, not its canvas. */
internal object TrimTransparent : Transformation {
    override val cacheKey = "jask-trim-transparent"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (!input.hasAlpha() || input.config == Bitmap.Config.HARDWARE) return input
        val w = input.width; val h = input.height
        var left = w; var top = h; var right = -1; var bottom = -1
        val row = IntArray(w)
        for (y in 0 until h) {
            input.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) if ((row[x] ushr 24) > 24) {
                if (x < left) left = x; if (x > right) right = x
                if (y < top) top = y; if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return input
        val pad = maxOf(1, (maxOf(right - left, bottom - top) * 0.02f).toInt())
        left = (left - pad).coerceAtLeast(0); top = (top - pad).coerceAtLeast(0)
        right = (right + pad).coerceAtMost(w - 1); bottom = (bottom + pad).coerceAtMost(h - 1)
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return input
        return Bitmap.createBitmap(input, left, top, right - left + 1, bottom - top + 1)
    }
}
