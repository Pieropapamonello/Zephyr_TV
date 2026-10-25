package dev.piume

import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio add-ons as a source of TV channels: the user adds an add-on by its manifest's address (an OMG+ TV
 * configuration, or any add-on with "tv" catalogs) and each of its TV catalogs becomes rows, the whole catalog and
 * one per category (its "genre" options). What the add-on lists is saved in a small file and read again every
 * quarter of an hour, so the rows show at once and what's on stays fresh. Network and disk: off the UI thread.
 */
internal object Stremio {
    private const val MAX_AGE_MS = 15 * 60_000L // the channels are read again after this long ("on now" goes stale)
    private const val RETRY_MS = 5 * 60_000L // and after a failure, not before five minutes
    private const val MAX_PAGES = 40 // a catalog's pages read at most (100 channels each, on OMG)

    /** One of the add-on's catalogs of [type] "tv", with the categories it can be filtered by. */
    internal class Catalog(val type: String, val id: String, val name: String, val genres: List<String>)

    /** An add-on as its manifest describes it: [url] is the manifest's own address. */
    internal class Addon(val url: String, val id: String, val name: String, val logo: String?, val catalogs: List<Catalog>) {
        val base get() = url.removeSuffix("/manifest.json")
    }

    /** A channel in a catalog: what to ask the streams for, its name and logo, and what's on (if the add-on says). */
    internal class Item(val type: String, val id: String, val name: String, val logo: String?, val now: String?)

    /** One row: a catalog, whole ([genre] null) or one of its categories. */
    internal class Row(val catalog: Catalog, val genre: String?, val items: List<Item>)

    /** What's saved for an add-on: its manifest and rows. */
    internal class Listing(val addon: Addon, val rows: List<Row>)

    /** A way to play a channel: the address, its name ("Rai 1 (2)") and the headers the player must send. */
    internal class Stream(val url: String, val title: String, val headers: Map<String, String>)

