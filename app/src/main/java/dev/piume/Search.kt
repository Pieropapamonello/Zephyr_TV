package dev.piume

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
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
 * code to type on a phone instead. Without a TMDB key there's nothing to search, so the TV's assistant opens.
 */
internal fun MainActivity.openSearch() {
    if (prefs.getString(K.TMDB_API_KEY, null).isNullOrBlank()) return assistant()
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
    val key = prefs.getString(K.TMDB_API_KEY, null) ?: return
    spotlightIo.execute {
        val found = TmdbClient.search(key, query, tmdbLang())
        // Each result's landscape art with its title on it, all at once (one TMDB call each) before showing them.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val art = try {
            pool.invokeAll(found.map { item -> java.util.concurrent.Callable { item.id to TmdbClient.art(key, item, tmdbLang()) } })
                .mapNotNull { runCatching { it.get() }.getOrNull() }.toMap()
        } finally {
            pool.shutdown()
        }
        ui.post {
            if (found.isEmpty()) return@post Toast.makeText(this, getString(R.string.toast_no_results, query), Toast.LENGTH_SHORT).show()
            searchQuery = query
            searchResults = found
            searchArt = art
            watchApp = null // the tile's cards are new even if it's focused already
            renderApps()
            (0 until appRow.childCount).map { appRow.getChildAt(it) }.firstOrNull { it.tag == SEARCH_PKG }?.requestFocus()
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_search_results, found.size, found.size, query), Toast.LENGTH_LONG).show()
        }
    }
}

/** The results' cards under the apps row: landscape art with the title on it (see TmdbClient.art), and what it is. */
internal fun MainActivity.showSearchResults() {
    val query = searchQuery ?: return
    watchApp = SEARCH_PKG
    showSections(listOf(Section("search", query, getString(R.string.search_results_for, query), searchResults.map { item ->
        val kind = getString(if (item.mediaType == "tv") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
        val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
        val art = searchArt[item.id]
        Prog(item.title, listOfNotNull(kind, year).joinToString(" · "), 0, art?.backdrop ?: TmdbClient.cardUrl(item.backdropPath), null, null,
            tmdb = item, logo = art?.logo)
    })))
}

/** Closes the search: its tile and cards go, focus goes back to Continue Watching. False when none is open. */
internal fun MainActivity.endSearch(): Boolean {
    if (searchQuery == null) return false
    val onIt = currentFocus?.let { it.tag == SEARCH_PKG || (it.getTag(R.id.watchRow) as? Prog)?.tmdb != null } ?: true
    searchQuery = null
    searchResults = emptyList()
    searchArt = emptyMap()
    if (watchApp == SEARCH_PKG) watchApp = null
    renderApps()
    if (onIt) appRow.getChildAt(0)?.requestFocus()
    return true
}

/**
 * A result's page in the side panel: its card art on top, what Spotlight would say about it (rating, kind, year,
 * genre, the overview) under it, and a row per app to watch it in, each with the app's icon: the YouTube trailer
 * and the services TMDB lists it on, as Spotlight's buttons.
 */
internal fun MainActivity.showTitle(item: TmdbItem, available: Set<String>? = null) {
    if (settingsScrim.visibility != View.VISIBLE) showPanel()
    val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }
    val rating = if (item.voteAverage > 0) "★ %.1f".format(item.voteAverage) else null
    val kind = getString(if (item.mediaType == "tv") R.string.tmdb_badge_tv else R.string.tmdb_badge_movie)
    val art = searchArt[item.id]
    val picture = FrameLayout(this).apply {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
        }
        setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt())
    }
    val panelWidth = dp(380 - 28 - 48) // the panel's width inside its padding (activity_main.xml)
    val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    picture.addView(img, MATCH_PARENT, MATCH_PARENT)
    loadPoster(img, art?.backdrop ?: TmdbClient.cardUrl(item.backdropPath))
    art?.logo?.let { addTitleLogo(picture, it, panelWidth, panelWidth * 9 / 16) }
    val top = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(12))
        addView(picture, LinearLayout.LayoutParams(MATCH_PARENT, panelWidth * 9 / 16).apply { bottomMargin = dp(12) })
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
