package com.vaanimitra.stt

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

object AdapterDownloader {

    private const val TAG = "AdapterDownloader"

    fun downloadBytes(downloadUrl: String, authToken: String): ByteArray {
        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $authToken")
            connectTimeout = 30_000
            readTimeout = 120_000
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                throw IllegalStateException("Adapter download failed: HTTP $code ${err ?: ""}")
            }
            val bytes = conn.inputStream.readBytes()
            Log.i(TAG, "Downloaded ${bytes.size} bytes from $downloadUrl")
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}
