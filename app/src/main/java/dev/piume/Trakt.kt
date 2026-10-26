package dev.piume

import java.io.File
import java.net.URLEncoder
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trakt (trakt.tv), each Piume user's own account: what they watch is scrobbled as it plays (see PlayerActivity),
 * what they mark watched on a page goes to their history, and what they watched elsewhere and where they paused
 * comes back into Piume (see MainActivity.syncTrakt).
 *
 * Signing in happens on a phone: the TV shows a QR code of Trakt's authorize page, whose answer goes to
 * mccoy88f.link/traktauth. That site holds the app's client secret (Trakt wants it for every token, and an app's
 * own copy would be anyone's), swaps the code for the tokens and keeps them a few minutes for the TV, which asks for
 * them by the random state it put in the QR code. Tokens are refreshed through the site too.
 *
 * Kept in a file in the user's folder (the player, in its own process, scrobbles with it). Network: off the UI thread.
 */
internal object Trakt {
    const val CLIENT_ID = "0TPZx8EN9N4gVqDp4QDbegDyKkcgLO9BDC1lavq9WWY"
    private const val SITE = "https://www.mccoy88f.link"
    private const val REDIRECT = "$SITE/traktauth"
    private const val API = "https://api.trakt.tv"
    private const val FILE = "trakt.json"
    private const val REFRESH_BEFORE_MS = 24 * 3600_000L // a token about to expire is refreshed this long before

    /** A signed-in account: its tokens, when the access token expires, its Trakt name, and when it was last synced. */
    internal class Account(val access: String, val refresh: String, val expires: Long, val user: String, val synced: Long = 0)

