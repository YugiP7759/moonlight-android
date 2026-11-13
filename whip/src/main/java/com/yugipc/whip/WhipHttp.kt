package com.yugipc.whip

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal WHIP signaling over HttpURLConnection (no extra dependencies). */
internal class WhipHttp(private val bearerToken: String?) {

    class Answer(val sdp: String, val resourceUrl: String?)

    @Volatile
    private var active: HttpURLConnection? = null

    /** POST the offer, return the answer. Throws IOException on any failure. */
    fun postOffer(endpoint: String, offerSdp: String): Answer {
        val conn = open(endpoint, "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/sdp")
            conn.setRequestProperty("Accept", "application/sdp")
            conn.outputStream.use { it.write(offerSdp.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200 && code != 201) {
                val body = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.take(300)
                throw IOException("WHIP POST failed: HTTP $code ${body ?: ""}".trim())
            }
            val sdp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!sdp.contains("v=0")) throw IOException("WHIP answer is not SDP")
            val location = conn.getHeaderField("Location")
            val resource = location?.let { URL(URL(endpoint), it).toString() }
            return Answer(sdp, resource)
        } finally {
            conn.disconnect()
            active = null
        }
    }

    /** Best effort DELETE of the WHIP session resource. */
    fun delete(resourceUrl: String) {
        runCatching {
            val conn = open(resourceUrl, "DELETE")
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            try { conn.responseCode } finally { conn.disconnect(); active = null }
        }
    }

    /** Abort a request in progress (called from stop()). */
    fun abort() {
        runCatching { active?.disconnect() }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.useCaches = false
        bearerToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        active = conn
        return conn
    }
}
