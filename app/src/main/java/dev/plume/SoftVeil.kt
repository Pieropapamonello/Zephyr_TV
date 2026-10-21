package dev.plume

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * A panel of [color] whose edges fade out instead of ending in a hard line, so it melts into whatever is behind it.
 * Top, bottom and left fade over [feather] px; the right over [tail] px, longer, where the text runs out.
 * Drawn as a horizontal gradient masked by a vertical one in an offscreen layer: two gradients of the same kind
 * can't be combined in one ComposeShader with hardware drawing before Android 9.
 */
internal class SoftVeil(private val feather: Float, private val tail: Float) : Drawable() {
    var color = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            shaders = null
            invalidateSelf()
        }

    private val fill = Paint()
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var shaders: Pair<Shader, Shader>? = null

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        shaders = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || Color.alpha(color) == 0) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val (across, down) = shaders ?: run {
            val inEnd = (feather / w).coerceIn(0f, 0.5f)
            val outStart = (1f - tail / w).coerceIn(inEnd, 1f)
            val edge = (feather / h).coerceIn(0f, 0.5f)
            // Eased, not linear: a straight ramp reads as a blurred box edge. Each fade stays faint for its first
            // half (a quarter of the colour by the midpoint), then rises.
            fun eased(c: Int) = intArrayOf(c and 0xFFFFFF, (c and 0xFFFFFF) or ((Color.alpha(c) / 4) shl 24), c)
            val across = eased(color).let { up -> up + up.reversedArray() }
            val ramp = eased(Color.BLACK)
            Pair<Shader, Shader>(
                LinearGradient(0f, 0f, w, 0f, across,
                    floatArrayOf(0f, inEnd / 2, inEnd, outStart, outStart + (1f - outStart) / 2, 1f), Shader.TileMode.CLAMP),
                LinearGradient(0f, 0f, 0f, h, ramp + ramp.reversedArray(),
                    floatArrayOf(0f, edge / 2, edge, 1f - edge, 1f - edge / 2, 1f), Shader.TileMode.CLAMP),
            ).also { shaders = it }
        }
        val layer = canvas.saveLayer(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), null)
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        fill.shader = across
        canvas.drawRect(0f, 0f, w, h, fill)
        mask.shader = down
        canvas.drawRect(0f, 0f, w, h, mask)
        canvas.restoreToCount(layer)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(filter: ColorFilter?) {
        fill.colorFilter = filter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
