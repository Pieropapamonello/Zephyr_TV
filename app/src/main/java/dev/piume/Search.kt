package dev.piume

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import dev.piume.MainActivity.Companion.MIC_REQUEST
import dev.piume.MainActivity.Prog
import java.io.IOException

// Searching TMDB from the top bar: typed with the remote, dictated, or typed on a phone. The results get a tile of
// their own in the apps row, with their cards under it; Spotlight is left alone.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/**
 * The search page: a box to type in with the remote, dictation into that box, the TV's own assistant, and a QR
 * code to type on a phone instead. Without a TMDB key or Stremio add-ons there's nothing to search, so the TV's
 * assistant opens.
 */
internal fun MainActivity.openSearch() {
    if (prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank() && addonUrls().isEmpty()) return assistant()
    showPanel()
    fun go(query: String) {
        if (query.isBlank()) return
        closeLauncherSettings()
        searchTmdb(query.trim())
    }
    val input = EditText(this).apply {
        id = View.generateViewId()
        hint = getString(R.string.search_hint)
        textSize = 14f
        setTextColor(fg)
        setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setPadding(dp(14), 0, dp(14), 0)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(field)
            setStroke(dp(1), fieldEdge())
        }
        background = bg
        setOnFocusChangeListener { _, focused -> bg.setStroke(if (focused) dp(2) else dp(1), if (focused) fg else fieldEdge()) }
        setOnEditorActionListener { v, _, _ -> go(v.text.toString()); true }
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
        addView(input, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
    }
    val rows = listOf(
        page(getString(R.string.search_dictate)) { listen(input, ::go) },
        page(getString(R.string.search_assistant)) { closeLauncherSettings(); assistant() },
    )
    showPage(getString(R.string.search_title), rows, top = top) { closeLauncherSettings() }
    val first = settingsList.getChildAt(1)
    first.nextFocusUpId = input.id // showPage pins Up on the first row to itself; here it goes to the box
    // The TV keyboard keeps Down for itself while the box has focus, even with the keyboard put away.
    input.setOnKeyListener { _, key, e ->
        (key == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN).also { if (it) first.requestFocus() }
    }
    input.requestFocus()
    pageExit = { stopListening() }
    // Under the rows, the phone: the same search typed on its own keyboard. The link works while the page is open.
    val ip = lanAddress() ?: return
    val page = PhonePages.text(phoneTexts(R.string.phone_search_title, R.string.phone_search_intro), getString(R.string.search_hint))
    lateinit var link: PhoneLink // the task below checks it's still this page's
    link = try {
        PhoneLink(cacheDir, PhoneTask(page, 1024) { file ->
            val query = file.readText().trim().take(200)
            if (query.isEmpty()) return@PhoneTask 400 to getString(R.string.search_hint)
            ui.postDelayed({ if (upload === link) go(query) }, 300) // once the phone has its answer: closing the page stops the server
            200 to getString(R.string.phone_search_sent, query)
        })
    } catch (e: IOException) {
        return
    }
    upload = link
    pageExit = {
        stopListening()
        link.stop()
        if (upload === link) upload = null
    }
    settingsList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(20), 0, 0)
        addView(label(getString(R.string.search_phone), 12f).apply { gravity = Gravity.CENTER; alpha = 0.6f; maxLines = 2 })
        addView(ImageView(this@openSearch).apply { setImageBitmap(qrBitmap(link.url(ip), dp(160))) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    })
}

/**
 * Dictation into the search box, without leaving the page: what's heard so far shows in [input], and the search
 * starts once it's said. (The TV's own dictation screen hears it but, on some TVs, never hands the words back.)
 */
