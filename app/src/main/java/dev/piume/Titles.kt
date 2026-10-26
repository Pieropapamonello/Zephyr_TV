package dev.piume

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import dev.piume.MainActivity.Prog

// Films and series from the Stremio add-ons on the home screen: the Films and Series tiles (or each add-on's own),
// a title's page with its seasons and episodes, playing one in Piume's player, the catalogs in the search, and
// films and episodes begun in Continue Playing. Functions of MainActivity, like the other topics; the add-ons
// themselves are read by Stremio.

/** App.pkg of the Films and Series tiles (see addonsVod), and of the Continue Playing cards of films and episodes. */
internal const val MOVIES_PKG = STREMIO + "movies"
internal const val SERIES_PKG = STREMIO + "series"
private const val VOD_PKG = STREMIO + "vod"

/**
 * A film or series a card stands for: what to ask its page for ([type], [id]), its name and art (as the card shows
 * it, for its page and the player), the add-on it came from ([source]: its manifest's address, asked for the page
 * first), and, on a Continue Playing card, the film or episode to go on with ([video]).
 */
internal class TitleRef(val type: String, val id: String, val name: String, val poster: String?, val background: String?,
                        val source: String? = null, val video: String? = null, val logo: String? = null, val rating: String? = null)

/** Cinemeta's manifest: Stremio's catalog of films and series, always the library's first; its address can be changed. */
internal fun MainActivity.cinemetaUrl(): String = prefs.getString(K.CINEMETA_URL, null)?.takeIf { it.isNotBlank() } ?: Stremio.CINEMETA

/** Where films and series show: "tiles" (a Films tile and a Series tile), "apps" (each add-on's own tile), "off". */
internal fun MainActivity.addonsVod(): String = prefs.getString(K.ADDONS_VOD, null)?.takeIf { it in listOf("tiles", "apps", "off") } ?: "tiles"

/** The add-ons films and series are listed from (Cinemeta first), as manifest addresses. */
internal fun MainActivity.libraryUrls(): List<String> = (listOf(cinemetaUrl()) + addonUrls()).distinct()

/** The add-ons as read, those with film or series catalogs or streams. Reads them when they're missing (in the background). */
private fun MainActivity.vodAddons(): List<Stremio.Addon> = libraryUrls().mapNotNull { url ->
    Stremio.saved(filesDir, url)?.addon ?: null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
}

/** The add-ons that give streams for a film or episode of [type] with this [id] (address without /manifest.json, and name). */
internal fun MainActivity.streamSources(type: String, id: String): List<Pair<String, String>> =
    addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.filter { it.provides("stream", type, id) }.map { it.base to it.name }

/** The tiles films and series add to the apps row: Films and Series, or nothing here (each add-on's own, see addonApps). */
internal fun MainActivity.vodApps(): List<MainActivity.App> {
    if (addonsVod() != "tiles" || libraryUrls().isEmpty()) return emptyList()
    return listOf(
        MainActivity.App(MOVIES_PKG, getString(R.string.vod_movies), Intent(), getDrawable(R.drawable.ic_movie)!!),
        MainActivity.App(SERIES_PKG, getString(R.string.vod_series), Intent(), getDrawable(R.drawable.ic_series)!!),
    )
}

/** The add-ons with film or series catalogs that get a tile of their own (films and series "apps"). */
internal fun MainActivity.vodTileAddons(): List<Stremio.Addon> =
    if (addonsVod() != "apps") emptyList() else vodAddons().filter { a -> a.vod.any { it.listed } }

/**
 * The rows of films or series under the tile [pkg]: under Films or Series, every add-on's catalogs of that type
 * ("Cinemeta · Popular"); under an add-on's own tile, its catalogs of both. A catalog read a few hours ago is read
 * again in the background, and the rows built again once it's in. Runs off the UI thread.
 */
internal fun MainActivity.titleSections(pkg: String, hidden: Set<String>): List<Section> {
    val mode = addonsVod()
    val type = when (pkg) { MOVIES_PKG -> "movie"; SERIES_PKG -> "series"; else -> null }
    val addons = when {
        mode == "tiles" && type != null -> vodAddons()
        mode == "apps" && pkg.startsWith(STREMIO) -> vodAddons().filter { STREMIO + it.id == pkg }
        else -> return emptyList()
    }
    val missing = ArrayList<String>()
    val watched = VodWatched.all(vodDir())
    return addons.flatMap { addon ->
        if (Stremio.vodDue(filesDir, addon.url) && addon.vod.any { it.listed }) refreshShelves(addon, pkg)
        Stremio.shelves(filesDir, addon).filter { type == null || it.catalog.type == type }.mapNotNull { shelf ->
            val kind = getString(if (shelf.catalog.type == "series") R.string.vod_series else R.string.vod_movies)
            val name = if (type != null) "${addon.name} · ${shelf.catalog.name}" else "$kind · ${shelf.catalog.name}"
            // In the TV's language when TMDB has the title (those it hasn't been asked about yet: once it's answered).
            val cards = shelf.titles.map { t -> localize(t) ?: t.also { if (wantsLocal(it)) missing += it.id } }
                .filter { hideKey(null, it.name) !in hidden }.map { titleCard(it, addon.url, pkg, it.id in watched) }
            if (cards.isEmpty()) null else Section("vod:${addon.id}:${shelf.catalog.type}:${shelf.catalog.id}", name, name, cards)
        }
    }.also { if (missing.isNotEmpty()) localizeLater(missing.distinct(), pkg) }
}

