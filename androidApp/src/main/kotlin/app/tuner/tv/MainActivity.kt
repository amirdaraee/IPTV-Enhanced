package app.tuner.tv

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.tuner.core.model.GroupType
import app.tuner.tv.ui.TouchUi
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
        val touch = !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        if (touch) {
            // Full screen like a TV; the system bars come back with a swipe from the edge.
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            // Draw the background under the camera cutout too (the content is padded clear of it).
            // Before Android 15 the window isn't edge-to-edge unless asked.
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
        setContent {
            TunerTheme {
                val start by produceState<Screen?>(null) {
                    value = if (core.hasSources()) Screen.Home else Screen.Welcome
                }
                // The camera cutout is left black (like the player's bars), and nothing is drawn under it.
                Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout).background(Palette.Background)) {
                    TouchUi(touch) { start?.let { App(it) } }
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
