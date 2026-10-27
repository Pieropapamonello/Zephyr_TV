package link.mccoy88f.zephyr

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
    val (top, _) = titleTop("", null, null)
    titlePage(ref.name, Triple(ref.background, ref.logo, ref.poster), ref.name, top,
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
 * A film's or series' page in the side panel: its art across the top, rating, kind, year and
 * genre, what it's about; horizontally scrollable app icons without "Watch on..."; then Watch (for movies),
 * or the list of seasons (for TV series).
 */
internal fun MainActivity.showVodTitle(meta: Stremio.TitleMeta, ref: TitleRef, available: Set<String>? = null,
                                       focusRow: Int? = null) {
    val t = meta.title
    val kind = getString(if (t.type == "series") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    markWhole(meta)
    // Watched (by the user in use): a film when, a series its last episode watched and when.
    var mine = VodWatched.all(vodDir())
    if (t.type == "series" && t.id in mine && meta.episodes.isNotEmpty() && meta.episodes.none { it.id in mine }) {
        VodWatched.set(vodDir(), meta.episodes.map { it.id to t.id }, true, name = t.name)
        mine = VodWatched.all(vodDir())
    }
    val seenLine = if (t.type == "series") mine.values.filter { it.metaId == t.id && ':' in it.id }.maxByOrNull { it.at }?.let { m ->
        val e = meta.episodes.firstOrNull { it.id == m.id }
        getString(R.string.title_last_watched, listOfNotNull(e?.let { episodeLine(it) }, watchedOn(m.at)).joinToString(" · "))
    } else mine[t.id]?.let { m -> watchedOn(m.at)?.let { getString(R.string.title_watched_on, it) } ?: getString(R.string.title_watched) }

    val (servicesBar, services) = serviceBar(t, available)
    val (top, descView) = titleTop(listOfNotNull((meta.rating ?: ref.rating)?.let { "★ $it" }, kind, t.year, meta.genres.firstOrNull(), meta.runtime)
        .joinToString(" · "), null, meta.description, seenLine, servicesBar)

    val sources = streamSources(t.type, meta.episodes.firstOrNull()?.id ?: t.id)
    val sole = soleAddon(sources) // the one add-on with streams for it: Watch on it, with its icon
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    val holds = HashMap<Int, () -> Unit>()
    val marks = VodWatched.all(vodDir())

    val episodic = sources.isNotEmpty() && t.type == "series" && meta.episodes.isNotEmpty()
    if (sources.isEmpty()) {
        rows += page(getString(R.string.title_no_streams)) { }
    } else if (!episodic) {
        val at = VodProgress.position(vodDir(), t.id)
        val seen = t.id in marks
        icons[rows.size] = watchIcon(sole)
        rows += page(watchLabel(sole, at, seen), if (seen) "✓" else "") {
            pageReturn = { showVodTitle(meta, ref, services.known) }
            playTitle(ref, meta, null)
        }
        rows += page(getString(if (seen) R.string.title_mark_unwatched else R.string.title_mark_watched)) {
            VodWatched.set(vodDir(), listOf(t.id to t.id), !seen, name = t.name)
            traktHistory(listOf(t.id to t.id), !seen)
            if (!seen) VodProgress.remove(vodDir(), t.id)
            showVodTitle(meta, ref, services.known, focusRow = 1)
        }
    } else {
        // TV Series: show the list of seasons on the main title page
        val seasons = meta.episodes.map { it.season }.distinct()
        fun seasonName(s: Int) = if (s == 0) getString(R.string.title_specials) else getString(R.string.title_season, s)
        for (s in seasons) {
            val inSeason = meta.episodes.filter { it.season == s }
            val watchedCount = inSeason.count { it.id in marks }
            val allWatched = inSeason.isNotEmpty() && watchedCount == inSeason.size
            val value = if (allWatched) "✓" else if (watchedCount > 0) "$watchedCount/${inSeason.size} ✓" else resources.getQuantityString(R.plurals.title_episodes, inSeason.size, inSeason.size)
            rows += page(seasonName(s), value) {
                showVodSeason(meta, ref, s, services.known)
            }
        }
        val allSeriesWatched = meta.episodes.all { it.id in marks }
        rows += page(getString(if (allSeriesWatched) R.string.title_unmark_season else R.string.title_mark_season)) {
            markWatched(meta, meta.episodes, !allSeriesWatched)
            showVodTitle(meta, ref, services.known, focusRow = seasons.size)
        }
    }

    val focus = focusRow ?: if (descView != null) -1 else 0
    val art = if (ref.background != null) Triple(ref.background, ref.logo, ref.poster ?: t.poster) else Triple(t.background, t.logo, ref.poster ?: t.poster)
    titlePage(t.name, art, t.name, top, rows, focus, icons, holds, descView) { closeLauncherSettings() }
    services.later { showVodTitle(meta, ref, it, focusRow) }
}

/**
 * A series' season page: with Left/Right (sx / dx) you move between seasons, and below it the list of episodes.
 */
internal fun MainActivity.showVodSeason(meta: Stremio.TitleMeta, ref: TitleRef, season: Int, available: Set<String>? = null,
                                        episode: String? = null, focusRow: Int? = null) {
    val t = meta.title
    val seasons = meta.episodes.map { it.season }.distinct()
    val shown = season.takeIf { it in seasons } ?: seasons.first()
    fun seasonName(s: Int) = if (s == 0) getString(R.string.title_specials) else getString(R.string.title_season, s)
    val at = seasons.indexOf(shown)
    val sides = { step: Int -> showVodSeason(meta, ref, seasons[Math.floorMod(at + step, seasons.size)], available) }

    val marks = VodWatched.all(vodDir())
    val inSeason = meta.episodes.filter { it.season == shown }
    val watchedCount = inSeason.count { it.id in marks }
    val seenLine = if (watchedCount == inSeason.size) "✓ " + getString(R.string.title_watched) else if (watchedCount > 0) "$watchedCount / ${inSeason.size} " + getString(R.string.title_watched) else null

    val (servicesBar, services) = serviceBar(t, available)
    val (top, _) = titleTop(listOfNotNull(t.name, seasonName(shown), resources.getQuantityString(R.plurals.title_episodes, inSeason.size, inSeason.size))
        .joinToString(" · "), null, null, seenLine, servicesBar)

    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    val holds = HashMap<Int, () -> Unit>()
    var focus = 0

    // Row 0: Season switcher header (navigates with sx / dx)
    rows += page(seasonName(shown), "‹ ${at + 1} / ${seasons.size} ›") { sides(1) }

    val begun = VodProgress.all(vodDir()).associateBy { it.id }
    val ahead = inSeason.firstOrNull { begun[it.id] != null }?.id
        ?: inSeason.takeIf { list -> list.any { it.id in marks } }?.firstOrNull { it.id !in marks }?.id

    inSeason.forEach { e ->
        val name = getString(R.string.title_episode_row, e.episode, e.name.ifBlank { getString(R.string.title_episode_n, e.episode) })
        val value = begun[e.id]?.let { if (it.upNext) getString(R.string.title_up_next) else clock(it.position) }
            ?: if (e.id in marks) "✓" else ""
        if (episode == e.id || (episode == null && focusRow == null && e.id == ahead)) focus = rows.size
        holds[rows.size] = { // hold OK: watched, or not
            markWatched(meta, listOf(e), e.id !in marks)
            showVodSeason(meta, ref, shown, services.known, e.id)
        }
        rows += page(name, value) { showVodEpisode(meta, ref, e, services.known) }
    }
    val all = inSeason.all { it.id in marks }
    val markAt = rows.size
    rows += page(getString(if (all) R.string.title_unmark_season else R.string.title_mark_season)) {
        markWatched(meta, inSeason, !all)
        showVodSeason(meta, ref, shown, services.known, focusRow = markAt)
    }

    focusRow?.let { focus = it }
    val art = if (ref.background != null) Triple(ref.background, ref.logo, ref.poster ?: t.poster) else Triple(t.background, t.logo, ref.poster ?: t.poster)
    val pageTitle = "${t.name} · ${seasonName(shown)}"
    titlePage(pageTitle, art, t.name, top, rows, focus, icons, holds) {
        showVodTitle(meta, ref, services.known)
    }
    pageSides = sides
    services.later { showVodSeason(meta, ref, shown, it, episode, focusRow) }
}

/**
 * An episode's page, from its row on its season's page: its still (else the series' art) across the top, its season,
 * number and name, when it came out, what it's about; then Watch (or Resume).
 */
internal fun MainActivity.showVodEpisode(meta: Stremio.TitleMeta, ref: TitleRef, e: Stremio.Episode, available: Set<String>? = null,
                                         found: Map<String, Int>? = null, focus: Int = -1) {
    val t = meta.title
    val title = getString(R.string.player_episode, t.name, e.season, e.episode)
    val released = e.released?.take(10)?.let { d -> runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).parse(d) }.getOrNull() }
        ?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(it) }
    val mark = VodWatched.all(vodDir())[e.id]
    val seen = mark != null
    val seenLine = mark?.let { m -> watchedOn(m.at)?.let { getString(R.string.title_watched_on, it) } ?: getString(R.string.title_watched) }

    val (servicesBar, services) = serviceBar(t, available)
    val (top, descView) = titleTop(listOfNotNull(episodeLine(e), released).joinToString(" · "), e.name.takeIf { it.isNotBlank() }, e.overview ?: meta.description, seenLine, servicesBar)

    val sources = streamSources(t.type, e.id)
    val rows = ArrayList<Triple<String, String, () -> Unit>>()
    val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    fun play(only: String?) {
        pageReturn = { showVodEpisode(meta, ref, e, services.known, found, focus) }
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
    val still = e.thumbnail
    val art = when {
        still != null -> Triple(still, null, ref.poster ?: t.poster)
        ref.background != null -> Triple(ref.background, ref.logo, ref.poster ?: t.poster)
        else -> Triple(t.background, t.logo, ref.poster ?: t.poster)
    }
    val targetFocus = if (focus < 0 && descView != null) -1 else if (focus < 0) 0 else focus
    titlePage(title, art, t.name, top, rows, targetFocus, icons, descView = descView) {
        showVodSeason(meta, ref, e.season, services.known, e.id)
    }
    services.later { showVodEpisode(meta, ref, e, it, found, focusedRow() ?: targetFocus) }
    // The stream add-ons asked whether they have it (how many streams each), all at once; the page again once they've said.
    if (found != null || sources.isEmpty()) return
    netIo.execute {
        val counts = parallel(sources, 4, 15) { (base, name) -> base to runCatching { Stremio.titleStreams(base, name, t.type, e.id).size }.getOrDefault(0) }.toMap()
        ui.post { if (pageShowing(title)) showVodEpisode(meta, ref, e, services.known, counts, focusedRow() ?: targetFocus) }
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

/** Under a title's art: its [info] line, a [name] (an episode's), when the user [seen] it, what it's about, and [servicesBar]. */
private fun MainActivity.titleTop(info: String, name: String?, about: String?, seen: String? = null, servicesBar: View? = null): Pair<View, View?> {
    var descView: View? = null
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        name?.let { addView(label(it, 15f).apply { setPadding(0, 0, 0, dp(4)) }) }
        addView(label(info, 13f).apply { alpha = 0.7f })
        seen?.let { addView(label("✓  $it", 13f).apply { setPadding(0, dp(4), 0, 0) }) }
        about?.let { d ->
            val v = label(d, 13f).apply {
                id = View.generateViewId()
                maxLines = 10
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, dp(6)) // no box: in line with the lines above it
                isFocusable = true
                isClickable = true
                nextFocusUpId = id
                background = null
                setOnClickListener {
                    maxLines = if (maxLines == 10) 50 else 10
                }
            }
            descView = v
            addView(v)
        }
        servicesBar?.let { sb ->
            addView(sb)
            val container = (sb as? android.widget.HorizontalScrollView)?.getChildAt(0) as? LinearLayout
            if (container != null && container.childCount > 0) {
                val firstChild = container.getChildAt(0)
                descView?.let { dv ->
                    dv.nextFocusDownId = firstChild.id
                    for (c in 0 until container.childCount) {
                        container.getChildAt(c).nextFocusUpId = dv.id
                    }
                }
            }
        }
    }
    return root to descView
}

