package link.mccoy88f.zephyr

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// The page of a film, series, season, episode or channel as a whole page, not the side panel: its art over the
// whole screen, shaded on the left, and the page (logo or title, what it is, the rows) in a column on that side.
// It is the panel itself, made as wide as the screen while the page shows: its rows, focus, Back and the way back
// from the player stay as they are. Functions of MainActivity, kept here by topic; their state lives in MainActivity.

/** The art behind the page on show, and what it shows: a page drawn again with the same art keeps it, unloaded. */
private var detailArt: FrameLayout? = null
private var detailKey: String? = null
private var detailHeld = false
private var detailLogo: Pair<String, android.graphics.Bitmap>? = null // the page's logo, by its key: drawn again, at once

/** How the panel was before the page took it over, given back when the page goes. */
private class PanelWas(val width: Int, val gravity: Int, val padding: IntArray, val background: Drawable?, val titleSize: Float)

/**
 * The page just shown (see showPage) as a whole page: [paint] draws its art into a frame as big as the screen
 * ([key] names that art: the same key keeps it); [logo] stands for the page's title when it loads.
 */
internal fun MainActivity.detailPage(key: String, logo: List<String>, paint: (FrameLayout, Int, Int) -> Unit) {
    val scrim = settingsScrim as FrameLayout
    val panel = findViewById<LinearLayout>(R.id.settingsPanel)
    val title = findViewById<TextView>(R.id.settingsTitle)
    val w = scrim.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    val h = scrim.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    // The art: kept when it's the same (the page drawn again as its streams or names come in), else drawn anew.
    detailHeld = true
    if (detailKey != key || detailArt?.parent == null) {
        detailArt?.let { scrim.removeView(it) }
        val art = FrameLayout(this).apply { setBackgroundColor(artGround()) }
        paint(art, w, h)
        // Shaded where the page is, so its text reads over any picture; the right side stays the picture.
        art.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, detailShade()) }, MATCH_PARENT, MATCH_PARENT)
        art.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(detailShade()[0], 0))
        }, FrameLayout.LayoutParams(MATCH_PARENT, h / 3, Gravity.BOTTOM))
        scrim.addView(art, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        detailArt = art
        detailKey = key
    }

    // The panel, as wide as the screen, its page in a column on the left: about half of a wide screen.
    val lp = panel.layoutParams as FrameLayout.LayoutParams
    val was = PanelWas(lp.width, lp.gravity, intArrayOf(panel.paddingStart, panel.paddingTop, panel.paddingEnd, panel.paddingBottom),
        panel.background, title.textSize)
    val wide = w > dp(720)
    lp.width = MATCH_PARENT
    lp.gravity = Gravity.START
    panel.layoutParams = lp
    panel.setPadding(if (wide) dp(56) else dp(20), dp(36), if (wide) w * 48 / 100 else dp(20), dp(24))
    panel.background = null
    title.textSize = 30f
    panelButtonsAt(w)

    // The logo over the column, for the title, once it loads; the title stays written until then (and without one).
    val logoView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_START
        adjustViewBounds = true
        visibility = View.GONE
    }
    panel.addView(logoView, 0, LinearLayout.LayoutParams(WRAP_CONTENT, dp(96)).apply { marginStart = dp(14); bottomMargin = dp(8) })
    fun showLogo(bmp: android.graphics.Bitmap) {
        logoView.setImageBitmap(bmp)
        logoView.visibility = View.VISIBLE
        title.visibility = View.GONE
    }
    val had = detailLogo?.takeIf { it.first == key }?.second
    if (had != null) showLogo(had)
    else if (logo.isNotEmpty()) posterIo.execute {
        val bmp = logo.firstNotNullOfOrNull { runCatching { art(it, alpha = true) }.getOrNull() } ?: return@execute
        ui.post {
            detailLogo = key to bmp
            if (logoView.parent != null) showLogo(bmp) // else the page went meanwhile
        }
    }

    val own = pageExit
    pageExit = {
        own?.invoke()
        panel.removeView(logoView)
        title.visibility = View.VISIBLE
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, was.titleSize)
        lp.width = was.width
        lp.gravity = was.gravity
        panel.layoutParams = lp
        panel.setPadding(was.padding[0], was.padding[1], was.padding[2], was.padding[3])
        panel.background = was.background
        panelButtonsAt(null)
        // The art goes unless the next page takes it again (a page drawn again, in the same round).
        detailHeld = false
        ui.post {
            if (detailHeld) return@post
            detailArt?.let { scrim.removeView(it) }
            detailArt = null
            detailKey = null
        }
    }
}

/**
 * The first of [uris] (backdrops) that loads, as sharp as the screen [w] wide, over what [frame] shows already (the
 * card's picture, at once): the cards' pictures are decoded small. Under the page's shade. Network: off the UI thread.
 */
internal fun MainActivity.sharpBackdrop(frame: FrameLayout, w: Int, uris: List<String>) {
    if (uris.isEmpty()) return
    posterIo.execute {
        val bmp = uris.firstNotNullOfOrNull { uri ->
            runCatching {
                val file = if (uri.startsWith("http")) Images.file(uri) else return@runCatching null // the same file the card was drawn from
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w) sample *= 2
                android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                })
            }.getOrNull()
        } ?: return@execute
        ui.post {
            if (frame.parent == null) return@post // the page went meanwhile
            frame.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }, 1.coerceAtMost(frame.childCount), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }
}

/** The shade over the art, from the page's side: the theme's ground, nearly solid, to clear. */
private fun MainActivity.detailShade(): IntArray {
    val ground = if (glass || dark) 0x000000 else 0xF7F8FA
    return intArrayOf(0xF2000000.toInt() or ground, 0xB3000000.toInt() or ground, 0x14000000 or ground)
}
