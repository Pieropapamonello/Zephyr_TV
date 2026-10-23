package dev.piume

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
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

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidateOutline()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        invalidateOutline() // the items may have changed: whether there are more either side with them
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
    }
}