/**
 * A title's page in the panel: [art] (backdrop, logo, poster) across the top, over the panel's padding so it reaches
 * its edges and the top of the screen; [top] under it, then [rows], each with its icon from [icons] by its index.
 */
private fun MainActivity.titlePage(title: String, art: Triple<String?, String?, String?>, name: String, top: View,
                                   rows: List<Triple<String, String, () -> Unit>>, focus: Int,
                                   icons: Map<Int, android.graphics.drawable.Drawable>, holds: Map<Int, () -> Unit> = emptyMap(),
                                   descView: View? = null,
                                   back: () -> Unit) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val panelWidth = dp(380) // the panel's width (activity_main.xml)
    val picture = FrameLayout(this).apply { setBackgroundColor(artGround()) }
    fillArt(picture, panelWidth, panelWidth * 9 / 16, art.first, art.second, art.third, name)
    showPage(title, rows, focus, top = top, back = back, panes = glass)
    panel.addView(picture, 0, LinearLayout.LayoutParams(panelWidth, panelWidth * 9 / 16).apply {
        setMargins(-panel.paddingStart, -panel.paddingTop, -panel.paddingEnd, dp(20))
    })
    panel.clipToPadding = false
    pageExit = {
        panel.removeView(picture)
        panel.clipToPadding = true
    }
    // If starting on description, focus it and scroll to top
    if (focus < 0 && descView != null) {
        descView.post {
            descView.requestFocus()
            val scroll = settingsList.parent as? android.widget.ScrollView
            scroll?.scrollTo(0, 0)
        }
    }
    descView?.setOnFocusChangeListener { _, focused ->
        if (focused) {
            val scroll = settingsList.parent as? android.widget.ScrollView
            scroll?.post { scroll.smoothScrollTo(0, 0) }
        }
    }
    // Back up on the first row, the page scrolls back up: nothing above it takes focus to pull it there (unless top has focusables).
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
    // Each row with an icon starts with it, before the name. Invert tint on focus for vector/shape icons.
    for ((i, icon) in icons) {
        val row = settingsList.getChildAt(1 + i) as? LinearLayout ?: continue
        val iv = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
        row.addView(iv, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val orig = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, focused ->
            orig?.onFocusChange(v, focused)
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                iv.setColorFilter(if (focused && !glass) onFg else fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
    }
}

/**
 * A title's trailer and streaming apps, as TMDB knows it, shown horizontally without "Watch on...";
 * [known]: the services TMDB lists it on, when it has answered. [later] asks it when it hasn't,
 * and calls back with the answer.
 */
private class Services(val known: Set<String>?, val later: (((Set<String>) -> Unit) -> Unit))

private fun MainActivity.serviceBar(t: Stremio.Title, available: Set<String>?): Pair<View?, Services> {
    val item = tmdbItemOf(t) ?: return null to Services(available) { }
    val region = TmdbClient.region(tmdbLang())
    val known = available ?: TmdbClient.cachedProviders(item, region)
    val apps = titleApps().filter { TmdbClient.offers(it.pkg, known) }

    val barView = if (apps.isEmpty()) null else {
        android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(10), 0, dp(4))
            clipToPadding = false

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val rest = if (glass) 0x662C2C34.toInt() else if (dark) 0x991C1C22.toInt() else 0x99DCE0E8.toInt()

            val btnIds = apps.indices.map { View.generateViewId() }
            for ((idx, a) in apps.withIndex()) {
                val btn = FrameLayout(context).apply {
                    id = btnIds[idx]
                    isFocusable = true
                    isClickable = true
                    nextFocusLeftId = if (idx > 0) btnIds[idx - 1] else btnIds[0]
                    nextFocusRightId = if (idx < apps.size - 1) btnIds[idx + 1] else btnIds[idx]
                    val pad = dp(6)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else a.label

                    val bg = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(rest)
                    }
                    background = bg
                    val light = if (glass) glassPane(this, dp(8)) else null

                    val iv = ImageView(context).apply {
                        setImageDrawable(a.icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(iv, FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                        gravity = Gravity.CENTER
                    })

                    // Focus marks it with a ring inside its own bounds: grown, it was cut off by the bar's edges.
                    setOnFocusChangeListener { _, focused ->
                        if (light != null) light(focused) else bg.setStroke(if (focused) dp(2) else 0, if (focused) fg else Color.TRANSPARENT)
                        if (focused) {
                            (parent?.parent as? android.widget.HorizontalScrollView)?.smoothScrollTo(left - dp(16), 0)
                        }
                    }
                    setOnClickListener {
                        closeLauncherSettings()
                        openInApp(a, item)
                    }
                }
                container.addView(btn, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginStart = if (idx > 0) dp(8) else 0
                })
            }
            addView(container)
        }
    }

    val title = findViewById<android.widget.TextView>(R.id.settingsTitle)
    val services = Services(known) { again ->
        val key = tmdbKey()
        if (known == null && providersOn() && key != null) {
            val shown = title?.text?.toString() ?: ""
            spotlightIo.execute {
                val found = TmdbClient.providers(key, item, region) ?: return@execute
                ui.post { if (pageShowing(shown)) again(found) }
            }
        }
    }
    return barView to services
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
            return d
        }
        addon.logo?.takeIf { channelLogoFiles.has(it) }?.let { uri ->
            runCatching { android.graphics.BitmapFactory.decodeFile(channelLogoFiles.get(uri) { u -> openStream(u) }.path) }.getOrNull()
                ?.let { return android.graphics.drawable.BitmapDrawable(resources, it) }
        }
    }
    return getDrawable(R.drawable.ic_play)!!.mutate()
}

