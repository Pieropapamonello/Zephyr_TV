package dev.piume

import java.io.File
import java.io.InputStream

/** The Spotlight backdrops' folder in cacheDir. */
internal const val BACKDROP_DIR = "spotlight"

/** What each cached backdrop shows (file, title, year and genre), for the TMDB screensaver; in cacheDir. */
internal const val BACKDROP_TITLES = "spotlight-titles.tsv"

/** The screensaver choice that shows the Spotlight backdrops. */
internal const val SS_TMDB = "tmdb:trending"

/**
 * Spotlight backdrops kept on disk, so cycling through the trending titles downloads each one once instead of
 * every few seconds (and fitWallpaper's three passes over an image read the file, not the network three times).
 * The newest [maxFiles] stay; the rest go, oldest use first. Files are written under a temporary name and renamed,
 * so a download cut short never leaves a broken image behind. Safe to use from several threads.
 */
internal class BackdropCache(private val dir: File, private val maxFiles: Int = 48, private val name: (String) -> String = ::nameFor) {
    init {
        dir.mkdirs()
        // Leftovers of downloads cut short by a crash or a killed process.
        dir.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
    }

    /** The image file for [url], downloaded with [download] first if it isn't here yet. Network: off the UI thread. */
    fun get(url: String, download: (String) -> InputStream): File {
        val file = fileFor(url)
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis()) // recently used: kept longest
            return file
        }
        val part = File(dir, "${file.name}.${Thread.currentThread().id}$PART")
        try {
            download(url).use { input -> part.outputStream().use { input.copyTo(it) } }
            if (!part.renameTo(file) && file.length() == 0L) throw java.io.IOException("couldn't save $url")
        } finally {
            part.delete()
        }
        trim()
        return file
    }

    /** True when [url] is already on disk. */
    fun has(url: String) = fileFor(url).length() > 0

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(url: String) = File(dir, name(url))

    @Synchronized
    private fun trim() {
        val files = dir.listFiles { f -> !f.name.endsWith(PART) } ?: return
        if (files.size <= maxFiles) return
        files.sortedByDescending { it.lastModified() }.drop(maxFiles).forEach { it.delete() }
    }

    companion object {
        private const val PART = ".part"

        /** The file name an image is kept under. TMDB names images by a unique hash ("/abc123.jpg"). */
        fun nameFor(url: String): String {
            val name = url.substringAfterLast('/').substringBefore('?').filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            return name.takeIf { it.length > 4 } ?: "u${url.hashCode().toUInt()}"
        }
    }
}
