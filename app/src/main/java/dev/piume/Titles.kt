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
    return addons.flatMap { addon ->
        if (Stremio.vodDue(filesDir, addon.url) && addon.vod.any { it.listed }) refreshShelves(addon, pkg)
        Stremio.shelves(filesDir, addon).filter { type == null || it.catalog.type == type }.mapNotNull { shelf ->
            val kind = getString(if (shelf.catalog.type == "series") R.string.vod_series else R.string.vod_movies)
            val name = if (type != null) "${addon.name} · ${shelf.catalog.name}" else "$kind · ${shelf.catalog.name}"
            // In the TV's language when TMDB has the title (those it hasn't been asked about yet: once it's answered).
            val cards = shelf.titles.map { t -> localize(t) ?: t.also { if (wantsLocal(it)) missing += it.id } }
                .filter { hideKey(null, it.name) !in hidden }.map { titleCard(it, addon.url, pkg) }
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
 * marked with the icon of the tile [icon] it's under (Films, Series, or the add-on's own).
 */
internal fun titleCard(t: Stremio.Title, source: String?, icon: String?) =
    Prog(t.name, listOfNotNull(t.year, t.genres.firstOrNull(), t.rating?.let { "★ $it" }).joinToString(" · "), 0, t.background, null, null,
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
 * genre, what it's about; then Watch (Resume, when it was begun), or a series' seasons and episodes, each playing in
 * Piume's player with the episodes after it for the next ones. [season]: the season shown (the first, at first).
 * With TMDB knowing it, the trailer and the services that have it too, as on a TMDB result's page (after Watch, or
 * before a series' seasons); [available]: the services TMDB lists it on, once it answered.
 */
internal fun MainActivity.showVodTitle(meta: Stremio.TitleMeta, ref: TitleRef, season: Int? = null, available: Set<String>? = null) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val t = meta.title
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val panelWidth = dp(380) // the panel's width (activity_main.xml)
    val picture = FrameLayout(this).apply { setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt()) }
    // The art of the card (or search result) it was opened from, so the page goes on from it.
    if (ref.background != null) fillArt(picture, panelWidth, panelWidth * 9 / 16, ref.background, ref.logo, ref.poster ?: t.poster, t.name)
    else fillArt(picture, panelWidth, panelWidth * 9 / 16, t.background, t.logo, ref.poster ?: t.poster, t.name)
    val kind = getString(if (t.type == "series") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        addView(label(listOfNotNull((meta.rating ?: ref.rating)?.let { "★ $it" }, kind, t.year, meta.genres.firstOrNull(), meta.runtime).joinToString(" · "), 13f).apply { alpha = 0.7f })
        meta.description?.let { d ->
            addView(label(d, 13f).apply {
                maxLines = 10
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(8), 0, 0)
            })
        }
    }
    val sources = streamSources(t.type, meta.episodes.firstOrNull()?.id ?: t.id)
    val sole = soleAddon(sources) // the one add-on with streams for it: Watch on it, with its icon
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    var focus = 0
    // The trailer and the services, as TMDB knows the title (its id from the TV's-language lookup, see localizeMeta).
    val item = tmdbItemOf(t)
    val region = TmdbClient.region(tmdbLang())
    val known = available ?: item?.let { TmdbClient.cachedProviders(it, region) }
    val others = if (item == null) emptyList() else titleApps().filter { TmdbClient.offers(it.pkg, known) }
    val otherRows = others.map { a ->
        page(if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else getString(R.string.tmdb_search_on, a.label)) {
            closeLauncherSettings()
            openInApp(a, item)
        }
    }
    val episodic = sources.isNotEmpty() && t.type == "series" && meta.episodes.isNotEmpty()
    if (episodic) {
        rows += otherRows
        focus = rows.size
    }
    if (sources.isEmpty()) {
        rows += page(getString(R.string.title_no_streams)) { }
    } else if (!episodic) {
        val at = VodProgress.position(filesDir, t.id)
        val label = when {
            sole != null && at != null -> getString(R.string.title_resume_on, clock(at), sole.name)
            sole != null -> getString(R.string.tmdb_search_on, sole.name)
            at != null -> getString(R.string.title_resume, clock(at))
            else -> getString(R.string.title_watch)
        }
        rows += page(label) {
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
            if (begun[e.id] != null && focus == otherRows.size) focus = rows.size
            rows += page(name, value) {
                closeLauncherSettings()
                playTitle(ref, meta, e)
            }
        }
        if (season != null) focus = otherRows.size // a season picked: stay on its row
    }
    val othersAt = if (episodic) 0 else rows.size
    if (!episodic) rows += otherRows
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
    // Watch marked with its add-on's icon (the one add-on's), else Play, as a TMDB page's rows are with their apps'.
    if (sources.isNotEmpty() && (t.type != "series" || meta.episodes.isEmpty())) (settingsList.getChildAt(1) as? LinearLayout)?.addView(ImageView(this).apply {
        setImageDrawable(watchIcon(sole))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
    others.forEachIndexed { i, a ->
        (settingsList.getChildAt(1 + othersAt + i) as? LinearLayout)?.addView(ImageView(this).apply {
            setImageDrawable(a.icon.constantState?.newDrawable(resources) ?: a.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
    }
    // Which services have it: once TMDB answers, the page again with only those (as a TMDB result's).
    val key = tmdbKey()
    if (item == null || known != null || !providersOn() || key == null) return
    val title = t.name
    spotlightIo.execute {
        val found = TmdbClient.providers(key, item, region) ?: return@execute
        ui.post {
            val stillOpen = settingsScrim.visibility == View.VISIBLE && findViewById<android.widget.TextView>(R.id.settingsTitle).text.toString() == title
            if (stillOpen) showVodTitle(meta, ref, season, found)
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

/** The add-ons with streams for films ([tv] false) or series, whatever the id: on a TMDB page, before its IMDb id is known. */
internal fun MainActivity.vodStreamAddons(tv: Boolean): List<Stremio.Addon> =
    addonUrls().mapNotNull { Stremio.saved(filesDir, it)?.addon }.filter { it.provides("stream", if (tv) "series" else "movie") }

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
 * stream add-ons that have it are asked for their streams (see PlayerActivity, MODE_VOD).
 */
internal fun MainActivity.playTitle(ref: TitleRef, meta: Stremio.TitleMeta?, episode: Stremio.Episode?) {
    val t = meta?.title
    val name = t?.name ?: ref.name
    val poster = t?.poster ?: ref.poster
    val art = ref.background ?: t?.background ?: poster // the card's: what loads in the player, and its Continue Playing card
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
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { it.third?.thumbnail ?: art })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { "" })
            .putStringArrayListExtra(PlayerActivity.DESCS, strings { it.third?.overview ?: meta?.description })
            .putExtra(PlayerActivity.META_TYPE, type)
            .putExtra(PlayerActivity.META_ID, t?.id ?: ref.id)
            .putExtra(PlayerActivity.POSTER, art))
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

/**
 * A TMDB result's Watch: its IMDb id, then a film plays at once; a series' page from the add-ons (its episodes)
 * takes the result's page's place, with the same art.
 */
internal fun MainActivity.watchTmdbTitle(item: TmdbItem) {
    val key = prefs.getString(K.TMDB_API_KEY, null) ?: return
    val art = searchArt[item.id]
    Toast.makeText(this, getString(R.string.player_loading), Toast.LENGTH_SHORT).show()
    netIo.execute {
        val imdb = TmdbClient.imdbIdOf(key, item)
        val ref = imdb?.let {
            TitleRef(if (item.mediaType == "tv") "series" else "movie", it, item.title,
                art?.poster ?: item.posterPath?.let { p -> "https://image.tmdb.org/t/p/w500$p" },
                art?.backdrop ?: TmdbClient.cardUrl(item.backdropPath), logo = art?.logo,
                rating = item.voteAverage.takeIf { v -> v > 0 }?.let { v -> "%.1f".format(java.util.Locale.ROOT, v) })
        }
        val meta = ref?.let { titleMeta(it) }
        ui.post {
            when {
                ref == null -> Toast.makeText(this, getString(R.string.title_no_page), Toast.LENGTH_LONG).show()
                ref.type == "movie" -> {
                    closeLauncherSettings()
                    playTitle(ref, meta, null)
                }
                meta != null -> showVodTitle(meta, ref)
                else -> Toast.makeText(this, getString(R.string.title_no_page), Toast.LENGTH_LONG).show()
            }
        }
    }
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

/** Asks TMDB for [ids] in the TV's language, a few at a time; once in, the rows under [pkg] again if it's still in focus. */
private fun MainActivity.localizeLater(ids: List<String>, pkg: String) {
    val key = tmdbKey() ?: return
    val lang = tmdbLang()
    val todo = ids.filter { Local.asking.add(it) }
    if (todo.isEmpty()) return
    netIo.execute {
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
