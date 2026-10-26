package link.mccoy88f.zephyr

import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast

// A film's or series' page in the side panel (its seasons, an episode's page, the trailer and services), and
// playing one in Zephyr TV's player. Functions of MainActivity, like the other topics; see Titles for the rows.

/**
 * OK on a film's or series' card: its page, from the add-on it came from if that one has pages for it, else the
 * first that does (Cinemeta, for IMDb ids). A card from Continue Playing goes on playing instead.
 */
internal fun MainActivity.openTitle(ref: TitleRef) {
    if (ref.video != null) return resumeTitle(ref)
    // The page at once, with the card's art and Loading…: it fills in once the add-on answers (if it's still open).
    titlePage(ref.name, Triple(ref.background, ref.logo, ref.poster), ref.name, titleTop("", null, null),
        listOf(page(getString(R.string.player_loading)) { }), 0, emptyMap()) { closeLauncherSettings() }
    netIo.execute {
        val meta = titleMeta(ref)
        ui.post {
            if (!pageShowing(ref.name)) return@post // closed meanwhile
            when {
                meta != null -> showVodTitle(meta, ref)
                ref.type == "movie" -> { closeLauncherSettings(); playTitle(ref, null, null) }
                else -> showPage(ref.name, listOf(page(getString(R.string.title_no_page)) { closeLauncherSettings() }), back = { closeLauncherSettings() })
            }
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
    if (season == null && episode == null && focusRow == null) markWhole(meta) // on opening: episodes watched since
    // Watched (by the user in use): a film when, a series its last episode watched and when.
    val mine = VodWatched.all(vodDir())
    val seenLine = if (t.type == "series") mine.values.filter { it.metaId == t.id && ':' in it.id }.maxByOrNull { it.at }?.let { m ->
        val e = meta.episodes.firstOrNull { it.id == m.id }
        getString(R.string.title_last_watched, listOfNotNull(e?.let { episodeLine(it) }, watchedOn(m.at)).joinToString(" · "))
    } else mine[t.id]?.let { m -> watchedOn(m.at)?.let { getString(R.string.title_watched_on, it) } ?: getString(R.string.title_watched) }
    val top = titleTop(listOfNotNull((meta.rating ?: ref.rating)?.let { "★ $it" }, kind, t.year, meta.genres.firstOrNull(), meta.runtime)
        .joinToString(" · "), null, meta.description, seenLine)
    val sources = streamSources(t.type, meta.episodes.firstOrNull()?.id ?: t.id)
    val sole = soleAddon(sources) // the one add-on with streams for it: Watch on it, with its icon
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    var focus = 0
    val services = serviceRows(t, available)
    val episodic = sources.isNotEmpty() && t.type == "series" && meta.episodes.isNotEmpty()
    var sides: ((Int) -> Unit)? = null
    var seasonAt: Int? = null // the season's row, among the rows
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
        icons[rows.size] = watchIcon(sole)
        rows += page(watchLabel(sole, at, seen), if (seen) "✓" else "") {
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
            seasonAt = rows.size
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
            VodWatched.set(vodDir(), listOf(t.id to t.id), !seen, name = t.name)
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
    // The season's row, with the focus, at the top of the panel: its episodes under it show (not just the row, at
    // the bottom, as scrolling only as far as the focus needs would leave it). After a season picked, at once.
    seasonAt?.let { i -> settingsList.getChildAt(1 + i) }?.let { row ->
        val scroll = settingsList.parent as android.widget.ScrollView
        fun top() = (row.top + settingsList.top - dp(8)).coerceAtLeast(0)
        val own = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, focused ->
            own?.onFocusChange(v, focused)
            if (focused) scroll.post { scroll.smoothScrollTo(0, top()) }
        }
        if (focus == seasonAt) scroll.post { scroll.scrollTo(0, top()) }
    }
    // Built again once TMDB says which services have it: on the row the focus is on by then, not back at the start.
    // Under the rows, what holding OK on an episode does: nothing on screen tells it otherwise.
    if (episodic) settingsList.addView(label(getString(R.string.title_hold_hint), 12f).apply {
        alpha = 0.55f
        maxLines = 3
        setPadding(dp(14), dp(14), dp(14), dp(4))
    })
    services.later { showVodTitle(meta, ref, season, it, episode, focusedRow()) }
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
    val mark = VodWatched.all(vodDir())[e.id]
    val seen = mark != null
    val seenLine = mark?.let { m -> watchedOn(m.at)?.let { getString(R.string.title_watched_on, it) } ?: getString(R.string.title_watched) }
    val top = titleTop(listOfNotNull(episodeLine(e), released).joinToString(" · "), e.name.takeIf { it.isNotBlank() }, e.overview ?: meta.description, seenLine)
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
            rows += page(watchLabel(sole, at, seen)) { play(sole?.base) }
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
    services.later { showVodEpisode(meta, ref, e, it, found, focusedRow() ?: focus) }
    // The stream add-ons asked whether they have it (how many streams each), all at once; the page again once they've said.
    if (found != null || sources.isEmpty()) return
    netIo.execute {
        val counts = parallel(sources, 4, 15) { (base, name) -> base to runCatching { Stremio.titleStreams(base, name, t.type, e.id).size }.getOrDefault(0) }.toMap()
        ui.post { if (pageShowing(title)) showVodEpisode(meta, ref, e, services.known, counts, focusedRow() ?: focus) }
    }
}

/**
 * Marks [episodes] of the series [meta] watched ([on]) or not, from its pages: watched ones off Continue Playing; the
 * series itself watched once all its episodes are (specials aside), and not any more once one isn't.
 */
private fun MainActivity.markWatched(meta: Stremio.TitleMeta, episodes: List<Stremio.Episode>, on: Boolean) {
    val id = meta.title.id
    VodWatched.set(vodDir(), episodes.map { it.id to id }, on, name = meta.title.name)
    traktHistory(episodes.map { it.id to id }, on)
    if (on) episodes.forEach { VodProgress.remove(vodDir(), it.id) }
    markWhole(meta)
}

/**
 * The series [meta] marked watched once all its episodes are (specials aside), and not any more once one isn't:
 * after marks on its pages, and when its page opens (the player and Trakt mark episodes only).
 */
private fun MainActivity.markWhole(meta: Stremio.TitleMeta) {
    if (meta.title.type != "series" || meta.episodes.isEmpty()) return
    val id = meta.title.id
    val marks = VodWatched.all(vodDir())
    val whole = meta.episodes.filter { it.season > 0 }.ifEmpty { meta.episodes }.all { it.id in marks }
    if (whole != (id in marks)) VodWatched.set(vodDir(), listOf(id to id), whole, name = meta.title.name)
}

/**
 * A film's or episode's Watch row: Resume from where it [at] got to, Watch again once [seen], else Watch; "on" the
 * add-on when one alone ([sole]) has its streams.
 */
private fun MainActivity.watchLabel(sole: Stremio.Addon?, at: Long?, seen: Boolean) = when {
    sole != null && at != null -> getString(R.string.title_resume_on, clock(at), sole.name)
    at != null -> getString(R.string.title_resume, clock(at))
    sole != null && seen -> getString(R.string.title_watch_again_on, sole.name)
    seen -> getString(R.string.title_watch_again)
    sole != null -> getString(R.string.tmdb_search_on, sole.name)
    else -> getString(R.string.title_watch)
}

/** "Season 1 · Episode 3" (specials: "Special 3"). */
internal fun MainActivity.episodeLine(e: Stremio.Episode) =
    if (e.season == 0) getString(R.string.title_special_n, e.episode) else getString(R.string.title_season_episode, e.season, e.episode)

/** Under a title's art: its [info] line, a [name] (an episode's), when the user [seen] it, and what it's about. */
private fun MainActivity.titleTop(info: String, name: String?, about: String?, seen: String? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
    name?.let { addView(label(it, 15f).apply { setPadding(0, 0, 0, dp(4)) }) }
    addView(label(info, 13f).apply { alpha = 0.7f })
    seen?.let { addView(label("✓  $it", 13f).apply { setPadding(0, dp(4), 0, 0) }) }
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

/**
 * Plays the film, or the episode [episode] with the ones after it (for the next ones), in Zephyr TV's player: the
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
