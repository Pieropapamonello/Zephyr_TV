package dev.piume

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * How far into each film and episode the player got: where it resumes, and the Continue Playing cards. The player
 * writes it (in its own process, so a file, not the preferences) and the home screen reads it. Newest first, at
 * most [KEEP]; one watched to the end is taken out.
 */
internal object VodProgress {
    private const val FILE = "vod_progress.json"
    private const val KEEP = 30
    private const val END_MS = 3 * 60_000L // this close to the end, it's watched (the credits)
    private const val MIN_MS = 30_000L // less than this in, there's nothing to resume

    /**
     * One film or episode: its id for the streams ([id], "tt…:1:3"), the title's type and id (for its page), what the
     * card says ([name]: "Show · S1 E3", [sub]: the episode's name), its art, and where it got to.
     */
    internal class Entry(val id: String, val type: String, val metaId: String, val name: String, val sub: String, val poster: String?,
                         val position: Long, val duration: Long, val at: Long) {
        val percent get() = if (duration > 0) (position * 100 / duration).toInt().coerceIn(1, 99) else 0
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

    /** Keeps [e] (in place of what was there for it), or takes it out once it's watched or hardly begun. */
    @Synchronized
    fun put(dir: File, e: Entry) {
        val rest = all(dir).filter { it.id != e.id }
        write(dir, if (resumable(e.position, e.duration)) (listOf(e) + rest).take(KEEP) else rest)
    }

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