private val refreshing = java.util.Collections.synchronizedSet(HashSet<String>())

/** Reads [addon]'s film and series catalogs again; once they're in, the rows under [pkg] again if it's still in focus. */
private fun MainActivity.refreshShelves(addon: Stremio.Addon, pkg: String) {
    if (!refreshing.add(addon.url)) return
    netIo.execute {
        val ok = runCatching { Stremio.updateShelves(filesDir, addon) }.onFailure { warn("Stremio catalogs failed: ${android.net.Uri.parse(addon.url).host} ($it)") }.isSuccess
        refreshing.remove(addon.url)
        if (ok) ui.post {
            if (tmdbLastFocusApp == pkg) {
                watchApp = null
                queryAppChannels(pkg)
            }
        }
    }
}

/**
 * A film's or series' card: its wide art (or its poster on a blur of itself), under it its year, genre and rating,
 * marked with the icon of the tile [icon] it's under (Films, Series, or the add-on's own), and ✓ once [watched].
 */
internal fun titleCard(t: Stremio.Title, source: String?, icon: String?, watched: Boolean = false) =
    Prog(t.name, listOfNotNull("✓".takeIf { watched }, t.year, t.genres.firstOrNull(), t.rating?.let { "★ $it" }).joinToString(" · "), 0, t.background, null, null,
        logo = t.logo, poster = t.poster, icon = icon, vod = TitleRef(t.type, t.id, t.name, t.poster, t.background, source, logo = t.logo, rating = t.rating))

/** The tile a title of [type] from the add-on at [source] is under: Films or Series, or the add-on's own (null: none). */
internal fun MainActivity.titleTile(type: String, source: String?): String? = when (addonsVod()) {
    "tiles" -> if (type == "series") SERIES_PKG else MOVIES_PKG
    "apps" -> source?.let { Stremio.saved(filesDir, it)?.addon }?.let { STREMIO + it.id }
    else -> null
}

// ---------- a title's page ----------

/**
 * OK on a film's or series' card: its page, from the add-on it came from if that one has pages for it, else the
 * first that does (Cinemeta, for IMDb ids). A card from Continue Playing goes on playing instead.
 */
