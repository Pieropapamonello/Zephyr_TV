package link.mccoy88f.zephyr
import android.app.SearchManager
import android.content.Intent

internal fun MainActivity.acceptVoiceWords(words: String, search: (String) -> Unit = { searchTmdb(it) }) {
    val apps = sortedApps()
    val pkg = VoiceCommand.appForWords(words, apps.map { it.label }.toTypedArray(), apps.map { it.pkg }.toTypedArray())
    val launch = pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it) }
    if (launch != null) { startActivity(launch); return }
    VoiceCommand.contentWords(words).takeIf { it.isNotBlank() }?.let(search)
}

/** A standard ACTION_SEARCH can also be sent by a compatible assistant/recognizer. */
internal fun MainActivity.handleVoiceSearchIntent(i: Intent): Boolean {
    if (i.action != Intent.ACTION_SEARCH) return false
    val query = i.getStringExtra(SearchManager.QUERY)?.take(500) ?: return false
    ui.post { acceptVoiceWords(query) }
    return true
}
