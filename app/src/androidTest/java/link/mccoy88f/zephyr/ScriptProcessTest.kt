package link.mccoy88f.zephyr

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On a device: scripts run in their own process, and one that won't stop gets its process killed and started again. */
@RunWith(AndroidJUnit4::class)
class ScriptProcessTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun pid(): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.firstOrNull { it.processName == context.packageName + ":plugins" }?.pid
    }

    @Test fun aScriptRunsInThePluginsProcessAndOneThatHangsIsStopped() {
        val plugins = ScriptProcess.plugins(context)

        val first = plugins.run("async function go(n) { return { n: n * 2, pid: 0 }; }", "go(21)", "", 5_000)
        assertEquals("""{"n":42,"pid":0}""", first.out)
        val before = pid()
        assertNotNull("the :plugins process runs", before)
        assertNotEquals(android.os.Process.myPid(), before)

        val start = System.currentTimeMillis()
        val hung = runCatching { plugins.run("function go() { while (true) {} }", "go()", "", 1_000) }
        val waited = System.currentTimeMillis() - start
        assertTrue("a script that won't stop fails", hung.exceptionOrNull() is IOException)
        assertTrue("after its time and the margin, not forever: $waited ms", waited in 5_000..15_000)

        val again = plugins.run("function go() { return 'ok'; }", "go()", "", 5_000)
        assertEquals("\"ok\"", again.out)
        assertNotEquals("started again", before, pid())
    }

    @Test fun localStorageComesBack() {
        val a = ScriptProcess.plugins(context).run("function go() { localStorage.setItem('k', 'v'); return 1; }", "go()",
            "globalThis.__store = {\"old\":\"x\"};", 5_000, wantsStore = true)
        assertEquals("""{"old":"x","k":"v"}""", a.store)
    }
}
