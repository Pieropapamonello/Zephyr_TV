package dev.piume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLogosTest {
    private val table = ChannelLogos.table(listOf(
        listOf("Rai 3") to "rai3",
        listOf("Canale 5", "Canale 10", "Telemilano") to "canale5",
        listOf("Real Time") to "realtime",
        listOf("Real Time +1") to "realtime1",
        listOf("TeleAmbiente TV") to "teleambiente",
        listOf("Telepace 1") to "telepace1",
        listOf("Italia 2") to "italia2",
        listOf("Donna TV") to "donna",
        listOf("Rai 4K") to "rai4k",
    ))

    @Test fun namesNormalize() {
        assertEquals("canale5", ChannelLogos.normalize("Canale 5 HD"))
        assertEquals("canale5", ChannelLogos.normalize("CANALE5"))
        assertEquals("realtime+1", ChannelLogos.normalize("Real Time +1"))
        assertEquals("e", ChannelLogos.normalize("È"))
        assertEquals("20mediaset", ChannelLogos.normalize("20Mediaset FHD"))
        assertEquals("rai4k", ChannelLogos.normalize("Rai 4K"))
    }

    @Test fun matchesByNameLooselyAndByDroppingWords() {
        assertEquals("canale5", ChannelLogos.match(table, "Canale5 HD"))
        assertEquals("canale5", ChannelLogos.match(table, "Telemilano"))
        assertEquals("rai3", ChannelLogos.match(table, "Rai 3 TGR Lazio"))
        assertEquals("realtime1", ChannelLogos.match(table, "Real Time +1"))
        assertEquals("teleambiente", ChannelLogos.match(table, "TELEAMBIENTE"))
        assertEquals("telepace1", ChannelLogos.match(table, "TELEPACE"))
        assertEquals("italia2", ChannelLogos.match(table, "Mediaset Italia2 HD"))
        assertEquals("rai4k", ChannelLogos.match(table, "Rai 4K"))
        assertNull(ChannelLogos.match(table, "Warner TV"))
        assertNull(ChannelLogos.match(table, "Rai")) // not Rai 4K's
    }

    @Test fun leavesOutWhatWouldMatchTheWrongChannel() {
        assertNull(ChannelLogos.match(table, "CANALE 10 HD")) // Canale 5's old name, now a local channel
        assertNull(ChannelLogos.match(table, "DONNA SPORT TV")) // not Donna TV: one word left is too little
        assertNull(ChannelLogos.match(table, "Italia 53"))
    }

    @Test fun picksADrawableLogoOfTheChannelItself() {
        val svg = ChannelLogos.Logo("https://example.org/a.svg", null, true, "SVG", 500)
        val page = ChannelLogos.Logo("https://share.google/abc", null, true, null, 0)
        val feed = ChannelLogos.Logo("https://example.org/hd.png", "HD", true, "PNG", 900)
        val own = ChannelLogos.Logo("https://example.org/own.png", null, true, "PNG", 300)
        val wide = ChannelLogos.Logo("https://example.org/wide.png", null, true, "PNG", 600)
        val wikiSvg = ChannelLogos.Logo("https://upload.wikimedia.org/wikipedia/commons/c/c5/Logo_Italia2.svg", null, true, "SVG", 0)
        assertNull(ChannelLogos.drawable(svg))
        assertNull(ChannelLogos.drawable(page))
        assertEquals(own.url, ChannelLogos.drawable(own))
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Logo_Italia2.svg/500px-Logo_Italia2.svg.png", ChannelLogos.drawable(wikiSvg))
        assertTrue(ChannelLogos.better(own, feed))
        assertTrue(ChannelLogos.better(wide, own))
        assertTrue(ChannelLogos.better(feed, null))
    }
}
