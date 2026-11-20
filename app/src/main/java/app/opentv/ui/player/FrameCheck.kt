package app.opentv.ui.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Whether the video on screen is one flat colour. A provider's channel can be "on" and sending video that is pure
 * black frame after frame (ESPN 2 on 2026-11-20: 360 frames, every one the same brightness), which no check of the
 * stream's bytes can tell from a picture. A real picture, however dark, varies across the frame; checked on 64x36.
 */
internal object FrameCheck {
    /** True: flat. False: a picture. Null: couldn't look (no surface yet, or unsupported). */
    suspend fun flat(view: View?): Boolean? {
        val bitmap = when (view) {
            is TextureView -> view.takeIf { it.isAvailable }?.getBitmap(64, 36)
            is SurfaceView -> if (android.os.Build.VERSION.SDK_INT >= 24) copy(view) else null
            else -> null
        } ?: return null
        return flat(bitmap).also { bitmap.recycle() }
    }

    /** Whether [bitmap] (any size) is one flat colour. Doesn't recycle it. */
    fun flat(bitmap: Bitmap): Boolean {
        val small = if (bitmap.width == 64 && bitmap.height == 36) bitmap else Bitmap.createScaledBitmap(bitmap, 64, 36, true)
        val px = IntArray(64 * 36).also { small.getPixels(it, 0, 64, 0, 0, 64, 36) }
        if (small !== bitmap) small.recycle()
        val luma = DoubleArray(px.size) { i -> val c = px[i]; 0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255) }
        val mean = luma.average()
        val spread = kotlin.math.sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
        return spread < 2.0
    }

    @androidx.annotation.RequiresApi(24)
    private suspend fun copy(view: SurfaceView): Bitmap? {
        if (!view.holder.surface.isValid || view.width == 0) return null
        val bitmap = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888)
        return suspendCancellableCoroutine { cont ->
            runCatching {
                PixelCopy.request(view, bitmap, { result ->
                    if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bitmap else null.also { bitmap.recycle() })
                }, Handler(Looper.getMainLooper()))
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}
