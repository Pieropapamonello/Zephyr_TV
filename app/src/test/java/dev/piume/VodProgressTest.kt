package dev.piume

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodProgressTest {
    private fun entry(id: String, position: Long, duration: Long = 3_600_000L, at: Long = position) =
        VodProgress.Entry(id, "series", "tt0898266", "Show · S1 E1", "Pilot", null, position, duration, at)

    @Test fun keepsWhereFilmsGotToNewestFirst() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodProgress.put(dir, entry("tt0898266:1:1", 600_000L, at = 1))
        VodProgress.put(dir, entry("tt0898266:1:2", 900_000L, at = 2))
        assertEquals(listOf("tt0898266:1:2", "tt0898266:1:1"), VodProgress.all(dir).map { it.id })
        assertEquals(600_000L, VodProgress.position(dir, "tt0898266:1:1"))
        VodProgress.put(dir, entry("tt0898266:1:1", 700_000L, at = 3)) // in place of what was there
        assertEquals(listOf("tt0898266:1:1", "tt0898266:1:2"), VodProgress.all(dir).map { it.id })
        assertEquals(700_000L, VodProgress.position(dir, "tt0898266:1:1"))
    }

    @Test fun dropsWhatIsWatchedOrHardlyBegun() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodProgress.put(dir, entry("a", 600_000L))
        VodProgress.put(dir, entry("a", 3_500_000L)) // at the credits: watched
        assertTrue(VodProgress.all(dir).isEmpty())
        VodProgress.put(dir, entry("b", 10_000L)) // ten seconds in: nothing to resume
        assertNull(VodProgress.position(dir, "b"))
        assertTrue(VodProgress.all(dir).isEmpty())
        assertFalse(VodProgress.resumable(10_000L, 3_600_000L))
        assertTrue(VodProgress.resumable(60_000L, 0)) // length unknown: in a little way is enough
    }

    @Test fun nothingSavedIsNothingToResume() {
        val dir = Files.createTempDirectory("vod").toFile()
        assertTrue(VodProgress.all(dir).isEmpty())
        assertNull(VodProgress.position(dir, "x"))
    }

    @Test fun theNextEpisodeWaitsUntilItIsBegun() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodProgress.putNext(dir, entry("tt0898266:1:2", 0L, at = 5))
        val next = VodProgress.all(dir).single()
        assertTrue(next.upNext)
        assertNull(VodProgress.position(dir, "tt0898266:1:2")) // it starts from the start
        VodProgress.put(dir, entry("tt0898266:1:2", 10_000L, at = 6)) // its first seconds don't take it off
        assertTrue(VodProgress.all(dir).single().upNext)
        VodProgress.put(dir, entry("tt0898266:1:2", 120_000L, at = 7))
        assertEquals(120_000L, VodProgress.position(dir, "tt0898266:1:2"))
    }

    @Test fun marksWhatIsWatched() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodWatched.set(dir, listOf("tt0898266:1:1" to "tt0898266", "tt0898266:1:2" to "tt0898266"), true)
        assertTrue(VodWatched.has(dir, "tt0898266:1:1"))
        VodWatched.set(dir, listOf("tt0898266:1:1" to "tt0898266"), false)
        assertFalse(VodWatched.has(dir, "tt0898266:1:1"))
        assertTrue(VodWatched.has(dir, "tt0898266:1:2"))
        assertTrue(VodProgress.finished(3_500_000L, 3_600_000L))
        assertFalse(VodProgress.finished(600_000L, 3_600_000L))
    }
}
