package dev.piume

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Piume's one way to the internet: the same timeouts everywhere (a TV on a slow Wi-Fi shouldn't hang a thread for
 * minutes), and a clear failure for anything but a 2xx answer. Network: call it off the UI thread.
 */
internal object Http {
    private const val TIMEOUT_MS = 6000
    private const val USER_AGENT = "Piume/1 (Android TV launcher; https://github.com/mccoy88f/Piume_Launcher)"

    fun connect(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // Who's asking, as Wikimedia (most channel logos) wants: Android's anonymous default gets throttled there.
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    /** The body of [url]; throws on a network error or a non-2xx answer. */
    fun open(url: String, headers: Map<String, String> = emptyMap()): InputStream = connect(url, headers).inputStream

    fun text(url: String, headers: Map<String, String> = emptyMap()) = open(url, headers).use { it.readBytes().decodeToString() }

    fun json(url: String, headers: Map<String, String> = emptyMap()) = JSONObject(text(url, headers + ("Accept" to "application/json")))
}
