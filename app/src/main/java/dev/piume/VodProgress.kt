package dev.piume

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * How far into each film and episode the player got: where it resumes, and the Continue Playing cards. The player
 * writes it (in its own process, so a file, not the preferences) and the home screen reads it. Newest first, at
 * most [KEEP]; one watched to the end is taken out (and marked watched: see VodWatched). An episode watched to the
 * end leaves the next one in its place, not begun ([Entry.upNext]): Continue Playing goes on with it.
 */
internal object VodProgress {
    private const val FILE = "vod_progress.json"
    private const val KEEP = 30
    private const val END_MS = 3 * 60_000L // this close to the end, it's watched (the credits)
    private const val MIN_MS = 30_000L // less than this in, there's nothing to resume

    /**
     * One film or episode: its id for the streams ([id], "tt…:1:3"), the title's type and id (for its page), what the
     * card says ([name]: the series' name, [sub]: the episode's season, number and name), its art, and where it got to.
     */
    internal class Entry(val id: String, val type: String, val metaId: String, val name: String, val sub: String, val poster: String?,
                         val position: Long, val duration: Long, val at: Long) {
        val percent get() = if (upNext) 0 else if (duration > 0) (position * 100 / duration).toInt().coerceIn(1, 99) else 0

        /** The next episode, after one watched to the end: not begun yet (see putNext). */
        val upNext get() = position == 0L && duration == 0L
    }

    /** Whether [position] in [duration] is worth coming back to: in a little way, and not at the credits. */
    internal fun resumable(position: Long, duration: Long) = position >= MIN_MS && (duration <= 0 || position < duration - END_MS)

    @Synchronized
    fun all(dir: File): List<Entry> = runCatching {
        val a = JSONArray(File(dir, FILE).readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Entry(o.getString("id"), o.optString("type"), o.optString("metaId"), o.optString("name"), o.optString("sub"),
                o.optString("poster").ifEmpty { null }, o.optLong("position"), o.optLong("duration"), o.optLong("at"))
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.at }

    /** Where [id] got to, if it's worth resuming. */
    fun position(dir: File, id: String): Long? = all(dir).firstOrNull { it.id == id }?.takeIf { resumable(it.position, it.duration) }?.position

    /**
     * Keeps [e] in place of what was there for it, or takes it out once it's watched (at the credits). Hardly begun,
     * what was there stays: where it was before, or the next episode waiting (the first seconds of it don't take it off).
     */
    @Synchronized
    fun put(dir: File, e: Entry) {
        val all = all(dir)
        val rest = all.filter { it.id != e.id }
        when {
            resumable(e.position, e.duration) -> write(dir, (listOf(e) + rest).take(KEEP))
            e.duration > 0 && e.position >= e.duration - END_MS -> write(dir, rest)
        }
    }

    /** [e], the episode after one watched to the end, waiting in Continue Playing (from its start). */
    @Synchronized
    fun putNext(dir: File, e: Entry) {
        val rest = all(dir).filter { it.id != e.id }
        write(dir, (listOf(Entry(e.id, e.type, e.metaId, e.name, e.sub, e.poster, 0L, 0L, e.at)) + rest).take(KEEP))
    }

    /** Whether [position] in [duration] is at the credits: the film or episode is watched. */
    internal fun finished(position: Long, duration: Long) = duration > 0 && position >= duration - END_MS

    @Synchronized
    fun remove(dir: File, id: String) = write(dir, all(dir).filter { it.id != id })

    private fun write(dir: File, list: List<Entry>) {
        val f = File(dir, FILE)
        val part = File(dir, "$FILE.part")
        part.writeText(JSONArray(list.map { e ->
            JSONObject().put("id", e.id).put("type", e.type).put("metaId", e.metaId).put("name", e.name).put("sub", e.sub)
                .put("poster", e.poster.orEmpty()).put("position", e.position).put("duration", e.duration).put("at", e.at)
        }).toString())
        part.renameTo(f)
    }
}

/**
 * The films and episodes watched to the end (or marked so on their pages, or on Trakt), and the series watched whole,
 * by their ids ("tt…" for a film or series, "tt…:1:3" for an episode), each with its title's id, when, and the
 * title's name (for the Watched rows; empty in marks from before it was kept). Written by the player
 * (its own process) and the home screen, so a file; read often (each card's mark), so kept while the file is the same.
 */
internal object VodWatched {
    private const val FILE = "vod_watched.json"
    private const val KEEP = 5000

