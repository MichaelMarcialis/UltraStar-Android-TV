package com.example.ultrastarandroidtv.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A real-input radix-2 FFT, just big enough to drive a visualiser.
 *
 * Written rather than pulled in: this is one textbook algorithm in fifty lines, and the
 * alternative is a dependency in the audio path of a machine that has to keep working in a
 * living room years from now.
 *
 * Not thread-safe, and reuses its scratch buffers between calls — one instance per consumer,
 * used from one thread. That is deliberate: this runs inside the audio pipeline, where
 * allocating per buffer is how you get audible glitches.
 *
 * @param size number of samples per transform. Must be a power of two.
 */
class Fft(val size: Int) {

    init {
        require(size > 1 && size and (size - 1) == 0) { "size must be a power of two, was $size" }
    }

    private val real = FloatArray(size)
    private val imaginary = FloatArray(size)

    // Twiddle factors, computed once. Recomputing sin and cos inside the butterflies is the
    // single biggest waste available in a naive implementation.
    private val cosTable = FloatArray(size / 2) { cos(-2.0 * Math.PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(-2.0 * Math.PI * it / size).toFloat() }

    /** A Hann window, so a tone that does not fit the buffer exactly does not smear across it. */
    private val window = FloatArray(size) {
        (0.5 - 0.5 * cos(2.0 * Math.PI * it / (size - 1))).toFloat()
    }

    /**
     * Transforms [input] and writes the magnitude of each of the first `size / 2` bins into
     * [magnitudes], which must be at least that long.
     *
     * Only the first half is meaningful: the input is real, so the upper half of the spectrum is
     * a mirror image of the lower and carries nothing new.
     */
    fun magnitudes(input: FloatArray, magnitudes: FloatArray) {
        require(input.size >= size) { "input must hold at least $size samples" }
        require(magnitudes.size >= size / 2) { "magnitudes must hold at least ${size / 2} bins" }

        for (i in 0 until size) {
            real[i] = input[i] * window[i]
            imaginary[i] = 0f
        }

        // Bit-reversal permutation: the in-place butterflies below expect the input shuffled
        // into this order, which is what lets them run without a second array.
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImaginary = imaginary[i]
                imaginary[i] = imaginary[j]
                imaginary[j] = tempImaginary
            }
        }

        var length = 2
        while (length <= size) {
            val step = size / length
            var start = 0
            while (start < size) {
                var twiddle = 0
                for (offset in start until start + length / 2) {
                    val partner = offset + length / 2
                    val wReal = cosTable[twiddle]
                    val wImaginary = sinTable[twiddle]

                    val productReal = real[partner] * wReal - imaginary[partner] * wImaginary
                    val productImaginary = real[partner] * wImaginary + imaginary[partner] * wReal

                    real[partner] = real[offset] - productReal
                    imaginary[partner] = imaginary[offset] - productImaginary
                    real[offset] += productReal
                    imaginary[offset] += productImaginary

                    twiddle += step
                }
                start += length
            }
            length = length shl 1
        }

        for (bin in 0 until size / 2) {
            magnitudes[bin] =
                sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin])
        }
    }
}
