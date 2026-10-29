package link.mccoy88f.zephyr

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a device, by hand: every channel of the playlist -e playlist <url>, its first stream played as the player does
 * (checked first, HLS through HlsAlign, headers), muted and with no screen, 10 s at most: whether it starts, and why not.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaylistScanTest {
    @Test fun scan() {
        val url = InstrumentationRegistry.getArguments().getString("playlist")
        assumeTrue(url != null)
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val list = Http.open(url!!, emptyMap(), 30_000).bufferedReader().use { M3u.read(it) }
        println("SCAN start ${list.channels.size} channels")
        for (c in list.channels) {
            val st = c.streams.firstOrNull() ?: continue
            val sUrl = st.getString("url")
            val h = st.optJSONObject("headers")
            val headers = h?.keys()?.asSequence()?.associateWith { h.optString(it) }.orEmpty()
            val probe = runCatching { Stremio.probe(sUrl, headers) }.getOrElse { Stremio.Probe(false, null, Stremio.whyOf(it)) }
            if (!probe.ok) { println("SCAN FAIL ${c.name} | check ${probe.why} | $sUrl"); continue }
            var result = "STUCK"
            val lock = Object()
            var p: ExoPlayer? = null
            inst.runOnMainSync {
                val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers)
                headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { http.setUserAgent(it.value) }
                p = ExoPlayer.Builder(ctx).build().also { pl ->
                    pl.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) synchronized(lock) { result = "OK"; lock.notifyAll() } }
                        override fun onPlayerError(e: PlaybackException) {
                            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                            synchronized(lock) { result = "ERROR ${e.errorCodeName}${code?.let { " $it" } ?: ""} ${Stremio.whyOf(e.cause) ?: ""}"; lock.notifyAll() }
                        }
                    })
                    val item = MediaItem.Builder().setUri(sUrl).apply { if (probe.format == "hls") setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
                    if (probe.format == "hls") pl.setMediaSource(HlsMediaSource.Factory(DefaultDataSource.Factory(ctx, http)).setExtractorFactory(HlsAlign()).createMediaSource(item))
                    else pl.setMediaItem(item)
                    pl.volume = 0f; pl.playWhenReady = true; pl.prepare()
                }
            }
            synchronized(lock) { if (result == "STUCK") lock.wait(10_000) }
            // Started: does it keep going a few seconds (a stream that plays and stalls)?
            if (result == "OK") { Thread.sleep(4_000); inst.runOnMainSync { if (p!!.playbackState != Player.STATE_READY) result = "STALLED after start" } }
            inst.runOnMainSync { p!!.release() }
            println("SCAN ${if (result == "OK") "OK  " else "FAIL"} ${c.name} | ${probe.format ?: "file"} | $result | $sUrl")
        }
        println("SCAN end")
    }
}
