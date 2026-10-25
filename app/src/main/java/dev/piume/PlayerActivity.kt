package dev.piume

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.Executors

/**
 * Piume's player, for the Stremio add-ons' TV channels (see Addons): it asks the add-on for the channel's streams
 * and plays the first that works, trying the next when one fails. Media3's ExoPlayer plays them, the one library
 * Piume ships, for HLS and DASH as the TV's own player doesn't do reliably. It runs in a process of its own
 * (":player" in the manifest), so a stream or a decoder that hangs or crashes never takes the home screen with it,
 * and all its memory goes back when it closes.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private val net = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var streams: List<Stremio.Stream> = emptyList()
    private var tried = -1 // the stream playing, or being tried
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val base = intent.getStringExtra(BASE)
        val id = intent.getStringExtra(ID)
        val name = intent.getStringExtra(NAME).orEmpty()
        if (base == null || id == null) return finish()
        say("$name\n${getString(R.string.player_loading)}")
        net.execute {
            val list = runCatching { Stremio.streams(base, intent.getStringExtra(TYPE) ?: "tv", id) }
                .onFailure { warn("streams failed: $it") }.getOrDefault(emptyList())
            ui.post {
                if (isFinishing) return@post
                streams = list
                next()
            }
        }
    }

    /** Tries the stream after the one that failed (the first, at the start); with none left, says so. */
    private fun next() {
        tried++
        val s = streams.getOrNull(tried) ?: return say(getString(R.string.player_no_streams))
        if (tried > 0) say(getString(R.string.player_stream_n, tried + 1, streams.size))
        val at = tried
        net.execute {
            val probe = Stremio.probe(s.url, s.headers)
            ui.post {
                if (isFinishing || at != tried) return@post
                if (!probe.ok) next() else play(s, probe.format)
            }
        }
    }

    private fun play(s: Stremio.Stream, format: String?) {
        player?.release()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // OMG's addresses go from https to the proxy's http, and back
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        s.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) status.visibility = View.GONE
                if (state == Player.STATE_BUFFERING && status.visibility == View.GONE) say(getString(R.string.player_loading))
            }

            override fun onPlayerError(error: PlaybackException) {
                // A live stream fallen too far behind starts again from now; anything else, the next stream.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                warn("stream ${tried + 1} failed (${error.errorCodeName})")
                next()
            }

            override fun onVideoSizeChanged(size: VideoSize) = fit(size)
        })
        val item = MediaItem.Builder().setUri(s.url).apply {
            when (format) {
                "hls" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                "dash" -> setMimeType(MimeTypes.APPLICATION_MPD)
                "ts" -> setMimeType(MimeTypes.VIDEO_MP2T)
            }
        }.build()
        p.setMediaItem(item)
        p.playWhenReady = true
        p.prepare()
    }

    /** The picture as large as the screen allows, at its own shape (black bars rather than stretched). */
    private fun fit(size: VideoSize) {
        if (size.width == 0 || size.height == 0) return
        val root = surface.parent as View
        val ratio = size.width * size.pixelWidthHeightRatio / size.height
        val (w, h) = if (root.width.toFloat() / root.height > ratio) (root.height * ratio).toInt() to root.height
            else root.width to (root.width / ratio).toInt()
        surface.layoutParams = (surface.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }
    }

    private fun say(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    // Live TV: nothing to come back to, so leaving the player closes it.
    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        net.shutdownNow()
    }

    companion object {
        const val BASE = "base" // the add-on's address, without /manifest.json
        const val TYPE = "type"
        const val ID = "id"
        const val NAME = "name"
    }
}
