package link.mccoy88f.zephyr

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
import android.widget.ScrollView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Zephyr TV's player, for the Stremio add-ons' TV channels (see Addons). It gets the channels of the row the user
 * picked from, plays the one picked and zaps through the others: CH+/CH- (never Up/Down), the number keys, Last
 * channel. For each it asks the add-on for its streams and plays the first that works; Left/Right go to the
 * channel's other streams, and one that fails or stays stuck gives way to the next by itself (round the list, until
 * each was tried). OK or Info shows what's on, in short; OK again shows all of it (the add-on's guide, else the one
 * set in Zephyr TV settings). Up/Down show the banner, and with it up reach its buttons, as Left/Right do: Play/Pause, then Audio, Subtitles and Stream, each opening its list to pick from (holding OK, or Menu, opens audio and subtitles together). The audio and subtitles
 * picked are kept by language, for the next channels and the next time.
 *
 * Films and episodes (MODE "vod", see Titles) play the same way, their streams asked of every stream add-on at once
 * and the first that works played: Left/Right then go back and on 10 seconds (more while held), CH+/CH- to the
 * episode before and after, and the Stream button to another stream. It resumes where it was left (VodProgress),
 * and an episode that ends goes on to the next, on the same kind of stream when there's one (its bingeGroup).
 *
 * Media3's ExoPlayer plays them, the one library Zephyr TV ships, for HLS and DASH as the TV's own player doesn't do
 * reliably. It runs in a process of its own (":player" in the manifest), so a stream or a decoder that hangs or
 * crashes never takes the home screen with it, and all its memory goes back when it closes.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    /** One channel of the list: what to ask the add-on for, its name, logos (the add-on's, then Zephyr TV's) and what's on. */
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
    private var attempts = 0 // streams tried since the channel was tuned, or one was picked: all of them, and it's none
    private var expanded = false // the banner shows all of what's on, not just its first line
    private var tune = 0 // counts tunings, so an answer for a channel left meanwhile is dropped
    private var typed = "" // number keys pressed so far
    private var vod = false // films and episodes (see MODE): seeking, resuming, the next episode; else live channels
    private var adult = false // whether this content is from an adult add-on (no history or continue watching)
    private var adultSources: Set<String> = emptySet()
    private var sources: List<Pair<String, String>> = emptyList() // films' and episodes' stream add-ons: address and name, in order
    private var descs: List<String> = emptyList() // what each film or episode is about (OK on the banner shows it)
    private var pendingSeek = -1L // where the stream starts once it's ready: resumed, or where a failed one stopped
    private var group: String? = null // the bingeGroup of the stream playing: the next episode's like it first
    private var seeking = false // the banner came up for a seek: Left/Right go on seeking, not to its buttons
    private var ticks = 0
    private val pool by lazy { Executors.newFixedThreadPool(4) } // a film's streams, every add-on at once
    private val fetched = ConcurrentHashMap<String, Pair<Long, List<Stremio.Stream>>>() // channel id to its streams, and when
    private val pages = ConcurrentHashMap<String, Stremio.Meta>()
    private val done = HashSet<String>() // the films and episodes marked watched while the player is open
    private val userDir by lazy { intent.getStringExtra(USER_DIR)?.let(::File) ?: filesDir } // the user's: progress, watched, Trakt
    private var scrobbling = false // Trakt was told the film or episode playing plays
    private val logos = ConcurrentHashMap<String, Bitmap>()
    private val guide by lazy {
        getSharedPreferences(K.FILE, MODE_PRIVATE).getString(K.EPG_URL, null)?.takeIf { it.isNotBlank() }?.let { Epg.saved(filesDir, it) }
    }

    private class LogoBox(val frame: FrameLayout, val bg: ImageView, val fg: ImageView, val mark: ImageView)

    private lateinit var surface: SurfaceView
    private lateinit var blurBackdrop: ImageView
    private lateinit var blurBackdropShade: View
    private lateinit var status: TextView // under the channel's logo and name while it loads: which stream, or none works
    private lateinit var loading: LinearLayout // in the middle while a channel loads: its logo, name and the status
    private lateinit var loadLogoBox: LogoBox
    private lateinit var loadName: TextView
    private lateinit var loadSub: TextView // under the name, for an episode: its season, number and name
    private var loadFor = -1 // the channel (film, episode) the loading screen shows
    private lateinit var banner: LinearLayout
    private lateinit var logoBox: LogoBox
    private var titleLogo: Bitmap? = null
    private val blurLogos = ConcurrentHashMap<String, Bitmap>()
    private val roundArt = ConcurrentHashMap<String, Bitmap>() // a film's art with its corners rounded (see rounded)
    private lateinit var title: TextView
    private lateinit var position: TextView
    private lateinit var clock: TextView
    private lateinit var onNow: TextView
    private lateinit var bar: ProgressBar
    private lateinit var more: TextView
    private lateinit var source: TextView
    private lateinit var trackLine: TextView // in the banner: the audio track and subtitles playing
    private lateinit var subtitles: FrameLayout // over the picture, its size: the subtitles' cues
    private lateinit var tracksPanel: LinearLayout // Audio and subtitles, on the right
    private lateinit var tracksTitle: TextView
    private lateinit var tracksList: LinearLayout
    private lateinit var buttons: LinearLayout // in the banner, right of the stream and audio lines: icons for Pause, Audio, Subtitles, Stream
    private lateinit var desc: LinearLayout // in the banner: what's on, focused once OK brings it up (OK there: all of it)
    private lateinit var lines: LinearLayout // the stream and audio lines, the buttons as tall as both

    /** The audio and subtitle languages picked, for every channel: a file only this process writes (see pick). */
    private val choices by lazy { getSharedPreferences(CHOICES_FILE, MODE_PRIVATE) }

    /**
     * The home screen's preferences, read again whenever the file changed (it's written in the other process): the
     * Nuvio plugins on, and whether 18+ shows, as they are now.
     */
    @Suppress("DEPRECATION")
    private val prefs get() = getSharedPreferences(K.FILE, MODE_MULTI_PROCESS)

    /** One audio or subtitle track of the stream playing, as the panel lists it. */
    private class Track(val group: Tracks.Group, val index: Int, val name: String, val selected: Boolean)

    private val hideBanner = Runnable {
        expanded = false
        seeking = false
        unfocusBanner()
        banner.animate().alpha(0f).setDuration(250).withEndAction { banner.visibility = View.GONE }.start()
    }
    private val zapNow = Runnable { if (shown != at) play(shown) }
    private val goTyped = Runnable {
        typed.toIntOrNull()?.let { n -> if (n in 1..channels.size) zapTo(n - 1, delay = 0) }
        typed = ""
    }
    /** Once a second while a film plays: its time in the banner; every ten, where it got to (VodProgress). */
    private val tick = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (!vod) return
            if (player?.isPlaying == true) {
                if (banner.visibility == View.VISIBLE && shown == at) fillGuide(channels[at])
                if (++ticks % 10 == 0) saveProgress()
            }
            ui.postDelayed(this, 1000)
        }
    }
    private val stuck = Runnable {
        if (player?.playWhenReady == false) return@Runnable // paused: waiting is what it's asked to do
        warn("stream ${tried + 1} stuck, trying the next")
        next()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Nuvio.useAssets(applicationContext) // the modules Nuvio's plugins require(), in this process too
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vod = intent.getStringExtra(MODE) == MODE_VOD
        base = intent.getStringExtra(BASE) ?: if (vod) "" else return finish()
        addon = intent.getStringExtra(ADDON).orEmpty()
        adult = intent.getBooleanExtra(ADULT, false)
        fun list(key: String) = intent.getStringArrayListExtra(key).orEmpty()
        adultSources = list(ADULT_SOURCES).toSet()
        sources = list(SOURCES).zip(list(SOURCE_NAMES))
        descs = list(DESCS)
        if (vod && sources.isEmpty()) return finish()
        val ids = list(IDS)
        channels = ids.indices.map { i ->
            Channel(list(TYPES).getOrNull(i)?.ifEmpty { null } ?: "tv", ids[i], list(NAMES).getOrNull(i).orEmpty(),
                listOfNotNull(list(LOGOS).getOrNull(i), list(FALLBACK_LOGOS).getOrNull(i)).filter { it.isNotEmpty() }.distinct(),
                list(NOWS).getOrNull(i)?.ifEmpty { null })
        }
        if (channels.isEmpty()) return finish()
        // A stream picked by hand (see pickStream): the one the first episode or film plays, none looked for.
        intent.getStringExtra(CHOSEN)?.let { j -> runCatching { streamOf(JSONObject(j)) }.getOrNull() }?.let { s ->
            channels.getOrNull(intent.getIntExtra(INDEX, 0))?.let { fetched[it.id] = System.currentTimeMillis() to listOf(s) }
        }
        build()
        if (vod) markInto(logoBox)
        play(intent.getIntExtra(INDEX, 0).coerceIn(0, channels.size - 1))
        if (vod) ui.post(tick)
    }

    // ---------- screen ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** One line that scrolls round when it's longer than its room. */
    private fun TextView.scrolling() {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
    }

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
        blurBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        blurBackdropShade = View(this).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
        }
        root.addView(blurBackdrop, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(blurBackdropShade, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // While a channel loads: its logo and name in the middle, and under them the stream being tried, or that
        // none works.
        loadLogoBox = createLogoBox(dp(6), dp(6), 12)
        val loadSize = if (vod) LinearLayout.LayoutParams(dp(320), dp(180)) else LinearLayout.LayoutParams(dp(180), dp(240))
        loadName = text(28f, bold = true).apply { gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        loadSub = text(20f).apply { gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0) }
        status = text(18f).apply { gravity = Gravity.CENTER; alpha = 0.8f; setPadding(0, dp(10), 0, 0) }
        loading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(48), 0, dp(48), 0)
            addView(loadLogoBox.frame, loadSize.apply { bottomMargin = dp(16) })
            addView(loadName, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(loadSub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        root.addView(loading, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        subtitles = FrameLayout(this)
        root.addView(subtitles, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // The banner: across the bottom, on a shade so it reads over any picture.
        logoBox = createLogoBox(dp(8), dp(6), 10)
        title = text(24f, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        position = text(14f).apply { alpha = 0.7f; setPadding(dp(12), 0, 0, 0) }
        clock = text(18f)
        onNow = text(16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable = LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x40FFFFFF) },
                ClipDrawable(GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.WHITE) }, Gravity.START, ClipDrawable.HORIZONTAL),
            )).apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        more = text(14f).apply { alpha = 0.85f; maxLines = 14; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0) }
        // The stream's line and the audio and subtitles' under the bar: too long for it, they scroll (selected: they
        // scroll without focus, which stays on the buttons).
        source = text(12f).apply { alpha = 0.6f; scrolling() }
        trackLine = text(12f).apply { alpha = 0.6f; scrolling(); setPadding(0, dp(4), 0, 0) }
        buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        // What's on: framed while it has the focus, so it's clear OK there shows all of it.
        val descBg = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.TRANSPARENT) }
        desc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
            background = descBg
            addView(onNow)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
            addView(more)
            isFocusable = false
            isClickable = true
            setOnClickListener { toggleMore() }
            setOnFocusChangeListener { _, has ->
                descBg.setColor(if (has) 0x26FFFFFF else Color.TRANSPARENT)
                descBg.setStroke(if (has) dp(2) else 0, Color.WHITE)
            }
        }
        lines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(source)
            addView(trackLine)
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) buttons.post { sizeButtons() }
            }
        }
        val foot = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(lines, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(position)
            addView(clock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(24) })
        }
        // Nothing clips the buttons, which grow a little with the focus (see button).
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(head)
            // Its frame's padding out to the side, so its text lines up with the title.
            addView(desc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = -dp(12)
                marginEnd = -dp(12)
            })
            addView(foot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        banner = LinearLayout(this).apply {
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setPadding(dp(48), dp(48), dp(48), dp(32))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0xB3000000.toInt(), 0x00000000))
            addView(logoBox.frame, LinearLayout.LayoutParams(dp(160), dp(90)).apply { marginEnd = dp(24) })
            addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Audio and subtitles: a side panel over the picture, its rows picked with Up/Down and OK.
        tracksList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tracksPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(40), dp(28), dp(28))
            setBackgroundColor(0xF0101014.toInt())
            tracksTitle = text(22f, bold = true).apply { setPadding(dp(14), 0, 0, dp(16)) }
            addView(tracksTitle)
            addView(ScrollView(this@PlayerActivity).apply { isVerticalScrollBarEnabled = false; addView(tracksList) })
            visibility = View.GONE
        }
        root.addView(tracksPanel, FrameLayout.LayoutParams(dp(380), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /**
     * Shows the banner for channel [i] (for a while, or until hidden with [stay]), filling in what's known so far: in
     * short, or all of what's on once OK [expanded] it.
     */
    private fun showBanner(i: Int, stay: Boolean = false) {
        val c = channels[i]
        title.text = c.name
        position.text = if (vod && channels.size < 2) "" else getString(R.string.player_channel_n, i + 1, channels.size)
        clock.text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        fillGuide(c)
        fillSource(i)
        fillTrackLine(i)
        fillButtons(i)
        showLogo(c)
        if (banner.visibility != View.VISIBLE) {
            banner.alpha = 0f
            banner.visibility = View.VISIBLE
        }
        banner.animate().cancel()
        banner.animate().alpha(1f).setDuration(150).start()
        ui.removeCallbacks(hideBanner)
        if (!stay && !expanded) ui.postDelayed(hideBanner, if (bannerFocused()) BUTTONS_MS else BANNER_MS)
        // The add-on's page for the channel, for what's on and next: once, in the background.
        if (!vod && !pages.containsKey(c.id)) ahead.execute {
            val meta = runCatching { Stremio.meta(base, c.type, c.id) }.getOrNull() ?: return@execute
            pages[c.id] = meta
            ui.post { if (shown == i && banner.visibility == View.VISIBLE) fillGuide(c) }
        }
    }

    /** The stream playing on channel [i] and its picture's resolution ("Stream 1 of 2 · RAI 1 · 1080p"), or Loading. */
    private fun fillSource(i: Int) {
        val playing = streams.getOrNull(tried)?.takeIf { i == at }
        source.text = if (playing != null)
            listOfNotNull(getString(R.string.player_stream, tried + 1, streams.size, playing.title), resolution()).joinToString(" · ")
                .let { if (player?.playWhenReady == false) getString(R.string.player_paused) + "  ·  " + it else it }
        else getString(R.string.player_loading)
    }

    /** "720p", "1080p", "4K": the picture's lines (its width, for a wide film cropped shorter); null before it plays. */
    private fun resolution(): String? {
        val size = player?.videoSize ?: return null
        if (size.width == 0 || size.height == 0) return null
        val lines = maxOf(size.height, size.width * 9 / 16)
        return when {
            lines >= 2100 -> "4K"
            lines >= 1400 -> "1440p"
            lines >= 1000 -> "1080p"
            lines >= 700 -> "720p"
            else -> "${size.height}p"
        }
    }

    /** What's on: the add-on's (its page, else its catalog's line), else the guide set in Zephyr TV settings. */
    private fun fillGuide(c: Channel) {
        if (vod) return fillTime(c)
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
        // All of it only once OK asks: over a channel playing, the banner stays short.
        more.visibility = if (expanded && !more.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (progress == null) View.GONE else View.VISIBLE
        bar.progress = progress ?: 0
        desc.visibility = if (onNow.visibility == View.VISIBLE || bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /**
     * A film's or episode's line in the banner: its name (an episode's), the time it's at and how long it is, with the
     * bar; OK there shows what it's about.
     */
    private fun fillTime(c: Channel) {
        val p = player?.takeIf { channels.indexOf(c) == at }
        val duration = p?.duration?.takeIf { it > 0 && it != C.TIME_UNSET }
        val pos = p?.currentPosition ?: 0
        onNow.text = listOfNotNull(c.now, duration?.let { "${clock(pos)} / ${clock(it)}" }).joinToString("  ·  ")
        more.text = descs.getOrNull(channels.indexOf(c)).orEmpty()
        onNow.visibility = if (onNow.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        more.visibility = if (expanded && !more.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        bar.visibility = if (duration == null) View.GONE else View.VISIBLE
        bar.progress = if (duration == null) 0 else (pos * 100 / duration).toInt().coerceIn(0, 100)
        desc.visibility = if (onNow.visibility == View.VISIBLE || bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /**
     * Left/Right on a film: back or on [step] (-1, +1), 10 seconds, more while held; the banner shows where to, and
     * Left/Right go on seeking while it's up for it.
     */
    private fun seek(step: Int, repeat: Int = 0) {
        val p = player ?: return
        val by = when { repeat > 20 -> 60_000L; repeat > 5 -> 30_000L; else -> 10_000L }
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val to = (p.currentPosition + step * by).coerceIn(0, duration?.minus(1000) ?: Long.MAX_VALUE)
        p.seekTo(to)
        ui.removeCallbacks(stuck)
        if (banner.visibility != View.VISIBLE) seeking = true
        showBanner(at)
        fillTime(channels[at])
    }

    private fun isAdult(): Boolean = adult || (streams.getOrNull(tried)?.source?.let { it in adultSources } == true)

    /** Where the film or episode playing got to, for resuming it and for Continue Playing; at the credits, it's watched. */
    private fun saveProgress() {
        if (!vod || isAdult()) return
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        if (at !in channels.indices) return
        val e = entry(at, p.currentPosition, duration)
        if (VodProgress.finished(e.position, duration)) return watched(at)
        ahead.execute { runCatching { VodProgress.put(userDir, e) } }
    }

    private fun entry(i: Int, position: Long, duration: Long): VodProgress.Entry {
        val c = channels[i]
        return VodProgress.Entry(c.id, intent.getStringExtra(META_TYPE) ?: c.type, intent.getStringExtra(META_ID) ?: c.id,
            c.name, c.now.orEmpty(), intent.getStringExtra(POSTER) ?: c.logos.firstOrNull(), position, duration, System.currentTimeMillis())
    }

    /**
     * Film or episode [i] watched (at the credits, or to its end): marked so, off Continue Playing, and the next
     * episode waiting there in its place. Once each. (Whether the whole series is watched, the home screen tells:
     * its episodes, specials aside; see Titles.markWatched.)
     */
    private fun watched(i: Int) {
        val c = channels.getOrNull(i) ?: return
        if (!done.add(c.id)) return
        if (isAdult()) return
        // Watched here at the credits, maybe before Trakt's 80% (a short episode): Trakt counts it watched too.
        if (i == at) scrobble("stop", 100.0)
        val meta = intent.getStringExtra(META_ID) ?: c.id
        val last = i == channels.size - 1 // the episodes after the one picked, to the series' last
        val next = if (last) null else entry(i + 1, 0L, 0L)
        ahead.execute {
            runCatching {
                VodWatched.set(userDir, listOf(c.id to meta), true, name = c.name)
                VodProgress.remove(userDir, c.id)
                next?.let { VodProgress.putNext(userDir, it) }
            }
        }
    }

    /**
     * Tells the user's Trakt account (when they have one) about the film or episode playing: "start" (it plays, again
     * after a pause), "pause", "stop" (left, or ended), with how far in it is.
     */
    private fun scrobble(action: String, percent: Double? = null) {
        if (!vod || isAdult()) return
        val p = player ?: return
        val c = channels.getOrNull(at) ?: return
        val duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        when {
            action == "start" -> scrobbling = true
            !scrobbling -> return // nothing told yet: nothing to pause or stop
            action == "stop" -> scrobbling = false
        }
        val progress = percent ?: (p.currentPosition * 100.0 / duration)
        val meta = intent.getStringExtra(META_ID) ?: c.id
        val dir = userDir
        ahead.execute { Trakt.scrobble(dir, action, c.id, meta, progress) }
    }

    /** A film or episode played to its end: watched, then the next episode, or the player closes. */
    private fun ended() {
        scrobble("stop")
        watched(at)
        if (at >= channels.size - 1) return finish()
        // A season's last episode: the next season's first only if the user wants it; Back leaves it there.
        val now = season(channels[at].id)
        val next = season(channels[at + 1].id)
        if (now == null || next == null || now == next) return play(at + 1)
        release()
        openPanel(getString(R.string.player_season_end, now)) { row ->
            row(getString(R.string.player_next_season, next), false, 1) { play(at + 1) }
            row(getString(R.string.player_leave), false, 1) { finish() }
        }
    }

    /** The season of the episode [id] ("tt0944947:2:1": 2), or null for a film or an id without one. */
    private fun season(id: String): Int? {
        val parts = id.split(':')
        if (parts.size < 3 || !parts.takeLast(2).all { p -> p.isNotEmpty() && p.all(Char::isDigit) }) return null
        return parts[parts.size - 2].toInt()
    }

    /** OK on what's on: all of it (the description, what's next), or back to its first line. */
    private fun toggleMore() {
        if (more.text.isNullOrEmpty()) return
        expanded = !expanded
        fillGuide(channels[shown])
        keepUp()
    }

    private fun createLogoBox(padH: Int, padV: Int, radiusDp: Int = 10): LogoBox {
        // A film's art stands on its own, with no box: its corners are rounded on the picture itself (see rounded).
        val frame = FrameLayout(this).apply {
            if (!vod) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(radiusDp).toFloat()
                    setColor(0x33000000)
                }
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        }
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.55f
            if (vod) visibility = View.GONE
        }
        val fg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (!vod) setPadding(padH, padV, padH, padV)
        }
        // The title's logo, over the middle of its art.
        val mark = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        frame.addView(bg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(fg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(mark, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (mark.paddingLeft != w / 8) mark.setPadding(w / 8, h / 4, w / 8, h / 4)
        }
        return LogoBox(frame, bg, fg, mark)
    }

    /**
     * [src] with its corners rounded, when it's a picture that fills its frame; one with see-through corners (a logo,
     * a cut-out poster) is left as it is.
     */
    private fun rounded(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 2 || h < 2) return src
        val corners = listOf(src.getPixel(0, 0), src.getPixel(w - 1, 0), src.getPixel(0, h - 1), src.getPixel(w - 1, h - 1))
        if (src.hasAlpha() && corners.any { Color.alpha(it) < 255 }) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        val radius = w * 0.04f
        android.graphics.Canvas(out).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, paint)
        return out
    }

    /** The title's logo over its art in [box]: decoded once, while the player is open. */
    private fun markInto(box: LogoBox) {
        val uri = intent.getStringExtra(TITLE_LOGO)?.takeIf { it.isNotEmpty() } ?: return
        titleLogo?.let { box.mark.setImageBitmap(it); box.mark.visibility = View.VISIBLE; return }
        ahead.execute {
            val bmp = runCatching { Http.open(uri).use { it.readCapped(Http.MAX_IMAGE) } }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } ?: return@execute
            ui.post {
                titleLogo = bmp
                for (b in listOf(logoBox, loadLogoBox)) {
                    b.mark.setImageBitmap(bmp)
                    b.mark.visibility = View.VISIBLE
                }
            }
        }
    }

    /** The channel's logo in the banner: the add-on's, then Zephyr TV's own; downloaded once, kept while the player is open. */
    private fun showLogo(c: Channel) = logoInto(logoBox, c) { title.text == c.name }

    /** [c]'s logo in [box]: the add-on's, then Zephyr TV's own; downloaded once, set if [still] holds when it arrives. */
    private fun logoInto(box: LogoBox, c: Channel, isFullScreenBackdrop: Boolean = false, still: () -> Boolean) {
        val knownUri = c.logos.firstOrNull { logos[it] != null }
        val known = knownUri?.let { logos[it] }
        if (known != null) {
            val blur = blurLogos[knownUri] ?: runCatching { softBlur(known) }.getOrNull()?.also { blurLogos[knownUri] = it }
            box.bg.setImageBitmap(blur)
            box.fg.setImageBitmap(if (vod) roundArt[knownUri] ?: known else known)
            if (isFullScreenBackdrop && blur != null) blurBackdrop.setImageBitmap(blur)
        } else {
            box.bg.setImageDrawable(null)
            box.fg.setImageDrawable(null)
            if (isFullScreenBackdrop) blurBackdrop.setImageDrawable(null)
        }
        box.frame.visibility = if (c.logos.isEmpty()) View.GONE else View.VISIBLE
        if (known != null || c.logos.isEmpty()) return
        ahead.execute {
            var foundUri: String? = null
            val bmp = c.logos.firstNotNullOfOrNull { uri ->
                runCatching {
                    val bytes = Http.open(uri).use { it.readCapped(Http.MAX_IMAGE) }
                    // A film's art is a backdrop, up to 4K: decoded at about the size it shows (320 dp wide at most).
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= dp(320)) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.also {
                            logos[uri] = it
                            foundUri = uri
                        }
                }.getOrNull()
            } ?: return@execute
            val blur = foundUri?.let { u -> blurLogos.getOrPut(u) { softBlur(bmp) } } ?: runCatching { softBlur(bmp) }.getOrNull()
            val shown = if (vod) runCatching { rounded(bmp) }.getOrNull()?.also { r -> foundUri?.let { roundArt[it] = r } } ?: bmp else bmp
            ui.post {
                if (still()) {
                    box.bg.setImageBitmap(blur)
                    box.fg.setImageBitmap(shown)
                    if (isFullScreenBackdrop && blur != null) blurBackdrop.setImageBitmap(blur)
                }
            }
        }
    }

    /**
     * The channel tuned in the middle of the screen, its logo and name over [text] (Loading…, Loading stream 2 of
     * 3…, or that none works); null takes it away, once the picture plays.
     */
    private fun say(text: String?) {
        if (text == null) {
            loading.visibility = View.GONE
            blurBackdrop.visibility = View.GONE
            blurBackdropShade.visibility = View.GONE
            return
        }
        val c = channels[at]
        if (loadFor != at) {
            val i = at
            loadFor = i
            loadName.text = c.name
            // A film's or episode's line (an episode's season, number and name); a channel's what's on stays in the banner.
            loadSub.text = if (vod) c.now.orEmpty() else ""
            loadSub.visibility = if (loadSub.text.isEmpty()) View.GONE else View.VISIBLE
            logoInto(loadLogoBox, c, isFullScreenBackdrop = true) { loadFor == i }
        }
        status.text = text
        loading.visibility = View.VISIBLE
        blurBackdrop.visibility = View.VISIBLE
        blurBackdropShade.visibility = View.VISIBLE
    }

    // ---------- remote ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Audio and subtitles open: its rows take the arrows and OK; Back closes it.
        if (tracksPanel.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) closeTracks() else return super.onKeyDown(keyCode, event)
            return true
        }
        // The banner in use (OK, Up or Down brought it up): the focus is on what's on or on a button. Down goes to
        // the buttons, Up back to what's on, Left/Right along the buttons; OK picks (the views' own handling). It
        // stays up while it's used.
        if (bannerFocused()) when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { hideBanner.run(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (buttonFocused()) focusDesc() else keepUp(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!buttonFocused() && buttons.childCount > 0) focusButton(0) else keepUp(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val step = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                // A film's line is its timeline: Left/Right there seek.
                if (vod && currentFocus === desc) { seek(step, event.repeatCount); keepUp(); return true }
                val n = buttons.childCount
                if (n > 0) focusButton(if (buttonFocused()) (buttons.indexOfChild(currentFocus) + step).coerceIn(0, n - 1) else if (step > 0) 0 else n - 1)
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { hideBanner.run(); return true }
        }
        // The banner up (and done fading in) with buttons: Left/Right reach them; otherwise they change the stream.
        if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && banner.visibility == View.VISIBLE &&
            banner.alpha >= 1f && shown == at && buttons.childCount > 0 && !(vod && seeking)) {
            focusButton(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 0 else buttons.childCount - 1)
            return true
        }
        // Up/Down never zap (CH+/CH- do): they bring the banner up, as OK does.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            okPressed()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
            KeyEvent.KEYCODE_MEDIA_NEXT -> zap(+1) // a series' next episode (a channel's next, live)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> zap(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (vod) seek(-1, event.repeatCount) else otherStream(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (vod) seek(+1, event.repeatCount) else otherStream(+1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (vod) seek(-1, 6)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (vod) seek(+1, 6)
            // OK acts once let go (see onKeyUp), as holding it opens Audio and subtitles (onKeyLongPress).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (event.repeatCount == 0) event.startTracking()
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> if (event.repeatCount == 0) okPressed()
            KeyEvent.KEYCODE_MENU -> openTracks()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> cycle(C.TRACK_TYPE_AUDIO)
            KeyEvent.KEYCODE_CAPTIONS -> cycle(C.TRACK_TYPE_TEXT)
            KeyEvent.KEYCODE_LAST_CHANNEL -> if (previous in channels.indices) zapTo(previous, delay = 0)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> player?.let { p ->
                setPlaying(if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) !p.playWhenReady else keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)
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

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return super.onKeyLongPress(keyCode, event)
        openTracks()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (tracksPanel.visibility == View.VISIBLE || bannerFocused()) return super.onKeyUp(keyCode, event)
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled) okPressed() // a hold opened the panel instead
        return true
    }

    /**
     * OK (or Up, Down, Info) with the banner down, or up as a zap shows it: the banner, the focus on what's on (so it's
     * clear where OK goes next: all of it), else on the first button. Once in use, the banner's views take OK.
     */
    private fun okPressed() {
        seeking = false
        showBanner(shown)
        if (shown == at) focusDesc()
    }

    // ---------- audio and subtitles ----------

    /** The stream's audio or subtitle tracks the TV can play, in the stream's order, each named for the panel. */
    private fun tracks(type: Int): List<Track> {
        val p = player ?: return emptyList()
        var n = 0
        return p.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { i -> Track(g, i, trackName(g.getTrackFormat(i), ++n), g.isTrackSelected(i)) }
        }
    }

    /** "Italiano 5.1", "English (forced)": the track's language (or label, or number), and what tells it from others. */
    private fun trackName(f: Format, n: Int): String {
        val locale = resources.configuration.locales[0]
        val lang = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(locale).takeIf { d -> d.isNotBlank() && d != it } }
            ?.replaceFirstChar { it.titlecase(locale) }
        val name = listOfNotNull(lang, f.label?.takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }).joinToString(" · ")
            .ifEmpty { getString(R.string.player_track_n, n) }
        val sound = when (f.channelCount) { 1 -> "Mono"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
        val forced = if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) getString(R.string.player_track_forced) else null
        return listOfNotNull(name, sound, forced).joinToString(" ")
    }

    /** In the banner, under the stream: the audio track and subtitles playing, and how to change them. */
    private fun fillTrackLine(i: Int) {
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        if (i != at || (audio.size < 2 && subs.isEmpty())) {
            trackLine.visibility = View.GONE
            return
        }
        val sound = audio.firstOrNull { it.selected }?.name ?: audio.firstOrNull()?.name ?: "–"
        val shownSubs = subs.firstOrNull { it.selected }?.name ?: getString(R.string.player_subtitles_off)
        trackLine.text = getString(R.string.player_tracks_line, sound, shownSubs)
        trackLine.visibility = View.VISIBLE
    }

    // ---------- the banner's buttons ----------

    /** The banner's buttons for channel [i] once it plays: Audio with two tracks or more, Subtitles with any, Stream with two or more. */
    private fun fillButtons(i: Int) {
        val keep = (currentFocus?.parent === buttons).let { if (it) buttons.indexOfChild(currentFocus) else -1 }
        val paused = player?.playWhenReady == false
        val list = if (i != at || player == null) emptyList() else listOfNotNull(
            // A series' episodes: the one before and the one after, either side of play and pause.
            if (vod && at > 0) Triple(R.drawable.ic_chevron_left, R.string.player_episode_previous) { zap(-1) } else null,
            if (paused) Triple(R.drawable.ic_play, R.string.player_play) { setPlaying(true) }
            else Triple(R.drawable.ic_pause, R.string.player_pause) { setPlaying(false) },
            if (vod && at < channels.size - 1) Triple(R.drawable.ic_chevron_right, R.string.player_episode_next) { zap(+1) } else null,
            if (tracks(C.TRACK_TYPE_AUDIO).size > 1) Triple(R.drawable.ic_audio, R.string.player_audio) { openTracks(C.TRACK_TYPE_AUDIO) } else null,
            if (tracks(C.TRACK_TYPE_TEXT).isNotEmpty()) Triple(R.drawable.ic_subtitles, R.string.player_subtitles) { openTracks(C.TRACK_TYPE_TEXT) } else null,
            if (streams.size > 1) Triple(R.drawable.ic_stream, R.string.player_streams) { openStreams() } else null,
        )
        val now = (0 until buttons.childCount).map { buttons.getChildAt(it).tag }
        if (now == list.map { it.second }) return // the same: leave them (and focus) as they are
        buttons.removeAllViews()
        list.forEach { (icon, label, action) -> buttons.addView(button(icon, getString(label), action).apply { tag = label }) }
        buttons.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        sizeButtons()
        if (bannerFocused() || keep >= 0) reachable() // in use: the new ones too
        if (keep >= 0) focusButton(minOf(keep, buttons.childCount - 1))
    }

    /**
     * Play or pause the channel (the button, or the remote's media keys). Paused, the banner stays up and says so; a
     * live stream paused past what it keeps catches up with the broadcast when it plays again (see onPlayerError).
     */
    private fun setPlaying(play: Boolean) {
        val p = player ?: return
        if (p.playWhenReady == play) return
        p.playWhenReady = play
        ui.removeCallbacks(stuck) // a pause isn't a stream that's stuck
        showBanner(at, stay = !play)
    }

    /**
     * A square icon (its name for TalkBack), lit white while it has the focus; focusable only once the banner is in
     * use. As tall as the stream and audio lines beside it (see sizeButtons).
     */
    private fun button(icon: Int, label: String, action: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        contentDescription = label
        scaleType = ImageView.ScaleType.FIT_CENTER
        val bg = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33FFFFFF) }
        background = bg
        isFocusable = false
        isClickable = true
        setOnClickListener { action() }
        setOnFocusChangeListener { v, has ->
            bg.setColor(if (has) Color.WHITE else 0x33FFFFFF)
            setColorFilter(if (has) Color.BLACK else Color.WHITE)
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).setDuration(120).start()
        }
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(10) }
    }

    /** The buttons square, as tall as the stream and audio lines together (one line when there's no audio line). */
    private fun sizeButtons() {
        val h = maxOf(lines.height, dp(32))
        for (k in 0 until buttons.childCount) {
            val b = buttons.getChildAt(k)
            val lp = b.layoutParams as LinearLayout.LayoutParams
            if (lp.width == h && lp.height == h) continue
            lp.width = h
            lp.height = h
            b.layoutParams = lp
            b.setPadding(h / 4, h / 4, h / 4, h / 4)
        }
    }

    private fun buttonFocused() = ::buttons.isInitialized && currentFocus?.parent === buttons

    /** The banner in use: the focus on what's on or one of its buttons. */
    private fun bannerFocused() = ::desc.isInitialized && (currentFocus === desc || buttonFocused())

    /** The banner up a while longer (as long as all of what's on shows, when it does): it's in use. */
    private fun keepUp() {
        ui.removeCallbacks(hideBanner)
        if (!expanded) ui.postDelayed(hideBanner, BUTTONS_MS)
    }

    /** Makes the banner's views reachable: what's on and the buttons. */
    private fun reachable() {
        desc.isFocusable = true
        for (k in 0 until buttons.childCount) buttons.getChildAt(k).isFocusable = true
    }

    /** The focus on what's on (else, with nothing on, on the first button); the banner stays up a while longer. */
    private fun focusDesc() {
        if (desc.visibility != View.VISIBLE) return if (buttons.childCount > 0) focusButton(0) else Unit
        reachable()
        desc.requestFocus()
        keepUp()
    }

    /** The focus on the [n]th button; the banner stays up a while longer. */
    private fun focusButton(n: Int) {
        reachable()
        buttons.getChildAt(n)?.requestFocus()
        keepUp()
    }

    /** What's on and the buttons out of reach again (not focusable, so the window can't hand them the focus by itself). */
    private fun unfocusBanner() {
        if (!::buttons.isInitialized) return
        desc.isFocusable = false
        for (k in 0 until buttons.childCount) buttons.getChildAt(k).isFocusable = false
    }

    /** Stream: the channel's streams, as the add-on lists them; the one picked plays (then the next ones, if it fails). */
    private fun openStreams() {
        if (streams.size < 2) return
        openPanel(getString(R.string.player_streams)) { row ->
            streams.forEachIndexed { k, s ->
                val name = if (s.source != null && !s.title.contains(s.source, ignoreCase = true)) "${s.source} · ${s.title}" else s.title
                row(getString(R.string.player_stream_row, k + 1, name), k == tried, 1) {
                    tried = k - 1
                    attempts = 0
                    next()
                }
            }
        }
    }

    /** Hold OK, or Menu (both lists), or a button (the audio tracks, or Off and the subtitles); focus on what's playing. */
    private fun openTracks(type: Int? = null) {
        if (player == null) return showBanner(shown)
        val audio = tracks(C.TRACK_TYPE_AUDIO)
        val subs = tracks(C.TRACK_TYPE_TEXT)
        val title = when (type) {
            C.TRACK_TYPE_AUDIO -> R.string.player_audio
            C.TRACK_TYPE_TEXT -> R.string.player_subtitles
            else -> R.string.player_tracks_title
        }
        openPanel(getString(title)) { row ->
            fun heading(res: Int) = tracksList.addView(text(13f).apply {
                this.text = getString(res)
                alpha = 0.6f
                setPadding(dp(14), dp(16), 0, dp(6))
            })
            if (type != C.TRACK_TYPE_TEXT) {
                if (type == null) heading(R.string.player_audio)
                if (audio.isEmpty()) row(getString(R.string.player_track_n, 1), true, 1) {}
                audio.forEach { t -> row(t.name, t.selected, 1) { pick(t, C.TRACK_TYPE_AUDIO) } }
            }
            if (type != C.TRACK_TYPE_AUDIO) {
                if (type == null) heading(R.string.player_subtitles)
                row(getString(R.string.player_subtitles_off), subs.none { it.selected }, 1) { subtitlesOff() }
                subs.forEach { t -> row(t.name, t.selected, 1) { pick(t, C.TRACK_TYPE_TEXT) } }
            }
        }
    }

    /**
     * The side panel titled [title], its rows added by [fill] through the row maker it's given (a name, whether it's
     * the one playing, its lines at most: a stream's long name takes two, what picking it does); focus on the one playing.
     */
    private fun openPanel(title: String, fill: ((String, Boolean, Int, () -> Unit) -> Unit) -> Unit) {
        hideBanner.run()
        tracksTitle.text = title
        tracksList.removeAllViews()
        var focus: View? = null
        fun row(name: String, on: Boolean, lines: Int, pick: () -> Unit) {
            TextView(this).apply {
                this.text = if (on) "✓  $name" else "     $name"
                textSize = 16f
                // One line scrolls while it has focus (a stream's long name); more are cut short.
                if (lines == 1) {
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                } else {
                    maxLines = lines
                    ellipsize = TextUtils.TruncateAt.END
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isClickable = true
                val bg = GradientDrawable().apply { cornerRadius = dp(8).toFloat() }
                background = bg
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { _, has ->
                    bg.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                    setTextColor(if (has) Color.BLACK else Color.WHITE)
                    isSelected = has
                }
                setOnClickListener { pick(); closeTracks() }
                tracksList.addView(this)
                if (on && focus == null) focus = this
            }
        }
        fill { name, on, lines, pick -> row(name, on, lines, pick) }
        tracksPanel.visibility = View.VISIBLE
        (focus ?: (0 until tracksList.childCount).map { tracksList.getChildAt(it) }.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun closeTracks() {
        tracksPanel.visibility = View.GONE
        tracksList.removeAllViews()
    }

    /** Plays track [t], and keeps its language for the channels after this one (and the next time). */
    private fun pick(t: Track, type: Int) {
        val p = player ?: return
        val lang = t.group.getTrackFormat(t.index).language
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
            .apply { if (type == C.TRACK_TYPE_AUDIO) setPreferredAudioLanguage(lang) else setPreferredTextLanguage(lang) }
            .build()
        val key = if (type == C.TRACK_TYPE_AUDIO) AUDIO_LANG else TEXT_LANG
        choices.edit().apply { if (lang != null) putString(key, lang) else if (type == C.TRACK_TYPE_AUDIO) remove(key) else putString(key, "und") }.apply()
    }

    private fun subtitlesOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        choices.edit().putString(TEXT_LANG, "").apply()
        showCues(emptyList())
    }

    /** The remote's audio or subtitles key: the next track (subtitles: after the last, off), shown in the banner. */
    private fun cycle(type: Int) {
        val list = tracks(type)
        val on = list.indexOfFirst { it.selected }
        when {
            type == C.TRACK_TYPE_TEXT && on == list.size - 1 && on >= 0 -> subtitlesOff()
            list.isNotEmpty() && (type == C.TRACK_TYPE_TEXT || list.size > 1) -> pick(list[(on + 1) % list.size], type)
        }
        ui.postDelayed({ showBanner(shown) }, 300) // once the player has switched
    }

    /** The languages picked before, as preferences for a new stream's player: Off stays off. */
    private fun applyChoices(p: ExoPlayer) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            choices.getString(AUDIO_LANG, null)?.let { setPreferredAudioLanguage(it) }
            when (val t = choices.getString(TEXT_LANG, null)) {
                null -> {}
                "" -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                else -> setPreferredTextLanguage(t).setSelectUndeterminedTextLanguage(true)
            }
        }.build()
    }

    /**
     * The subtitles on screen: text ones in a caption at the bottom, picture ones (DVB, common on IPTV) where the
     * stream places them, over the picture's own area.
     */
    private fun showCues(cues: List<Cue>) {
        subtitles.removeAllViews()
        val w = subtitles.width
        val h = subtitles.height
        val lines = ArrayList<CharSequence>()
        for (c in cues) {
            val bmp = c.bitmap
            if (bmp == null) {
                c.text?.let { lines += it }
                continue
            }
            if (w == 0 || h == 0) continue
            val cw = if (c.size != Cue.DIMEN_UNSET) (c.size * w).toInt() else bmp.width
            val ch = if (c.bitmapHeight != Cue.DIMEN_UNSET) (c.bitmapHeight * h).toInt() else cw * bmp.height / maxOf(1, bmp.width)
            val x = if (c.position != Cue.DIMEN_UNSET) (c.position * w).toInt() else w / 2
            val y = if (c.line != Cue.DIMEN_UNSET && c.lineType == Cue.LINE_TYPE_FRACTION) (c.line * h).toInt() else h - dp(32)
            val left = when (c.positionAnchor) { Cue.ANCHOR_TYPE_MIDDLE -> x - cw / 2; Cue.ANCHOR_TYPE_END -> x - cw; else -> if (c.position == Cue.DIMEN_UNSET) x - cw / 2 else x }
            val top = when (c.lineAnchor) { Cue.ANCHOR_TYPE_MIDDLE -> y - ch / 2; Cue.ANCHOR_TYPE_END -> y - ch; else -> if (c.line == Cue.DIMEN_UNSET) y - ch else y }
            subtitles.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY },
                FrameLayout.LayoutParams(cw, ch).apply { leftMargin = left; topMargin = top })
        }
        if (lines.isNotEmpty()) subtitles.addView(text(22f).apply {
            text = TextUtils.join("\n", lines)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(40)
        })
    }

    /** CH+ / CH-: the banner moves on at once; the channel is tuned once the keys rest (holding one runs through). */
    private fun zap(step: Int) {
        if (channels.size < 2) return showBanner(shown)
        // A series' episodes stop at its first and last; channels go round.
        if (vod) return (shown + step).let { to -> if (to in channels.indices) zapTo(to, delay = ZAP_MS) else showBanner(shown) }
        zapTo(Math.floorMod(shown + step, channels.size), delay = ZAP_MS)
    }

    private fun zapTo(i: Int, delay: Long) {
        shown = i
        expanded = false
        showBanner(i)
        ui.removeCallbacks(zapNow)
        ui.postDelayed(zapNow, delay)
    }

    /** Left / Right: the channel's other streams, as the add-on lists them. */
    private fun otherStream(step: Int) {
        if (streams.size < 2 || shown != this.at) return showBanner(shown)
        tried = Math.floorMod(tried + step, streams.size) - 1
        attempts = 0
        next()
    }

    // ---------- playing ----------

    /** Tunes channel [i]: its streams (fetched ahead if they're fresh), the first that works, then its neighbours'. */
    private fun play(i: Int) {
        if (vod && player != null) { saveProgress(); scrobble("stop") } // the one left (still [at]), before the next
        if (i != at) previous = at
        at = i
        shown = i
        val c = channels[i]
        val tuning = ++tune
        streams = emptyList()
        tried = -1
        attempts = 0
        release()
        pendingSeek = if (vod && !isAdult()) VodProgress.position(userDir, c.id) ?: -1L else -1L
        say(getString(R.string.player_loading))
        if (banner.visibility == View.VISIBLE) showBanner(i) // zapping: the banner keeps up with it; else the middle says it all
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
        if (vod) return titleStreams(c)
        // The channel's add-on, then the Nuvio plugins that can play it (those for TV channels, by its id).
        val own = runCatching { Stremio.streams(base, c.type, c.id) }.onFailure { warn("streams failed: $it") }.getOrDefault(emptyList())
        val plugins = pluginSources(prefs, filesDir, c.type, c.id, adultShows(prefs)).flatMap { (b, name) ->
            runCatching { sourceStreams(prefs, filesDir, b, name, c.type, c.id) }.getOrDefault(emptyList())
        }
        return (own + plugins).also { if (it.isNotEmpty()) fetched[c.id] = System.currentTimeMillis() to it }
    }

    /**
     * A film's or episode's streams from every stream add-on at once, in the add-ons' order (each its best first),
     * waiting for the slow ones up to VOD_SOME_MS, or VOD_WAIT_MS while none has any; the same kind as the one playing
     * (its bingeGroup) first.
     */
    private fun titleStreams(c: Channel): List<Stremio.Stream> {
        val asked = sources.map { (b, name) ->
            pool.submit(Callable { runCatching { sourceStreams(prefs, filesDir, b, name, c.type, c.id) }.onFailure { warn("streams failed: ${b.substringAfter("://").substringBefore('/')} ($it)") }.getOrDefault(emptyList()) })
        }
        val start = System.currentTimeMillis()
        val got = arrayOfNulls<List<Stremio.Stream>>(asked.size)
        for ((i, f) in asked.withIndex()) {
            val limit = start + if (got.any { !it.isNullOrEmpty() }) VOD_SOME_MS else VOD_WAIT_MS
            got[i] = runCatching { f.get((limit - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS) }.getOrElse { f.cancel(true); emptyList() }
        }
        val all = got.flatMap { it.orEmpty() }.distinctBy { it.url }
        val list = group?.let { g -> all.sortedByDescending { it.group == g } } ?: all
        if (list.isNotEmpty()) fetched[c.id] = System.currentTimeMillis() to list
        return list
    }

    /** The streams of the channels either side, so CH+ and CH- don't wait for the add-on (OMG checks each: ~1.5 s). */
    private fun prefetch(i: Int) {
        if (vod) return run { if (i + 1 < channels.size) channels[i + 1].let { c -> ahead.execute { streamsOf(c) } } } // the next episode only
        if (channels.size < 2) return
        for (j in listOf(Math.floorMod(i + 1, channels.size), Math.floorMod(i - 1, channels.size)).distinct()) {
            val c = channels[j]
            ahead.execute { streamsOf(c) }
        }
    }

    /**
     * Tries the stream after the one that failed (the first, at the start), going round the list; once each was
     * tried since the channel was tuned (or one was picked), says none works.
     */
    private fun next() {
        ui.removeCallbacks(stuck)
        // A film's stream that fails (or another picked) carries on where the last one got to.
        if (vod) player?.let { if (it.currentPosition > 5000) pendingSeek = it.currentPosition }
        if (streams.isEmpty() || ++attempts > streams.size) {
            release()
            return say(getString(R.string.player_no_streams))
        }
        tried = Math.floorMod(tried + 1, streams.size)
        val s = streams[tried]
        say(if (streams.size > 1) getString(R.string.player_stream_n, tried + 1, streams.size) else getString(R.string.player_loading))
        val tuning = tune
        val n = tried
        net.execute {
            val probe = Stremio.probe(s.url, s.headers)
            ui.post {
                if (isFinishing || tuning != tune || n != tried) return@post
                // The only stream (one picked by hand) is played even when the check failed: some servers turn the
                // check away (too many requests) and not the player.
                if (!probe.ok && streams.size > 1) next() else start(s, probe.format)
            }
        }
    }

    private fun start(s: Stremio.Stream, format: String?) {
        release()
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // OMG's addresses go from https to the proxy's http, and back
            // Not what the connection sets itself: a plugin's Accept-Encoding: gzip would hand the player packed bytes.
            .setDefaultRequestProperties(s.headers.filterKeys { k -> SKIPPED_HEADERS.none { it.equals(k, ignoreCase = true) } })
        s.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
        // Sound as a film's, through the output the TV has chosen (its speakers, a soundbar or receiver on HDMI ARC or
        // optical, Bluetooth): taking the audio focus, so the TV routes it there and other sound stops; pausing when
        // headphones or a Bluetooth speaker go away. The sink follows the output's changes and what it can decode
        // (Dolby, DTS passed through to a receiver that can).
        // The web through [http]; a playlist written in its address (data:, from a Nuvio plugin) read as it is.
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = p
        applyChoices(p)
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        ui.removeCallbacks(stuck)
                        say(null)
                        if (vod && p.playWhenReady && !scrobbling) ui.post { scrobble("start") } // once it has sought to where it resumes
                        if (pendingSeek > 0) p.seekTo(pendingSeek).also { pendingSeek = -1L }
                        if (vod) group = streams.getOrNull(tried)?.group else remember(channels[at])
                        if (banner.visibility == View.VISIBLE && shown == at) showBanner(at)
                    }
                    // Waiting too long, at the start or later, is a stream that's stuck: the next one.
                    Player.STATE_BUFFERING -> {
                        ui.removeCallbacks(stuck)
                        ui.postDelayed(stuck, if (vod) VOD_STUCK_MS else STUCK_MS)
                    }
                    Player.STATE_ENDED -> if (vod) ended()
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

            override fun onVideoSizeChanged(size: VideoSize) {
                fit(size)
                if (banner.visibility == View.VISIBLE && shown == at) fillSource(at)
            }

            override fun onCues(cueGroup: CueGroup) = showCues(cueGroup.cues)

            // Paused and played again: Trakt keeps up (it shows the user watching, and where they paused).
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (vod && p.playbackState == Player.STATE_READY) scrobble(if (playWhenReady) "start" else "pause")
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (banner.visibility == View.VISIBLE && shown == at) { fillTrackLine(at); fillButtons(at) }
            }
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
        ui.postDelayed(stuck, if (vod) VOD_STUCK_MS else STUCK_MS)
    }

    private fun release() {
        ui.removeCallbacks(stuck)
        player?.release()
        player = null
        if (::subtitles.isInitialized) subtitles.removeAllViews()
    }

    /** The picture as large as the screen allows, at its own shape (black bars rather than stretched). */
    private fun fit(size: VideoSize) {
        if (size.width == 0 || size.height == 0) return
        val root = surface.parent as View
        val ratio = size.width * size.pixelWidthHeightRatio / size.height
        val (w, h) = if (root.width.toFloat() / root.height > ratio) (root.height * ratio).toInt() to root.height
            else root.width to (root.width / ratio).toInt()
        surface.layoutParams = (surface.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h }
        subtitles.layoutParams = (subtitles.layoutParams as FrameLayout.LayoutParams).apply { width = w; height = h } // placed over the picture
    }

    /** The channel that plays, for Continue Playing and the add-on's tile (see Addons.lastAddonChannel): a file both processes read. */
    private fun remember(c: Channel) {
        if (isAdult()) return
        ahead.execute {
            runCatching {
                val o = JSONObject().put("addon", addon).put("type", c.type).put("id", c.id).put("name", c.name)
                    .put("logo", c.logos.firstOrNull().orEmpty()).put("at", System.currentTimeMillis())
                val f = File(filesDir, LAST_FILE)
                val part = File(filesDir, "$LAST_FILE.part")
                part.writeText(o.toString())
                part.renameTo(f)
            }
        }
    }

    // Live TV: nothing to come back to, so leaving the player closes it; a film is kept where it got to.
    override fun onStop() {
        super.onStop()
        saveProgress()
        scrobble("stop")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        release()
        net.shutdownNow()
        ahead.shutdown()
        if (vod) pool.shutdownNow()
    }

    companion object {
        const val ADDON = "addon" // the add-on's manifest address
        const val BASE = "base" // the add-on's address, without /manifest.json
        const val INDEX = "index" // the channel picked, in the lists below
        const val CHOSEN = "chosen" // a film's or episode's stream picked by hand, as streamJson writes it

        /** [s] for an Intent: its address, name, headers and source. */
        internal fun streamJson(s: Stremio.Stream): JSONObject = JSONObject().put("url", s.url).put("title", s.title)
            .put("headers", JSONObject(s.headers as Map<*, *>)).put("group", s.group ?: JSONObject.NULL).put("source", s.source ?: JSONObject.NULL)

        private fun streamOf(j: JSONObject): Stremio.Stream {
            val h = j.optJSONObject("headers") ?: JSONObject()
            return Stremio.Stream(j.getString("url"), j.optString("title"), h.keys().asSequence().associateWith { h.optString(it) },
                j.optString("group").takeIf { !j.isNull("group") && it.isNotEmpty() }, j.optString("source").takeIf { !j.isNull("source") && it.isNotEmpty() })
        }
        const val TYPES = "types"
        const val IDS = "ids"
        const val NAMES = "names"
        const val NOWS = "nows" // what's on, as the add-on's catalog says
        const val LOGOS = "logos" // the add-on's
        const val FALLBACK_LOGOS = "fallback_logos" // Zephyr TV's own, by name (see ChannelLogos)
        const val LAST_FILE = "last_channel.json"
        const val MODE = "mode" // MODE_VOD for films and episodes; none for TV channels
        const val MODE_VOD = "vod"
        const val SOURCES = "sources" // films and episodes: the stream add-ons' addresses (without /manifest.json), in order
        const val SOURCE_NAMES = "source_names"
        const val DESCS = "descs" // what each film or episode is about
        const val META_TYPE = "meta_type" // the title's type and id ("series", "tt…"), for its page from Continue Playing
        const val META_ID = "meta_id"
        const val POSTER = "poster" // the title's art, for its Continue Playing card
        const val TITLE_LOGO = "title_logo" // a film's or series' logo, over its art in the player
        const val USER_DIR = "user_dir" // the folder of the user watching (see Users): their progress, watched titles, Trakt
        const val ADULT = "adult" // whether this content is from an adult add-on (no history, no continue watching)
        const val ADULT_SOURCES = "adult_sources" // names and addresses of adult stream add-ons
        private const val VOD_WAIT_MS = 20_000L // a film's slower add-ons (an aggregator, AIOStreams) are waited for this long
        private const val VOD_SOME_MS = 12_000L // ...or this long, once one has given streams
        private const val VOD_STUCK_MS = 30_000L // a film's stream buffering longer than this: the next
        private const val CHOICES_FILE = "player" // not K.FILE: the home screen's process writes that one whole
        private const val AUDIO_LANG = "audio_lang"
        private const val TEXT_LANG = "text_lang" // "" for off; none for the stream's own choice
        private const val BANNER_MS = 5000L
        private const val BUTTONS_MS = 10_000L // the banner stays up this long after a key on its buttons
        private const val ZAP_MS = 700L // the keys rest this long before the channel is tuned
        private const val STUCK_MS = 15_000L // buffering longer than this: the next stream
        private val SKIPPED_HEADERS = listOf("User-Agent", "Accept-Encoding", "Content-Length", "Host", "Connection")
        private const val FRESH_MS = 90_000L // fetched streams are used this long (their addresses can expire)
    }
}
