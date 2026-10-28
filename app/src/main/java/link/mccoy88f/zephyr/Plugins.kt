package link.mccoy88f.zephyr

import android.content.SharedPreferences
import android.widget.Toast
import java.io.File
import link.mccoy88f.zephyr.MainActivity.Companion.SUBMENU

// Nuvio's plugins in Zephyr TV: the repositories added, which of their plugins are on, which repositories are 18+,
// and the plugins as stream sources next to the Stremio add-ons (for the title pages and the player alike). The
// plugins themselves, and the engine they run in, are in NuvioPlugins. Settings → Add-ons and plugins → Nuvio.

/** A stream source that's a plugin, in the lists of sources the add-ons are in: "nuvio:<manifest>#<plugin id>". */
internal const val PLUGIN = "nuvio:"

/** The repositories added, as manifest addresses, in the order added. */
internal fun nuvioRepoUrls(p: SharedPreferences) = p.getString(K.NUVIO_REPOS, "")!!.lines().filter { it.isNotBlank() }

/** The repositories marked 18+: their plugins only while 18+ shows (see adultShows). */
internal fun nuvioAdultRepos(p: SharedPreferences) = p.getStringSet(K.NUVIO_ADULT, emptySet())!!

private fun pluginKey(repo: String, s: Nuvio.Scraper) = "$repo#${s.id}"

/** Whether [s] of [repo] is on: as its manifest says, unless the user switched it. */
internal fun pluginOn(p: SharedPreferences, repo: String, s: Nuvio.Scraper): Boolean {
    val key = pluginKey(repo, s)
    return key in p.getStringSet(K.NUVIO_ON, emptySet())!! || (s.enabled && key !in p.getStringSet(K.NUVIO_OFF, emptySet())!!)
}

/**
 * The plugins on that can find streams for an item of [type] with this [id], as stream sources (source, name): the
 * 18+ repositories' only when [adult]. For the title pages, and the player for a channel.
 */
internal fun pluginSources(p: SharedPreferences, dir: File, type: String, id: String, adult: Boolean): List<Pair<String, String>> {
    val hidden = (if (adult) emptySet() else nuvioAdultRepos(p)) + p.getStringSet(K.NUVIO_REPOS_OFF, emptySet())!!
    return nuvioRepoUrls(p).filter { it !in hidden }.flatMap { repo ->
        Nuvio.saved(dir, repo)?.scrapers.orEmpty().filter { pluginOn(p, repo, it) && Nuvio.provides(it, type, id) }
            .map { "$PLUGIN$repo#${it.id}" to it.name }
    }
}

/** The stream sources of the 18+ repositories' plugins: the player keeps no history of what they play. */
internal fun adultPluginSources(p: SharedPreferences, dir: File) = nuvioAdultRepos(p).flatMap { repo ->
    Nuvio.saved(dir, repo)?.scrapers.orEmpty().map { "$PLUGIN$repo#${it.id}" }
}

/**
 * The streams of a film or episode from the source [base] (named [name]): a Stremio add-on asked, or a Nuvio plugin
 * run. Throws when an add-on can't be asked; a plugin that fails finds none. Network: off the UI thread.
 */
internal fun sourceStreams(p: SharedPreferences, dir: File, base: String, name: String, type: String, id: String): List<Stremio.Stream> {
    if (!base.startsWith(PLUGIN)) return Stremio.titleStreams(base, name, type, id)
    val repo = base.removePrefix(PLUGIN).substringBeforeLast('#')
    val s = Nuvio.saved(dir, repo)?.scrapers?.firstOrNull { it.id == base.substringAfterLast('#') } ?: return emptyList()
    return Nuvio.streams(dir, repo, s, name, type, id, p.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() })
}

/** A day: a repository is read again (new plugins, new versions of them) once its copy is this old. */
private const val REPO_AGE_MS = 24 * 3600_000L

/** The repositories read a day ago or more, read again in the background. */
internal fun MainActivity.refreshPlugins() {
    val due = nuvioRepoUrls(prefs).filter { Nuvio.age(filesDir, it) > REPO_AGE_MS }
    if (due.isNotEmpty()) netIo.execute { due.forEach { Nuvio.update(filesDir, it) } }
}

// ---------- settings ----------

