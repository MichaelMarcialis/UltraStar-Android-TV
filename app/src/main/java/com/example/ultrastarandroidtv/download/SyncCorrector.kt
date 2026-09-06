package com.example.ultrastarandroidtv.download

import android.content.Context
import android.util.Log
import com.example.ultrastarandroidtv.audio.ChromaScanner
import com.example.ultrastarandroidtv.song.SyncVerdict
import com.example.ultrastarandroidtv.song.UltraStarSongParser
import com.example.ultrastarandroidtv.song.checkSync
import com.example.ultrastarandroidtv.song.shiftGap

private const val TAG = "SyncCorrector"

/** A chart, as it should be written, and one line about what was done to it. */
data class SyncCorrection(val chart: String, val note: String? = null)

/**
 * Checks a freshly saved song against its own recording and puts the timing right.
 *
 * ## Why at download time
 *
 * A chart and a recording arrive from two different places — the chart from USDB, the audio from
 * whichever YouTube upload the chart's meta tags name — and nothing has ever checked that they
 * agree. Six of the hundred and twenty-three songs on this card were out of time, and every one
 * of them was found by somebody starting the song and noticing, which is the worst possible
 * moment: mid-performance, with a room watching.
 *
 * Now is the cheapest moment there is. The audio is already on the card, nobody is singing, and
 * the download has just spent half a minute waiting on USDB's throttle — a few seconds of
 * arithmetic against that is invisible.
 *
 * ## What it will and will not do
 *
 * It moves `#GAP` and nothing else, and only when the measurement is confident — see
 * [checkSync]. A chart that simply does not match its recording is left exactly as it came,
 * because no offset repairs that and an edited-but-still-wrong chart is worse than an honest one.
 * The note it returns is what the screen says afterwards; a correction happening silently would
 * be an app quietly editing somebody's files.
 */
fun interface SyncCorrector {
    /** The chart to write, given the document id of the audio saved beside it. */
    fun correct(audioId: String, chart: String): SyncCorrection

    companion object {
        /** Leaves every chart exactly as it is. The default, and what the tests use. */
        val NONE = SyncCorrector { _, chart -> SyncCorrection(chart) }
    }
}

/**
 * The real one: decodes the audio on the card and measures the chart against it.
 *
 * [uriOf] turns a document id into something [ChromaScanner] can open, which is the only part of
 * this that knows about the Storage Access Framework.
 */
class CardSyncCorrector(
    private val context: Context,
    private val uriOf: (String) -> String,
) : SyncCorrector {

    override fun correct(audioId: String, chart: String): SyncCorrection {
        val song = runCatching { UltraStarSongParser.parse(chart) }.getOrNull()
            ?: return SyncCorrection(chart)
        val profile = ChromaScanner.scan(context, uriOf(audioId))
            ?: return SyncCorrection(chart)

        return when (val verdict = checkSync(song, profile)) {
            is SyncVerdict.Shifted -> {
                val moved = shiftGap(chart, verdict.offsetSeconds)
                if (moved == null) {
                    SyncCorrection(chart)
                } else {
                    Log.i(TAG, "shifted ${song.metadata.title} by ${verdict.offsetSeconds}s")
                    SyncCorrection(moved, "Timing corrected by ${describe(verdict.offsetSeconds)}")
                }
            }

            is SyncVerdict.Mismatch -> {
                Log.i(TAG, "${song.metadata.title} does not match its audio (${verdict.confidence})")
                SyncCorrection(chart, "The notes do not match this recording")
            }

            SyncVerdict.Aligned, SyncVerdict.Unscoreable -> SyncCorrection(chart)
        }
    }

    /** "+0.5 s", the way somebody would say it. Milliseconds here would be false precision. */
    private fun describe(seconds: Double): String =
        (if (seconds >= 0) "+" else "−") + "%.1f s".format(kotlin.math.abs(seconds))
}
