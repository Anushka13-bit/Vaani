package com.vaanimitra.stt

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whisper-compatible log-mel spectrogram (80 bins, 16 kHz, hop 160, FFT 400).
 */
object MelSpectrogram {

    const val N_MELS = 80
    const val N_FFT = 400
    const val HOP = 160
    const val SAMPLE_RATE = 16_000
    const val MAX_FRAMES = 3000

    // N_FFT=400 is not a power of two (400 = 2^4 * 5^2), so it cannot be transformed
    // by a plain radix-2 FFT — that requires Bluestein's algorithm, which computes an
    // arbitrary-length DFT via a convolution carried out with a power-of-two FFT.
    private val BLUESTEIN_M: Int = run {
        var m = 1
        while (m < 2 * N_FFT - 1) m = m shl 1
        m
    }
    private val chirpAngle: DoubleArray = DoubleArray(N_FFT) { n -> Math.PI * n.toDouble() * n.toDouble() / N_FFT }
    private val chirpCos: FloatArray = FloatArray(N_FFT) { cos(chirpAngle[it]).toFloat() }
    private val chirpSin: FloatArray = FloatArray(N_FFT) { sin(chirpAngle[it]).toFloat() }

    // Frequency-domain chirp filter B = FFT(b). Depends only on N_FFT/BLUESTEIN_M, so
    // it is computed once here rather than on every frame.
    private val bluesteinBReal: FloatArray
    private val bluesteinBImag: FloatArray

    init {
        val br = FloatArray(BLUESTEIN_M)
        val bi = FloatArray(BLUESTEIN_M)
        for (n in 0 until N_FFT) {
            br[n] = chirpCos[n]
            bi[n] = chirpSin[n]
            if (n != 0) {
                val idx = BLUESTEIN_M - n
                br[idx] = chirpCos[n]
                bi[idx] = chirpSin[n]
            }
        }
        fftRadix2(br, bi)
        bluesteinBReal = br
        bluesteinBImag = bi
    }

    fun compute(pcm: ShortArray): FloatArray {
        val floats = FloatArray(pcm.size) { pcm[it] / 32768f }
        val frameCount = min(MAX_FRAMES, 1 + (floats.size - N_FFT) / HOP)
        val mel = FloatArray(N_MELS * frameCount)

        val window = hannWindow(N_FFT)
        val melFilters = buildMelFilterBank()

        for (frame in 0 until frameCount) {
            val start = frame * HOP
            val frameData = FloatArray(N_FFT)
            for (i in 0 until N_FFT) {
                val idx = start + i
                frameData[i] = if (idx in floats.indices) floats[idx] * window[i] else 0f
            }
            val power = powerSpectrum(frameData)
            for (m in 0 until N_MELS) {
                var sum = 0f
                val filter = melFilters[m]
                for (k in filter.indices) {
                    sum += power[k] * filter[k]
                }
                val logMel = ln(max(sum, 1e-10f))
                mel[m * frameCount + frame] = max(logMel, logMel.coerceAtMost(-8f) - 4f)
            }
        }
        return mel
    }

    fun frameCount(pcmLength: Int): Int =
        min(MAX_FRAMES, max(1, 1 + (pcmLength - N_FFT) / HOP))

    private fun hannWindow(n: Int): FloatArray =
        FloatArray(n) { i -> 0.5f * (1f - cos(2f * Math.PI.toFloat() * i / (n - 1))) }

    private fun powerSpectrum(frame: FloatArray): FloatArray {
        val (real, imag) = bluesteinDft(frame)
        val half = N_FFT / 2 + 1
        return FloatArray(half) { k ->
            real[k] * real[k] + imag[k] * imag[k]
        }
    }

    /** Arbitrary-length DFT (here N_FFT=400) via Bluestein's algorithm. */
    private fun bluesteinDft(frame: FloatArray): Pair<FloatArray, FloatArray> {
        val m = BLUESTEIN_M
        val ar = FloatArray(m)
        val ai = FloatArray(m)
        for (i in 0 until N_FFT) {
            // a[n] = x[n] * exp(-i*ang[n]); x is real-valued here (imag=0)
            ar[i] = frame[i] * chirpCos[i]
            ai[i] = -frame[i] * chirpSin[i]
        }
        fftRadix2(ar, ai)
        for (i in 0 until m) {
            val ar_i = ar[i]
            val ai_i = ai[i]
            ar[i] = ar_i * bluesteinBReal[i] - ai_i * bluesteinBImag[i]
            ai[i] = ar_i * bluesteinBImag[i] + ai_i * bluesteinBReal[i]
        }
        ifftRadix2(ar, ai)
        val outR = FloatArray(N_FFT)
        val outI = FloatArray(N_FFT)
        for (k in 0 until N_FFT) {
            val c = chirpCos[k]
            val s = chirpSin[k]
            val xr = ar[k]
            val xi = ai[k]
            // X[k] = c[k] * exp(-i*ang[k])
            outR[k] = xr * c + xi * s
            outI[k] = xi * c - xr * s
        }
        return Pair(outR, outI)
    }

    private fun ifftRadix2(real: FloatArray, imag: FloatArray) {
        val n = real.size
        for (i in real.indices) imag[i] = -imag[i]
        fftRadix2(real, imag)
        val scale = 1f / n
        for (i in real.indices) {
            real[i] *= scale
            imag[i] = -imag[i] * scale
        }
    }

    /** Iterative radix-2 Cooley-Tukey — requires real.size to be an exact power of two. */
    private fun fftRadix2(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2f * Math.PI.toFloat() / len
            val wlenR = cos(ang)
            val wlenI = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until len / 2) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + len / 2] * wR - imag[i + k + len / 2] * wI
                    val vI = real[i + k + len / 2] * wI + imag[i + k + len / 2] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + len / 2] = uR - vR
                    imag[i + k + len / 2] = uI - vI
                    val nextWR = wR * wlenR - wI * wlenI
                    wI = wR * wlenI + wI * wlenR
                    wR = nextWR
                }
                i += len
            }
            len *= 2
        }
    }

    private fun hzToMel(hz: Float): Float = 2595f * ln(1f + hz / 700f)

    private fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

    private fun buildMelFilterBank(): Array<FloatArray> {
        val fMin = 0f
        val fMax = SAMPLE_RATE / 2f
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = FloatArray(N_MELS + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (N_MELS + 1))
        }
        val bins = IntArray(N_MELS + 2) { i ->
            ((N_FFT + 1) * melPoints[i] / SAMPLE_RATE).toInt().coerceIn(0, N_FFT / 2)
        }
        val filters = Array(N_MELS) { FloatArray(N_FFT / 2 + 1) }
        for (m in 0 until N_MELS) {
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]
            for (k in left until center) {
                if (center > left) filters[m][k] = (k - left).toFloat() / (center - left)
            }
            for (k in center until right) {
                if (right > center) filters[m][k] = (right - k).toFloat() / (right - center)
            }
        }
        return filters
    }
}
