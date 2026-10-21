package dev.piume

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemsTest {
    @Test fun keepsTheLastHundredWithoutKeys() {
        val f = File(Files.createTempDirectory("problems").toFile(), "p.log")
        Problems.init(f)
        Problems.clear()
        repeat(120) { warn("failed $it https://api.themoviedb.org/3/x?api_key=secret123&y=1") }
        val recent = Problems.recent()
        assertEquals(100, recent.size)
        assertTrue(recent.first().contains("failed 119"))
        assertTrue(recent.none { "secret123" in it })
    }
}
