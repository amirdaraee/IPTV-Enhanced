package app.tuner.tv.ui.player

import app.tuner.core.model.Channel
import app.tuner.core.model.PlaybackRequest

/** The lineup the fullscreen live player zaps through (the group it was opened from). */
object LiveSession {
    var channels: List<Channel> = emptyList()
        private set
    var index: Int = 0
    var pendingRequest: PlaybackRequest? = null

    fun start(lineup: List<Channel>, channel: Channel, request: PlaybackRequest) {
        channels = lineup.ifEmpty { listOf(channel) }
        index = channels.indexOfFirst { it.id == channel.id }.coerceAtLeast(0)
        pendingRequest = request
    }

    val channel: Channel? get() = channels.getOrNull(index)
}