internal fun MainActivity.listen(input: EditText, go: (String) -> Unit) {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        afterMicAccess = { listen(input, go) }
        return requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }
    if (!SpeechRecognizer.isRecognitionAvailable(this)) {
        closeLauncherSettings()
        return assistant()
    }
    stopListening()
    val heard = SpeechRecognizer.createSpeechRecognizer(this)
    recognizer = heard
    input.setText("")
    input.hint = getString(R.string.search_listening)
    fun words(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
    heard.setRecognitionListener(object : RecognitionListener {
        override fun onPartialResults(b: Bundle?) {
            words(b)?.let {
                input.setText(it)
                input.setSelection(it.length)
            }
        }

        override fun onResults(b: Bundle?) {
            stopListening()
            val said = words(b)
            input.hint = getString(R.string.search_hint)
            if (said != null) go(said)
        }

        override fun onError(error: Int) {
            stopListening()
            input.hint = getString(R.string.search_not_heard)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    heard.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tmdbLang())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
}

internal fun MainActivity.stopListening() {
    recognizer?.destroy()
    recognizer = null
}

/**
 * Looks [query] up on TMDB (films and series). The results get a tile after Continue Watching, focused at once,
 * and their cards show under it. Back, or holding OK on the tile, closes it (endSearch).
 */
internal fun MainActivity.searchTmdb(query: String) {
    // Without a TMDB key, the TV channels only (the tuner's and the add-ons').
    val key = prefs.getString(K.TMDB_API_KEY, null)?.takeIf { it.isNotBlank() }
    spotlightIo.execute {
        val channels = runCatching { findChannels(query) }.getOrDefault(emptyList())
        val found = if (key == null) emptyList() else TmdbClient.search(key, query, tmdbLang())
        // The results show at once, on the plain art the search gives; their art with the title on it follows.
        ui.post {
            if (found.isEmpty() && channels.isEmpty()) return@post Toast.makeText(this, getString(R.string.toast_no_results, query), Toast.LENGTH_SHORT).show()
            searchQuery = query
            searchResults = found
            searchArt = emptyMap()
            searchChannels = channels
            watchApp = null // the tile's cards are new even if it's focused already
            renderApps()
            (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == SEARCH_PKG }?.requestFocus()
            val n = found.size + channels.size
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_search_results, n, n, query), Toast.LENGTH_LONG).show()
        }
        if (key == null) return@execute
        // Each result's landscape art with its title on it, all at once (one TMDB call each).
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val art = try {
            pool.invokeAll(found.map { item -> java.util.concurrent.Callable { item.id to TmdbClient.art(key, item, tmdbLang()) } })
                .mapNotNull { runCatching { it.get() }.getOrNull() }.toMap()
        } finally {
            pool.shutdown()
        }
        ui.post {
            if (searchQuery != query || searchResults !== found) return@post // another search, or none, since
            searchArt = art
            refreshSearchArt()
        }
    }
}

/** [p] with its art from [MainActivity.searchArt], when it's a search result that has some; else [p] itself. */
internal fun MainActivity.withArt(p: Prog): Prog {
    val item = p.tmdb ?: return p
    val art = searchArt[item.id] ?: return p
    return Prog(p.title, p.sub, 0, art.backdrop ?: TmdbClient.cardUrl(item.backdropPath), null, null,
        tmdb = item, logo = art.logo, poster = art.poster ?: item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" })
}

/**
 * The results' art arrived: each card built so far gets it in place (focus staying on the one it's on), and so do
 * those still to come (see RowCards).
 */
internal fun MainActivity.refreshSearchArt() {
    if (watchApp != SEARCH_PKG) return // not on screen: showSearchResults builds them with it
    for (row in cardRows()) {
        val cards = row.getTag(R.id.watchScroll) as? RowCards ?: continue
        cards.items = cards.items.map(::withArt)
        for (i in 0 until row.childCount) {
            val old = row.getChildAt(i) as? ViewGroup ?: continue
            val frame = old.getChildAt(0) ?: continue
            val p = frame.getTag(R.id.watchRow) as? Prog ?: continue
            val fresh = withArt(p)
            if (fresh === p) continue
            val focused = frame.hasFocus()
            val card = watchCard(fresh, i > 0)
            row.addView(card, i)
            if (focused) (card as ViewGroup).getChildAt(0).requestFocus()
            row.removeView(old)
        }
    }
}

/**
 * The results' cards under the apps row, a row each: the films, the series (landscape art with the title on it,
 * see TmdbClient.art, and what each is), then the TV channels it found.
 */
internal fun MainActivity.showSearchResults() {
    val query = searchQuery ?: return
    watchApp = SEARCH_PKG
    fun titles(key: String, heading: Int, items: List<TmdbItem>) = items.takeIf { it.isNotEmpty() }?.let { found ->
        Section(key, query, getString(heading, query), found.map { item ->
            val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
            val genre = item.genres.firstOrNull()
            withArt(Prog(item.title, listOfNotNull(year, genre).joinToString(" · "), 0, TmdbClient.cardUrl(item.backdropPath), null, null,
                tmdb = item, poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }))
        })
    }
    val channels = searchChannels.takeIf { it.isNotEmpty() }?.let { list ->
        Section("channels", query, getString(R.string.search_channels_for, query), list.map { d ->
            Prog(d.name, listOfNotNull(d.number, d.now?.title).filter { it.isNotEmpty() }.joinToString(" · "), d.now?.let { Epg.progress(it) } ?: 0,
                d.logo, d.intent, d.pkg, badge = d.number, channelPage = d)
        })
    }
    showSections(listOfNotNull(
        titles("films", R.string.search_films_for, searchResults.filter { it.mediaType != "tv" }),
        titles("series", R.string.search_series_for, searchResults.filter { it.mediaType == "tv" }),
        channels,
    ))
}

