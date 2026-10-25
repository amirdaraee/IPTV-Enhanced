package app.tuner.tv.ui.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.LockedException
import app.tuner.core.model.Channel
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Programme
import app.tuner.tv.core
import app.tuner.tv.playerController
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.components.Artwork
import app.tuner.tv.ui.components.Badge
import app.tuner.tv.ui.components.Clock
import app.tuner.tv.ui.components.ContextMenu
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.MenuAction
import app.tuner.tv.ui.components.PinDialog
import app.tuner.tv.ui.components.ProgressBar
import app.tuner.tv.ui.components.sleepActions
import app.tuner.tv.ui.components.sleepLabel
import app.tuner.tv.ui.guide.Toast
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.util.formatRange
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Zaps closer together than this are coalesced into one tune. */
private const val ZAP_SETTLE_MS = 350L

private val RESIZE_MODES = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill (crop)",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
)

/**
 * Fullscreen live TV. Remote grammar:
 * Up/Down zap · OK channel list · Left/Right info · long-press OK / Menu options ·
 * digits jump to a channel number · Last/0-double-press toggles previous channel · Back exits overlays, then the player.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen() {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var showInfo by remember { mutableStateOf(true) }
    var showList by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<Int?>(null) }
    var sleepMenu by remember { mutableStateOf(false) }
    var numberEntry by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var pinFor by remember { mutableStateOf<Channel?>(null) }
    var channel by remember { mutableStateOf(LiveSession.channel) }
    var nowNext by remember { mutableStateOf<List<Programme>>(emptyList()) }
    var infoJob by remember { mutableStateOf<Job?>(null) }
    var tuneJob by remember { mutableStateOf<Job?>(null) }
    var lastZap by remember { mutableStateOf(0L) }
    var longPressed by remember { mutableStateOf(false) }
    // Only a press that started here counts: the OK that opened this screen is released here.
    var okDown by remember { mutableStateOf(false) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    val listNowPlaying = remember { mutableStateMapOf<Long, String>() }
    val isCatchup = playerController.current?.let { !it.isLive } == true

    LaunchedEffect(Unit) {
        LiveSession.pendingRequest?.let { playerController.play(it) }
        LiveSession.pendingRequest = null
        rootFocus.requestFocus()
    }

    fun flashInfo() {
        showInfo = true
        infoJob?.cancel()
        infoJob = scope.launch {
            delay(core.settings.overlayTimeoutSec * 1000L)
            showInfo = false
        }
    }

    LaunchedEffect(channel?.id) {
        val ch = channel ?: return@LaunchedEffect
        nowNext = runCatching { core.upcoming(ch, 2) }.getOrDefault(emptyList())
        flashInfo()
    }

    fun tune(target: Channel, index: Int? = null, debounce: Boolean = false) {
        if (!core.isAllowed(target.locked)) {
            pinFor = target
            return
        }
        tuneJob?.cancel()
        tuneJob = scope.launch {
            // While the user is flicking through channels, only the one they stop on is
            // opened: a single-connection account would otherwise be refused mid-zap.
            if (debounce) {
                channel = target
                delay(ZAP_SETTLE_MS)
            }
            try {
                val req = core.playChannel(target)
                playerController.play(req)
                LiveSession.index = index ?: LiveSession.channels.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
                channel = target
            } catch (e: LockedException) {
                pinFor = target
            } catch (e: Exception) {
                message = e.message ?: "Could not play channel"
            }
        }
    }

    fun zap(delta: Int) {
        val list = LiveSession.channels
        if (list.isEmpty()) return
        val next = ((LiveSession.index + delta) % list.size + list.size) % list.size
        val t = System.currentTimeMillis()
        val rapid = t - lastZap < ZAP_SETTLE_MS * 2
        lastZap = t
        LiveSession.index = next
        tune(list[next], next, debounce = rapid)
    }

    fun jumpToNumber(number: Int) {
        val list = LiveSession.channels
        val idx = list.indexOfFirst { it.number == number }
        if (idx >= 0) tune(list[idx], idx) else message = "No channel $number in this group"
    }

    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        delay(1500)
        numberEntry.toIntOrNull()?.let(::jumpToNumber)
        numberEntry = ""
    }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }
    LaunchedEffect(showList) {
        if (showList) {
            delay(50)
            runCatching { listFocus.requestFocus() }
        } else {
            runCatching { rootFocus.requestFocus() }
        }
    }

    BackHandler {
        when {
            numberEntry.isNotEmpty() -> numberEntry = ""
            showList -> showList = false
            showInfo -> { infoJob?.cancel(); showInfo = false }
            else -> { LiveSession.returnTo = LiveSession.channel?.id; nav.pop() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (showList || showMenu) return@onPreviewKeyEvent false
                val code = e.key.nativeKeyCode
                val isOk = code == NativeKeyEvent.KEYCODE_DPAD_CENTER || code == NativeKeyEvent.KEYCODE_ENTER
                if (isOk) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) okDown = true
                        if (okDown && e.nativeKeyEvent.repeatCount == 1 && !longPressed) { longPressed = true; showMenu = true }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyUp) {
                        if (okDown && !longPressed) { if (isCatchup) flashInfo() else showList = true }
                        okDown = false
                        longPressed = false
                        return@onPreviewKeyEvent true
                    }
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (code in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9) {
                    numberEntry = (numberEntry + (code - NativeKeyEvent.KEYCODE_0)).take(4)
                    return@onPreviewKeyEvent true
                }
                when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
                    Key.DirectionLeft, Key.DirectionRight, Key.Info -> {
                        if (isCatchup && e.key != Key.Info) {
                            val delta = if (e.key == Key.DirectionRight) 30_000L else -15_000L
                            playerController.player.seekTo((playerController.player.currentPosition + delta).coerceAtLeast(0))
                        }
                        flashInfo(); true
                    }
                    Key.Menu -> { showMenu = true; true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (playerController.player.isPlaying) playerController.pause() else playerController.resume()
                        flashInfo(); true
                    }
                    Key.LastChannel -> {
                        scope.launch { core.previousChannel()?.let { tune(it) } ?: run { message = "No previous channel" } }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(Modifier.fillMaxSize(), RESIZE_MODES[resizeIndex].first)

        if (playerController.isBuffering || playerController.status != null) {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(16.dp)) {
                Text(playerController.status ?: "Buffering…", color = Color.White)
            }
        }

        AnimatedVisibility(showInfo && channel != null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            channel?.let { InfoBar(it, nowNext, isCatchup) }
        }

        if (numberEntry.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(32.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(numberEntry, fontSize = 40.sp, color = Palette.Accent)
            }
        }

        AnimatedVisibility(showList, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            ChannelListOverlay(
                current = channel,
                nowPlaying = listNowPlaying,
                onVisible = { range ->
                    // What's on now for rows scrolled into view: one guide query per screenful.
                    val wanted = range.mapNotNull { LiveSession.channels.getOrNull(it) }.filter { it.id !in listNowPlaying }
                    if (wanted.isNotEmpty()) {
                        wanted.forEach { listNowPlaying[it.id] = "" }
                        scope.launch {
                            val now = System.currentTimeMillis()
                            runCatching { core.guide(wanted, now, now + 1) }.getOrNull()?.forEach { row ->
                                row.programmes.firstOrNull()?.let { listNowPlaying[row.channel.id] = it.title }
                            }
                        }
                    }
                },
                focusRequester = listFocus,
                onSelect = { idx, ch -> showList = false; tune(ch, idx) },
            )
        }

        message?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) }
    }

    if (showMenu) {
        val ch = channel
        val actions = buildList {
            if (ch != null) add(MenuAction(if (ch.favorite) "Remove from Favorites" else "Add to Favorites") {
                scope.launch {
                    core.setFavorite(ContentKind.LIVE, ch.sourceId, ch.key, !ch.favorite)
                    channel = ch.copy(favorite = !ch.favorite)
                }
            })
            add(MenuAction("Audio track", playerController.audioTracks().firstOrNull { it.selected }?.label) { trackMenu = C.TRACK_TYPE_AUDIO })
            add(MenuAction("Subtitles", playerController.subtitleTracks().firstOrNull { it.selected }?.label ?: "Off") { trackMenu = C.TRACK_TYPE_TEXT })
            add(MenuAction("Picture", RESIZE_MODES[resizeIndex].second) { resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size })
            add(MenuAction("Sleep timer", sleepLabel()) { sleepMenu = true })
            add(MenuAction("Previous channel") { scope.launch { core.previousChannel()?.let { tune(it) } } })
            if (ch != null && isCatchup) add(MenuAction("Back to live") { tune(ch) })
        }
        ContextMenu(ch?.name ?: "Options", nowNext.firstOrNull()?.title, actions) {
            showMenu = false
            scope.launch { delay(50); runCatching { rootFocus.requestFocus() } }
        }
    }
    trackMenu?.let { type ->
        val options = if (type == C.TRACK_TYPE_AUDIO) playerController.audioTracks() else playerController.subtitleTracks()
        val actions = buildList {
            if (type == C.TRACK_TYPE_TEXT) add(MenuAction("Off") { playerController.selectTrack(type, null) })
            options.forEach { o -> add(MenuAction(o.label, if (o.selected) "✓" else null) { playerController.selectTrack(type, o) }) }
            if (isEmpty()) add(MenuAction("No tracks available") {})
        }
        ContextMenu(if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles", null, actions) {
            trackMenu = null
            scope.launch { delay(50); runCatching { rootFocus.requestFocus() } }
        }
    }
    if (sleepMenu) {
        ContextMenu("Sleep timer", "Stops playback after", sleepActions()) {
            sleepMenu = false
            scope.launch { delay(50); runCatching { rootFocus.requestFocus() } }
        }
    }
    pinFor?.let { target ->
        PinDialog("Parental PIN", "Enter the PIN to watch ${target.name}", onSubmit = { pin ->
            if (core.unlock(pin)) { pinFor = null; tune(target); true } else false
        }, onDismiss = { pinFor = null })
    }
}

@Composable
private fun InfoBar(channel: Channel, nowNext: List<Programme>, isCatchup: Boolean) {
    val now = System.currentTimeMillis()
    val current = nowNext.firstOrNull { it.isLive(now) }
    val next = nowNext.firstOrNull { it.start >= (current?.stop ?: now) }
    val request = playerController.current
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(channel.logo, channel.name, Modifier.size(width = 96.dp, height = 64.dp), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    channel.number?.let { Text("$it  ", style = MaterialTheme.typography.titleLarge, color = Palette.Accent) }
                    Text(channel.name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    if (isCatchup) Badge("CATCH-UP") else Badge("LIVE", Palette.Live.copy(alpha = 0.25f), Palette.Live)
                    if (channel.favorite) Text("  ★", color = Palette.Warning)
                    Spacer(Modifier.weight(1f))
                    Clock(style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(6.dp))
                if (isCatchup && request != null) {
                    Text(request.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                } else if (current != null) {
                    Text(current.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dim(formatRange(current.start, current.stop))
                        Spacer(Modifier.width(12.dp))
                        ProgressBar(current.progress(now), Modifier.width(240.dp))
                    }
                } else {
                    Dim("No programme information")
                }
                if (next != null && !isCatchup) {
                    Spacer(Modifier.height(4.dp))
                    Dim("Next  ${formatRange(next.start, next.stop)}  ${next.title}")
                }
            }
        }
    }
}

@Composable
private fun ChannelListOverlay(
    current: Channel?,
    nowPlaying: Map<Long, String>,
    onVisible: (IntRange) -> Unit,
    focusRequester: FocusRequester,
    onSelect: (Int, Channel) -> Unit,
) {
    val channels = LiveSession.channels
    val currentIndex = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 4).coerceAtLeast(0))
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.visibleItemsInfo.size }
            .collect { (first, count) -> onVisible(first..(first + count + 10)) }
    }
    Box(Modifier.width(400.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.95f), Color.Black.copy(alpha = 0.75f))))) {
        LazyColumn(state = state, modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                FocusSurface(
                    onClick = { onSelect(i, ch) },
                    modifier = Modifier.fillMaxWidth().then(if (i == currentIndex) Modifier.focusRequester(focusRequester) else Modifier),
                    color = if (ch.id == current?.id) Palette.SurfaceHigh else Color.Transparent,
                    scale = 1.02f,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(ch.number?.toString() ?: "", color = Palette.TextDim, modifier = Modifier.widthIn(min = 40.dp).padding(end = 4.dp), fontSize = 13.sp, maxLines = 1, softWrap = false)
                        Artwork(ch.logo, ch.name, Modifier.size(width = 44.dp, height = 30.dp), contentScale = ContentScale.Fit, fontSize = 11)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (ch.id == current?.id) Palette.Accent else Palette.Text)
                            nowPlaying[ch.id]?.takeIf { it.isNotEmpty() }?.let { Dim(it) }
                        }
                        if (ch.locked) Text("🔒", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