internal fun MainActivity.openTitle(ref: TitleRef) {
    if (ref.video != null) return resumeTitle(ref)
    Toast.makeText(this, getString(R.string.player_loading), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val meta = titleMeta(ref)
        ui.post {
            if (meta != null) showVodTitle(meta, ref)
            else if (ref.type == "movie") playTitle(ref, null, null)
            else Toast.makeText(this, getString(R.string.title_no_page), Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * [ref]'s page from the add-on it came from, else the first with pages for it; in the TV's language when TMDB has
 * it (see localizeMeta). Network: off the UI thread.
 */
private fun MainActivity.titleMeta(ref: TitleRef): Stremio.TitleMeta? {
    val addons = vodAddons().filter { it.provides("meta", ref.type, ref.id) }.sortedByDescending { it.url == ref.source }
    for (a in addons) runCatching { Stremio.titleMeta(a.base, ref.type, ref.id) }.getOrNull()?.let { return localizeMeta(it) }
    return null
}

/**
 * A film's or series' page in the side panel, like a TMDB result's: its art across the top, rating, kind, year and
 * genre, what it's about; then Watch (Resume, when it was begun), or a series' seasons and episodes: Left/Right (or OK
 * on the season's row) go from one season to the next, OK on an episode opens its page (see showVodEpisode).
 * [season]: the season shown (the one last played, at first); [episode]: the episode row to focus (back from its page).
 * With TMDB knowing it, the trailer and the services that have it too, as on a TMDB result's page (after Watch, or
 * before a series' seasons); [available]: the services TMDB lists it on, once it answered. Back from the player, the
 * page is there again, as it was left.
 */
internal fun MainActivity.showVodTitle(meta: Stremio.TitleMeta, ref: TitleRef, season: Int? = null, available: Set<String>? = null,
                                       episode: String? = null, focusRow: Int? = null) {
    val t = meta.title
    val kind = getString(if (t.type == "series") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    val top = titleTop(listOfNotNull((meta.rating ?: ref.rating)?.let { "★ $it" }, kind, t.year, meta.genres.firstOrNull(), meta.runtime)
        .joinToString(" · "), null, meta.description)
    val sources = streamSources(t.type, meta.episodes.firstOrNull()?.id ?: t.id)
    val sole = soleAddon(sources) // the one add-on with streams for it: Watch on it, with its icon
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    var focus = 0
    val services = serviceRows(t, available)
    val episodic = sources.isNotEmpty() && t.type == "series" && meta.episodes.isNotEmpty()
    var sides: ((Int) -> Unit)? = null
    fun addServices() = services.rows.forEachIndexed { i, row -> icons[rows.size] = services.icons[i]; rows += row }
    if (episodic) {
        addServices()
        focus = rows.size
    }
    val marks = VodWatched.all(vodDir())
    val holds = HashMap<Int, () -> Unit>()
    if (sources.isEmpty()) {
        rows += page(getString(R.string.title_no_streams)) { }
    } else if (!episodic) {
        val at = VodProgress.position(vodDir(), t.id)
        val seen = t.id in marks
        val label = when {
            sole != null && at != null -> getString(R.string.title_resume_on, clock(at), sole.name)
            at != null -> getString(R.string.title_resume, clock(at))
            sole != null && seen -> getString(R.string.title_watch_again_on, sole.name)
            seen -> getString(R.string.title_watch_again)
            sole != null -> getString(R.string.tmdb_search_on, sole.name)
            else -> getString(R.string.title_watch)
        }
        icons[rows.size] = watchIcon(sole)
        rows += page(label, if (seen) "✓" else "") {
            pageReturn = { showVodTitle(meta, ref, season, services.known) }
            playTitle(ref, meta, null)
        }
    } else {
        val seasons = meta.episodes.map { it.season }.distinct()
        // The season of the episode last played (or the next one, waiting), else of the one last watched, else the first.
        fun seasonOf(id: String?) = id?.let { i -> meta.episodes.firstOrNull { it.id == i }?.season }
        val last = seasonOf(VodProgress.all(vodDir()).firstOrNull { it.metaId == t.id }?.id)
            ?: seasonOf(marks.values.filter { it.metaId == t.id && it.id != t.id }.maxByOrNull { it.at }?.id)
        val shown = season?.takeIf { it in seasons } ?: last ?: seasons.first()
        fun seasonName(s: Int) = if (s == 0) getString(R.string.title_specials) else getString(R.string.title_season, s)
        val at = seasons.indexOf(shown)
        if (seasons.size > 1) {
            // Left/Right on any row go to the season before or after; the season's row keeps the focus.
            sides = { step -> showVodTitle(meta, ref, seasons[Math.floorMod(at + step, seasons.size)], services.known) }
            rows += page(seasonName(shown), "‹ ${at + 1} / ${seasons.size} ›") { sides?.invoke(1) }
        }
        val begun = VodProgress.all(vodDir()).associateBy { it.id }
        val seasonRow = focus
        val inSeason = meta.episodes.filter { it.season == shown }
        // At first, the episode begun (or waiting next); else the first not watched, after one that is.
        val ahead = inSeason.firstOrNull { begun[it.id] != null }?.id
            ?: inSeason.takeIf { list -> list.any { it.id in marks } }?.firstOrNull { it.id !in marks }?.id
        inSeason.forEach { e ->
            val name = getString(R.string.title_episode_row, e.episode, e.name.ifBlank { getString(R.string.title_episode_n, e.episode) })
            val value = begun[e.id]?.let { if (it.upNext) getString(R.string.title_up_next) else clock(it.position) }
                ?: if (e.id in marks) "✓" else ""
            if (episode == e.id || (episode == null && season == null && e.id == ahead)) focus = rows.size
            holds[rows.size] = { // hold OK: watched, or not
                markWatched(meta, listOf(e), e.id !in marks)
                showVodTitle(meta, ref, shown, services.known, e.id)
            }
            rows += page(name, value) { showVodEpisode(meta, ref, e, services.known) }
        }
        val all = inSeason.all { it.id in marks }
        val markAt = rows.size
        rows += page(getString(if (all) R.string.title_unmark_season else R.string.title_mark_season)) {
            markWatched(meta, inSeason, !all)
            showVodTitle(meta, ref, shown, services.known, focusRow = markAt)
        }
        if (season != null && episode == null && focusRow == null) focus = seasonRow // a season picked: stay on its row
    }
    if (!episodic && sources.isNotEmpty()) {
        val seen = t.id in marks
        rows += page(getString(if (seen) R.string.title_mark_unwatched else R.string.title_mark_watched)) {
            VodWatched.set(vodDir(), listOf(t.id to t.id), !seen)
            traktHistory(listOf(t.id to t.id), !seen)
            if (!seen) VodProgress.remove(vodDir(), t.id)
            showVodTitle(meta, ref, season, services.known, focusRow = 1)
        }
    }
    focusRow?.let { focus = it }
    if (!episodic) addServices()
    val art = if (ref.background != null) Triple(ref.background, ref.logo, ref.poster ?: t.poster) else Triple(t.background, t.logo, ref.poster ?: t.poster)
    titlePage(t.name, art, t.name, top, rows, focus, icons, holds) { closeLauncherSettings() }
    pageSides = sides
    services.later { showVodTitle(meta, ref, season, it, episode) }
}

/**
 * An episode's page, from its row on its series' page: its still (else the series' art) across the top, its season,
 * number and name, when it came out, what it's about; then Watch (or Resume), once the stream add-ons have said
 * whether they have it: with several, Watch on each of them too (how many streams each has), then the trailer and
 * the services, as on the series' page. Back goes to the series' page, on this episode's row.
 */
internal fun MainActivity.showVodEpisode(meta: Stremio.TitleMeta, ref: TitleRef, e: Stremio.Episode, available: Set<String>? = null,
                                         found: Map<String, Int>? = null, focus: Int = 0) {
    val t = meta.title
    val title = getString(R.string.player_episode, t.name, e.season, e.episode)
    val released = e.released?.take(10)?.let { d -> runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(d) }.getOrNull() }
        ?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(it) }
    val seen = VodWatched.has(vodDir(), e.id)
    val top = titleTop(listOfNotNull("✓".takeIf { seen }, episodeLine(e), released).joinToString(" · "), e.name.takeIf { it.isNotBlank() }, e.overview ?: meta.description)
    val sources = streamSources(t.type, e.id)
    val services = serviceRows(t, available)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    fun play(only: String?) {
        pageReturn = { showVodEpisode(meta, ref, e, services.known) }
        playTitle(ref, meta, e, only)
    }
    val having = found?.filterValues { it > 0 }?.keys?.let { keys -> sources.filter { it.first in keys } }
    when {
        sources.isEmpty() || having?.isEmpty() == true -> rows += page(getString(R.string.title_no_streams)) { }
        having == null -> rows += page(getString(R.string.title_finding_streams)) { }
        else -> {
            val sole = soleAddon(having)
            val at = VodProgress.position(vodDir(), e.id)
            icons[rows.size] = watchIcon(sole)
            rows += page(when {
                sole != null && at != null -> getString(R.string.title_resume_on, clock(at), sole.name)
                at != null -> getString(R.string.title_resume, clock(at))
                sole != null && seen -> getString(R.string.title_watch_again_on, sole.name)
                seen -> getString(R.string.title_watch_again)
                sole != null -> getString(R.string.tmdb_search_on, sole.name)
                else -> getString(R.string.title_watch)
            }) { play(sole?.base) }
            // Several add-ons have it: each of them too, with how many streams it has.
            if (sole == null) having.forEach { (base, name) ->
                icons[rows.size] = watchIcon(soleAddon(listOf(base to name)))
                rows += page(getString(R.string.tmdb_search_on, name), resources.getQuantityString(R.plurals.title_streams, found[base] ?: 0, found[base] ?: 0)) { play(base) }
            }
        }
    }
    val markRow = rows.size
    rows += page(getString(if (seen) R.string.title_mark_unwatched else R.string.title_mark_watched)) {
        markWatched(meta, listOf(e), !seen)
        showVodEpisode(meta, ref, e, services.known, found, markRow)
    }
    services.rows.forEachIndexed { i, row -> icons[rows.size] = services.icons[i]; rows += row }
    val still = e.thumbnail
    val art = when {
        still != null -> Triple(still, null, ref.poster ?: t.poster)
        ref.background != null -> Triple(ref.background, ref.logo, ref.poster ?: t.poster)
        else -> Triple(t.background, t.logo, ref.poster ?: t.poster)
    }
    titlePage(title, art, t.name, top, rows, focus, icons) { showVodTitle(meta, ref, e.season, services.known, e.id) }
    services.later { showVodEpisode(meta, ref, e, it, found, focus) }
    // The stream add-ons asked whether they have it (how many streams each), all at once; the page again once they've said.
    if (found != null || sources.isEmpty()) return
    netIo.execute {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val counts = try {
            pool.invokeAll(sources.map { (base, name) -> java.util.concurrent.Callable {
                base to runCatching { Stremio.titleStreams(base, name, t.type, e.id).size }.getOrDefault(0)
            } }, 15, java.util.concurrent.TimeUnit.SECONDS).mapNotNull { runCatching { it.get() }.getOrNull() }.toMap()
        } finally {
            pool.shutdownNow()
        }
        ui.post { if (pageShowing(title)) showVodEpisode(meta, ref, e, services.known, counts, focus) }
    }
}

/**
 * Marks [episodes] of the series [meta] watched ([on]) or not, from its pages: watched ones off Continue Playing; the
 * series itself watched once all its episodes are (specials aside), and not any more once one isn't.
 */
private fun MainActivity.markWatched(meta: Stremio.TitleMeta, episodes: List<Stremio.Episode>, on: Boolean) {
    val id = meta.title.id
    VodWatched.set(vodDir(), episodes.map { it.id to id }, on)
    traktHistory(episodes.map { it.id to id }, on)
    if (on) episodes.forEach { VodProgress.remove(vodDir(), it.id) }
    val marks = VodWatched.all(vodDir())
    val whole = meta.episodes.filter { it.season > 0 }.ifEmpty { meta.episodes }.all { it.id in marks }
    if (whole != (id in marks)) VodWatched.set(vodDir(), listOf(id to id), whole)
}

/** "Season 1 · Episode 3" (specials: "Special 3"). */
internal fun MainActivity.episodeLine(e: Stremio.Episode) =
    if (e.season == 0) getString(R.string.title_special_n, e.episode) else getString(R.string.title_season_episode, e.season, e.episode)

/** Whether the page titled [title] is still the one in the panel (an answer for it may show it again). */
private fun MainActivity.pageShowing(title: String) =
    settingsScrim.visibility == View.VISIBLE && findViewById<android.widget.TextView>(R.id.settingsTitle).text.toString() == title

/** Under a title's art: its [info] line, a [name] (an episode's) and what it's about. */
private fun MainActivity.titleTop(info: String, name: String?, about: String?) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
    name?.let { addView(label(it, 15f).apply { setPadding(0, 0, 0, dp(4)) }) }
    addView(label(info, 13f).apply { alpha = 0.7f })
    about?.let { d ->
        addView(label(d, 13f).apply {
            maxLines = 10
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
        })
    }
}

/**
 * A title's page in the panel: [art] (backdrop, logo, poster) across the top, over the panel's padding so it reaches
 * its edges and the top of the screen; [top] under it, then [rows], each with its icon from [icons] by its index.
 */
private fun MainActivity.titlePage(title: String, art: Triple<String?, String?, String?>, name: String, top: View,
                                   rows: List<Triple<String, String, () -> Unit>>, focus: Int,
                                   icons: Map<Int, android.graphics.drawable.Drawable>, holds: Map<Int, () -> Unit> = emptyMap(),
                                   back: () -> Unit) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val panelWidth = dp(380) // the panel's width (activity_main.xml)
    val picture = FrameLayout(this).apply { setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt()) }
    fillArt(picture, panelWidth, panelWidth * 9 / 16, art.first, art.second, art.third, name)
    showPage(title, rows, focus, top = top, back = back)
    panel.addView(picture, 0, LinearLayout.LayoutParams(panelWidth, panelWidth * 9 / 16).apply {
        setMargins(-panel.paddingStart, -panel.paddingTop, -panel.paddingEnd, dp(20))
    })
    panel.clipToPadding = false
    pageExit = {
        panel.removeView(picture)
        panel.clipToPadding = true
    }
    // Back up on the first row, the page scrolls back up: nothing above it takes focus to pull it there.
    settingsList.getChildAt(1)?.let { first ->
        val own = first.onFocusChangeListener
        first.setOnFocusChangeListener { v, focused ->
            own?.onFocusChange(v, focused)
            val scroll = settingsList.parent as android.widget.ScrollView
            if (focused) scroll.post { scroll.smoothScrollTo(0, (v.bottom + dp(8) - scroll.height).coerceAtLeast(0)) }
        }
    }
    // What holding OK on a row does (see MainActivity.onHold).
    for ((i, hold) in holds) settingsList.getChildAt(1 + i)?.setTag(R.id.settingsList, hold)
    // Each row with an icon starts with it, before the name, as a TMDB page's rows with their apps'.
    for ((i, icon) in icons) (settingsList.getChildAt(1 + i) as? LinearLayout)?.addView(ImageView(this).apply {
        setImageDrawable(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
}

/**
 * A title's trailer and services rows, as TMDB knows it (its id from the TV's-language lookup, see localizeMeta),
 * each with its app's icon; [known]: the services TMDB lists it on, when it has answered. [later] asks it when it
 * hasn't, and calls back with the answer.
 */
private class Services(val rows: List<Triple<String, String, () -> Unit>>, val icons: List<android.graphics.drawable.Drawable>,
                       val known: Set<String>?, val later: (((Set<String>) -> Unit) -> Unit))

private fun MainActivity.serviceRows(t: Stremio.Title, available: Set<String>?): Services {
    val item = tmdbItemOf(t) ?: return Services(emptyList(), emptyList(), available) { }
    val region = TmdbClient.region(tmdbLang())
    val known = available ?: TmdbClient.cachedProviders(item, region)
    val apps = titleApps().filter { TmdbClient.offers(it.pkg, known) }
    val rows = apps.map { a ->
        page(if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else getString(R.string.tmdb_search_on, a.label)) {
            closeLauncherSettings()
            openInApp(a, item)
        }
    }
    val title = findViewById<android.widget.TextView>(R.id.settingsTitle)
    return Services(rows, apps.map { it.icon.constantState?.newDrawable(resources) ?: it.icon }, known) { again ->
        val key = tmdbKey()
        if (known == null && providersOn() && key != null) {
            val shown = title.text.toString()
            spotlightIo.execute {
                val found = TmdbClient.providers(key, item, region) ?: return@execute
                ui.post { if (pageShowing(shown)) again(found) }
            }
        }
    }
}

/**
 * [t] as a TMDB result, for its trailer and services: known once its name in the TV's language was asked of TMDB
 * (see localizeMeta); its IMDb id kept with it, for the apps that open a title by it. Null without a TMDB key.
 */
private fun MainActivity.tmdbItemOf(t: Stremio.Title): TmdbItem? {
    if (tmdbKey() == null || !t.id.startsWith("tt")) return null
    Local.use(filesDir, tmdbLang())
    val l = Local.get(t.id) ?: return null
    val item = TmdbItem(l.tmdbId, l.title, l.overview, null, l.vote, if (l.tv) "tv" else "movie", l.genres, l.year)
    TmdbClient.knowImdbId(item, t.id)
    return item
}

/** The add-on behind [sources] when it's the only one (Watch on it), else null. */
internal fun MainActivity.soleAddon(sources: List<Pair<String, String>>): Stremio.Addon? =
    sources.singleOrNull()?.let { (base, _) -> addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.firstOrNull { it.base == base } }

/**
 * A Watch row's icon: [addon]'s tile's (when it has a tile of its own), else its logo (once on disk), else Play,
 * tinted for the row when it's a mark rather than a picture.
 */
internal fun MainActivity.watchIcon(addon: Stremio.Addon?): android.graphics.drawable.Drawable {
    if (addon != null) {
        apps.firstOrNull { it.pkg == STREMIO + addon.id }?.icon?.constantState?.newDrawable(resources)?.mutate()?.let { d ->
            return d.apply { if (this !is android.graphics.drawable.BitmapDrawable) setTint(fg) }
        }
        addon.logo?.takeIf { channelLogoFiles.has(it) }?.let { uri ->
            runCatching { android.graphics.BitmapFactory.decodeFile(channelLogoFiles.get(uri) { u -> openStream(u) }.path) }.getOrNull()
                ?.let { return android.graphics.drawable.BitmapDrawable(resources, it) }
        }
    }
    return getDrawable(R.drawable.ic_play)!!.mutate().apply { setTint(fg) }
}

/** "1:02:03", "4:05". */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Plays the film, or the episode [episode] with the ones after it (for the next ones), in Piume's player: the
 * stream add-ons that have it are asked for their streams (see PlayerActivity, MODE_VOD), or [only] the one at that
 * address. The player shows the series' name, and the episode's season, number and name under it.
 */
internal fun MainActivity.playTitle(ref: TitleRef, meta: Stremio.TitleMeta?, episode: Stremio.Episode?, only: String? = null) {
    val t = meta?.title
    val name = t?.name ?: ref.name
    val poster = t?.poster ?: ref.poster
    val art = ref.background ?: t?.background ?: poster // the card's: what loads in the player, and its Continue Playing card
    // One item for a film; the episode and the ones after it for a series.
    val items: List<Triple<String, String, Stremio.Episode?>> = if (episode == null || meta == null) listOf(Triple(t?.id ?: ref.id, name, null))
        else meta.episodes.dropWhile { it.id != episode.id }.map { e -> Triple(e.id, name, e) }
    val type = t?.type ?: ref.type
    val sources = streamSources(type, items.first().first).filter { only == null || it.first == only }
    if (sources.isEmpty()) return Toast.makeText(this, getString(R.string.title_no_streams), Toast.LENGTH_LONG).show()
    fun strings(f: (Triple<String, String, Stremio.Episode?>) -> String?) = ArrayList(items.map { f(it).orEmpty() })
    try {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.MODE, PlayerActivity.MODE_VOD)
            .putExtra(PlayerActivity.INDEX, 0)
            .putStringArrayListExtra(PlayerActivity.SOURCES, ArrayList(sources.map { it.first }))
            .putStringArrayListExtra(PlayerActivity.SOURCE_NAMES, ArrayList(sources.map { it.second }))
            .putStringArrayListExtra(PlayerActivity.TYPES, strings { type })
            .putStringArrayListExtra(PlayerActivity.IDS, strings { it.first })
            .putStringArrayListExtra(PlayerActivity.NAMES, strings { it.second })
            .putStringArrayListExtra(PlayerActivity.NOWS, strings { it.third?.let { e -> listOf(episodeLine(e), e.name).filter { n -> n.isNotBlank() }.joinToString(" · ") } ?: t?.year ?: "" })
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { it.third?.thumbnail ?: art })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { "" })
            .putStringArrayListExtra(PlayerActivity.DESCS, strings { it.third?.overview ?: meta?.description })
            .putExtra(PlayerActivity.META_TYPE, type)
            .putExtra(PlayerActivity.META_ID, t?.id ?: ref.id)
            .putExtra(PlayerActivity.POSTER, art)
            .putExtra(PlayerActivity.USER_DIR, vodDir().path))
    } catch (e: Exception) {
        warn("player didn't open ($e)")
    }
}

