package dev.piume

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.piume.MainActivity.Prog

/**
 * Present only so Android lets Piume see the media sessions (what's playing in Spotify, YouTube…): reading them
 * needs an enabled notification listener. It reads no notifications.
 */
class PlayingListener : NotificationListenerService()

// What's playing now, from Android's media sessions: the first row of the Play queue tile, above Continue Watching.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.listenerComponent() = ComponentName(this, PlayingListener::class.java)

/** The adb command that lets Piume see what's playing (Android lets only the system grant it; no app can). */
internal const val LISTENER_GRANT = "cmd notification allow_listener dev.piume/dev.piume.PlayingListener"

/** Whether Piume may see the media sessions (its listener is allowed). */
internal fun MainActivity.listenerAllowed(): Boolean =
    getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(listenerComponent())

/**
 * Where to allow it: Android's notification access screen, where the TV has one (most hide it); otherwise the adb
 * command, on the panel's page (see showListenerHelp).
 */
internal fun MainActivity.allowListener(back: () -> Unit) {
    try {
        startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        showListenerHelp(back)
    }
}

/** The adb command to allow it, in a box to copy, with a row back. */
internal fun MainActivity.showListenerHelp(back: () -> Unit) {
    val box = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        setPadding(dp(14), dp(4), dp(14), dp(12))
        addView(label(getString(R.string.now_playing_help), 13f).apply { maxLines = 6; setLineSpacing(0f, 1.15f) })
        addView(label("adb shell $LISTENER_GRANT", 12f).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 3
            ellipsize = null
            setPadding(0, dp(12), 0, 0)
        })
    }
    showPage(getString(R.string.setting_now_playing), listOf(page(getString(R.string.dialog_done)) { back() }), top = box, back = back)
}

/** The media sessions, or none when Piume may not see them (its listener is off). */
internal fun MainActivity.mediaSessions(): List<MediaController> = runCatching {
    getSystemService(MediaSessionManager::class.java).getActiveSessions(listenerComponent())
}.getOrDefault(emptyList())

/**
 * What's playing or paused in the media sessions, playing first, as cards: the title, the artist (or album, or
 * app) and whether it's paused, the art, and how far in it is. OK opens the app. Sessions with nothing loaded
 * are left out.
 */
internal fun MainActivity.nowPlaying(): List<Prog> = mediaSessions()
    .filter { it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }
    .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
    .mapNotNull { c ->
        val meta = c.metadata ?: return@mapNotNull null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val who = listOf(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM)
            .firstNotNullOfOrNull { meta.getString(it)?.takeIf { s -> s.isNotBlank() } } ?: appLabel(c.packageName)
        val paused = c.playbackState?.state == PlaybackState.STATE_PAUSED
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val position = c.playbackState?.position ?: 0
        val progress = if (duration > 0 && position > 0) (position * 100 / duration).toInt().coerceIn(1, 100) else 0
        // The art as a bitmap the session holds, kept under a key of its own for the card (see loadPoster); else its address.
        val bitmap = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            .firstNotNullOfOrNull { meta.getBitmap(it) }
        val image = if (bitmap != null) "session:${c.packageName}:${title.hashCode()}".also { posters.put(it, bitmap) }
            else listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { meta.getString(it)?.takeIf { s -> s.isNotBlank() } }
        val sub = if (paused) getString(R.string.playing_paused, who) else who
        val launch = packageManager.getLeanbackLaunchIntentForPackage(c.packageName) ?: packageManager.getLaunchIntentForPackage(c.packageName)
        Prog(title, sub, progress, image, launch?.toUri(android.content.Intent.URI_INTENT_SCHEME), c.packageName)
    }

/** Rebuilds the Play queue rows when what's playing changes (another track, play, pause, stop), while they show. */
internal fun MainActivity.watchSessions() {
    if (watching || !listenerAllowed()) return // already, or not allowed yet: tried again on each return home
    val sessions = getSystemService(MediaSessionManager::class.java) ?: return
    fun follow(list: List<MediaController>?) = list.orEmpty().forEach { c ->
        if (!followed.add(c.sessionToken)) return@forEach
        var last = c.playbackState?.state
        runCatching {
            c.registerCallback(object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    android.util.Log.d("PiumePlaying", "metadata changed: ${c.packageName}")
                    playingChanged()
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    if (state?.state == last) return // the position moving on: nothing to redraw
                    last = state?.state
                    playingChanged()
                }
                override fun onSessionDestroyed() {
                    followed.remove(c.sessionToken)
                    playingChanged()
                }
            }, ui)
        }
    }
    runCatching {
        sessions.addOnActiveSessionsChangedListener({ list -> follow(list); playingChanged() }, listenerComponent(), ui)
        follow(mediaSessions())
        watching = true
    }.onFailure { warn("can't follow the media sessions ($it)") }
}

private var watching = false
private val followed = HashSet<android.media.session.MediaSession.Token>()

private fun MainActivity.playingChanged() {
    ui.removeCallbacks(playingRefresh)
    ui.postDelayed(playingRefresh, 500) // one rebuild for a burst of changes (a new track: metadata, then state)
}
