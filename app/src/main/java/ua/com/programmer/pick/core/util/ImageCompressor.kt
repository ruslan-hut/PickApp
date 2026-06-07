package ua.com.programmer.pick.core.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downscales and JPEG-compresses captured photos to fit the server's per-line
 * upload cap. Pure, side-effect free — callers persist/upload the result.
 */
@Singleton
class ImageCompressor @Inject constructor() {

    companion object {
        private const val MAX_EDGE_PX = 1600
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val INITIAL_QUALITY = 80
        private const val MIN_QUALITY = 40
        private const val QUALITY_STEP = 10
    }

    /**
     * Decode [jpeg], apply [rotationDegrees], downscale so the longest edge is
     * at most 1600px, and JPEG-compress to at most 2 MB (stepping quality down
     * if needed). Returns null when the bytes can't be decoded.
     */
    fun compress(jpeg: ByteArray, rotationDegrees: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_EDGE_PX)
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOpts) ?: return null
        val scaled = decoded.scaledToMaxEdge(MAX_EDGE_PX)
        if (scaled !== decoded) decoded.recycle()
        val bitmap = scaled.rotated(rotationDegrees)
        if (bitmap !== scaled) scaled.recycle()

        var quality = INITIAL_QUALITY
        var out = bitmap.toJpeg(quality)
        while (out.size > MAX_BYTES && quality > MIN_QUALITY) {
            quality -= QUALITY_STEP
            out = bitmap.toJpeg(quality)
        }
        bitmap.recycle()
        return out
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    private fun Bitmap.scaledToMaxEdge(maxEdge: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return this
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees % 360 == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    // Halve until the sampled longest edge is within ~2x the target, keeping
    // peak decode memory low before the exact scale step.
    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}