/** OK on a Continue Playing card of a film or episode: it goes on (a series with the episodes after it). */
private fun MainActivity.resumeTitle(ref: TitleRef) {
    netIo.execute {
        val meta = titleMeta(ref)
        ui.post {
            val episode = meta?.episodes?.firstOrNull { it.id == ref.video }
            playTitle(ref, meta, episode)
        }
    }
}

// ---------- Continue Playing ----------

/** The films and episodes begun, as Continue Playing cards (with the time they were left, for its order). Off the UI thread. */
internal fun MainActivity.titleProgressCards(hidden: Set<String>): List<Pair<Long, Prog>> =
    // One card a series: its newest episode, begun or waiting next.
    VodProgress.all(vodDir()).filter { hideKey(VOD_PKG, it.name) !in hidden }.distinctBy { it.metaId }.take(10).map { e ->
        val left = ((e.duration - e.position) / 60_000).coerceAtLeast(1)
        val tile = if (addonsVod() == "tiles") (if (e.type == "series") SERIES_PKG else MOVIES_PKG) else null
        val state = if (e.upNext) getString(R.string.title_up_next) else getString(R.string.time_left_min, left.toInt())
        e.at to Prog(e.name, listOf(e.sub, state).filter { it.isNotBlank() }.joinToString(" · "),
            e.percent, e.poster, null, VOD_PKG, poster = e.poster, icon = tile,
            vod = TitleRef(e.type, e.metaId, e.name.substringBefore(" · "), null, e.poster, video = e.id))
    }

