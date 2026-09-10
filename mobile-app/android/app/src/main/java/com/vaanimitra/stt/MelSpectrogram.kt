package com.vaanimitra.stt

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
        val n = frame.size
        val real = frame.copyOf()
        val imag = FloatArray(n)
        fft(real, imag)
        val half = n / 2 + 1
        return FloatArray(half) { k ->
            real[k] * real[k] + imag[k] * imag[k]
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
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
