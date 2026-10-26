package link.mccoy88f.zephyr

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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import link.mccoy88f.zephyr.MainActivity.Companion.CHOSEN
import link.mccoy88f.zephyr.MainActivity.Companion.MIC_REQUEST
import link.mccoy88f.zephyr.MainActivity.Prog
import java.io.IOException

// Searching from the top bar (films and series in Cinemeta and the add-ons, TV channels): typed with the remote, dictated, or typed on a phone. The results get a tile of
// their own in the apps row, with their cards under it; Spotlight is left alone.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

private var searchFiltersExpanded = false

/**
 * The search page: a box to type in with the remote, the filters (films or series, year, genre: Search finds
 * with them alone too, without words), dictation into that box, the TV's own assistant, and a QR code to type on a
 * phone instead. [text]: what's in the box (the page built again after a filter changed); [focus]: the row to focus.
 */
internal fun MainActivity.openSearch(text: String = "", focus: Int? = null) {
    if (focus == null && text.isEmpty()) searchFiltersExpanded = false
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    // The keyboard's Search wants words or a filter; the Search row goes without (Cinemeta's popular ones).
    fun go(query: String, always: Boolean = false) {
        if (query.isBlank() && !searchFilter.any && !always) return
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
        setText(text)
    }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
        addView(input, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
    }
    val f = searchFilter
    val summary = if (!f.any) getString(R.string.search_filter_any)
        else listOfNotNull(if (f.type != "all") typeName(f.type) else null, f.year, f.genre?.let { genreName(it) }).joinToString(" · ")
    fun typed() = input.text?.toString().orEmpty()
    val filterRows = if (!searchFiltersExpanded) emptyList() else listOfNotNull(
        page(getString(R.string.search_filter_type), typeName(f.type)) {
            val types = listOf("all", "movie", "series")
            searchFilter = SearchFilter(types[(types.indexOf(f.type) + 1) % types.size], f.year, f.genre)
            openSearch(typed(), 2)
        },
        page(getString(R.string.search_filter_year), f.year ?: getString(R.string.search_filter_any)) { pickYear(typed()) },
        page(getString(R.string.search_filter_genre), f.genre?.let { genreName(it) } ?: getString(R.string.search_filter_any)) { pickGenre(typed()) },
        if (!f.any) null else page(getString(R.string.search_filter_clear)) {
            searchFilter = SearchFilter()
            openSearch(typed(), 1)
        },
    )
    val rows = listOf(
        // Search: with words, or with the filters alone (all of Cinemeta's popular ones, without either).
        page(getString(R.string.search_go)) { go(typed(), always = true) },
        page(getString(R.string.search_filters), if (searchFiltersExpanded) "▲ $summary" else "▼ $summary") {
            searchFiltersExpanded = !searchFiltersExpanded
            openSearch(typed(), 1)
        },
    ) + filterRows + listOf(
        page(getString(R.string.search_dictate)) { listen(input, ::go) },
        page(getString(R.string.search_assistant)) { closeLauncherSettings(); assistant() },
    )
    showPage(getString(R.string.search_title), rows, focus ?: 0, top = top) { closeLauncherSettings() }
    val first = settingsList.getChildAt(1)
    first.nextFocusUpId = input.id // showPage pins Up on the first row to itself; here it goes to the box
    // The TV keyboard keeps Down for itself while the box has focus, even with the keyboard put away.
    input.setOnKeyListener { _, key, e ->
        (key == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN).also { if (it) first.requestFocus() }
    }
    if (focus == null) input.requestFocus()
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
    val qrCard = FrameLayout(this).apply {
        id = View.generateViewId()
        isFocusable = true
        isFocusableInTouchMode = true
        val bg = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.TRANSPARENT)
        }
        background = bg
        val img = ImageView(this@openSearch).apply {
            setImageBitmap(qrBitmap(link.url(ip), dp(160)))
        }
        addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        })
        setOnFocusChangeListener { _, focused ->
            bg.setColor(if (focused) (0x22000000 or (fg and 0x00FFFFFF)) else Color.TRANSPARENT)
            bg.setStroke(if (focused) dp(2) else 0, if (focused) fg else Color.TRANSPARENT)
            if (focused) (settingsList.parent as? android.widget.ScrollView)?.smoothScrollTo(0, bottom + dp(40))
        }
    }
    settingsList.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(16), 0, dp(16))
        addView(label(getString(R.string.search_phone), 12f).apply { gravity = Gravity.CENTER; alpha = 0.6f; maxLines = 2 })
        addView(qrCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    })
    val lastRow = settingsList.getChildAt(rows.size)
    lastRow?.nextFocusDownId = qrCard.id
    qrCard.nextFocusUpId = lastRow?.id ?: View.NO_ID
    qrCard.nextFocusDownId = qrCard.id
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
 * Looks [query] up: the films and series in Cinemeta and the add-ons' catalogs (TMDB, with its key, finding them by
 * their names in the TV's language too: see searchLibrary), and the TV channels. The results get a tile after
 * Continue Watching, focused at once, and their cards show under it. Back, or holding OK on the tile, closes it (endSearch).
 */
