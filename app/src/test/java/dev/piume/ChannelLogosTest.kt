package dev.piume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLogosTest {
    private val table = mapOf(
        "rai3" to "https://example.org/rai3.png",
        "canale5" to "https://example.org/canale5.png",
        "realtime" to "https://example.org/realtime.png",
        "realtime+1" to "https://example.org/realtime1.png",
        "la7" to "https://example.org/la7.png",
    )

    @Test fun namesNormalize() {
        assertEquals("canale5", ChannelLogos.normalize("Canale 5 HD"))
        assertEquals("canale5", ChannelLogos.normalize("CANALE5"))
        assertEquals("realtime+1", ChannelLogos.normalize("Real Time +1"))
        assertEquals("e", ChannelLogos.normalize("È"))
        assertEquals("20mediaset", ChannelLogos.normalize("20Mediaset FHD"))
    }

    @Test fun matchesByNameThenByDroppingWordsFromTheEnd() {
        assertEquals("https://example.org/canale5.png", ChannelLogos.match(table, "Canale5 HD"))
        assertEquals("https://example.org/rai3.png", ChannelLogos.match(table, "Rai 3 TGR Lazio"))
        assertEquals("https://example.org/realtime1.png", ChannelLogos.match(table, "Real Time +1"))
        assertNull(ChannelLogos.match(table, "Warner TV"))
        // Never down to a word too short to tell channels apart.
        assertNull(ChannelLogos.match(mapOf("la" to "x"), "La Effe"))
    }

    @Test fun picksADrawableLogoOfTheChannelItself() {
        val svg = ChannelLogos.Logo("https://example.org/a.svg", null, true, "SVG", 500)
        val page = ChannelLogos.Logo("https://share.google/abc", null, true, null, 0)
        val feed = ChannelLogos.Logo("https://example.org/hd.png", "HD", true, "PNG", 900)
        val own = ChannelLogos.Logo("https://example.org/own.png", null, true, "PNG", 300)
        val wide = ChannelLogos.Logo("https://example.org/wide.png", null, true, "PNG", 600)
        assertFalse(ChannelLogos.usable(svg))
        assertFalse(ChannelLogos.usable(page))
        assertTrue(ChannelLogos.usable(own))
        assertTrue(ChannelLogos.better(own, feed))
        assertTrue(ChannelLogos.better(wide, own))
        assertTrue(ChannelLogos.better(feed, null))
    }
}
