package dev.piume

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val netflix = "com.netflix.ninja"
    private val prime = "com.amazon.amazonvideo.livingroom"

    @Test fun standardServicesOnlyWhenTheyHaveIt() {
        assertTrue(TmdbClient.offers(netflix, setOf(netflix)))
        assertFalse(TmdbClient.offers(prime, setOf(netflix)))
        assertFalse(TmdbClient.offers(netflix, emptySet()))
    }

    @Test fun aggregatorsTrailerAndUnknownAppsAlways() {
        for (pkg in listOf("com.nuvio.tv", "com.nuvio.app", "com.stremio.one", "com.google.android.youtube.tv", "org.xbmc.kodi")) {
            assertTrue(pkg, TmdbClient.offers(pkg, emptySet()))
        }
    }

    @Test fun everythingWhileUnknown() {
        assertTrue(TmdbClient.offers(prime, null))
    }
}
