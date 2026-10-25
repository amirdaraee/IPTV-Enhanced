package app.tuner.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import app.tuner.core.model.PlaybackRequest
import app.tuner.core.platform.DEFAULT_USER_AGENT
import app.tuner.tv.core
import okhttp3.OkHttpClient

data class TrackOption(val label: String, val group: Int, val track: Int, val selected: Boolean)

/**
 * Owns the single ExoPlayer instance shared by the guide preview and the
 * fullscreen player, so switching between them never re-buffers. Adds request
 * headers, retries with backoff and falls back to HLS when a URL without an
 * extension turns out to be a playlist.
 */
@OptIn(UnstableApi::class)
class PlayerController(private val context: Context, private val http: OkHttpClient) {
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var triedHls = false
    private var stallCheck: Runnable? = null

    var current: PlaybackRequest? by mutableStateOf(null)
        private set
    var status: String? by mutableStateOf(null)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var resizeMode by mutableStateOf(0)

    /** When the sleep timer stops playback (epoch millis), or null when it is off. */
    var sleepAt: Long? by mutableStateOf(null)
        private set
    /** Increments each time the sleep timer fires, so the UI can leave the player. */
    var sleepFired by mutableIntStateOf(0)
        private set
    /** The length the sleep timer was set to, for showing the chosen option. */
    var sleepMinutes by mutableIntStateOf(0)
        private set
    // Separate from [handler]: stop() clears that one, and the timer must outlive a channel change.
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleep = Runnable {
        sleepAt = null
        sleepMinutes = 0
        stop()
        sleepFired++
    }

    /** Stops playback after [minutes]; 0 turns the timer off. */
    fun setSleepTimer(minutes: Int) {
        sleepHandler.removeCallbacks(sleep)
        sleepMinutes = minutes
        sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else null
        if (minutes > 0) sleepHandler.postDelayed(sleep, minutes * 60_000L)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
                .build(),
        )
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isBuffering = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        retries = 0
                        status = null
                    }
                    if (state == Player.STATE_ENDED && current?.isLive == true) scheduleRetry("Stream ended")
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    this@PlayerController.isPlaying = playing
                }

                override fun onPlayerError(error: PlaybackException) = handleError(error)
            })
        }

    fun play(request: PlaybackRequest) {
        val same = current?.url == request.url
        current = request
        if (same && player.playbackState != Player.STATE_IDLE) {
            player.playWhenReady = true
            return
        }
        retries = 0
        triedHls = false
        status = null
        applyLanguages()
        prepare(request, forceHls = false)
    }

    /** The user's preferred audio and subtitle languages; also undoes a track picked by hand for the previous stream. */
    private fun applyLanguages() {
        val s = core.settings
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredAudioLanguage(s.audioLanguage.ifEmpty { null })
            .setPreferredTextLanguage(s.subtitleLanguage.ifEmpty { null })
            .build()
    }

    private fun prepare(request: PlaybackRequest, forceHls: Boolean) {
        val headers = buildMap {
            request.referrer?.let { put("Referer", it) }
        }
        // Timeouts and redirects (including http↔https) come from the shared client.
        val dataSource = OkHttpDataSource.Factory(http)
            .setUserAgent(request.userAgent ?: DEFAULT_USER_AGENT)
            .setDefaultRequestProperties(headers)
        val item = MediaItem.Builder()
            .setUri(Uri.parse(request.url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).setSubtitle(request.subtitle).build())
            .apply {
                val path = request.url.substringBefore('?').lowercase()
                when {
                    forceHls || path.endsWith(".m3u8") || path.endsWith(".m3u") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                    path.endsWith(".ts") -> setMimeType(MimeTypes.VIDEO_MP2T)
                }
                if (request.isLive) {
                    setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).build())
                }
            }
            .build()
        val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item)
        player.setMediaSource(source, if (request.startPositionMs > 0) request.startPositionMs else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
        watchForStalls()
    }

    private fun handleError(error: PlaybackException) {
        val req = current ?: return
        val cause = error.cause
        if (!triedHls && (cause is UnrecognizedInputFormatException || error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)) {
            triedHls = true
            prepare(req, forceHls = true)
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        scheduleRetry(describe(error))
    }

    private fun scheduleRetry(reason: String) {
        val req = current ?: return
        if (retries >= MAX_RETRIES) {
            status = "Playback failed: $reason"
            return
        }
        val delay = 1000L shl retries
        retries++
        status = "Reconnecting… ($retries/$MAX_RETRIES)"
        handler.postDelayed({
            if (current === req) {
                val pos = if (req.isLive) 0 else player.currentPosition
                prepare(req.copy(startPositionMs = pos), forceHls = triedHls)
            }
        }, delay)
    }

    /** Treats more than 10 s of continuous buffering on a live stream as a failure. */
    private fun watchForStalls() {
        stallCheck?.let(handler::removeCallbacks)
        var bufferingSince = 0L
        val check = object : Runnable {
            override fun run() {
                val req = current ?: return
                if (req.isLive && player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                    if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - bufferingSince > 10_000) {
                        bufferingSince = 0
                        scheduleRetry("Stream stalled")
                        return
                    }
                } else {
                    bufferingSince = 0
                }
                handler.postDelayed(this, 1000)
            }
        }
        stallCheck = check
        handler.postDelayed(check, 1000)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        current = null
        status = null
        player.stop()
        player.clearMediaItems()
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun resume() {
        player.playWhenReady = true
    }

    fun audioTracks(): List<TrackOption> = tracks(C.TRACK_TYPE_AUDIO)

    fun subtitleTracks(): List<TrackOption> = tracks(C.TRACK_TYPE_TEXT)

    private fun tracks(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        player.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val label = listOfNotNull(f.label, f.language?.uppercase(), f.channelCount.takeIf { it > 0 }?.let { "${it}ch" })
                    .joinToString(" · ").ifEmpty { "Track ${out.size + 1}" }
                out.add(TrackOption(label, gi, ti, g.isTrackSelected(ti)))
            }
        }
        return out
    }

    fun selectTrack(type: Int, option: TrackOption?) {
        val params = player.trackSelectionParameters.buildUpon()
        if (option == null) {
            params.setTrackTypeDisabled(type, true)
        } else {
            val group: Tracks.Group = player.currentTracks.groups[option.group]
            params.setTrackTypeDisabled(type, false)
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.track))
        }
        player.trackSelectionParameters = params.build()
    }

    private fun describe(e: PlaybackException) = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "server refused the stream"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "network error"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "unsupported format"
        else -> e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
    }

    companion object {
        const val MAX_RETRIES = 5
    }
}
