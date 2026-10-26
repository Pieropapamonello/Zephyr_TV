package link.mccoy88f.zephyr

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Toast
import java.io.File
import org.json.JSONObject

// Zephyr TV is what Piume became. On its first start it takes what Piume kept, through Piume's MigrationProvider
// (Piume, from 4.2.1, hands it only to this app, which it knows by its certificate; this app takes it only from
// the Piume it knows by Piume's):
// the settings, and each user's progress, watched titles and Trakt account; then it suggests uninstalling Piume.
// Functions of MainActivity, kept here by topic.

/** Piume: its package, and what it hands over (only to this app, told by its certificate). */
private const val PIUME_PKG = "dev.piume"
private val PIUME_EXPORT: Uri = Uri.parse("content://dev.piume.migration/export")

/** The SHA-256 of Piume's signing certificate (CN=Piume, O=mccoy88f): only that Piume is taken from. */
private const val PIUME_CERT = "0207d5096b66aee9f326687eccbc1ebb62513bbc0542ef16dfc706e2232e7fa7"

/** Settings that stay this app's own: its Home setup and its updates (Piume doesn't hand these, nor are they taken). */
private val OWN = setOf(K.HOME_OFF, K.SETUP_SHOWN, K.REOPEN_ROW, K.UPD_VER, K.UPD_TIME, K.UPD_TOLD, K.SEEN_NOTES, K.IMPORTED, K.PIUME_TOLD)

/** The files taken, by their path: each user's, in the files folder or a user_<id> folder (nothing else, nowhere else). */
private val TAKEN = Regex("(user_[A-Za-z0-9_-]{1,32}/)?(vod_progress\\.json|vod_watched\\.json|trakt\\.json|trakt_seen\\.txt)")

/** Whether Piume is installed, signed with Piume's key: the one this app may take things from. */
internal fun MainActivity.piumeHere(): Boolean = signedBy(PIUME_PKG, PIUME_CERT)

/**
 * Whether [pkg] is installed and signed with the certificate whose SHA-256 is [sha256] (hex): an app told by its
 * key, not only its name (anyone can name an app anything).
 */
internal fun android.content.Context.signedBy(pkg: String, sha256: String): Boolean = runCatching {
    val pm = packageManager
    val certs = if (android.os.Build.VERSION.SDK_INT >= 28) {
        val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
        if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
    } ?: return false
    certs.any { s -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } == sha256 }
}.getOrDefault(false)

/**
 * The first start, with Piume here: its settings and its users' files, taken once (whatever happens, it isn't tried
 * again), then the screen built again with them. True when it started (the Home setup waits until it's done).
 */
internal fun MainActivity.takeFromPiume(): Boolean {
    if (prefs.getBoolean(K.IMPORTED, false)) return false
    prefs.edit().putBoolean(K.IMPORTED, true).apply()
    if (!piumeHere()) return false
    io.execute {
        val n = runCatching {
            val json = contentResolver.openInputStream(PIUME_EXPORT)?.use { JSONObject(it.readCapped(64L * 1024 * 1024).decodeToString()) }
                ?: throw java.io.IOException("Piume handed nothing")
            take(json)
        }.onFailure { warn("taking Piume's data failed ($it)") }.getOrNull()
        ui.post {
            Toast.makeText(this, if (n == null) getString(R.string.piume_failed) else getString(R.string.piume_taken), Toast.LENGTH_LONG).show()
            recreate() // with Piume's look, apps row, users…; the Home setup comes next
        }
    }
    return true
}

/** Piume's [json]: its settings in place of these, its users' files written. How many settings it had. */
private fun MainActivity.take(json: JSONObject): Int {
    require(json.optString("app") == "piume") { "not Piume's" }
    val values = json.getJSONObject("prefs")
    val edit = prefs.edit()
    var n = 0
    for (key in values.keys()) {
        if (key in OWN) continue
        val v = values.optJSONObject(key) ?: continue
        when {
            v.has("s") -> edit.putString(key, v.getString("s"))
            v.has("b") -> edit.putBoolean(key, v.getBoolean("b"))
            v.has("i") -> edit.putInt(key, v.getInt("i"))
            v.has("l") -> edit.putLong(key, v.getLong("l"))
            v.has("f") -> edit.putFloat(key, v.getDouble("f").toFloat())
            v.has("set") -> v.getJSONArray("set").let { a -> edit.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet()) }
            else -> continue
        }
        n++
    }
    edit.commit() // before the screen is built again
    val files = json.optJSONObject("files") ?: JSONObject()
    for (path in files.keys()) {
        if (!TAKEN.matches(path)) continue // a path of Piume's own making only: never outside these folders
        val f = File(filesDir, path)
        f.parentFile?.mkdirs()
        val part = File(f.parentFile, f.name + ".part")
        part.writeText(files.getString(path))
        part.renameTo(f)
    }
    return n
}

/**
 * Once the Home setup is done and Piume's things are here: a page to uninstall Piume (Android asks first), once.
 */
internal fun MainActivity.piumeGoesNow() {
    if (!prefs.getBoolean(K.IMPORTED, false) || prefs.getBoolean(K.PIUME_TOLD, false)) return
    if (!homeServiceOn() || settingsScrim.visibility == View.VISIBLE || setupDialog != null) return
    if (runCatching { packageManager.getPackageInfo(PIUME_PKG, 0) }.isFailure) return
    prefs.edit().putBoolean(K.PIUME_TOLD, true).apply()
    ui.post {
        showPanel()
        val top = label(getString(R.string.piume_uninstall_intro), 14f).apply {
            maxLines = 10
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(4), dp(14), dp(16))
        }
        showPage(getString(R.string.piume_uninstall_title), listOf(
            page(getString(R.string.piume_uninstall)) {
                closeLauncherSettings()
                runCatching { startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", PIUME_PKG, null))) }
            },
            page(getString(R.string.zephyr_later)) { closeLauncherSettings() },
        ), top = top) { closeLauncherSettings() }
    }
}