// ---------- search ----------

/** The films and series the add-ons' searchable catalogs find for [query] (Cinemeta first), each once. Off the UI thread. */
internal fun MainActivity.findTitles(query: String): List<Pair<Stremio.Title, String>> {
    val asked = vodAddons().flatMap { a -> a.vod.filter { it.search }.map { c -> a to c } }
    if (asked.isEmpty()) return emptyList()
    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    return try {
        pool.invokeAll(asked.map { (a, c) -> java.util.concurrent.Callable { runCatching { Stremio.search(a, c, query) }.getOrDefault(emptyList()).map { it to a.url } } },
            15, java.util.concurrent.TimeUnit.SECONDS)
            .flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }.distinctBy { it.first.type + it.first.id }
    } finally {
        pool.shutdownNow()
    }
}

/**
 * The films and series for a search of [query], each once: with a TMDB key, those TMDB finds first (it knows their
 * names in the TV's language: "Il padrino" finds The Godfather), as Cinemeta's titles by their IMDb ids (those without
 * one left out); then those the add-ons' searchable catalogs find (Cinemeta's first). In the TV's language, as the
 * Films and Series rows are. Off the UI thread.
 */
internal fun MainActivity.searchLibrary(query: String): List<Pair<Stremio.Title, String>> {
    val key = tmdbKey()
    val viaTmdb = if (key == null) emptyList() else {
        val found = TmdbClient.search(key, query, tmdbLang())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        try {
            pool.invokeAll(found.map { item -> java.util.concurrent.Callable {
                val imdb = TmdbClient.imdbIdOf(key, item) ?: return@Callable null
                Stremio.Title(if (item.mediaType == "tv") "series" else "movie", imdb, item.title,
                    item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, TmdbClient.cardUrl(item.backdropPath), null,
                    item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, item.genres,
                    item.voteAverage.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }, item.overview, item.id) to cinemetaUrl()
            } }, 15, java.util.concurrent.TimeUnit.SECONDS).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }
    val all = (viaTmdb + findTitles(query)).distinctBy { it.first.type + it.first.id }
    if (key != null) {
        Local.use(filesDir, tmdbLang())
        val todo = all.map { it.first }.filter { wantsLocal(it) }.map { it.id }.filter { Local.asking.add(it) }
        if (todo.isNotEmpty()) askLocal(todo, key, tmdbLang())
    }
    return all.map { (t, source) -> (localize(t)?.let { l -> Stremio.Title(l.type, l.id, l.name, l.poster, l.background, l.logo, l.year, l.genres,
        l.rating ?: t.rating, t.description, t.tmdbId) } ?: t) to source }
}

