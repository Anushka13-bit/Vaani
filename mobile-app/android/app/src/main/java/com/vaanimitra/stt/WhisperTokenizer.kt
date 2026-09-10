package com.vaanimitra.stt

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Loads HuggingFace tokenizer.json from the mobile bundle for ONNX decode.
 */
object WhisperTokenizer {

    private const val TAG = "WhisperTokenizer"
    private val idToPiece = mutableMapOf<Long, String>()

    @Volatile
    private var loadedFor: String? = null

    fun load(bundleDir: File, adapterId: String): Boolean {
        if (loadedFor == adapterId && idToPiece.isNotEmpty()) return true
        val tokFile = File(bundleDir, "tokenizer.json")
        if (!tokFile.isFile) {
            Log.w(TAG, "tokenizer.json not found in $bundleDir")
            return false
        }
        try {
            idToPiece.clear()
            val root = JSONObject(tokFile.readText())
            val vocab = root.getJSONObject("model").getJSONObject("vocab")
            val keys = vocab.keys()
            while (keys.hasNext()) {
                val piece = keys.next()
                val id = vocab.getLong(piece)
                idToPiece[id] = piece
            }
            loadedFor = adapterId
            Log.i(TAG, "Loaded ${idToPiece.size} tokenizer pieces for $adapterId")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Tokenizer load failed: ${e.message}")
            return false
        }
    }

    fun decode(tokenIds: List<Long>): String {
        val raw = tokenIds.mapNotNull { idToPiece[it] }.joinToString("")
        return raw
            .replace("Ġ", " ")
            .replace("Ċ", "\n")
            .trim()
    }
}
