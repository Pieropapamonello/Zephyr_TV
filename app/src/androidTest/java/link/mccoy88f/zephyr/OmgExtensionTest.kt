package link.mccoy88f.zephyr

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a device: OMG Premium TV installed from its manifest-zephyrtv.json (-e omg <url>), a playlist set in its options
 * (-e playlist <url>), then asked as the rest of Zephyr TV asks it: manifest, a catalog page, a channel, its streams,
 * its guides. Without the arguments, skipped.
 */
@RunWith(AndroidJUnit4::class)
class OmgExtensionTest {
    @Test fun channelsFromAPlaylist() {
        val args = InstrumentationRegistry.getArguments()
        val omg = args.getString("omg")
        val playlist = args.getString("playlist")
        assumeTrue(omg != null && playlist != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val e = Extensions.download(context.filesDir, Extensions.published(Extensions.publishedUrl(omg!!)!!))
        val prefs = context.getSharedPreferences(K.FILE, Context.MODE_PRIVATE)
        val options = JSONObject().put(e.id, JSONObject().put("playlists", JSONArray().put(JSONObject().put("name", "Italia").put("url", playlist))))
        prefs.edit().putString(K.EXT_OPTIONS, options.toString()).commit()
        try {
            ExtensionCalls.use(context)
            val url = ExtensionCalls.url(e.id)
            val manifest = Stremio.get(url)
            val addon = Stremio.addon(url, manifest)
            val tv = addon.catalogs.first { it.type == "tv" }
            assertEquals("Italia", tv.name)
            // Radio stations (radio="true"), when the playlist has some: a catalog of their own.
            addon.catalogs.firstOrNull { it.type == "radio" }?.let { r ->
                val radios = Stremio.get("${addon.base}/catalog/radio/${Stremio.enc(r.id)}/skip=0.json").getJSONArray("metas")
                val first = radios.getJSONObject(0)
                assertEquals("radio", first.getString("type"))
                val s = Stremio.streams(addon.base, "radio", first.getString("id"))
                assertTrue(s.isNotEmpty())
                println("OMG radio: ${r.name}, ${radios.length()} stations, first ${first.getString("name")} → ${s[0].url}")
            }
            assertTrue("groups: ${tv.genres}", tv.genres.isNotEmpty())
            val page = Stremio.get("${addon.base}/catalog/tv/${Stremio.enc(tv.id)}/skip=0.json").getJSONArray("metas")
            assertTrue("channels: ${page.length()}", page.length() > 0)
            val first = page.getJSONObject(0)
            assertTrue(first.getString("id").startsWith("omg:p0:"))
            val meta = Stremio.get("${addon.base}/meta/tv/${Stremio.enc(first.getString("id"))}.json").getJSONObject("meta")
            assertEquals(first.getString("name"), meta.getString("name"))
            val streams = Stremio.streams(addon.base, "tv", first.getString("id"))
            assertTrue("streams of ${first.getString("name")}", streams.isNotEmpty() && streams[0].url.startsWith("http"))
            val byGenre = Stremio.get("${addon.base}/catalog/tv/${Stremio.enc(tv.id)}/genre=${Stremio.enc(tv.genres.first())}.json").getJSONArray("metas")
            assertTrue(byGenre.length() > 0)
            println("OMG: ${page.length()} channels on the first page, ${tv.genres.size} groups, first ${first.getString("name")} → ${streams[0].url}")
        } finally {
            prefs.edit().remove(K.EXT_OPTIONS).commit()
            Extensions.remove(context.filesDir, e.id)
        }
    }
}