    @Volatile private var listings: Map<String, Listing> = emptyMap() // manifest address to what's saved, once read
    private val updating = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The manifest's address for what the user typed or pasted: stremio:// links open as https, and an address
     * without /manifest.json gets it. Null when it isn't a web address.
     */
    fun manifestUrl(input: String): String? {
        var url = input.trim()
        if (url.startsWith("stremio://")) url = "https://" + url.removePrefix("stremio://")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        if (url.substringAfter("://").substringBefore('/').isEmpty()) return null
        url = url.removeSuffix("/")
        return if (url.endsWith("/manifest.json")) url else "$url/manifest.json"
    }

    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)

    private fun file(dir: File, url: String) = File(dir, "stremio_${key(url)}.json")
    private fun failed(dir: File, url: String) = File(dir, "stremio_${key(url)}.failed")

    /** What's saved for the add-on at [url], or null when nothing is yet. Reads the disk the first time. */
    fun saved(dir: File, url: String): Listing? {
        listings[url]?.let { return it }
        val f = file(dir, url)
        if (!f.exists()) return null
        val listing = runCatching { listing(url, JSONObject(f.readText())) }.getOrNull() ?: return null
        listings = listings + (url to listing)
        return listing
    }

    /** Whether the add-on's channels are missing or a quarter of an hour old, and a failed try isn't too recent. */
    fun due(dir: File, url: String): Boolean {
        val now = System.currentTimeMillis()
        val f = file(dir, url)
        if (f.exists() && now - f.lastModified() < MAX_AGE_MS) return false
        val fail = failed(dir, url)
        return !(fail.exists() && now - fail.lastModified() < RETRY_MS)
    }

    /** Forgets what's saved for an add-on the user removed. */
    fun forget(dir: File, url: String) {
        listings = listings - url
        file(dir, url).delete()
        failed(dir, url).delete()
    }

    /**
     * Reads the add-on at [url] again, its manifest and every row, and saves it: the add-on, or null when it couldn't
     * (or another update of it is running). An add-on without TV catalogs is saved too, with no rows.
     */
    fun update(dir: File, url: String): Listing? {
        if (!updating.add(url)) return null
        try {
            val manifest = Http.json(url)
            val addon = addon(url, manifest)
            val rows = addon.catalogs.flatMap { c -> (listOf<String?>(null) + c.genres).map { g -> Row(c, g, items(addon, c, g)) } }
                .filter { it.items.isNotEmpty() }
            val saved = JSONObject().put("manifest", manifest).put("rows", JSONArray(rows.map { row ->
                JSONObject().put("catalog", row.catalog.id).put("type", row.catalog.type).put("genre", row.genre ?: JSONObject.NULL)
                    .put("items", JSONArray(row.items.map { JSONObject().put("type", it.type).put("id", it.id).put("name", it.name)
                        .put("logo", it.logo ?: JSONObject.NULL).put("now", it.now ?: JSONObject.NULL) }))
            }))
            val f = file(dir, url)
            val part = File(dir, f.name + ".part")
            part.writeText(saved.toString())
            if (!part.renameTo(f)) throw java.io.IOException("can't save ${f.name}")
            failed(dir, url).delete()
            return Listing(addon, rows).also { listings = listings + (url to it) }
        } catch (e: Exception) {
            warn("Stremio add-on failed: ${Uri.host(url)} ($e)")
            failed(dir, url).apply { writeText(url); setLastModified(System.currentTimeMillis()) }
            return null
        } finally {
            updating.remove(url)
        }
    }

    /** The add-on in a manifest: its name, logo and TV catalogs (categories without a letter or digit are left out: OMG's ⚙️). */
    internal fun addon(url: String, m: JSONObject): Addon {
        val catalogs = ArrayList<Catalog>()
        val list = m.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            if (c.optString("type") != "tv") continue
            val id = c.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val extra = c.optJSONArray("extra") ?: JSONArray()
            // A catalog that can't be listed without a filter (search only, or a required genre) isn't a list of channels.
            val required = (0 until extra.length()).mapNotNull { extra.optJSONObject(it) }.filter { it.optBoolean("isRequired") }
            if (required.any { it.optString("name") != "genre" }) continue
            val genres = (0 until extra.length()).mapNotNull { extra.optJSONObject(it) }.firstOrNull { it.optString("name") == "genre" }
                ?.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty()
                .filter { g -> g.any { it.isLetterOrDigit() } }
            catalogs += Catalog("tv", id, c.optString("name").ifBlank { m.optString("name") }, genres)
        }
        return Addon(url, m.optString("id").ifBlank { url }, m.optString("name").ifBlank { Uri.host(url) }, text(m, "logo"), catalogs)
    }

    /** A catalog's channels, all its pages: whole, or those of one [genre]. */
    private fun items(addon: Addon, c: Catalog, genre: String?): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (page in 0 until MAX_PAGES) {
            val extra = listOfNotNull(genre?.let { "genre=${enc(it)}" }, if (out.isEmpty()) null else "skip=${out.size}").joinToString("&")
            val path = "${addon.base}/catalog/${enc(c.type)}/${enc(c.id)}" + (if (extra.isEmpty()) "" else "/$extra") + ".json"
            val metas = items(Http.json(path))
            val before = out.size
            for (it in metas) out.putIfAbsent(it.id, it)
            // A short page is the last; one with nothing new means the add-on ignores "skip".
            if (metas.size < 50 || out.size == before) break
        }
        return out.values.toList()
    }

    /** The channels in a catalog page. */
    internal fun items(page: JSONObject): List<Item> {
        val metas = page.optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }.mapNotNull { m ->
            val id = m.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = m.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // "LIVE" is what OMG says when it has no guide for the channel: nothing to show.
            val now = text(m, "releaseInfo")?.takeIf { !it.equals("LIVE", ignoreCase = true) }
            Item(m.optString("type").ifBlank { "tv" }, id, name, logo(text(m, "logo") ?: text(m, "poster")), now)
        }
    }

    /**
     * The add-on's logo for a channel, its own placeholder too (it chose it): null only when it isn't a web address
     * or it's cut short ("…/images?q"). Piume's own (see ChannelLogos) is the fallback, for none or one that won't load.
     */
    internal fun logo(url: String?): String? {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        val query = url.substringAfter('?', "")
        if (url.contains('?') && (query.isEmpty() || !query.contains('='))) return null
        return url
    }

    /** What the add-on tells about a channel on its page: what's on and next ([description]), and what's on in short. */
    internal class Meta(val description: String?, val now: String?)

    /** Channel [id]'s page from the add-on. Throws when the add-on can't be asked. */
    fun meta(base: String, type: String, id: String): Meta = meta(Http.json("$base/meta/${enc(type)}/${enc(id)}.json"))

    internal fun meta(answer: JSONObject): Meta {
        val m = answer.optJSONObject("meta") ?: return Meta(null, null)
        val now = text(m, "releaseInfo")?.takeIf { !it.equals("LIVE", ignoreCase = true) }
        // Without a guide OMG says only "Channel: <name> - ID: <id>": nothing to tell, the fallback guide tells more.
        val description = text(m, "description")?.takeIf { now != null }
            ?.lines()?.map { it.trim() }?.filterIndexed { i, line -> line.isNotEmpty() || i == 0 }?.joinToString("\n")
        return Meta(description, now)
    }

    /**
     * How far into the program on now [at] is (1 to 100), from the first "HH:MM - HH:MM" in an add-on's description
     * (OMG writes "⏰ 05:00 - 00:30"), on today's clock (an end before the start is after midnight). Null without one.
     */
    internal fun progress(description: String?, at: java.util.Calendar = java.util.Calendar.getInstance()): Int? {
        val m = Regex("""(\d{1,2}):(\d{2})\s*[-–]\s*(\d{1,2}):(\d{2})""").find(description ?: return null) ?: return null
        val (h1, m1, h2, m2) = m.destructured
        val now = at.get(java.util.Calendar.HOUR_OF_DAY) * 60 + at.get(java.util.Calendar.MINUTE)
        val start = h1.toInt() * 60 + m1.toInt()
        var end = h2.toInt() * 60 + m2.toInt()
        if (end <= start) end += 24 * 60
        val t = if (now < start && now + 24 * 60 < end) now + 24 * 60 else now
        if (t < start || t >= end) return null
        return ((t - start) * 100 / (end - start)).coerceIn(1, 100)
    }

    /** The ways to play channel [id], in the add-on's order (best first); those without a web address are left out. */
    fun streams(base: String, type: String, id: String): List<Stream> = streams(Http.json("$base/stream/${enc(type)}/${enc(id)}.json"))

    internal fun streams(answer: JSONObject): List<Stream> {
        val list = answer.optJSONArray("streams") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { s ->
            val url = text(s, "url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            val request = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val headers = request?.keys()?.asSequence()?.associateWith { request.optString(it) }?.filterValues { it.isNotEmpty() }.orEmpty()
            Stream(url, (text(s, "title") ?: text(s, "name") ?: "").lines().first().trim(), headers)
        }
    }

    /** What a stream's address turned out to be when asked: whether it answers at all, and its format if known. */
    internal class Probe(val ok: Boolean, val format: String?)

    /**
     * Asks [url] what it is before the player gets it: its addresses (OMG's /s/<token>) have no extension to tell HLS
     * from DASH by, and the player can't tell either. Follows the redirects (also http to https), then goes by the
     * answer's type, the final address's extension, and the first bytes. Network: off the UI thread.
     */
    fun probe(url: String, headers: Map<String, String>): Probe {
        var at = url
        for (hop in 0..5) {
            val c = Http.connect(at, headers).apply { instanceFollowRedirects = false }
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    at = URL(URL(at), c.getHeaderField("Location") ?: return Probe(false, null)).toString()
                    continue
                }
                if (code !in 200..299) return Probe(false, null)
                val head = ByteArray(2048)
                var n = 0
                c.inputStream.use { input -> while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r } }
                return Probe(true, format(c.contentType, at, head.copyOf(n)))
            } catch (e: Exception) {
                warn("stream check failed: ${Uri.host(at)} ($e)")
                return Probe(false, null)
            } finally {
                c.disconnect()
            }
        }
        return Probe(false, null)
    }

    /** "hls", "dash", "ts" or null (let the player work it out), from an answer's type, address and first bytes. */
    internal fun format(contentType: String?, url: String, head: ByteArray): String? {
        val type = contentType?.lowercase().orEmpty()
        when {
            "mpegurl" in type -> return "hls"
            "dash+xml" in type -> return "dash"
            "mp2t" in type -> return "ts"
        }
        val path = url.substringBefore('?').lowercase()
        when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> return "hls"
            path.endsWith(".mpd") -> return "dash"
            path.endsWith(".ts") -> return "ts"
        }
        val text = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            text.startsWith("#EXTM3U") -> "hls"
            text.startsWith("<?xml") && "<MPD" in text || text.startsWith("<MPD") -> "dash"
            // MPEG-TS: packets of 188 bytes, each starting with 0x47.
            head.isNotEmpty() && head[0] == 0x47.toByte() && (head.size <= 188 || head[188] == 0x47.toByte()) -> "ts"
            else -> null
        }
    }

    private fun listing(url: String, saved: JSONObject): Listing {
        val addon = addon(url, saved.getJSONObject("manifest"))
        val rows = saved.optJSONArray("rows") ?: JSONArray()
        return Listing(addon, (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { r ->
            val catalog = addon.catalogs.firstOrNull { it.id == r.optString("catalog") } ?: return@mapNotNull null
            val items = r.optJSONArray("items") ?: JSONArray()
            Row(catalog, text(r, "genre"), (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map {
                Item(it.optString("type"), it.optString("id"), it.optString("name"), text(it, "logo"), text(it, "now"))
            })
        })
    }

    /** A string field, or null when it's missing, null or blank (optString would say "null"). */
    private fun text(o: JSONObject, name: String): String? = if (o.isNull(name)) null else o.optString(name).takeIf { it.isNotBlank() }

    /** Encoded for a Stremio path, as its clients do (encodeURIComponent: spaces as %20). */
    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Addresses taken apart without android.net.Uri, so this file's tests run off a TV. */
    private object Uri {
        fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfter('@').substringBefore(':')
    }
}
