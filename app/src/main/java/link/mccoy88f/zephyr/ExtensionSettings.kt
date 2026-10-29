package link.mccoy88f.zephyr

import android.content.SharedPreferences
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU
import java.io.File
import org.json.JSONObject

// The extensions in Zephyr TV: which are installed and in what order, which are on, which are 18+, where each came
// from, the preinstalled ones, and their updates. What an extension is, and its package on disk, are in Extensions.

/** The extensions installed, in the user's order (those installed since, after). */
internal fun extensions(p: SharedPreferences, files: File): List<Extensions.Extension> {
    val all = Extensions.installed(files).associateBy { it.id }
    val order = p.getString(K.EXT_ORDER, "")!!.lines().filter { it in all }
    return (order + (all.keys - order.toSet()).sorted()).mapNotNull { all[it] }
}

/** Whether the extension [id] is on. */
internal fun extensionOn(p: SharedPreferences, id: String) = id !in p.getStringSet(K.EXT_OFF, emptySet())!!

/** Whether [e] is 18+: it says so, or the user marked it. */
internal fun extensionAdult(p: SharedPreferences, e: Extensions.Extension) = e.adult || e.id in p.getStringSet(K.EXT_ADULT, emptySet())!!

/** Whether the extension [id] came with Zephyr TV: it can be switched off, not removed. */
internal fun extensionBuiltIn(p: SharedPreferences, id: String) = id in p.getStringSet(K.EXT_BUILTIN, emptySet())!!

/** Where the extension [id] was added from (its manifest-zephyrtv.json), or null (from a .zip, or preinstalled). */
internal fun extensionSource(p: SharedPreferences, id: String): String? =
    runCatching { JSONObject(p.getString(K.EXT_SOURCES, null) ?: "{}").optString(id).takeIf { it.isNotBlank() } }.getOrNull()

/** Keeps a newly installed [e] in the lists: last in the order, [source] its address (when added from one), [adult]. */
internal fun rememberExtension(p: SharedPreferences, e: Extensions.Extension, source: String?, adult: Boolean) {
    val order = p.getString(K.EXT_ORDER, "")!!.lines().filter { it.isNotBlank() && it != e.id } + e.id
    val sources = runCatching { JSONObject(p.getString(K.EXT_SOURCES, null) ?: "{}") }.getOrDefault(JSONObject())
    if (source != null) sources.put(e.id, source)
    val marked = p.getStringSet(K.EXT_ADULT, emptySet())!!.toMutableSet().apply { if (adult && !e.adult) add(e.id) else remove(e.id) }
    p.edit().putString(K.EXT_ORDER, order.joinToString("\n")).putString(K.EXT_SOURCES, sources.toString()).putStringSet(K.EXT_ADULT, marked).apply()
}

/** Forgets the extension [id] (removed): its place in the lists. Its files: Extensions.remove. */
internal fun forgetExtension(p: SharedPreferences, id: String) {
    val sources = runCatching { JSONObject(p.getString(K.EXT_SOURCES, null) ?: "{}") }.getOrDefault(JSONObject()).apply { remove(id) }
    p.edit().putString(K.EXT_ORDER, p.getString(K.EXT_ORDER, "")!!.lines().filter { it.isNotBlank() && it != id }.joinToString("\n"))
        .putStringSet(K.EXT_OFF, p.getStringSet(K.EXT_OFF, emptySet())!! - id)
        .putStringSet(K.EXT_ADULT, p.getStringSet(K.EXT_ADULT, emptySet())!! - id)
        .putString(K.EXT_SOURCES, sources.toString()).apply()
}

/** The address an installed [e] is updated from: where it was added from, else its own updateUrl. */
internal fun extensionUpdateUrl(p: SharedPreferences, e: Extensions.Extension) = extensionSource(p, e.id) ?: e.updateUrl

/** A day: an extension's manifest-zephyrtv.json is read again once its last check is this old. */
private const val EXT_CHECK_MS = 24 * 3600_000L

