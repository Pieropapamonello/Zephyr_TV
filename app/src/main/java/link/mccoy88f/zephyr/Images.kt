package link.mccoy88f.zephyr

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Zephyr TV's one way to an image on disk, for every part of it (the home screen, the player, Spotlight, the
 * screensaver): an image is downloaded once and kept, by its [Kind], in a folder of its own with a limit of its own,
 * the least used let go first. The home screen and the player (two processes) read and add to the same folders.
 * Decoded pictures stay in memory too, per process (see MainActivity.art).
 */
internal object Images {
    /**
     * What an image is: where it's kept, how many, and its file's name. [ART] films' and series' posters, backdrops
     * and logos (the cache, which Android may empty); [LOGO] channel logos (the app's files: few, always in use);
     * [BACKDROP] Spotlight's full-screen backdrops (the cache, a few: they're large).
     */
    enum class Kind(val folder: String, val inCache: Boolean, val max: Int, val fileName: (String) -> String) {
        ART(ART_DIR, true, ART_FILES, BackdropCache::artName),
        LOGO(LOGO_DIR, false, 2000, BackdropCache::logoName),
        BACKDROP(BACKDROP_DIR, true, 48, BackdropCache::nameFor),
    }

    @Volatile private var app: Context? = null
    private val caches = ConcurrentHashMap<Kind, BackdropCache>()

    /** The app, for its folders: set by each process's activities before any image is asked for. */
    fun use(context: Context) {
        app = context.applicationContext
    }

    /** The folder images of [kind] are kept in (the screensaver shows Spotlight's from there). */
    fun folder(kind: Kind): File {
        val c = app ?: throw IllegalStateException("Images.use first")
        return File(if (kind.inCache) c.cacheDir else c.filesDir, kind.folder)
    }

    private fun cache(kind: Kind): BackdropCache = caches.getOrPut(kind) { BackdropCache(folder(kind), kind.max, kind.fileName) }

    /** [url]'s image of [kind] on disk, downloaded the first time. Network: off the UI thread. */
    fun file(url: String, kind: Kind = Kind.ART): File = cache(kind).get(url) { open(it) }

    /** Whether [url]'s image of [kind] is on disk already. */
    fun has(url: String, kind: Kind = Kind.ART) = cache(kind).has(url)

    /** Lets go of every image of [kind] (channel logos when their table is fetched again, Spotlight's on a change). */
    fun clear(kind: Kind) = cache(kind).clear()

    /** [uri] read: from the web, or where an app keeps it (a TV app's content://). */
    fun open(uri: String): InputStream =
        if (uri.startsWith("http")) Http.open(uri)
        else app?.contentResolver?.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
}
