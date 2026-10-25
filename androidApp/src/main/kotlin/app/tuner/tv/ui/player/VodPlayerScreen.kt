package app.tuner.tv.ui.player

import android.view.KeyEvent
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.model.PlaybackRequest
import app.tuner.tv.R
import app.tuner.tv.TunerApplication
import app.tuner.tv.core
import app.tuner.tv.playerController
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.components.ContextMenu
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.MenuAction
import app.tuner.tv.ui.components.PrimaryButton
import app.tuner.tv.ui.components.sleepActions
import app.tuner.tv.ui.components.sleepLabel
import app.tuner.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Set by the movie player while it is open; MainActivity offers it every key first. */
object PlayerKeys {
    var handler: ((KeyEvent) -> Boolean)? = null
}

/** Request handed to the VOD player by the movie/series screens. */
object VodSession {
    var request: PlaybackRequest? = null
}

/** Seconds the "Up next" card waits before playing the next episode. */
private const val UP_NEXT_SECONDS = 10

/**
 * Movies, episodes and catch-up with Media3's standard D-pad transport controls. Progress saves every 10 s.
 * When an episode ends, the next one plays after a short countdown; Menu opens the sleep timer.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf(VodSession.request) }
    var upNext by remember { mutableStateOf<PlaybackRequest?>(null) }
    var countdown by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
    var showMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val playNowFocus = remember { FocusRequester() }
    // Focus must go through Compose: focusing the embedded View directly leaves Compose's focus
    // elsewhere, and then no key reaches the player until a D-pad press happens to move it in.
    val playerFocus = remember { FocusRequester() }
    fun focusPlayer() { runCatching { playerFocus.requestFocus() } }
    var playerFocused by remember { mutableStateOf(false) }
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
    // The wrapper below is created once; it reads the latest state through this.
    val openMenu by rememberUpdatedState { if (upNext == null) showMenu = true }

    /** Menu opens options; with the controls hidden, Left/Right seek (10 s back, 30 s forward). */
    fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        // Keys this doesn't handle (OK, FF/RW, play/pause, the D-pad on the controls) are Media3's:
        // make sure its view really has focus before the activity dispatches the key on.
        if (upNext == null && !view.hasFocus()) view.requestFocus()
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.action == KeyEvent.ACTION_UP) openMenu()
            return true
        }
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && !view.isControllerFullyVisible && upNext == null) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                val delta = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 30_000L else -10_000L
                val end = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + delta).coerceIn(0, end))
                view.showController()
            }
            return true
        }
        return false
    }
    // PlayerView consumes D-pad keys itself (to show its controls), and the first key after the
    // screen opens doesn't reach it at all, so the activity hands keys to this first.
    val keys by rememberUpdatedState(::handleKey)
    DisposableEffect(Unit) {
        PlayerKeys.handler = { e -> !showMenu && !sleepMenu && keys(e) }
        onDispose { PlayerKeys.handler = null }
    }

    fun playNext() {
        val next = upNext ?: return
        upNext = null
        request = next
        playerController.play(next)
        // The card's button had focus; give it back to the player once the card is gone,
        // or the remote does nothing.
        scope.launch {
            delay(50)
            focusPlayer()
            view.showController()
        }
    }

    LaunchedEffect(Unit) {
        request?.let { playerController.play(it) }
        view.player = player
        view.showController()
        // Requests made before the view is attached (or while the previous screen still holds
        // focus) are dropped, so keep asking until the player really has it.
        repeat(20) {
            if (playerFocused) return@repeat
            focusPlayer()
            delay(150)
        }
        while (true) {
            delay(10_000)
            if (player.isPlaying) save()
        }
    }

    LaunchedEffect(upNext) {
        if (upNext == null) return@LaunchedEffect
        delay(50)
        runCatching { playNowFocus.requestFocus() }
        for (s in UP_NEXT_SECONDS downTo 1) {
            countdown = s
            delay(1000)
        }
        playNext()
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_ENDED) return
                val req = request ?: run { nav.pop(); return }
                scope.launch {
                    runCatching { core.saveProgress(req, player.duration, player.duration) }
                    val next = if (core.settings.autoplayNextEpisode) runCatching { core.nextEpisode(req) }.getOrNull() else null
                    if (next == null) nav.pop() else upNext = next
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
        when {
            upNext != null -> nav.pop()
            // Paused, buffering or failed, Media3 keeps the controls up and re-shows them, so
            // hiding them would just eat the press: leave instead.
            view.isControllerFullyVisible && player.isPlaying -> view.hideController()
            else -> nav.pop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxSize().focusRequester(playerFocus).onFocusChanged { playerFocused = it.hasFocus },
        )
        playerController.status?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(24.dp))
        }
        upNext?.let { next ->
            Column(
                Modifier.align(Alignment.BottomEnd).padding(40.dp).width(380.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Palette.Surface.copy(alpha = 0.95f)).padding(20.dp),
            ) {
                Dim("Up next · playing in $countdown s")
                Spacer(Modifier.height(6.dp))
                Text(next.title, style = MaterialTheme.typography.titleMedium, color = Palette.Text, maxLines = 2)
                next.subtitle?.let { Dim(it) }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Play now", onClick = { playNext() }, modifier = Modifier.focusRequester(playNowFocus))
                    PrimaryButton("Cancel", onClick = { nav.pop() })
                }
            }
        }
    }

    if (showMenu) {
        ContextMenu(request?.title ?: "Options", request?.subtitle, listOf(MenuAction("Sleep timer", sleepLabel()) { sleepMenu = true })) {
            showMenu = false
            if (!sleepMenu) focusPlayer()
        }
    }
    if (sleepMenu) {
        ContextMenu("Sleep timer", "Stops playback after", sleepActions()) {
            sleepMenu = false
            focusPlayer()
        }
    }
}