// ---------- in the TV's language ----------

/**
 * Cinemeta's catalogs and pages are in English (IMDb's) and take no language. With a TMDB key, a title with an IMDb
 * id gets its name, art, genres and what it's about from TMDB in the TV's language (tmdbLang), and a series its
 * episodes' names, overviews and stills; what TMDB lacks stays Cinemeta's. Kept on disk a couple of weeks, per language.
 */
private object Local {
    private const val FILE = "tmdb_titles.json"
    private const val AGE_MS = 14 * 24 * 3600_000L
    @Volatile private var lang: String? = null
    private val known = java.util.concurrent.ConcurrentHashMap<String, TmdbClient.Localized>()
    private val unknown: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val asking: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The titles known in [language], read from disk the first time (or when the language changed). */
    @Synchronized
    fun use(dir: java.io.File, language: String) {
        if (lang == language) return
        lang = language
        known.clear()
        unknown.clear()
        val f = java.io.File(dir, FILE)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > AGE_MS) return
        runCatching {
            val o = org.json.JSONObject(f.readText())
            if (o.optString("lang") != language) return
            val items = o.getJSONObject("items")
            for (id in items.keys()) {
                val e = items.optJSONObject(id) ?: run { unknown += id; null } ?: continue
                fun list(a: org.json.JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()
                known[id] = TmdbClient.Localized(e.optLong("id"), e.optBoolean("tv"), e.optString("t"), e.optString("o"),
                    e.optString("p").ifEmpty { null }, e.optString("b").ifEmpty { null }, list(e.optJSONArray("g")), e.optString("y").ifEmpty { null },
                    e.optDouble("v", 0.0))
            }
        }
    }

