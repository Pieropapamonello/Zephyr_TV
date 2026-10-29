package link.mccoy88f.zephyr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a device: an extension installed from its manifest-zephyrtv.json, and its handler run in the extensions'
 * process. The address comes from the instrumentation arguments (-e extension <url>); without one, skipped.
 */
@RunWith(AndroidJUnit4::class)
class ExtensionInstallTest {
    @Test fun installsFromItsAddressAndRunsInItsProcess() {
        val url = InstrumentationRegistry.getArguments().getString("extension")
        assumeTrue(url != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = context.filesDir
        val p = Extensions.published(Extensions.publishedUrl(url!!)!!)
        val e = Extensions.download(files, p)
        assertEquals(p.id, e.id)
        val script = java.io.File(e.dir, e.main).readText()
        val answer = ScriptProcess.extensions(context).run(script, "module.exports.catalog({ type: 'tv', id: 'ciao', extra: {} })", "", 10_000)
        assertEquals("Canale di prova", JSONObject(answer.out!!).getJSONArray("metas").getJSONObject(0).getString("name"))
        Extensions.remove(files, e.id)
    }
}
