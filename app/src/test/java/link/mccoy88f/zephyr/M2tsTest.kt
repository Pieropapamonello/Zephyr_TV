package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class M2tsTest {
    // The player's bytes are the file's with each 192-byte packet's first 4 (its time) left out.
    @Test fun mapsThePlayersBytesToTheFiles() {
        assertEquals(4L, M2ts.file(0))
        assertEquals(191L, M2ts.file(187))
        assertEquals(196L, M2ts.file(188))
        assertEquals(1000L * 192 + 4 + 10, M2ts.file(1000L * 188 + 10))
    }

    @Test fun countsThePlayersBytesInTheFiles() {
        assertEquals(0L, M2ts.ts(0))
        assertEquals(0L, M2ts.ts(4))
        assertEquals(188L, M2ts.ts(192))
        assertEquals(188L, M2ts.ts(196))
        assertEquals(189L, M2ts.ts(197))
        assertEquals(3L * 188, M2ts.ts(3L * 192)) // a whole file of 3 packets
        for (b in listOf(0L, 1L, 187L, 188L, 5000L)) assertEquals(b + 1, M2ts.ts(M2ts.file(b) + 1)) // up to and including a byte: one more than it
    }
}
