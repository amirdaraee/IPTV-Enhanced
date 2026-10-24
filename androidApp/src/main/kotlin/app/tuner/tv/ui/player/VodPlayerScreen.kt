package app.tuner.tv.ui.player

import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import app.tuner.core.model.PlaybackRequest
import app.tuner.tv.R
import app.tuner.tv.TunerApplication
import app.tuner.tv.core
import app.tuner.tv.playerController
import app.tuner.tv.ui.LocalNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Request handed to the VOD player by the movie/series screens. */
object VodSession {
    var request: PlaybackRequest? = null
}

/** Movies, episodes and catch-up with Media3's standard D-pad transport controls. Progress saves every 10 s. */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val request = remember { VodSession.request }
    val player = playerController.player

    fun save() {
        val req = request ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0
        val position = player.currentPosition
        TunerApplication.instance.appScope.launch { runCatching { core.saveProgress(req, position, duration) } }
    }

    val view = remember {
        (LayoutInflater.from(context).inflate(R.layout.player_vod, null) as PlayerView).apply {
            controllerShowTimeoutMs = 4000
            setShowFastForwardButton(true)
            setShowRewindButton(true)
        }
    }

    LaunchedEffect(Unit) {
        request?.let { playerController.play(it) }
        view.player = player
        view.requestFocus()
        view.showController()
        while (true) {
            delay(10_000)
            if (player.isPlaying) save()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    request?.let { req -> scope.launch { runCatching { core.saveProgress(req, player.duration, player.duration) } } }
                    nav.pop()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            save()
            view.player = null
            playerController.stop()
        }
    }

    BackHandler {
        // Paused, buffering or failed, Media3 keeps the controls up and re-shows them, so
        // hiding them would just eat the press: leave instead.
        if (view.isControllerFullyVisible && player.isPlaying) view.hideController() else nav.pop()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        playerController.status?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        }
    }
}
