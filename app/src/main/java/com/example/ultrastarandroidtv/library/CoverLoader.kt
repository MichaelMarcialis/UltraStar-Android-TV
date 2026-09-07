package com.example.ultrastarandroidtv.library

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

private const val TAG = "CoverLoader"

/**
 * Loads song cover art off the card, downscaled.
 *
 * Community covers are routinely 1000×1000 or larger and there is one per song; decoding them at
 * full size would spend tens of megabytes to draw a thumbnail. `inSampleSize` lets the decoder
 * throw away the detail *while* reading, so the full image is never in memory at all.
 *
 * Deliberately no image-loading library. This is one format, from one place, at one size, and a
 * dependency here would be a durability risk for a machine that has to keep working in the
 * living room years from now.
 */
object CoverLoader {

    /**
     * Decodes cover bytes already in hand, for artwork that came off the network rather than the
     * card. Same downscaling, same tolerance of rubbish: a cover that will not decode is a
     * cosmetic problem.
     */
    fun decode(bytes: ByteArray, maxPixels: Int = 512): ImageBitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            var sample = 1
            while (longest / (sample * 2) >= maxPixels) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
        } catch (error: Exception) {
            Log.w(TAG, "could not decode a cover of ${bytes.size} bytes", error)
            null
        }
    }

    /** Decodes [uri] down to roughly [maxPixels] on its longest edge, or null if it will not load. */
    fun load(resolver: ContentResolver, uri: Uri, maxPixels: Int = 512): ImageBitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            // Powers of two only: anything else makes the decoder resample rather than skip.
            var sample = 1
            while (longest / (sample * 2) >= maxPixels) sample *= 2

            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
                ?.asImageBitmap()
        } catch (error: Exception) {
            // A missing or corrupt cover is a cosmetic problem, never a reason not to sing.
            Log.w(TAG, "could not load cover $uri", error)
            null
        }
    }
}
