package com.vaanimitra.stt

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Whisper-compatible log-mel spectrogram (80 bins, 16 kHz, hop 160, FFT 400).
 *
 * Mirrors transformers' WhisperFeatureExtractor: audio is padded/trimmed to a fixed
 * 30 s window, framed with a centred (reflect-padded) periodic Hann STFT, projected
 * through slaney-scale mel filters, then log10-scaled and normalised against the
 * spectrogram's own maximum. Deviating from any of these makes the encoder see
 * features unlike anything it saw in training.
 */
object MelSpectrogram {

    const val N_MELS = 80
    const val N_FFT = 400
    const val HOP = 160
    const val SAMPLE_RATE = 16_000

    // Whisper's encoder graph has a fixed input width — it always consumes exactly
    // 30 s (3000 mel frames). Emitting a frame count derived from the actual audio
    // length makes ONNX Runtime reject the tensor outright.
    const val N_FRAMES = 3000
    const val N_SAMPLES = N_FRAMES * HOP

    private const val LOG_FLOOR = 1e-10f

    // N_FFT=400 is not a power of two (400 = 2^4 * 5^2), so it cannot be transformed
    // by a plain radix-2 FFT — that requires Bluestein's algorithm, which computes an
    // arbitrary-length DFT via a convolution carried out with a power-of-two FFT.
    private val BLUESTEIN_M: Int = run {
        var m = 1
        while (m < 2 * N_FFT - 1) m = m shl 1
        m
    }
    private val chirpCos: FloatArray
    private val chirpSin: FloatArray
    private val bluesteinBReal: FloatArray
    private val bluesteinBImag: FloatArray

    private val window: FloatArray
    private val melFilters: Array<FloatArray>

    init {
        chirpCos = FloatArray(N_FFT)
        chirpSin = FloatArray(N_FFT)
        for (n in 0 until N_FFT) {
            val ang = Math.PI * n.toDouble() * n.toDouble() / N_FFT
            chirpCos[n] = cos(ang).toFloat()
            chirpSin[n] = sin(ang).toFloat()
        }

        val br = FloatArray(BLUESTEIN_M)
        val bi = FloatArray(BLUESTEIN_M)
        for (n in 0 until N_FFT) {
            br[n] = chirpCos[n]
            bi[n] = chirpSin[n]
            if (n != 0) {
                br[BLUESTEIN_M - n] = chirpCos[n]
                bi[BLUESTEIN_M - n] = chirpSin[n]
            }
        }
        fftRadix2(br, bi)
        bluesteinBReal = br
        bluesteinBImag = bi

        window = hannWindow(N_FFT)
        melFilters = buildMelFilterBank()
    }

    /** Returns N_MELS * N_FRAMES features in mel-major order. */
    fun compute(pcm: ShortArray): FloatArray {
        val audioLen = min(pcm.size, N_SAMPLES)
        val audio = FloatArray(N_SAMPLES)
        for (i in 0 until audioLen) audio[i] = pcm[i] / 32768f

        val logSpec = FloatArray(N_MELS * N_FRAMES)
        var maxLog = Float.NEGATIVE_INFINITY

        // Frame f is centred on sample f*HOP, so it still touches real audio while
        // f*HOP - N_FFT/2 < audioLen. Every later frame is pure zero padding and
        // resolves to the same constant — worth short-circuiting, since the padding
        // is usually >20x the spoken audio and each frame costs two 1024-pt FFTs.
        val lastAudioFrame = min(N_FRAMES - 1, (audioLen + N_FFT / 2) / HOP)

        val frameData = FloatArray(N_FFT)
        for (f in 0..lastAudioFrame) {
            val start = f * HOP - N_FFT / 2
            for (i in 0 until N_FFT) {
                frameData[i] = audio[reflectIndex(start + i, N_SAMPLES)] * window[i]
            }
            val power = powerSpectrum(frameData)
            for (m in 0 until N_MELS) {
                val filter = melFilters[m]
                var sum = 0f
                for (k in filter.indices) sum += power[k] * filter[k]
                val v = log10(max(sum, LOG_FLOOR))
                logSpec[m * N_FRAMES + f] = v
                if (v > maxLog) maxLog = v
            }
        }

        if (lastAudioFrame < N_FRAMES - 1) {
            val silence = log10(LOG_FLOOR)
            if (silence > maxLog) maxLog = silence
            for (m in 0 until N_MELS) {
                val row = m * N_FRAMES
                java.util.Arrays.fill(logSpec, row + lastAudioFrame + 1, row + N_FRAMES, silence)
            }
        }

        val floor = maxLog - 8f
        for (i in logSpec.indices) {
            logSpec[i] = (max(logSpec[i], floor) + 4f) / 4f
        }
        return logSpec
    }

