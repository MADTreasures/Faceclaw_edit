package com.madtreasures.faceclaw.app.platform

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController as AndroidMediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.madtreasures.faceclaw.core.gfx.GrayBitmap
import com.madtreasures.faceclaw.core.platform.MediaController
import com.madtreasures.faceclaw.core.platform.MediaState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Follows the phone's active media session (needs notification access). */
class AndroidMedia(private val context: Context) : MediaController {
    private val _state = MutableStateFlow<MediaState?>(null)
    override val state: StateFlow<MediaState?> = _state
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val component = ComponentName(context, NotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var current: AndroidMediaController? = null
    private var listening = false
    private var lastArtKey: Any? = null
    private var lastArt: GrayBitmap? = null

    /** Apps that publish sessions for embedded clips rather than music. */
    private val ignored = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
        "com.facebook.katana", "com.facebook.orca", "com.instagram.android", "com.whatsapp",
        "org.telegram.messenger", "com.snapchat.android", "com.twitter.android", "com.discord",
    )

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    private val callback = object : AndroidMediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    /** Call when notification access may have changed. */
    fun refresh() {
        main.post {
            try {
                if (!listening) {
                    msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                    listening = true
                }
                choose(msm.getActiveSessions(component))
            } catch (_: SecurityException) {
                _state.value = null
            }
        }
    }

    private fun choose(sessions: List<AndroidMediaController>) {
        val candidates = sessions.filter { it.packageName !in ignored }
        val pick = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: candidates.firstOrNull()
        if (pick?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = pick
            pick?.registerCallback(callback, main)
        }
        publish()
    }

    private fun publish() {
        val c = current
        if (c == null) {
            _state.value = null
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        val playing = ps?.state == PlaybackState.STATE_PLAYING || ps?.state == PlaybackState.STATE_BUFFERING
        var pos = ps?.position ?: 0L
        if (ps != null && ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        val appName = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        _state.value = MediaState(
            sourceApp = appName,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: 0,
            positionMs = pos.coerceAtLeast(0),
            positionSampledAtMs = System.currentTimeMillis(),
            playing = playing,
            art = art(md),
        )
    }

    private fun art(md: MediaMetadata?): GrayBitmap? {
        val bmp: Bitmap = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: return null
        if (bmp === lastArtKey) return lastArt
        val size = 150
        val scaled = bmp.scale(size, size)
        val px = IntArray(size * size)
        scaled.getPixels(px, 0, size, 0, 0, size, size)
        if (scaled !== bmp) scaled.recycle()
        lastArtKey = bmp
        lastArt = GrayBitmap.fromArgb(size, size, px)
        return lastArt
    }

    override fun playPause() {
        val c = current ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    override fun next() { current?.transportControls?.skipToNext() }
    override fun previous() { current?.transportControls?.skipToPrevious() }
    override fun volumeUp() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
    override fun volumeDown() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
    override fun seekTo(positionMs: Long) { current?.transportControls?.seekTo(positionMs) }
}