/** A TV channel the search found: what its card and its page show. */
internal class ChannelDetail(
    val name: String, val number: String, val logo: String?, val intent: String, val pkg: String?,
    val now: Epg.Show?, val next: Epg.Show?,
    val addon: AddonChannel? = null, // a Stremio add-on's channel: Watch plays it in Piume's player
)

/**
 * The tuner channels whose name has [query] in it, or whose number it is, with what's on (the TV's guide, else the
 * one set in Piume settings). At most 20. Runs off the UI thread.
 */
internal fun MainActivity.findChannels(query: String): List<ChannelDetail> {
    val words = ChannelLogos.normalize(query).takeIf { it.length >= 2 } ?: return emptyList()
    val number = query.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.trimStart('0')
    // The tuner's channels, then the Stremio add-ons' (see Addons): one row, the same cards.
    val tuner = if (checkSelfPermission(MainActivity.TV_LISTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) emptyList()
        else channelDetails(tunerChannels()
            .filter { ChannelLogos.normalize(it.name).contains(words) || (number != null && it.number.trimStart('0') == number) }
            .sortedBy { channelKey(it.number) }.take(20))
    return (tuner + findAddonChannels(words)).take(40)
}

/** What [channels]' pages show: logo, what's on now and next (the guide set in Piume settings, else the TV's). Off the UI thread. */
internal fun MainActivity.channelDetails(channels: List<TunerChannel>): List<ChannelDetail> {
    val tvApp = apps.firstOrNull { isTvApp(it.pkg) }?.pkg
    val logos = if (channelLogosOn()) ChannelLogos.saved(filesDir, channelCountry()).orEmpty() else emptyMap()
    val guide = epgUrl()?.let { Epg.saved(filesDir, it) }
    val onNow = programsOnNow()
    return channels.map { ch ->
        val epg = guide?.let { Epg.nowAndNext(it, ch.name) }
        val now = epg?.first ?: onNow[ch.id]?.let { Epg.Show(0, 0, it.title) }
        ChannelDetail(ch.name, ch.number.trimStart('0').ifEmpty { ch.number }, channelLogo(ch, logos),
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUri(ch.id)).toUri(android.content.Intent.URI_INTENT_SCHEME),
            tvApp, now, epg?.second)
    }
}

/** Hold OK on a channel card, Details: the channel's page, as search shows it. */
internal fun MainActivity.showChannelDetails(id: Long) = io.execute {
    val d = tunerChannels().firstOrNull { it.id == id }?.let { channelDetails(listOf(it)).first() } ?: return@execute
    ui.post { showChannel(d) }
}

/**
 * A channel's page in the side panel, like a film's: its logo across the top (on a blur of itself), what's on with
 * its time and what it's about, what follows, and Watch on TV.
 */
