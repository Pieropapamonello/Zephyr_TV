package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StreamCacheTest {
    private val cache = kotlin.io.path.createTempDirectory().toFile()
    private fun stream(n: Int) = Stremio.Stream("https://s.example/$n.m3u8", "Stream $n", mapOf("Referer" to "https://r.example/"),
        langRank = Stremio.LANG_UNKNOWN, clearkey = mapOf("a" to "b"))

    @Test fun keptWithAllTheirFields() {
        assertNull(StreamCache.fresh(cache, "https://a.example", "movie", "tt1"))
        val asked = AtomicInteger()
        val first = StreamCache.get(cache, "https://a.example", "movie", "tt1") { asked.incrementAndGet(); listOf(stream(1), stream(2)) }
        val again = StreamCache.get(cache, "https://a.example", "movie", "tt1") { asked.incrementAndGet(); emptyList() }
        assertEquals(1, asked.get())
        assertEquals(first.map { it.url }, again.map { it.url })
        assertEquals(Stremio.LANG_UNKNOWN, again[0].langRank)
        assertEquals("https://r.example/", again[0].headers["Referer"])
        assertEquals("b", again[0].clearkey["a"])
        // Another source, or another item: their own.
        assertNull(StreamCache.fresh(cache, "https://b.example", "movie", "tt1"))
        assertNull(StreamCache.fresh(cache, "https://a.example", "series", "tt1:1:1"))
        cache.deleteRecursively()
    }

    @Test fun aSearchGoingOnIsWaitedFor() {
        val asked = AtomicInteger()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        // The launcher looks (slowly); the player, asking meanwhile, waits for it instead of asking again.
        StreamCache.warm(cache, listOf("https://a.example"), "movie", "tt2", { asked.incrementAndGet(); started.countDown(); Thread.sleep(600); listOf(stream(3)) }) { counts ->
            assertEquals(1, counts["https://a.example"]); done.countDown()
        }
        started.await(5, TimeUnit.SECONDS)
        val got = StreamCache.get(cache, "https://a.example", "movie", "tt2") { asked.incrementAndGet(); emptyList() }
        assertEquals(listOf("https://s.example/3.m3u8"), got.map { it.url })
        assertEquals(1, asked.get())
        done.await(5, TimeUnit.SECONDS)
        cache.deleteRecursively()
    }

    @Test fun aFailedSearchIsNotKept() {
        runCatching { StreamCache.get(cache, "https://a.example", "movie", "tt3") { throw java.io.IOException("down") } }
        assertNull(StreamCache.fresh(cache, "https://a.example", "movie", "tt3"))
        assertEquals(1, StreamCache.get(cache, "https://a.example", "movie", "tt3") { listOf(stream(4)) }.size)
        cache.deleteRecursively()
    }
}
