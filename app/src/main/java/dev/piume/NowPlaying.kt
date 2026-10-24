package dev.piume

import android.content.ComponentName
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.piume.MainActivity.Companion.WRITE_SECURE
import dev.piume.MainActivity.Prog

/**
 * Present only so Android lets Piume see the media sessions (what's playing in Spotify, YouTube…): reading them
 * needs an enabled notification listener. It reads no notifications.
 */
class PlayingListener : NotificationListenerService()

// What's playing now, from Android's media sessions: the first row of the Play queue tile, above Continue Watching.
// Functions of MainActivity, kept here by topic; their state lives in MainActivity.

internal fun MainActivity.listenerComponent() = ComponentName(this, PlayingListener::class.java)

/**
 * Turns Piume's listener on by itself where it may (the adb-granted WRITE_SECURE_SETTINGS, like the Home service);
 * otherwise `adb shell cmd notification allow_listener dev.piume/dev.piume.PlayingListener` does it.
 */
internal fun MainActivity.enableListener() {
    if (checkSelfPermission(WRITE_SECURE) != PackageManager.PERMISSION_GRANTED) return
    val me = listenerComponent().flattenToString()
    val on = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
    if (me in on.split(':')) return
    runCatching { Settings.Secure.putString(contentResolver, "enabled_notification_listeners", if (on.isEmpty()) me else "$on:$me") }
        .onFailure { warn("can't turn on the media listener ($it)") }
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
    val sessions = getSystemService(MediaSessionManager::class.java) ?: return
    fun follow(list: List<MediaController>?) = list.orEmpty().forEach { c ->
        if (!followed.add(c.sessionToken)) return@forEach
        var last = c.playbackState?.state
        runCatching {
            c.registerCallback(object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = playingChanged()
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
    }.onFailure { warn("can't follow the media sessions ($it)") }
}

private val followed = HashSet<android.media.session.MediaSession.Token>()

private fun MainActivity.playingChanged() {
    ui.removeCallbacks(playingRefresh)
    ui.postDelayed(playingRefresh, 500) // one rebuild for a burst of changes (a new track: metadata, then state)
}
