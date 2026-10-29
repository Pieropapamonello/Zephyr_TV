package link.mccoy88f.zephyr

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongOnAirTest {
    @Test fun fromIcyAndId3() {
        assertEquals("Vasco Rossi - Albachiara", songOf(Metadata(IcyInfo(ByteArray(0), "Vasco Rossi - Albachiara", null))))
        assertEquals("Tiziano Ferro - Fingo", songOf(Metadata(TextInformationFrame("TIT2", null, listOf("Fingo")),
            TextInformationFrame("TPE1", null, listOf("Tiziano Ferro")))))
        // Some stations write "Artist - Song" in the group (TIT1) alone.
        assertEquals("TIZIANO FERRO - FINGO&SPINGO", songOf(Metadata(TextInformationFrame("TIT1", null, listOf("TIZIANO FERRO - FINGO&SPINGO")))))
        assertNull(songOf(Metadata(IcyInfo(ByteArray(0), " ", null))))
    }
}
