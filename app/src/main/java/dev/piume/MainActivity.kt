package dev.piume

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.BulletSpan
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.concurrent.Executors
import org.json.JSONObject

/** Light/dark from the launcher's theme setting; shared with the Gallery screensaver. */
internal fun prefersDark(prefs: SharedPreferences) = when (prefs.getString("theme", "light")) {
    "dark" -> true
    "auto" -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) !in 7..18
    else -> false
}

/** "1.10" > "1.9" > "1.2.1" > "1.2": compared number by number, missing parts count as 0. */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

/** "external" covers every attached volume (USB drives too) from Android 10; before that only the main one. */
internal val PHOTOS: Uri by lazy { // lazy: unit tests load this file without Android
    if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
}

/** Photo ids in a "folder:<bucket id>:<name>" choice. */
internal fun folderPhotos(cr: ContentResolver, choice: String): List<Long> =
    cr.query(PHOTOS, arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(choice.split(':')[1]), null)
        ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } } ?: emptyList()

/** Newest first across apps (ties keep their order), at most [perApp] per app: one app's ten shows can't bury the rest. */
internal fun <T> newestPerApp(items: List<Pair<Long, T>>, perApp: Int, app: (T) -> String?): List<T> {
    val shown = HashMap<String?, Int>()
    return items.sortedByDescending { it.first }.map { it.second }.filter { shown.merge(app(it), 1, Int::plus)!! <= perApp }
}

/** Layout lock PIN, stored as "salt:sha256". Keeps it out of plain sight in a prefs backup; not real security. */
internal fun hashPin(pin: String, salt: String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }) =
    salt + ":" + MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

/** "5", "5-1", "5.1", "101" as numbers to sort by (5-1 right after 5); no number sorts last. */
internal fun channelKey(number: String?): Double {
    val parts = number?.split('-', '.', ' ')?.mapNotNull { it.toIntOrNull() } ?: return Double.MAX_VALUE
    val major = parts.firstOrNull() ?: return Double.MAX_VALUE
    return major + (parts.getOrNull(1) ?: 0) / 1000.0
}

internal fun pinMatches(stored: String, pin: String) = hashPin(pin, stored.substringBefore(':')) == stored