    fun get(id: String) = known[id]
    fun asked(id: String) = known.containsKey(id) || id in unknown
    fun put(id: String, l: TmdbClient.Localized?) { if (l != null) known[id] = l else unknown += id }

    @Synchronized
    fun save(dir: java.io.File) {
        val items = org.json.JSONObject()
        for ((id, l) in known) items.put(id, org.json.JSONObject().put("id", l.tmdbId).put("tv", l.tv).put("t", l.title).put("o", l.overview)
            .put("p", l.poster.orEmpty()).put("b", l.backdrop.orEmpty()).put("g", org.json.JSONArray(l.genres)).put("y", l.year.orEmpty()).put("v", l.vote))
        for (id in unknown) items.put(id, org.json.JSONObject.NULL)
        val f = java.io.File(dir, FILE)
        val part = java.io.File(dir, "$FILE.part")
        part.writeText(org.json.JSONObject().put("lang", lang).put("items", items).toString())
        part.renameTo(f)
    }
}

/** TMDB's rating ("7.4"), for a title with no IMDb one; null when it has none. */
private fun TmdbClient.Localized.rating() = vote.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }

private fun MainActivity.tmdbKey() = prefs.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }

/** Whether [t] could be had in the TV's language and TMDB wasn't asked yet. */
private fun MainActivity.wantsLocal(t: Stremio.Title): Boolean {
    if (tmdbKey() == null || !t.id.startsWith("tt")) return false
    Local.use(filesDir, tmdbLang())
    return !Local.asked(t.id)
}

/** [t] in the TV's language, as TMDB has it (its logo, written in English, left out); null when it isn't known (yet). */
private fun MainActivity.localize(t: Stremio.Title): Stremio.Title? {
    if (tmdbKey() == null || !t.id.startsWith("tt")) return null
    Local.use(filesDir, tmdbLang())
    val l = Local.get(t.id) ?: return null
    val same = l.title.equals(t.name, ignoreCase = true)
    return Stremio.Title(t.type, t.id, l.title, l.poster ?: t.poster, l.backdrop ?: t.background, t.logo.takeIf { same }, t.year ?: l.year,
        l.genres.ifEmpty { t.genres }, t.rating ?: l.rating())
}

