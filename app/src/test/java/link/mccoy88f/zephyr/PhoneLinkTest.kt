package link.mccoy88f.zephyr

import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLinkTest {
    private fun call(url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).use { it.readBytes().decodeToString() }
        return code to text
    }

    @Test fun answersOnlyOnItsToken() {
        var got = ""
        val link = PhoneLink(Files.createTempDirectory("phone").toFile(), PhoneTask("<p>page</p>", 100) { f ->
            got = f.readText()
            200 to "thanks"
        })
        try {
            val base = "http://127.0.0.1:${link.port}"
            assertEquals(404, call("$base/1234").first)
            assertEquals(200 to "<p>page</p>", call("$base/${link.token}"))
            assertEquals(200 to "thanks", call("$base/${link.token}", "my key"))
            assertEquals("my key", got)
            assertEquals(413, call("$base/${link.token}", "x".repeat(101)).first)
            assertTrue(link.token.length >= 24)
        } finally {
            link.stop()
        }
    }
}

class PhonePagesTest {
    @Test fun downloadContentCantBreakOutOfThePage() {
        val page = PhonePages.download("T", "I", "Save", "b.json", "{\"a\":\"</script><b>'x'\\n\"}\nline2").html
        assertTrue(page.contains("\\x3c/script>"))
        assertTrue(!page.contains("</script><b>"))
        assertTrue(page.contains("\\'x\\'"))
        assertTrue(page.contains("\\nline2"))
    }
}
