package link.mccoy88f.zephyr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Turns a photo into a wallpaper: upright (phones often store photos sideways with an orientation tag),
 * cropped to 16:9 around the centre, 1920×1080, mutable. [open] is called once per pass over the image.
 * Returns null if it isn't an image Android can decode.
 */
internal fun fitWallpaper(open: () -> InputStream): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val rotation = try {
        when (open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: IOException) {
        0
    }
    val (w, h) = if (rotation % 180 == 0) bounds.outWidth to bounds.outHeight else bounds.outHeight to bounds.outWidth
    // Decode at the smallest size whose 16:9 crop still covers the screen: a 12 MP photo in full is ~48 MB.
    val cropW = minOf(w, h * 16 / 9)
    var sample = 1
    while (cropW / (sample * 2) >= WALL_W) sample *= 2
    val decoded = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val upright = if (rotation == 0) decoded else
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            .also { decoded.recycle() }
    val cw = minOf(upright.width, upright.height * 16 / 9)
    val ch = cw * 9 / 16
    val left = (upright.width - cw) / 2
    val top = (upright.height - ch) / 2
    val out = Bitmap.createBitmap(WALL_W, WALL_H, Bitmap.Config.ARGB_8888)
    Canvas(out).drawBitmap(upright, Rect(left, top, left + cw, top + ch), Rect(0, 0, WALL_W, WALL_H), Paint(Paint.FILTER_BITMAP_FLAG))
    upright.recycle()
    return out
}

/** Saves a phone photo as a wallpaper file (~300-500 KB JPEG). Returns the file, or null if it isn't an image. */
internal fun importWallpaper(src: File, dir: File): File? {
    val out = fitWallpaper { src.inputStream() } ?: return null
    dir.mkdirs()
    val file = generateSequence(1) { it + 1 }.map { File(dir, "Photo $it.jpg") }.first { !it.exists() }
    file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    out.recycle()
    return file
}

private const val WALL_W = 1920
private const val WALL_H = 1080
