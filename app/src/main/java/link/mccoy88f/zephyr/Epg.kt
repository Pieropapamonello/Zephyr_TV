package link.mccoy88f.zephyr

import android.util.Xml
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * A TV guide from the internet, for TVs that keep their own to themselves (Android lets only system apps read the
 * programs in the TV listings). Any XMLTV file, gzipped or not, at an address set in Zephyr TV settings: read as a
 * stream, keeping the next two days, into a small file fetched again once a day. Channels are matched to it by
 * name, the same way as their logos (see ChannelLogos). Network and disk: off the UI thread.
 */
internal object Epg {
    private const val MAX_AGE_MS = 24 * 3600_000L // the file is fetched again after this long (at least a day)
    private const val RETRY_MS = 3600_000L // and after a failure, not before an hour
    private const val KEEP_MS = 48 * 3600_000L // how far ahead programs are kept (two days)

    /** One program: when it's on, its title and what it's about. */
    internal class Show(val start: Long, val stop: Long, val title: String, val desc: String = "")

    /** The guide as read: XMLTV channel id to its programs in time order, and the names to find those ids by. */
    internal class Guide(val shows: Map<String, List<Show>>, val names: Map<String, String>)

    @Volatile private var guide: Pair<String, Guide>? = null // the address it came from, and the guide
    private val updating = java.util.concurrent.atomic.AtomicBoolean()

    private fun file(dir: File) = File(dir, "epg.tsv")
    private fun failed(dir: File) = File(dir, "epg.failed")

    /** The saved guide for [url], or null when there's none yet (or it's from another address). */
    fun saved(dir: File, url: String): Guide? {
        guide?.takeIf { it.first == url }?.let { return it.second }
        val f = file(dir)
        if (!f.exists()) return null
        val lines = f.readLines()
        if (lines.firstOrNull() != url) return null
        val names = ArrayList<Pair<List<String>, String>>()
        val shows = HashMap<String, MutableList<Show>>()
        for (line in lines.drop(1)) {
            val p = line.split('\t')
            when {
                p.size >= 3 && p[0] == "C" -> names += p.drop(2) to p[1]
                p.size >= 5 && p[0] == "P" -> shows.getOrPut(p[1]) { ArrayList() } += Show(p[2].toLong(), p[3].toLong(), p[4], p.getOrNull(5) ?: "")
            }
        }
        return Guide(shows, ChannelLogos.table(names)).also { guide = url to it }
    }

    /** Whether the guide for [url] is missing or half a day old, and a failed try isn't too recent to try again. */
    fun due(dir: File, url: String): Boolean {
        val now = System.currentTimeMillis()
        val f = file(dir)
        if (f.exists() && now - f.lastModified() < MAX_AGE_MS && f.useLines { it.firstOrNull() } == url) return false
        val fail = failed(dir)
        return !(fail.exists() && now - fail.lastModified() < RETRY_MS && fail.readText() == url)
    }

    /**
     * Fetches and saves the guide at [url]: how many channels it has programs for in the next day (0: none, an
     * out-of-date guide), or null when it couldn't (or another update is running).
     */
    fun update(dir: File, url: String): Int? {
        if (!updating.compareAndSet(false, true)) return null
        try {
            val now = System.currentTimeMillis()
            val (names, shows) = Http.open(url).use { read(it, now - 3 * 3600_000L, now + KEEP_MS) }
            val f = file(dir)
            val part = File(dir, f.name + ".part")
            part.bufferedWriter().use { w ->
                w.write(url)
                w.newLine()
                for ((id, all) in names) w.write("C\t$id\t${all.joinToString("\t")}\n")
                for ((id, list) in shows) for (s in list) w.write("P\t$id\t${s.start}\t${s.stop}\t${s.title}\t${s.desc}\n")
            }
            if (!part.renameTo(f)) throw java.io.IOException("can't save ${f.name}")
            failed(dir).delete()
            guide = url to Guide(shows, ChannelLogos.table(names.map { (id, all) -> all to id }))
            return shows.size
        } catch (e: Exception) {
            warn("TV guide download failed ($e)")
            failed(dir).apply { writeText(url); setLastModified(System.currentTimeMillis()) }
            return null
        } finally {
            updating.set(false)
        }
    }