/**
 * Plays the film, or the episode [episode] with the ones after it (for the next ones), in Zephyr TV's player: the
 * stream add-ons that have it are asked for their streams (see PlayerActivity, MODE_VOD), or [only] the one at that
 * address. The player shows the series' name, and the episode's season, number and name under it.
 */
internal fun MainActivity.playTitle(ref: TitleRef, meta: Stremio.TitleMeta?, episode: Stremio.Episode?, only: String? = null) {
    val t = meta?.title
    val name = t?.name ?: ref.name
    val poster = ref.poster ?: t?.poster
    val bg = ref.background ?: t?.background
    val locandina = poster ?: bg
    val backdrop = bg ?: poster
    // One item for a film; the episode and the ones after it for a series.
    val items: List<Triple<String, String, Stremio.Episode?>> = if (episode == null || meta == null) listOf(Triple(t?.id ?: ref.id, name, null))
        else meta.episodes.dropWhile { it.id != episode.id }.map { e -> Triple(e.id, name, e) }
    val type = t?.type ?: ref.type
    val sources = streamSources(type, items.first().first).filter { only == null || it.first == only }
    if (sources.isEmpty()) return Toast.makeText(this, getString(R.string.title_no_streams), Toast.LENGTH_LONG).show()
    val adultUrls = adultAddonUrls()
    val adultAddons = adultUrls.mapNotNull { Stremio.saved(filesDir, it)?.addon }
    val adultSourcesList = adultUrls + adultAddons.map { it.base } + adultAddons.map { it.name }
    val isCatalogAdult = isAdultSource(ref.source)
    val isOnlyAdult = only != null && isAdultSource(only)
    fun strings(f: (Triple<String, String, Stremio.Episode?>) -> String?) = ArrayList(items.map { f(it).orEmpty() })
    try {
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.MODE, PlayerActivity.MODE_VOD)
            .putExtra(PlayerActivity.INDEX, 0)
            .putExtra(PlayerActivity.ADULT, isCatalogAdult || isOnlyAdult)
            .putStringArrayListExtra(PlayerActivity.ADULT_SOURCES, ArrayList(adultSourcesList))
            .putStringArrayListExtra(PlayerActivity.SOURCES, ArrayList(sources.map { it.first }))
            .putStringArrayListExtra(PlayerActivity.SOURCE_NAMES, ArrayList(sources.map { it.second }))
            .putStringArrayListExtra(PlayerActivity.TYPES, strings { type })
            .putStringArrayListExtra(PlayerActivity.IDS, strings { it.first })
            .putStringArrayListExtra(PlayerActivity.NAMES, strings { it.second })
            .putStringArrayListExtra(PlayerActivity.NOWS, strings { it.third?.let { e -> listOf(episodeLine(e), e.name).filter { n -> n.isNotBlank() }.joinToString(" · ") } ?: t?.year ?: "" })
            // In the player the art across: the backdrop (an episode's still without one), with the title's logo on it.
            .putStringArrayListExtra(PlayerActivity.LOGOS, strings { bg ?: it.third?.thumbnail ?: poster })
            .putStringArrayListExtra(PlayerActivity.FALLBACK_LOGOS, strings { it.third?.thumbnail ?: poster ?: "" })
            .putExtra(PlayerActivity.TITLE_LOGO, ref.logo ?: t?.logo)
            .putStringArrayListExtra(PlayerActivity.DESCS, strings { it.third?.overview ?: meta?.description })
            .putExtra(PlayerActivity.META_TYPE, type)
            .putExtra(PlayerActivity.META_ID, t?.id ?: ref.id)
            .putExtra(PlayerActivity.POSTER, locandina ?: backdrop)
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
