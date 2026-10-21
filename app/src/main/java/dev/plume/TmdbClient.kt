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

    // TMDB id -> IMDb id (or "" when TMDB has none), so a second press doesn't ask again.
    private val imdbCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Apps whose title links need the IMDb id: Stremio's catalogue (Cinemeta) is keyed by it. */
    fun wantsImdb(pkg: String) = pkg == STREMIO

    /** The title's IMDb id ("tt…"), or null. Network: call it off the UI thread. */
    fun imdbId(apiKey: String, item: TmdbItem): String? {
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
     * Opens [item] in [pkg]: on its own page where the app takes an id (Nuvio: TMDB id; Stremio: [imdb], from
     * [imdbId]), otherwise through the app's search by title, otherwise the app itself.
     */
    fun openIntent(context: Context, pkg: String, item: TmdbItem, imdb: String?): Intent {
        val pm = context.packageManager
        fun view(uri: String) = viewIn(pm, pkg, uri)
        val series = item.mediaType == "tv"
        when {
            pkg in NUVIO -> view("nuvio://tmdb/${if (series) "series" else "movie"}/${item.id}")?.let { return it }
            // Movies open on the title (the video id is the title's own id); series on their episode list.
            pkg == STREMIO && imdb != null ->
                view(if (series) "stremio:///detail/series/$imdb" else "stremio:///detail/movie/$imdb/$imdb")?.let { return it }
        }
        return searchIntent(context, pkg, item.title)
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
            "com.google.android.youtube.tv" -> view("https://www.youtube.com/results?search_query=$q")?.let { return it }
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
