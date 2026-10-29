package link.mccoy88f.zephyr

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionCallsTest {
    @Test fun anAddressBecomesAHandlersArgs() {
        val a = ExtensionCalls.request("tv", "ciao%3A1", "genre=Sport%20%26%20News&skip=100&search=a+b")
        assertEquals("tv", a.getString("type"))
        assertEquals("ciao:1", a.getString("id"))
        assertEquals("Sport & News", a.getJSONObject("extra").getString("genre"))
        assertEquals(100, a.getJSONObject("extra").getInt("skip"))
        assertEquals("a+b", a.getJSONObject("extra").getString("search"))
        assertEquals("org.example.x", ExtensionCalls.idOf("zext://org.example.x/catalog/tv/a.json"))
        assertEquals(null, ExtensionCalls.idOf("https://example.org/manifest.json"))
    }
}
