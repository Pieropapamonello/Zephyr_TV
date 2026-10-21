package dev.piume

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import dev.piume.MainActivity.App
import dev.piume.MainActivity.Companion.CW_APPS_ROW
import dev.piume.MainActivity.Companion.GREYSCALE
import dev.piume.MainActivity.Companion.GRID_GAP
import dev.piume.MainActivity.Companion.LOGOS
import dev.piume.MainActivity.Companion.PER_APP
import dev.piume.MainActivity.Companion.TV_LISTINGS
import dev.piume.MainActivity.Companion.WRITE_EPG
import dev.piume.MainActivity.Prog

// The row under the apps: Continue Watching, an app's recommendations, their cards and posters.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

// ---------- continue watching (Android TV Watch Next) ----------

internal fun MainActivity.refreshWatchNext() {
    if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
        showWatchNext(emptyList())
        return
    }
    ui.post { watchTitle.text = getString(R.string.continue_watching) }
    io.execute {
        val list = try {
            queryWatchNext()
        } catch (e: Exception) {
            emptyList()
        }
        ui.post {
            watchTitle.text = getString(R.string.continue_watching)
            showWatchNext(list)
        }
    }
}

/**
 * Two sources, same columns:
 * 1. The system Watch Next list (Netflix etc.), newest engagement first.
 * 2. Any app's own home-screen channel named "Continue Watching". Stremio publishes one (with
 *    progress and an autoPlay resume link) instead of using Watch Next, so signing in to Stremio
 *    on the TV is all it takes. Channel rows carry no timestamps, so they use the time the app was
 *    last opened from Piume, keeping the app's own order among themselves.
 * Newest first across apps, at most [PER_APP] each, so one app with ten shows can't bury the others.
 */
internal fun MainActivity.queryWatchNext(): List<Prog> {
    val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!!
    val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
    val channels = continueChannels().flatMap { id ->
        readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden).map { (_, p) -> prefs.getLong("t_${p.pkg}", 0) to p }
    }
    val all = watchNext + channels
    publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
    val off = prefs.getStringSet(K.CW_OFF, emptySet())!!
    return newestPerApp(all.filter { it.second.pkg !in off }.distinctBy { it.second.intent ?: it.second.title }, PER_APP) { it.pkg }
        .take(20)
}

/**
 * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
 * actually publishing now. Built from installed apps each time, so new apps appear on their own.
 */
internal fun MainActivity.watchSources(): List<App> = apps.filter { a ->
    a.pkg in publishingPkgs || try {
        packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(WRITE_EPG) == true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}.sortedBy { it.label.lowercase() }

internal fun MainActivity.pickWatchSources() {
    val sources = watchSources()
    if (sources.isEmpty()) {
        Toast.makeText(this, getString(R.string.toast_no_cw_apps), Toast.LENGTH_SHORT).show()
        return
    }
    toggles(getString(R.string.setting_cw_apps), sources.map { it.label to it.pkg }, K.CW_OFF, getString(R.string.state_on), getString(R.string.state_off)) {
        watchKey = null
        refreshWatchNext()
        renderSettings(CW_APPS_ROW)
    }
}

internal fun MainActivity.continueChannels(): List<Long> {
    val ids = ArrayList<Long>()
    contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
        val name = c.getColumnIndex("display_name")
        val pkg = c.getColumnIndex("package_name")
        val id = c.getColumnIndex("_id")
        while (c.moveToNext()) {
            if (c.getString(name).equals("Continue Watching", ignoreCase = true) && c.getString(pkg) != packageName) {
                ids += c.getLong(id)
            }
        }
    }
    return ids
}

