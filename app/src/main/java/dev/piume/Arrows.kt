package dev.piume

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * The round arrow that says there's more that way, like Spotlight's: a disc in [disc] with a chevron in [ink],
 * pointing [dir] (LEFT, RIGHT, UP or DOWN). Starts hidden (alpha 0).
 */
internal class ChevronDrawable(private val dir: Int, private val disc: Int, private val ink: Int, private val line: Float) : Drawable() {
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = line
    }
    private var shown = 0

    override fun draw(canvas: Canvas) {
        if (shown == 0) return
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val s = b.width().toFloat()
        discPaint.color = disc
        discPaint.alpha = (disc ushr 24) * shown / 255
        inkPaint.color = ink
        inkPaint.alpha = (ink ushr 24) * shown / 255
        canvas.drawCircle(cx, cy, s / 2, discPaint)
        canvas.save()
        canvas.rotate(when (dir) { LEFT -> 180f; UP -> 270f; DOWN -> 90f; else -> 0f }, cx, cy)
        canvas.drawPath(Path().apply {
            moveTo(cx - s * 0.08f, cy - s * 0.2f)
            lineTo(cx + s * 0.12f, cy)
            lineTo(cx - s * 0.08f, cy + s * 0.2f)
        }, inkPaint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        if (alpha == shown) return
        shown = alpha
        invalidateSelf()
    }

    override fun getAlpha() = shown
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val LEFT = 0
        const val RIGHT = 1
        const val UP = 2
        const val DOWN = 3
    }
}

/** Where [v] sits in [host], in pixels, whatever lies between (scrolling, translation). */
internal fun offsetIn(v: View, host: View): IntArray {
    val a = IntArray(2).also { v.getLocationInWindow(it) }
    val h = IntArray(2).also { host.getLocationInWindow(it) }
    return intArrayOf(a[0] - h[0], a[1] - h[1])
}

/**
 * The panel's arrows: up at the top of its list while it's scrolled down, down at the bottom while there's more
 * below. Set up once; they follow every page shown in it.
 */
internal fun MainActivity.panelArrows() {
    val scroll = settingsList.parent as ScrollView
    if (scroll.getTag(R.id.settingsList) != null) return
    val panel = findViewById<ViewGroup>(R.id.settingsPanel)
    val disc = (fg and 0xFFFFFF) or 0x33000000
    val up = ChevronDrawable(ChevronDrawable.UP, disc, fg, dp(2).toFloat())
    val down = ChevronDrawable(ChevronDrawable.DOWN, disc, fg, dp(2).toFloat())
    scroll.setTag(R.id.settingsList, up)
    panel.overlay.add(up)
    panel.overlay.add(down)
    fun update() {
        val size = dp(28)
        val (x, y) = offsetIn(scroll, panel).let { it[0] to it[1] }
        val cx = x + scroll.width / 2
        up.setBounds(cx - size / 2, y + dp(4), cx + size / 2, y + dp(4) + size)
        down.setBounds(cx - size / 2, y + scroll.height - dp(4) - size, cx + size / 2, y + scroll.height - dp(4))
        up.alpha = if (scroll.canScrollVertically(-1)) 255 else 0
        down.alpha = if (scroll.canScrollVertically(1)) 255 else 0
    }
    scroll.setOnScrollChangeListener { _, _, _, _, _ -> update() }
    scroll.viewTreeObserver.addOnGlobalLayoutListener { update() }
}
