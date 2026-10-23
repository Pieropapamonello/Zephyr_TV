package dev.piume

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The rows under Spotlight: apps, then a row per section of the focused app's recommendations. Only the apps and
 * the first section count towards where it sits, so the apps row stays put however many sections the app has;
 * the rest (the last child) hang below, past the bottom edge, until focus goes down and the page slides up to
 * them. They stay inside its bounds (a negative bottom margin takes their height back), because views outside
 * their parent's bounds aren't reliably drawn.
 */
class BottomSection(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Children at their full height: the parent would squeeze the overflow.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val more = getChildAt(childCount - 1)
        val hanging = if (more.visibility == GONE) 0 else more.measuredHeight
        // Read by the parent after this measure, so its spacer only makes room for the rest.
        (layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = -hanging
    }
}
