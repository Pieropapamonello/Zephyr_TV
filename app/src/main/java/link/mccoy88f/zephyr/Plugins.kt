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
    val lang = streamLanguage(p)
    return nuvioRepoUrls(p).filter { it !in hidden }.flatMap { repo ->
        Nuvio.saved(dir, repo)?.scrapers.orEmpty().filter { pluginOn(p, repo, it) && Nuvio.provides(it, type, id) }
            // A plugin for other languages only (its manifest's contentLanguage) isn't asked, with the language on.
            .filter { s -> lang == null || s.languages.isEmpty() || s.languages.any { it.lowercase().startsWith(lang) } }
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
    val lang = streamLanguage(p)
    var declared = emptySet<String>() // a plugin's languages (its manifest's): a stream of it that says none is in those
    val all = if (!base.startsWith(PLUGIN)) Stremio.titleStreams(base, name, type, id) else {
        val repo = base.removePrefix(PLUGIN).substringBeforeLast('#')
        val s = Nuvio.saved(dir, repo)?.scrapers?.firstOrNull { it.id == base.substringAfterLast('#') } ?: return emptyList()
        declared = s.languages.map { it.lowercase().take(2) }.toSet()
        Nuvio.streams(dir, repo, s, name, type, id, p.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() })
    }
    val hideUnknown = p.getBoolean(K.HIDE_UNKNOWN_LANG, true)
    return if (lang == null) all else all.filter { inLanguage(it.title, lang, declared, hideUnknown) }
}

/**
 * The language streams are kept in ("it", from the TMDB language "it-IT"), or null when Content → Streams in the TV's
 * language is off: then every stream is kept.
 */
internal fun streamLanguage(p: SharedPreferences): String? {
    if (!p.getBoolean(K.STREAM_LANG_ONLY, true)) return null
    return (p.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()).substringBefore('-').lowercase()
}

/**
 * The words and flags a stream's name says its language with, by language: those add-ons and plugins write ("ITA",
 * "🇮🇹", "Italian", "SUB-ITA"). Words only whole, and none that's an everyday word too.
 */
private val LANGUAGE_MARKS = mapOf(
    "it" to listOf("ita", "italian", "italiano", "\uD83C\uDDEE\uD83C\uDDF9"),
    "en" to listOf("eng", "english", "\uD83C\uDDEC\uD83C\uDDE7", "\uD83C\uDDFA\uD83C\uDDF8"),
    "es" to listOf("spa", "esp", "spanish", "español", "castellano", "\uD83C\uDDEA\uD83C\uDDF8", "\uD83C\uDDF2\uD83C\uDDFD"),
    "fr" to listOf("fre", "french", "français", "vff", "vostfr", "\uD83C\uDDEB\uD83C\uDDF7"),
    "de" to listOf("ger", "deu", "german", "deutsch", "\uD83C\uDDE9\uD83C\uDDEA"),
    "pt" to listOf("portuguese", "português", "\uD83C\uDDF5\uD83C\uDDF9", "\uD83C\uDDE7\uD83C\uDDF7"),
    "hi" to listOf("hin", "hindi", "\uD83C\uDDEE\uD83C\uDDF3"),
    "ta" to listOf("tamil"), "te" to listOf("telugu"), "ml" to listOf("malayalam"), "kn" to listOf("kannada"),
    "ja" to listOf("jpn", "japanese", "\uD83C\uDDEF\uD83C\uDDF5"), "ko" to listOf("korean", "\uD83C\uDDF0\uD83C\uDDF7"),
    "ar" to listOf("arabic"), "ru" to listOf("rus", "russian", "\uD83C\uDDF7\uD83C\uDDFA"),
)

/**
 * The picture's height a stream's name says ("2160p", "4K", "FHD", "720"), for putting the best first; [UNKNOWN_QUALITY]
 * when it says none (an "Auto" HLS one, which picks as it plays, among them).
 */