    /** A random state for one sign-in: it ties the phone's answer, kept by the site, to this TV. */
    fun newState(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    /** The sign-in for the QR code: the site sends the phone on to Trakt's page to sign in and allow Piume (short, for a QR code). */
    fun signInUrl(state: String) = "$REDIRECT?start=${enc(state)}"

    @Synchronized
    fun account(dir: File): Account? = runCatching {
        val o = JSONObject(File(dir, FILE).readText())
        Account(o.getString("access"), o.getString("refresh"), o.getLong("expires"), o.optString("user"), o.optLong("synced"))
    }.getOrNull()

    @Synchronized
    private fun save(dir: File, a: Account) {
        dir.mkdirs()
        val part = File(dir, "$FILE.part")
        part.writeText(JSONObject().put("access", a.access).put("refresh", a.refresh).put("expires", a.expires)
            .put("user", a.user).put("synced", a.synced).toString())
        part.renameTo(File(dir, FILE))
    }

    fun forget(dir: File) = File(dir, FILE).delete()

    fun synced(dir: File, at: Long) = account(dir)?.let { save(dir, Account(it.access, it.refresh, it.expires, it.user, at)) }

    private fun tokens(o: JSONObject, user: String, synced: Long = 0) = Account(o.getString("access_token"), o.getString("refresh_token"),
        (o.optLong("created_at", System.currentTimeMillis() / 1000) + o.optLong("expires_in", 7 * 86400)) * 1000, user, synced)

    /**
     * Whether the sign-in with [state] is done: true once the account is saved in [dir] (with its Trakt name), null
     * while the phone hasn't answered yet, false when the site no longer knows [state] (expired, or taken).
     */
    fun poll(dir: File, state: String): Boolean? {
        val (code, body) = Http.send("$SITE/api/trakt/poll", JSONObject().put("state", state))
        return when (code) {
            200 -> {
                val a = tokens(JSONObject(body), "")
                val name = runCatching {
                    JSONObject(request(a.access, "GET", "/users/settings", null).second).getJSONObject("user").optString("username")
                }.getOrDefault("")
                save(dir, Account(a.access, a.refresh, a.expires, name))
                true
            }
            202 -> null
            else -> false
        }
    }

    /** The account's access token, refreshed (through the site) when it's about to expire; null when signed out. */
    private fun token(dir: File): String? {
        val a = account(dir) ?: return null
        if (System.currentTimeMillis() < a.expires - REFRESH_BEFORE_MS) return a.access
        val (code, body) = runCatching { Http.send("$SITE/api/trakt/refresh", JSONObject().put("refresh_token", a.refresh)) }
            .getOrElse { return a.access } // offline: the old one may still work
        if (code == 401 || code == 400) { forget(dir); return null } // revoked: signed out
        if (code !in 200..299) return a.access
        val fresh = tokens(JSONObject(body), a.user, a.synced)
        save(dir, fresh)
        return fresh.access
    }

    private fun request(token: String, method: String, path: String, body: JSONObject?): Pair<Int, String> {
        val headers = mapOf("trakt-api-version" to "2", "trakt-api-key" to CLIENT_ID, "Authorization" to "Bearer $token")
        if (method == "GET") return runCatching { 200 to Http.text(API + path, headers + ("Accept" to "application/json")) }.getOrElse { 0 to "" }
        return Http.send(API + path, body, headers, method)
    }

    /** [path] asked as the account in [dir]; null when signed out. */
    private fun call(dir: File, method: String, path: String, body: JSONObject? = null): Pair<Int, String>? =
        token(dir)?.let { request(it, method, path, body) }

    // ---------- what Piume tells Trakt ----------

    /** A film ([id] its IMDb id) or an episode ("tt…:1:3", [metaId] its series'), as Trakt names it in a scrobble. */
    private fun item(id: String, metaId: String): JSONObject {
        val parts = id.split(':')
        if (parts.size < 3) return JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("imdb", id)))
        return JSONObject().put("show", JSONObject().put("ids", JSONObject().put("imdb", metaId)))
            .put("episode", JSONObject().put("season", parts[1].toIntOrNull() ?: 0).put("number", parts[2].toIntOrNull() ?: 0))
    }

    /**
     * The player's news: "start" (playing, or again after a pause), "pause", "stop" (left or ended; from 80% on,
     * Trakt counts it watched). [progress] in percent. Quietly nothing when signed out or offline.
     */
    fun scrobble(dir: File, action: String, id: String, metaId: String, progress: Double) {
        if (!id.startsWith("tt")) return
        runCatching { call(dir, "POST", "/scrobble/$action", item(id, metaId).put("progress", progress.coerceIn(0.0, 100.0))) }
    }

    /**
     * Films ("tt…") and episodes ("tt…:1:3", with their series' id) added to the history ([on]) or taken out of it,
     * as [items] (an id to its title's id). Quietly nothing when signed out or offline.
     */
    fun history(dir: File, items: List<Pair<String, String>>, on: Boolean) {
        val movies = JSONArray()
        val shows = LinkedHashMap<String, LinkedHashMap<Int, JSONArray>>()
        for ((id, metaId) in items.filter { it.first.startsWith("tt") }) {
            val parts = id.split(':')
            if (parts.size < 3) movies.put(JSONObject().put("ids", JSONObject().put("imdb", id)))
            else shows.getOrPut(metaId) { LinkedHashMap() }.getOrPut(parts[1].toIntOrNull() ?: continue) { JSONArray() }
                .put(JSONObject().put("number", parts[2].toIntOrNull() ?: continue))
        }
        val showList = JSONArray(shows.map { (metaId, seasons) -> JSONObject().put("ids", JSONObject().put("imdb", metaId))
            .put("seasons", JSONArray(seasons.map { (n, eps) -> JSONObject().put("number", n).put("episodes", eps) })) })
        if (movies.length() == 0 && showList.length() == 0) return
        runCatching { call(dir, "POST", if (on) "/sync/history" else "/sync/history/remove", JSONObject().put("movies", movies).put("shows", showList)) }
    }

    // ---------- what Trakt tells Piume ----------

    /** Films and episodes watched, as VodWatched keeps them: an id to its title's id. Null when Trakt can't be asked. */
    fun watched(dir: File): List<Pair<String, String>>? {
        val movies = call(dir, "GET", "/sync/watched/movies")?.takeIf { it.first == 200 } ?: return null
        val shows = call(dir, "GET", "/sync/watched/shows")?.takeIf { it.first == 200 } ?: return null
        return parseWatched(JSONArray(movies.second), JSONArray(shows.second))
    }

    internal fun parseWatched(movies: JSONArray, shows: JSONArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (m in objects(movies)) imdb(m.optJSONObject("movie"))?.let { out += it to it }
        for (s in objects(shows)) {
            val show = imdb(s.optJSONObject("show")) ?: continue
            val reset = s.optString("reset_at").takeIf { it.isNotBlank() && it != "null" } // a rewatch began: earlier ones don't count
            for (season in objects(s.optJSONArray("seasons"))) for (e in objects(season.optJSONArray("episodes"))) {
                if (reset != null && e.optString("last_watched_at") < reset) continue
                out += "$show:${season.optInt("number")}:${e.optInt("number")}" to show
            }
        }
        return out
    }

    /** Where a film or episode was paused, elsewhere or here, as Trakt keeps it. */
    internal class Paused(val id: String, val metaId: String, val series: Boolean, val progress: Double, val runtimeMin: Int, val at: Long,
                          val title: String, val season: Int, val number: Int, val episodeTitle: String)

    fun paused(dir: File): List<Paused>? {
        val (code, body) = call(dir, "GET", "/sync/playback?extended=full") ?: return null
        return if (code == 200) parsePaused(JSONArray(body)) else null
    }

    internal fun parsePaused(list: JSONArray): List<Paused> = objects(list).mapNotNull { p ->
        val at = runCatching { java.time.Instant.parse(p.optString("paused_at")).toEpochMilli() }.getOrDefault(0L)
        val progress = p.optDouble("progress", 0.0)
        if (p.optString("type") == "episode") {
            val show = p.optJSONObject("show") ?: return@mapNotNull null
            val e = p.optJSONObject("episode") ?: return@mapNotNull null
            val id = imdb(show) ?: return@mapNotNull null
            Paused("$id:${e.optInt("season")}:${e.optInt("number")}", id, true, progress, e.optInt("runtime", show.optInt("runtime")), at,
                show.optString("title"), e.optInt("season"), e.optInt("number"), e.optString("title").takeIf { it != "null" }.orEmpty())
        } else {
            val m = p.optJSONObject("movie") ?: return@mapNotNull null
            val id = imdb(m) ?: return@mapNotNull null
            Paused(id, id, false, progress, m.optInt("runtime"), at, m.optString("title"), 0, 0, "")
        }
    }

    private fun objects(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

    private fun imdb(o: JSONObject?) = o?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
