package dev.piume

import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader
import java.text.Normalizer

/**
 * Logos for the TV's tuner channels, for the ones the TV keeps none for, from iptv-org's channel database
 * (github.com/iptv-org/database, public domain). Its API's channels.json and logos.json, about 14 MB together, are
 * read as streams, keeping only the TV's country, into a small table (a channel's name, normalized, to its logo's
 * address) saved in a file and fetched again each month. Channels are matched to it by name (see [match]).
 * Network and disk: off the UI thread, except [match].
 */
internal object ChannelLogos {
    private const val API = "https://iptv-org.github.io/api"
    private const val MAX_AGE_MS = 30L * 24 * 3600_000 // the table is fetched again after a month
    private const val RETRY_MS = 24L * 3600_000 // and after a failure, not before the next day

    @Volatile private var table: Pair<String, Map<String, String>>? = null // country to its table, once read
    private val updating = java.util.concurrent.atomic.AtomicBoolean()
    val logged = java.util.concurrent.atomic.AtomicBoolean() // the channels and their logos went to the log this run

    private fun file(dir: File, country: String) = File(dir, "channel_logos_$country.tsv")
    private fun failed(dir: File, country: String) = File(dir, "channel_logos_$country.failed")

    /** The saved table for [country], or null when there's none yet. Reads the disk the first time. */
    fun saved(dir: File, country: String): Map<String, String>? {
        table?.takeIf { it.first == country }?.let { return it.second }
        val f = file(dir, country)
        if (!f.exists()) return null
        val map = f.readLines().mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        table = country to map
        return map
    }

    /** Whether the table is missing or a month old, and a failed try isn't too recent to try again. */
    fun due(dir: File, country: String): Boolean {
        val now = System.currentTimeMillis()
        val f = file(dir, country)
        if (f.exists() && now - f.lastModified() < MAX_AGE_MS) return false
        val fail = failed(dir, country)
        return !(fail.exists() && now - fail.lastModified() < RETRY_MS)
    }

    /**
     * Builds the table for [country] from iptv-org and saves it: how many names it has, or null when it couldn't
     * (no network, a change in their files) or another update is running.
     */
    fun update(dir: File, country: String): Int? {
        if (!updating.compareAndSet(false, true)) return null
        try {
            val built = build(country)
            val f = file(dir, country)
            val part = File(dir, f.name + ".part")
            part.writeText(built.entries.joinToString("\n") { (k, v) -> "$k\t$v" })
            if (!part.renameTo(f)) throw java.io.IOException("can't save ${f.name}")
            failed(dir, country).delete()
            table = country to built
            return built.size
        } catch (e: Exception) {
            warn("channel logos failed ($e)")
            failed(dir, country).apply { writeText(""); setLastModified(System.currentTimeMillis()) }
            return null
        } finally {
            updating.set(false)
        }
    }

    private fun build(country: String): Map<String, String> {
        val names = HashMap<String, List<String>>() // channel id -> its name and other names
        read("$API/channels.json") { r ->
            var id: String? = null
            var name: String? = null
            var where: String? = null
            val other = ArrayList<String>()
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "id" -> id = text(r)
                "name" -> name = text(r)
                "country" -> where = text(r)
                "alt_names" -> if (r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.beginArray()
                    while (r.hasNext()) text(r)?.let { other += it }
                    r.endArray()
                } else r.skipValue()
                else -> r.skipValue()
            }
            r.endObject()
            if (where == country && id != null && name != null) names[id] = listOf(name) + other
        }
        val best = HashMap<String, Logo>()
        read("$API/logos.json") { r ->
            var channel: String? = null
            var feed: String? = null
            var inUse = true
            var format: String? = null
            var width = 0
            var url: String? = null
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "channel" -> channel = text(r)
                "feed" -> feed = text(r)
                "in_use" -> if (r.peek() == JsonToken.BOOLEAN) inUse = r.nextBoolean() else r.skipValue()
                "format" -> format = text(r)
                "width" -> if (r.peek() == JsonToken.NUMBER) width = r.nextInt() else r.skipValue()
                "url" -> url = text(r)
                else -> r.skipValue()
            }
            r.endObject()
            if (channel != null && url != null && channel in names) {
                val logo = Logo(url, feed, inUse, format, width)
                if (usable(logo) && better(logo, best[channel])) best[channel] = logo
            }
        }
        val out = LinkedHashMap<String, String>()
        for ((id, all) in names) {
            val logo = best[id] ?: continue
            for (n in all) normalize(n).takeIf { it.isNotEmpty() }?.let { out.putIfAbsent(it, logo.url) }
        }
        return out
    }

    /** Each item of the JSON array at [url], read one at a time with [item] so the whole file never sits in memory. */
    private fun read(url: String, item: (JsonReader) -> Unit) = Http.open(url).use { stream ->
        JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { r ->
            r.beginArray()
            while (r.hasNext()) item(r)
            r.endArray()
        }
    }

    private fun text(r: JsonReader): String? = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()

    internal class Logo(val url: String, val feed: String?, val inUse: Boolean, val format: String?, val width: Int)

    /** A picture Android can draw: PNG, JPEG or WebP (not SVG), at an http(s) address (not a page that links to one). */
    internal fun usable(l: Logo) = l.format?.uppercase() in setOf("PNG", "JPEG", "WEBP") && l.url.startsWith("http")

    /** Whether [a] beats [b]: the channel's own logo before a feed's (an HD or regional version), in use, then wider. */
    internal fun better(a: Logo, b: Logo?): Boolean = b == null || compareValuesBy(a, b, { it.feed == null }, { it.inUse }, { it.width }) > 0

    /**
     * A channel name as the table keys it: no accents, case, spaces or punctuation, and no picture-quality words
     * ("Canale 5 HD" and "CANALE5" are one). "+1" stays: a timeshift channel is a channel of its own.
     */
    internal fun normalize(name: String): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        return plain.replace(Regex("\\b(hd|fhd|uhd|4k|sd)\\b"), "").replace(Regex("[^a-z0-9+]"), "")
    }

    /**
     * The logo for a channel called [name]: its name as it is, or else with words dropped from the end ("Rai 3 TGR
     * Lazio", a regional edition, gets Rai 3's), never down to fewer than 3 letters, which would match too much.
     */
    internal fun match(table: Map<String, String>, name: String): String? {
        table[normalize(name)]?.let { return it }
        val words = name.trim().split(Regex("\\s+"))
        for (n in words.size - 1 downTo 1) {
            val key = normalize(words.take(n).joinToString(" "))
            if (key.length >= 3) table[key]?.let { return it }
        }
        return null
    }
}
