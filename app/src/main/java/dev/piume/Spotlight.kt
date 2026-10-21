package dev.piume

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.piume.MainActivity.App
import dev.piume.MainActivity.Companion.DECELERATE
import dev.piume.MainActivity.Companion.HDMI
import dev.piume.MainActivity.Companion.SPOTLIGHT_PKGS
import dev.piume.MainActivity.Prog
import java.io.File

// TMDB Spotlight on the home screen: the title, its veil and arrows, the app buttons, cycling and backdrops.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.tmdbLang() = prefs.getString(K.TMDB_LANG, null) ?: TmdbClient.defaultLanguage()

internal fun MainActivity.isDescendantOf(child: View?, parent: View): Boolean {
    var v = child
    while (v != null) {
        if (v === parent) return true
        v = v.parent as? View
    }
    return false
}

internal fun MainActivity.setupSpotlight() {
    // Feathered rather than a rounded box, so the veil melts into the backdrop instead of drawing a hard frame. It is
    // on the text only, not across the whole row: the backdrop stays clear around it.
    spotlightText.background = SoftVeil(dp(28).toFloat(), dp(28).toFloat()).apply { color = spotlightVeil() }
    // The arrows get a small disc in the ground colour, so they read on any backdrop.
    for (arrow in arrayOf(spotlightPrev, spotlightNext)) {
        arrow.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((ground and 0xFFFFFF) or 0x80000000.toInt()) }
        arrow.setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    // Text stays inside the veil's solid part, clear of the 28dp feathered edges.
    spotlightText.setPadding(dp(34), dp(20), dp(34), dp(20))
    spotlightPrev.setOnClickListener { cycleSpotlight(-1) }
    spotlightNext.setOnClickListener { cycleSpotlight(1) }

    window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
        updateSpotlightFocusState(newFocus)
        keepInView(newFocus)
    }
}

/**
 * A light veil behind the Spotlight text even at rest: it sits over the backdrop's unveiled middle, where a dark
 * film still would swallow the light theme's dark text (and a bright one the dark theme's light text).
 */
// Light: just enough to lift the text off a busy backdrop. The text halo does the rest; focused, the veil is a
// little stronger for the 13sp overview.
internal fun MainActivity.spotlightVeil() = if (dark) 0x4D000000 else 0x4DF3F5F7

internal fun MainActivity.spotlightVeilFocused() = if (dark) 0x73000000 else 0x73F3F5F7

/**
 * The home screen doesn't scroll, but with Spotlight on top the rows under it can run past the bottom edge.
 * Focus down there slides the whole page up just enough to show the focused item with what's under it (a
 * card's title and details, a tile's name), and focus anywhere above slides it back.
 */
internal fun MainActivity.keepInView(v: View?) {
    val content = findViewById<View>(R.id.content)
    if (v == null || !isDescendantOf(v, content)) return
    val target = if (v.getTag(R.id.watchRow) is Prog) v.parent as View else v
    val under = if (v.parent === appRow) dp(28) else 0
    content.post {
        var shift = 0f
        if (isDescendantOf(v, bottomSection)) {
            val at = IntArray(2).also { target.getLocationInWindow(it) }
            // Where it will sit once the page and the rows stop moving (both may be mid-animation).
            val bottom = at[1] - content.translationY - bottomSection.translationY + target.height + under
            shift = (bottom - (findViewById<View>(R.id.root).height - dp(16))).coerceAtLeast(0f)
        }
        if (shift != -content.translationY) content.animate().translationY(-shift).setDuration(200).setInterpolator(DECELERATE).start()
    }
}

internal fun MainActivity.updateSpotlightFocusState(targetFocus: View?) {
    val inSpotlight = isDescendantOf(targetFocus, spotlightContainer)
    spotlightOverview.visibility = if (inSpotlight) View.VISIBLE else View.GONE
    spotlightActions.visibility = if (inSpotlight) View.VISIBLE else View.GONE
    for (arrow in arrayOf(spotlightPrev, spotlightNext)) arrow.animate().alpha(if (inSpotlight) 0.9f else 0.5f).setDuration(220).start()
    (spotlightText.background as? SoftVeil)?.color = if (inSpotlight) spotlightVeilFocused() else spotlightVeil()
    // Translate bottomSection downwards to give spotlight breathing room
    val targetTransY = if (inSpotlight) dp(65).toFloat() else 0f
    val targetAlpha = if (inSpotlight) 0.65f else 1f
    bottomSection.animate().translationY(targetTransY).alpha(targetAlpha).setDuration(220).start()
}

internal fun MainActivity.resolveAppByPkg(pkg: String): App? = try {
    val pm = packageManager
    val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
    val info = pm.getApplicationInfo(pkg, 0)
    App(pkg, info.loadLabel(pm).toString(), intent, info.loadIcon(pm))
} catch (_: Exception) {
    null
}

