package link.mccoy88f.zephyr

import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a film is out at home in the TV's country, as TMDB knows it: its release dates there (cinema, digital,
 * disc, TV) and the services that have it. Only what TMDB says for sure counts: anything else is out (a card shows
 * as it is, none is hidden). Kept on disk: those not yet out a day, the others two weeks.
 */
internal object Releases {
    enum class State { CINEMA, COMING, OUT }

    private const val FILE = "tmdb_releases.json"
    private const val OUT_MS = 14 * 24 * 3600_000L
    private const val SOON_MS = 24 * 3600_000L
    const val CINEMA_DAYS = 120L // in cinemas this long at most: older, with no date at home, it's taken to be out

    private class Known(val state: State, val at: Long)

    private val known = ConcurrentHashMap<String, Known>()
    val asking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var file: File? = null

    /** The states known, read from disk the first time. */
    @Synchronized
    fun use(dir: File) {
        if (file != null) return
        file = File(dir, FILE)
        runCatching {
            val o = JSONObject(file!!.readText())
            for (id in o.keys()) o.optJSONObject(id)?.let { e ->
                runCatching { known[id] = Known(State.valueOf(e.getString("s")), e.getLong("at")) }
            }
        }
    }

    /** [id]'s state while it's fresh, else null (not asked yet, or to ask again). */
    fun known(id: String): State? = known[id]?.takeIf { k ->
        System.currentTimeMillis() - k.at < if (k.state == State.OUT) OUT_MS else SOON_MS
    }?.state

    /** Whether a film with [id] isn't out at home yet (in cinemas, or still to come), as known so far. */
    fun unreleased(id: String) = known(id).let { it == State.CINEMA || it == State.COMING }

    /** Films' ids TMDB can be asked about: IMDb's, or TMDB's own ("tmdb:123"). */
    fun askable(id: String) = id.startsWith("tt") || id.startsWith("tmdb:")

    /**
     * The state of each of the films [ids] in [region], asked of TMDB, then kept. Network: off the UI thread. Those
     * TMDB can't be asked about stay unknown.
     */
    fun ask(key: String, ids: List<String>, region: String) {
        val today = LocalDate.now()
        val got = parallel(ids, 6, 30) { id ->
            val tmdb = tmdbId(key, id) ?: return@parallel id to State.OUT
            val movie = JSONObject(TmdbClient.get(key, "/movie/$tmdb?append_to_response=release_dates,watch/providers"))
            id to state(movie, region, today)
        }
        if (got.isEmpty()) return
        val now = System.currentTimeMillis()
        got.forEach { (id, s) -> known[id] = Known(s, now) }
        save()
    }

    private fun tmdbId(key: String, id: String): Long? = when {
        id.startsWith("tmdb:") -> id.removePrefix("tmdb:").substringBefore(':').toLongOrNull()
        else -> Local.get(id)?.takeIf { !it.tv }?.tmdbId ?: JSONObject(TmdbClient.get(key, "/find/$id?external_source=imdb_id"))
            .optJSONArray("movie_results")?.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val o = JSONObject()
            known.forEach { (id, k) -> o.put(id, JSONObject().put("s", k.state.name).put("at", k.at)) }
            val part = File(f.path + ".part")
            part.writeText(o.toString())
            part.renameTo(f)
        }
    }

    /**
     * A film's state in [region] on [today], from TMDB's [movie] with its release dates and services:
     * - out, once a service has it there, or it came out there at home (digital, disc, TV);
     * - in cinemas, when it came out there in cinemas less than CINEMA_DAYS ago;
     * - to come, when its dates there (or, with none there, everywhere) are all ahead;
     * - otherwise out.
     */
    internal fun state(movie: JSONObject, region: String, today: LocalDate): State {
        val services = movie.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(region)
        if (services != null && listOf("flatrate", "free", "ads", "rent", "buy").any { (services.optJSONArray(it)?.length() ?: 0) > 0 }) return State.OUT
        val all = movie.optJSONObject("release_dates")?.optJSONArray("results")
        val dates = ArrayList<Triple<String, Int, LocalDate>>() // country, type, date
        for (i in 0 until (all?.length() ?: 0)) {
            val c = all!!.optJSONObject(i) ?: continue
            val list = c.optJSONArray("release_dates") ?: continue
            for (j in 0 until list.length()) {
                val d = list.optJSONObject(j) ?: continue
                val day = runCatching { LocalDate.parse(d.optString("release_date").take(10)) }.getOrNull() ?: continue
                dates += Triple(c.optString("iso_3166_1"), d.optInt("type"), day)
            }
        }
        val here = dates.filter { it.first == region }
        if (here.any { it.second >= 4 && !it.third.isAfter(today) }) return State.OUT
        val cinemas = here.filter { it.second in 1..3 }
        if (cinemas.isNotEmpty()) {
            val first = cinemas.minOf { it.third }
            return when {
                first.isAfter(today) -> State.COMING
                !first.isBefore(today.minusDays(CINEMA_DAYS)) -> State.CINEMA
                else -> State.OUT
            }
        }
        // Nothing for the country: to come only when nothing came out anywhere yet.
        val primary = runCatching { LocalDate.parse(movie.optString("release_date").take(10)) }.getOrNull()
        val anyOut = dates.any { !it.third.isAfter(today) } || (primary != null && !primary.isAfter(today))
        return if (!anyOut && (primary != null || dates.isNotEmpty())) State.COMING else State.OUT
    }
}