    internal class Mark(val id: String, val metaId: String, val at: Long, val name: String = "")

    private var cache: Pair<String, Map<String, Mark>>? = null // the file's time and size, to what it holds

    @Synchronized
    fun all(dir: File): Map<String, Mark> {
        val f = File(dir, FILE)
        val stamp = "${f.lastModified()}/${f.length()}"
        cache?.takeIf { it.first == stamp }?.let { return it.second }
        val marks = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Mark(it.getString("id"), it.optString("metaId"), it.optLong("at"), it.optString("name")) }
        }.getOrDefault(emptyList()).associateBy { it.id }
        cache = stamp to marks
        return marks
    }

    fun has(dir: File, id: String) = id in all(dir)

    /** Marks [items] (id to its title's id) watched ([on]) or not, now; [name]: the title's. Newest kept first, at most [KEEP]. */
    @Synchronized
    fun set(dir: File, items: List<Pair<String, String>>, on: Boolean, now: Long = System.currentTimeMillis(), name: String = "") {
        val ids = items.map { it.first }.toSet()
        val rest = all(dir).values.filter { it.id !in ids }
        write(dir, (if (on) items.map { (id, meta) -> Mark(id, meta, now, name) } else emptyList()) + rest)
    }

    /**
     * [marks] from elsewhere (Trakt): those not marked yet added, with their own dates; those marked, newer dates and
     * missing names taken from them.
     */
    @Synchronized
    fun add(dir: File, marks: List<Mark>) {
        val mine = all(dir)
        val fresh = marks.associateBy { it.id }
        val kept = mine.values.map { m ->
            val o = fresh[m.id] ?: return@map m
            Mark(m.id, m.metaId, maxOf(m.at, o.at), m.name.ifEmpty { o.name })
        }
        write(dir, kept + marks.filter { it.id !in mine })
    }

    /** The names of titles whose marks had none (looked up since). */
    @Synchronized
    fun name(dir: File, names: Map<String, String>) {
        if (names.isEmpty()) return
        write(dir, all(dir).values.map { m -> names[m.metaId]?.takeIf { m.name.isEmpty() }?.let { Mark(m.id, m.metaId, m.at, it) } ?: m })
    }

    private fun write(dir: File, marks: List<Mark>) {
        val f = File(dir, FILE)
        val part = File(dir, "$FILE.part")
        part.writeText(JSONArray(marks.sortedByDescending { it.at }.take(KEEP).map {
            JSONObject().put("id", it.id).put("metaId", it.metaId).put("at", it.at).put("name", it.name)
        }).toString())
        part.renameTo(f)
        cache = null
    }

    /**
     * The films watched, and the series with episodes watched, newest first (a series by its last episode): each a
     * title's id, its name and when. A series is told from a film by its episodes' marks.
     */
    fun titles(dir: File): Pair<List<Mark>, List<Mark>> {
        val marks = all(dir).values
        val series = marks.filter { ':' in it.id }.groupBy { it.metaId }
        val films = marks.filter { ':' !in it.id && it.id !in series }.sortedByDescending { it.at }
        val shows = series.map { (meta, eps) ->
            val last = eps.maxBy { it.at }
            Mark(meta, meta, last.at, eps.firstOrNull { it.name.isNotEmpty() }?.name ?: marks.firstOrNull { it.id == meta }?.name.orEmpty())
        }.sortedByDescending { it.at }
        return films to shows
    }
}