internal fun streamQuality(title: String): Int {
    val t = title.lowercase()
    Regex("""(?<![0-9])(4320|2160|1440|1080|720|576|540|480|360|240)\s*[pi]?(?![0-9])""").findAll(t)
        .map { it.groupValues[1].toInt() }.maxOrNull()?.let { return it }
    return when {
        Regex("""(?<![\p{L}0-9])(8k)(?![\p{L}])""").containsMatchIn(t) -> 4320
        Regex("""(?<![\p{L}0-9])(4k|uhd)(?![\p{L}])""").containsMatchIn(t) -> 2160
        Regex("""(?<![\p{L}0-9])(qhd|2k)(?![\p{L}])""").containsMatchIn(t) -> 1440
        Regex("""(?<![\p{L}])(fhd|full[\s_.-]*hd)(?![\p{L}])""").containsMatchIn(t) -> 1080
        Regex("""(?<![\p{L}])hd(?![\p{L}])""").containsMatchIn(t) -> 720
        Regex("""(?<![\p{L}])sd(?![\p{L}])""").containsMatchIn(t) -> 480
        else -> UNKNOWN_QUALITY
    }
}

/** A stream that says no quality ranks as this: after the full HD ones, before the plain HD ones. */
internal const val UNKNOWN_QUALITY = 900

/** The qualities Content → Preferred quality offers, by height; 0 is the highest there is. */
internal val QUALITIES = listOf(0, 2160, 1440, 1080, 720, 480)

/** The quality streams are preferred in (see QUALITIES): 0, the highest, by default. */
internal fun preferredQuality(p: SharedPreferences) = p.getInt(K.STREAM_QUALITY, 0)

/**
 * Streams in the order of [preferred]: those up to it first, the best of them first; then those above it, the
 * nearest first (a 4K one stays, after the 1080p ones, for a TV set to 1080p). With 0, simply the best first.
 */
internal fun qualityOrder(preferred: Int): Comparator<Stremio.Stream> = compareBy<Stremio.Stream> { s ->
    if (preferred == 0 || streamQuality(s.title) <= preferred) 0 else 1
}.thenBy { s -> streamQuality(s.title).let { q -> if (preferred == 0 || q <= preferred) -q else q } }

/** The languages [title] says a stream is in, as LANGUAGE_MARKS knows them; none when it doesn't say. */
internal fun streamLanguages(title: String): Set<String> {
    val t = title.lowercase()
    return LANGUAGE_MARKS.filterValues { marks ->
        marks.any { m -> if (m.first().isLetter()) Regex("(?<![\\p{L}])${Regex.escape(m)}(?![\\p{L}])").containsMatchIn(t) else m in title }
    }.keys
}

/**
 * Whether a stream named [title] is in [lang]: it says so (or "multi"), or, saying none, its plugin's [declared]
 * languages have it; saying none and from a source that doesn't tell, only when [hideUnknown] is off.
 */
internal fun inLanguage(title: String, lang: String, declared: Set<String> = emptySet(), hideUnknown: Boolean = true): Boolean {
    if (Regex("(?<![\\p{L}])multi").containsMatchIn(title.lowercase())) return true
    val said = streamLanguages(title).ifEmpty { declared }
    return if (said.isEmpty()) !hideUnknown else lang in said
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
    // Where their content shows (TV channels, films and series, adult ones), then Stremio's add-ons and Nuvio's plugins.
    showPage(getString(R.string.settings_section_addons_plugins), placementRows { addonsAndPlugins(it) } + listOf(
        page(getString(R.string.addons_stremio), "${addonUrls().size}") { renderSettings(MainActivity.ADDON_ADD_ROW) },
        page(getString(R.string.nuvio_plugins), "${repos.size}") { nuvioPage() },
    ), focus) { renderSettingsHome(HOME_SECTIONS.indexOf(MainActivity.ADDONS_SECTION)) }
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
    } + page(getString(R.string.nuvio_add), SUBMENU) { nuvioFromPhone() }, focus, top = intro) { addonsAndPlugins(4) }
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
