package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLanguageTest {
    @Test fun saysItsLanguage() {
        assertEquals(setOf("it"), streamLanguages("Il cavaliere oscuro · 1080p ITA"))
        assertEquals(setOf("it"), streamLanguages("SUB-ITA 720p"))
        assertEquals(setOf("it", "en"), streamLanguages("Source A 1080p · 🇮🇹 🇬🇧"))
        assertEquals(emptySet<String>(), streamLanguages("NovaITA 1080p")) // a name with ITA in it, not a language
        assertEquals(setOf("en", "hi"), streamLanguages("Source B 2160p English Hindi"))
    }

    @Test fun qualityFromTheName() {
        assertEquals(2160, streamQuality("Source A 4K · A Film (2008)"))
        assertEquals(2160, streamQuality("Source B 2160p HDR"))
        assertEquals(1080, streamQuality("Source C 1080p · Host D"))
        assertEquals(720, streamQuality("Source E 720p"))
        assertEquals(1080, streamQuality("Source F FHD"))
        for (name in listOf("Full HD", "FullHD", "Full-HD", "full_hd", "1080i")) assertEquals(name, 1080, streamQuality("Source G $name"))
        assertEquals(1440, streamQuality("Source H QHD"))
        assertEquals(1440, streamQuality("Source H 2K"))
        assertEquals(2160, streamQuality("Source H UHD HDR"))
        assertEquals(720, streamQuality("Source H HD"))
        assertEquals(UNKNOWN_QUALITY, streamQuality("Source H HDR")) // HDR isn't HD
        assertEquals(UNKNOWN_QUALITY, streamQuality("Source A Auto · Multi-audio"))
        assertEquals(UNKNOWN_QUALITY, streamQuality("A Film (2008)")) // a year isn't a quality
    }

    @Test fun preferredQualityFirst() {
        val streams = listOf("A 720p", "B 2160p", "C 1080p", "D Auto", "E 1440p", "F 480p").map { Stremio.Stream("https://x/$it", it, emptyMap()) }
        assertEquals(listOf("B 2160p", "E 1440p", "C 1080p", "D Auto", "A 720p", "F 480p"),
            streams.sortedWith(qualityOrder(0)).map { it.title })
        // A 1080p TV: 1080p down first, then those above it, the nearest first; 4K still there, last.
        assertEquals(listOf("C 1080p", "D Auto", "A 720p", "F 480p", "E 1440p", "B 2160p"),
            streams.sortedWith(qualityOrder(1080)).map { it.title })
    }

    @Test fun rankedAgainstTheTvsLanguage() {
        assertEquals(Stremio.LANG_OURS, languageRank("Source C 1080p · Host D", "it", declared = setOf("it"))) // its plugin's language
        assertEquals(Stremio.LANG_OURS, languageRank("Torrent 1080p ITA ENG", "it"))
        assertEquals(Stremio.LANG_OURS, languageRank("Multi audio 4K", "it"))
        assertEquals(Stremio.LANG_OTHER, languageRank("Torrent 1080p ENG", "it"))
        assertEquals(Stremio.LANG_UNKNOWN, languageRank("Torrent 1080p", "it")) // says none: a last choice, not hidden
    }
}
