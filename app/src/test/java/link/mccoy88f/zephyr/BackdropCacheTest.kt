package link.mccoy88f.zephyr

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropCacheTest {
    private val dir: File = Files.createTempDirectory("backdrops").toFile()
    private var downloads = 0
    private fun fake(url: String): InputStream {
        downloads++
        return ByteArrayInputStream(url.toByteArray())
    }

    @Test fun downloadsOnce() {
        val cache = BackdropCache(dir)
        val first = cache.get("https://image.tmdb.org/t/p/w1280/abc.jpg", ::fake)
        val again = cache.get("https://image.tmdb.org/t/p/w1280/abc.jpg", ::fake)
        assertEquals(1, downloads)
        assertEquals(first, again)
        assertTrue(cache.has("https://image.tmdb.org/t/p/w1280/abc.jpg"))
    }

    @Test fun keepsTheNewest() {
        val cache = BackdropCache(dir, maxFiles = 2)
        cache.get("https://x/one.jpg", ::fake).setLastModified(1_000)
        cache.get("https://x/two.jpg", ::fake)
        cache.get("https://x/three.jpg", ::fake)
        assertFalse(cache.has("https://x/one.jpg"))
        assertTrue(cache.has("https://x/two.jpg") && cache.has("https://x/three.jpg"))
    }

    @Test fun aFailedDownloadLeavesNothing() {
        val cache = BackdropCache(dir)
        try {
            cache.get("https://x/broken.jpg") { object : InputStream() { override fun read(): Int = throw IOException("cut") } }
        } catch (e: IOException) {
        }
        assertFalse(cache.has("https://x/broken.jpg"))
        assertEquals(0, dir.listFiles()!!.size)
    }
}
