package app.tuner.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
import app.tuner.tv.R
import app.tuner.tv.core
import app.tuner.tv.ui.components.expiryText
import app.tuner.tv.ui.guide.Toast
import app.tuner.tv.playerController
import app.tuner.tv.ui.components.ConfirmDialog
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.guide.GuideScreen
import app.tuner.tv.ui.search.SearchScreen
import app.tuner.tv.ui.settings.SettingsScreen
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.ui.vod.MoviesScreen
import app.tuner.tv.ui.vod.SeriesScreen
import kotlinx.coroutines.delay

enum class Tab(val label: String, val icon: ImageVector) {
    Live("Live TV", Icons.Outlined.LiveTv),
    Movies("Movies", Icons.Outlined.Movie),
    Series("Series", Icons.Outlined.VideoLibrary),
    Search("Search", Icons.Outlined.Search),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Survives HomeScreen leaving composition (e.g. while a detail screen or player is open). */
object HomeState {
    var tab by mutableStateOf(Tab.Live)
    var searchQuery by mutableStateOf("")
    /** The subscription-expiry reminder is shown once per launch. */
    var expiryChecked = false
}

/** Accounts ending within this many days get a reminder at launch and a highlighted status in Settings. */
const val EXPIRY_WARNING_DAYS = 7

/** Left rail (expands when focused) + the selected tab. The guide is the default tab. */
@Composable
fun HomeScreen(onExit: () -> Unit) {
    var tab by HomeState::tab
    var railFocused by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf<List<SyncState>>(emptyList()) }
    val contentFocus = remember { FocusRequester() }
    val tabFocus = remember { Tab.entries.associateWith { FocusRequester() } }

    DisposableEffect(Unit) {
        val w = core.watchSync { syncing = it.filter { s -> s.running } }
        onDispose { w.cancel() }
    }
    var expiry by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { contentFocus.requestFocus() }
    }
    LaunchedEffect(Unit) {
        if (HomeState.expiryChecked) return@LaunchedEffect
        HomeState.expiryChecked = true
        val soon = runCatching { core.expiringSources(EXPIRY_WARNING_DAYS) }.getOrDefault(emptyList())
        expiry = soon.firstOrNull()?.let { s -> "Your subscription \"${s.name}\" ${expiryText(s)}" } ?: return@LaunchedEffect
        delay(10_000)
        expiry = null
    }

    // Registered first, so every screen's own Back comes before it: with nothing focused (a
    // touch screen after a tap elsewhere), Back on the guide asks to exit rather than closing the app.
    BackHandler(enabled = !railFocused && tab == Tab.Live) { confirmExit = true }
    // Back on the side menu goes home to Live TV first, then asks to exit.
    BackHandler(enabled = railFocused) {
        if (tab != Tab.Live) runCatching { tabFocus.getValue(Tab.Live).requestFocus() } else confirmExit = true
    }
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
                .background(if (railFocused) Palette.Surface else Palette.Background)
                .onFocusChanged { railFocused = it.hasFocus }
                // Entering the menu (Left from a screen) lands on the current tab, never on the
                // item that happens to be level with the focused element, which would switch tabs.
                .focusProperties { onEnter = { runCatching { tabFocus.getValue(tab).requestFocus() } } }
                .focusGroup()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                        runCatching { contentFocus.requestFocus() }
                        true
                    } else false
                }
                .padding(vertical = 28.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.padding(start = 6.dp, bottom = 20.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.brand_mark), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                if (railFocused) {
                    Text(
                        "IPTV Enhanced", style = MaterialTheme.typography.titleMedium, color = Palette.Accent, maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
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

    expiry?.let { Toast(it, Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter)) }

    if (confirmExit) {
        ConfirmDialog("Exit IPTV Enhanced?", if (playerController.current != null) "Playback will stop." else "", "Exit", onConfirm = onExit, onDismiss = { confirmExit = false })
    }
}