private val extensionsChecking = java.util.concurrent.atomic.AtomicBoolean()

/**
 * In the background: the preinstalled extensions (assets/extensions), installed or brought up to the version this
 * Zephyr TV carries; the extensions in the order but missing (a backup put back on another TV), installed again from
 * their address; and each extension's address read once a day, a newer version installed. [done] on the UI thread,
 * with how many were installed or updated.
 */
internal fun MainActivity.refreshExtensions(force: Boolean = false, done: ((Int) -> Unit)? = null) {
    if (!extensionsChecking.compareAndSet(false, true)) return
    netIo.execute {
        var changed = 0
        try {
            changed += installBuiltIns()
            val installed = Extensions.installed(filesDir).associateBy { it.id }
            // Missing, with an address to have it from: installed again.
            for (id in prefs.getString(K.EXT_ORDER, "")!!.lines().filter { it.isNotBlank() && it !in installed }) {
                val url = extensionSource(prefs, id) ?: continue
                runCatching { Extensions.download(filesDir, Extensions.published(url)) }
                    .onSuccess { changed++ }.onFailure { warn("Extension not installed again: $id ($it)") }
            }
            for (e in installed.values) {
                val url = extensionUpdateUrl(prefs, e) ?: continue
                val stamp = File(Extensions.dataDir(filesDir, e.id), ".checked")
                if (!force && System.currentTimeMillis() - stamp.lastModified() < EXT_CHECK_MS) continue
                runCatching {
                    val p = Extensions.published(url)
                    stamp.writeText("")
                    if (p.id == e.id && Extensions.newer(p.version, e.version)) { Extensions.download(filesDir, p); changed++ }
                }.onFailure { warn("Extension update failed: ${e.name} ($it)") }
            }
        } finally {
            extensionsChecking.set(false)
            ui.post {
                if (changed > 0) {
                    Extensions.installed(filesDir).forEach { Stremio.forget(filesDir, ExtensionCalls.url(it.id)) }
                    addonsChanged()
                }
                done?.invoke(changed)
            }
        }
    }
}

/** The extensions that come with Zephyr TV (the .zip files in assets/extensions): installed when missing or older. Off the UI thread. */
private fun MainActivity.installBuiltIns(): Int {
    var changed = 0
    val names = runCatching { assets.list("extensions")?.filter { it.endsWith(".zip") } }.getOrNull().orEmpty()
    for (name in names) {
        val zip = File(cacheDir, "builtin-$name")
        runCatching {
            assets.open("extensions/$name").use { input -> zip.outputStream().use { input.copyTo(it) } }
            val m = Extensions.manifestOf(zip) ?: return@runCatching
            val id = m.optString("id")
            val have = Extensions.installed(filesDir, id)
            if (have == null || Extensions.newer(m.optString("version"), have.version)) {
                val e = Extensions.install(filesDir, zip)
                if (have == null) rememberExtension(prefs, e, null, false)
                changed++
            }
            prefs.edit().putStringSet(K.EXT_BUILTIN, prefs.getStringSet(K.EXT_BUILTIN, emptySet())!! + id).apply()
        }.onFailure { warn("Preinstalled extension failed: $name ($it)") }
        zip.delete()
    }
    return changed
}

// ---------- settings ----------

/** What Zephyr TV says when an extension can't be installed. */
private fun MainActivity.refusal(e: Throwable): String = when ((e as? Extensions.Refused)?.why) {
    Extensions.Why.NEWER_STANDARD -> getString(R.string.ext_err_newer)
    Extensions.Why.NEEDS_FEATURES -> getString(R.string.ext_err_needs, e.message?.substringAfter(": ").orEmpty())
    Extensions.Why.TOO_LARGE -> getString(R.string.ext_err_large)
    Extensions.Why.DAMAGED -> getString(R.string.ext_err_damaged)
    Extensions.Why.DIFFERENT -> getString(R.string.ext_err_different)
    Extensions.Why.UNREACHABLE -> getString(R.string.ext_err_unreachable)
    else -> getString(R.string.ext_err_not)
}

