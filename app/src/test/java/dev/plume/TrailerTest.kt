package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerTest {
    @Test fun namesTheLanguageInItself() {
        assertEquals("Dune trailer italiano", TmdbClient.trailerQuery("Dune", "it-IT"))
        assertEquals("Dune trailer deutsch", TmdbClient.trailerQuery("Dune", "de-DE"))
        assertEquals("Dune trailer english", TmdbClient.trailerQuery("Dune", "en-US"))
        assertEquals("Dune trailer english", TmdbClient.trailerQuery("Dune", "pt-BR")) // unknown: English
    }
}