/** Add-ons and plugins: Stremio's add-ons (their section) and Nuvio's plugins, each a page of its own. */
internal fun MainActivity.addonsAndPlugins(focus: Int = 0) {
    val repos = nuvioRepoUrls(prefs)
    showPage(getString(R.string.settings_section_addons_plugins), listOf(
        page(getString(R.string.addons_stremio), "${addonUrls().size}") { renderSettings(MainActivity.ADDONS_TV_ROW) },
        page(getString(R.string.nuvio_plugins), "${repos.size}") { nuvioPage() },
    ), focus) { renderSettingsHome(4) } // Add-ons and plugins' row on the settings' first page
}

/** Nuvio's page: what plugins are (and that they run code from elsewhere), each repository, and Add a repository. */
internal fun MainActivity.nuvioPage(focus: Int = 0) {
    val repos = nuvioRepoUrls(prefs)
    val intro = label(getString(R.string.nuvio_intro), 13f).apply {
        alpha = 0.7f
        maxLines = 8
        setLineSpacing(0f, 1.15f)
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    showPage(getString(R.string.nuvio_plugins), repos.map { url ->
        val r = Nuvio.saved(filesDir, url)
        val on = r?.scrapers?.count { pluginOn(prefs, url, it) } ?: 0
        val value = when {
            url in prefs.getStringSet(K.NUVIO_REPOS_OFF, emptySet())!! -> getString(R.string.state_off)
            r == null -> SUBMENU
            else -> getString(R.string.nuvio_plugins_on, on, r.scrapers.size)
        }
        page(r?.name ?: url, value) { repoPage(url) }
    } + page(getString(R.string.nuvio_add), SUBMENU) { nuvioFromPhone() }, focus, top = intro) { addonsAndPlugins(1) }
}

/**
 * A repository's page: on or off (off, its plugins aren't asked), each plugin with its switch, 18+, up or down the
 * list (its plugins' Watch on rows come in this order), Update, Remove.
 */
private fun MainActivity.repoPage(url: String, focus: Int = 0) {
    val r = Nuvio.saved(filesDir, url)
    val scrapers = r?.scrapers.orEmpty()
    val at = nuvioRepoUrls(prefs).indexOf(url)
    val about = listOfNotNull(r?.description?.takeIf { it.isNotBlank() },
        listOfNotNull(r?.version?.takeIf { it.isNotBlank() }?.let { "v$it" }, r?.author?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null })
        .joinToString("\n")
    val top = label(about, 13f).apply {
        alpha = 0.7f
        maxLines = 4
        setPadding(dp(14), 0, dp(14), dp(12))
    }
    fun switch(set: String, key: String, add: Boolean) {
        val now = prefs.getStringSet(set, emptySet())!!.toMutableSet()
        if (add) now += key else now -= key
        prefs.edit().putStringSet(set, now).apply()
    }
    val adult = url in nuvioAdultRepos(prefs)
    val repoOn = url !in prefs.getStringSet(K.NUVIO_REPOS_OFF, emptySet())!!
    val urls = nuvioRepoUrls(prefs)
    fun move(by: Int) {
        val list = urls.toMutableList()
        list.add(at + by, list.removeAt(at))
        prefs.edit().putString(K.NUVIO_REPOS, list.joinToString("\n")).apply()
    }
    val moves = listOfNotNull(
        if (at > 0) page(getString(R.string.addon_move_up)) { move(-1); repoPage(url, scrapers.size + 2) } else null,
        if (at < urls.size - 1) page(getString(R.string.addon_move_down)) { move(+1); repoPage(url, scrapers.size + 2) } else null,
    )
    showPage(r?.name ?: url, listOf(
        page(getString(R.string.addon_on), toggle(repoOn)) {
            switch(K.NUVIO_REPOS_OFF, url, repoOn)
            repoPage(url, 0)
        },
    ) + scrapers.mapIndexed { i, s ->
        page(s.name, toggle(pluginOn(prefs, url, s))) {
            val key = pluginKey(url, s)
            val on = !pluginOn(prefs, url, s)
            switch(K.NUVIO_ON, key, on && !s.enabled)
            switch(K.NUVIO_OFF, key, !on && s.enabled)
            repoPage(url, 1 + i)
        }
    } + listOf(
        page(getString(R.string.addon_adult), toggle(adult)) {
            switch(K.NUVIO_ADULT, url, !adult)
            repoPage(url, scrapers.size + 1)
        },
    ) + moves + listOf(
        page(getString(R.string.addon_update)) {
            Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
            netIo.execute {
                val again = Nuvio.update(filesDir, url)
                ui.post {
                    Toast.makeText(this, if (again == null) getString(R.string.toast_addon_failed)
                        else getString(R.string.nuvio_added, again.name, again.scrapers.size), Toast.LENGTH_LONG).show()
                    if (pageShowing(again?.name ?: "")) repoPage(url, scrapers.size + 2 + moves.size)
                }
            }
        },
        page(getString(R.string.addon_remove)) {
            prefs.edit().putString(K.NUVIO_REPOS, (nuvioRepoUrls(prefs) - url).joinToString("\n")).apply()
            switch(K.NUVIO_ADULT, url, false)
            switch(K.NUVIO_REPOS_OFF, url, false)
            Nuvio.forget(filesDir, url)
            nuvioPage()
        },
    ), focus, top = if (about.isEmpty()) null else top) { nuvioPage(at.coerceAtLeast(0)) }
}

/** Adds the repository at [input] once its manifest answers with plugins, telling how it went. */
private fun MainActivity.addRepo(input: String, adult: Boolean) {
    val url = Nuvio.manifestUrl(input) ?: return Toast.makeText(this, getString(R.string.phone_epg_bad), Toast.LENGTH_LONG).show()
    Toast.makeText(this, getString(R.string.addon_checking), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val r = Nuvio.update(filesDir, url)
        ui.post {
            when {
                r == null -> Toast.makeText(this, getString(R.string.toast_addon_failed), Toast.LENGTH_LONG).show()
                r.scrapers.isEmpty() -> {
                    Nuvio.forget(filesDir, url)
                    Toast.makeText(this, getString(R.string.nuvio_empty), Toast.LENGTH_LONG).show()
                }
                else -> {
                    prefs.edit().putString(K.NUVIO_REPOS, (nuvioRepoUrls(prefs) + url).distinct().joinToString("\n")).apply()
                    if (adult) prefs.edit().putStringSet(K.NUVIO_ADULT, nuvioAdultRepos(prefs) + url).apply()
                    Toast.makeText(this, getString(R.string.nuvio_added, r.name, r.scrapers.size), Toast.LENGTH_LONG).show()
                    if (settingsScrim.visibility == android.view.View.VISIBLE) nuvioPage(nuvioRepoUrls(prefs).indexOf(url))
                }
            }
        }
    }
}

/** A repository's address typed with the remote, and whether it's 18+. */
private fun MainActivity.typeRepo(initial: String = "", adult: Boolean = false) {
    textPage(getString(R.string.nuvio_add), initial, getString(R.string.nuvio_hint), getString(R.string.nuvio_add_intro), back = { nuvioFromPhone() }) { typed ->
        listOf(
            page(getString(R.string.addon_adult), toggle(adult)) { typeRepo(typed(), !adult) },
            page(getString(R.string.dialog_save)) { addRepo(typed(), adult) },
        )
    }
}

/** Add a repository: a QR code to paste its address on a phone (with the 18+ box), or typed with the remote. */
private fun MainActivity.nuvioFromPhone() {
    val status = label(getString(R.string.addon_phone_waiting), 14f)
    val page = PhonePages.addon(phoneTexts(R.string.nuvio_phone_title, R.string.nuvio_add_intro), getString(R.string.nuvio_hint), getString(R.string.addon_adult))
    showPhoneLink(getString(R.string.nuvio_add), PhoneTask(page, 4096) { file ->
        val lines = file.readText().lines()
        val url = Nuvio.manifestUrl(lines.firstOrNull().orEmpty()) ?: return@PhoneTask 400 to getString(R.string.phone_epg_bad)
        val adult = lines.getOrNull(1)?.trim() == "adult"
        ui.post {
            status.text = getString(R.string.addon_checking)
            addRepo(url, adult)
        }
        200 to getString(R.string.phone_addon_saved)
    }, status, listOf(page(getString(R.string.addon_type)) { typeRepo() })) { nuvioPage(nuvioRepoUrls(prefs).size) }
}
