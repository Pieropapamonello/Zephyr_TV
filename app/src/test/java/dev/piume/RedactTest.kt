package dev.piume

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {
    @Test fun keepsTheKeyOutOfTheLog() {
        val e = FileNotFoundException("https://api.themoviedb.org/3/movie/1/videos?language=it-IT&api_key=0123456789abcdef&x=1")
        assertEquals("java.io.FileNotFoundException: https://api.themoviedb.org/3/movie/1/videos?language=it-IT&api_key=…&x=1", TmdbClient.redact(e))
    }
}
