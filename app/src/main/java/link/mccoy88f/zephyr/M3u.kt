package link.mccoy88f.zephyr

import java.io.BufferedReader
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * M3U playlists read for extensions (zephyr.m3u in docs/extensions.md): a playlist read as it downloads, line after
 * line, into channels — the same channel in several qualities one channel with several streams, its headers, its
 * ClearKey keys, its catch-up — kept on disk in the extension's data folder, one channel a line, and asked for a page
 * at a time. The rules are OMG Premium TV's, so a channel has the same id there and here.
 */
internal object M3u {
    /** One channel: [id] stable while the playlist keeps its tvg-id (or its name). */
    class Channel(val id: String, var name: String, val groups: MutableList<String>, val logo: String?, val epgId: String,
                  val catchup: JSONObject?, val streams: MutableList<JSONObject>) {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("group", groups.firstOrNull() ?: JSONObject.NULL)
            .put("groups", JSONArray(groups)).put("logo", logo ?: JSONObject.NULL).put("epgId", epgId)
            .put("catchup", catchup ?: JSONObject.NULL).put("streams", JSONArray(streams))
    }

    class Playlist(val channels: List<Channel>, val groups: List<String>, val guides: List<String>)

    // ---------- names and ids ----------

    internal fun normalizeId(id: String): String = id.substringBefore('@').lowercase().replace(Regex("""[^\w.]"""), "").trim()

    /** "-----DOCUMENTARI-----" → "DOCUMENTARI". */
    internal fun stripDashes(v: String): String =
        v.replace(Regex("""^\s*[-–—]{3,}\s*"""), "").replace(Regex("""\s*[-–—]{3,}\s*$"""), "").trim().ifEmpty { v.trim() }

    /** A channel's name or id without country, symbols and quality: "Rai 1 HD" and "rai1.it" both "rai1". */
    internal fun looseKey(v: String): String {
        val text = v.replace(Regex("""^\s*[\[(]?(fhd|uhd|hd|sd|4k)[\])]?\s+""", RegexOption.IGNORE_CASE), "")
        return normalizeId(text).replace(Regex("""\.[a-z]{2,3}$"""), "").replace(Regex("[^a-z0-9]"), "")
            .replace(Regex("(fhd|uhd|hd|sd|4k|hevc|h265|backup)+$"), "")
    }

    private val QUALITY = Regex("""^[\[(]?(FHD|UHD|HD|SD|4K|HEVC|H\.?26[45])\+?[\])]?$""", RegexOption.IGNORE_CASE)
    private fun quality(word: String) = QUALITY.find(word)?.groupValues?.get(1)?.uppercase()?.replace(".", "")

    /** The name without its quality words at its start and end: "SD Rai 1", "Canale 5 FHD HEVC" → "Canale 5". */
    internal fun withoutQuality(name: String): String {
        val words = name.trim().split(Regex("""\s+""")).toMutableList()
        while (words.size > 1 && quality(words.last()) != null) words.removeAt(words.size - 1)
        while (words.size > 1 && quality(words.first()) != null) words.removeAt(0)
        return words.joinToString(" ").replace(Regex("""\s*[,|:–-]+$"""), "").trim().ifEmpty { name.trim() }
    }

    /** The name in the list, without quality ("La 7 HD" → "La 7"), but "Rai 4K" stays: 4K is part of that name. */
    internal fun displayName(name: String): String {
        val plain = withoutQuality(name)
        if (plain == name.trim()) return plain
        val removed = name.trim().split(Regex("""\s+""")).mapNotNull { quality(it) }
        if (!plain.contains(Regex("""\s""")) && removed.all { it == "4K" || it == "UHD" }) return name.trim()
        return plain
    }

    private fun cleanName(name: String) = name.replace(Regex("""[(\[].*?[)\]]"""), "").trim().lowercase().replace(Regex("""\s+"""), "")

    /** "kid:key" pairs in hex (comma-separated); null for other forms. */
    internal fun clearKey(v: String): String? =
        v.split(',').map { it.trim() }.filter { Regex("^[0-9a-fA-F]{32}:[0-9a-fA-F]{32}$").matches(it) }.takeIf { it.isNotEmpty() }?.joinToString(",")?.lowercase()

    // ---------- reading ----------

    private class Entry(val name: String, val groups: List<String>, val tvg: Map<String, String>, val headers: JSONObject, val clearkey: String?)

