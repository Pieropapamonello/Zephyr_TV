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
 * A film or series a card stands for: what to ask its page for ([type], [id]), its name and art, the add-on it came
 * from ([source]: its manifest's address, asked for the page first), and, on a Continue Playing card, the film or
 * episode to go on with ([video]).
 */
internal class TitleRef(val type: String, val id: String, val name: String, val poster: String?, val background: String?,
                        val source: String? = null, val video: String? = null)

/** Whether Cinemeta, Stremio's catalog of films and series, is on (it is, unless turned off). */
internal fun MainActivity.cinemetaOn() = prefs.getBoolean(K.CINEMETA, true)

/** Where films and series show: "tiles" (a Films tile and a Series tile), "apps" (each add-on's own tile), "off". */
internal fun MainActivity.addonsVod(): String = prefs.getString(K.ADDONS_VOD, null)?.takeIf { it in listOf("tiles", "apps", "off") } ?: "tiles"

/** The add-ons films and series are listed from (Cinemeta first, when it's on), as manifest addresses. */
internal fun MainActivity.libraryUrls(): List<String> = listOfNotNull(Stremio.CINEMETA.takeIf { cinemetaOn() }) + addonUrls()

/** The add-ons as read, those with film or series catalogs or streams. Reads them when they're missing (in the background). */
private fun MainActivity.vodAddons(): List<Stremio.Addon> = libraryUrls().mapNotNull { url ->
    Stremio.saved(filesDir, url)?.addon ?: null.also { if (Stremio.due(filesDir, url)) updateAddon(url, null) }
}

/** The add-ons that give streams for a film or episode of [type] with this [id] (address without /manifest.json, and name). */
internal fun MainActivity.streamSources(type: String, id: String): List<Pair<String, String>> =
    addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.filter { it.provides("stream", type, id) }.map { it.base to it.name }

/** Whether some add-on gives streams for films or series at all (Watch shows on a title's page). */
internal fun MainActivity.hasVodStreams(): Boolean = addonUrls().any { Stremio.saved(filesDir, it)?.addon?.streamsVod == true }

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
    return addons.flatMap { addon ->
        if (Stremio.vodDue(filesDir, addon.url) && addon.vod.any { it.listed }) refreshShelves(addon, pkg)
        Stremio.shelves(filesDir, addon).filter { type == null || it.catalog.type == type }.mapNotNull { shelf ->
            val kind = getString(if (shelf.catalog.type == "series") R.string.vod_series else R.string.vod_movies)
            val name = if (type != null) "${addon.name} · ${shelf.catalog.name}" else "$kind · ${shelf.catalog.name}"
            val cards = shelf.titles.filter { hideKey(null, it.name) !in hidden }.map { titleCard(it, addon.url) }
            if (cards.isEmpty()) null else Section("vod:${addon.id}:${shelf.catalog.type}:${shelf.catalog.id}", name, name, cards)
        }
    }
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

/** A film's or series' card: its wide art (or its poster on a blur of itself), its year under it. */
internal fun titleCard(t: Stremio.Title, source: String?) =
    Prog(t.name, t.year.orEmpty(), 0, t.background, null, null, logo = t.logo, poster = t.poster,
        vod = TitleRef(t.type, t.id, t.name, t.poster, t.background, source))

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

/** [ref]'s page from the add-on it came from, else the first with pages for it. Network: off the UI thread. */
private fun MainActivity.titleMeta(ref: TitleRef): Stremio.TitleMeta? {
    val addons = vodAddons().filter { it.provides("meta", ref.type, ref.id) }.sortedByDescending { it.url == ref.source }
    for (a in addons) runCatching { Stremio.titleMeta(a.base, ref.type, ref.id) }.getOrNull()?.let { return it }
    return null
}

/**
 * A film's or series' page in the side panel, like a TMDB result's: its art across the top, rating, kind, year and
 * genre, what it's about; then Watch (Resume, when it was begun), or a series' seasons and episodes, each playing in
 * Piume's player with the episodes after it for the next ones. [season]: the season shown (the first, at first).
 */
internal fun MainActivity.showVodTitle(meta: Stremio.TitleMeta, ref: TitleRef, season: Int? = null) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val t = meta.title
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val panelWidth = dp(380) // the panel's width (activity_main.xml)
    val picture = FrameLayout(this).apply { setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt()) }
    fillArt(picture, panelWidth, panelWidth * 9 / 16, t.background ?: ref.background, t.logo, t.poster ?: ref.poster, t.name)
    val kind = getString(if (t.type == "series") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        addView(label(listOfNotNull(meta.rating?.let { "★ $it" }, kind, t.year, meta.genres.firstOrNull(), meta.runtime).joinToString(" · "), 13f).apply { alpha = 0.7f })
        meta.description?.let { d ->
            addView(label(d, 13f).apply {
                maxLines = 10
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }
    val sources = streamSources(t.type, meta.episodes.firstOrNull()?.id ?: t.id)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    var focus = 0
    if (sources.isEmpty()) {
        rows += page(getString(R.string.title_no_streams)) { }
    } else if (t.type != "series" || meta.episodes.isEmpty()) {
        val at = VodProgress.position(filesDir, t.id)
        rows += page(if (at != null) getString(R.string.title_resume, clock(at)) else getString(R.string.title_watch)) {
            closeLauncherSettings()
            playTitle(ref, meta, null)
        }
    } else {
        val seasons = meta.episodes.map { it.season }.distinct()
        // The season of the episode last played, else the first.
        val last = VodProgress.all(filesDir).firstOrNull { it.metaId == t.id }?.let { e -> meta.episodes.firstOrNull { it.id == e.id }?.season }
        val shown = season?.takeIf { it in seasons } ?: last ?: seasons.first()
        fun seasonName(s: Int) = if (s == 0) getString(R.string.title_specials) else getString(R.string.title_season, s)
        if (seasons.size > 1) rows += page(seasonName(shown), "${seasons.indexOf(shown) + 1} / ${seasons.size}") {
            showVodTitle(meta, ref, seasons[(seasons.indexOf(shown) + 1) % seasons.size])
        }
        val begun = VodProgress.all(filesDir).associateBy { it.id }
        meta.episodes.filter { it.season == shown }.forEach { e ->
            val name = getString(R.string.title_episode_row, e.episode, e.name.ifBlank { getString(R.string.title_episode_n, e.episode) })
            val value = begun[e.id]?.let { clock(it.position) }.orEmpty()
            if (begun[e.id] != null && focus == 0) focus = rows.size
            rows += page(name, value) {
                closeLauncherSettings()
                playTitle(ref, meta, e)
            }
        }
        if (season != null) focus = 0 // a season picked: stay on its row
    }
    showPage(t.name, rows, focus, top = top) { closeLauncherSettings() }
    // Over the panel's padding, so it reaches its edges and the top of the screen; gone with the page.
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
    // Watch (or the episodes) marked with the films' or series' icon, as a TMDB page's rows are with their apps'.
    if (sources.isNotEmpty() && (t.type != "series" || meta.episodes.isEmpty())) (settingsList.getChildAt(1) as? LinearLayout)?.addView(ImageView(this).apply {
        setImageDrawable(getDrawable(R.drawable.ic_play)!!.mutate().apply { setTint(fg) })
        scaleType = ImageView.ScaleType.FIT_CENTER
    }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
}

/** "1:02:03", "4:05". */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * Plays the film, or the episode [episode] with the ones after it (for the next ones), in Piume's player: the
 * stream add-ons that have it are asked for their streams (see PlayerActivity, MODE_VOD).
 */
internal fun MainActivity.playTitle(ref: TitleRef, meta: Stremio.TitleMeta?, episode: Stremio.Episode?) {
    val t = meta?.title
    val name = t?.name ?: ref.name
    val poster = t?.poster ?: ref.poster
    // One item for a film; the episode and the ones after it for a series.
    val items: List<Triple<String, String, Stremio.Episode?>> = if (episode == null || meta == null) listOf(Triple(t?.id ?: ref.id, name, null))
        else meta.episodes.dropWhile { it.id != episode.id }.map { e -> Triple(e.id, getString(R.string.player_episode, name, e.season, e.episode), e) }
    val type = t?.type ?: ref.type
    val sources = streamSources(type, items.first().first)
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
            .putStringArrayListExtra(PlayerActivity.NOWS, strings { it.third?.name ?: t?.year ?: "" })
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { it.third?.thumbnail ?: poster })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { "" })
            .putStringArrayListExtra(PlayerActivity.DESCS, strings { it.third?.overview ?: meta?.description })
            .putExtra(PlayerActivity.META_TYPE, type)
            .putExtra(PlayerActivity.META_ID, t?.id ?: ref.id)
            .putExtra(PlayerActivity.POSTER, ref.background ?: t?.background ?: poster))
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
    VodProgress.all(filesDir).filter { hideKey(VOD_PKG, it.name) !in hidden }.take(10).map { e ->
        val left = ((e.duration - e.position) / 60_000).coerceAtLeast(1)
        val tile = if (addonsVod() == "tiles") (if (e.type == "series") SERIES_PKG else MOVIES_PKG) else null
        e.at to Prog(e.name, listOf(e.sub, getString(R.string.time_left_min, left.toInt())).filter { it.isNotBlank() }.joinToString(" · "),
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

/** A TMDB result's Watch: its IMDb id, then its page from the add-ons (a series' episodes), as a card from them would. */
internal fun MainActivity.watchTmdbTitle(item: TmdbItem) {
    val key = prefs.getString(K.TMDB_API_KEY, null) ?: return
    Toast.makeText(this, getString(R.string.player_loading), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val imdb = TmdbClient.imdbIdOf(key, item)
        ui.post {
            if (imdb == null) return@post Toast.makeText(this, getString(R.string.title_no_page), Toast.LENGTH_LONG).show()
            closeLauncherSettings()
            openTitle(TitleRef(if (item.mediaType == "tv") "series" else "movie", imdb, item.title,
                item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, TmdbClient.cardUrl(item.backdropPath)))
        }
    }
}