/** Pairs of (last engagement time, item); time is 0 for channel rows, which don't have it. */
internal fun MainActivity.readPrograms(uri: Uri, hidden: Set<String>): List<Pair<Long, Prog>> {
    val out = ArrayList<Pair<Long, Prog>>()
    contentResolver.query(uri, null, null, null, null)?.use { c ->
        fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
        while (c.moveToNext()) {
            if (str("browsable") == "0") continue
            val title = str("title") ?: continue
            if (hideKey(str("package_name"), title) in hidden) continue
            val season = str("season_display_number")
            val episode = str("episode_display_number")
            val duration = num("duration_millis")
            val position = num("last_playback_position_millis")
            val sub = listOfNotNull(
                if (season != null && episode != null) "S$season E$episode" else null,
                // Under a second in means "not really started": no "time left", no bar.
                if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
            ).joinToString(" - ")
            val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
            val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), str("package_name"), num("weight"))
            out += num("last_engagement_time_utc_millis") to prog
        }
    }
    return out
}

internal fun MainActivity.hideKey(pkg: String?, title: String) = "$pkg/$title"

/** We can't delete another app's Watch Next row, so hiding is a local filter. */
internal fun MainActivity.hideWatch(p: Prog) {
    val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!! + hideKey(p.pkg, p.title)
    prefs.edit().putStringSet(K.HIDDEN_WATCH, hidden).apply()
    Toast.makeText(this, getString(R.string.toast_hidden_from_cw), Toast.LENGTH_SHORT).show()
    refreshWatchNext()
}

internal fun MainActivity.queryAppChannels(pkg: String?) {
    if (pkg == null || checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
        ui.post { watchTitle.text = getString(R.string.continue_watching) }
        refreshWatchNext()
        return
    }
    io.execute {
        val hidden = prefs.getStringSet(K.HIDDEN_WATCH, emptySet())!!
        val appChannels = ArrayList<Pair<Long, String>>() // (id, displayName)
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex("display_name")
            val pkgIdx = c.getColumnIndex("package_name")
            val idIdx = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                if (c.getString(pkgIdx) == pkg) {
                    val dName = c.getString(nameIdx) ?: ""
                    appChannels += c.getLong(idIdx) to dName
                }
            }
        }
        // Fetch preview programs from all channels for this app
        val programs = appChannels.flatMap { (channelId, _) ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(channelId), hidden)
        }.map { it.second }.distinctBy { it.intent ?: it.title }.let(::inChannelOrder)
        if (programs.isEmpty()) { // an app with nothing to recommend shows nothing under the row
            ui.post { if (tmdbLastFocusApp == pkg) hideWatchRow() }
            return@execute
        }
        val appLabel = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        ui.post {
            watchTitle.text = getString(R.string.tmdb_app_channels, appLabel)
            showWatchNext(programs)
        }
    }
}

/**
 * A TV app's recommendations are usually its channels, but the database returns them in no useful order (the
 * order they were last written). Sorted by channel number: the channel each card opens (its intent points at
 * content://android.media.tv/channel/<id>), else the channel with the card's name, else a number the title
 * starts with ("1 Rai 1"). Cards without a number follow, by the weight the app gave them. Runs on the io thread.
 */
internal fun MainActivity.inChannelOrder(programs: List<Prog>): List<Prog> {
    val byId = HashMap<Long, String>()
    val byName = HashMap<String, String>()
    contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "display_number", "display_name", "type"), null, null, null)?.use { c ->
        while (c.moveToNext()) {
            if (c.getString(3) == TvContract.Channels.TYPE_PREVIEW) continue // home-screen rows, not tuner channels
            val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
            byId[c.getLong(0)] = number
            c.getString(2)?.let { byName[it.trim().lowercase()] = number }
        }
    }
    fun number(p: Prog): String? {
        val channel = p.intent?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME).data }.getOrNull() }
            ?.takeIf { it.authority == TvContract.AUTHORITY && it.pathSegments.firstOrNull() == "channel" }
            ?.lastPathSegment?.toLongOrNull()
        return channel?.let(byId::get) ?: byName[p.title.trim().lowercase()]
            ?: Regex("""^\s*(\d+(?:[.-]\d+)?)\b""").find(p.title)?.groupValues?.get(1)
    }
    return programs.sortedWith(compareBy<Prog>({ channelKey(number(it)) }).thenByDescending { it.weight })
}

