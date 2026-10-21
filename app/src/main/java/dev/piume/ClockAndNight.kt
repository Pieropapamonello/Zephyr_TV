package dev.piume

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextClock
import java.util.Calendar

// The clock's format, and night mode: a time window when the home screen dims and Spotlight stops.

/** Whether [minute] (after midnight) falls in the window [from]..[to], which may run past midnight (23:00-07:00). */
internal fun inWindow(minute: Int, from: Int, to: Int) = when {
    from == to -> false
    from < to -> minute in from until to
    else -> minute >= from || minute < to
}

/** "auto" follows the TV's 24-hour setting; "24" and "12" override it. */
internal fun MainActivity.applyClock() {
    val clock = findViewById<TextClock>(R.id.clock)
    when (prefs.getString(K.CLOCK_24, "auto")) {
        "24" -> { clock.format12Hour = "HH:mm"; clock.format24Hour = "HH:mm" }
        "12" -> { clock.format12Hour = "h:mm"; clock.format24Hour = "h:mm" }
        else -> { clock.format12Hour = "h:mm"; clock.format24Hour = "HH:mm" }
    }
}

internal fun MainActivity.nightFrom() = prefs.getInt(K.NIGHT_FROM, 23 * 60)
internal fun MainActivity.nightTo() = prefs.getInt(K.NIGHT_TO, 7 * 60)

/** Night mode is on and it's within its hours now. */
internal fun MainActivity.isNight(): Boolean {
    if (!prefs.getBoolean(K.NIGHT, false)) return false
    val now = Calendar.getInstance()
    return inWindow(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE), nightFrom(), nightTo())
}

/**
 * Dims the home screen at night with a dark layer over the rows (the settings panel and All apps sit above it,
 * at full brightness), and stops Spotlight turning titles. Called on resume and every minute.
 */
internal fun MainActivity.applyNight() {
    val root = findViewById<FrameLayout>(R.id.root)
    val night = isNight()
    var dim = root.findViewWithTag<View>(NIGHT_TAG)
    if (night && dim == null) {
        dim = View(this).apply {
            tag = NIGHT_TAG
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Right above the home screen's content: under All apps and the settings panel.
        root.addView(dim, root.indexOfChild(findViewById(R.id.content)) + 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    dim?.animate()?.alpha(if (night) NIGHT_DIM else 0f)?.setDuration(600)?.start()
    // Only on the change: restarting the timer every minute would reset its countdown and wake an idle Spotlight.
    if (night == nightShown) return
    nightShown = night
    if (night) ui.removeCallbacks(spotlightTick) else restartSpotlightTick()
}

internal fun hourName(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

private const val NIGHT_TAG = "night"
private const val NIGHT_DIM = 0.55f
