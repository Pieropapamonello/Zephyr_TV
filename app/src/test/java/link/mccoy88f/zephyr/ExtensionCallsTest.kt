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

    @Test fun aListFieldHasItsItemsFields() {
        val f = Nuvio.fields(org.json.JSONArray("""[{"type":"list","key":"playlists","label":"Playlists","itemLabel":"name",
            "item":[{"type":"text","key":"name","label":"Name"},{"type":"text","key":"url","label":"Address"},{"type":"list","key":"x","item":[]}]},
            {"type":"list","key":"empty","label":"Nothing","item":[{"type":"header","label":"h"}]}]"""))
        assertEquals(listOf("playlists"), f.map { it.key })
        assertEquals(listOf("name", "url"), f[0].item.map { it.key })
        assertEquals("name", f[0].itemLabel)
    }
}