/** Asks TMDB for [todo] (marked as being asked) in [lang], a few at a time, and keeps what it says. Network: off the UI thread. */
private fun MainActivity.askLocal(todo: List<String>, key: String, lang: String) {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
    try {
        pool.invokeAll(todo.map { id -> java.util.concurrent.Callable {
            runCatching { TmdbClient.localized(key, id, lang) }.onSuccess { Local.put(id, it) } // a failure is asked again next time
        } })
    } finally {
        pool.shutdown()
        todo.forEach { Local.asking.remove(it) }
    }
    runCatching { Local.save(filesDir) }
}

// ---------- Spotlight ----------

/**
 * Spotlight's titles from Cinemeta, which needs no key: its popular films, series or both ([type]: "movie", "tv",
 * "all", taken in turn), those with a background. With a TMDB key, in the TV's language as the Films and Series rows
 * are (see localize), with their TMDB ids for the services; their background stays Cinemeta's, the largest.
 * Empty when Cinemeta can't be asked. Network: off the UI thread.
 */
internal fun MainActivity.spotlightTitles(type: String): List<TmdbItem> {
    val url = cinemetaUrl()
    val addon = Stremio.saved(filesDir, url)?.addon
    val kinds = when (type) { "movie" -> listOf("movie"); "tv" -> listOf("series"); else -> listOf("movie", "series") }
    val lists = kinds.map { k ->
        val catalog = addon?.vod?.firstOrNull { it.type == k && it.listed }?.id ?: "top"
        runCatching { Stremio.titles(Http.json("${url.removeSuffix("/manifest.json")}/catalog/$k/${Stremio.enc(catalog)}.json")) }
            .onFailure { warn("Cinemeta for Spotlight failed ($it)") }.getOrDefault(emptyList())
    }
    val titles = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        .filter { it.background != null }.distinctBy { it.id }.take(40)
    val key = tmdbKey()
    if (key != null) {
        Local.use(filesDir, tmdbLang())
        val todo = titles.filter { wantsLocal(it) }.map { it.id }.filter { Local.asking.add(it) }
        if (todo.isNotEmpty()) askLocal(todo, key, tmdbLang())
    }
    return titles.map { t ->
        val local = localize(t)
        val l = if (key != null && t.id.startsWith("tt")) Local.get(t.id) else null
        val name = local?.name ?: t.name
        TmdbItem(l?.tmdbId ?: t.tmdbId ?: 0, name, l?.overview?.ifBlank { null } ?: t.description.orEmpty(),
            t.background?.replace("/background/medium/", "/background/large/"), t.rating?.toDoubleOrNull() ?: l?.vote ?: 0.0,
            if (t.type == "series") "tv" else "movie", local?.genres ?: t.genres, t.year, imdbId = t.id)
            .also { if (it.id > 0) TmdbClient.knowImdbId(it, t.id) }
    }
}

/** Asks TMDB for [ids] in the TV's language, a few at a time; once in, the rows under [pkg] again if it's still in focus. */
private fun MainActivity.localizeLater(ids: List<String>, pkg: String) {
    val key = tmdbKey() ?: return
    val lang = tmdbLang()
    val todo = ids.filter { Local.asking.add(it) }
    if (todo.isEmpty()) return
    netIo.execute {
        askLocal(todo, key, lang)
        ui.post {
            if (tmdbLastFocusApp == pkg) {
                watchApp = null
                queryAppChannels(pkg)
            }
        }
    }
}

/**
 * [meta] in the TV's language when TMDB has it: name, art, genres and what it's about, and a series' episodes
 * (their names, overviews and stills, a season at a time). Network: off the UI thread.
 */
private fun MainActivity.localizeMeta(meta: Stremio.TitleMeta): Stremio.TitleMeta {
    val key = tmdbKey() ?: return meta
    val t = meta.title
    if (!t.id.startsWith("tt")) return meta
    val lang = tmdbLang()
    Local.use(filesDir, lang)
    val l = Local.get(t.id) ?: if (Local.asked(t.id)) return meta else
        runCatching { TmdbClient.localized(key, t.id, lang) }.getOrNull().also { Local.put(t.id, it); runCatching { Local.save(filesDir) } } ?: return meta
    val title = localize(t) ?: t
    val episodes = if (!l.tv || meta.episodes.isEmpty()) meta.episodes else {
        val seasons = meta.episodes.map { it.season }.distinct().take(40)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val local = try {
            pool.invokeAll(seasons.map { n -> java.util.concurrent.Callable { n to runCatching { TmdbClient.seasonEpisodes(key, l.tmdbId, n, lang) }.getOrDefault(emptyMap()) } },
                20, java.util.concurrent.TimeUnit.SECONDS).mapNotNull { runCatching { it.get() }.getOrNull() }.toMap()
        } finally {
            pool.shutdownNow()
        }
        meta.episodes.map { e ->
            val le = local[e.season]?.get(e.episode) ?: return@map e
            Stremio.Episode(e.id, e.season, e.episode, le.name.ifBlank { e.name }, e.released, le.still ?: e.thumbnail, le.overview.ifBlank { e.overview.orEmpty() }.ifBlank { null })
        }
    }
    return Stremio.TitleMeta(title, l.overview.ifBlank { meta.description.orEmpty() }.ifBlank { null }, l.genres.ifEmpty { meta.genres },
        meta.rating ?: l.rating(), meta.runtime, episodes)
}
