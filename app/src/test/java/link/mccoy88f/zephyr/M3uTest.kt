package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uTest {
    private fun read(text: String) = M3u.read(text.trimIndent().reader().buffered())

    @Test fun channelsQualitiesHeadersKeysGuides() {
        val p = read("""
            #EXTM3U url-tvg="https://g.example/a.xml.gz, https://g.example/b.xml"
            #EXTINF:-1 tvg-id="rai1.it" tvg-logo="https://l.example/rai1.png" group-title="-----GENERALISTI-----",Rai 1 HD
            #EXTVLCOPT:http-user-agent=Agent/1
            #EXTVLCOPT:http-referrer=https://r.example/
            https://s.example/rai1-hd.m3u8
            #EXTINF:-1 tvg-id="rai1.it" group-title="Generalisti",Rai 1 FHD
            https://s.example/rai1-fhd.m3u8
            #EXTINF:-1 tvg-id="sky.it" group-title="Cinema",Sky Cinema Uno
            https://s.example/sky1.m3u8
            #EXTINF:-1 tvg-id="sky.it" group-title="Cinema",Sky Cinema Due HD
            https://s.example/sky2.m3u8
            #EXTINF:-1 group-title="Sport" catchup="default" catchup-days="3" catchup-source="https://c.example/{utc}",Dazn 1
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=0123456789abcdef0123456789ABCDEF:fedcba9876543210fedcba9876543210
            #EXTHTTP:{"Cookie":"a=1"}
            https://s.example/dazn.mpd
            #EXTINF:-1,Spento
            null
        """)
        assertEquals(listOf("https://g.example/a.xml.gz", "https://g.example/b.xml"), p.guides)
        assertEquals(listOf("Rai 1", "Sky Cinema Uno", "Sky Cinema Due", "Dazn 1"), p.channels.map { it.name })
        val rai = p.channels[0]
        assertEquals("rai1.it", rai.id)
        assertEquals(2, rai.streams.size) // HD and FHD: one channel
        assertEquals("GENERALISTI", rai.groups[0])
        val h = rai.streams[0].getJSONObject("headers")
        assertEquals("Agent/1", h.getString("User-Agent"))
        assertEquals("https://r.example/", h.getString("Referer"))
        assertEquals("skycinemadue", p.channels[2].id) // the same tvg-id for another channel: one of its own
        val dazn = p.channels[3]
        assertEquals("0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210", dazn.streams[0].getString("clearkey"))
        assertEquals("a=1", dazn.streams[0].getJSONObject("headers").getString("Cookie"))
        assertEquals(3, dazn.catchup!!.getInt("days"))
        assertEquals("https://c.example/{utc}", dazn.catchup!!.getString("url"))
        assertEquals(listOf("GENERALISTI", "Cinema", "Sport"), p.groups)
    }

    @Test fun namesWithoutQuality() {
        assertEquals("Canale 5", M3u.displayName("Canale 5 FHD HEVC"))
        assertEquals("Rai 1", M3u.displayName("SD Rai 1"))
        assertEquals("Rai 4K", M3u.displayName("Rai 4K"))
        assertEquals("rai1", M3u.looseKey("Rai 1 HD"))
        assertEquals("rai1", M3u.looseKey("rai1.it"))
        assertNull(M3u.clearKey("nope"))
    }

    @Test fun keptAndAskedAPageAtATime() {
        val data = kotlin.io.path.createTempDirectory().toFile()
        val p = read("""
            #EXTM3U
            #EXTINF:-1 group-title="A",Uno
            https://s/1
            #EXTINF:-1 group-title="B",Due
            https://s/2
            #EXTINF:-1 group-title="A",Tre
            https://s/3
        """)
        // As load keeps it: one channel a line.
        val f = java.io.File(data, "m3u/main.jsonl").apply { parentFile!!.mkdirs() }
        f.writeText(p.channels.joinToString("\n") { it.json().toString() })
        assertEquals(listOf("Uno", "Tre"), M3u.channels(data, "main", "A", null, 0, 10).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } })
        assertEquals(listOf("Tre"), M3u.channels(data, "main", null, "tr", 0, 10).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } })
        assertEquals("Due", M3u.channel(data, "main", p.channels[1].id)!!.getString("name"))
        M3u.forget(data, "main")
        data.deleteRecursively()
    }

    @Test fun radioStationsApartFromTvChannels() {
        val p = read("""
            #EXTM3U
            #EXTINF:-1 group-title="Rai",Rai 1
            https://s/rai1.m3u8
            #EXTINF:-1 radio="true" group-title="Radio Rai",Rai Radio 1
            https://s/radio1.aac
        """)
        assertEquals(listOf(false, true), p.channels.map { it.radio })
        val data = kotlin.io.path.createTempDirectory().toFile()
        val f = java.io.File(data, "m3u/main.jsonl").apply { parentFile!!.mkdirs() }
        f.writeText(p.channels.joinToString("\n") { it.json().toString() })
        fun names(kind: String?) = M3u.channels(data, "main", null, null, 0, 10, kind).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertEquals(listOf("Rai Radio 1"), names("radio"))
        assertEquals(listOf("Rai 1"), names("tv"))
        assertEquals(2, names(null).size)
        data.deleteRecursively()
    }
}