    /** Reads a playlist from [reader] (a download, as it comes), [suffix] after the ids made from names. */
    fun read(reader: BufferedReader, suffix: String = ""): Playlist {
        val channels = LinkedHashMap<String, Channel>()
        val groups = LinkedHashSet<String>()
        val guides = LinkedHashSet<String>()
        val split = HashMap<String, String>()
        val versions = HashMap<String, String>()
        var entry: Entry? = null
        var extinf: String? = null
        val options = ArrayList<String>()
        var first = true

        fun finishOptions(): Entry? {
            val line = extinf ?: return null
            val extinfHeaders = JSONObject()
            Regex("""http-([^=]+)=["']([^"']+)""").findAll(line).forEach { extinfHeaders.put(it.groupValues[1], it.groupValues[2]) }
            val vlc = JSONObject()
            val http = JSONObject()
            var key: String? = null
            for (o in options) when {
                o.startsWith("#EXTVLCOPT:") -> o.removePrefix("#EXTVLCOPT:").trim().let { kv ->
                    vlc.put(kv.substringBefore('=').removePrefix("http-"), kv.substringAfter('=', ""))
                }
                o.startsWith("#EXTHTTP:") -> runCatching { JSONObject(o.removePrefix("#EXTHTTP:")) }.getOrNull()?.let { j -> j.keys().forEach { http.put(it, j.get(it)) } }
                o.startsWith("#KODIPROP:") -> o.removePrefix("#KODIPROP:").trim().let { kv ->
                    if (kv.substringBefore('=').trim().equals("inputstream.adaptive.license_key", true)) key = clearKey(kv.substringAfter('=', "")) ?: key
                }
            }
            val headers = JSONObject()
            listOf(extinfHeaders, vlc, http).forEach { h -> h.keys().forEach { headers.put(it, h.get(it)) } }
            val ua = http.optString("User-Agent").ifEmpty { http.optString("user-agent") }.ifEmpty { vlc.optString("user-agent") }.ifEmpty { extinfHeaders.optString("user-agent") }
            headers.remove("user-agent")
            if (ua.isNotEmpty()) headers.put("User-Agent", ua)
            (vlc.optString("referrer").ifEmpty { vlc.optString("referer") }).takeIf { it.isNotEmpty() }?.let { headers.put("Referer", it) }
            headers.remove("referrer"); headers.remove("referer")
            vlc.optString("origin").takeIf { it.isNotEmpty() }?.let { headers.remove("origin"); headers.put("Origin", it) }

            val meta = line.removePrefix("#EXTINF:").trim()
            val tvg = HashMap<String, String>()
            Regex("""([a-zA-Z-]+)="([^"]+)"""").findAll(meta).forEach { tvg[it.groupValues[1].removePrefix("tvg-")] = it.groupValues[2] }
            val g = Regex("""group-title="([^"]+)"""").find(meta)?.groupValues?.get(1)?.split(';')?.map { stripDashes(it) }
                ?.filter { it.isNotEmpty() && !it.equals("undefined", true) }.orEmpty().ifEmpty { listOf("Altri Canali") }
            val name = stripDashes(meta.substringAfterLast(','))
            tvg["name"]?.let { tvg["name"] = stripDashes(it) }
            if (tvg["id"].isNullOrBlank()) tvg["id"] = cleanName(withoutQuality(name)) + (if (suffix.isNotEmpty()) ".$suffix" else "")
            return Entry(name, g, tvg, headers, key)
        }

        fun add(url: String) {
            val e = entry ?: return
            val normalized = normalizeId(e.tvg["id"].orEmpty())
            var id = normalized
            var channel = channels[id]
            val name = (e.tvg["name"] ?: e.name).replace(Regex("""\s*\(.*?\)\s*"""), " ").trim()
            // The same tvg-id for another channel: only the same channel's entries (HD, FHD…) join it.
            if (channel != null && looseKey(channel.name) != looseKey(name)) {
                id = split[looseKey(name)] ?: normalizeId(cleanName(withoutQuality(name)) + (if (suffix.isNotEmpty()) ".$suffix" else ""))
                split[looseKey(name)] = id
                channel = channels[id]
            }
            // Another tvg-id for the quality only, and the same name but for it: a version of the same channel.
            val versionKey = "${looseKey(id)}|${looseKey(name)}"
            if (channel == null && versions.containsKey(versionKey)) {
                id = versions.getValue(versionKey)
                channel = channels[id]
            } else if (!versions.containsKey(versionKey)) versions[versionKey] = id
            if (channel == null) {
                val catchup = e.tvg["catchup"]?.let { type ->
                    JSONObject().put("type", type).put("days", e.tvg["catchup-days"]?.toIntOrNull() ?: 1)
                        .put("url", e.tvg["catchup-source"] ?: JSONObject.NULL)
                }
                channel = Channel(id, name, e.groups.toMutableList(), e.tvg["logo"], id, catchup, ArrayList())
                channels[id] = channel
                groups += e.groups
            }
            e.groups.forEach { if (it !in channel.groups) channel.groups += it }
            if (!url.equals("null", true)) channel.streams += JSONObject().put("url", url).put("name", e.name).put("headers", e.headers)
                .put("clearkey", e.clearkey ?: JSONObject.NULL)
            entry = null
        }

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (first) {
                first = false
                (Regex("""url-tvg="([^"]+)"""").find(line) ?: Regex("""x-tvg-url="([^"]+)"""").find(line))?.groupValues?.get(1)
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { guides += it }
            }
            when {
                line.startsWith("#EXTINF:") -> { extinf = line; options.clear(); entry = null }
                line.startsWith("#") -> if (extinf != null) options += line
                line.isEmpty() -> {}
                extinf != null -> {
                    entry = finishOptions()
                    extinf = null
                    if (line.startsWith("http") || line.startsWith("rtmp") || line.startsWith("rtsp") || line.equals("null", true)) add(line)
                }
            }
        }
        val out = channels.values.filter { it.streams.isNotEmpty() }.onEach { it.name = displayName(it.name) }
        return Playlist(out, groups.filter { g -> out.any { g in it.groups } }, guides.toList())
    }

    // ---------- kept on disk, asked a page at a time ----------

    private fun folder(data: File) = File(data, "m3u").apply { mkdirs() }
    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80).ifEmpty { "playlist" }
    private fun channelsFile(data: File, name: String) = File(folder(data), safeName(name) + ".jsonl")
    private fun summaryFile(data: File, name: String) = File(folder(data), safeName(name) + ".json")

    /** The playlists read, by their file: kept while the extensions' process runs. */
    private val loaded = object : LinkedHashMap<String, Pair<Long, List<JSONObject>>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<JSONObject>>>?) = size > 3
    }

    /**
     * The playlist [name] of the extension whose data is in [data]: read from [url] (with [headers]) when it's older
     * than [maxAgeS] or wasn't read yet, else as kept. Its summary: how many channels, its groups, its guides.
     */
    fun load(data: File, name: String, url: String, headers: Map<String, String>, maxAgeS: Long, text: String? = null): JSONObject {
        val summary = summaryFile(data, name)
        val kept = runCatching { JSONObject(summary.readText()) }.getOrNull()
        if (text == null && kept != null && kept.optString("url") == url && System.currentTimeMillis() - summary.lastModified() < maxAgeS * 1000) return kept
        val playlist = try {
            // Written by the extension itself (from an account's API), or downloaded.
            if (text != null) read(text.reader().buffered()) else Http.open(url, headers, 30_000).bufferedReader().use { read(it) }
        } catch (e: Exception) {
            if (kept != null) return kept // the one kept, rather than none
            throw e
        }
        val f = channelsFile(data, name)
        val part = File(f.path + ".part")
        part.bufferedWriter().use { w -> playlist.channels.forEach { w.write(it.json().toString()); w.write("\n") } }
        part.renameTo(f)
        synchronized(loaded) { loaded.remove(f.path) }
        val out = JSONObject().put("url", url).put("channels", playlist.channels.size).put("groups", JSONArray(playlist.groups))
            .put("guides", JSONArray(playlist.guides))
        summary.writeText(out.toString())
        return out
    }

    private fun all(data: File, name: String): List<JSONObject> {
        val f = channelsFile(data, name)
        synchronized(loaded) {
            loaded[f.path]?.takeIf { it.first == f.lastModified() }?.let { return it.second }
            if (!f.exists()) throw java.io.IOException("playlist $name not loaded")
            val list = f.bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() }.map { JSONObject(it) }.toList() }
            loaded[f.path] = f.lastModified() to list
            return list
        }
    }

    /** A page of the playlist [name]'s channels: of [group], with [search]'s words in the name, from [skip], [limit] of them. */
    fun channels(data: File, name: String, group: String?, search: String?, skip: Int, limit: Int): JSONArray {
        val words = search?.lowercase()?.split(Regex("""\s+"""))?.filter { it.isNotEmpty() }.orEmpty()
        val page = all(data, name).asSequence()
            .filter { c -> group.isNullOrEmpty() || (c.optJSONArray("groups")?.let { g -> (0 until g.length()).any { g.optString(it) == group } } == true) }
            .filter { c -> words.isEmpty() || c.optString("name").lowercase().let { n -> words.all { it in n } } }
            .drop(skip.coerceAtLeast(0)).take(limit.coerceIn(1, 500)).toList()
        return JSONArray(page)
    }

    /** The channel [id] of the playlist [name], or null. */
    fun channel(data: File, name: String, id: String): JSONObject? = all(data, name).firstOrNull { it.optString("id") == id }

    /** Forgets the playlist [name]. */
    fun forget(data: File, name: String) {
        val f = channelsFile(data, name)
        synchronized(loaded) { loaded.remove(f.path) }
        f.delete()
        summaryFile(data, name).delete()
    }
}
