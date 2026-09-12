package com.vaanimitra.stt

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import kotlin.math.exp

/**
 * Greedy Whisper ONNX decoder with merged TORGO LoRA weights (baked into ONNX at export).
 */
class OnnxWhisperRuntime(
    private val context: Context,
    private val adapterId: String,
) {
    companion object {
        private const val TAG = "OnnxWhisperRuntime"
        private const val MAX_TOKENS = 128
        private const val START_TOKEN = 50258L
        private const val LANG_EN = 50259L
        private const val TASK_TRANSCRIBE = 50359L
        private const val NO_TIMESTAMPS = 50363L
        private const val EOT = 50257L
    }

    data class DecodeResult(
        val text: String,
        val avgLogProb: Float,
        val executionProvider: String,
    )

    fun transcribe(pcm: ShortArray): DecodeResult? {
        val bundleDir = ModelBundleManager.bundleDir(context, adapterId)
        val holder = OnnxRuntimeHolder.getOrCreate(context, bundleDir, adapterId)
            ?: return null

        val nFrames = MelSpectrogram.frameCount(pcm.size)
        val mel = MelSpectrogram.compute(pcm)

        val encoderOut = holder.runEncoder(mel, nFrames)
        try {
            val prompt = longArrayOf(START_TOKEN, LANG_EN, TASK_TRANSCRIBE, NO_TIMESTAMPS)
            val tokens = mutableListOf<Long>()
            tokens.addAll(prompt.toList())
            val logProbs = mutableListOf<Float>()

            for (step in 0 until MAX_TOKENS) {
                val logits = holder.runDecoderStep(tokens.toLongArray(), encoderOut)
                val (nextId, logProb) = argmaxLogits(logits)
                logProbs.add(logProb)
                if (nextId == EOT) break
                tokens.add(nextId)
            }

            WhisperTokenizer.load(bundleDir, adapterId)
            val text = WhisperTokenizer.decode(tokens.drop(4))
            val avgLog = if (logProbs.isEmpty()) -1f else logProbs.average().toFloat()
            holder.reportEpPlacement(context)
            return DecodeResult(text.trim(), avgLog, holder.executionProvider)
        } finally {
            encoderOut.close()
        }
    }

    private fun argmaxLogits(logits: FloatArray): Pair<Long, Float> {
        var bestIdx = 0
        var bestVal = logits[0]
        for (i in 1 until logits.size) {
            if (logits[i] > bestVal) {
                bestVal = logits[i]
                bestIdx = i
            }
        }
        val maxLog = logits.maxOrNull() ?: 0f
        val expSum = logits.sumOf { exp((it - maxLog).toDouble()) }.toFloat()
        val logProb = maxLog - ln(expSum)
        return Pair(bestIdx.toLong(), logProb)
    }

    private fun ln(x: Float): Float = kotlin.math.ln(x)

}