/** Allowed to start an activity from the background because the Home button service is bound. */
/**
 * Where the update's install session reports back. It isn't exported, so only Android (through the PendingIntent
 * Piume handed it) can call it. That matters: the report can carry an Intent to start (Android's confirm box), and
 * MainActivity, which every app may start, must never launch an Intent someone else put in.
 */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        // The outcome only (no Intent): MainActivity resets its "Updating…" row and says if it failed.
        c.startActivity(Intent(c, MainActivity::class.java).setAction(UPDATE_STATUS)
            .putExtra(PackageInstaller.EXTRA_STATUS, status)
            .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class Updated : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val CW_PKG = "piume:continue_watching"
private const val UPDATE_STATUS = "dev.piume.UPDATE_STATUS"

class MainActivity : Activity() {

    private class App(val pkg: String, val label: String, val launch: Intent, val icon: Drawable)
    private class Prog(val title: String, val sub: String, val progress: Int, val image: String?, val intent: String?, val pkg: String?, val weight: Long = 0)

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    // Posters get their own threads: one slow image server must not hold up the rest (or the row data).
    private val posterIo = Executors.newFixedThreadPool(3)
    // Network work that can take a while (weather, update checks and downloads, city search) has its own threads,
    // so an update download or a slow weather server never holds up the rows, which io reads from the TV.
    private val netIo = Executors.newFixedThreadPool(2)
    // Spotlight's own thread: downloading the next backdrop ahead mustn't hold up the rows' data on io.
    private val spotlightIo = Executors.newSingleThreadExecutor()
    private val backdrops by lazy { BackdropCache(File(cacheDir, "spotlight")) }
    private var lastInput = SystemClock.elapsedRealtime() // last key press, for pausing Spotlight when nobody watches
    private var spotlightIdle = false
    private val loading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val ui = Handler(Looper.getMainLooper())
    private val posters = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    // Kept across activity rebuilds (theme switch), so the new home screen shows its apps at once.
    private var apps = APPS ?: emptyList()
    private var appsDirty = APPS == null
    private var watchKey: String? = null
    private var lastTop: View? = null // last focused view in each home row, so Up/Down return to it
    private var lastApp: View? = null
    private var lastCard: View? = null
    private var editing = false
    private var grabbed: View? = null
    private var holdFired = false
    private var dark = false
    private var fg = Color.BLACK
    private var ground = Color.WHITE
    private var onFg = Color.WHITE // text and icons on an fg-coloured fill (focused small controls)
    private var field = Color.WHITE // input boxes (PIN, API key) on the settings panel
    private var cardRest = intArrayOf(Color.WHITE, Color.WHITE)
    private var cardLit = cardRest

    private lateinit var appRow: LinearLayout
    private lateinit var watchRow: LinearLayout
    private lateinit var watchTitle: TextView
    private val appsTitle by lazy { findViewById<TextView>(R.id.appsTitle) }
    private lateinit var watchScroll: View
    private lateinit var allApps: View
    private lateinit var allAppsGrid: GridLayout
    private lateinit var wifi: ImageView
    private lateinit var spotlightContainer: View
    private lateinit var spotlightInfoBox: View
    private lateinit var spotlightTitle: TextView
    private lateinit var spotlightMeta: TextView
    private lateinit var spotlightOverview: TextView
    private lateinit var spotlightActions: LinearLayout
    private lateinit var spotlightPrev: ImageView
    private lateinit var spotlightNext: ImageView
    private lateinit var bottomSection: View

    // Written on the io thread (decodeWallpaper), read on the UI thread.
    @Volatile private var tmdbTrending: List<TmdbItem> = emptyList()
    @Volatile private var tmdbIndex = 0
    @Volatile private var tmdbFetchedAt = 0L
    private var spotlightApps: List<App>? = null // apps behind the Spotlight buttons; null rebuilds them
    private var tmdbLastFocusApp: String? = null
    private val appChannelDebounce = Runnable { queryAppChannels(tmdbLastFocusApp) }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            appsDirty = true
            i.data?.schemeSpecificPart?.let { GLYPHS.remove(it); ACCENTS.remove(it) } // an update may bring a new icon
        }
    }

    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
            ui.post {
                showNet(caps)
                // Online again (e.g. Wi-Fi coming up after boot): fetch any posters that failed meanwhile.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) retryPosters()
            }
        }

        override fun onLost(n: Network) {
            ui.post { showNet(null) }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Problems.init(File(filesDir, "problems.log"))
        setContentView(R.layout.activity_main)
        appRow = findViewById(R.id.appRow)
        watchRow = findViewById(R.id.watchRow)
        watchTitle = findViewById(R.id.watchTitle)
        watchScroll = findViewById(R.id.watchScroll)
        bottomSection = findViewById(R.id.bottomSection)
        allApps = findViewById(R.id.allApps)
        allAppsGrid = findViewById(R.id.allAppsGrid)
        wifi = findViewById(R.id.wifi)
        spotlightContainer = findViewById(R.id.spotlightContainer)
        spotlightInfoBox = findViewById(R.id.spotlightInfoBox)
        spotlightTitle = findViewById(R.id.spotlightTitle)
        spotlightMeta = findViewById(R.id.spotlightMeta)
        spotlightOverview = findViewById(R.id.spotlightOverview)
        spotlightActions = findViewById(R.id.spotlightActions)
        spotlightPrev = findViewById(R.id.spotlightPrev)
        spotlightNext = findViewById(R.id.spotlightNext)
        applyTheme()
        setupSpotlight() // after applyTheme: its veil and arrow discs use the theme's colours
        loadWallpaper()

        // Top bar: network, All apps, TV inputs, Android settings, launcher settings. Round, lit on focus.
        for ((id, action) in listOf<Pair<Int, () -> Unit>>(
            R.id.wifi to { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            R.id.allAppsButton to { openAllApps() },
            R.id.tvInputsButton to { openInputsMenu(findViewById(R.id.tvInputsButton)) },
            R.id.systemSettings to { openSettings(Settings.ACTION_SETTINGS) },
            R.id.launcherSettings to { unlock { openLauncherSettings() } },
        )) findViewById<ImageView>(id).apply {
            setColorFilter(fg)
            val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            background = ring
            setOnFocusChangeListener { v, focused ->
                if (focused) lastTop = v
                ring.setColor(if (focused) fg else 0)
                setColorFilter(if (focused) onFg else fg)
                animate().scaleX(if (focused) 1.15f else 1f).scaleY(if (focused) 1.15f else 1f)
                    .setDuration(160).setInterpolator(DECELERATE).start()
            }
            setOnClickListener { action() }
        }
        if (prefs.contains("reopen_row")) {
            val row = prefs.getInt("reopen_row", THEME_ROW)
            prefs.edit().remove("reopen_row").apply()
            ui.post { openLauncherSettings(row) }
        } else if (!homeServiceOn() && !prefs.getBoolean("setup_shown", false)) {
            // Fresh install: walk through making Home land on Piume, the one step that has to happen by hand.
            prefs.edit().putBoolean("setup_shown", true).apply()
            ui.post { startHomeSetup() }
        }

        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        // Application context: ConnectivityManager keeps its first instance (and that instance's context) in a
        // static callback handler forever, so an Activity context here leaks the whole first home screen.
        applicationContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, ui)

        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(TV_LISTINGS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ensureHomeService()
        homeSetupResumed()
        if (wantDark() != dark) {
            recreate()
            return
        }
        // Only rebuild what changed, so focus stays where it was when returning from an app.
        if (appsDirty) refreshApps() else if (appRow.childCount == 0) renderApps()
        refreshWatchNext()
        showWeather()
        ui.removeCallbacks(weatherTick)
        ui.post(weatherTick)
        ui.postDelayed(themeTick, 60_000L)
        ui.removeCallbacks(wallpaperTick)
        ui.post(wallpaperTick)
        lastInput = SystemClock.elapsedRealtime() // back from an app: someone is here
        spotlightIdle = false
        restartSpotlightTick()
        // Gallery chosen but its paintings missing (updated from a version that bundled them): fetch them.
        if (galleryOn() && photoScreensaver() == null && !paintingsReady(this)) downloadPaintings {}
        checkUpdate(force = false)
        ui.post { updateBadge() } // after layout, so the icon has its size
    }

    /** Auto theme flips at 7:00 and 19:00 even while the home screen just sits there. */
    private val themeTick = object : Runnable {
        override fun run() {
            // Rebuilding the screen while someone uses it would throw them out of the panel or a half-done move.
            // Wait until it's been left alone for a minute.
            val busy = settingsScrim.visibility == View.VISIBLE || allApps.visibility == View.VISIBLE || editing ||
                setupDialog != null || SystemClock.elapsedRealtime() - lastInput < 60_000L
            if (wantDark() != dark && !busy) recreate() else ui.postDelayed(this, 60_000L)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        if (code != MEDIA_REQUEST) return refreshWatchNext()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) afterPhotoAccess?.invoke()
        else Toast.makeText(this, getString(R.string.toast_photo_access_needed), Toast.LENGTH_LONG).show()
    }

    @Deprecated("Only used for the default-home role request")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        if (code == ROLE_REQUEST) refreshSettings(DEFAULT_HOME_ROW)
        if (code == ROLE_REQUEST && setupStep == 1) {
            setupStep = 0
            afterHomeRole()
        }
    }

    override fun onPause() {
        ui.removeCallbacks(weatherTick)
        ui.removeCallbacks(themeTick)
        ui.removeCallbacks(wallpaperTick)
        ui.removeCallbacks(spotlightTick)
        if (editing) endEdit()
        super.onPause()
    }

    override fun onStop() {
        unlockedUntil = 0L // leaving the home screen locks it again (onPause would also fire for permission dialogs)
        if (homeTest != 0) leftDuringTest = true
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        posters.evictAll()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        applicationContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net)
        getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback)
        upload?.stop()
        io.shutdownNow()
        posterIo.shutdownNow()
        spotlightIo.shutdownNow()
        netIo.shutdownNow()
        super.onDestroy()
    }

    /**
     * Android drops our Home service from the enabled accessibility services whenever the app is
     * force-stopped, and then Home lands on Google TV. Put it back (needs the adb-granted permission),
     * unless the user turned it off.
     */
    private fun ensureHomeService() {
        if (homeServiceOn()) return prefs.edit().remove("home_off").apply() // what Android says wins
        if (prefs.getBoolean("home_off", false) || checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(this, HomeOverride::class.java).flattenToString()
        val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (on.isEmpty()) me else "$on:$me")
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    /** Compared as components: Settings may store us in short form (dev.piume/.HomeOverride). */
    private fun homeServiceOn(): Boolean {
        val me = ComponentName(this, HomeOverride::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun toggleHomeButton() = if (homeServiceOn()) turnOffHomeButton() else turnOnHomeButton()

    /** Off hands Home back to the TV's own launcher, and Piume leaves it off until switched on again. */
    private fun turnOffHomeButton() {
        prefs.edit().putBoolean("home_off", true).apply()
        HomeOverride.running?.disableSelf()
        if (homeServiceOn() && checkSelfPermission(WRITE_SECURE) == PackageManager.PERMISSION_GRANTED) {
            val me = ComponentName(this, HomeOverride::class.java)
            val on = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                on.split(':').filter { ComponentName.unflattenFromString(it) != me }.joinToString(":"))
        }
        if (homeServiceOn()) {
            Toast.makeText(this, getString(R.string.home_off_hint), Toast.LENGTH_LONG).show()
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        renderSettings(HOME_BUTTON_ROW)
    }

    /** The Home key service is an accessibility service, which Android only lets the user switch on. */
    private fun turnOnHomeButton() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) return renderSettings(HOME_BUTTON_ROW)
        // The written path is the safety net: some Google TVs (a Xiaomi stick) answer the Accessibility link
        // with "You don't have an app that can do this", which we can't detect from here.
        Toast.makeText(this, getString(R.string.home_on_hint), Toast.LENGTH_LONG).show()
        openHomeServiceSettings()
    }

    /** False when no Settings screen opened at all. */
    private fun openHomeServiceSettings(): Boolean {
        val service = ComponentName(this, HomeOverride::class.java).flattenToString()
        val tries = listOf(
            // Straight to our own toggle (Android 12+, where the TV supports it), then the list, then Settings.
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, service),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in tries) {
            try {
                startActivity(intent) // not resolveActivity(): Android 11+ hides other apps from that
                return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    private fun isDefaultHome() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)

    /**
     * Asks Android to make us the default home app (its own confirmation dialog). On Google TV the system
     * launcher still wins Home intents, which is why the Home button service exists; on other Android TVs
     * this alone is enough.
     */
    private fun makeDefaultHome() {
        if (canAskForHome()) askForHome()
        else openSettings(Settings.ACTION_HOME_SETTINGS) // Android lets apps ask for Home, not give it back
    }

    /** Android 10+ can show its own "use as home app" dialog; older versions only have the Settings screen. */
    private fun canAskForHome() = Build.VERSION.SDK_INT >= 29 &&
        getSystemService(RoleManager::class.java).let { it.isRoleAvailable(RoleManager.ROLE_HOME) && !it.isRoleHeld(RoleManager.ROLE_HOME) }

    private fun askForHome() {
        if (Build.VERSION.SDK_INT < 29) return
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME), ROLE_REQUEST)
    }

    // ---------- Home setup ----------
    // Popups that try each way of making Home land on Piume, stopping at the first that works: the default-home
    // role, then the Home button service, then adb help for TVs that grey out the service (Android 13+
    // "restricted settings" for sideloaded apps). Each way is checked by asking for a Home press: one that
    // reaches us arrives as a Home intent; one that doesn't sends us to the background.

    private var setupDialog: Dialog? = null
    private var setupStep = 0 // 1 role dialog open, 2 Accessibility settings open
    private var homeTest = 0 // the way being checked (1 role, 2 service) while waiting for a Home press
    private var leftDuringTest = false
    private var previewAt = -1 // debug builds: which popup the preview is on, -1 when not previewing

    private fun startHomeSetup() = if (canAskForHome()) rolePopup() else afterHomeRole()

    private fun afterHomeRole() = if (isDefaultHome() || homeServiceOn()) testPopup(1) else tryHomeService()

    private fun tryHomeService() {
        prefs.edit().remove("home_off").apply()
        ensureHomeService() // works where the adb permission was granted
        if (homeServiceOn()) testPopup(2) else servicePopup()
    }

    /** Back from the role dialog, Accessibility settings, or another home screen after a Home press. */
    private fun homeSetupResumed() {
        if (setupStep == 2) {
            setupStep = 0
            if (homeServiceOn()) testPopup(2) else adbPopup()
        } else if (homeTest != 0 && leftDuringTest) {
            val failed = homeTest
            homeTest = 0
            if (failed == 1) tryHomeService() else adbPopup()
        }
    }

    private fun rolePopup() = homePopup(1, getString(R.string.setup_role_title), getString(R.string.setup_role_body), null,
        getString(R.string.setup_continue) to {
            setupStep = 1
            askForHome()
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    private fun testPopup(way: Int) {
        if (previewAt < 0) {
            homeTest = way
            leftDuringTest = false
        }
        homePopup(way, getString(R.string.setup_test_title), getString(R.string.setup_test_body), null,
            getString(R.string.setup_skip_check) to { homeTest = 0; if (way == 1) tryHomeService() else adbPopup() })
    }

    private fun servicePopup() = homePopup(2, getString(R.string.setup_service_title), getString(R.string.setup_service_body), null,
        getString(R.string.setup_open_accessibility) to {
            setupStep = 2
            if (!openHomeServiceSettings()) {
                setupStep = 0
                adbPopup()
            }
        },
        getString(R.string.setup_not_now) to { setupDialog?.dismiss() })

    /** The one-time grant lets Piume switch the service on itself, past the greyed-out toggle. */
    private fun adbPopup() {
        val mono = Typeface.MONOSPACE
        val code = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (dark) 0xFF09090B.toInt() else 0xFFE9EBEF.toInt())
            }
            listOfNotNull(lanAddress()?.let { "adb connect $it:5555" }, "adb shell pm grant $packageName $WRITE_SECURE").forEach {
                addView(label(it, 12f).apply { typeface = mono; maxLines = 2; ellipsize = null; setPadding(0, dp(2), 0, dp(2)) })
            }
        }
        val qr = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply { setImageBitmap(qrBitmap(HELP_URL, dp(108))) }, LinearLayout.LayoutParams(dp(108), dp(108)))
            addView(label(getString(R.string.setup_scan_help), 11f).apply { alpha = 0.6f; setPadding(0, dp(6), 0, 0) })
        }
        val extra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(code, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(20) })
        }
        homePopup(3, getString(R.string.setup_adb_title), getString(R.string.setup_adb_body), extra,
            getString(R.string.setup_try_again) to { tryHomeService() },
            getString(R.string.setup_close) to { setupDialog?.dismiss() })
    }

    private fun donePopup() = homePopup(0, getString(R.string.setup_done_title),
        getString(if (homeServiceOn()) R.string.setup_done_body_service else R.string.setup_done_body), null,
        getString(R.string.setup_done) to { setupDialog?.dismiss() })

    /** A card in the middle of the screen over the dimmed home screen, in the hold menus' colours. */
    private fun homePopup(step: Int, title: String, body: String, extra: View?, vararg buttons: Pair<String, () -> Unit>) {
        setupDialog?.let { setupDialog = null; it.dismiss() } // replaced, not closed: the dismiss listener leaves the flow alone
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (dark) 0xFF141418.toInt() else 0xFFF7F8FA.toInt())
            }
            if (step > 0) addView(label(getString(R.string.setup_step, step), 12f).apply { alpha = 0.6f })
            addView(label(title, 22f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, 0) })
            addView(label(body, 14f).apply { maxLines = 6; alpha = 0.8f; setLineSpacing(0f, 1.15f); setPadding(0, dp(10), 0, 0) })
            extra?.let { addView(it) }
            buttons.forEachIndexed { i, (name, action) ->
                addView(settingsRow(name, "") { if (previewAt >= 0) previewHomeSetup(previewAt + 1) else action() },
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply { topMargin = if (i == 0) dp(20) else dp(4) })
            }
        }
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(card)
        dialog.window?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(if (extra != null) 600 else 480), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.6f)
        }
        dialog.setOnDismissListener {
            if (setupDialog !== dialog) return@setOnDismissListener
            setupDialog = null // closed with Back or a button: setup stops here, and Settings can start it again
            setupStep = 0
            homeTest = 0
            previewAt = -1
        }
        setupDialog = dialog
        dialog.show()
        card.getChildAt(card.childCount - buttons.size).requestFocus()
    }

    /** Debug builds: every popup in turn, with buttons that only move on, to check the look on a TV. */
    private fun previewHomeSetup(at: Int = 0) {
        previewAt = at
        when (at) {
            0 -> rolePopup()
            1 -> testPopup(1)
            2 -> servicePopup()
            3 -> adbPopup()
            4 -> donePopup()
            else -> setupDialog?.dismiss()
        }
    }

    /** Home pressed while already home: close whatever is open and go back to the start of the apps row. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UPDATE_STATUS) return installStatus(intent)
        if (homeTest != 0 && intent.hasCategory(Intent.CATEGORY_HOME)) { // the Home press reached us: setup works
            homeTest = 0
            return donePopup()
        }
        if (editing) endEdit()
        if (settingsScrim.visibility == View.VISIBLE) closeLauncherSettings()
        if (allApps.visibility == View.VISIBLE) closeAllApps()
        watchScroll.scrollTo(0, 0)
        appRow.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Home screen: Back only closes overlays")
    override fun onBackPressed() {
        when {
            settingsScrim.visibility == View.VISIBLE -> pageBack?.invoke() ?: closeLauncherSettings()
            allApps.visibility == View.VISIBLE -> closeAllApps()
        }
    }

    // ---------- theme ----------

    private fun wantDark() = prefersDark(prefs)

    private fun applyTheme() {
        dark = wantDark()
        fg = if (dark) 0xFFF5F5F7.toInt() else 0xFF111214.toInt()
        // Small controls (top-bar icons, settings and menu rows, Spotlight buttons) show focus by inverting:
        // an fg fill with onFg content. A white-on-near-white or grey-on-black highlight barely reads from the sofa.
        onFg = if (dark) 0xFF111214.toInt() else Color.WHITE
        field = if (dark) 0xFF2A2A32.toInt() else 0xFFE5E7EB.toInt()
        // Dark cards are matte black glass on a true-black ground: lit faintly from the top and fading to
        // near-black, no outline (outlines read as a grey grid). Focus brightens the glass, never to grey.
        // Light cards are frosted grey glass, so they still read on white wallpapers; focus turns them white.
        cardRest = if (dark) intArrayOf(0xFF16161B.toInt(), 0xFF060607.toInt()) else intArrayOf(0xD9E2E5E9.toInt(), 0xD9D8DCE1.toInt())
        cardLit = if (dark) intArrayOf(0xFF26262E.toInt(), 0xFF0C0C0F.toInt()) else intArrayOf(Color.WHITE, Color.WHITE)
        // Must match the colour each wallpaper fades into, or the bottom edge shows a seam.
        ground = if (dark) Color.BLACK else 0xFFF3F5F7.toInt()
        findViewById<View>(R.id.root).setBackgroundColor(ground)
        allApps.setBackgroundColor(ground)
        // A halo in the ground colour: dark text gets a light one, light text a dark one, so either stays readable
        // where it crosses a photo or a TMDB backdrop.
        val textShadow = if (dark) 0x80000000.toInt() else 0xB3FFFFFF.toInt()
        val textRadius = if (dark) 6f else 5f
        for (id in intArrayOf(R.id.clock, R.id.date, R.id.appsTitle, R.id.watchTitle, R.id.allAppsTitle, R.id.rowLabel, R.id.weather, R.id.spotlightTitle, R.id.spotlightMeta, R.id.spotlightOverview)) {
            findViewById<TextView>(id)?.apply {
                setTextColor(fg)
                setShadowLayer(textRadius, 0f, if (dark) 2f else 0f, textShadow)
            }
        }
        wifi.setColorFilter(fg)
        spotlightPrev.setColorFilter(fg)
        spotlightNext.setColorFilter(fg)
        renderSpotlight()
    }

    // ---------- wallpapers ----------
    // Bundled ones have their fade baked in. Your own go in Android/data/dev.piume/files/wallpapers/
    // (adb push, no permission needed) and get the fade painted once when loaded. A folder on the TV (MediaStore,
    // so USB drives too) shows its photos in shuffled turns, one decoded at a time.
    // Each theme remembers its own pick. Decoded off the UI thread, max 1080p (~8 MB). Full colour, not RGB_565:
    // 565 rounds a smooth sky's 1-2 level JPEG ripples into visible grey and green blocks.

    private fun wallpaperDir() = getExternalFilesDir("wallpapers")

    /** A plain choice first (just the theme's base colour, no image), then pictures. */
    private fun wallpaperChoices(forDark: Boolean): List<Pair<String, String>> =
        listOf(plainWallpaper(forDark).let { wallpaperName(it) to it }) +
            BUNDLED_WALLPAPERS.keys.map { wallpaperName("res:$it") to "res:$it" } +
            (if (!prefs.getString("tmdb_api_key", null).isNullOrBlank()) listOf(wallpaperName("tmdb:trending") to "tmdb:trending") else emptyList()) +
            (wallpaperDir()?.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                ?.sortedBy { it.name }?.map { it.nameWithoutExtension to "file:${it.name}" } ?: emptyList()) +
            listOfNotNull(currentWallpaper(forDark).takeIf { it.startsWith("folder:") }?.let { wallpaperName(it) to it })

    // Light gets a soft white (the light ground colour), not #FFFFFF: pure white glares on a TV.
    private fun plainWallpaper(forDark: Boolean) = if (forDark) "plain:Pure black" else "plain:Soft white"

    private fun defaultWallpaper(forDark: Boolean) = if (forDark) "res:Rockies (dusk)" else "res:Rockies (day)"

    private fun tmdbLang() = prefs.getString("tmdb_lang", null) ?: TmdbClient.defaultLanguage()

    private fun currentWallpaper(forDark: Boolean = dark): String {
        val saved = prefs.getString(if (forDark) "wp_dark" else "wp_light", null)
        if (saved != null) return saved
        if (!prefs.getString("tmdb_api_key", null).isNullOrBlank()) {
            return "tmdb:trending"
        }
        return defaultWallpaper(forDark)
    }

    /** Fade your own photos into the background at the bottom. Bundled wallpapers have it baked in either way. */
    private fun wallpaperFade() = prefs.getBoolean("wp_fade", true)

    private fun wallpaperName(choice: String) = when {
        choice == "tmdb:trending" -> getString(R.string.wallpaper_tmdb_trending)
        choice.startsWith("folder:") -> getString(R.string.wallpaper_prefix_folder, choice.substringAfter(':').substringAfter(':'))
        choice == "plain:Pure black" -> getString(R.string.wallpaper_plain_pure_black)
        choice == "plain:Soft white" -> getString(R.string.wallpaper_plain_soft_white)
        choice == "res:Rockies (day)" -> getString(R.string.wallpaper_rockies_day)
        choice == "res:Rockies (dusk)" -> getString(R.string.wallpaper_rockies_dusk)
        else -> choice.substringAfter(':').substringBeforeLast('.')
    }

    private fun loadWallpaper() {
        val view = findViewById<ImageView>(R.id.wallpaper)
        val choice = currentWallpaper()
        if (choice.startsWith("plain:")) { // the root's ground colour shows through
            view.setImageDrawable(null)
            return
        }
        val fallback = defaultWallpaper(dark)
        io.execute {
            val bmp = try {
                decodeWallpaper(choice)
            } catch (e: Exception) {
                null
            } ?: decodeWallpaper(fallback)
            ui.post {
                // The next title's countdown starts once this backdrop is up, so slow loads never pile up.
                if (choice == "tmdb:trending") {
                    restartSpotlightTick()
                    prefetchBackdrops()
                }
                val old = view.drawable
                if (old == null || bmp == null) {
                    view.setImageBitmap(bmp)
                    renderSpotlight()
                    return@post
                }
                // Soft change from one photo to the next; then drop the old one so only one stays in memory.
                val fade = TransitionDrawable(arrayOf(old, BitmapDrawable(resources, bmp))).apply { isCrossFadeEnabled = true }
                view.setImageDrawable(fade)
                fade.startTransition(800)
                ui.postDelayed({ if (view.drawable === fade) view.setImageBitmap(bmp) }, 900)
                renderSpotlight()
            }
        }
    }

    private fun decodeWallpaper(choice: String): Bitmap? {
        BUNDLED_WALLPAPERS[choice.removePrefix("res:")]?.let { res ->
            return BitmapFactory.decodeResource(resources, res)
        }
        if (choice == "tmdb:trending") {
            val key = prefs.getString("tmdb_api_key", null) ?: return null
            val type = prefs.getString("tmdb_type", "all") ?: "all"
            // Trending is per day: refetch now and then, or a home screen that never closes keeps last week's list.
            if (tmdbTrending.isEmpty() || System.currentTimeMillis() - tmdbFetchedAt > TMDB_FRESH_MS) {
                val fresh = TmdbClient.fetchTrending(key, tmdbLang(), type)
                if (fresh.isNotEmpty()) {
                    // A different order each time, so the same top titles don't always open the round.
                    tmdbTrending = fresh.shuffled()
                    tmdbIndex = 0
                    tmdbFetchedAt = System.currentTimeMillis()
                }
            }
            val item = spotlightItem() ?: return null
            val url = TmdbClient.backdropUrl(item.backdropPath) ?: return null
            val file = backdrops.get(url) { openStream(it) }
            val bmp = fitWallpaper { file.inputStream() } ?: return null
            val h = bmp.height.toFloat()
            val base = ground and 0x00FFFFFF
            // Lighter than for photos, so the backdrop shows: the Spotlight text has its own veil and the clock its
            // halo, so the top needs only a faint shade behind the icons, and the bottom fades from halfway, under
            // the apps row, instead of from 28%.
            if (wallpaperFade()) Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
                shader = LinearGradient(0f, h * 0.5f, 0f, h * 0.8f, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
            })
            Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h * 0.16f, Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h * 0.16f, base or 0x66000000, base, Shader.TileMode.CLAMP)
            })
            return bmp
        }
        // fitWallpaper's bitmap is mutable: the fades are painted onto it.
        val bmp = if (choice.startsWith("folder:")) {
            val uri = ContentUris.withAppendedId(PHOTOS, folderPhoto(choice) ?: return null)
            fitWallpaper { contentResolver.openInputStream(uri) ?: throw IOException("gone: $uri") }
        } else {
            val file = File(wallpaperDir(), choice.removePrefix("file:"))
            fitWallpaper { file.inputStream() }
        } ?: return null
        // Same fade as the bundled ones: clear above 28% height, into the background colour by 50%.
        val h = bmp.height.toFloat()
        val base = ground and 0x00FFFFFF
        if (wallpaperFade()) Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h, Paint().apply {
            shader = LinearGradient(0f, h * 0.28f, 0f, h * 0.5f, base, base or 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        })
        // Photos rarely have the bundled ones' empty sky: a soft veil behind the clock and top-bar icons keeps them readable.
        // Strong over the clock and icons (top ~20%), gone by 30% so the photo still shows above the apps.
        Canvas(bmp).drawRect(0f, 0f, bmp.width.toFloat(), h * 0.3f, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.3f, intArrayOf(base or 0xC0000000.toInt(), base or 0xA0000000.toInt(), base),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        return bmp
    }

    /** Wallpapers page, framed by "Add from phone…" first and, once there are photos, "Remove a photo…" last. */
    private fun pickWallpaper(forDark: Boolean) {
        val row = if (forDark) DARK_WP_ROW else LIGHT_WP_ROW
        val choices = wallpaperChoices(forDark)
        val own = choices.filter { it.second.startsWith("file:") }
        val current = currentWallpaper(forDark)
        val rows = listOf(page(getString(R.string.add_from_phone)) { uploadWallpaper(forDark) }, page(getString(R.string.choose_from_tv)) { pickFolder(forDark) }) +
            choices.map { (name, choice) -> page(name, if (choice == current) CHOSEN else "") { setWallpaper(forDark, choice); renderSettings(row) } } +
            if (own.isEmpty()) emptyList() else listOf(page(getString(R.string.remove_photo)) { removeWallpaper(own, forDark) })
        showPage(if (forDark) getString(R.string.setting_dark_wallpaper) else getString(R.string.setting_light_wallpaper), rows, 2 + choices.indexOfFirst { it.second == current }) {
            renderSettings(row)
        }
    }

    private fun setWallpaper(forDark: Boolean, choice: String) {
        prefs.edit().putString(if (forDark) "wp_dark" else "wp_light", choice)
            .remove(photoKey(forDark)).putLong(photoAtKey(forDark), System.currentTimeMillis()).apply()
        if (forDark == dark) loadWallpaper()
    }

    // ---------- folder wallpapers ----------

    private var afterPhotoAccess: (() -> Unit)? = null // what asked, while Android's permission prompt is up
    private val deck = ArrayDeque<Long>() // photos not shown yet this round; io thread only

    private fun photoKey(forDark: Boolean = dark) = if (forDark) "wp_photo_dark" else "wp_photo_light"
    private fun photoAtKey(forDark: Boolean = dark) = if (forDark) "wp_photo_at_dark" else "wp_photo_at_light"
    private fun photoEvery() = prefs.getInt("wp_every", 30)

    private fun photoEveryName(minutes: Int) = when (minutes) {
        1 -> getString(R.string.interval_every_minute)
        5 -> getString(R.string.interval_every_5_min)
        10 -> getString(R.string.interval_every_10_min)
        15 -> getString(R.string.interval_every_15_min)
        30 -> getString(R.string.interval_every_30_min)
        60 -> getString(R.string.interval_every_hour)
        1440 -> getString(R.string.interval_every_day)
        else -> "$minutes min"
    }

    private fun pickPhotoEvery() {
        val rows = PHOTO_EVERY.map { minutes ->
            page(photoEveryName(minutes), if (minutes == photoEvery()) CHOSEN else "") {
                prefs.edit().putInt("wp_every", minutes).apply()
                ui.removeCallbacks(wallpaperTick)
                ui.post(wallpaperTick)
                renderSettings(PHOTO_EVERY_ROW)
            }
        }
        showPage(getString(R.string.setting_folder_photo_changes), rows, PHOTO_EVERY.indexOf(photoEvery())) { renderSettings(PHOTO_EVERY_ROW) }
    }

    /** While the home screen shows, a folder wallpaper moves on to its next photo when its time is up. */
    private val wallpaperTick = object : Runnable {
        override fun run() {
            val every = photoEvery() * 60_000L
            if (!currentWallpaper().startsWith("folder:")) return
            val wait = prefs.getLong(photoAtKey(), 0) + every - System.currentTimeMillis()
            if (wait <= 0) nextPhoto()
            ui.postDelayed(this, if (wait <= 0) every else wait)
        }
    }

    private fun nextPhoto() {
        prefs.edit().remove(photoKey()).putLong(photoAtKey(), System.currentTimeMillis()).apply()
        loadWallpaper()
    }

    /** The folder's photo on show now, or the next one in this round's shuffle. Runs on the io thread. */
    private fun folderPhoto(choice: String): Long? {
        val ids = folderPhotos(contentResolver, choice)
        val all = ids.toHashSet()
        prefs.getLong(photoKey(), -1).takeIf { it in all }?.let { return it }
        deck.removeAll { it !in all } // deleted, or another folder's
        if (deck.isEmpty()) deck.addAll(ids.shuffled())
        return deck.removeFirstOrNull()?.also { prefs.edit().putLong(photoKey(), it).apply() }
    }

    /** Every folder with photos in it, internal storage and USB drives alike: (bucket id, name, photo count). */
    private fun photoFolders(): List<Triple<String, String, Int>> {
        val found = LinkedHashMap<String, Pair<String, Int>>()
        contentResolver.query(PHOTOS, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
            null, null, "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                found[id] = (c.getString(1) ?: getString(R.string.folder_untitled)) to (found[id]?.second ?: 0) + 1
            }
        }
        return found.map { (id, v) -> Triple(id, v.first, v.second) }
    }

    private fun pickFolder(forDark: Boolean) = pickFolder({ pickWallpaper(forDark) }) { choice ->
        setWallpaper(forDark, choice)
        renderSettings(if (forDark) DARK_WP_ROW else LIGHT_WP_ROW)
    }

    /** Folders page for wallpapers and the screensaver; [onPick] gets "folder:<bucket id>:<name>". */
    private fun pickFolder(back: () -> Unit, onPick: (String) -> Unit) {
        if (checkSelfPermission(READ_PHOTOS) != PackageManager.PERMISSION_GRANTED) {
            afterPhotoAccess = { pickFolder(back, onPick) }
            return requestPermissions(arrayOf(READ_PHOTOS), MEDIA_REQUEST)
        }
        val folders = photoFolders()
        if (folders.isEmpty()) {
            return Toast.makeText(this, getString(R.string.toast_no_photos_found), Toast.LENGTH_LONG).show()
        }
        val rows = folders.map { (id, name, n) ->
            val countStr = if (n == 1) getString(R.string.photos_count_singular, n) else getString(R.string.photos_count_plural, n)
            page(name, countStr) { onPick("folder:$id:$name") }
        }
        showPage(getString(R.string.choose_folder_title), rows, back = back)
    }

    private fun removeWallpaper(own: List<Pair<String, String>>, forDark: Boolean) {
        showPage(getString(R.string.remove_photo), own.map { (name, choice) ->
            page(name) {
                showPage(getString(R.string.delete_photo_confirm, name), listOf(
                    page(getString(R.string.dialog_cancel)) { removeWallpaper(own, forDark) },
                    page(getString(R.string.dialog_delete)) {
                        File(wallpaperDir(), choice.removePrefix("file:")).delete()
                        // A theme showing it falls back to its default.
                        prefs.edit().apply {
                            if (prefs.getString("wp_light", null) == choice) remove("wp_light")
                            if (prefs.getString("wp_dark", null) == choice) remove("wp_dark")
                        }.apply()
                        loadWallpaper()
                        pickWallpaper(forDark)
                    },
                )) { removeWallpaper(own, forDark) }
            }
        }) { pickWallpaper(forDark) }
    }

    private var upload: PhoneLink? = null

    /**
     * "Add from phone": a QR code for a page served from this TV. The phone posts a photo, we turn it into a
     * wallpaper and show it at once. The server runs only while this panel is open.
     */
    private fun uploadWallpaper(forDark: Boolean) {
        val dir = wallpaperDir() ?: return
        val status = label(getString(R.string.upload_waiting), 14f)
        val page = PhonePages.file(phoneTexts(R.string.phone_wallpaper_title, R.string.phone_wallpaper_intro), getString(R.string.phone_choose_photo), "image/*")
        showPhoneLink(getString(R.string.add_from_phone_title), PhoneTask(page, 25L * 1024 * 1024) { file ->
            val saved = importWallpaper(file, dir) ?: return@PhoneTask 415 to getString(R.string.phone_not_a_photo)
            ui.post {
                setWallpaper(forDark, "file:${saved.name}")
                status.text = getString(R.string.upload_added_status, saved.nameWithoutExtension)
            }
            200 to getString(R.string.phone_wallpaper_added, saved.nameWithoutExtension)
        }, status) { pickWallpaper(forDark) }
    }

    private fun phoneTexts(title: Int, intro: Int) = PhoneTexts(getString(title), getString(intro),
        getString(R.string.phone_send), getString(R.string.phone_sending), getString(R.string.phone_unreachable))

    /**
     * A settings page with a QR code that opens [task] on a phone on the same Wi-Fi, for anything easier done on a
     * phone than with a remote (a photo, a long key). The link works while the page is open; [status] shows under
     * the code, for the task to report what arrived. [back] leaves the page.
     */
    private fun showPhoneLink(title: String, task: PhoneTask, status: TextView, back: () -> Unit) {
        val ip = lanAddress() ?: return Toast.makeText(this, getString(R.string.toast_connect_network), Toast.LENGTH_LONG).show()
        val link = try {
            PhoneLink(cacheDir, task)
        } catch (e: IOException) {
            return Toast.makeText(this, getString(R.string.toast_upload_server_failed), Toast.LENGTH_LONG).show()
        }
        upload = link
        val url = link.url(ip)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(12))
            addView(ImageView(this@MainActivity).apply { setImageBitmap(qrBitmap(url, dp(200))) })
            addView(label(url, 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) })
            addView(label(getString(R.string.upload_qr_instructions), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
            })
            addView(status.apply { gravity = Gravity.CENTER; maxLines = 2; setPadding(0, dp(12), 0, 0) })
        }
        showPage(title, listOf(page(getString(R.string.dialog_done)) { back() }), top = box, back = back)
        pageExit = {
            link.stop()
            if (upload === link) upload = null
        }
    }

    /** The last problems (newest first), to see what went wrong; readable on a phone too, where lines fit. */
    private fun showProblems() {
        val recent = Problems.recent()
        val rows = listOf(
            page(getString(R.string.problems_on_phone)) { problemsOnPhone() },
            page(getString(R.string.dialog_clear)) { Problems.clear(); showProblems() },
        ) + recent.map { page(it) {} }
        showPage(getString(R.string.setting_problems), rows) { renderSettings(PROBLEMS_ROW) }
    }

    private fun problemsOnPhone() {
        val esc = { t: String -> t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
        val list = Problems.recent().ifEmpty { listOf(getString(R.string.problems_none)) }
        val page = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Piume</title><style>body{font:14px ui-monospace,monospace;margin:0;padding:20px;background:#f7f8fa;color:#111214}
            @media (prefers-color-scheme:dark){body{background:#09090b;color:#f5f5f7}} li{margin:0 0 10px;word-break:break-word}</style></head>
            <body><h1 style="font:600 20px system-ui,sans-serif">${esc(getString(R.string.setting_problems))}</h1><ol>${list.joinToString("") { "<li>${esc(it)}</li>" }}</ol></body></html>"""
        showPhoneLink(getString(R.string.setting_problems), PhoneTask(page, 0) { 405 to "" }, label("", 12f)) { showProblems() }
    }

    /** The TMDB key typed or pasted on a phone: checked with TMDB, then saved like the Save row does. */
    private fun tmdbKeyFromPhone() {
        val status = label(getString(R.string.tmdb_phone_waiting), 14f)
        val page = PhonePages.text(phoneTexts(R.string.phone_tmdb_title, R.string.phone_tmdb_intro), getString(R.string.phone_tmdb_hint))
        showPhoneLink(getString(R.string.setting_tmdb_api_key), PhoneTask(page, 2048) { file ->
            val key = file.readText().trim()
            if (key.length < 16 || key.any { it.isWhitespace() }) return@PhoneTask 400 to getString(R.string.phone_tmdb_refused)
            val ok = TmdbClient.check(key)
            if (ok == false) return@PhoneTask 400 to getString(R.string.phone_tmdb_refused)
            ui.post {
                saveTmdbKey(key)
                status.text = getString(R.string.phone_tmdb_saved)
            }
            200 to getString(if (ok == true) R.string.phone_tmdb_saved else R.string.phone_tmdb_unchecked)
        }, status) { renderSettings(TMDB_KEY_ROW) }
    }

    /** The TV's own address on the home network (IPv4: easy to read and type). */
    private fun lanAddress(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Black on white with the standard 4-module quiet zone, whole pixels per module so it stays sharp. */
    private fun qrBitmap(text: String, maxPx: Int): Bitmap {
        val m = Qr.encode(text)
        val n = m.size + 8
        val px = maxOf(1, maxPx / n)
        val bmp = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { color = Color.BLACK }
        for (r in m.indices) for (c in m.indices) if (m[r][c]) {
            canvas.drawRect(((c + 4) * px).toFloat(), ((r + 4) * px).toFloat(), ((c + 5) * px).toFloat(), ((r + 5) * px).toFloat(), paint)
        }
        return bmp
    }

    // ---------- launcher settings panel (top-bar button; the Settings tile is Android's) ----------

    private val settingsScrim by lazy { findViewById<View>(R.id.settingsScrim) }
    private val settingsList by lazy { findViewById<LinearLayout>(R.id.settingsList) }

    /** Opens the panel on its list of sections, or straight on the section page holding [row]. */
    private fun openLauncherSettings(row: Int? = null) {
        showPanel()
        if (row == null) renderSettingsHome() else renderSettings(row)
    }

    private fun showPanel() {
        panelFrom = currentFocus
        findViewById<View>(R.id.settingsPanel).setBackgroundColor(if (dark) 0xFF09090B.toInt() else 0xFFF7F8FA.toInt())
        findViewById<TextView>(R.id.settingsTitle).setTextColor(fg)
        settingsScrim.visibility = View.VISIBLE
        homeFocusable(false)
    }

    private fun closeLauncherSettings() {
        pageExit?.invoke()
        pageExit = null
        pageBack = null
        settingsScrim.visibility = View.GONE
        homeFocusable(allApps.visibility != View.VISIBLE) // the PIN can open over All apps
        (panelFrom?.takeIf { it.isAttachedToWindow } ?: findViewById(R.id.launcherSettings)).requestFocus()
        panelFrom = null
    }

    private var panelFrom: View? = null // focus to go back to when the panel closes

    // The main page lists sections; each opens a page of its own. A row is addressed as section * SECTION + its
    // position on that page (the *_ROW constants), so whatever a row opens can come back to that row.

    private var shownSection = -1 // section page on screen, -1 for the main list or any other page

    private fun sectionTitle(section: Int) = getString(when (section) {
        LOOK_SECTION -> R.string.settings_section_look
        APPS_SECTION -> R.string.settings_section_apps
        WATCH_SECTION -> R.string.continue_watching
        SPOTLIGHT_SECTION -> R.string.setting_tmdb
        SCREENSAVER_SECTION -> R.string.setting_screensaver
        else -> R.string.settings_section_system
    })

    private fun renderSettingsHome(focus: Int = 0) {
        val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } }
        val summaries = mapOf(
            LOOK_SECTION to themeName(),
            APPS_SECTION to if (lockOn()) getString(R.string.settings_summary_locked) else "",
            WATCH_SECTION to if (cwOff == 0) getString(R.string.cw_apps_all_on) else getString(R.string.cw_apps_count_off, cwOff),
            SPOTLIGHT_SECTION to if (prefs.getString("tmdb_api_key", null).isNullOrBlank()) getString(R.string.state_off) else getString(R.string.state_on),
            SCREENSAVER_SECTION to screensaverValue(),
            // The one thing here that needs doing by hand comes first; otherwise the version.
            SYSTEM_SECTION to if (!homeServiceOn()) getString(R.string.settings_summary_setup) else updatesValue(),
        )
        showPage(getString(R.string.plume_settings), summaries.map { (section, value) ->
            page(sectionTitle(section), value) { renderSettings(section * SECTION) }
        }, focus, back = null)
    }

    /** Shows the section page holding [row], focused on it. */
    private fun renderSettings(row: Int) {
        val section = row / SECTION
        showPage(sectionTitle(section), sectionRows(section), row % SECTION) { renderSettingsHome(section) }
        shownSection = section
    }

    /** Redraws the section page holding [row] for news that arrives later (a download, an update), only if it's on screen. */
    private fun refreshSettings(row: Int) {
        if (settingsScrim.visibility != View.VISIBLE || shownSection != row / SECTION) return
        val at = (0 until settingsList.childCount).firstOrNull { settingsList.getChildAt(it).hasFocus() } ?: (row % SECTION)
        renderSettings(row / SECTION * SECTION + at)
    }

    private fun themeName() = when (prefs.getString("theme", "light")) {
        "light" -> getString(R.string.setting_theme_light)
        "dark" -> getString(R.string.setting_theme_dark)
        else -> getString(R.string.setting_theme_auto)
    }

    private fun screensaverValue() = when {
        downloading -> getString(R.string.screensaver_downloading)
        !galleryOn() -> screensaverName()
        else -> photoScreensaver()?.let { wallpaperName(it) } ?: getString(R.string.screensaver_gallery_label)
    }

    private fun updatesValue() = when {
        updating -> getString(R.string.updates_updating)
        checking -> getString(R.string.updates_checking)
        availableUpdate() != null -> getString(R.string.updates_available_prompt, availableUpdate()!!)
        notesUnseen() -> getString(R.string.whats_new_title, myVersion())
        else -> getString(R.string.updates_up_to_date, myVersion())
    }

    /** Each section's rows, in the order of its *_ROW constants. */
    private fun sectionRows(section: Int): List<Triple<String, String, () -> Unit>> = when (section) {
        LOOK_SECTION -> {
            val theme = prefs.getString("theme", "light")!!
            val weather = prefs.getString("weather", "off")!!
            val unit = if (weather == "off") prefs.getString("wx_unit", "c")!! else weather
            listOf(
                page(getString(R.string.setting_theme), themeName()) {
                    prefs.edit().putString("theme", mapOf("light" to "dark", "dark" to "auto", "auto" to "light")[theme])
                        .putInt("reopen_row", THEME_ROW).apply()
                    recreate() // colours are applied once per activity; reopen the panel after
                },
                page(getString(R.string.setting_light_wallpaper), wallpaperName(currentWallpaper(false))) { pickWallpaper(false) },
                page(getString(R.string.setting_dark_wallpaper), wallpaperName(currentWallpaper(true))) { pickWallpaper(true) },
                page(getString(R.string.setting_photo_fade), toggle(wallpaperFade())) {
                    prefs.edit().putBoolean("wp_fade", !wallpaperFade()).apply()
                    loadWallpaper()
                    renderSettings(FADE_ROW)
                },
                page(getString(R.string.setting_folder_photo_changes), photoEveryName(photoEvery())) { pickPhotoEvery() },
                // "weather" is off/c/f as before; "wx_unit" keeps the unit while it's off.
                page(getString(R.string.setting_weather), toggle(weather != "off")) {
                    if (weather == "off") prefs.edit().putString("weather", unit).apply()
                    else prefs.edit().putString("wx_unit", weather).putString("weather", "off").apply()
                    showWeather()
                    ui.removeCallbacks(weatherTick)
                    ui.post(weatherTick)
                    renderSettings(WEATHER_ROW)
                },
                page(getString(R.string.weather_units), if (unit == "f") "°F" else "°C") {
                    val next = if (unit == "f") "c" else "f"
                    prefs.edit().putString("wx_unit", next).putString("weather", if (weather == "off") "off" else next).apply()
                    showWeather()
                    renderSettings(WEATHER_UNITS_ROW)
                },
                page(getString(R.string.weather_location), if (manualCity()) prefs.getString("geo_city", null) ?: "" else getString(R.string.automatic)) { pickCity() },
            )
        }
        APPS_SECTION -> {
            val sort = prefs.getString("sort", "manual")!!
            val rowHidden = rowHiddenSet().count { pkg -> apps.any { it.pkg == pkg } } // uninstalled ones don't count
            val names = rowNames()
            val colour = colourIcons()
            listOf(
                page(getString(R.string.edit_apps_row)) { closeLauncherSettings(); startEdit(grab = null) },
                page(getString(R.string.setting_sort_apps), if (sort == "manual") getString(R.string.setting_sort_manual) else getString(R.string.setting_sort_usage)) {
                    prefs.edit().putString("sort", if (sort == "manual") "usage" else "manual").apply()
                    renderApps()
                    renderSettings(SORT_ROW)
                },
                page(getString(R.string.setting_apps_in_row), if (rowHidden == 0) getString(R.string.apps_in_row_all) else getString(R.string.apps_in_row_count, apps.size - rowHidden, apps.size)) { pickRowApps() },
                page(getString(R.string.row_position), getString(ROW_ALIGN[rowAlign()]!!.first)) {
                    prefs.edit().putString("row_align", ROW_ALIGN.keys.let { it.elementAt((it.indexOf(rowAlign()) + 1) % it.size) }).apply()
                    alignRow()
                    renderSettings(ALIGN_ROW)
                },
                page(getString(R.string.setting_app_names), when (names) {
                    "always" -> getString(R.string.app_names_always)
                    "never" -> getString(R.string.app_names_never)
                    else -> getString(R.string.app_names_focus)
                }) {
                    prefs.edit().putString("names", mapOf("focus" to "always", "always" to "never", "never" to "focus")[names]).apply()
                    renderApps()
                    renderSettings(NAMES_ROW)
                },
                page(getString(R.string.setting_icon_style), if (colour) getString(R.string.icon_style_colour) else getString(R.string.icon_style_monochrome)) {
                    prefs.edit().putBoolean("colour_icons", !colour).apply()
                    renderApps()
                    renderSettings(ICONS_ROW)
                },
                page(getString(R.string.setting_lock_layout), toggle(lockOn())) {
                    if (!lockOn()) return@page setLock()
                    prefs.edit().remove("lock_pin").apply()
                    renderSettings(LOCK_ROW)
                },
            )
        }
        WATCH_SECTION -> {
            val hidden = prefs.getStringSet("hidden_watch", emptySet())!!.size
            val cwOff = prefs.getStringSet("cw_off", emptySet())!!.count { pkg -> apps.any { it.pkg == pkg } }
            listOf(
                page(getString(R.string.setting_cw_apps), if (cwOff == 0) getString(R.string.cw_apps_all_on) else getString(R.string.cw_apps_count_off, cwOff)) { pickWatchSources() },
                page(getString(R.string.setting_restore_hidden_shows), if (hidden == 0) getString(R.string.hidden_shows_none) else getString(R.string.hidden_shows_count, hidden)) {
                    prefs.edit().remove("hidden_watch").apply()
                    watchKey = null
                    refreshWatchNext()
                    renderSettings(HIDDEN_ROW)
                },
            )
        }
        SPOTLIGHT_SECTION -> spotlightRows()
        SCREENSAVER_SECTION -> listOf(
            page(getString(R.string.setting_screensaver), screensaverValue()) { pickScreensaver() },
            page(getString(R.string.setting_test_screensaver)) { testScreensaver() },
            page(getString(R.string.setting_screensaver_timing)) { openScreensaverSettings() },
        )
        else -> listOfNotNull(
            page(getString(R.string.setup_home)) { closeLauncherSettings(); startHomeSetup() },
            page(getString(R.string.setting_home_button), toggle(homeServiceOn())) { toggleHomeButton() },
            page(getString(R.string.setting_default_launcher), if (isDefaultHome()) getString(R.string.plume_press_change) else getString(R.string.default_launcher_set_prompt)) { makeDefaultHome() },
            page(getString(R.string.setting_updates), updatesValue()) { showUpdates() },
            page(getString(R.string.setting_problems), Problems.recent().size.let { if (it == 0) getString(R.string.problems_none) else "$it" }) { showProblems() },
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) null
            else page(getString(R.string.preview_home_setup)) { closeLauncherSettings(); previewHomeSetup() },
        )
    }

    private fun spotlightRows(): List<Triple<String, String, () -> Unit>> {
        val hasKey = !prefs.getString("tmdb_api_key", null).isNullOrBlank()
        val lang = tmdbLang()
        val type = prefs.getString("tmdb_type", "all") ?: "all"
        val typeName = when (type) {
            "movie" -> getString(R.string.tmdb_type_movie)
            "tv" -> getString(R.string.tmdb_type_tv)
            else -> getString(R.string.tmdb_type_all)
        }
        val offSet = prefs.getStringSet("tmdb_default_apps", emptySet()) ?: emptySet()
        val availableApps = apps.filter { !it.pkg.startsWith(HDMI) && it.pkg != CW_PKG }
        val activeCount = availableApps.count { it.pkg !in offSet }
        val every = spotlightEvery()
        return listOf(
            page(getString(R.string.setting_tmdb_api_key), if (hasKey) getString(R.string.tmdb_api_key_set) else getString(R.string.tmdb_api_key_empty)) {
                configureTmdbApiKey()
            },
            page(getString(R.string.setting_tmdb_language), lang) {
                val langs = TmdbClient.LANGUAGES
                showPage(getString(R.string.setting_tmdb_language), langs.map { l ->
                    page(l, if (l == lang) CHOSEN else "") {
                        prefs.edit().putString("tmdb_lang", l).apply()
                        tmdbTrending = emptyList()
                        loadWallpaper()
                        renderSettings(TMDB_LANG_ROW)
                    }
                }, langs.indexOf(lang)) { renderSettings(TMDB_LANG_ROW) }
            },
            page(getString(R.string.setting_tmdb_media_type), typeName) {
                val types = listOf("all" to getString(R.string.tmdb_type_all), "movie" to getString(R.string.tmdb_type_movie), "tv" to getString(R.string.tmdb_type_tv))
                showPage(getString(R.string.setting_tmdb_media_type), types.map { (k, name) ->
                    page(name, if (k == type) CHOSEN else "") {
                        prefs.edit().putString("tmdb_type", k).apply()
                        tmdbTrending = emptyList()
                        loadWallpaper()
                        renderSettings(TMDB_TYPE_ROW)
                    }
                }, types.indexOfFirst { it.first == type }) { renderSettings(TMDB_TYPE_ROW) }
            },
            page(getString(R.string.setting_tmdb_default_apps), if (offSet.isEmpty()) getString(R.string.cw_apps_all_on) else "$activeCount") {
                val appChoices = availableApps.map { it.label to it.pkg }
                toggles(getString(R.string.setting_tmdb_default_apps), appChoices, "tmdb_default_apps", getString(R.string.state_on), getString(R.string.state_off)) {
                    spotlightApps = null
                    renderSpotlight()
                    renderSettings(TMDB_APPS_ROW)
                }
            },
            page(getString(R.string.setting_tmdb_providers), toggle(providersOn())) {
                prefs.edit().putBoolean("tmdb_providers", !providersOn()).apply()
                spotlightItem()?.let { showProviders(it) }
                renderSettings(TMDB_PROVIDERS_ROW)
            },
            page(getString(R.string.setting_tmdb_cycle), cycleName(every)) {
                showPage(getString(R.string.setting_tmdb_cycle), SPOTLIGHT_EVERY.map { n ->
                    page(cycleName(n), if (n == every) CHOSEN else "") {
                        prefs.edit().putInt("tmdb_cycle", n).apply()
                        restartSpotlightTick()
                        renderSettings(TMDB_CYCLE_ROW)
                    }
                }, SPOTLIGHT_EVERY.indexOf(every)) { renderSettings(TMDB_CYCLE_ROW) }
            },
        )
    }

    private fun cycleName(seconds: Int) = if (seconds == 0) getString(R.string.tmdb_cycle_off) else getString(R.string.tmdb_cycle_seconds, seconds)

    /** A new TMDB key turns Spotlight on, as both wallpapers. */
    private fun saveTmdbKey(key: String) {
        prefs.edit().putString("tmdb_api_key", key).apply()
        tmdbTrending = emptyList()
        setWallpaper(false, "tmdb:trending")
        setWallpaper(true, "tmdb:trending")
    }

    private fun configureTmdbApiKey() {
        val currentKey = prefs.getString("tmdb_api_key", "") ?: ""
        val input = EditText(this).apply {
            setText(currentKey)
            hint = getString(R.string.tmdb_api_key_hint)
            setTextColor(fg)
            setHintTextColor(if (dark) 0x66FFFFFF else 0x66000000)
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(field)
                setStroke(dp(1), fieldEdge())
            }
            background = bg
            // Same focus mark as the PIN boxes: otherwise only a blinking cursor tells you you're typing here.
            setOnFocusChangeListener { _, focused -> bg.setStroke(if (focused) dp(2) else dp(1), if (focused) fg else fieldEdge()) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(input)
        }
        val rows = listOf(
            // Typing a 32-character key (or a 200-character token) with a remote is no fun: paste it on a phone.
            page(getString(R.string.setting_tmdb_key_phone)) { tmdbKeyFromPhone() },
            page(getString(R.string.dialog_save)) {
                val key = input.text?.toString()?.trim()
                if (!key.isNullOrBlank()) saveTmdbKey(key)
                renderSettings(TMDB_KEY_ROW)
            },
            page(getString(R.string.dialog_clear)) {
                prefs.edit().remove("tmdb_api_key").apply()
                tmdbTrending = emptyList()
                spotlightIo.execute { backdrops.clear() } // no Spotlight any more: give the space back
                if (currentWallpaper() == "tmdb:trending") {
                    setWallpaper(dark, defaultWallpaper(dark))
                }
                renderSettings(TMDB_KEY_ROW)
            }
        )
        showPage(getString(R.string.setting_tmdb_api_key), rows, top = box, back = { renderSettings(TMDB_KEY_ROW) })
        input.requestFocus()
    }

    // Pages inside the settings panel (wallpaper list, app toggles…) instead of system dialogs, so they
    // match Piume's look. Back goes up one page; from the main list it closes the panel.

    private var pageBack: (() -> Unit)? = null
    private var pageExit: (() -> Unit)? = null // cleanup when the page goes away (stops the upload server)

    private fun page(title: String, value: String = "", action: () -> Unit) = Triple(title, value, action)

    private fun showPage(title: String, rows: List<Triple<String, String, () -> Unit>>, focus: Int = 0, top: View? = null, back: (() -> Unit)?) {
        pageExit?.invoke()
        pageExit = null
        pageBack = back
        shownSection = -1 // renderSettings sets it again for section pages
        findViewById<TextView>(R.id.settingsTitle).text = title
        settingsList.removeAllViews()
        top?.let { settingsList.addView(it) }
        val first = settingsList.childCount
        rows.forEach { (name, value, action) ->
            settingsList.addView(settingsRow(name, value, action), LinearLayout.LayoutParams(MATCH_PARENT, dp(44)).apply {
                topMargin = dp(4)
            })
        }
        // Keep focus inside the panel: Up from the first row and Down from the last go nowhere.
        (settingsList.parent as View).scrollTo(0, 0)
        if (rows.isEmpty()) return // a page that's all [top], like the PIN boxes, focuses itself
        settingsList.getChildAt(first).let { it.nextFocusUpId = it.id }
        settingsList.getChildAt(first + rows.size - 1).let { it.nextFocusDownId = it.id }
        settingsList.getChildAt(first + focus.coerceIn(0, rows.size - 1)).requestFocus()
    }

    // ---------- layout lock ----------
    // A PIN in front of Piume settings and everything that changes the home screen; apps still open as usual.
    // It keeps the layout safe from small hands, it doesn't keep apps from kids: voice search and Android
    // settings get past it, so it's called a layout lock, not parental controls.

    private fun lockOn() = prefs.contains("lock_pin")

    private var unlockedUntil = 0L // one PIN covers a few minutes of changes, until the home screen is left
    private var pinFails = 0
    private var pinWaitUntil = 0L // ponytail: in memory, so restarting Piume resets the wait; fine for kids at a remote
    private var pinBox: View? = null

    private fun locked() = lockOn() && SystemClock.elapsedRealtime() > unlockedUntil

    /** Runs [then] at once when unlocked, otherwise after the right PIN. */
    private fun unlock(then: () -> Unit) {
        if (!locked()) return then()
        showPanel()
        pinPage(getString(R.string.pin_enter_title), back = null) { pin ->
            val now = SystemClock.elapsedRealtime()
            if (now < pinWaitUntil) return@pinPage getString(R.string.pin_too_many_tries_wait, (pinWaitUntil - now) / 1000 + 1)
            if (!pinMatches(prefs.getString("lock_pin", null)!!, pin)) {
                if (++pinFails < PIN_TRIES) return@pinPage getString(R.string.pin_wrong)
                pinFails = 0
                pinWaitUntil = now + 30_000L
                return@pinPage getString(R.string.pin_too_many_tries_wait, 30)
            }
            pinFails = 0
            unlockedUntil = now + UNLOCKED_MS
            closeLauncherSettings()
            then()
            null
        }
    }

    private fun setLock(): Unit = pinPage(getString(R.string.pin_choose_title), { renderSettings(LOCK_ROW) }) { first ->
        pinPage(getString(R.string.pin_enter_again_title), { renderSettings(LOCK_ROW) }) { again ->
            if (again != first) {
                Toast.makeText(this, getString(R.string.pin_mismatch), Toast.LENGTH_LONG).show()
                setLock()
                return@pinPage null
            }
            prefs.edit().putString("lock_pin", hashPin(first)).apply()
            unlockedUntil = SystemClock.elapsedRealtime() + UNLOCKED_MS // no PIN straight away for the one who set it
            renderSettings(LOCK_ROW)
            Toast.makeText(this, getString(R.string.layout_locked_toast), Toast.LENGTH_LONG).show()
            null
        }
        null
    }

    /**
     * Four digit boxes: ▲ ▼ change a digit, ◀ ▶ move between boxes, number keys type, OK enters.
     * [done] gets the PIN and returns null when it's accepted, or a message to show before trying again.
     */
    private fun pinPage(title: String, back: (() -> Unit)?, done: (String) -> String?) {
        val digits = arrayOfNulls<Int>(4)
        var at = 0
        val boxes = List(4) { label("", 24f).apply { gravity = Gravity.CENTER } }
        val status = label("", 13f).apply { gravity = Gravity.CENTER; alpha = 0.6f }
        val row = LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
            boxes.forEach { addView(it, LinearLayout.LayoutParams(dp(48), dp(56)).apply { marginStart = dp(6); marginEnd = dp(6) }) }
        }
        fun draw() = boxes.forEachIndexed { i, b ->
            // Only the box being changed shows its digit.
            b.text = if (digits[i] == null) "" else if (i == at) digits[i].toString() else "•"
            b.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(field)
                setStroke(if (i == at) dp(2) else dp(1), if (i == at) fg else fieldEdge())
            }
        }
        fun submit() {
            val error = done(digits.joinToString("")) ?: return
            digits.fill(null)
            at = 0
            draw()
            status.text = error
            ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, -dp(12).toFloat(), dp(12).toFloat(), -dp(8).toFloat(), dp(8).toFloat(), 0f).setDuration(300).start()
        }
        row.setOnKeyListener { _, code, e ->
            val digit = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> null
            }
            if (digit == null && code !in PIN_KEYS) return@setOnKeyListener false // Back still leaves
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> digits[at] = ((digits[at] ?: 0) + 1) % 10
                KeyEvent.KEYCODE_DPAD_DOWN -> digits[at] = ((digits[at] ?: 0) + 9) % 10
                KeyEvent.KEYCODE_DPAD_LEFT -> at = maxOf(0, at - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (digits[at] != null) at = minOf(3, at + 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    if (digits.all { it != null }) return@setOnKeyListener true.also { submit() }
                    else if (digits[at] != null) at = minOf(3, at + 1)
                else -> {
                    digits[at] = digit
                    if (at == 3) return@setOnKeyListener true.also { draw(); submit() }
                    at++
                }
            }
            draw()
            true
        }
        draw()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
            addView(row)
            addView(status.apply { setPadding(0, dp(12), 0, 0) })
            addView(label(getString(R.string.pin_instructions), 12f).apply {
                maxLines = 2
                gravity = Gravity.CENTER
                alpha = 0.6f
                setPadding(0, dp(8), 0, 0)
            })
            if (lockOn()) addView(label(getString(R.string.pin_forgot_notice), 12f).apply {
                gravity = Gravity.CENTER
                alpha = 0.6f
                maxLines = 3
                setPadding(0, dp(24), 0, 0)
            })
        }
        showPage(title, emptyList(), top = box, back = back)
        pinBox = row
        row.requestFocus()
    }

    /** A faint outline for input boxes: their fill alone sits too close to the panel's colour to find at a glance. */
    private fun fieldEdge() = (fg and 0xFFFFFF) or 0x40000000

    private fun settingsRow(title: String, value: String, action: () -> Unit): View {
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0)).apply { cornerRadius = dp(8).toFloat() }
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = bg
            isFocusable = true
            isClickable = true
            val name = label(title, 14f)
            val toggled = value == TOGGLE_ON || value == TOGGLE_OFF
            val state = label(if (toggled) "" else value, 13f).apply { alpha = 0.6f }
            addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun showSwitch(lit: Boolean) {
                if (childCount > 1) removeViewAt(1)
                addView(switch(value == TOGGLE_ON, lit), LinearLayout.LayoutParams(dp(36), dp(20)))
            }
            if (toggled) showSwitch(false) else addView(state)
            setOnClickListener { action() }
            setOnFocusChangeListener { _, focused ->
                bg.colors = if (focused) intArrayOf(fg, fg) else intArrayOf(0, 0)
                name.setTextColor(if (focused) onFg else fg)
                state.setTextColor(if (focused) onFg else fg)
                state.alpha = if (focused) 0.75f else 0.6f
                if (toggled) showSwitch(focused) // redrawn in the inverted colours on the lit row
            }
        }
    }

    private fun toggle(on: Boolean) = if (on) TOGGLE_ON else TOGGLE_OFF

    /**
     * A mono switch for on/off rows: filled track with the knob right when on, outlined and left when off.
     * [lit] draws it for a focused row, whose fill is fg: then onFg is the ink and the knob takes the row's colour.
     */
    private fun switch(on: Boolean, lit: Boolean = false) = FrameLayout(this).apply {
        val ink = if (lit) onFg else fg
        val faint = ink and 0xFFFFFF or 0x66000000
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (on) setColor(ink) else setStroke(dp(2), faint)
        }
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (!on) faint else if (lit) fg else if (dark) 0xFF09090B.toInt() else 0xFFF7F8FA.toInt())
            }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL or if (on) Gravity.END else Gravity.START).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun showNet(caps: NetworkCapabilities?) {
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        wifi.setImageResource(if (ethernet) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        // Dim only the icon: the focus ring is the background and should stay full strength.
        wifi.imageAlpha = if (caps == null) 90 else 255
    }

    private fun openSettings(action: String, data: Uri? = null) {
        try {
            startActivity(Intent(action, data))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ---------- apps row ----------

    /** Labels and icons come from each app's resources, ~200 ms on this TV: done off the UI thread. */
    private fun refreshApps() {
        appsDirty = false
        io.execute {
            val list = try {
                loadApps()
            } catch (e: Exception) {
                return@execute
            }
            ui.post {
                apps = list
                APPS = list
                renderApps()
                spotlightApps = null
                renderSpotlight()
            }
        }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != TV_SETTINGS }
            .distinctBy { it.packageName }
            .map { a ->
                App(
                    a.packageName,
                    a.loadLabel(pm).toString(),
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setComponent(ComponentName(a.packageName, a.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    a.loadIcon(pm),
                )
            } + consoleInputs()
    }

    /**
     * Devices on HDMI that name themselves over CEC (the PS5 shows up as "PlayStation 5"), as app tiles.
     * Opening one switches the TV to that HDMI input, which also wakes the device over CEC.
     */
    private fun consoleInputs(): List<App> = try {
        getSystemService(TvInputManager::class.java).tvInputList
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId != null }
            .map { input ->
                val label = input.loadLabel(this).toString()
                val ps = label.contains("PlayStation", ignoreCase = true)
                App(
                    HDMI + input.parentId,
                    label,
                    Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(input.parentId))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    if (ps) getDrawable(R.drawable.logo_playstation)!! else input.loadIcon(this) ?: getDrawable(R.drawable.ic_apps)!!,
                )
            }
            .distinctBy { it.pkg }
    } catch (e: Exception) {
        emptyList()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(id: String) = refreshApps()
        override fun onInputRemoved(id: String) = refreshApps()
    }

    private fun sortedApps(): List<App> {
        // Streaming apps first by default, then alphabetical; manual order or usage overrides.
        val byDefault = compareBy<App> { DEFAULT_ORDER.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() }
        if (prefs.getString("sort", "manual") == "usage") {
            return apps.sortedWith(compareByDescending<App> { prefs.getInt("n_" + it.pkg, 0) }.then(byDefault))
        }
        val order = prefs.getString("order", "")!!.split(',')
        // A console not yet placed by hand goes first, so it's visible without scrolling.
        return apps.sortedWith(compareBy<App> {
            order.indexOf(it.pkg).let { i -> if (i >= 0) i else if (it.pkg.startsWith(HDMI)) -1 else Int.MAX_VALUE }
        }.then(byDefault))
    }

    /**
     * Every app, logo only, on the same 8-column grid as the Continue Watching cards (one card = two
     * tiles), so 8 show at once and the row scrolls for the rest. All apps and Settings are in the top bar.
     */
    private fun rowAlign() = prefs.getString("row_align", "left")!!.takeIf { it in ROW_ALIGN } ?: "left"

    /**
     * A row shorter than the screen sits left, centred or right. A longer row always starts at the left:
     * centred, the scroller would push its first tiles out of reach.
     */
    private fun alignRow() {
        val content = appRow.parent as View
        (content.layoutParams as FrameLayout.LayoutParams).gravity =
            if (appTileCount() > ROW_TILES) Gravity.START else ROW_ALIGN[rowAlign()]!!.second
        content.requestLayout()
    }

    private fun renderApps() {
        val focusedTag = currentFocus?.tag
        // Rebuilds can happen in the background (an app or HDMI device added); only take focus if the row had it.
        val takeFocus = currentFocus == null || currentFocus?.parent === appRow
        val gap = dp(GRID_GAP)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * (ROW_TILES - 1)) / ROW_TILES
        appRow.removeAllViews()
        val hidden = rowHiddenSet()
        // Fixed first tile: "Continue Watching" hub
        val cwDrawable = getDrawable(R.drawable.ic_continue_watching) ?: getDrawable(R.drawable.ic_apps)!!
        // Tinted with the theme's foreground like the other marks: a white disc vanishes on the light theme's white focused card.
        val cwTile = tile(null, cwDrawable, mono = true, CW_PKG) {
            watchTitle.text = getString(R.string.continue_watching)
            tmdbLastFocusApp = null
            queryAppChannels(null)
        }.apply {
            contentDescription = getString(R.string.continue_watching)
        }

        val appTiles = sortedApps().filter { it.pkg !in hidden }.map { appTile(it, labeled = false) }
        val tiles = listOf(cwTile) + appTiles
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            appRow.addView(t, LinearLayout.LayoutParams(width, dp(76)).apply { if (i > 0) marginStart = gap })
            val own = t.onFocusChangeListener
            t.setOnFocusChangeListener { v, focused ->
                own?.onFocusChange(v, focused)
                if (focused) {
                    lastApp = v
                    val pkg = v.tag as? String
                    if (pkg == CW_PKG) {
                        watchTitle.text = getString(R.string.continue_watching)
                        tmdbLastFocusApp = null
                        ui.removeCallbacks(appChannelDebounce)
                        ui.postDelayed(appChannelDebounce, 200)
                    } else if (pkg != null && !pkg.startsWith(HDMI)) {
                        tmdbLastFocusApp = pkg
                        ui.removeCallbacks(appChannelDebounce)
                        ui.postDelayed(appChannelDebounce, 300)
                    }
                }
                showRowLabel(v, focused)
            }
        }
        showNames()
        alignRow()
        if (takeFocus) (tiles.firstOrNull { it.tag == focusedTag } ?: tiles[0]).requestFocus()
        if (!prefs.getBoolean("tip_move", false)) {
            prefs.edit().putBoolean("tip_move", true).apply()
            Toast.makeText(this, getString(R.string.tip_rearrange_row), Toast.LENGTH_LONG).show()
        }
    }

    /** Home row names: "focus" (under the focused tile only), "always" (in every tile) or "never". */
    private fun rowNames() = prefs.getString("names", "focus")!!

    /** Each app's own shipped icon instead of the mono mark, in the row and All apps. */
    private fun colourIcons() = prefs.getBoolean("colour_icons", false)

    /** The home row shows marks only by default (see rowNames); the All apps grid always labels them. */
    private fun appTile(a: App, labeled: Boolean = true): View {
        val logo = LOGOS[a.pkg]
        val custom = customIcon(a.pkg)
        val name = a.label.takeIf { labeled }
        // Colour mode keeps the same marks, tinted with the brand colour (or the colour the app's icon is mostly).
        val colour = colourIcons()
        val t = when {
            custom != null -> tile(name, custom, false, a.pkg) { launch(a) }
            a.pkg.startsWith(HDMI) -> tile(name, a.icon, true, a.pkg,
                tint = BRAND_COLOURS["playstation"]?.takeIf { colour && a.label.contains("PlayStation", ignoreCase = true) }) { launch(a) }
            logo != null -> tile(name.takeIf { !logo.second }, getDrawable(logo.first)!!, true, a.pkg,
                wordmarkInset = if (!logo.second) null else dp(if (labeled) 28 else 14),
                tint = BRAND_COLOURS[a.pkg]?.takeIf { colour }) { launch(a) }
            else -> cachedGlyph(a)?.let { tile(name, it, true, a.pkg, tint = if (colour) cachedAccent(a) else null) { launch(a) } }
                ?: if (colour) tile(name, a.icon.constantState?.newDrawable(resources) ?: a.icon, false, a.pkg) { launch(a) }
                // A copy: greying the shared icon itself would keep it grey after switching to colour.
                else tile(name, (a.icon.constantState?.newDrawable(resources) ?: a.icon).mutate().apply { colorFilter = GREYSCALE }, false, a.pkg) { launch(a) }
        }
        t.contentDescription = a.label
        return t
    }

    /** "App names: Always": a name under every tile, same width and gap as the tiles, so they line up. */
    private fun showNames() {
        val row = findViewById<LinearLayout>(R.id.nameRow)
        val always = rowNames() == "always"
        row.visibility = if (always) View.VISIBLE else View.GONE
        findViewById<View>(R.id.rowLabel).visibility = if (always) View.GONE else View.VISIBLE
        row.removeAllViews()
        if (!always) return
        for (i in 0 until appRow.childCount) {
            val t = appRow.getChildAt(i)
            row.addView(label(t.contentDescription?.toString().orEmpty(), 13f).apply {
                gravity = Gravity.CENTER
                alpha = if (dark) 0.85f else 1f
            }, LinearLayout.LayoutParams(t.layoutParams as LinearLayout.LayoutParams).apply { height = MATCH_PARENT })
        }
    }

    /** Slides the shared name label under the focused row tile; fades it out once focus leaves the row. */
    private fun showRowLabel(v: View, focused: Boolean) {
        val label = findViewById<TextView>(R.id.rowLabel)
        val inWatchRow = currentFocus?.getTag(R.id.watchRow) is Prog
        if (focused && !editing && rowNames() == "focus") {
            label.text = v.contentDescription
            // Posted: on first render focus lands before layout, when every width is still 0.
            v.post { label.translationX = v.left + v.width / 2f - label.width / 2f }
            label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
            // Reset app tiles alpha
            for (i in 0 until appRow.childCount) appRow.getChildAt(i).alpha = 1f
        } else if (inWatchRow && !editing) {
            // Keep the selected app name visible, and dim the non-selected app tiles
            val activeTile = lastApp?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
            if (activeTile != null && rowNames() == "focus") {
                label.text = activeTile.contentDescription
                activeTile.post { label.translationX = activeTile.left + activeTile.width / 2f - label.width / 2f }
                label.animate().alpha(if (dark) 0.85f else 1f).setDuration(160).start()
            }
            for (i in 0 until appRow.childCount) {
                val child = appRow.getChildAt(i)
                child.animate().alpha(if (child === activeTile) 1f else 0.45f).setDuration(160).start()
            }
        } else {
            ui.post {
                if (currentFocus?.parent !== appRow && currentFocus?.getTag(R.id.watchRow) !is Prog || editing) {
                    label.animate().alpha(0f).setDuration(120).start()
                    for (i in 0 until appRow.childCount) appRow.getChildAt(i).animate().alpha(1f).setDuration(160).start()
                }
            }
        }
    }

    /** White card: logo on top, name underneath. With no label the mark sits alone, centred, at one size. */
    private fun tile(label: String?, icon: Drawable, mono: Boolean, tag: String, wordmarkInset: Int? = null, tint: Int? = null, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val img = ImageView(this).apply {
            setImageDrawable(icon.mutate())
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (mono) setColorFilter(tint?.let { readable(it) } ?: fg)
        }
        val text = label?.let {
            label(it, 13f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(7), dp(6), 0)
            }
        }
        // Same card as light mode; in dark the marks rest slightly dimmed and brighten with focus.
        val restLogo = if (dark) 0.8f else 1f
        val restText = if (dark) 0.65f else 1f
        img.alpha = restLogo
        text?.alpha = restText
        style(col, dp(8), card = true) { focused ->
            if (!dark) return@style
            img.animate().alpha(if (focused) 1f else restLogo).setDuration(160).start()
            text?.animate()?.alpha(if (focused) 1f else restText)?.setDuration(160)?.start()
        }
        if (text == null) {
            // Icons share one height. Wordmarks sit in a square viewport, so fit them by width instead
            // (crop the empty top and bottom) or they shrink to a sliver.
            if (wordmarkInset != null) {
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.setPadding(wordmarkInset, 0, wordmarkInset, 0)
            } else {
                img.setPadding(dp(16), dp(20), dp(16), dp(20))
            }
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        } else {
            col.addView(img, LinearLayout.LayoutParams(MATCH_PARENT, dp(26)))
            col.addView(text, LinearLayout.LayoutParams(MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        col.tag = tag
        col.setOnClickListener { onClick() }
        return col
    }

    /**
     * A mono mark from an adaptive icon's foreground layer (its symbol, without the coloured backplate),
     * cropped to the safe zone. Brightness becomes opacity: the light symbol becomes the mark, and dark
     * details in it (a note inside a circle) become holes, so the mono tint keeps them. Null for old
     * single-layer icons and for photo-like layers that fill the square, which keep the greyscale look.
     */
    private fun glyph(icon: Drawable): Bitmap? {
        val fg = (icon as? AdaptiveIconDrawable)?.foreground ?: return null
        val size = dp(96) // large, so a small symbol is still sharp after trimming and scaling up
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Layers are 108dp with the symbol inside the middle 72dp; drawing at 1.5x crops to that.
        fg.setBounds(-size / 4, -size / 4, size * 5 / 4, size * 5 / 4)
        fg.draw(Canvas(bmp))
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        if (px.count { Color.alpha(it) > 200 } > px.size * 0.7) return null // a picture, not a symbol
        val alpha = FloatArray(px.size) { i ->
            val c = px[i]
            Color.alpha(c) * (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
        }
        val peak = alpha.max().takeIf { it > 0f } ?: return null
        // Stretch so the brightest part is fully opaque: an orange symbol shouldn't look dimmer than a white one.
        for (i in px.indices) px[i] = Color.argb((alpha[i] * 255f / peak).toInt().coerceAtMost(255), 255, 255, 255)
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        // Trim to the visible mark (as a square, centred), so small symbols fill the tile like the logos do.
        val on = px.indices.filter { Color.alpha(px[it]) > 40 }.ifEmpty { return null }
        val l = on.minOf { it % size }; val r = on.maxOf { it % size }
        val t = on.minOf { it / size }; val b = on.maxOf { it / size }
        val side = maxOf(r - l, b - t) + 1
        val x = ((l + r + 1 - side) / 2).coerceIn(0, size - side)
        val y = ((t + b + 1 - side) / 2).coerceIn(0, size - side)
        return Bitmap.createBitmap(bmp, x, y, side, side)
    }

    /**
     * A brand colour nudged towards the theme's foreground until it stands out from the card at 3:1 (the bar for
     * icons): Spotify green or VLC orange on the light theme's pale grey cards are only about 2:1 as they come.
     */
    private fun readable(c: Int): Int {
        val card = if (dark) 0xFF0E0E11.toInt() else 0xFFE0E3E7.toInt() // the rest card, middle of its gradient
        fun contrast(a: Int): Float {
            val (hi, lo) = Color.luminance(a).let { la -> Color.luminance(card).let { lc -> maxOf(la, lc) to minOf(la, lc) } }
            return (hi + 0.05f) / (lo + 0.05f)
        }
        var out = c
        var mix = 0f
        while (contrast(out) < 3f && mix < 1f) {
            mix += 0.1f
            out = Color.rgb(
                (Color.red(c) * (1 - mix) + Color.red(fg) * mix).toInt(),
                (Color.green(c) * (1 - mix) + Color.green(fg) * mix).toInt(),
                (Color.blue(c) * (1 - mix) + Color.blue(fg) * mix).toInt())
        }
        return out
    }

    /** glyph() walks every pixel, too slow to redo on each row rebuild; kept per app until the app changes. */
    private fun cachedGlyph(a: App): Drawable? {
        val bmp = if (a.pkg in GLYPHS) GLYPHS[a.pkg] else glyph(a.icon).also { GLYPHS[a.pkg] = it }
        return bmp?.let { BitmapDrawable(resources, it) }
    }

    /**
     * The colour an icon is mostly, for tinting its mark: the biggest hue among its vivid pixels (backplate
     * included, which is where most apps keep their colour). Null if it's all greys, or too close to the
     * card colour to read on it; the mark then stays the theme's mono colour.
     */
    private fun accent(icon: Drawable): Int? {
        val size = 32
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.constantState?.newDrawable(resources)?.apply { setBounds(0, 0, size, size) }?.draw(Canvas(bmp)) ?: return null
        val px = IntArray(size * size).also { bmp.getPixels(it, 0, size, 0, 0, size, size) }
        bmp.recycle()
        val hsv = FloatArray(3)
        val buckets = px.filter { Color.alpha(it) > 200 }
            .filter { Color.colorToHSV(it, hsv); hsv[1] > 0.35f && hsv[2] > 0.3f }
            .groupBy { Color.colorToHSV(it, hsv); (hsv[0] / 30).toInt() } // 12 hue buckets
        val top = buckets.values.maxByOrNull { it.size }?.takeIf { it.size > px.size / 20 } ?: return null
        return Color.rgb(top.sumOf { Color.red(it) } / top.size, top.sumOf { Color.green(it) } / top.size, top.sumOf { Color.blue(it) } / top.size)
    }

    /** accent() per app, kept like GLYPHS; the contrast check is per theme, so it runs on every use. */
    private fun cachedAccent(a: App): Int? {
        val c = if (a.pkg in ACCENTS) ACCENTS[a.pkg] else accent(a.icon).also { ACCENTS[a.pkg] = it }
        val lum = c?.let { Color.luminance(it) } ?: return null
        return c.takeIf { if (dark) lum > 0.08f else lum < 0.6f }
    }

    /** Optional per-theme icons: assets/icons/<package>_dark.png, <package>_light.png, or <package>.png. */
    private fun customIcon(pkg: String): Drawable? =
        listOf("icons/${pkg}_${if (dark) "dark" else "light"}.png", "icons/$pkg.png").firstNotNullOfOrNull { path ->
            try {
                assets.open(path).use { Drawable.createFromStream(it, null) }
            } catch (e: IOException) {
                null
            }
        }

    // ---------- holding OK, and editing the apps row ----------

    /**
     * Hold detection lives here, not in View long-click: this TV's remote doesn't reliably trigger the
     * framework's long-press timer. We treat the first auto-repeat of OK as "held"; the long-click
     * listener in style() is a fallback for remotes that send no repeats. [holdFired] stops both firing.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (spotlightIdle) {
            spotlightIdle = false
            restartSpotlightTick()
        }
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
            e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (confirm) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) holdFired = false
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!holdFired) currentFocus?.let { fireHold(it) }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && holdFired) {
                currentFocus?.isPressed = false
                return true
            }
        }
        // The settings panel is a vertical list: don't let Left/Right leak focus to the home screen behind it.
        // The PIN boxes and the text fields (city search, API key) use them themselves.
        if (settingsScrim.visibility == View.VISIBLE && currentFocus !== pinBox && currentFocus !is EditText &&
            (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) return true
        if (editing && editKey(e, confirm)) return true
        if (e.action == KeyEvent.ACTION_DOWN && settingsScrim.visibility != View.VISIBLE && allApps.visibility != View.VISIBLE) {
            currentFocus?.let { homeNav(it, e.keyCode) }?.let { it.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    /**
     * The home screen is three rows: top bar, apps, Continue Watching. Left/Right stay inside a row and
     * stop at its ends; Up/Down move between rows and land where you last were in that row, not on
     * whatever happens to sit nearest on screen. Returns the view to focus (itself = stay), or null to
     * let Android handle the key.
     */
    private fun homeNav(v: View, key: Int): View? {
        fun alive(x: View?) = x?.takeIf { it.isAttachedToWindow }
        val apps = alive(lastApp)?.takeIf { it.parent === appRow } ?: appRow.getChildAt(0)
        val cards = if (watchScroll.visibility != View.VISIBLE) null
            else alive(lastCard) ?: (watchRow.getChildAt(0) as? ViewGroup)?.getChildAt(0)
        val top = alive(lastTop) ?: findViewById(R.id.launcherSettings)
        val spotVis = spotlightContainer.visibility == View.VISIBLE
        return when {
            v.parent === wifi.parent -> when (key) { // top bar
                KeyEvent.KEYCODE_DPAD_DOWN -> if (spotVis) spotlightInfoBox else apps ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_UP -> v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (v.id == R.id.wifi) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (v.id == R.id.launcherSettings) v else null
                else -> null
            }
            v === spotlightInfoBox -> when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> spotlightAction(-1, 1) ?: apps ?: cards ?: v
                KeyEvent.KEYCODE_DPAD_UP -> top
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cycleSpotlight(1)
                    v
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    cycleSpotlight(-1)
                    v
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    spotlightAction(-1, 1) ?: v
                }
                else -> null
            }
            v.parent === spotlightActions -> {
                val idx = spotlightActions.indexOfChild(v)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> apps ?: cards ?: v
                    KeyEvent.KEYCODE_DPAD_UP -> spotlightInfoBox
                    KeyEvent.KEYCODE_DPAD_LEFT -> spotlightAction(idx, -1) ?: v
                    KeyEvent.KEYCODE_DPAD_RIGHT -> spotlightAction(idx, 1) ?: v
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        v.performClick()
                        v
                    }
                    else -> null
                }
            }
            v.parent === appRow -> when (key) {
                KeyEvent.KEYCODE_DPAD_UP -> if (spotVis) spotlightInfoBox else top
                KeyEvent.KEYCODE_DPAD_DOWN -> cards ?: v
                KeyEvent.KEYCODE_DPAD_LEFT -> if (appRow.indexOfChild(v) == 0) v else null
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (appRow.indexOfChild(v) == appRow.childCount - 1) v else null
                else -> null
            }
            v.getTag(R.id.watchRow) is Prog -> {
                val i = watchRow.indexOfChild(v.parent as View)
                when (key) {
                    KeyEvent.KEYCODE_DPAD_UP -> apps ?: top
                    KeyEvent.KEYCODE_DPAD_DOWN -> v
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (i == 0) v else null
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (i == watchRow.childCount - 1) v else null
                    else -> null
                }
            }
            else -> null
        }
    }

    /** Overlays (All apps, launcher settings) sit on top of the home rows; keep focus from slipping behind them. */
    private fun homeFocusable(on: Boolean) {
        findViewById<ViewGroup>(R.id.content).descendantFocusability =
            if (on) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun fireHold(v: View) {
        holdFired = true
        v.cancelLongPress()
        v.isPressed = false
        onHold(v)
    }

    /** What holding OK does depends on what's focused. */
    private fun onHold(v: View) {
        val tag = v.tag as? String
        when {
            allApps.visibility == View.VISIBLE && tag != null -> unlock { // grid: pin to the front of the row (unhiding it)
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() - tag).apply()
                saveOrder(listOf(tag) + sortedApps().map { it.pkg }.filter { it != tag })
                closeAllApps()
                appRow.getChildAt(0)?.requestFocus() // show the app just pinned
            }
            v.parent === appRow -> appMenu(v)
            v.getTag(R.id.watchRow) is Prog -> watchMenu(v, v.getTag(R.id.watchRow) as Prog)
        }
    }

    /** Hold OK on a row app: Move (OK places it), Remove from row (stays in All apps), App info. Locked: Open only. */
    private fun appMenu(v: View) {
        val pkg = v.tag as String
        if (locked()) return holdMenu(v, listOf(
            getString(R.string.menu_open) to { v.performClick(); Unit },
            getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); appMenu(v) } },
        ))
        holdMenu(v, listOfNotNull(
            getString(R.string.menu_move) to { startEdit(grab = v, once = true) },
            getString(R.string.menu_remove_from_row) to {
                val i = appRow.indexOfChild(v)
                prefs.edit().putStringSet("row_hidden", rowHiddenSet() + pkg).apply()
                renderApps()
                appRow.getChildAt(minOf(i, appRow.childCount - 1))?.requestFocus()
                Toast.makeText(this, getString(R.string.removed_from_row_toast), Toast.LENGTH_SHORT).show()
            },
            if (pkg.startsWith(HDMI)) null else getString(R.string.menu_app_info) to {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            },
        ))
    }

    /** Hold OK on a Continue Watching card: hide the show, or open its app rather than the episode. */
    private fun watchMenu(v: View, p: Prog) {
        holdMenu(v, listOfNotNull(
            if (locked()) null else getString(R.string.menu_remove_from_cw) to { hideWatch(p) },
            if (!locked()) null else getString(R.string.unlock_to_edit) to { unlock { v.requestFocus(); watchMenu(v, p) } },
            p.pkg?.let { pkg ->
                getString(R.string.menu_open_app) to {
                    (packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg))
                        ?.let { startActivity(it) }
                    Unit
                }
            },
        ))
    }

    /** A small Piume-styled menu just above [anchor]; Back closes it. */
    private fun holdMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (dark) 0xFF141418.toInt() else 0xFFF7F8FA.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val menu = PopupWindow(list, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        items.forEachIndexed { i, (name, action) ->
            list.addView(settingsRow(name, "") { menu.dismiss(); action() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply {
                if (i > 0) topMargin = dp(2)
            })
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.elevation = dp(8).toFloat()
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], maxOf(dp(8), at[1] - list.measuredHeight - dp(8)))
        list.getChildAt(0).requestFocus()
    }

    /** Top-bar TV Inputs popup menu listing all HDMI and passthrough inputs. */
    private fun openInputsMenu(anchor: View) {
        val tm = getSystemService(TvInputManager::class.java)
        val inputs = try {
            tm?.tvInputList?.filter { it.type == TvInputInfo.TYPE_HDMI || it.isPassthroughInput } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (inputs.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_inputs), Toast.LENGTH_SHORT).show()
            return
        }
        val items = inputs.distinctBy { it.id }.map { input ->
            val label = input.loadLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: input.id
            label to {
                try {
                    val uri = TvContract.buildChannelUriForPassthroughInput(input.parentId ?: input.id)
                    startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent("android.intent.action.VIEW", TvContract.buildChannelUriForPassthroughInput(input.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e2: Exception) {
                        openSettings(Settings.ACTION_SETTINGS)
                    }
                }
            }
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (dark) 0xFF141418.toInt() else 0xFFF7F8FA.toInt())
            }
            elevation = dp(8).toFloat()
        }
        val menu = PopupWindow(list, dp(260), LinearLayout.LayoutParams.WRAP_CONTENT, true)
        items.forEachIndexed { i, (name, action) ->
            list.addView(settingsRow(name, "") { menu.dismiss(); action() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply {
                if (i > 0) topMargin = dp(2)
            })
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        menu.elevation = dp(8).toFloat()
        // Display directly underneath the top bar icon
        menu.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0] - dp(100), at[1] + anchor.height + dp(8))
        list.getChildAt(0).requestFocus()
    }

    private fun appTileCount() = appRow.childCount

    private var moveOnce = false // moving one app from the hold menu: OK places it and editing ends

    private fun startEdit(grab: View?, once: Boolean = false) {
        editing = true
        moveOnce = once
        appsTitle.text = if (once) getString(R.string.moving_app_instructions) else getString(R.string.editing_row_instructions)
        appsTitle.visibility = View.VISIBLE
        (grab ?: appRow.getChildAt(0)).requestFocus()
        setGrabbed(grab)
    }

    private fun setGrabbed(v: View?) {
        grabbed?.alpha = 1f
        grabbed = v
        v?.alpha = 0.6f
    }

    private fun endEdit() {
        setGrabbed(null)
        editing = false
        moveOnce = false
        appsTitle.visibility = View.GONE
        val row = (0 until appTileCount()).map { appRow.getChildAt(it).tag as String }
        saveOrder(row + sortedApps().map { it.pkg }.filter { it !in row })
        currentFocus?.takeIf { it.parent === appRow }?.let { showRowLabel(it, true) } // the name was hidden while editing
    }

    /** While editing, keys only rearrange the row; nothing launches and focus can't leave it. */
    private fun editKey(e: KeyEvent, confirm: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) endEdit()
            return true
        }
        val v = currentFocus ?: return false
        val i = appRow.indexOfChild(v)
        if (confirm) {
            if (e.action == KeyEvent.ACTION_UP && i in 0 until appTileCount()) {
                if (moveOnce) endEdit() else setGrabbed(if (grabbed === v) null else v)
            }
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return e.keyCode in EDIT_KEYS
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val j = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) i - 1 else i + 1
                if (j !in 0 until appTileCount()) return true
                if (grabbed === v) {
                    // Swap layout params too, so the first tile keeps no leading margin.
                    val other = appRow.getChildAt(j)
                    val lp = v.layoutParams
                    v.layoutParams = other.layoutParams
                    other.layoutParams = lp
                    appRow.removeViewAt(i)
                    appRow.addView(v, j)
                    showNames()
                    v.requestFocus()
                    return true
                }
                return false // not holding anything: normal focus movement within the row
            }
        }
        return false
    }

    /** Apps left out of the home row; they stay in All apps. New installs show until hidden. */
    private fun rowHiddenSet() = prefs.getStringSet("row_hidden", emptySet())!!

    private fun pickRowApps() {
        val list = sortedApps()
        toggles(getString(R.string.setting_apps_in_row), list.map { it.label to it.pkg }, "row_hidden", getString(R.string.state_shown), getString(R.string.state_hidden)) {
            renderApps()
            renderSettings(ROW_APPS_ROW)
        }
    }

    /** A page of on/off rows saved as the set of keys that are off; [done] runs on Back. */
    private fun toggles(title: String, items: List<Pair<String, String>>, pref: String, on: String, off: String, focus: Int = 0, done: () -> Unit) {
        val offSet = prefs.getStringSet(pref, emptySet())!!
        showPage(title, items.mapIndexed { i, (name, key) ->
            page(name, if (key in offSet) off else on) {
                prefs.edit().putStringSet(pref, if (key in offSet) offSet - key else offSet + key).apply()
                toggles(title, items, pref, on, off, i, done)
            }
        }, focus, back = done)
    }

    private fun saveOrder(pkgs: List<String>) {
        prefs.edit().putString("order", pkgs.joinToString(",")).putString("sort", "manual").apply()
    }

    private fun launch(a: App) {
        prefs.edit().putInt("n_" + a.pkg, prefs.getInt("n_" + a.pkg, 0) + 1).putLong("t_" + a.pkg, System.currentTimeMillis()).apply()
        if (prefs.getString("sort", "manual") == "usage") appsDirty = true
        try {
            startActivity(a.launch)
        } catch (e: Exception) {
            packageManager.getLeanbackLaunchIntentForPackage(a.pkg)?.let { startActivity(it) }
        }
    }

    // ---------- all apps ----------

    private fun openAllApps() {
        allAppsGrid.removeAllViews()
        val gap = dp(10)
        val width = (resources.displayMetrics.widthPixels - dp(96) - gap * 5) / 6
        for (a in sortedApps()) {
            val t = appTile(a) // holding OK here pins it to the row (see onHold)
            allAppsGrid.addView(t, GridLayout.LayoutParams().apply {
                this.width = width
                height = dp(76)
                setMargins(0, 0, gap, gap)
            })
        }
        allApps.visibility = View.VISIBLE
        homeFocusable(false)
        allAppsGrid.getChildAt(0)?.requestFocus()
    }

    private fun closeAllApps() {
        allApps.visibility = View.GONE
        homeFocusable(true)
        renderApps()
        findViewById<View>(R.id.allAppsButton).requestFocus() // back where it was opened from
    }

    // ---------- screensaver ----------
    // Gallery on: remember whatever screensaver was set (Aerial Views here) and switch to ours.
    // Gallery off: put that one back. Needs the adb-granted WRITE_SECURE_SETTINGS, like the Home service.

    private fun gallery() = ComponentName(this, GalleryDream::class.java).flattenToString()

    private fun currentScreensaver() = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()

    private fun galleryOn() = currentScreensaver() == gallery()

    private fun screensaverName(component: String = currentScreensaver()): String {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return getString(R.string.state_off)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.screensaver_system_default)
        }
    }

    /** The folder the Gallery screensaver shows instead of paintings, or null for paintings. */
    private fun photoScreensaver() = prefs.getString("ss_folder", null)

    private fun pickScreensaver(focus: Int = 0) {
        val paintings = galleryOn() && photoScreensaver() == null
        val photos = galleryOn() && photoScreensaver() != null
        val other = if (galleryOn()) prefs.getString("prev_screensaver", "").orEmpty() else currentScreensaver()
        showPage(getString(R.string.setting_screensaver), listOf(
            page(getString(R.string.screensaver_gallery_paintings), if (paintings) CHOSEN else if (downloading) getString(R.string.updates_checking) else PAINTINGS_MB) {
                useGallery(null)
                pickScreensaver(0)
            },
            page(getString(R.string.screensaver_your_photos), if (photos) "${wallpaperName(photoScreensaver()!!)} $CHOSEN" else "") {
                pickFolder({ pickScreensaver(1) }) { choice ->
                    useGallery(choice)
                    pickScreensaver(1)
                }
            },
            page(screensaverName(other), if (galleryOn()) "" else CHOSEN) {
                if (galleryOn()) stopGallery()
                pickScreensaver(2)
            },
        ), focus) { renderSettings(SCREENSAVER_ROW) }
    }

    /** Gallery on, with paintings ([folder] null, downloaded first if needed) or a folder of photos. */
    private fun useGallery(folder: String?) {
        prefs.edit().putString("ss_folder", folder).apply()
        if (folder == null && !paintingsReady(this)) return downloadPaintings { useGallery(null) }
        if (folder != null) paintingsDir(this).deleteRecursively() // not needed for photos: give the space back
        if (galleryOn()) return
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) {
            // Without the adb permission Piume can't switch it itself; Android's own list has "Gallery".
            Toast.makeText(this, getString(R.string.toast_screensaver_choose_gallery), Toast.LENGTH_LONG).show()
            return openScreensaverSettings()
        }
        prefs.edit().putString("prev_screensaver", currentScreensaver()).apply()
        Settings.Secure.putString(contentResolver, "screensaver_components", gallery())
        Settings.Secure.putInt(contentResolver, "screensaver_enabled", 1)
    }

    /** Back to whatever screensaver was set before Gallery. */
    private fun stopGallery() {
        if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return openScreensaverSettings()
        Settings.Secure.putString(contentResolver, "screensaver_components", prefs.getString("prev_screensaver", "") ?: "")
        paintingsDir(this).deleteRecursively() // another screensaver is in charge: give the space back
    }

    /** Android's screensaver settings (timing, sleep): plain Android TV and Google TV name them differently. */
    private fun openScreensaverSettings() {
        for (action in listOf(Settings.ACTION_DREAM_SETTINGS, "com.google.android.tv.settings.ambient")) {
            try {
                return startActivity(Intent(action))
            } catch (e: Exception) {
            }
        }
        openSettings(Settings.ACTION_SETTINGS)
    }

    @Volatile private var downloading = false

    /**
     * Fetches the Gallery paintings into a temporary folder and only swaps it in once every file arrived,
     * so a dropped connection never leaves a half-filled Gallery behind.
     */
    private fun downloadPaintings(then: () -> Unit) {
        if (downloading) return
        downloading = true
        Toast.makeText(this, getString(R.string.toast_screensaver_downloading, PAINTINGS_MB), Toast.LENGTH_SHORT).show()
        Thread {
            val part = File(filesDir, "paintings.part").apply { deleteRecursively(); mkdirs() }
            val ok = try {
                for (f in PAINTING_FILES) openStream(PAINTINGS_URL + f).use { input ->
                    File(part, f).outputStream().use { input.copyTo(it) }
                }
                paintingsDir(this).deleteRecursively()
                part.renameTo(paintingsDir(this))
            } catch (e: Exception) {
                warn("paintings download failed ($e)")
                part.deleteRecursively()
                false
            }
            ui.post {
                downloading = false
                if (ok) then() else Toast.makeText(this, getString(R.string.toast_screensaver_download_failed), Toast.LENGTH_LONG).show()
                refreshSettings(SCREENSAVER_ROW)
            }
        }.start()
    }

    /** Starts whichever screensaver is set, through SystemUI's "dream now" entry point. */
    private fun testScreensaver() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.systemui", "com.android.systemui.Somnambulator"))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_screensaver_cannot_start), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- updates (GitHub releases) ----------
    // Checked once a day from the home screen; a new version is announced once, then waits in settings.
    // The APK streams straight into an install session, so there's no temporary file to clean up.

    @Volatile private var checking = false
    @Volatile private var updating = false

    private fun myVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    /** The newer version a check found, if any (null again once it's installed). */
    private fun availableUpdate() = prefs.getString("upd_ver", null)?.takeIf { newerVersion(it, myVersion()) }

    private fun checkUpdate(force: Boolean) {
        if (checking || !force && System.currentTimeMillis() - prefs.getLong("upd_time", 0) < 24 * 3600_000L) return
        checking = true
        if (force) refreshSettings(UPDATE_ROW)
        netIo.execute {
            val latest = try {
                getJson("https://api.github.com/repos/$REPO/releases/latest").getString("tag_name").removePrefix("v")
            } catch (e: Exception) {
                warn("update check failed ($e)")
                null
            }
            if (latest != null) prefs.edit().putString("upd_ver", latest).putLong("upd_time", System.currentTimeMillis()).apply()
            ui.post {
                checking = false
                val ver = availableUpdate()
                if (ver != null && (force || prefs.getString("upd_told", null) != ver)) {
                    prefs.edit().putString("upd_told", ver).apply()
                    if (!force) Toast.makeText(this, getString(R.string.toast_update_available, ver), Toast.LENGTH_LONG).show()
                } else if (force) {
                    Toast.makeText(this, if (latest == null) getString(R.string.toast_update_check_failed) else getString(R.string.toast_update_up_to_date), Toast.LENGTH_SHORT).show()
                }
                refreshSettings(UPDATE_ROW)
                updateBadge()
            }
        }
    }

    /** What's new in this version (the CHANGELOG.md section the build put in), with check or install on top. */
    private fun showUpdates() {
        prefs.edit().putString("seen_notes", myVersion()).apply()
        updateBadge()
        val ver = availableUpdate()
        showPage(getString(R.string.whats_new_title, myVersion()), listOf(
            if (ver != null) page(getString(R.string.install_update, ver)) { installUpdate(ver) }
            else page(getString(R.string.check_updates)) { checkUpdate(force = true) },
        ), back = { renderSettings(UPDATE_ROW) })
        // Focusable, lit on focus, so Down walks through notes longer than the panel and it scrolls along.
        getString(R.string.whats_new).split('\n').forEach {
            settingsList.addView(label("", 13f).apply {
                text = SpannableString(it).apply { setSpan(BulletSpan(dp(10), fg), 0, it.length, 0) }
                maxLines = Int.MAX_VALUE
                ellipsize = null
                alpha = 0.7f
                isFocusable = true
                setOnFocusChangeListener { v, focused -> v.alpha = if (focused) 1f else 0.7f }
                setPadding(dp(14), dp(10), dp(14), dp(2))
            })
        }
        settingsList.getChildAt(0).nextFocusDownId = View.NO_ID // showPage stops Down at its last row
        settingsList.getChildAt(settingsList.childCount - 1).let { it.id = View.generateViewId(); it.nextFocusDownId = it.id }
    }

    /** Notes the user hasn't opened since updating. A fresh install has nothing new to tell. */
    private fun notesUnseen(): Boolean {
        val info = packageManager.getPackageInfo(packageName, 0)
        return info.firstInstallTime != info.lastUpdateTime && prefs.getString("seen_notes", null) != info.versionName
    }

    private val badge by lazy { GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BADGE) } }

    /** A dot on the Piume settings icon while an update waits or its notes are unread. */
    private fun updateBadge() {
        val icon = findViewById<ImageView>(R.id.launcherSettings)
        icon.overlay.clear()
        if (availableUpdate() == null && !notesUnseen()) return
        val size = dp(9)
        badge.setBounds(icon.width - size - dp(3), dp(3), icon.width - dp(3), dp(3) + size)
        icon.overlay.add(badge)
    }

    private fun installUpdate(ver: String) {
        if (updating) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, getString(R.string.toast_update_permission), Toast.LENGTH_LONG).show()
            return openSettings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        }
        updating = true
        refreshSettings(UPDATE_ROW)
        val installer = packageManager.packageInstaller
        netIo.execute {
            val ok = try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(packageName)
                // Android 12+ can skip the confirm box when we're the app's installer; otherwise Android asks.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    try {
                        openStream("https://github.com/$REPO/releases/download/v$ver/Piume.apk").use { input ->
                            session.openWrite("Piume.apk", 0, -1).use { out ->
                                input.copyTo(out)
                                session.fsync(out)
                            }
                        }
                    } catch (e: Exception) {
                        session.abandon()
                        throw e
                    }
                    // Mutable: the installer adds the status to it. Explicit, to a receiver only Android can reach.
                    val done = PendingIntent.getBroadcast(this, 0, Intent(this, InstallResult::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(done.intentSender)
                }
                true
            } catch (e: Exception) {
                warn("update download failed ($e)")
                false
            }
            ui.post {
                if (!ok) {
                    updating = false
                    Toast.makeText(this, getString(R.string.toast_update_download_failed), Toast.LENGTH_LONG).show()
                }
                refreshSettings(UPDATE_ROW)
            }
        }
    }

    /**
     * The install's outcome, passed on by InstallResult (which shows Android's confirm box itself). Any app can
     * send this action, so it only ever resets the row and may show a toast: nothing in it gets launched.
     */
    private fun installStatus(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        updating = false
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            warn("update install failed: $status $why")
            if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(this, getString(R.string.toast_update_install_failed), Toast.LENGTH_LONG).show()
            }
        }
        refreshSettings(UPDATE_ROW)
    }

    // ---------- weather (under the date) ----------
    // Location comes from the public IP (ipwho.is): a TV has no GPS and Android TV no location service.
    // Weather is Open-Meteo. Both are free and keyless. We always fetch Celsius and convert, so
    // switching units needs no refetch. The last reading is kept in prefs and shows instantly.

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            ui.postDelayed(this, 30 * 60_000L)
        }
    }

    private fun refreshWeather() {
        if (prefs.getString("weather", "off") == "off") return
        if (System.currentTimeMillis() - prefs.getLong("wx_time", 0) < 25 * 60_000L) return
        netIo.execute {
            try {
                if (!manualCity() && System.currentTimeMillis() - prefs.getLong("geo_time", 0) > 6 * 3600_000L) {
                    val geo = getJson("https://ipwho.is/?fields=success,city,latitude,longitude")
                    if (geo.optBoolean("success")) prefs.edit()
                        .putString("geo_city", geo.getString("city"))
                        .putFloat("geo_lat", geo.getDouble("latitude").toFloat())
                        .putFloat("geo_lon", geo.getDouble("longitude").toFloat())
                        .putLong("geo_time", System.currentTimeMillis()).apply()
                }
                if (!prefs.contains("geo_lat")) return@execute
                val now = getJson("https://api.open-meteo.com/v1/forecast?latitude=${prefs.getFloat("geo_lat", 0f)}" +
                    "&longitude=${prefs.getFloat("geo_lon", 0f)}&current=temperature_2m,weather_code").getJSONObject("current")
                prefs.edit()
                    .putFloat("wx_temp", now.getDouble("temperature_2m").toFloat())
                    .putInt("wx_code", now.getInt("weather_code"))
                    .putLong("wx_time", System.currentTimeMillis()).apply()
                ui.post { showWeather() }
            } catch (e: Exception) {
                warn("weather failed ($e)") // keep the last reading; next tick retries
            }
        }
    }

    // An IP's location is wherever the provider routes it, sometimes a whole region off (Rome showing as
    // Sicily), so you can pick the city instead. Open-Meteo's geocoding, also free and keyless.

    private fun manualCity() = prefs.getBoolean("geo_manual", false)

    private class City(val name: String, val where: String, val lat: Float, val lon: Float)

    /** Automatic first, then the search box; a search lists what it found under them. */
    private fun pickCity(query: String = "", found: List<City>? = null, status: String = "") {
        val search = EditText(this).apply {
            id = View.generateViewId()
            setText(query)
            setSelection(query.length)
            hint = getString(R.string.city_hint)
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), 0, dp(14), 0)
            val bg = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(field)
                setStroke(dp(1), fieldEdge())
            }
            background = bg
            setOnFocusChangeListener { _, focused -> bg.setStroke(if (focused) dp(2) else dp(1), if (focused) fg else fieldEdge()) }
            setOnEditorActionListener { v, _, _ -> searchCity(v.text.toString().trim()); true }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, 0)
            addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            if (status.isNotEmpty()) addView(label(status, 12f).apply { alpha = 0.6f; setPadding(dp(14), dp(8), 0, 0) })
        }
        val rows = listOf(page(getString(R.string.automatic_ip), if (manualCity()) "" else CHOSEN) {
            prefs.edit().remove("geo_manual").remove("geo_time").remove("wx_time").apply()
            refreshWeather()
            renderSettings(CITY_ROW)
        }) + found.orEmpty().map { c ->
            page(c.name, c.where) {
                prefs.edit().putBoolean("geo_manual", true).putString("geo_city", c.name)
                    .putFloat("geo_lat", c.lat).putFloat("geo_lon", c.lon).remove("wx_time").apply()
                refreshWeather()
                renderSettings(CITY_ROW)
            }
        }
        showPage(getString(R.string.weather_location), rows, if (found.isNullOrEmpty()) 0 else 1, top = top) { renderSettings(CITY_ROW) }
        // showPage pins Up on the first row to itself; here Up goes to the search box.
        settingsList.getChildAt(1).nextFocusUpId = search.id
        if (found.isNullOrEmpty()) search.requestFocus()
        // Results in: put the keyboard away, it would cover them.
        else getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun searchCity(query: String) {
        if (query.isEmpty()) return
        pickCity(query, status = getString(R.string.searching))
        netIo.execute {
            val found = try {
                val r = getJson("https://geocoding-api.open-meteo.com/v1/search?count=8&format=json" +
                    "&language=${java.util.Locale.getDefault().language}&name=${URLEncoder.encode(query, "UTF-8")}")
                val list = r.optJSONArray("results")
                (0 until (list?.length() ?: 0)).map { i ->
                    val c = list!!.getJSONObject(i)
                    City(c.getString("name"), listOf(c.optString("admin1"), c.optString("country")).filter { it.isNotEmpty() }.joinToString(", "),
                        c.getDouble("latitude").toFloat(), c.getDouble("longitude").toFloat())
                }
            } catch (e: Exception) {
                null
            }
            ui.post {
                // Only if the page is still open: Back may have left it while the search ran.
                if (settingsScrim.visibility != View.VISIBLE || findViewById<TextView>(R.id.settingsTitle).text.toString() != getString(R.string.weather_location)) return@post
                when {
                    found == null -> pickCity(query, status = getString(R.string.search_failed))
                    found.isEmpty() -> pickCity(query, status = getString(R.string.no_places, query))
                    else -> pickCity(query, found)
                }
            }
        }
    }

    private fun showWeather() {
        val view = findViewById<TextView>(R.id.weather)
        val unit = prefs.getString("weather", "off")
        if (unit == "off" || !prefs.contains("wx_temp")) {
            view.visibility = View.GONE
            return
        }
        val c = prefs.getFloat("wx_temp", 0f)
        val temp = Math.round(if (unit == "f") c * 9 / 5 + 32 else c)
        view.text = listOfNotNull("$temp° ${weatherWord(prefs.getInt("wx_code", 0))}".trim(), prefs.getString("geo_city", null))
            .joinToString("  ·  ")
        view.visibility = View.VISIBLE
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private fun weatherWord(code: Int) = when (code) {
        0 -> getString(R.string.weather_clear)
        1, 2 -> getString(R.string.weather_partly_cloudy)
        3 -> getString(R.string.weather_cloudy)
        45, 48 -> getString(R.string.weather_fog)
        in 51..57 -> getString(R.string.weather_drizzle)
        in 61..67, in 80..82 -> getString(R.string.weather_rain)
        in 71..77, 85, 86 -> getString(R.string.weather_snow)
        in 95..99 -> getString(R.string.weather_thunderstorm)
        else -> ""
    }

    private fun getJson(url: String) = JSONObject(openStream(url).use { it.readBytes().decodeToString() })

    // ---------- continue watching (Android TV Watch Next) ----------

    private fun refreshWatchNext() {
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            showWatchNext(emptyList())
            return
        }
        ui.post { watchTitle.text = getString(R.string.continue_watching) }
        io.execute {
            val list = try {
                queryWatchNext()
            } catch (e: Exception) {
                emptyList()
            }
            ui.post {
                watchTitle.text = getString(R.string.continue_watching)
                showWatchNext(list)
            }
        }
    }

    /**
     * Two sources, same columns:
     * 1. The system Watch Next list (Netflix etc.), newest engagement first.
     * 2. Any app's own home-screen channel named "Continue Watching". Stremio publishes one (with
     *    progress and an autoPlay resume link) instead of using Watch Next, so signing in to Stremio
     *    on the TV is all it takes. Channel rows carry no timestamps, so they use the time the app was
     *    last opened from Piume, keeping the app's own order among themselves.
     * Newest first across apps, at most [PER_APP] each, so one app with ten shows can't bury the others.
     */
    private fun queryWatchNext(): List<Prog> {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
        val watchNext = readPrograms(TvContract.WatchNextPrograms.CONTENT_URI, hidden)
        val channels = continueChannels().flatMap { id ->
            readPrograms(TvContract.buildPreviewProgramsUriForChannel(id), hidden).map { (_, p) -> prefs.getLong("t_${p.pkg}", 0) to p }
        }
        val all = watchNext + channels
        publishingPkgs = all.mapNotNull { it.second.pkg }.toSet()
        val off = prefs.getStringSet("cw_off", emptySet())!!
        return newestPerApp(all.filter { it.second.pkg !in off }.distinctBy { it.second.intent ?: it.second.title }, PER_APP) { it.pkg }
            .take(20)
    }

    /** Apps last seen publishing, so the settings list includes them even without the permission check. */
    @Volatile private var publishingPkgs = emptySet<String>()

    /**
     * Apps that can feed Continue Watching: anything allowed to write TV listings, plus anything
     * actually publishing now. Built from installed apps each time, so new apps appear on their own.
     */
    private fun watchSources(): List<App> = apps.filter { a ->
        a.pkg in publishingPkgs || try {
            packageManager.getPackageInfo(a.pkg, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains(WRITE_EPG) == true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }.sortedBy { it.label.lowercase() }

    private fun pickWatchSources() {
        val sources = watchSources()
        if (sources.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_cw_apps), Toast.LENGTH_SHORT).show()
            return
        }
        toggles(getString(R.string.setting_cw_apps), sources.map { it.label to it.pkg }, "cw_off", getString(R.string.state_on), getString(R.string.state_off)) {
            watchKey = null
            refreshWatchNext()
            renderSettings(CW_APPS_ROW)
        }
    }

    private fun continueChannels(): List<Long> {
        val ids = ArrayList<Long>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
            val name = c.getColumnIndex("display_name")
            val pkg = c.getColumnIndex("package_name")
            val id = c.getColumnIndex("_id")
            while (c.moveToNext()) {
                if (c.getString(name).equals("Continue Watching", ignoreCase = true) && c.getString(pkg) != packageName) {
                    ids += c.getLong(id)
                }
            }
        }
        return ids
    }

    /** Pairs of (last engagement time, item); time is 0 for channel rows, which don't have it. */
    private fun readPrograms(uri: Uri, hidden: Set<String>): List<Pair<Long, Prog>> {
        val out = ArrayList<Pair<Long, Prog>>()
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            fun str(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
            fun num(col: String) = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                if (str("browsable") == "0") continue
                val title = str("title") ?: continue
                if (hideKey(str("package_name"), title) in hidden) continue
                val season = str("season_display_number")
                val episode = str("episode_display_number")
                val duration = num("duration_millis")
                val position = num("last_playback_position_millis")
                val sub = listOfNotNull(
                    if (season != null && episode != null) "S$season E$episode" else null,
                    // Under a second in means "not really started": no "time left", no bar.
                    if (duration > 0 && position in 1000 until duration) timeLeft(duration - position) else null,
                ).joinToString(" - ")
                val progress = if (duration > 0 && position >= 1000) (position * 100 / duration).toInt().coerceIn(0, 100) else 0
                val prog = Prog(title, sub, progress, str("thumbnail_uri") ?: str("poster_art_uri"), str("intent_uri"), str("package_name"), num("weight"))
                out += num("last_engagement_time_utc_millis") to prog
            }
        }
        return out
    }

    private fun hideKey(pkg: String?, title: String) = "$pkg/$title"

    /** We can't delete another app's Watch Next row, so hiding is a local filter. */
    private fun hideWatch(p: Prog) {
        val hidden = prefs.getStringSet("hidden_watch", emptySet())!! + hideKey(p.pkg, p.title)
        prefs.edit().putStringSet("hidden_watch", hidden).apply()
        Toast.makeText(this, getString(R.string.toast_hidden_from_cw), Toast.LENGTH_SHORT).show()
        refreshWatchNext()
    }

    private fun queryAppChannels(pkg: String?) {
        if (pkg == null || checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            ui.post { watchTitle.text = getString(R.string.continue_watching) }
            refreshWatchNext()
            return
        }
        io.execute {
            val hidden = prefs.getStringSet("hidden_watch", emptySet())!!
            val appChannels = ArrayList<Pair<Long, String>>() // (id, displayName)
            contentResolver.query(TvContract.Channels.CONTENT_URI, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex("display_name")
                val pkgIdx = c.getColumnIndex("package_name")
                val idIdx = c.getColumnIndex("_id")
                while (c.moveToNext()) {
                    if (c.getString(pkgIdx) == pkg) {
                        val dName = c.getString(nameIdx) ?: ""
                        appChannels += c.getLong(idIdx) to dName
                    }
                }
            }
            // Fetch preview programs from all channels for this app
            val programs = appChannels.flatMap { (channelId, _) ->
                readPrograms(TvContract.buildPreviewProgramsUriForChannel(channelId), hidden)
            }.map { it.second }.distinctBy { it.intent ?: it.title }.let(::inChannelOrder)
            if (programs.isEmpty()) { // an app with nothing to recommend shows nothing under the row
                ui.post { if (tmdbLastFocusApp == pkg) hideWatchRow() }
                return@execute
            }
            val appLabel = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
            ui.post {
                watchTitle.text = getString(R.string.tmdb_app_channels, appLabel)
                showWatchNext(programs)
            }
        }
    }

    /**
     * A TV app's recommendations are usually its channels, but the database returns them in no useful order (the
     * order they were last written). Sorted by channel number: the channel each card opens (its intent points at
     * content://android.media.tv/channel/<id>), else the channel with the card's name, else a number the title
     * starts with ("1 Rai 1"). Cards without a number follow, by the weight the app gave them. Runs on the io thread.
     */
    private fun inChannelOrder(programs: List<Prog>): List<Prog> {
        val byId = HashMap<Long, String>()
        val byName = HashMap<String, String>()
        contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "display_number", "display_name", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) == TvContract.Channels.TYPE_PREVIEW) continue // home-screen rows, not tuner channels
                val number = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                byId[c.getLong(0)] = number
                c.getString(2)?.let { byName[it.trim().lowercase()] = number }
            }
        }
        fun number(p: Prog): String? {
            val channel = p.intent?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME).data }.getOrNull() }
                ?.takeIf { it.authority == TvContract.AUTHORITY && it.pathSegments.firstOrNull() == "channel" }
                ?.lastPathSegment?.toLongOrNull()
            return channel?.let(byId::get) ?: byName[p.title.trim().lowercase()]
                ?: Regex("""^\s*(\d+(?:[.-]\d+)?)\b""").find(p.title)?.groupValues?.get(1)
        }
        return programs.sortedWith(compareBy<Prog>({ channelKey(number(it)) }).thenByDescending { it.weight })
    }

    /**
     * Hides the row under the apps for an app with no recommendations. The old cards stay (invisible) so the row
     * keeps its height and the apps row doesn't jump as focus moves along it; the next list rebuilds them.
     */
    private fun hideWatchRow() {
        watchKey = null
        if (watchScroll.visibility == View.GONE) return
        watchTitle.visibility = View.INVISIBLE
        watchScroll.visibility = View.INVISIBLE
    }

    private fun isDescendantOf(child: View?, parent: View): Boolean {
        var v = child
        while (v != null) {
            if (v === parent) return true
            v = v.parent as? View
        }
        return false
    }

    private fun setupSpotlight() {
        // Feathered rather than a rounded box, so the veil melts into the backdrop instead of drawing a hard frame.
        spotlightInfoBox.background = SoftVeil(dp(32).toFloat(), dp(240).toFloat()).apply { color = spotlightVeil() }
        // The arrows get a small disc in the ground colour, so they read on any backdrop.
        for (arrow in arrayOf(spotlightPrev, spotlightNext)) {
            arrow.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((ground and 0xFFFFFF) or 0x80000000.toInt()) }
            arrow.setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // Text stays inside the veil's solid part: clear of the 32dp feathered edges, and ending where the long fade on
        // the right begins.
        spotlightInfoBox.setPadding(dp(34), dp(26), dp(200), dp(26))
        spotlightPrev.setOnClickListener { cycleSpotlight(-1) }
        spotlightNext.setOnClickListener { cycleSpotlight(1) }

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            updateSpotlightFocusState(newFocus)
            keepInView(newFocus)
        }
    }

    /**
     * A light veil behind the Spotlight text even at rest: it sits over the backdrop's unveiled middle, where a dark
     * film still would swallow the light theme's dark text (and a bright one the dark theme's light text).
     */
    // As see-through as readability allows, or even a feathered veil looks like a solid slab. Measured on the worst
    // backdrop for each theme (near-black under dark text, light grey under light text): at rest the title and meta
    // stay above 3.8:1; focused, the 13sp overview above 4.5:1. The text halo adds to that.
    private fun spotlightVeil() = if (dark) 0x73000000 else 0x66F3F5F7

    private fun spotlightVeilFocused() = if (dark) 0x99181820.toInt() else 0x8CEEF2F7.toInt()

    /**
     * The home screen doesn't scroll, but with Spotlight on top the rows under it can run past the bottom edge.
     * Focus down there slides the whole page up just enough to show the focused item with what's under it (a
     * card's title and details, a tile's name), and focus anywhere above slides it back.
     */
    private fun keepInView(v: View?) {
        val content = findViewById<View>(R.id.content)
        if (v == null || !isDescendantOf(v, content)) return
        val target = if (v.getTag(R.id.watchRow) is Prog) v.parent as View else v
        val under = if (v.parent === appRow) dp(28) else 0
        content.post {
            var shift = 0f
            if (isDescendantOf(v, bottomSection)) {
                val at = IntArray(2).also { target.getLocationInWindow(it) }
                // Where it will sit once the page and the rows stop moving (both may be mid-animation).
                val bottom = at[1] - content.translationY - bottomSection.translationY + target.height + under
                shift = (bottom - (findViewById<View>(R.id.root).height - dp(16))).coerceAtLeast(0f)
            }
            if (shift != -content.translationY) content.animate().translationY(-shift).setDuration(200).setInterpolator(DECELERATE).start()
        }
    }

    private fun updateSpotlightFocusState(targetFocus: View?) {
        val inSpotlight = isDescendantOf(targetFocus, spotlightContainer)
        spotlightOverview.visibility = if (inSpotlight) View.VISIBLE else View.GONE
        spotlightActions.visibility = if (inSpotlight) View.VISIBLE else View.GONE
        for (arrow in arrayOf(spotlightPrev, spotlightNext)) arrow.animate().alpha(if (inSpotlight) 0.9f else 0.5f).setDuration(220).start()
        (spotlightInfoBox.background as? SoftVeil)?.color = if (inSpotlight) spotlightVeilFocused() else spotlightVeil()
        // Translate bottomSection downwards to give spotlight breathing room
        val targetTransY = if (inSpotlight) dp(65).toFloat() else 0f
        val targetAlpha = if (inSpotlight) 0.65f else 1f
        bottomSection.animate().translationY(targetTransY).alpha(targetAlpha).setDuration(220).start()
    }

    private fun resolveAppByPkg(pkg: String): App? = try {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
        val info = pm.getApplicationInfo(pkg, 0)
        App(pkg, info.loadLabel(pm).toString(), intent, info.loadIcon(pm))
    } catch (_: Exception) {
        null
    }

    private fun spotlightItem(): TmdbItem? = tmdbTrending.let { list -> if (list.isEmpty()) null else list[tmdbIndex.mod(list.size)] }

    private fun renderSpotlight() {
        val item = spotlightItem()
        if (currentWallpaper() != "tmdb:trending" || item == null) {
            spotlightContainer.visibility = View.GONE
            return
        }
        spotlightContainer.visibility = View.VISIBLE
        val year = item.releaseDate?.take(4)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        val rating = if (item.voteAverage > 0) "★ %.1f · ".format(item.voteAverage) else ""
        val typeBadge = if (item.mediaType == "tv") getString(R.string.tmdb_badge_tv) else getString(R.string.tmdb_badge_movie)
        val genre = item.genres.firstOrNull()?.let { " · $it" } ?: ""
        spotlightMeta.text = "$rating$typeBadge$year$genre"
        spotlightTitle.text = item.title
        spotlightOverview.text = item.overview
        val more = if (tmdbTrending.size > 1) View.VISIBLE else View.INVISIBLE
        spotlightPrev.visibility = more
        spotlightNext.visibility = more
        // The buttons search for whichever title shows when pressed, so they're only rebuilt when the apps change
        // (rebuilding them on every title would also drop the focus off them).
        if (spotlightApps == null) renderSpotlightActions()
        showProviders(item)
    }

    private fun providersOn() = prefs.getBoolean("tmdb_providers", true)

    /**
     * Shows the buttons of the services that have [item] (TMDB's watch providers for this country) and hides the
     * other services' ones; aggregators, YouTube and unknown apps always stay. Known answers apply at once, others
     * once TMDB answers (until then, and offline, every button shows).
     */
    private fun showProviders(item: TmdbItem) {
        val key = prefs.getString("tmdb_api_key", null)
        if (!providersOn() || key == null) return applyProviders(null)
        val region = TmdbClient.region(tmdbLang())
        val known = TmdbClient.cachedProviders(item, region)
        applyProviders(known)
        if (known == null) spotlightIo.execute {
            val found = TmdbClient.providers(key, item, region) ?: return@execute
            ui.post { if (spotlightItem() == item) applyProviders(found) }
        }
    }

    private fun applyProviders(available: Set<String>?) {
        var first = true
        for (i in 0 until spotlightActions.childCount) {
            val b = spotlightActions.getChildAt(i)
            val show = TmdbClient.offers(b.tag as? String ?: continue, available)
            b.visibility = if (show) View.VISIBLE else View.GONE
            // Gaps only between visible buttons, so the first one lines up with the text above.
            (b.layoutParams as LinearLayout.LayoutParams).marginStart = if (show && !first) dp(8) else 0
            if (show) first = false
        }
        spotlightActions.requestLayout()
        if (currentFocus?.parent === spotlightActions && currentFocus?.visibility != View.VISIBLE) {
            (spotlightAction(-1, 1) ?: spotlightInfoBox).requestFocus()
        }
    }

    /** The next visible Spotlight button after index [from] going [step] (±1), or null at the end. */
    private fun spotlightAction(from: Int, step: Int): View? {
        var i = from + step
        while (i in 0 until spotlightActions.childCount) {
            spotlightActions.getChildAt(i).takeIf { it.visibility == View.VISIBLE }?.let { return it }
            i += step
        }
        return null
    }

    /** Up to five search buttons: the known streaming apps first, then other installed apps, minus those turned off. */
    private fun renderSpotlightActions() {
        spotlightActions.removeAllViews()
        val offSet = prefs.getStringSet("tmdb_default_apps", emptySet()) ?: emptySet()
        val candidateApps = mutableListOf<App>()
        // The known services' apps join whenever installed: each shows only for titles TMDB lists on it.
        for (pkg in SPOTLIGHT_PKGS + TmdbClient.providerApps) {
            if (pkg in offSet) continue
            val app = apps.firstOrNull { it.pkg == pkg } ?: resolveAppByPkg(pkg)
            if (app != null && candidateApps.none { it.pkg == app.pkg }) {
                candidateApps.add(app)
            }
        }
        for (app in apps) {
            if (candidateApps.size >= 5) break
            if (app.pkg !in offSet && !app.pkg.startsWith(HDMI) && app.pkg != CW_PKG && candidateApps.none { it.pkg == app.pkg }) {
                candidateApps.add(app)
            }
        }
        // Five at most, not counting services' apps beyond that: those hide for titles they don't have.
        val targetApps = candidateApps.filterIndexed { i, a -> i < 5 || a.pkg in TmdbClient.providerApps }
        spotlightApps = targetApps

        for (a in targetApps) {
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(14), dp(7))
                val rest = if (dark) 0xCC1C1C22.toInt() else 0xCCDCE0E8.toInt() // opaque enough for any backdrop
                val btnBg = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(rest)
                }
                background = btnBg
                isFocusable = true
                isClickable = true
                tag = a.pkg
                val ic = ImageView(this@MainActivity).apply {
                    setImageDrawable(a.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val txt = TextView(this@MainActivity).apply {
                    text = if (TmdbClient.isYouTube(a.pkg)) getString(R.string.spotlight_trailer) else a.label
                    setTextColor(fg)
                    textSize = 12f
                    setPadding(dp(6), 0, 0, 0)
                }
                addView(ic, dp(18), dp(18))
                addView(txt)
                setOnFocusChangeListener { _, focused ->
                    btnBg.setColor(if (focused) fg else rest)
                    txt.setTextColor(if (focused) onFg else fg)
                    scaleX = if (focused) 1.08f else 1f
                    scaleY = if (focused) 1.08f else 1f
                }
                setOnClickListener { openInApp(a) }
            }
            spotlightActions.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (spotlightActions.childCount > 0) dp(8) else 0
            })
        }
    }

    /**
     * Opens the Spotlight title in [a]: straight on its page where the app takes an id (Nuvio by TMDB id, Stremio by
     * IMDb id, looked up first), YouTube on the trailer TMDB lists (or a search for one), otherwise through the
     * app's search, otherwise just the app.
     */
    private fun openInApp(a: App) {
        val item = spotlightItem() ?: return launch(a)
        val lang = tmdbLang()
        fun start(ref: String?) = try {
            startActivity(TmdbClient.openIntent(this, a.pkg, item, ref, lang))
        } catch (e: Exception) {
            launch(a)
        }
        val key = prefs.getString("tmdb_api_key", null)
        if (key == null || !TmdbClient.needsLookup(a.pkg)) return start(null)
        spotlightIo.execute {
            val ref = TmdbClient.lookup(key, a.pkg, item, lang)
            ui.post { start(ref) }
        }
    }

    private fun cycleSpotlight(direction: Int) {
        if (tmdbTrending.isEmpty()) return
        ui.removeCallbacks(spotlightTick) // restarted once the new backdrop is up
        tmdbIndex = (tmdbIndex + direction).mod(tmdbTrending.size)
        renderSpotlight()
        loadWallpaper()
    }

    /** Downloads the next and previous titles' backdrops ahead, so ◀ ▶ and the timer show them at once. */
    private fun prefetchBackdrops() {
        val list = tmdbTrending
        if (list.size < 2) return
        val urls = listOf(1, -1).mapNotNull { TmdbClient.backdropUrl(list[(tmdbIndex + it).mod(list.size)].backdropPath) }
            .filterNot { backdrops.has(it) }
        if (urls.isNotEmpty()) spotlightIo.execute {
            for (url in urls) try {
                backdrops.get(url) { openStream(it) }
            } catch (e: Exception) {
                warn("backdrop prefetch failed ($e)") // shown later from the network instead
            }
        }
    }

    private fun spotlightEvery() = prefs.getInt("tmdb_cycle", 5) // seconds; 0 = only by hand

    /**
     * Spotlight moves on to the next title by itself every few seconds while the home screen shows. It waits
     * while you're on one of its buttons (the title shouldn't change under you), while the panel or All apps is
     * open, and while something covers the home screen (a screensaver, a dialog), so it doesn't fetch backdrops unseen.
     */
    private val spotlightTick = object : Runnable {
        override fun run() {
            if (spotlightContainer.visibility != View.VISIBLE) return // armed again when a TMDB backdrop loads
            // Nobody has pressed a key for a while: stop turning titles (and decoding backdrops) for an empty room.
            // The next key press starts it again.
            if (SystemClock.elapsedRealtime() - lastInput > SPOTLIGHT_IDLE_MS) {
                spotlightIdle = true
                return
            }
            val busy = currentFocus?.parent === spotlightActions || settingsScrim.visibility == View.VISIBLE ||
                allApps.visibility == View.VISIBLE || editing || !hasWindowFocus()
            if (busy) restartSpotlightTick() else cycleSpotlight(1)
        }
    }

    private fun restartSpotlightTick() {
        ui.removeCallbacks(spotlightTick)
        val every = spotlightEvery()
        if (every > 0 && tmdbTrending.size > 1) ui.postDelayed(spotlightTick, every * 1000L)
    }

    private fun sourceIcon(pkg: String?): Drawable? {
        if (pkg == null) return null
        LOGOS[pkg]?.takeIf { !it.second }?.let { return getDrawable(it.first)!!.mutate().apply { setTint(fg) } }
        return try {
            packageManager.getApplicationIcon(pkg).mutate().apply { colorFilter = GREYSCALE }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun timeLeft(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) getString(R.string.time_left_hr_min, min / 60, min % 60) else getString(R.string.time_left_min, min)
    }

    private fun showWatchNext(list: List<Prog>) {
        val key = list.joinToString("|") { "${it.title}/${it.sub}/${it.progress}/${it.image}" }
        if (key == watchKey) { // unchanged: keep views (and focus) as they are, just fill in missing posters
            retryPosters()
            return
        }
        watchKey = key
        val focusedAt = watchRow.indexOfChild(currentFocus?.parent as? View)
        watchRow.removeAllViews()
        val visible = if (list.isEmpty()) View.GONE else View.VISIBLE
        watchTitle.visibility = visible
        watchScroll.visibility = visible
        // ~16 ms per card here and only four show at once: build them four per frame, not all 20 in one.
        fun addCards(from: Int) {
            if (watchKey != key) return // a newer list replaced this one
            list.subList(from, minOf(from + 4, list.size)).forEach { watchRow.addView(watchCard(it, watchRow.childCount > 0)) }
            if (from + 4 < list.size) ui.post { addCards(from + 4) }
        }
        addCards(0)
        if (focusedAt >= 0) {
            ((watchRow.getChildAt(focusedAt.coerceAtMost(watchRow.childCount - 1)) as? LinearLayout)?.getChildAt(0)
                ?: appRow.getChildAt(0))?.requestFocus()
        }
    }

    /**
     * Four posters across, each two apps-row columns wide. Progress sits on the artwork's bottom edge;
     * title and details (source, episode, time left) go underneath.
     */
    private fun watchCard(p: Prog, gapBefore: Boolean): View {
        val gap = dp(GRID_GAP)
        val w = (resources.displayMetrics.widthPixels - dp(96) - gap * 3) / 4
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (gapBefore) marginStart = gap
            }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(if (dark) 0xFF18181A.toInt() else 0xFFDDE1E5.toInt())
        }
        // Long titles scroll while their poster is focused instead of staying cut off.
        val title = label(p.title, 14f).apply {
            setPadding(0, dp(6), 0, 0)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
        }
        val frame = FrameLayout(this)
        style(frame, dp(8), card = true, lift = 1.1f) { focused ->
            title.isSelected = focused
            if (focused) {
                lastCard = frame
                showRowLabel(frame, false)
            }
        }
        frame.contentDescription = p.title
        frame.addView(img, MATCH_PARENT, MATCH_PARENT)
        frame.setOnClickListener { open(p) }
        frame.setTag(R.id.watchRow, p) // holding OK hides it (see onHold)
        // Standard 16:9 aspect ratio prevents cutting off channel numbers and provider badges
        val cardHeight = w * 9 / 16
        col.addView(frame, LinearLayout.LayoutParams(w, cardHeight))
        // Thin bar under the poster, card width. Kept (invisible) when there's no progress so titles line up.
        col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = p.progress
            progressDrawable = progressBarDrawable()
            visibility = if (p.progress > 0) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(w, dp(3)).apply { topMargin = dp(6) })
        col.addView(title)
        val icon = sourceIcon(p.pkg)
        if (p.sub.isNotEmpty() || icon != null) {
            col.addView(label(p.sub, 12f).apply {
                alpha = 0.8f
                setPadding(0, dp(2), 0, 0)
                icon?.let {
                    it.setBounds(0, 0, dp(12), dp(12))
                    setCompoundDrawablesRelative(it, null, null, null)
                    compoundDrawablePadding = dp(6)
                }
            })
        }
        loadPoster(img, p.image)
        return col
    }

    private fun progressBarDrawable(): Drawable {
        fun bar(color: Int) = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(color) }
        return LayerDrawable(arrayOf(
            bar(if (dark) 0x40FFFFFF else 0x26000000),
            ClipDrawable(bar(fg), Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun open(p: Prog) {
        prefs.edit().putLong("t_${p.pkg}", System.currentTimeMillis()).apply()
        try {
            startActivity(programIntent(p) ?: throw IllegalArgumentException("unsafe or missing intent"))
        } catch (e: Exception) {
            p.pkg?.let { packageManager.getLeanbackLaunchIntentForPackage(it) }?.let { startActivity(it) }
        }
    }

    /**
     * A card's intent, written by another app into the TV listings, made safe to start as Piume. One naming its
     * target may only name the app that published it (never Piume itself, never a third app); one that doesn't is
     * left to Android, which won't resolve it to anything not exported (a TV input's channel link is opened by the
     * TV's own app, not always the publisher). Either way it loses URI permission grants and any selector that
     * would swap in a different target. Null when it names a wrong target or doesn't parse.
     */
    private fun programIntent(p: Prog): Intent? {
        val intent = runCatching { Intent.parseUri(p.intent ?: return null, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        val target = intent.component?.packageName ?: intent.`package`
        if (target == packageName || (p.pkg != null && target != null && target != p.pkg)) return null
        intent.selector = null
        intent.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun loadPoster(v: ImageView, uri: String?) {
        if (uri == null) return
        posters.get(uri)?.let { v.setImageBitmap(it); fitPoster(v, it); return }
        v.tag = uri
        if (!loading.add(uri)) return // already on its way
        posterIo.execute {
            val bmp = try {
                decode(uri)
            } catch (e: Exception) {
                warn("poster failed: $uri ($e)") // retried by retryPosters()
                null
            } finally {
                loading.remove(uri)
            } ?: return@execute
            posters.put(uri, bmp)
            ui.post {
                if (v.tag == uri) {
                    v.setImageBitmap(bmp)
                    fitPoster(v, bmp)
                }
            }
        }
    }

    /**
     * Film and show art fills the card (cropped to 16:9). Art that isn't 16:9, or that sits on a plain background
     * (a TV app's channel cards: logo and name on a flat colour), is shown whole instead, inset from the edges on
     * its own background colour, so the name in it doesn't run into the card's border.
     */
    private fun fitPoster(v: ImageView, bmp: Bitmap) {
        val ratio = bmp.width.toFloat() / bmp.height
        // Plain background: the corners and the middle of each edge all the same colour (a film still rarely is).
        val r = bmp.width - 1
        val b = bmp.height - 1
        val edge = listOf(0 to 0, r / 2 to 0, r to 0, 0 to b / 2, r to b / 2, 0 to b, r / 2 to b, r to b).map { (x, y) -> bmp.getPixel(x, y) }
        fun near(c1: Int, c2: Int) = Math.abs(Color.red(c1) - Color.red(c2)) + Math.abs(Color.green(c1) - Color.green(c2)) + Math.abs(Color.blue(c1) - Color.blue(c2)) < 24
        val flat = edge.all { Color.alpha(it) == 255 && near(it, edge[0]) }
        if (Math.abs(ratio / (16f / 9f) - 1f) < 0.08f && !flat) {
            v.scaleType = ImageView.ScaleType.CENTER_CROP
            v.setPadding(0, 0, 0, 0)
            return
        }
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        v.setPadding(dp(12), dp(10), dp(12), dp(10))
        if (flat) v.setBackgroundColor(edge[0])
    }

    /** Cards whose poster never arrived (offline, server timeout) try again; loaded ones are left alone. */
    private fun retryPosters() {
        for (i in 0 until watchRow.childCount) {
            val img = ((watchRow.getChildAt(i) as? ViewGroup)?.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ImageView ?: continue
            if (img.drawable == null) loadPoster(img, img.tag as? String)
        }
    }

    /** Downsample to card size in RGB_565 so a dozen posters stay around 1-2 MB. */
    private fun decode(uri: String): Bitmap? {
        val bytes = openStream(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= dp(220)) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun openStream(uri: String): InputStream =
        if (uri.startsWith("http")) {
            (URL(uri).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }.inputStream
        } else {
            contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("no stream for $uri")
        }

    // ---------- shared view bits ----------

    /**
     * tvOS-style focus, no outline: the focused card lifts (scale + shadow). Light mode adds a soft
     * top sheen; dark mode brightens the glass gradient instead (a white sheen would haze it grey).
     * Only transform/alpha/colour change, so it stays on the GPU and costs no re-layout.
     */
    private fun style(v: View, radius: Int, card: Boolean, lift: Float = 1.08f, onFocus: ((Boolean) -> Unit)? = null) {
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x00FFFFFF)).apply {
            cornerRadius = radius.toFloat()
            alpha = 0
        }
        val bg = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (card) cardRest else intArrayOf(0, 0)).apply {
            cornerRadius = radius.toFloat()
        }
        v.background = bg
        if (!dark) v.foreground = sheen
        v.clipToOutline = true
        // Android 9+ only (8.x would crash on these); black is the default shadow colour before that anyway.
        if (Build.VERSION.SDK_INT >= 28) {
            v.outlineSpotShadowColor = Color.BLACK
            v.outlineAmbientShadowColor = Color.BLACK
        }
        v.elevation = if (card) dp(2).toFloat() else 0f
        v.isFocusable = true
        v.isClickable = true
        v.setOnLongClickListener { if (!holdFired) fireHold(v); true }
        v.setOnFocusChangeListener { _, focused ->
            sheen.alpha = if (focused) 255 else 0
            if (card) bg.colors = if (focused) cardLit else cardRest
            v.animate()
                .scaleX(if (focused) lift else 1f).scaleY(if (focused) lift else 1f)
                .translationZ(if (focused) dp(14).toFloat() else 0f)
                .setDuration(160).setInterpolator(DECELERATE).start()
            onFocus?.invoke(focused)
        }
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(fg)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        const val WRITE_SECURE = "android.permission.WRITE_SECURE_SETTINGS"
        const val ROLE_REQUEST = 2
        const val SPOTLIGHT_IDLE_MS = 15 * 60_000L // Spotlight stops turning after this long without a key press
        const val TMDB_FRESH_MS = 3 * 3600_000L // how long a trending list is kept before it's fetched again
        val SPOTLIGHT_PKGS = listOf("com.nuvio.tv", "com.nuvio.app", "com.stremio.one", "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom")
        // Settings sections, and each row's address in them (see renderSettings).
        const val SECTION = 100
        const val LOOK_SECTION = 0
        const val APPS_SECTION = 1
        const val WATCH_SECTION = 2
        const val SPOTLIGHT_SECTION = 3
        const val SCREENSAVER_SECTION = 4
        const val SYSTEM_SECTION = 5
        const val THEME_ROW = LOOK_SECTION * SECTION
        const val LIGHT_WP_ROW = THEME_ROW + 1
        const val DARK_WP_ROW = THEME_ROW + 2
        const val FADE_ROW = THEME_ROW + 3
        const val PHOTO_EVERY_ROW = THEME_ROW + 4
        const val WEATHER_ROW = THEME_ROW + 5
        const val WEATHER_UNITS_ROW = THEME_ROW + 6
        const val CITY_ROW = THEME_ROW + 7
        const val SORT_ROW = APPS_SECTION * SECTION + 1
        const val ROW_APPS_ROW = APPS_SECTION * SECTION + 2
        const val ALIGN_ROW = APPS_SECTION * SECTION + 3
        const val NAMES_ROW = APPS_SECTION * SECTION + 4
        const val ICONS_ROW = APPS_SECTION * SECTION + 5
        const val LOCK_ROW = APPS_SECTION * SECTION + 6
        const val CW_APPS_ROW = WATCH_SECTION * SECTION
        const val HIDDEN_ROW = WATCH_SECTION * SECTION + 1
        const val TMDB_KEY_ROW = SPOTLIGHT_SECTION * SECTION
        const val TMDB_LANG_ROW = TMDB_KEY_ROW + 1
        const val TMDB_TYPE_ROW = TMDB_KEY_ROW + 2
        const val TMDB_APPS_ROW = TMDB_KEY_ROW + 3
        const val TMDB_PROVIDERS_ROW = TMDB_KEY_ROW + 4
        const val TMDB_CYCLE_ROW = TMDB_KEY_ROW + 5
        const val SCREENSAVER_ROW = SCREENSAVER_SECTION * SECTION
        const val HOME_BUTTON_ROW = SYSTEM_SECTION * SECTION + 1 // after "Set up Home button"
        const val DEFAULT_HOME_ROW = HOME_BUTTON_ROW + 1
        const val UPDATE_ROW = HOME_BUTTON_ROW + 2
        const val PROBLEMS_ROW = HOME_BUTTON_ROW + 3
        val SPOTLIGHT_EVERY = listOf(0, 5, 8, 10, 15, 30) // seconds between Spotlight titles; 0 = off
        val ROW_ALIGN = linkedMapOf( // setting -> its name, and the row's gravity in the scroller
            "left" to (R.string.align_left to Gravity.START),
            "centre" to (R.string.align_centre to Gravity.CENTER_HORIZONTAL),
            "right" to (R.string.align_right to Gravity.END),
        )
        const val BADGE = 0xFFFF453A.toInt()
        const val REPO = "mccoy88f/Piume_Launcher" // where Piume's releases are published
        const val HELP_URL = "https://github.com/$REPO#home-button-on-android-13-and-newer"
        const val TOGGLE_ON = "\u0000on" // row values drawn as a switch instead of text
        const val TOGGLE_OFF = "\u0000off"
        const val PIN_TRIES = 5 // wrong PINs before a 30 s wait
        const val UNLOCKED_MS = 5 * 60_000L
        const val MEDIA_REQUEST = 3
        const val PER_APP = 3 // Continue Watching items per app
        val READ_PHOTOS = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_IMAGES" else "android.permission.READ_EXTERNAL_STORAGE"
        /** Minutes between folder photos. */
        val PHOTO_EVERY = listOf(1, 5, 10, 15, 30, 60, 1440)
        const val CHOSEN = "✓"
        const val WRITE_EPG ="com.android.providers.tv.permission.WRITE_EPG_DATA"
        const val TV_SETTINGS = "com.android.tv.settings"
        const val HDMI = "hdmi:" // App.pkg prefix for HDMI device tiles; the rest is the TV input id
        const val ROW_TILES = 8
        const val GRID_GAP = 16 // dp; shared by the apps row and the cards so their columns line up
        val BUNDLED_WALLPAPERS = linkedMapOf(
            "Rockies (day)" to R.drawable.wallpaper,
            "Rockies (dusk)" to R.drawable.wallpaper_dark,
        )
        val PIN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        )
        val EDIT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        val GREYSCALE = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        val DECELERATE = android.view.animation.DecelerateInterpolator(1.5f)
        @Volatile var APPS: List<App>? = null
        /** Mono marks made by glyph(), by package; null = icon has no usable symbol. Main thread only. */
        val GLYPHS = HashMap<String, Bitmap?>()
        /** accent() colours by package; null = no clear colour. Main thread only. */
        val ACCENTS = HashMap<String, Int?>()

        /**
         * Colour mode tints for the bundled marks: Simple Icons' brand hex, except where that's too dark to
         * read on dark cards (PlayStation, Prime Video) or grey (Google Play), where the brand's brighter blue/green is used.
         */
        val BRAND_COLOURS = mapOf(
            "playstation" to 0xFF0070D1.toInt(),
            "com.nuvio.app" to 0xFF6C5CE7.toInt(),
            "com.nuvio.tv" to 0xFF6C5CE7.toInt(), // Nuvio's GitHub build
            "com.netflix.ninja" to 0xFFE50914.toInt(),
            "com.stremio.one" to 0xFF8D3DAF.toInt(),
            "io.gh.reisxd.tizentube.cobalt" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tv" to 0xFFFF0000.toInt(),
            "com.google.android.youtube.tvmusic" to 0xFFFF0000.toInt(),
            "com.amazon.amazonvideo.livingroom" to 0xFF00A8E1.toInt(),
            "com.spotify.tv.android" to 0xFF1DB954.toInt(),
            "org.videolan.vlc" to 0xFFFF8800.toInt(),
            "com.android.vending" to 0xFF01875F.toInt(),
        )

        val DEFAULT_ORDER = listOf(
            "com.netflix.ninja",
            "com.stremio.one",
            "io.gh.reisxd.tizentube.cobalt",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android",
        )

        /** Monochrome brand marks (Simple Icons, CC0). `true` = wordmark, shown without a label. */
        val LOGOS = mapOf(
            "com.netflix.ninja" to (R.drawable.logo_netflix to false),
            "com.stremio.one" to (R.drawable.logo_stremio to false),
            "io.gh.reisxd.tizentube.cobalt" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tv" to (R.drawable.logo_youtube to false),
            "com.google.android.youtube.tvmusic" to (R.drawable.logo_youtubemusic to false),
            "com.amazon.amazonvideo.livingroom" to (R.drawable.logo_primevideo to true),
            "com.spotify.tv.android" to (R.drawable.logo_spotify to false),
            "org.videolan.vlc" to (R.drawable.logo_vlcmediaplayer to false),
            "com.android.vending" to (R.drawable.logo_googleplay to false),
        )
    }
}
