package com.example.ultrastarandroidtv.pitch

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Fundamental-frequency estimator using the YIN algorithm.
 *
 * Implements steps 1–5 of de Cheveigné & Kawahara, *"YIN, a fundamental frequency estimator
 * for speech and music"* (JASA 111(4), 2002): the squared difference function, cumulative
 * mean normalisation, the absolute threshold, and parabolic interpolation. Written from the
 * published method, so nothing here is derived from another implementation and the app
 * carries no third-party audio licence.
 *
 * The threshold step is the part worth understanding. The difference function dips just as
 * deeply at *twice* the true period as at the period itself — a waveform correlates with
 * itself perfectly well when shifted by two cycles. So picking the deepest dip lands an
 * octave low about as often as not. Taking the *first* dip that clears a threshold is what
 * makes YIN octave-stable, and it's why [threshold] matters more than it looks.
 *
 * Allocation-free after construction, and never writes to the array handed to [detect].
 *
 * @param minHz,[maxHz] the range to search. Narrowing this is not just a filter on the
 *   answer — it bounds the lags actually examined, which is most of the cost.
 * @param threshold dip depth counting as periodic, on the normalised scale where 0 is a
 *   perfect match. Lower is stricter; too high starts picking short lags, i.e. octave-*up*
 *   errors.
 */
internal class Yin(
    private val sampleRate: Int,
    private val windowSize: Int,
    minHz: Float,
    maxHz: Float,
    private val threshold: Float = 0.20f,
) {
    init {
        require(windowSize >= 4 && windowSize % 2 == 0) {
            "windowSize must be even and >= 4, was $windowSize"
        }
        require(minHz > 0f && maxHz > minHz) { "need 0 < minHz < maxHz, got $minHz..$maxHz" }
    }

    /** Lags are compared over half the buffer so `j + tau` always stays in bounds. */
    private val sumWindow = windowSize / 2

    private val minLag = max(2, floor(sampleRate / maxHz).toInt())
    private val maxLag = min(sumWindow - 1, ceil(sampleRate / minHz).toInt())

    /**
     * Difference function, then normalised in place to the cumulative mean normalised
     * difference. Indexed by lag.
     */
    private val cmnd = FloatArray(maxLag + 1)

    init {
        require(minLag < maxLag) {
            "$minHz..$maxHz Hz collapses to lags $minLag..$maxLag at ${sampleRate}Hz — " +
                "widen the range or enlarge the window"
        }
    }

    /** Valid after [detect] returns true. */
    var frequencyHz: Float = 0f
        private set

    /** Confidence in [frequencyHz], 0..1. Valid after [detect] returns true. */
    var probability: Float = 0f
        private set

    /**
     * Estimates the fundamental of the first [windowSize] samples of [samples], returning
     * false if nothing in the searched range was periodic enough to commit to.
     */
    fun detect(samples: FloatArray): Boolean {
        require(samples.size >= windowSize) {
            "need at least $windowSize samples, got ${samples.size}"
        }

        difference(samples)
        cumulativeMeanNormalise()

        val lag = firstDipBelowThreshold()
        if (lag < 0) {
            frequencyHz = 0f
            probability = 0f
            return false
        }

        frequencyHz = sampleRate / refineLag(lag)
        probability = (1f - cmnd[lag]).coerceIn(0f, 1f)
        return true
    }

    /** Step 2: squared difference between the window and itself shifted by each lag. */
    private fun difference(x: FloatArray) {
        for (tau in 1..maxLag) {
            var sum = 0f
            for (j in 0 until sumWindow) {
                val delta = x[j] - x[j + tau]
                sum += delta * delta
            }
            cmnd[tau] = sum
        }
    }

    /**
     * Step 3: divide each lag by the running mean of all lags up to it. Without this the
     * difference function trends upward with lag and has no meaningful absolute scale, so no
     * fixed threshold could work.
     *
     * Every lag from 1 up contributes to the running mean, including those below [minLag] —
     * skipping them to save work would silently shift the scale the threshold is judged on.
     */
    private fun cumulativeMeanNormalise() {
        cmnd[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxLag) {
            runningSum += cmnd[tau]
            // Digital silence sums to zero; report "no dip" rather than dividing by it.
            cmnd[tau] = if (runningSum <= 0f) 1f else cmnd[tau] * tau / runningSum
        }
    }

    /** Step 4: the first dip under [threshold], not the deepest one. Returns -1 if none. */
    private fun firstDipBelowThreshold(): Int {
        var tau = minLag
        while (tau <= maxLag) {
            if (cmnd[tau] < threshold) {
                // The first sample under the threshold is partway down the slope; walk to
                // the actual bottom of this dip.
                while (tau + 1 <= maxLag && cmnd[tau + 1] < cmnd[tau]) tau++
                return tau
            }
            tau++
        }
        return -1
    }

    /**
     * Step 5: fit a parabola through the dip and its neighbours, so the period isn't
     * quantised to whole samples. At 48 kHz a whole-sample lag error near 400 Hz is already
     * worth ~15 cents, which would be audible as mistuning in the pitch bar.
     */
    private fun refineLag(tau: Int): Float {
        if (tau <= minLag || tau >= maxLag) return tau.toFloat()

        val s0 = cmnd[tau - 1]
        val s1 = cmnd[tau]
        val s2 = cmnd[tau + 1]

        val curvature = s0 + s2 - 2f * s1
        if (curvature <= 0f) return tau.toFloat()

        val offset = (s0 - s2) / (2f * curvature)
        return tau + offset.coerceIn(-1f, 1f)
    }
}
