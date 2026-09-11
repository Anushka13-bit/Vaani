package com.vaanimitra.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages calibration clips in app-private storage:
 *   /files/calibration/{session_id}/phrase_NN.wav
 *   /files/calibration/{session_id}/manifest.json
 *
 * And performs single batched multipart upload to POST /calibrate.
 */
object CalibrationStorageManager {

    private const val TAG = "CalibrationStorage"

    fun getSessionDir(context: Context, sessionId: String): File {
        val dir = File(context.filesDir, "calibration/$sessionId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getPhraseFile(context: Context, sessionId: String, phraseIndex: Int): File {
        val dir = getSessionDir(context, sessionId)
        return File(dir, String.format("phrase_%02d.wav", phraseIndex))
    }

    fun getManifestFile(context: Context, sessionId: String): File {
        val dir = getSessionDir(context, sessionId)
        return File(dir, "manifest.json")
    }

    fun updateManifest(
        context: Context,
        sessionId: String,
        filename: String,
        promptText: String,
    ) {
        val file = getManifestFile(context, sessionId)
        val json = if (file.exists()) {
            try {
                JSONObject(file.readText())
            } catch (_: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }

        json.put("session_id", sessionId)
        json.put(filename, promptText)

        val mapping = json.optJSONObject("mapping") ?: JSONObject().also { json.put("mapping", it) }
        mapping.put(filename, promptText)

        file.writeText(json.toString(2))
        Log.d(TAG, "Updated manifest for $filename -> '$promptText'")
    }

    fun getSessionClips(context: Context, sessionId: String): List<File> {
        val dir = getSessionDir(context, sessionId)
        return dir.listFiles { _, name -> name.endsWith(".wav") }
            ?.sortedBy { it.name }
            ?.toList() ?: emptyList()
    }

    /**
     * Single batched multipart upload to POST /calibrate:
     * - session_id (form field)
     * - manifest (manifest.json)
     * - files (all 40 phrase_NN.wav clips)
     */
    fun uploadBatch(
        context: Context,
        sessionId: String,
        baseUrl: String,
        authToken: String? = null,
    ): String {
        val sessionDir = getSessionDir(context, sessionId)
        val manifestFile = getManifestFile(context, sessionId)
        if (!manifestFile.exists()) {
            throw IllegalStateException("manifest.json missing in ${sessionDir.absolutePath}")
        }
        val clips = getSessionClips(context, sessionId)
        if (clips.isEmpty()) {
            throw IllegalStateException("No calibration WAV clips found in ${sessionDir.absolutePath}")
        }

        val rawBase = baseUrl.trimEnd('/')
        // Target URL: supports both http://127.0.0.1:8000/v1 and http://127.0.0.1:8000
        val targetUrl = if (rawBase.endsWith("/calibrate")) {
            rawBase
        } else if (rawBase.endsWith("/v1")) {
            "$rawBase/calibrate"
        } else {
            "$rawBase/calibrate"
        }

        Log.i(TAG, "Uploading batch for session $sessionId to $targetUrl (${clips.size} clips + manifest)")

        val boundary = "==VaaniMitraCalibrationBoundary" + System.currentTimeMillis() + "=="
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = 60_000
            readTimeout = 300_000
            setRequestProperty("Connection", "Keep-Alive")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }

        DataOutputStream(conn.outputStream).use { dos ->
            // 1. session_id form field
            dos.writeBytes(twoHyphens + boundary + lineEnd)
            dos.writeBytes("Content-Disposition: form-data; name=\"session_id\"$lineEnd")
            dos.writeBytes("Content-Type: text/plain; charset=UTF-8$lineEnd")
            dos.writeBytes(lineEnd)
            dos.writeBytes(sessionId + lineEnd)

            // 2. manifest form field (string payload)
            val manifestJson = manifestFile.readText()
            dos.writeBytes(twoHyphens + boundary + lineEnd)
            dos.writeBytes("Content-Disposition: form-data; name=\"manifest_json\"$lineEnd")
            dos.writeBytes("Content-Type: application/json; charset=UTF-8$lineEnd")
            dos.writeBytes(lineEnd)
            dos.writeBytes(manifestJson + lineEnd)

            // 3. manifest file upload
            dos.writeBytes(twoHyphens + boundary + lineEnd)
            dos.writeBytes("Content-Disposition: form-data; name=\"manifest\"; filename=\"manifest.json\"$lineEnd")
            dos.writeBytes("Content-Type: application/json$lineEnd")
            dos.writeBytes(lineEnd)
            dos.write(manifestFile.readBytes())
            dos.writeBytes(lineEnd)

            // 4. All audio clips as "files" parts
            for (clip in clips) {
                dos.writeBytes(twoHyphens + boundary + lineEnd)
                dos.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"${clip.name}\"$lineEnd")
                dos.writeBytes("Content-Type: audio/wav$lineEnd")
                dos.writeBytes(lineEnd)

                clip.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        dos.write(buffer, 0, read)
                    }
                }
                dos.writeBytes(lineEnd)
            }

            // End boundary
            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
            dos.flush()
        }

        val responseCode = conn.responseCode
        if (responseCode in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "Batch upload succeeded: HTTP $responseCode -> $responseText")
            conn.disconnect()
            return responseText
        } else {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            throw IOException("Batch upload failed with HTTP $responseCode: $errText")
        }
    }
}
