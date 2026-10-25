package dev.piume

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Piume's player, for the Stremio add-ons' TV channels (see Addons). It gets the channels of the row the user
 * picked from, plays the one picked and zaps through the others: CH+/CH- (or Up/Down), the number keys, Last
 * channel. For each it asks the add-on for its streams and plays the first that works; Left/Right go to the
 * channel's other streams, and one that fails or stays stuck gives way to the next by itself. OK or Info shows
 * what's on: the add-on's guide, else the one set in Piume settings.
 *
 * Media3's ExoPlayer plays them, the one library Piume ships, for HLS and DASH as the TV's own player doesn't do
 * reliably. It runs in a process of its own (":player" in the manifest), so a stream or a decoder that hangs or
 * crashes never takes the home screen with it, and all its memory goes back when it closes.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    /** One channel of the list: what to ask the add-on for, its name, logos (the add-on's, then Piume's) and what's on. */
    private class Channel(val type: String, val id: String, val name: String, val logos: List<String>, val now: String?)

    private val net = Executors.newSingleThreadExecutor() // streams of the channel being tuned, and their checks
    private val ahead = Executors.newSingleThreadExecutor() // the next and previous channels' streams, and pages
    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var base = ""
    private var addon = ""
    private var channels: List<Channel> = emptyList()
    private var at = 0 // the channel playing (or being tuned)
    private var shown = 0 // the channel the banner shows: ahead of [at] while zapping
    private var previous = -1 // for Last channel
    private var streams: List<Stremio.Stream> = emptyList()
    private var tried = -1 // the stream playing, or being tried
    private var tune = 0 // counts tunings, so an answer for a channel left meanwhile is dropped
    private var typed = "" // number keys pressed so far
    private val fetched = ConcurrentHashMap<String, Pair<Long, List<Stremio.Stream>>>() // channel id to its streams, and when
    private val pages = ConcurrentHashMap<String, Stremio.Meta>()
    private val logos = ConcurrentHashMap<String, Bitmap>()
    private val guide by lazy {
        getSharedPreferences(K.FILE, MODE_PRIVATE).getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }?.let { Epg.saved(filesDir, it) }
    }

    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var banner: LinearLayout
    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var position: TextView
    private lateinit var clock: TextView
    private lateinit var onNow: TextView
    private lateinit var bar: ProgressBar
    private lateinit var more: TextView
    private lateinit var source: TextView

    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility = View.GONE }.start() }
    private val zapNow = Runnable { if (shown != at) play(shown) }
    private val goTyped = Runnable {
        typed.toIntOrNull()?.let { n -> if (n in 1..channels.size) zapTo(n - 1, delay = 0) }
        typed = ""
    }
    private val stuck = Runnable {
        warn("stream ${tried + 1} stuck, trying the next")
        next()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        base = intent.getStringExtra(BASE) ?: return finish()
        addon = intent.getStringExtra(ADDON).orEmpty()
        fun list(key: String) = intent.getStringArrayListExtra(key).orEmpty()
        val ids = list(IDS)
        channels = ids.indices.map { i ->
            Channel(list(TYPES).getOrNull(i)?.ifEmpty { null } ?: "tv", ids[i], list(NAMES).getOrNull(i).orEmpty(),
                listOfNotNull(list(LOGOS).getOrNull(i), list(FALLBACK_LOGOS).getOrNull(i)).filter { it.isNotEmpty() }.distinct(),
                list(NOWS).getOrNull(i)?.ifEmpty { null })
        }
        if (channels.isEmpty()) return finish()
        build()
        play(intent.getIntExtra(INDEX, 0).coerceIn(0, channels.size - 1))
    }

    // ---------- screen ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt())
    }

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        status = text(20f).apply { gravity = Gravity.CENTER }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // The banner: across the bottom, on a shade so it reads over any picture.
        logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x33FFFFFF) }
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        title = text(24f, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        position = text(14f).apply { alpha = 0.7f; setPadding(dp(12), 0, 0, 0) }
        clock = text(18f)
        onNow = text(16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x40FFFFFF) },
                ClipDrawable(GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.WHITE) }, Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        more = text(14f).apply { alpha = 0.85f; maxLines = 4; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }
        source = text(12f).apply { alpha = 0.6f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(position)
            addView(clock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(onNow)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
            addView(more)
            addView(source)
        }
        banner = LinearLayout(this).apply {
            gravity = Gravity.TOP
            setPadding(dp(48), dp(48), dp(48), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0xB3000000.toInt(), 0x00000000))
            addView(logo, LinearLayout.LayoutParams(dp(160), dp(90)).apply { marginEnd = dp(24) })
            addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /** Shows the banner for channel [i] (for a while, or until hidden with [stay]), filling in what's known so far. */
    private fun showBanner(i: Int, stay: Boolean = false) {
        val c = channels[i]
        title.text = c.name
        position.text = getString(R.string.player_channel_n, i + 1, channels.size)
        clock.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        fillGuide(c)
        source.text = if (i == at && tried >= 0 && streams.isNotEmpty())
            getString(R.string.player_stream, tried + 1, streams.size, streams[tried].title).let { if (streams.size > 1) "$it   ◀ ▶" else it }
        else getString(R.string.player_loading)
        showLogo(c)
        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.visibility = View.VISIBLE
        }
        banner.animate().cancel()
        banner.animate().alpha(1f).setDuration(150).start()
        ui.removeCallbacks(hideBanner)
        if (!stay) ui.postDelayed(hideBanner, BANNER_MS)
        // The add-on's page for the channel, for what's on and next: once, in the background.
        if (!pages.containsKey(c.id)) ahead.execute {
            val meta = runCatching { Stremio.meta(base, c.type, c.id) }.getOrNull() ?: return@execute
            pages[c.id] = meta
            ui.post { if (shown == i && banner.visibility == View.VISIBLE) fillGuide(c) }
        }
    }

    /** What's on: the add-on's (its page, else its catalog's line), else the guide set in Piume settings. */
    private fun fillGuide(c: Channel) {
        val meta = pages[c.id]
        val epg = if (meta?.description == null && meta?.now == null && c.now == null) guide?.let { Epg.nowAndNext(it, c.name) } else null
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        val progress: Int?
        when {
            meta?.description != null -> {
                onNow.text = meta.now ?: c.now ?: ""
                more.text = meta.description
                progress = Stremio.progress(meta.description)
            }
            epg != null -> {
                val (now, next) = epg
                onNow.text = "${now.title}  ·  ${time.format(java.util.Date(now.start))} – ${time.format(java.util.Date(now.stop))}"
                more.text = listOfNotNull(now.desc.takeIf { it.isNotBlank() },
                    next?.let { getString(R.string.channel_next, time.format(java.util.Date(it.start)), it.title) }).joinToString("\n")
                progress = Epg.progress(now)
            }
            else -> {
                onNow.text = meta?.now ?: c.now ?: ""
                more.text = ""
                progress = null
            }
        }
        onNow.visibility = if (onNow.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        more.visibility = if (more.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        bar.progress = progress ?: 0
    }

    /** The channel's logo in the banner: the add-on's, then Piume's own; downloaded once, kept while the player is open. */
    private fun showLogo(c: Channel) {
        val known = c.logos.firstNotNullOfOrNull { logos[it] }
        logo.setImageBitmap(known)
        logo.visibility = if (c.logos.isEmpty()) View.GONE else View.VISIBLE
        if (known != null || c.logos.isEmpty()) return
        ahead.execute {
            val bmp = c.logos.firstNotNullOfOrNull { uri ->
                runCatching {
                    val bytes = Http.open(uri).use { it.readBytes() }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { logos[uri] = it }
                }.getOrNull()
            } ?: return@execute
            ui.post { if (title.text == c.name) logo.setImageBitmap(bmp) }
        }
    }

    private fun say(text: String?) {
        status.text = text
        status.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---------- remote ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> zap(+1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> otherStream(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> otherStream(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE ->
                if (banner.visibility == View.VISIBLE && event.repeatCount == 0 && keyCode != KeyEvent.KEYCODE_INFO) hideBanner.run() else showBanner(shown)
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous in channels.indices) zapTo(previous, delay = 0)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> player?.let { p ->
                p.playWhenReady = if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) !p.playWhenReady else keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                typed = (typed + (keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
                title.text = typed
                position.text = ""
                showBanner(shown, stay = true)
                title.text = typed
                ui.removeCallbacks(goTyped)
                ui.postDelayed(goTyped, 1500)
            }
            KeyEvent.KEYCODE_BACK -> if (banner.visibility == View.VISIBLE) hideBanner.run() else finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** CH+ / CH-: the banner moves on at once; the channel is tuned once the keys rest (holding one runs through). */
    private fun zap(step: Int) {
        if (channels.size < 2) return showBanner(shown)
        zapTo(Math.floorMod(shown + step, channels.size), delay = ZAP_MS)
    }

    private fun zapTo(i: Int, delay: Long) {
        shown = i
        showBanner(i)
        ui.removeCallbacks(zapNow)
        ui.postDelayed(zapNow, delay)
    }

    /** Left / Right: the channel's other streams, as the add-on lists them. */
    private fun otherStream(step: Int) {
        if (streams.size < 2 || shown != this.at) return showBanner(shown)
        tried = Math.floorMod(tried + step, streams.size) - 1
        next()
    }

    // ---------- playing ----------

    /** Tunes channel [i]: its streams (fetched ahead if they're fresh), the first that works, then its neighbours'. */
    private fun play(i: Int) {
        if (i != at) previous = at
        at = i
        shown = i
        val c = channels[i]
        val tuning = ++tune
        streams = emptyList()
        tried = -1
        release()
        say(null)
        showBanner(i)
        net.execute {
            val list = streamsOf(c)
            ui.post {
                if (isFinishing || tuning != tune) return@post
                streams = list
                next()
                prefetch(i)
            }
        }
    }

    private fun streamsOf(c: Channel): List<Stremio.Stream> {
        fetched[c.id]?.takeIf { System.currentTimeMillis() - it.first < FRESH_MS }?.let { return it.second }
        return runCatching { Stremio.streams(base, c.type, c.id) }.onFailure { warn("streams failed: $it") }.getOrDefault(emptyList())
            .also { if (it.isNotEmpty()) fetched[c.id] = System.currentTimeMillis() to it }
    }

    /** The streams of the channels either side, so CH+ and CH- don't wait for the add-on (OMG checks each: ~1.5 s). */
    private fun prefetch(i: Int) {
        if (channels.size < 2) return
        for (j in listOf(Math.floorMod(i + 1, channels.size), Math.floorMod(i - 1, channels.size)).distinct()) {
            val c = channels[j]
            ahead.execute { streamsOf(c) }
        }
    }

    /** Tries the stream after the one that failed (the first, at the start); with none left, says so. */
    private fun next() {
        ui.removeCallbacks(stuck)
        tried++
        val s = streams.getOrNull(tried) ?: run {
            release()
            if (shown == at) showBanner(at, stay = true)
            return say(getString(R.string.player_no_streams))
        }
        if (tried > 0) say(getString(R.string.player_stream_n, tried + 1, streams.size))
        val tuning = tune
        val n = tried
        net.execute {
            val probe = Stremio.probe(s.url, s.headers)
            ui.post {
                if (isFinishing || tuning != tune || n != tried) return@post
                if (!probe.ok) next() else start(s, probe.format)
            }
        }
    }

    private fun start(s: Stremio.Stream, format: String?) {
        release()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // OMG's addresses go from https to the proxy's http, and back
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        s.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(stuck)
                        say(null)
                        remember(channels[at])
                        if (banner.visibility == View.VISIBLE && shown == at) showBanner(at)
                    }
                    // Waiting too long, at the start or later, is a stream that's stuck: the next one.
                    Player.STATE_BUFFERING -> {
                        ui.removeCallbacks(stuck)
                        ui.postDelayed(stuck, STUCK_MS)
                    }
                }
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
        ui.postDelayed(stuck, STUCK_MS)
    }

    private fun release() {
        ui.removeCallbacks(stuck)
        player?.release()
        player = null
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

    /** The channel that plays, for Continue Playing and the add-on's tile (see Addons.lastAddonChannel): a file both processes read. */
    private fun remember(c: Channel) = ahead.execute {
        runCatching {
            val o = JSONObject().put("addon", addon).put("type", c.type).put("id", c.id).put("name", c.name)
                .put("logo", c.logos.firstOrNull().orEmpty()).put("at", System.currentTimeMillis())
            val f = File(filesDir, LAST_FILE)
            val part = File(filesDir, "$LAST_FILE.part")
            part.writeText(o.toString())
            part.renameTo(f)
        }
    }

    // Live TV: nothing to come back to, so leaving the player closes it.
    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        release()
        net.shutdownNow()
        ahead.shutdown()
    }

    companion object {
        const val ADDON = "addon" // the add-on's manifest address
        const val BASE = "base" // the add-on's address, without /manifest.json
        const val INDEX = "index" // the channel picked, in the lists below
        const val TYPES = "types"
        const val IDS = "ids"
        const val NAMES = "names"
        const val NOWS = "nows" // what's on, as the add-on's catalog says
        const val LOGOS = "logos" // the add-on's
        const val FALLBACK_LOGOS = "fallback_logos" // Piume's own, by name (see ChannelLogos)
        const val LAST_FILE = "last_channel.json"
        private const val BANNER_MS = 5000L
        private const val ZAP_MS = 700L // the keys rest this long before the channel is tuned
        private const val STUCK_MS = 15_000L // buffering longer than this: the next stream
        private const val FRESH_MS = 90_000L // fetched streams are used this long (their addresses can expire)
    }
}