    /** Whisper's encoder is fixed-width, so this never depends on the audio length. */
    fun frameCount(pcmLength: Int): Int = N_FRAMES

    /** Mirrors torch's 'reflect' STFT padding: x[-i] == x[i]. */
    private fun reflectIndex(i: Int, n: Int): Int {
        if (i in 0 until n) return i
        if (n <= 1) return 0
        val period = 2 * (n - 1)
        val k = ((i % period) + period) % period
        return if (k < n) k else period - k
    }

    /** Periodic Hann (divides by n, not n-1) — what torch.hann_window produces. */
    private fun hannWindow(n: Int): FloatArray =
        FloatArray(n) { i -> (0.5 * (1.0 - cos(2.0 * Math.PI * i / n))).toFloat() }

    private fun powerSpectrum(frame: FloatArray): FloatArray {
        val (real, imag) = bluesteinDft(frame)
        val half = N_FFT / 2 + 1
        return FloatArray(half) { k -> real[k] * real[k] + imag[k] * imag[k] }
    }

    /** Arbitrary-length DFT (here N_FFT=400) via Bluestein's algorithm. */
    private fun bluesteinDft(frame: FloatArray): Pair<FloatArray, FloatArray> {
        val m = BLUESTEIN_M
        val ar = FloatArray(m)
        val ai = FloatArray(m)
        for (i in 0 until N_FFT) {
            // a[n] = x[n] * exp(-i*ang[n]); x is real-valued here
            ar[i] = frame[i] * chirpCos[i]
            ai[i] = -frame[i] * chirpSin[i]
        }
        fftRadix2(ar, ai)
        for (i in 0 until m) {
            val r = ar[i]
            val v = ai[i]
            ar[i] = r * bluesteinBReal[i] - v * bluesteinBImag[i]
            ai[i] = r * bluesteinBImag[i] + v * bluesteinBReal[i]
        }
        ifftRadix2(ar, ai)
        val outR = FloatArray(N_FFT)
        val outI = FloatArray(N_FFT)
        for (k in 0 until N_FFT) {
            val c = chirpCos[k]
            val s = chirpSin[k]
            val xr = ar[k]
            val xi = ai[k]
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
            val wlenI = sin(ang)
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

    // Slaney mel scale (linear below 1 kHz, logarithmic above) — librosa's htk=False
    // default, which is what Whisper was trained with. The HTK formula is a different
    // curve and shifts every filter's centre frequency.
    private const val MEL_MIN_LOG_HZ = 1000f
    private const val MEL_MIN_LOG_MEL = 15f

    private fun hzToMel(hz: Float): Float {
        val logstep = 27f / ln(6.4f)
        return if (hz >= MEL_MIN_LOG_HZ) {
            MEL_MIN_LOG_MEL + ln(hz / MEL_MIN_LOG_HZ) * logstep
        } else {
            3f * hz / 200f
        }
    }

    private fun melToHz(mel: Float): Float {
        val logstep = ln(6.4f) / 27f
        return if (mel >= MEL_MIN_LOG_MEL) {
            MEL_MIN_LOG_HZ * exp(logstep * (mel - MEL_MIN_LOG_MEL))
        } else {
            200f * mel / 3f
        }
    }

    private fun buildMelFilterBank(): Array<FloatArray> {
        val numFreqBins = N_FFT / 2 + 1
        val nyquist = SAMPLE_RATE / 2f
        val fftFreqs = FloatArray(numFreqBins) { it * nyquist / (numFreqBins - 1) }

        val melMin = hzToMel(0f)
        val melMax = hzToMel(nyquist)
        val filterFreqs = FloatArray(N_MELS + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (N_MELS + 1))
        }
        val diff = FloatArray(N_MELS + 1) { filterFreqs[it + 1] - filterFreqs[it] }

        val filters = Array(N_MELS) { FloatArray(numFreqBins) }
        for (m in 0 until N_MELS) {
            // Slaney normalisation: equal area per filter rather than equal peak.
            val enorm = 2f / (filterFreqs[m + 2] - filterFreqs[m])
            for (k in 0 until numFreqBins) {
                val down = (fftFreqs[k] - filterFreqs[m]) / diff[m]
                val up = (filterFreqs[m + 2] - fftFreqs[k]) / diff[m + 1]
                filters[m][k] = max(0f, min(down, up)) * enorm
            }
        }
        return filters
    }
}
