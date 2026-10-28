package link.mccoy88f.zephyr

import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio add-ons, for TV channels and for films and series. The user adds an add-on by its manifest's address.
 *
 * TV: each "tv" catalog (an OMG+ TV configuration's) becomes rows, the whole catalog and one per category (its "genre"
 * options). What the add-on lists is saved in a small file and read again every few minutes, so the rows show at
 * once and what's on stays fresh.
 *
 * Films and series: most add-ons only give streams for a title they're asked about (by its IMDb id, "tt…"), and
 * have no list of their own; the library comes from catalog add-ons, Cinemeta first (Stremio's own, see CINEMETA).
 * Their "movie" and "series" catalogs (the first pages) are saved too, read again every few hours; a title's page
 * (with a series' episodes) is asked when it opens, and its streams from every add-on that has them.
 * Network and disk: off the UI thread.
 */
internal object Stremio {
    private const val MAX_AGE_MS = 5 * 60_000L // the channels are read again after this long ("on now" goes stale)
    private const val RETRY_MS = 5 * 60_000L // and after a failure, not before five minutes
    private const val MAX_PAGES = 40 // a catalog's pages read at most (100 channels each, on OMG)
    private const val VOD_AGE_MS = 6 * 3600_000L // films' and series' catalogs are read again after this long
    private const val VOD_PAGES = 2 // a film or series catalog's pages read (Cinemeta: 50 a page)

    /**
     * Cinemeta's art for the title with this IMDb [id], from its image server (metahub): [kind] "poster/small",
     * "background/medium", "logo/medium"… For titles known by their id alone (watched elsewhere, on Trakt).
     */
    fun art(id: String, kind: String) = "https://images.metahub.space/$kind/$id/img"

    /** Stremio's own catalog add-on: the films' and series' library (IMDb's), always on, its address can be changed (see MainActivity.cinemetaUrl). */
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    /** Nuvio's catalog add-on (TMDB's lists, IMDb ids), offered beside Cinemeta: its address carries its settings. */
    const val NUVIO_BASE = "https://catalog.nuvio.tv/"

    /** Nuvio's manifest in [language] ("it-IT") with [region]'s services ("IT"). */
    fun nuvio(language: String, region: String): String =
        NUVIO_BASE + enc(JSONObject().put("language", language).put("region", region).toString()) + "/manifest.json"

    /**
     * One of the add-on's catalogs of [type] ("tv", "movie", "series", or an add-on's own, kept for its addresses and
     * shown as films: see isVodType), the categories it can be filtered by, whether
     * it can be searched, and whether it can be [listed] (a search-only one can't).
     */
    internal class Catalog(val type: String, val id: String, val name: String, val genres: List<String>, val search: Boolean = false,
                           val listed: Boolean = true)

    /**
     * What an add-on answers: [name] ("stream", "meta", "subtitles", "catalog") for [types], and only for ids that
     * start with one of [prefixes] (null: any id).
     */
    internal class Resource(val name: String, val types: List<String>, val prefixes: List<String>?)

    /**
     * An add-on as its manifest describes it: [url] is the manifest's own address; [catalogs] its TV ones, [vod] its
     * films' and series' ones (each listable without a filter, or searchable).
     */
    internal class Addon(val url: String, val id: String, val name: String, val logo: String?, val catalogs: List<Catalog>,
                         val resources: List<Resource> = emptyList(), val vod: List<Catalog> = emptyList(), val adult: Boolean = false) {
        val base get() = url.removeSuffix("/manifest.json")

        /** Whether the add-on answers [resource] for an item of [type] with this [id] (or any id, when null). */
        fun provides(resource: String, type: String, id: String? = null) = resources.any { r ->
            r.name == resource && type in r.types && (id == null || r.prefixes == null || r.prefixes.any { id.startsWith(it) })
        }

        /** Whether it gives streams for films or series (the add-ons asked when a title plays). */
        val streamsVod get() = resources.any { r -> r.name == "stream" && r.types.any { isVodType(it) } }
    }

    /**
     * A film or series in a catalog: its type and id (IMDb's, for most), name, art, year, genres and rating (IMDb's,
     * "7.5"); what it's about and its TMDB id when the catalog tells (Cinemeta's do; not kept with the shelves).
     */
    internal data class Title(val type: String, val id: String, val name: String, val poster: String?, val background: String?,
                         val logo: String?, val year: String?, val genres: List<String> = emptyList(), val rating: String? = null,
                         val description: String? = null, val tmdbId: Long? = null)

    /** A catalog of films or series, its first pages: what's under the Films or Series tile, or the add-on's own. */
    internal class Shelf(val catalog: Catalog, val titles: List<Title>)

    /** A series' episode, as a title's page lists them: its id for the streams ("tt…:1:3"), season and number, name. */
    internal class Episode(val id: String, val season: Int, val episode: Int, val name: String, val released: String?,
                           val thumbnail: String?, val overview: String?)

    /** A title's page: what it's about and, for a series, its episodes in order (specials, season 0, last). */
    internal class TitleMeta(val title: Title, val description: String?, val genres: List<String>, val rating: String?,
                             val runtime: String?, val episodes: List<Episode>)

    /** A channel in a catalog: what to ask the streams for, its name and logo, what's on (if the add-on says), its categories. */
    internal class Item(val type: String, val id: String, val name: String, val logo: String?, val now: String?, val genres: List<String> = emptyList())

    /** One row: a catalog, whole ([genre] null) or one of its categories. */
    internal class Row(val catalog: Catalog, val genre: String?, val items: List<Item>)

    /** What's saved for an add-on: its manifest and rows, and when it was read ([at]: it tells one reading from the next). */
    internal class Listing(val addon: Addon, val rows: List<Row>, val at: Long = 0)

    /**
     * A way to play a channel or a title: the address, its name ("Rai 1 (2)", "1080p · Film.mkv") and the headers the
     * player must send; [group] is its bingeGroup (the same kind of stream for the next episode).
     */
    internal class Stream(val url: String, val title: String, val headers: Map<String, String>, val group: String? = null,
                          val source: String? = null) // source: the add-on it came from, when streams of several are mixed

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

    // v2: each channel once, rows as lists of ids (an IPTV add-on has thousands, most in two rows or more).
    private fun file(dir: File, url: String) = File(dir, "stremio2_${key(url)}.json")
    private fun failed(dir: File, url: String) = File(dir, "stremio_${key(url)}.failed")

    /** What's saved for the add-on at [url], or null when nothing is yet. Reads the disk the first time. */
    fun saved(dir: File, url: String): Listing? {
        listings[url]?.let { return it }
        val f = file(dir, url)
        if (!f.exists()) return null
        val listing = runCatching { listing(url, JSONObject(f.readText()), f.lastModified()) }.getOrNull() ?: return null
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
            // A catalog that fails leaves the others (an add-on can have TV, films, series or only some): only
            // all of them failing is the add-on failing.
            var failures = 0
            val rows = addon.catalogs.flatMap { c ->
                runCatching { rows(addon, c) }.getOrElse { failures++; warn("Stremio catalog failed: ${Uri.host(url)} ${c.id} ($it)"); emptyList() }
            }.filter { it.items.isNotEmpty() }
            if (failures > 0 && failures == addon.catalogs.size) throw java.io.IOException("every TV catalog failed")
            val items = rows.flatMap { it.items }.distinctBy { it.id }
            val saved = JSONObject().put("manifest", manifest)
                .put("items", JSONArray(items.map { JSONObject().put("type", it.type).put("id", it.id).put("name", it.name)
                    .put("logo", it.logo ?: JSONObject.NULL).put("now", it.now ?: JSONObject.NULL) }))
                .put("rows", JSONArray(rows.map { row ->
                    JSONObject().put("catalog", row.catalog.id).put("genre", row.genre ?: JSONObject.NULL).put("ids", JSONArray(row.items.map { it.id }))
                }))
            val f = file(dir, url)
            val part = File(dir, f.name + ".part")
            part.writeText(saved.toString())
            if (!part.renameTo(f)) throw java.io.IOException("can't save ${f.name}")
            failed(dir, url).delete()
            File(dir, "stremio_${key(url)}.json").delete() // v1's
            if (addon.vod.isNotEmpty() && vodDue(dir, url)) runCatching { updateShelves(dir, addon) }.onFailure { warn("Stremio catalogs failed: ${Uri.host(url)} ($it)") }
            return Listing(addon, rows, f.lastModified()).also { listings = listings + (url to it) }
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
        val vod = ArrayList<Catalog>()
        val list = m.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val type = c.optString("type")
            if (type != "tv" && !isVodType(type)) continue
            val id = c.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val extras = c.optJSONArray("extra")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it) } }.orEmpty()
            val search = extras.any { it.optString("name") == "search" }
            val name = c.optString("name").ifBlank { m.optString("name") }
            // A catalog that can't be listed without a filter (search only, or a required genre) isn't a list: for
            // films and series it can still be searched.
            val listable = extras.filter { it.optBoolean("isRequired") }.all { it.optString("name") == "genre" }
            val genres = extras.firstOrNull { it.optString("name") == "genre" }
                ?.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty()
                .filter { g -> g.any { it.isLetterOrDigit() } }
            if (type != "tv") {
                // A required genre is a list only once one is picked: searched, not listed. Its genres: the search filter's.
                val open = extras.none { it.optBoolean("isRequired") }
                if (open || search) vod += Catalog(type, id, name, genres, search, listed = open)
                continue
            }
            if (!listable) continue
            catalogs += Catalog("tv", id, name, genres, search)
        }
        return Addon(url, m.optString("id").ifBlank { url }, m.optString("name").ifBlank { Uri.host(url) }, text(m, "logo"), catalogs,
            resources(m), vod, adult = m.optJSONObject("behaviorHints")?.optBoolean("adult") == true)
    }

    /**
     * What the add-on answers, from its manifest's resources: each a name ("stream") with the manifest's types and
     * id prefixes, or an object with its own. A catalog add-on's "catalog" isn't one of them (its catalogs say it).
     */
    internal fun resources(m: JSONObject): List<Resource> {
        fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) }.filter { s -> s.isNotEmpty() } }
        val types = strings(m.optJSONArray("types")).orEmpty()
        val prefixes = strings(m.optJSONArray("idPrefixes"))?.takeIf { it.isNotEmpty() }
        val list = m.optJSONArray("resources") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            when (val r = list.opt(i)) {
                is String -> Resource(r, types, prefixes)
                is JSONObject -> r.optString("name").takeIf { it.isNotEmpty() }?.let { name ->
                    // Its own types; without its own prefixes it takes any id (the protocol's rule for the full notation).
                    Resource(name, strings(r.optJSONArray("types")) ?: types, strings(r.optJSONArray("idPrefixes"))?.takeIf { it.isNotEmpty() })
                }
                else -> null
            }
        }
    }

    /**
     * A catalog's rows: the whole of it, then one per category. When its channels say their categories (OMG's do),
     * those rows come from the whole one, without asking again for each; else each category is asked for.
     */
    private fun rows(addon: Addon, c: Catalog): List<Row> {
        val all = items(addon, c, null)
        val known = all.any { it.genres.isNotEmpty() }
        return listOf(Row(c, null, all)) + c.genres.map { g -> Row(c, g, if (known) all.filter { g in it.genres } else items(addon, c, g)) }
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
            val genres = (m.optJSONArray("genre") ?: m.optJSONArray("genres"))?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty()
            Item(m.optString("type").ifBlank { "tv" }, id, name, logo(text(m, "logo") ?: text(m, "poster")), now, genres)
        }
    }

    /**
     * The add-on's logo for a channel, its own placeholder too (it chose it): null only when it isn't a web address
     * or it's cut short ("…/images?q"). Zephyr TV's own (see ChannelLogos) is the fallback, for none or one that won't load.
     */
    internal fun logo(url: String?): String? {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        val query = url.substringAfter('?', "")
        if (url.contains('?') && (query.isEmpty() || !query.contains('='))) return null
        return url
    }

    // ---------- films and series ----------

    // v2: with each title's genres and rating.
    private fun shelfFile(dir: File, url: String) = File(dir, "stremio_vod2_${key(url)}.json")

    /** Whether the add-on's film and series catalogs are missing or a few hours old. */
    fun vodDue(dir: File, url: String): Boolean {
        val f = shelfFile(dir, url)
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > VOD_AGE_MS
    }

    @Volatile private var shelves: Map<String, Pair<Long, List<Shelf>>> = emptyMap()

    /** The add-on's film and series catalogs as last read (their first pages), or none yet. Reads the disk the first time. */
    fun shelves(dir: File, addon: Addon): List<Shelf> {
        val f = shelfFile(dir, addon.url)
        shelves[addon.url]?.takeIf { it.first == f.lastModified() }?.let { return it.second }
        if (!f.exists()) return emptyList()
        val saved = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val list = saved.optJSONArray("shelves") ?: JSONArray()
        val out = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { o ->
            val catalog = addon.vod.firstOrNull { it.type == o.optString("type") && it.id == o.optString("catalog") } ?: return@mapNotNull null
            Shelf(catalog, titles(o))
        }
        shelves = shelves + (addon.url to (f.lastModified() to out))
        return out
    }

    /** Reads the add-on's film and series catalogs again (the listable ones, their first pages) and saves them. */
    fun updateShelves(dir: File, addon: Addon): List<Shelf> {
        val out = addon.vod.filter { it.listed }.mapNotNull { c ->
            val titles = LinkedHashMap<String, Title>()
            for (page in 0 until VOD_PAGES) {
                val extra = if (titles.isEmpty()) "" else "/skip=${titles.size}"
                val metas = runCatching { titles(Http.json("${addon.base}/catalog/${enc(c.type)}/${enc(c.id)}$extra.json")) }.getOrNull() ?: break
                val before = titles.size
                for (t in metas) titles.putIfAbsent(t.id, t)
                if (metas.size < 50 || titles.size == before) break
            }
            if (titles.isEmpty()) null else Shelf(c, titles.values.toList())
        }
        val f = shelfFile(dir, addon.url)
        val part = File(dir, f.name + ".part")
        part.writeText(JSONObject().put("shelves", JSONArray(out.map { shelf ->
            JSONObject().put("type", shelf.catalog.type).put("catalog", shelf.catalog.id).put("metas", JSONArray(shelf.titles.map { t ->
                JSONObject().put("type", t.type).put("id", t.id).put("name", t.name).put("poster", t.poster ?: JSONObject.NULL)
                    .put("background", t.background ?: JSONObject.NULL).put("logo", t.logo ?: JSONObject.NULL).put("releaseInfo", t.year ?: JSONObject.NULL)
                    .put("genres", JSONArray(t.genres)).put("imdbRating", t.rating ?: JSONObject.NULL)
                    .put("description", t.description ?: JSONObject.NULL) // on its page at once, before the add-on answers
            }))
        })).toString())
        if (!part.renameTo(f)) throw java.io.IOException("can't save ${f.name}")
        File(dir, "stremio_vod_${key(addon.url)}.json").delete() // the first version's
        shelves = shelves + (addon.url to (f.lastModified() to out))
        return out
    }

    /**
     * Whether items of [type] are films or series: "movie", "series", or a type of an add-on's own ("Porn", "Anime"),
     * played as a film. Not TV channels, nor Stremio's "channel" (a YouTube channel's videos).
     */
    internal fun isVodType(type: String) = type.isNotBlank() && type != "tv" && type != "channel"

    /** The films or series in a catalog page (or a saved shelf): those with an id, a name and a type of theirs. */
    internal fun titles(page: JSONObject): List<Title> {
        val metas = page.optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).mapNotNull { metas.optJSONObject(it) }.mapNotNull { m -> title(m) }
    }

    private fun title(m: JSONObject, fallbackType: String? = null): Title? {
        val id = m.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = m.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        val type = m.optString("type").ifBlank { fallbackType ?: return null }
        if (!isVodType(type)) return null
        val year = (text(m, "releaseInfo") ?: text(m, "year"))?.take(9)
        val genres = (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty()
            .filter { it.isNotBlank() }
        return Title(type, id, name, logo(text(m, "poster")), logo(text(m, "background")), logo(text(m, "logo")), year, genres, text(m, "imdbRating"),
            text(m, "description"), m.optLong("moviedb_id").takeIf { it > 0 })
    }

    /** The titles of a searchable catalog for [query]. Throws when the add-on can't be asked. */
    fun search(addon: Addon, c: Catalog, query: String): List<Title> =
        titles(Http.json("${addon.base}/catalog/${enc(c.type)}/${enc(c.id)}/search=${enc(query)}.json"))

    /** A title's page from an add-on that has it (Cinemeta, for IMDb ids). Throws when the add-on can't be asked. */
    fun titleMeta(base: String, type: String, id: String): TitleMeta? = titleMeta(Http.json("$base/meta/${enc(type)}/${enc(id)}.json"), type)

    internal fun titleMeta(answer: JSONObject, type: String): TitleMeta? {
        val m = answer.optJSONObject("meta") ?: return null
        val title = title(m, type) ?: return null
        val genres = (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { g -> (0 until g.length()).map { g.optString(it) } }.orEmpty()
            .filter { it.isNotBlank() }
        val list = m.optJSONArray("videos") ?: JSONArray()
        val episodes = if (type != "series") emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { v ->
            val id = v.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val season = v.optInt("season", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val episode = v.optInt("episode", v.optInt("number", 0))
            Episode(id, season, episode, (text(v, "name") ?: text(v, "title")).orEmpty(), text(v, "released"), logo(text(v, "thumbnail")),
                text(v, "overview") ?: text(v, "description"))
        }.distinctBy { it.id }.sortedWith(compareBy({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))
        return TitleMeta(title, text(m, "description"), genres, text(m, "imdbRating"), text(m, "runtime"), episodes)
    }

    /**
     * The ways to play a film or an episode ([id], "tt…:1:3" for an episode) from the add-on at [base] (called [name]), best first as it lists
     * them: direct web addresses only (no torrents), each named by its quality line and its file ("1080p · Film.mkv").
     */
    fun titleStreams(base: String, name: String, type: String, id: String): List<Stream> =
        streams(Http.json("$base/stream/${enc(type)}/${enc(id)}.json", timeout = Http.STREAMS_TIMEOUT_MS), vod = true).map {
            Stream(it.url, it.title, it.headers, it.group, name)
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
    fun streams(base: String, type: String, id: String): List<Stream> =
        streams(Http.json("$base/stream/${enc(type)}/${enc(id)}.json", timeout = Http.STREAMS_TIMEOUT_MS))

    /**
     * The streams in an add-on's answer that have a web address (torrents, YouTube ids and outside links are left
     * out), with the headers they need. A channel's is named by its title's first line; a film's or episode's
     * ([vod]) by its name (the quality, "1080p") and its description's first line (the file).
     */
    internal fun streams(answer: JSONObject, vod: Boolean = false): List<Stream> {
        val list = answer.optJSONArray("streams") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { s ->
            // A web address, or the playlist itself written in it (data:, as some Nuvio plugins hand an HLS one).
            val url = text(s, "url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("data:") }
                ?.let { if (it.startsWith("data://")) "data:" + it.removePrefix("data://") else it } // "data://": a plugin's slip
                ?: return@mapNotNull null
            val hints = s.optJSONObject("behaviorHints")
            val request = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val headers = request?.keys()?.asSequence()?.associateWith { request.optString(it) }?.filterValues { it.isNotEmpty() }.orEmpty()
            val described = text(s, "description") ?: text(s, "title")
            val title = if (!vod) (text(s, "title") ?: text(s, "name") ?: "").lines().first().trim()
                else listOfNotNull(text(s, "name")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" "),
                    described?.lines()?.firstOrNull { it.isNotBlank() }?.trim()).distinct().joinToString(" · ")
            Stream(url, title, headers, hints?.let { text(it, "bingeGroup") })
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
        // The playlist written in its address: nothing to ask, its type says what it is.
        if (url.startsWith("data:")) {
            // By its type, else by its first bytes ("#EXTM3U"): some plugins write a type of their own ("application/m3u8/").
            val meta = url.substringAfter("data:").substringBefore(',')
            val payload = url.substringAfter(',', "").substringBefore('#').take(400) // "#.m3u8": a hint some add, not data
            val head = runCatching {
                if (meta.endsWith(";base64")) java.util.Base64.getMimeDecoder().decode(payload.take(payload.length / 4 * 4))
                else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray()
            }.getOrDefault(ByteArray(0))
            return Probe(true, format(meta.substringBefore(';').trim('/'), "", head))
        }
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
                // A web page, not a video: a site's "please wait" or a link's landing page (some plugins hand those).
                if (c.contentType?.lowercase()?.startsWith("text/html") == true) return Probe(false, null)
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

    private fun listing(url: String, saved: JSONObject, at: Long): Listing {
        val addon = addon(url, saved.getJSONObject("manifest"))
        val list = saved.optJSONArray("items") ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.associate {
            it.optString("id") to Item(it.optString("type"), it.optString("id"), it.optString("name"), text(it, "logo"), text(it, "now"))
        }
        val rows = saved.optJSONArray("rows") ?: JSONArray()
        return Listing(addon, (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { r ->
            val catalog = addon.catalogs.firstOrNull { it.id == r.optString("catalog") } ?: return@mapNotNull null
            val ids = r.optJSONArray("ids") ?: JSONArray()
            Row(catalog, text(r, "genre"), (0 until ids.length()).mapNotNull { items[ids.optString(it)] })
        }, at)
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