/** What an extension with these [types] and [resources] provides, in words: "Films, Series, TV guide". */
private fun MainActivity.provides(types: List<String>, resources: List<String>): String {
    val kinds = types.mapNotNull { t ->
        when (t) {
            "movie" -> R.string.vod_movies
            "series" -> R.string.vod_series
            "tv" -> R.string.setting_addons_tv
            "radio" -> R.string.ext_kind_radio
            "video", "channel" -> R.string.ext_kind_videos
            "course" -> R.string.ext_kind_courses
            "podcast" -> R.string.ext_kind_podcasts
            "audiobook" -> R.string.ext_kind_audiobooks
            "event" -> R.string.ext_kind_events
            else -> null
        }
    }.distinct().map { getString(it) }
    val more = listOfNotNull(
        getString(R.string.ext_kind_streams).takeIf { "stream" in resources && "catalog" !in resources },
        getString(R.string.ext_kind_guide).takeIf { "guide" in resources },
    )
    return (kinds + more).joinToString(", ")
}

/** A page's text on top: [text], dimmed. */
private fun MainActivity.intro(text: String) = label(text, 13f).apply {
    alpha = 0.7f
    maxLines = 10
    setLineSpacing(0f, 1.15f)
    setPadding(dp(14), 0, dp(14), dp(12))
}

/** Extensions: what they are (and that they run code from elsewhere), each one, Add an extension, Check for updates. */
internal fun MainActivity.extensionsPage(focus: Int = 0) {
    val all = extensions(prefs, filesDir)
    showPage(getString(R.string.ext_title), all.map { e ->
        page(e.name, if (extensionOn(prefs, e.id)) SUBMENU else getString(R.string.state_off)) { extensionPage(e.id) }
    } + listOf(
        page(getString(R.string.ext_add), SUBMENU) { extensionFromPhone() },
        page(getString(R.string.ext_check)) {
            Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
            refreshExtensions(force = true) { n ->
                Toast.makeText(this, if (n == 0) getString(R.string.ext_none_updated) else getString(R.string.ext_some_updated, n), Toast.LENGTH_LONG).show()
                if (pageShowing(getString(R.string.ext_title))) extensionsPage(all.size + 1)
            }
        },
    ), focus, top = intro(getString(R.string.ext_intro))) { addonsAndPlugins(3) }
}