internal fun MainActivity.spotlightItem(): TmdbItem? = tmdbTrending.let { list -> if (list.isEmpty()) null else list[tmdbIndex.mod(list.size)] }

internal fun MainActivity.renderSpotlight() {
    val item = spotlightItem()
    if (currentWallpaper() != "tmdb:trending" || item == null) {
        spotlightContainer.visibility = View.GONE
        return
    }
    spotlightContainer.visibility = View.VISIBLE
    val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
    val rating = if (item.voteAverage > 0) "★ %.1f · ".format(item.voteAverage) else ""
    val typeBadge = if (item.mediaType == "tv") getString(R.string.tmdb_badge_tv) else getString(R.string.tmdb_badge_movie)
    val genre = item.genres.firstOrNull()?.let { " · $it" } ?: ""
    spotlightMeta.text = "$rating$typeBadge$year$genre"
    spotlightTitle.text = item.title
    spotlightOverview.text = item.overview
    val more = if (tmdbTrending.size > 1) View.VISIBLE else View.INVISIBLE
    spotlightPrev.visibility = more
    spotlightNext.visibility = more
    // The buttons search for whichever title shows when pressed, so they're only rebuilt when the apps change
    // (rebuilding them on every title would also drop the focus off them).
    if (spotlightApps == null) renderSpotlightActions()
    showProviders(item)
}

internal fun MainActivity.providersOn() = prefs.getBoolean(K.TMDB_PROVIDERS, true)

/**
 * Shows the buttons of the services that have [item] (TMDB's watch providers for this country) and hides the
 * other services' ones; aggregators, YouTube and unknown apps always stay. Known answers apply at once, others
 * once TMDB answers (until then, and offline, every button shows).
 */
internal fun MainActivity.showProviders(item: TmdbItem) {
    val key = prefs.getString(K.TMDB_API_KEY, null)
    if (!providersOn() || key == null) return applyProviders(null)
    val region = TmdbClient.region(tmdbLang())
    val known = TmdbClient.cachedProviders(item, region)
    applyProviders(known)
    if (known == null) spotlightIo.execute {
        val found = TmdbClient.providers(key, item, region) ?: return@execute
        ui.post { if (spotlightItem() == item) applyProviders(found) }
    }
}

internal fun MainActivity.applyProviders(available: Set<String>?) {
    var first = true
    for (i in 0 until spotlightActions.childCount) {
        val b = spotlightActions.getChildAt(i)
        val show = TmdbClient.offers(b.tag as? String ?: continue, available)
        b.visibility = if (show) View.VISIBLE else View.GONE
        // Gaps only between visible buttons, so the first one lines up with the text above.
        (b.layoutParams as LinearLayout.LayoutParams).marginStart = if (show && !first) dp(8) else 0
        if (show) first = false
    }
    spotlightActions.requestLayout()
    if (currentFocus?.parent === spotlightActions && currentFocus?.visibility != View.VISIBLE) {
        (spotlightAction(-1, 1) ?: spotlightInfoBox).requestFocus()
    }
}

/** The next visible Spotlight button after index [from] going [step] (±1), or null at the end. */
internal fun MainActivity.spotlightAction(from: Int, step: Int): View? {
    var i = from + step
    while (i in 0 until spotlightActions.childCount) {
        spotlightActions.getChildAt(i).takeIf { it.visibility == View.VISIBLE }?.let { return it }
        i += step
    }
    return null
}

/** Up to five search buttons: the known streaming apps first, then other installed apps, minus those turned off. */
internal fun MainActivity.renderSpotlightActions() {
    spotlightActions.removeAllViews()
    val offSet = prefs.getStringSet(K.TMDB_DEFAULT_APPS, emptySet()) ?: emptySet()
    val candidateApps = mutableListOf<App>()
    // The known services' apps join whenever installed: each shows only for titles TMDB lists on it.
    for (pkg in SPOTLIGHT_PKGS + TmdbClient.providerApps) {
        if (pkg in offSet) continue
        val app = apps.firstOrNull { it.pkg == pkg } ?: resolveAppByPkg(pkg)
        if (app != null && candidateApps.none { it.pkg == app.pkg }) {
            candidateApps.add(app)
        }
    }
    for (app in apps) {
        if (candidateApps.size >= 5) break
        if (app.pkg !in offSet && !app.pkg.startsWith(HDMI) && app.pkg != CW_PKG && candidateApps.none { it.pkg == app.pkg }) {
            candidateApps.add(app)
        }
    }
    // Five at most, not counting services' apps beyond that: those hide for titles they don't have.
    val targetApps = candidateApps.filterIndexed { i, a -> i < 5 || a.pkg in TmdbClient.providerApps }
    spotlightApps = targetApps

    for (a in targetApps) {
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(14), dp(7))
            val rest = if (dark) 0xCC1C1C22.toInt() else 0xCCDCE0E8.toInt() // opaque enough for any backdrop
            val btnBg = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(rest)
            }
            background = btnBg
            isFocusable = true
            isClickable = true
            tag = a.pkg
            val ic = ImageView(this@renderSpotlightActions).apply {
                setImageDrawable(a.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val txt = TextView(this@renderSpotlightActions).apply {
                text = if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else a.label
                setTextColor(fg)
                textSize = 12f
                setPadding(dp(6), 0, 0, 0)
            }
            addView(ic, dp(18), dp(18))
            addView(txt)
            setOnFocusChangeListener { _, focused ->
                btnBg.setColor(if (focused) fg else rest)
                txt.setTextColor(if (focused) onFg else fg)
                scaleX = if (focused) 1.08f else 1f
                scaleY = if (focused) 1.08f else 1f
            }
            setOnClickListener { openInApp(a) }
        }
        spotlightActions.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = if (spotlightActions.childCount > 0) dp(8) else 0
        })
    }
}

