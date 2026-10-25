package app.tuner.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import app.tuner.core.model.GroupType
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Navigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.home.HomeScreen
import app.tuner.tv.ui.home.WelcomeScreen
import app.tuner.tv.ui.player.LivePlayerScreen
import app.tuner.tv.ui.player.PlayerKeys
import app.tuner.tv.ui.player.LiveSession
import app.tuner.tv.ui.player.VodPlayerScreen
import app.tuner.tv.ui.settings.SourceEditScreen
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.ui.theme.TunerTheme
import app.tuner.tv.ui.vod.MovieDetailScreen
import app.tuner.tv.ui.vod.SeriesDetailScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TunerTheme {
                val start by produceState<Screen?>(null) {
                    value = if (core.hasSources()) Screen.Home else Screen.Welcome
                }
                Box(Modifier.fillMaxSize().background(Palette.Background)) {
                    start?.let { App(it) }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Don't keep streaming (and holding a provider connection) in the background.
        playerController.stop()
        core.lockSession()
    }

    /** The movie player's keys go to it first, whatever Compose or Media3 think has focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        PlayerKeys.handler?.invoke(event) == true || super.dispatchKeyEvent(event)

    override fun onStart() {
        super.onStart()
        core.refreshStale()
    }

    @Composable
    private fun App(start: Screen) {
        val nav = remember { Navigator(start) }
        CompositionLocalProvider(LocalNavigator provides nav) {
            LaunchedEffect(Unit) {
                if (start == Screen.Home && core.settings.autoplayLastChannel) {
                    core.lastChannel()?.takeIf { core.isAllowed(it.locked) }?.let { ch ->
                        val req = core.playChannel(ch)
                        // Zap through the channel's own group, not a one-channel lineup.
                        val group = core.liveGroups(false).firstOrNull { it.type == GroupType.CATEGORY && it.sourceId == ch.sourceId && it.key == ch.categoryKey }
                        val lineup = group?.takeIf { core.isAllowed(it.locked) }?.let { runCatching { core.channels(it, false) }.getOrNull() }
                        LiveSession.start(lineup?.takeIf { l -> l.any { it.id == ch.id } } ?: listOf(ch), ch, req)
                        nav.push(Screen.LivePlayer)
                    }
                }
            }
            // The sleep timer stopped playback: leave the player for the guide.
            LaunchedEffect(Unit) {
                snapshotFlow { playerController.sleepFired }.collect {
                    while (nav.current == Screen.LivePlayer || nav.current == Screen.VodPlayer) nav.pop()
                }
            }
            LaunchedEffect(nav) {
                snapshotFlow { nav.current }.collect { screen ->
                    val playing = screen == Screen.LivePlayer || screen == Screen.VodPlayer
                    if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            when (val screen = nav.current) {
                Screen.Home -> HomeScreen(onExit = { finish() })
                Screen.Welcome -> {
                    WelcomeScreen()
                    BackHandler { finish() }
                }
                is Screen.EditSource -> SourceEditScreen(screen.sourceId)
                Screen.LivePlayer -> LivePlayerScreen()
                Screen.VodPlayer -> VodPlayerScreen()
                is Screen.MovieDetail -> MovieDetailScreen(screen.movieId)
                is Screen.SeriesDetail -> SeriesDetailScreen(screen.seriesId)
            }
        }
    }
}