/** An extension's page: what it is, On, 18+, up or down the list, Update now, Remove (not a preinstalled one). */
private fun MainActivity.extensionPage(id: String, focus: Int = 0) {
    val e = Extensions.installed(filesDir, id) ?: return extensionsPage()
    val ids = extensions(prefs, filesDir).map { it.id }
    val at = ids.indexOf(id)
    val on = extensionOn(prefs, id)
    val builtIn = extensionBuiltIn(prefs, id)
    val about = listOfNotNull(
        e.description.takeIf { it.isNotBlank() },
        listOfNotNull("v${e.version}", e.author.takeIf { it.isNotBlank() }, getString(R.string.ext_builtin).takeIf { builtIn }).joinToString(" · "),
        provides(e.types, e.resources).takeIf { it.isNotEmpty() }?.let { getString(R.string.ext_provides, it) },
    ).joinToString("\n")
    fun move(by: Int) {
        val list = ids.toMutableList()
        list.add(at + by, list.removeAt(at))
        prefs.edit().putString(K.EXT_ORDER, list.joinToString("\n")).apply()
        addonsChanged()
    }
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    rows += page(getString(R.string.addon_on), toggle(on)) {
        prefs.edit().putStringSet(K.EXT_OFF, prefs.getStringSet(K.EXT_OFF, emptySet())!!.let { if (on) it + id else it - id }).apply()
        extensionChanged(id)
        extensionPage(id, 0)
    }
    // One that says it's 18+ is, whatever the switch: the switch is for those that don't say.
    if (!e.adult) {
        val marked = id in prefs.getStringSet(K.EXT_ADULT, emptySet())!!
        val row = rows.size
        rows += page(getString(R.string.addon_adult), toggle(marked)) {
            prefs.edit().putStringSet(K.EXT_ADULT, prefs.getStringSet(K.EXT_ADULT, emptySet())!!.let { if (marked) it - id else it + id }).apply()
            addonsChanged()
            extensionPage(id, row)
        }
    }
    val moves = rows.size
    if (at > 0) rows += page(getString(R.string.addon_move_up)) { move(-1); extensionPage(id, moves) }
    if (at in 0 until ids.size - 1) rows += page(getString(R.string.addon_move_down)) { move(+1); extensionPage(id, moves) }
    extensionUpdateUrl(prefs, e)?.let { url ->
        val row = rows.size
        rows += page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
            netIo.execute {
                val result = runCatching {
                    val p = Extensions.published(url)
                    if (p.id == e.id && Extensions.newer(p.version, e.version)) Extensions.download(filesDir, p) else null
                }
                ui.post {
                    val text = result.fold({ got -> if (got == null) getString(R.string.ext_up_to_date, e.name) else getString(R.string.ext_updated, got.name, got.version) },
                        { refusal(it) })
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                    if (result.getOrNull() != null) extensionChanged(id)
                    if (pageShowing(e.name)) extensionPage(id, row)
                }
            }
        }
    }
    if (!builtIn) rows += page(getString(R.string.addon_remove)) {
        Extensions.remove(filesDir, id)
        forgetExtension(prefs, id)
        extensionChanged(id)
        extensionsPage(at.coerceAtMost(ids.size - 2).coerceAtLeast(0))
    }
    showPage(e.name, rows, focus, top = intro(about)) { extensionsPage(at.coerceAtLeast(0)) }
}

/**
 * Before installing: what the extension is ([name], [author], [description], [version], what it provides), that it
 * runs code by someone else, 18+ ([adult]), Install. [install] does it (off the UI thread), [back] leaves.
 */
private fun MainActivity.extensionPreview(name: String, author: String, description: String, version: String, kinds: String,
                                          adult: Boolean, back: () -> Unit, install: (adult: Boolean) -> Extensions.Extension) {
    val about = listOfNotNull(
        description.takeIf { it.isNotBlank() },
        listOfNotNull("v$version", author.takeIf { it.isNotBlank() }).joinToString(" · "),
        kinds.takeIf { it.isNotEmpty() }?.let { getString(R.string.ext_provides, it) },
        "",
        if (author.isNotBlank()) getString(R.string.ext_warning, author) else getString(R.string.ext_warning_anon),
    ).joinToString("\n")
    showPage(name, listOf(
        page(getString(R.string.addon_adult), toggle(adult)) { extensionPreview(name, author, description, version, kinds, !adult, back, install) },
        page(getString(R.string.ext_install)) {
            Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
            netIo.execute {
                val result = runCatching { install(adult) }
                ui.post {
                    result.onSuccess { e ->
                        Toast.makeText(this, getString(R.string.ext_installed, e.name, e.version), Toast.LENGTH_LONG).show()
                        extensionChanged(e.id)
                        extensionPage(e.id)
                    }.onFailure { Toast.makeText(this, refusal(it), Toast.LENGTH_LONG).show() }
                }
            }
        },
    ), 1, top = intro(about), back = back)
}

