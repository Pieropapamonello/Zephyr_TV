package link.mccoy88f.zephyr

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last problems Zephyr TV ran into (a request that failed, a poster that didn't load), kept on the TV so they
 * can be looked at from Zephyr TV settings or a phone when something doesn't work. Nothing leaves the TV unless the
 * user opens it on their phone. API keys are taken out before anything is written.
 */
internal object Problems {
    private const val MAX = 100
    private var file: File? = null
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun init(f: File) {
        if (file != null) return
        file = f
        runCatching { f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
    }

    /** Newest first. */
    @Synchronized
    fun recent(): List<String> = lines.reversed()

    @Synchronized
    fun clear() {
        lines.clear()
        file?.delete()
    }

    @Synchronized
    fun add(message: String) {
        val line = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT).format(Date()) + "  " + redactKeys(message).replace('\n', ' ')
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        file?.let { f -> runCatching { f.writeText(lines.joinToString("\n", postfix = "\n")) } }
    }

    fun redactKeys(s: String) = s.replace(Regex("api_key=[^&\\s)]+"), "api_key=…")
}

/** Logs a problem and keeps it in [Problems]. */
internal fun warn(message: String) {
    Problems.add(message)
    try {
        android.util.Log.w("Zephyr TV", Problems.redactKeys(message))
    } catch (e: RuntimeException) {
        // Plain JVM unit tests: android.util.Log is a stub that throws
    } catch (e: LinkageError) {
        // ... or a class without its native part
    }
}
