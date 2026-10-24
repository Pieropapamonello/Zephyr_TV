package dev.piume

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.IOException

// Searching TMDB from the top bar: typed with the remote, dictated, or typed on a phone; results show in Spotlight.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/**
 * The search page: a box to type in with the remote, dictation, the TV's own assistant, and a QR code to type on a
 * phone instead. Without a TMDB key there's nothing to search, so the TV's assistant opens straight away.
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
        page(getString(R.string.search_dictate)) { closeLauncherSettings(); dictate() },
        page(getString(R.string.search_assistant)) { closeLauncherSettings(); assistant() },
    )
    showPage(getString(R.string.search_title), rows, top = top) { closeLauncherSettings() }
    settingsList.getChildAt(1).nextFocusUpId = input.id // showPage pins Up on the first row to itself; here it goes to the box
    input.requestFocus()
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
 * Spotlight on TMDB's films and series for [query] (said to the mic), in place of its own titles: backdrop,
 * details and the same buttons (trailer, the services that have it). Back puts its titles back (endSearch).
 */
internal fun MainActivity.searchTmdb(query: String) {
    val key = prefs.getString(K.TMDB_API_KEY, null) ?: return
    spotlightIo.execute {
        val found = TmdbClient.search(key, query, tmdbLang())
        ui.post {
            if (found.isEmpty()) return@post Toast.makeText(this, getString(R.string.toast_no_results, query), Toast.LENGTH_SHORT).show()
            if (searchQuery == null) searchStash = tmdbTrending to tmdbIndex
            searchQuery = query
            tmdbTrending = found
            tmdbIndex = 0
            ui.removeCallbacks(spotlightTick)
            renderSpotlight()
            loadWallpaper()
            spotlightInfoBox.requestFocus()
            Toast.makeText(this, resources.getQuantityString(R.plurals.toast_search_results, found.size, found.size, query), Toast.LENGTH_LONG).show()
        }
    }
}

/** Closes a search: Spotlight's own titles come back where they were. False when there's no search open. */
internal fun MainActivity.endSearch(): Boolean {
    if (searchQuery == null) return false
    searchQuery = null
    searchStash?.let { (list, at) ->
        tmdbTrending = list
        tmdbIndex = at
    }
    searchStash = null
    val inSpotlight = isDescendantOf(currentFocus, spotlightContainer)
    renderSpotlight()
    loadWallpaper()
    if (inSpotlight && spotlightContainer.visibility != View.VISIBLE) (lastApp?.takeIf { it.isAttachedToWindow } ?: appRow.getChildAt(0))?.requestFocus()
    return true
}
