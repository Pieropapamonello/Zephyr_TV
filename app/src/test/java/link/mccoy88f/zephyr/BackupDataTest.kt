package link.mccoy88f.zephyr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupDataTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun usersDataGoesAndComesBack() {
        val tv = tmp.newFolder()
        File(tv, "vod_watched.json").writeText("""{"tt1":{"at":1}}""")
        File(tv, "user_kid").mkdirs()
        File(tv, "user_kid/vod_progress.json").writeText("""{"tt2":{"position":5}}""")
        File(tv, "user_kid/trakt.json").writeText("""{"token":"secret"}""")
        val data = Backup.exportData(tv, listOf("", "kid", "../evil"))
        assertEquals(setOf("", "kid"), data.keys().asSequence().toSet())
        assertFalse("Trakt's keys stay on the TV", data.toString().contains("secret"))

        val other = tmp.newFolder()
        data.put("../evil", data.getJSONObject("kid")) // a file that would write outside: left out
        assertEquals(2, Backup.restoreData(other, data))
        assertEquals("""{"tt1":{"at":1}}""", File(other, "vod_watched.json").readText())
        assertEquals("""{"tt2":{"position":5}}""", File(other, "user_kid/vod_progress.json").readText())
        assertFalse(File(other, "user_kid/trakt.json").exists())
        assertFalse(File(other.parentFile, "evil").exists())
    }
}
