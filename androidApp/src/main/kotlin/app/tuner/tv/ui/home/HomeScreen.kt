package app.tuner.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.model.SyncState
import app.tuner.tv.core
import app.tuner.tv.ui.components.ConfirmDialog
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.guide.GuideScreen
import app.tuner.tv.ui.search.SearchScreen
import app.tuner.tv.ui.settings.SettingsScreen
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.ui.vod.MoviesScreen
import app.tuner.tv.ui.vod.SeriesScreen
import kotlinx.coroutines.delay

private enum class Tab(val label: String, val icon: ImageVector) {
    Live("Live TV", Icons.Outlined.LiveTv),
    Movies("Movies", Icons.Outlined.Movie),
    Series("Series", Icons.Outlined.VideoLibrary),
    Search("Search", Icons.Outlined.Search),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Left rail (expands when focused) + the selected tab. The guide is the default tab. */
@Composable
fun HomeScreen(onExit: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Live) }
    var railFocused by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf<List<SyncState>>(emptyList()) }
    val contentFocus = remember { FocusRequester() }
    val tabFocus = remember { Tab.entries.associateWith { FocusRequester() } }

    DisposableEffect(Unit) {
        val w = core.watchSync { syncing = it.filter { s -> s.running } }
        onDispose { w.cancel() }
    }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { contentFocus.requestFocus() }
    }

    BackHandler(enabled = railFocused) { confirmExit = true }
    BackHandler(enabled = !railFocused && tab != Tab.Live) { runCatching { tabFocus.getValue(tab).requestFocus() } }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(Modifier.fillMaxSize()) {
            Spacer(Modifier.width(64.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    Tab.Live -> GuideScreen(contentFocus, onExitLeft = { runCatching { tabFocus.getValue(tab).requestFocus() } })
                    Tab.Movies -> MoviesScreen(contentFocus)
                    Tab.Series -> SeriesScreen(contentFocus)
                    Tab.Search -> SearchScreen(contentFocus)
                    Tab.Settings -> SettingsScreen(contentFocus)
                }
            }
        }
        // The rail overlays the content so expanding it doesn't reflow the guide.
        Column(
            Modifier
                .zIndex(1f)
                .fillMaxHeight()
                .animateContentSize()
                .width(if (railFocused) 220.dp else 64.dp)
                .background(if (railFocused) Palette.Surface.copy(alpha = 0.98f) else Palette.Background)
                .onFocusChanged { railFocused = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                        runCatching { contentFocus.requestFocus() }
                        true
                    } else false
                }
                .padding(vertical = 28.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (railFocused) "Tuner" else "T", style = MaterialTheme.typography.titleLarge, color = Palette.Accent,
                modifier = Modifier.padding(start = 14.dp, bottom = 20.dp),
            )
            Tab.entries.forEach { t ->
                FocusSurface(
                    onClick = { tab = t; runCatching { contentFocus.requestFocus() } },
                    modifier = Modifier.fillMaxWidth().focusRequester(tabFocus.getValue(t))
                        .onFocusChanged { if (it.isFocused) tab = t },
                    color = if (t == tab) Palette.SurfaceHigh else Color.Transparent,
                    scale = 1.02f,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(t.icon, t.label, tint = if (t == tab) Palette.Accent else Palette.TextDim, modifier = Modifier.size(24.dp))
                        if (railFocused) {
                            Spacer(Modifier.width(14.dp))
                            Text(t.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (syncing.isNotEmpty()) {
            val s = syncing.first()
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(16.dp))
                    .background(Palette.SurfaceHigh).padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    "Updating · ${s.phase}" + if (s.count > 0) " ${"%,d".format(s.count)}" else "",
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
                )
            }
        }
    }

    if (confirmExit) {
        ConfirmDialog("Exit Tuner?", "Playback will stop.", "Exit", onConfirm = onExit, onDismiss = { confirmExit = false })
    }
}