/**
 * Opens the Spotlight title in [a]: straight on its page where the app takes an id (Nuvio by TMDB id, Stremio by
 * IMDb id, looked up first), YouTube on the trailer TMDB lists (or a search for one), otherwise through the
 * app's search, otherwise just the app.
 */
internal fun MainActivity.openInApp(a: App) {
    val item = spotlightItem() ?: return launch(a)
    val lang = tmdbLang()
    fun start(ref: String?) = try {
        startActivity(TmdbClient.openIntent(this, a.pkg, item, ref, lang))
    } catch (e: Exception) {
        launch(a)
    }
    val key = prefs.getString(K.TMDB_API_KEY, null)
    if (key == null || !TmdbClient.needsLookup(a.pkg)) return start(null)
    spotlightIo.execute {
        val ref = TmdbClient.lookup(key, a.pkg, item, lang)
        ui.post { start(ref) }
    }
}

internal fun MainActivity.cycleSpotlight(direction: Int) {
    if (tmdbTrending.isEmpty()) return
    ui.removeCallbacks(spotlightTick) // restarted once the new backdrop is up
    tmdbIndex = (tmdbIndex + direction).mod(tmdbTrending.size)
    renderSpotlight()
    loadWallpaper()
}

/** Downloads the next and previous titles' backdrops ahead, so ◀ ▶ and the timer show them at once. */
internal fun MainActivity.prefetchBackdrops() {
    val list = tmdbTrending
    if (list.size < 2) return
    val urls = listOf(1, -1).mapNotNull { TmdbClient.backdropUrl(list[(tmdbIndex + it).mod(list.size)].backdropPath) }
        .filterNot { backdrops.has(it) }
    if (urls.isNotEmpty()) spotlightIo.execute {
        for (url in urls) try {
            backdrops.get(url) { openStream(it) }
        } catch (e: Exception) {
            warn("backdrop prefetch failed ($e)") // shown later from the network instead
        }
    }
}

internal fun MainActivity.spotlightEvery() = prefs.getInt(K.TMDB_CYCLE, 5) // seconds; 0 = only by hand

internal fun MainActivity.restartSpotlightTick() {
    ui.removeCallbacks(spotlightTick)
    val every = spotlightEvery()
    if (every > 0 && tmdbTrending.size > 1) ui.postDelayed(spotlightTick, every * 1000L)
}

/** Notes what each trending title's backdrop shows, so the TMDB screensaver can caption it without the network. */
internal fun MainActivity.saveBackdropTitles(list: List<TmdbItem>) {
    val lines = list.mapNotNull { item ->
        val url = TmdbClient.backdropUrl(item.backdropPath) ?: return@mapNotNull null
        val about = listOfNotNull(item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }, item.genres.firstOrNull()).joinToString(" · ")
        listOf(BackdropCache.nameFor(url), item.title, about).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }
    try {
        File(cacheDir, BACKDROP_TITLES).writeText(lines.joinToString("\n"))
    } catch (e: java.io.IOException) {
        warn("backdrop titles not saved ($e)")
    }
}

/** Downloads every trending title's backdrop ahead, for the TMDB screensaver. */
internal fun MainActivity.prefetchAllBackdrops() {
    val urls = tmdbTrending.mapNotNull { TmdbClient.backdropUrl(it.backdropPath) }.filterNot { backdrops.has(it) }
    if (urls.isNotEmpty()) spotlightIo.execute {
        for (url in urls) try {
            backdrops.get(url) { openStream(it) }
        } catch (e: Exception) {
            warn("backdrop prefetch failed ($e)")
        }
    }
}