internal fun MainActivity.searchTmdb(query: String) {
    val filter = searchFilter
    // What the results are for: the words, else the filters ("2020 · Drama"), else Cinemeta's popular ones.
    // With words and filters, both: the filters stay until they're cleared, and say so.
    val label = if (query.isBlank()) filterLabel(filter) else listOfNotNull(query, filterLabel(filter).takeIf { filter.any }).joinToString(" · ")
    spotlightIo.execute {
        // TV channels only for words, and not when films or series alone are asked for.
        val channels = if (query.isBlank() || filter.type != "all") emptyList() else runCatching { findChannels(query) }.getOrDefault(emptyList())
        val titles = runCatching {
            if (query.isBlank()) browseLibrary(filter)
            else searchLibrary(query).filter { (t, _) -> filter.accepts(t) { g -> genreName(g) } }
        }.getOrDefault(emptyList())
        ui.post {
            if (channels.isEmpty() && titles.isEmpty()) return@post Toast.makeText(this, getString(R.string.toast_no_results, label), Toast.LENGTH_SHORT).show()
            searchQuery = label
            searchChannels = channels
            searchTitles = titles
            watchApp = null // the tile's cards are new even if it's focused already
            renderApps()
            (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == SEARCH_PKG }?.requestFocus()
            val n = channels.size + titles.size
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_search_results, n, n, label), Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * The results' cards under the apps row, a row each: the films, the series (as under the Films and Series tiles),
 * then the TV channels it found.
 */
internal fun MainActivity.showSearchResults() {
    val query = searchQuery ?: return
    watchApp = SEARCH_PKG
    val channels = searchChannels.takeIf { it.isNotEmpty() }?.let { list ->
        Section("channels", query, getString(R.string.search_channels_for, query), list.map { d ->
            Prog(d.name, listOfNotNull(d.number, d.now?.title).filter { it.isNotEmpty() }.joinToString(" · "), d.now?.let { Epg.progress(it) } ?: 0,
                d.logo, d.intent, d.pkg, badge = d.number, channelPage = d)
        })
    }
    // Cinemeta's and the add-ons' films and series: their cards, as under the Films and Series tiles.
    fun addonTitles(key: String, heading: Int, type: String) = searchTitles.filter { it.first.type == type }.takeIf { it.isNotEmpty() }?.let { found ->
        Section(key, query, getString(heading, query), VodWatched.all(vodDir()).let { watched -> found.map { (t, source) -> titleCard(t, source, titleTile(t.type, source), t.id in watched) } })
    }
    showSections(listOfNotNull(
        addonTitles("addon-films", R.string.search_films_for, "movie"),
        addonTitles("addon-series", R.string.search_series_for, "series"),
        channels,
    ))
}

/** A TV channel the search found: what its card and its page show. */
internal class ChannelDetail(
    val name: String, val number: String, val logo: String?, val intent: String, val pkg: String?,
    val now: Epg.Show?, val next: Epg.Show?,
    val addon: AddonChannel? = null, // a Stremio add-on's channel: Watch plays it in Zephyr TV's player
)

/**
 * The tuner channels whose name has [query] in it, or whose number it is, with what's on (the TV's guide, else the
 * one set in Zephyr TV settings). At most 20. Runs off the UI thread.
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

/** What [channels]' pages show: logo, what's on now and next (the guide set in Zephyr TV settings, else the TV's). Off the UI thread. */
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
    // An add-on's channel: Watch on the tile it shows under (its own app, the IPTV app, the TV app), with its icon.
    val tile = d.addon?.let { addonTile(it) }
    val watch = tile?.let { getString(R.string.tmdb_search_on, it.label) } ?: getString(if (d.addon != null) R.string.channel_play else R.string.channel_watch)
    val rows = listOf(page(watch) {
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
    val icon = (d.pkg ?: tile?.pkg?.takeIf { !it.startsWith(STREMIO) })?.let { pkg -> runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() }
        ?: tile?.icon?.constantState?.newDrawable(resources)?.mutate()
    if (icon != null) {
        val row = settingsList.getChildAt(1) as? LinearLayout
        val iv = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                setColorFilter(fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
        row?.addView(iv, 0, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        val orig = row?.onFocusChangeListener
        row?.setOnFocusChangeListener { v, focused ->
            orig?.onFocusChange(v, focused)
            if (icon !is android.graphics.drawable.BitmapDrawable) {
                iv.setColorFilter(if (focused) onFg else fg, android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
    }
}

/** Closes the search: its tile and cards go, focus goes back to Continue Watching. False when none is open. */
internal fun MainActivity.endSearch(): Boolean {
    if (searchQuery == null) return false
    val onIt = currentFocus?.let { it.tag == SEARCH_PKG || (it.getTag(R.id.watchRow) as? Prog)?.let { p -> p.channelPage != null || p.vod != null } == true } ?: true
    searchQuery = null
    searchChannels = emptyList()
    searchTitles = emptyList()
    if (watchApp == SEARCH_PKG) watchApp = null
    renderApps()
    if (onIt) appRow.getChildAt(0)?.requestFocus()
    return true
}

// ---------- filters ----------

/** Films and series, films, series. */
private fun MainActivity.typeName(type: String) = getString(when (type) {
    "movie" -> R.string.tmdb_type_movie
    "series" -> R.string.tmdb_type_tv
    else -> R.string.tmdb_type_all
})

/** What a search with [f] and no words is for: "Films · 2020 · Drama", or Cinemeta's popular titles. */
private fun MainActivity.filterLabel(f: SearchFilter) = listOfNotNull(f.type.takeIf { it != "all" }?.let { typeName(it) }, f.year, f.genre?.let { genreName(it) })
    .joinToString(" · ").ifEmpty { getString(R.string.tmdb_source_cinemeta) }

/** The search's year: any, or one from this year back to 1920. */
private fun MainActivity.pickYear(text: String) {
    val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val years = listOf<String?>(null) + (now downTo 1920).map { it.toString() }
    val f = searchFilter
    showPage(getString(R.string.search_filter_year), years.map { y ->
        page(y ?: getString(R.string.search_filter_any), if (y == f.year) CHOSEN else "") {
            searchFilter = SearchFilter(f.type, y, f.genre)
            openSearch(text, 3)
        }
    }, years.indexOf(f.year).coerceAtLeast(0)) { openSearch(text, 3) }
    // Left/Right: ten years on or back, not a hundred rows one by one.
    pageSides = { step -> focusRow(((focusedRow() ?: 0) + step * 10).coerceIn(0, years.size - 1)) }
}

/** The search's genre: any, or one of Cinemeta's, in the TV's language. */
private fun MainActivity.pickGenre(text: String) {
    val genres = listOf<String?>(null) + libraryGenres()
    val f = searchFilter
    showPage(getString(R.string.search_filter_genre), genres.map { g ->
        page(g?.let { genreName(it) } ?: getString(R.string.search_filter_any), if (g == f.genre) CHOSEN else "") {
            searchFilter = SearchFilter(f.type, f.year, g)
            openSearch(text, 4)
        }
    }, genres.indexOf(f.genre).coerceAtLeast(0)) { openSearch(text, 4) }
}

/** Cinemeta's genres (IMDb's, in English) in the TV's language; one Zephyr TV doesn't know as it is. */
internal fun MainActivity.genreName(genre: String) = GENRES[genre]?.let { getString(it) } ?: genre

internal val GENRES = linkedMapOf(
    "Action" to R.string.genre_action, "Adventure" to R.string.genre_adventure, "Animation" to R.string.genre_animation,
    "Biography" to R.string.genre_biography, "Comedy" to R.string.genre_comedy, "Crime" to R.string.genre_crime,
    "Documentary" to R.string.genre_documentary, "Drama" to R.string.genre_drama, "Family" to R.string.genre_family,
    "Fantasy" to R.string.genre_fantasy, "History" to R.string.genre_history, "Horror" to R.string.genre_horror,
    "Mystery" to R.string.genre_mystery, "Romance" to R.string.genre_romance, "Sci-Fi" to R.string.genre_scifi,
    "Sport" to R.string.genre_sport, "Thriller" to R.string.genre_thriller, "War" to R.string.genre_war,
    "Western" to R.string.genre_western, "Reality-TV" to R.string.genre_reality, "Talk-Show" to R.string.genre_talk,
    "Game-Show" to R.string.genre_game,
)