/** The extension published at [input] (a manifest-zephyrtv.json, or its folder): read, then shown before installing. */
private fun MainActivity.extensionFromAddress(input: String) {
    val url = Extensions.publishedUrl(input) ?: return Toast.makeText(this, getString(R.string.ext_err_not), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.ext_reading), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val result = runCatching { Extensions.published(url) }
        ui.post {
            result.onSuccess { p ->
                // What it provides is in its package, not read yet: shown once installed.
                extensionPreview(p.name, p.author, p.description, p.version, "", p.adult, { extensionsPage() }) { adult ->
                    Extensions.download(filesDir, p).also { rememberExtension(prefs, it, url, adult) }
                }
            }.onFailure { Toast.makeText(this, refusal(it), Toast.LENGTH_LONG).show() }
        }
    }
}

/** The extension in the package [zip] (sent from a phone): shown before installing, then installed from it. */
private fun MainActivity.extensionFromZip(zip: File) {
    val m = Extensions.manifestOf(zip) ?: return Toast.makeText(this, getString(R.string.ext_err_not), Toast.LENGTH_LONG).show()
    val types = m.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val resources = m.optJSONArray("resources")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String) ?: (a.opt(it) as? JSONObject)?.optString("name") } }.orEmpty()
    extensionPreview(m.optString("name").ifBlank { zip.name }, m.optString("author"), m.optString("description"), m.optString("version"),
        provides(types, resources), m.optJSONObject("behaviorHints")?.optBoolean("adult") == true, { zip.delete(); extensionsPage() }) { adult ->
        Extensions.install(filesDir, zip).also { rememberExtension(prefs, it, null, adult); zip.delete() }
    }
}

/** An extension's address typed with the remote. */
private fun MainActivity.typeExtension() {
    textPage(getString(R.string.ext_add), "", getString(R.string.ext_hint), getString(R.string.ext_add_intro), back = { extensionFromPhone() }) { typed ->
        listOf(page(getString(R.string.ext_install)) { extensionFromAddress(typed()) })
    }
}

/** Add an extension: its address pasted on a phone (QR code), its .zip sent from a phone, or the address typed. */
private fun MainActivity.extensionFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.text(phoneTexts(R.string.ext_phone_title, R.string.ext_add_intro), getString(R.string.ext_hint))
    showPhoneLink(getString(R.string.ext_add), PhoneTask(page, 4096) { file ->
        val url = Extensions.publishedUrl(file.readText().trim()) ?: return@PhoneTask 400 to getString(R.string.ext_err_not)
        ui.post { extensionFromAddress(url) }
        200 to getString(R.string.ext_received)
    }, status, listOf(
        page(getString(R.string.ext_zip)) { extensionZipFromPhone() },
        page(getString(R.string.addon_type)) { typeExtension() },
    )) { extensionsPage(extensions(prefs, filesDir).size) }
}

/** An extension's .zip sent from a phone. */
private fun MainActivity.extensionZipFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.file(phoneTexts(R.string.ext_phone_title, R.string.ext_zip_intro), getString(R.string.ext_choose_zip), ".zip,application/zip")
    showPhoneLink(getString(R.string.ext_zip), PhoneTask(page, 10L * 1024 * 1024) { file ->
        val zip = File(cacheDir, "extension-${System.currentTimeMillis()}.zip")
        file.copyTo(zip, overwrite = true)
        if (Extensions.manifestOf(zip) == null) { zip.delete(); return@PhoneTask 415 to getString(R.string.ext_err_not) }
        ui.post { extensionFromZip(zip) }
        200 to getString(R.string.ext_received)
    }, status) { extensionFromPhone() }
}

/** The extensions on, as add-ons' addresses (see ExtensionCalls), in their order: the rest of Zephyr TV asks them so. */
internal fun MainActivity.extensionAddonUrls(): List<String> =
    extensions(prefs, filesDir).filter { extensionOn(prefs, it.id) }.map { ExtensionCalls.url(it.id) }

/** After an extension was installed, updated, removed or switched: what Zephyr TV kept of it as an add-on, read anew. */
internal fun MainActivity.extensionChanged(id: String) {
    Stremio.forget(filesDir, ExtensionCalls.url(id))
    addonsChanged()
}
