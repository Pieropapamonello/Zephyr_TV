package dev.plume

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.InputStream
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
    val genreIds: List<Int>,
    val releaseDate: String?,
)

object TmdbClient {
    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w1280"

    private val GENRES = mapOf(
        28 to "Azione", 12 to "Avventura", 16 to "Animazione", 35 to "Commedia", 80 to "Crime",
        99 to "Documentario", 18 to "Dramma", 10751 to "Famiglia", 14 to "Fantasy", 36 to "Storia",
        27 to "Horror", 10402 to "Musica", 9648 to "Mistero", 10749 to "Romance", 878 to "Fantascienza",
        10770 to "Film TV", 53 to "Thriller", 10752 to "Guerra", 37 to "Western",
        10759 to "Azione & Avventura", 10762 to "Bambini", 10763 to "News", 10764 to "Reality",
        10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "Guerra & Politica"
    )

    fun fetchTrending(apiKey: String, language: String = "it-IT", mediaType: String = "all"): List<TmdbItem> {
        val type = if (mediaType in setOf("movie", "tv")) mediaType else "all"
        val endpoint = "$BASE_URL/trending/$type/day?api_key=$apiKey&language=$language"
        val json = try {
            JSONObject(get(endpoint))
        } catch (e: Exception) {
            return emptyList()
        }
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = ArrayList<TmdbItem>()
        for (i in 0 until results.length()) {
            val obj = results.getJSONObject(i)
            val title = obj.optString("title").ifEmpty { obj.optString("name") }
            if (title.isBlank()) continue
            val genreArray = obj.optJSONArray("genre_ids")
            val genreIds = ArrayList<Int>()
            if (genreArray != null) {
                for (g in 0 until genreArray.length()) genreIds.add(genreArray.getInt(g))
            }
            list.add(
                TmdbItem(
                    id = obj.optLong("id"),
                    title = title,
                    overview = obj.optString("overview"),
                    backdropPath = obj.optString("backdrop_path").takeIf { it.isNotBlank() },
                    voteAverage = obj.optDouble("vote_average", 0.0),
                    mediaType = obj.optString("media_type", type),
                    genreIds = genreIds,
                    releaseDate = obj.optString("release_date").ifEmpty { obj.optString("first_air_date") }
                )
            )
        }
        return list
    }

    fun backdropUrl(path: String?): String? = if (path.isNullOrBlank()) null else "$IMAGE_BASE$path"

    fun genreName(id: Int): String? = GENRES[id]

    fun searchIntent(context: Context, pkg: String, query: String): Intent {
        val pm = context.packageManager
        // Specialized intents where known
        when (pkg) {
            "com.stremio.one" -> {
                val stremioUri = Uri.parse("stremio:///search?search=" + Uri.encode(query))
                Intent(Intent.ACTION_VIEW, stremioUri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(pm) != null }?.let { return it }
            }
            "com.netflix.ninja" -> {
                Intent("android.intent.action.SEARCH").apply {
                    setPackage(pkg)
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(pm) != null }?.let { return it }
            }
            "com.google.android.youtube.tv" -> {
                val ytUri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
                Intent(Intent.ACTION_VIEW, ytUri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.takeIf { it.resolveActivity(pm) != null }?.let { return it }
            }
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

    private fun get(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json")
        }
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }
}