internal fun MainActivity.showChannel(d: ChannelDetail) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val panelWidth = dp(380) // the panel's width (activity_main.xml)
    val picture = FrameLayout(this).apply { setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt()) }
    channelArt(picture, panelWidth, panelWidth * 9 / 16, d.number, d.name) {
        listOfNotNull(d.logo, d.addon?.let { ChannelLogos.match(channelLogoTable(), d.name) })
    }
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    fun hours(s: Epg.Show) = if (s.start > 0) "${time.format(java.util.Date(s.start))} – ${time.format(java.util.Date(s.stop))}" else null
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        if (d.number.isNotEmpty()) addView(label(getString(R.string.channel_number, d.number), 13f).apply { alpha = 0.7f })
        d.now?.let { now ->
            addView(label(now.title, 16f).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 2
                setPadding(0, dp(10), 0, 0)
            })
            hours(now)?.let { addView(label(it, 13f).apply { alpha = 0.7f; setPadding(0, dp(2), 0, 0) }) }
            if (now.desc.isNotBlank()) addView(label(now.desc, 13f).apply {
                maxLines = 8
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(8), 0, 0)
            })
        }
        d.next?.let { next ->
            addView(label(getString(R.string.channel_next, hours(next)?.substringBefore(" –") ?: "", next.title), 13f).apply {
                alpha = 0.7f
                maxLines = 2
                setPadding(0, dp(10), 0, 0)
            })
        }
    }
    val rows = listOf(page(getString(if (d.addon != null) R.string.channel_play else R.string.channel_watch)) {
        closeLauncherSettings()
        val p = Prog(d.name, d.number, 0, null, d.intent, d.pkg, addon = d.addon)
        if (d.addon != null) playAddonChannel(p) else open(p)
    })
    showPage(d.name, rows, top = top) { closeLauncherSettings() }
    // Over the panel's padding, so it reaches its edges and the top of the screen; gone with the page.
    panel.addView(picture, 0, LinearLayout.LayoutParams(panelWidth, panelWidth * 9 / 16).apply {
        setMargins(-panel.paddingStart, -panel.paddingTop, -panel.paddingEnd, dp(20))
    })
    panel.clipToPadding = false
    pageExit = {
        panel.removeView(picture)
        panel.clipToPadding = true
    }
    d.pkg?.let { pkg ->
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { icon ->
            (settingsList.getChildAt(1) as? LinearLayout)?.addView(ImageView(this).apply {
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        }
    }
}

/** Closes the search: its tile and cards go, focus goes back to Continue Watching. False when none is open. */
internal fun MainActivity.endSearch(): Boolean {
    if (searchQuery == null) return false
    val onIt = currentFocus?.let { it.tag == SEARCH_PKG || (it.getTag(R.id.watchRow) as? Prog)?.let { p -> p.tmdb != null || p.channelPage != null } == true } ?: true
    searchQuery = null
    searchResults = emptyList()
    searchChannels = emptyList()
    searchArt = emptyMap()
    if (watchApp == SEARCH_PKG) watchApp = null
    renderApps()
    if (onIt) appRow.getChildAt(0)?.requestFocus()
    return true
}

/**
 * A result's page in the side panel: its card art across the top, edge to edge above the page title, what
 * Spotlight would say about it (rating, kind, year,
 * genre, the overview) under it, and a row per app to watch it in, each with the app's icon: the YouTube trailer
 * and the services TMDB lists it on, as Spotlight's buttons.
 */
internal fun MainActivity.showTitle(item: TmdbItem, available: Set<String>? = null) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
    val rating = if (item.voteAverage > 0) "★ %.1f".format(item.voteAverage) else null
    val kind = getString(if (item.mediaType == "tv") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    val art = searchArt[item.id]
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val panelWidth = dp(380) // the panel's width (activity_main.xml)
    val picture = FrameLayout(this).apply { setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt()) }
    fillArt(picture, panelWidth, panelWidth * 9 / 16, art?.backdrop ?: TmdbClient.cardUrl(item.backdropPath), art?.logo,
        art?.poster ?: item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }, item.title)
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), 0, dp(14), dp(12)) // in line with the page title and the rows' names
        addView(label(listOfNotNull(rating, kind, year, item.genres.firstOrNull()).joinToString(" · "), 13f).apply { alpha = 0.7f })
        if (item.overview.isNotBlank()) addView(label(item.overview, 13f).apply {
            maxLines = 12
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(8), 0, 0)
        })
    }
    val shown = titleApps().filter { TmdbClient.offers(it.pkg, available) }
    val rows = shown.map { a ->
        val name = if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else getString(R.string.tmdb_search_on, a.label)
        page(name) {
            closeLauncherSettings()
            openInApp(a, item)
        }
    }
    showPage(item.title, rows, top = top) { closeLauncherSettings() }
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
            // As far up as the row itself still shows: as much of the description as fits above it.
            val scroll = settingsList.parent as android.widget.ScrollView
            if (focused) scroll.post { scroll.smoothScrollTo(0, (v.bottom + dp(8) - scroll.height).coerceAtLeast(0)) }
        }
    }
    // Each row starts with its app's icon, before the name.
    shown.forEachIndexed { i, a ->
        (settingsList.getChildAt(1 + i) as? LinearLayout)?.addView(ImageView(this).apply {
            setImageDrawable(a.icon.constantState?.newDrawable(resources) ?: a.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
    }
    // Which services have it: known answers apply at once, others once TMDB answers (until then every app shows).
    val key = prefs.getString(K.TMDB_API_KEY, null)
    if (available != null || !providersOn() || key == null) return
    val region = TmdbClient.region(tmdbLang())
    TmdbClient.cachedProviders(item, region)?.let { return showTitle(item, it) }
    val title = item.title
    spotlightIo.execute {
        val found = TmdbClient.providers(key, item, region) ?: return@execute
        ui.post {
            val stillOpen = settingsScrim.visibility == View.VISIBLE && findViewById<android.widget.TextView>(R.id.settingsTitle).text.toString() == title
            if (stillOpen) showTitle(item, found)
        }
    }
}