/**
 * Hides the row under the apps for an app with no recommendations. The old cards stay (invisible) so the row
 * keeps its height and the apps row doesn't jump as focus moves along it; the next list rebuilds them.
 */
internal fun MainActivity.hideWatchRow() {
    watchKey = null
    if (watchScroll.visibility == View.GONE) return
    watchTitle.visibility = View.INVISIBLE
    watchScroll.visibility = View.INVISIBLE
}

internal fun MainActivity.sourceIcon(pkg: String?): Drawable? {
    if (pkg == null) return null
    LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(fg) } }
    return try {
        packageManager.getApplicationIcon(pkg).mutate().apply { colorFilter = GREYSCALE }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

internal fun MainActivity.timeLeft(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) getString(R.string.time_left_hr_min, min / 60, min % 60) else getString(R.string.time_left_min, min)
}

internal fun MainActivity.showWatchNext(list: List<Prog>) {
    val key = list.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" }
    if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
        retryPosters()
        return
    }
    watchKey = key
    val focusedAt = watchRow.indexOfChild(currentFocus?.parent as? View)
    watchRow.removeAllViews()
    val visible = if (list.isEmpty()) View.GONE else View.VISIBLE
    watchTitle.visibility = visible
    watchScroll.visibility = visible
    // ~16 ms per card here and only four show at once: build them four per frame, not all 20 in one.
    fun addCards(from: Int) {
        if (watchKey != key) return // a newer list replaced this one
        list.subList(from, minOf(from + 4, list.size)).forEach { watchRow.addView(watchCard(it, watchRow.childCount > 0)) }
        if (from + 4 < list.size) ui.post { addCards(from + 4) }
    }
    addCards(0)
    if (focusedAt >= 0) {
        ((watchRow.getChildAt(focusedAt.coerceAtMost(watchRow.childCount - 1)) as? LinearLayout)?.getChildAt(0)
            ?: appRow.getChildAt(0))?.requestFocus()
    }
}

/**
 * Four posters across, each two apps-row columns wide. Progress sits on the artwork's bottom edge;
 * title and details (source, episode, time left) go underneath.
 */
internal fun MainActivity.watchCard(p: Prog, gapBefore: Boolean): View {
    val gap = dp(GRID_GAP)
    val w = (resources.displayMetrics.widthPixels - dp(96) - gap * 3) / 4
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (gapBefore) marginStart = gap
        }
    }
    val img = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt())
    }
    // Long titles scroll while their poster is focused instead of staying cut off.
    val title = label(p.title, 14f).apply {
        setPadding(0, dp(6), 0, 0)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
    }
    val frame = FrameLayout(this)
    style(frame, dp(8), card = true, lift = 1.1f) { focused ->
        title.isSelected = focused
        if (focused) {
            lastCard = frame
            showRowLabel(frame, false)
        }
    }
    frame.contentDescription = p.title
    frame.addView(img, MATCH_PARENT, MATCH_PARENT)
    frame.setOnClickListener { open(p) }
    frame.setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
    // Standard 16:9 aspect ratio prevents cutting off channel numbers and provider badges
    val cardHeight = w * 9 / 16
    col.addView(frame, LinearLayout.LayoutParams(w, cardHeight))
    // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
    col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = p.progress
        progressDrawable = progressBarDrawable()
        visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
    }, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(6) })
    col.addView(title)
    val icon = sourceIcon(p.pkg)
    if (p.sub.isNotEmpty() || icon != null) {
        col.addView(label(p.sub, 12f).apply {
            alpha = 0.8f
            setPadding(0, dp(2), 0, 0)
            icon?.let {
                it.setBounds(0, 0, dp(12), dp(12))
                setCompoundDrawablesRelative(it, null, null, null)
                compoundDrawablePadding = dp(6)
            }
        })
    }
    loadPoster(img, p.image)
    return col
}

