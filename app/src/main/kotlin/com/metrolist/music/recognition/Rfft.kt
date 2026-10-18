/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Computes the real-input FFT of [windowed] (size a power of two, e.g. 2048) using an iterative
 * Cooley-Tukey radix-2 DIT algorithm.
 *
 * Returns n / 2 + 1 power values:
 *   magnitude[k] = max((re[k]² + im[k]²) / 2^17, 1e-10)
 *
 * This matches the FFTW3 r2c output format used in the C++ vibra library.
 */
internal fun computeRfft(windowed: DoubleArray): DoubleArray {
    val n = windowed.size
    val re = windowed.copyOf()
    val im = DoubleArray(n)

    // Bit-reversal permutation
    var j = 0
    for (i in 1 until n) {
        var bit = n ushr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit ushr 1
        }
        j = j xor bit
        if (i < j) {
            var tmp = re[i]; re[i] = re[j]; re[j] = tmp
            tmp = im[i]; im[i] = im[j]; im[j] = tmp
        }
    }

    // Cooley-Tukey butterfly stages (log2(n) stages)
    var len = 2
    while (len <= n) {
        val halfLen = len ushr 1
        val ang = -PI / halfLen       // = -2π / len
        val wBaseRe = cos(ang)
        val wBaseIm = sin(ang)
        var i = 0
        while (i < n) {
            var wRe = 1.0
            var wIm = 0.0
            for (k in 0 until halfLen) {
                val u = i + k
                val v = u + halfLen
                val evenRe = re[u]
                val evenIm = im[u]
                val oddRe = re[v] * wRe - im[v] * wIm
                val oddIm = re[v] * wIm + im[v] * wRe
                re[u] = evenRe + oddRe
                im[u] = evenIm + oddIm
                re[v] = evenRe - oddRe
                im[v] = evenIm - oddIm
                val newWRe = wRe * wBaseRe - wIm * wBaseIm
                wIm = wRe * wBaseIm + wIm * wBaseRe
                wRe = newWRe
            }
            i += len
        }
        len = len shl 1
    }

    // Extract magnitudes for bins 0..n/2
    val scaleFactor = 1.0 / (1 shl 17)
    val minVal = 1e-10
    return DoubleArray(n / 2 + 1) { idx ->
        val r = re[idx]
        val img = im[idx]
        val mag = (r * r + img * img) * scaleFactor
        if (mag < minVal) minVal else mag
    }
}
