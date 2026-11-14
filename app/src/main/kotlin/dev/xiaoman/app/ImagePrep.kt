package dev.xiaoman.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Turns a picked photo into bytes small enough to travel inside a chat request.
 *
 * Phone photos are 3-5 MB and base64 adds another third, which is slow to upload and too big for
 * most endpoints. Downscaling to [MAX_EDGE] and re-encoding as JPEG keeps a page of handwriting
 * readable at 150-400 KB. The bitmap is subsampled while decoding so a 12 MP photo never allocates
 * the full-size buffer.
 */
object ImagePrep {
    const val MAX_EDGE = 1024
    const val QUALITY = 85
    const val MIME = "image/jpeg"

    /** Decodes, shrinks and re-encodes [uri]; null when the file is not a readable image. */
    fun load(context: Context, uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val scaled = scaleDown(decoded)
        try {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }.getOrNull()

    /** Largest power of two that still leaves the long edge above [MAX_EDGE]. */
    fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        while (max(width, height) / (size * 2) >= MAX_EDGE) size *= 2
        return size
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