    /** The channels (id to display names) and their programs between [from] and [to], from an XMLTV stream, gzipped or not. */
    internal fun read(input: InputStream, from: Long, to: Long): Pair<Map<String, List<String>>, Map<String, List<Show>>> {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
        buffered.reset()
        val stream = if (gzip) GZIPInputStream(buffered) else buffered
        val parser = Xml.newPullParser().apply { setInput(stream, null) }
        val names = LinkedHashMap<String, MutableList<String>>()
        val shows = HashMap<String, MutableList<Show>>()
        var channel: String? = null // inside <channel id=…>
        var show: Triple<String, Long, Long>? = null // inside a <programme> worth keeping: its channel, start, stop
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG) {
                if (parser.name == "channel") channel = null
                if (parser.name == "programme") show = null
                continue
            }
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "channel" -> channel = parser.getAttributeValue(null, "id")?.also { id ->
                    // The id names the channel too, with the country after it: "Rai1.it", "Canale.5.it".
                    id.replace(Regex("\\.[a-z]{2}$"), "").replace('.', ' ').trim()
                        .takeIf { it.isNotEmpty() }?.let { names.getOrPut(id) { ArrayList() } += it }
                }
                "display-name" -> channel?.let { id ->
                    val name = parser.nextText().trim()
                    val list = names.getOrPut(id) { ArrayList() }
                    if (name.isNotEmpty()) list += name
                    // Also without what some guides add: the channel number after it ("Rai 1 HD  101"), the
                    // country before it ("IT - Canale 5").
                    val bare = name.replace(Regex("\\s{2,}\\d+$"), "").replace(Regex("^[A-Z]{2}\\s*-\\s+"), "")
                    if (bare != name && bare.isNotEmpty()) list += bare
                }
                "programme" -> {
                    val id = parser.getAttributeValue(null, "channel")
                    val start = time(parser.getAttributeValue(null, "start"))
                    val stop = time(parser.getAttributeValue(null, "stop"))
                    show = if (id != null && start != null && stop != null && stop > from && start < to) Triple(id, start, stop) else null
                }
                "title" -> show?.let { (id, start, stop) ->
                    val title = flat(parser.nextText())
                    // The first title only (others are translations).
                    if (title.isNotEmpty() && shows[id]?.lastOrNull()?.start != start) shows.getOrPut(id) { ArrayList() } += Show(start, stop, title)
                }
                "desc" -> show?.let { (id, start, _) ->
                    val list = shows[id] ?: return@let
                    val last = list.last()
                    // The first description only, cut short: a card's page shows a few lines of it.
                    if (last.start == start && last.desc.isEmpty()) list[list.size - 1] = Show(last.start, last.stop, last.title, flat(parser.nextText()).take(600))
                }
            }
        }
        shows.values.forEach { list -> list.sortBy { it.start } }
        return names to shows
    }

    /** On one line, for the saved file's tab-separated rows. */
    private fun flat(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    /** An XMLTV time ("20261020051000 +0000"), or null. */
    internal fun time(s: String?): Long? {
        if (s == null) return null
        val t = s.trim()
        return runCatching {
            if (t.length > 14) SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(t)?.time
            else SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(t)?.time
        }.getOrNull()
    }

    /** What's on now on the channel called [name], and how far in it is (1 to 100), or null. */
    fun now(guide: Guide, name: String, at: Long = System.currentTimeMillis()): Pair<String, Int>? {
        val s = nowAndNext(guide, name, at)?.first ?: return null
        return s.title to progress(s, at)
    }

    /** What's on now on the channel called [name] and what follows it, or null when the guide has nothing on now. */
    fun nowAndNext(guide: Guide, name: String, at: Long = System.currentTimeMillis()): Pair<Show, Show?>? {
        val id = ChannelLogos.match(guide.names, name) ?: return null
        val list = guide.shows[id] ?: return null
        val i = list.indexOfFirst { it.start <= at && at < it.stop }.takeIf { it >= 0 } ?: return null
        return list[i] to list.getOrNull(i + 1)
    }

    /** How far into [s] [at] is, 1 to 100. */
    fun progress(s: Show, at: Long = System.currentTimeMillis()) = ((at - s.start) * 100 / (s.stop - s.start).coerceAtLeast(1)).toInt().coerceIn(1, 100)
}
