package link.mccoy88f.zephyr

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView

/**
 * A row that scrolls by whole items: when focus moves past an edge, the focused item lands flush with
 * that edge of the padded area, so the row always shows full tiles and never half-cut ones. The items
 * sit on a fixed grid that exactly fills the area, so aligning one edge aligns them all.
 */
class SnapScrollView(context: Context, attrs: AttributeSet?) : HorizontalScrollView(context, attrs) {
    init {
        // Clip to our own bounds, which reach a gap past the items so the next one stays hidden. The parent can't
        // do it: it leaves clipping off so other views can lift. On a side with no more items (the row's start or
        // end) there's nothing to hide, so the clip opens out there: the first and last item's focus lift and
        // shadow show whole, like every other item's, instead of cut off at the edge.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val open = resources.displayMetrics.density * OPEN_DP
                outline.setRect(Rect(
                    if (canScrollHorizontally(-1)) 0 else -open.toInt(),
                    -open.toInt(),
                    if (canScrollHorizontally(1)) width else width + open.toInt(),
                    height + open.toInt(),
                ))
            }
        }
        clipToOutline = true
        clipChildren = false // or the row inside clips the first tile's focus lift to its own edge
    }

    // Arrows in the page margins, like Spotlight's, while the row has focus and has more that way. Drawn on the
    // home rows' section (outside this view, which clips to its own bounds), so they sit in the margins.
    private var arrows: Pair<ChevronDrawable, ChevronDrawable>? = null
    private var host: ViewGroup? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val home = context as? MainActivity ?: return
        val h = generateSequence(parent as? ViewGroup) { it.parent as? ViewGroup }.firstOrNull { it.id == R.id.bottomSection } ?: return
        val disc = (home.ground and 0xFFFFFF) or 0x80000000.toInt()
        val line = resources.displayMetrics.density * 1.5f
        val pair = ChevronDrawable(ChevronDrawable.LEFT, disc, home.fg, line) to ChevronDrawable(ChevronDrawable.RIGHT, disc, home.fg, line)
        h.overlay.add(pair.first)
        h.overlay.add(pair.second)
        arrows = pair
        host = h
        // Any focus move, not just into or out of this row: a row losing focus to another isn't told otherwise.
        viewTreeObserver.addOnGlobalFocusChangeListener(focusMoved)
        updateArrows()
    }

    private val focusMoved = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> updateArrows() }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusMoved)
        arrows?.let { (l, r) -> host?.overlay?.apply { remove(l); remove(r) } }
        arrows = null
        host = null
        super.onDetachedFromWindow()
    }

    private fun updateArrows() {
        val (left, right) = arrows ?: return
        val h = host ?: return
        val dp = resources.displayMetrics.density
        val size = (ARROW_DP * dp).toInt()
        val at = offsetIn(this, h)
        // Level with the middle of the row's items (an app tile, a card's picture), not the row with its text.
        val item = generateSequence(getChildAt(0)) { (it as? ViewGroup)?.takeIf { g -> !g.isFocusable && g.childCount > 0 }?.getChildAt(0) }
            .firstOrNull { it.isFocusable }
        val cy = if (item != null) offsetIn(item, h)[1] + item.height / 2 else at[1] + paddingTop + (height - paddingTop - paddingBottom) / 2
        val start = at[0] + paddingLeft
        val end = at[0] + width - paddingRight
        // Centred in the page margin's half nearest the row, like Spotlight's.
        left.setBounds(start - (22 * dp).toInt() - size / 2, cy - size / 2, start - (22 * dp).toInt() + size / 2, cy + size / 2)
        right.setBounds(end + (22 * dp).toInt() - size / 2, cy - size / 2, end + (22 * dp).toInt() + size / 2, cy + size / 2)
        val on = hasFocus()
        left.alpha = if (on && canScrollHorizontally(-1)) 255 else 0
        right.alpha = if (on && canScrollHorizontally(1)) 255 else 0
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (isInTouchMode) (context as? MainActivity)?.cardsAlong(this, l) // a finger moves no focus to build them on
        invalidateOutline()
        updateArrows()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        invalidateOutline() // the items may have changed: whether there are more either side with them
        updateArrows()
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val left = scrollX + paddingLeft
        val right = scrollX + width - paddingRight
        return when {
            rect.left < left -> rect.left - left
            rect.right > right -> rect.right - right
            else -> 0
        }
    }

    private companion object {
        const val OPEN_DP = 48 // room for a lifted item's shadow past an open edge
        const val ARROW_DP = 20
    }
}