internal fun MainActivity.progressBarDrawable(): Drawable {
    fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
    return LayerDrawable(arrayOf(
        bar(if (dark) 0x40FFFFFF else 0x26000000),
        ClipDrawable(bar(fg), Gravity.START, ClipDrawable.HORIZONTAL),
    )).apply {
        setId(0, android.R.id.background)
        setId(1, android.R.id.progress)
    }
}

internal fun MainActivity.open(p: Prog) {
    prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
    try {
        startActivity(programIntent(p) ?: throw IllegalArgumentException("unsafe or missing intent"))
    } catch (e: Exception) {
        p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
    }
}

/**
 * A card's intent, written by another app into the TV listings, made safe to start as Piume. One naming its
 * target may only name the app that published it (never Piume itself, never a third app); one that doesn't is
 * left to Android, which won't resolve it to anything not exported (a TV input's channel link is opened by the
 * TV's own app, not always the publisher). Either way it loses URI permission grants and any selector that
 * would swap in a different target. Null when it names a wrong target or doesn't parse.
 */
internal fun MainActivity.programIntent(p: Prog): Intent? {
    val intent = runCatching { Intent.parseUri(p.intent ?: return null, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
    val target = intent.component?.packageName ?: intent.`package`
    if (target == packageName || (p.pkg != null && target != null && target != p.pkg)) return null
    intent.selector = null
    intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal fun MainActivity.loadPoster(v: ImageView, uri: String?) {
    if (uri == null) return
    posters.get(uri)?.let { v.setImageBitmap(it); fitPoster(v, it); return }
    v.tag = uri
    if (!loading.add(uri)) return // already on its way
    posterIo.execute {
        val bmp = try {
            decode(uri)
        } catch (e: Exception) {
            warn("poster failed: $uri ($e)") // retried by retryPosters()
            null
        } finally {
            loading.remove(uri)
        } ?: return@execute
        posters.put(uri, bmp)
        ui.post {
            if (v.tag == uri) {
                v.setImageBitmap(bmp)
                fitPoster(v, bmp)
            }
        }
    }
}

/**
 * Film and show art fills the card (cropped to 16:9). Art that isn't 16:9, or that sits on a plain background
 * (a TV app's channel cards: logo and name on a flat colour), is shown whole instead, inset from the edges on
 * its own background colour, so the name in it doesn't run into the card's border.
 */
internal fun MainActivity.fitPoster(v: ImageView, bmp: Bitmap) {
    val ratio = bmp.width.toFloat() / bmp.height
    // Plain background: the corners and the middle of each edge all the same colour (a film still rarely is).
    val r = bmp.width - 1
    val b = bmp.height - 1
    val edge = listOf(0 to 0, r / 2 to 0, r to 0, 0 to b / 2, r to b / 2, 0 to b, r / 2 to b, r to b).map { (x, y) -> bmp.getPixel(x, y) }
    fun near(c1: Int, c2: Int) = Math.abs(Color.red(c1) - Color.red(c2)) + Math.abs(Color.green(c1) - Color.green(c2)) + Math.abs(Color.blue(c1) - Color.blue(c2)) < 24
    val flat = edge.all { Color.alpha(it) == 255 && near(it, edge[0]) }
    if (Math.abs(ratio / (16f / 9f) - 1f) < 0.08f && !flat) {
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setPadding(0, 0, 0, 0)
        return
    }
    v.scaleType = ImageView.ScaleType.FIT_CENTER
    v.setPadding(dp(12), dp(10), dp(12), dp(10))
    if (flat) v.setBackgroundColor(edge[0])
}

/** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
internal fun MainActivity.retryPosters() {
    for (i in 0 until watchRow.childCount) {
        val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
        if (img.drawable == null) loadPoster(img, img.tag as? String)
    }
}

/** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
internal fun MainActivity.decode(uri: String): Bitmap? {
    val bytes = openStream(uri).use { it.readBytes() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= dp(220)) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}
