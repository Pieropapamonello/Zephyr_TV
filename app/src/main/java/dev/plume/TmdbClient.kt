package dev.plume

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class TmdbItem(
    val id: Long,
    val title: String,
    val overview: String,
    val backdropPath: String?,
    val voteAverage: Double,
    val mediaType: String, // "movie" or "tv"
    val genres: List<String>, // names in the language the list was fetched in
    val releaseDate: String?,
)

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w1280"

    /** Languages offered in settings; the device's own one is picked when it's among them. */
    val LANGUAGES = listOf("it-IT", "en-US", "es-ES", "fr-FR", "de-DE")

    fun defaultLanguage(): String {
        val device = java.util.Locale.getDefault().language
        return LANGUAGES.firstOrNull { it.substringBefore('-') == device } ?: "en-US"
    }

    // Genre names per language ("it-IT" -> id -> name), fetched once: TMDB only sends ids with each title.
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, String>>()

    fun fetchTrending(apiKey: String, language: String, mediaType: String = "all"): List<TmdbItem> {
        val type = if (mediaType in setOf("movie", "tv")) mediaType else "all"
        val json = try {
            JSONObject(get(apiKey, "/trending/$type/day?language=$language"))
        } catch (e: Exception) {
            android.util.Log.w("Plume", "TMDB trending failed ($e)")
            return emptyList()
        }
        val genres = genres(apiKey, language)
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = ArrayList<TmdbItem>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val title = obj.optString("title").ifEmpty { obj.optString("name") }
            if (title.isBlank()) continue
            val genreArray = obj.optJSONArray("genre_ids")
            val names = ArrayList<String>()
            if (genreArray != null) {
                for (g in 0 until genreArray.length()) genres[genreArray.getInt(g)]?.let { names.add(it) }
            }
            list.add(
                TmdbItem(
                    id = obj.optLong("id"),
                    title = title,
                    overview = obj.optString("overview"),
                    backdropPath = obj.optString("backdrop_path").takeIf { it.isNotBlank() },
                    voteAverage = obj.optDouble("vote_average", 0.0),
                    mediaType = obj.optString("media_type", type),
                    genres = names,
                    releaseDate = obj.optString("release_date").ifEmpty { obj.optString("first_air_date") }
                )
            )
        }
        return list
    }

    /** Movie and TV genres together (their ids don't clash). Empty on failure, so titles just show no genre. */
    private fun genres(apiKey: String, language: String): Map<Int, String> = genreCache[language] ?: try {
        val map = HashMap<Int, String>()
        for (kind in listOf("movie", "tv")) {
            val arr = JSONObject(get(apiKey, "/genre/$kind/list?language=$language")).getJSONArray("genres")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { map[it.getInt("id")] = it.getString("name") }
        }
        map.also { genreCache[language] = it }
    } catch (e: Exception) {
        android.util.Log.w("Plume", "TMDB genres failed ($e)")
        emptyMap()
    }

    fun backdropUrl(path: String?): String? = if (path.isNullOrBlank()) null else "$IMAGE_BASE$path"

    // Nuvio's builds: GitHub ("full") and Play Store. Both take nuvio://tmdb/<type>/<id> and have no search link
    // (their stremio:// handler only installs addons).
    private val NUVIO = setOf("com.nuvio.tv", "com.nuvio.app")
    private const val STREMIO = "com.stremio.one"
    private const val YOUTUBE = "com.google.android.youtube.tv"

    // TMDB id -> IMDb id (or "" when TMDB has none), so a second press doesn't ask again.
    private val imdbCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // TMDB id -> YouTube key of its best trailer (or "" when there's none), per language.
    private val trailerCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Apps whose title links need something looked up first: Stremio the IMDb id (its catalogue, Cinemeta, is
     * keyed by it), YouTube the trailer TMDB lists for the title.
     */
    fun needsLookup(pkg: String) = pkg == STREMIO || pkg == YOUTUBE

    /** What [openIntent] needs for [pkg] as its `ref`, or null. Network: call it off the UI thread. */
    fun lookup(apiKey: String, pkg: String, item: TmdbItem, language: String): String? = when (pkg) {
        STREMIO -> imdbId(apiKey, item)
        YOUTUBE -> trailerKey(apiKey, item, language)
        else -> null
    }

    /** A video TMDB lists for a title: the same trailers and teasers its website shows. */
    class Video(val key: String, val site: String, val type: String, val official: Boolean, val language: String, val published: String)

    /**
     * The trailer to play: YouTube only (the TV app plays those), a trailer before a teaser, in [language] before
     * English before anything else, official before fan-made, newest first. Null when there's no trailer or teaser.
     */
    fun bestTrailer(videos: List<Video>, language: String): Video? {
        val lang = language.substringBefore('-')
        return videos.filter { it.site == "YouTube" && it.key.isNotBlank() && (it.type == "Trailer" || it.type == "Teaser") }
            .sortedWith(compareBy<Video>(
                { if (it.type == "Trailer") 0 else 1 },
                { when (it.language) { lang -> 0; "en" -> 1; else -> 2 } },
                { if (it.official) 0 else 1 },
            ).thenByDescending { it.published })
            .firstOrNull()
    }

    /** The YouTube key of the title's trailer, or null. Network: call it off the UI thread. */
    private fun trailerKey(apiKey: String, item: TmdbItem, language: String): String? {
        val key = "${item.mediaType}/${item.id}/$language"
        trailerCache[key]?.let { return it.ifEmpty { null } }
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val lang = language.substringBefore('-')
        val videos = try {
            // Videos in the chosen language plus English ones, as a fallback when there's no dubbed trailer.
            val list = JSONObject(get(apiKey, "/$type/${item.id}/videos?language=$language&include_video_language=$lang,en"))
                .optJSONArray("results")
            (0 until (list?.length() ?: 0)).map { i ->
                val v = list!!.getJSONObject(i)
                Video(v.optString("key"), v.optString("site"), v.optString("type"), v.optBoolean("official"),
                    v.optString("iso_639_1"), v.optString("published_at"))
            }
        } catch (e: Exception) {
            android.util.Log.w("Plume", "TMDB videos failed ($e)")
            return null // not cached: a network hiccup shouldn't stick
        }
        val best = bestTrailer(videos, language)?.key
        trailerCache[key] = best.orEmpty()
        return best
    }

    /** The title's IMDb id ("tt…"), or null. Network: call it off the UI thread. */
    private fun imdbId(apiKey: String, item: TmdbItem): String? {
        val key = "${item.mediaType}/${item.id}"
        imdbCache[key]?.let { return it.ifEmpty { null } }
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val id = try {
            JSONObject(get(apiKey, "/$type/${item.id}/external_ids")).optString("imdb_id").takeIf { it.startsWith("tt") }
        } catch (e: Exception) {
            android.util.Log.w("Plume", "TMDB external ids failed ($e)")
            return null // not cached: a network hiccup shouldn't stick
        }
        imdbCache[key] = id.orEmpty()
        return id
    }

    /**
     * Opens [item] in [pkg]: on its own page where the app takes an id (Nuvio: TMDB id; Stremio: the IMDb id in
     * [ref]); on YouTube the trailer TMDB lists ([ref], its video key), or a search for one in [language] when it
     * lists none; otherwise through the app's search by title, otherwise the app itself. [ref] comes from [lookup].
     */
    fun openIntent(context: Context, pkg: String, item: TmdbItem, ref: String?, language: String): Intent {
        val pm = context.packageManager
        fun view(uri: String) = viewIn(pm, pkg, uri)
        val series = item.mediaType == "tv"
        when {
            pkg in NUVIO -> view("nuvio://tmdb/${if (series) "series" else "movie"}/${item.id}")?.let { return it }
            // Movies open on the title (the video id is the title's own id); series on their episode list.
            pkg == STREMIO && ref != null ->
                view(if (series) "stremio:///detail/series/$ref" else "stremio:///detail/movie/$ref/$ref")?.let { return it }
            // YouTube's own scheme first, then the plain watch link; both open the video in the TV app.
            pkg == YOUTUBE && ref != null ->
                (view("vnd.youtube:$ref") ?: view("https://www.youtube.com/watch?v=$ref"))?.let { return it }
        }
        return searchIntent(context, pkg, if (isYouTube(pkg)) trailerQuery(item.title, language) else item.title)
    }

    /** YouTube's Spotlight button is a trailer button: it plays TMDB's trailer, or searches "<title> trailer <language>". */
    fun isYouTube(pkg: String) = pkg == YOUTUBE

    /** "Dune trailer italiano": the language named in itself, so YouTube ranks the dubbed or subtitled cut first. */
    fun trailerQuery(title: String, language: String): String {
        val name = when (language.substringBefore('-')) {
            "it" -> "italiano"
            "es" -> "español"
            "fr" -> "français"
            "de" -> "deutsch"
            else -> "english"
        }
        return "$title trailer $name"
    }

    /** An ACTION_VIEW of [uri] for [pkg] only, or null when the app doesn't take that link. */
    private fun viewIn(pm: android.content.pm.PackageManager, pkg: String, uri: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.takeIf { it.resolveActivity(pm) != null }

    private fun searchIntent(context: Context, pkg: String, query: String): Intent {
        val pm = context.packageManager
        fun view(uri: String) = viewIn(pm, pkg, uri)
        val q = Uri.encode(query)
        // Specialized intents where known
        when (pkg) {
            STREMIO -> view("stremio:///search?search=$q")?.let { return it } // documented in Stremio's addon SDK
            "com.netflix.ninja" -> {
                Intent("android.intent.action.SEARCH").apply {
                    setPackage(pkg)
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(pm) != null }?.let { return it }
            }
            YOUTUBE -> view("https://www.youtube.com/results?search_query=$q")?.let { return it }
        }

        // Generic search intent
        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(pkg)
            putExtra("query", query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (searchIntent.resolveActivity(pm) != null) return searchIntent

        // Fallback: regular launch intent for the package
        return pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Accepts either kind of TMDB credential: a v4 read access token (a long JWT, sent as a Bearer header,
     * so it stays out of the URL) or a classic 32-character v3 API key (which TMDB only takes as a parameter).
     */
    private fun get(apiKey: String, path: String): String {
        val bearer = apiKey.length > 40
        val url = if (bearer) BASE_URL + path
            else BASE_URL + path + (if ('?' in path) "&" else "?") + "api_key=" + Uri.encode(apiKey)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json")
            if (bearer) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }
}
