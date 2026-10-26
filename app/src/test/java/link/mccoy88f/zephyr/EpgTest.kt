package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgTest {
    @Test fun readsXmltvTimes() {
        assertEquals(1792473000000L, Epg.time("20261020051000 +0000"))
        assertEquals(1792473000000L, Epg.time("20261020071000 +0200"))
        assertEquals(1792473000000L, Epg.time("20261020051000"))
        assertNull(Epg.time("soon"))
    }

    @Test fun findsWhatIsOnByChannelName() {
        val at = 1_000_000L
        val guide = Epg.Guide(
            mapOf("Rai1.it" to listOf(Epg.Show(at - 600_000, at + 1_200_000, "Tg1"), Epg.Show(at + 1_200_000, at + 3_000_000, "Porta a Porta"))),
            ChannelLogos.table(listOf(listOf("Rai1", "Rai 1 HD") to "Rai1.it")),
        )
        assertEquals("Tg1" to 33, Epg.now(guide, "Rai 1", at))
        assertEquals("Porta a Porta", Epg.now(guide, "Rai 1 HD", at + 1_500_000)?.first)
        assertNull(Epg.now(guide, "Canale 5", at))
        assertNull(Epg.now(guide, "Rai 1", at + 5_000_000))
    }
}
