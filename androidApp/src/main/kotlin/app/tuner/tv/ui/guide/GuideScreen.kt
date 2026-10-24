package app.tuner.tv.ui.guide

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.LockedException
import app.tuner.core.model.Channel
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Group
import app.tuner.core.model.GroupType
import app.tuner.core.model.PlaybackRequest
import app.tuner.core.model.Programme
import app.tuner.tv.core
import app.tuner.tv.playerController
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.Artwork
import app.tuner.tv.ui.components.Badge
import app.tuner.tv.ui.components.ContextMenu
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.EmptyState
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.MenuAction
import app.tuner.tv.ui.components.PinDialog
import app.tuner.tv.ui.components.PrimaryButton
import app.tuner.tv.ui.components.ProgressBar
import app.tuner.tv.ui.guide.GuideViewModel.Companion.HOUR
import app.tuner.tv.ui.guide.GuideViewModel.Companion.MINUTE
import app.tuner.tv.ui.guide.GuideViewModel.Companion.SLOT
import app.tuner.tv.ui.guide.GuideViewModel.Companion.align
import app.tuner.tv.ui.player.LiveSession
import app.tuner.tv.ui.player.VideoSurface
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.util.formatRange
import app.tuner.tv.util.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val ROW_HEIGHT = 56.dp
private val CHANNEL_WIDTH = 210.dp
private const val VIEWPORT_MINUTES = 150

/** Pending action that needs the parental PIN first. */
private class Gate(val title: String, val action: () -> Unit)

@Composable
fun GuideScreen(focusRequester: FocusRequester, onExitLeft: () -> Unit, vm: GuideViewModel = viewModel()) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var showGroups by remember { mutableStateOf(false) }
    // When the panel closes because focus left for the side menu, don't pull it back to the grid.
    var leavingToMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    var groupMenu by remember { mutableStateOf<Group?>(null) }
    var gate by remember { mutableStateOf<Gate?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val groupsFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (!vm.loaded) vm.reload()
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(message) {
        if (message != null) { delay(3000); message = null }
    }

    fun guarded(locked: Boolean, title: String, action: () -> Unit) {
        if (core.isAllowed(locked)) action() else gate = Gate(title, action)
    }

    fun request(block: suspend () -> PlaybackRequest?, then: (PlaybackRequest) -> Unit) {
        scope.launch {
            try {
                val r = block()
                if (r == null) message = "Not available" else then(r)
            } catch (e: LockedException) {
                message = e.message
            } catch (e: Exception) {
                message = e.message ?: "Could not start playback"
            }
        }
    }

    fun activate(channel: Channel, programme: Programme?) {
        val t = System.currentTimeMillis()
        guarded(channel.locked, channel.name) {
            if (programme != null && programme.stop <= t && channel.hasCatchup) {
                request({ core.playCatchup(channel, programme) }) { req ->
                    LiveSession.start(vm.channels, channel, req)
                    nav.push(Screen.LivePlayer)
                }
                return@guarded
            }
            val playingThis = playerController.current?.let { it.isLive && it.channelId == channel.id } == true
            if (playingThis) {
                LiveSession.start(vm.channels, channel, playerController.current!!)
                nav.push(Screen.LivePlayer)
            } else {
                request({ core.playChannel(channel) }) { req -> playerController.play(req) }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize()) {
            GuideHeader(vm, now)
            if (vm.loaded && vm.channels.isEmpty()) {
                EmptyState(
                    title = if (vm.group?.type == GroupType.FAVORITES) "No favorites yet" else "No channels here",
                    message = "Press Back to choose another group, or long-press OK on a channel to add it to Favorites.",
                    action = { PrimaryButton("Choose group", onClick = { showGroups = true }, modifier = Modifier.focusRequester(focusRequester)) },
                )
            } else {
                GuideGrid(
                    vm = vm,
                    now = now,
                    listState = listState,
                    modifier = Modifier.focusRequester(focusRequester),
                    onActivate = { ch, p -> activate(ch, p) },
                    onMenu = { ch, p -> menuFor = ch to p },
                    onExitLeft = { showGroups = true },
                )
            }
        }

        AnimatedVisibility(
            visible = showGroups,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) {
            GroupsPanel(
                vm = vm,
                focusRequester = groupsFocus,
                onSelect = { g ->
                    guarded(g.locked, g.name) {
                        vm.selectGroup(g)
                        showGroups = false
                        scope.launch { listState.scrollToItem(0); delay(50); runCatching { focusRequester.requestFocus() } }
                    }
                },
                onMenu = { groupMenu = it },
                onLeft = { leavingToMenu = true; showGroups = false; onExitLeft() },
            )
        }
        message?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
    }

    LaunchedEffect(showGroups) {
        if (showGroups) {
            delay(50)
            runCatching { groupsFocus.requestFocus() }
        } else if (!leavingToMenu) {
            runCatching { focusRequester.requestFocus() }
        }
        leavingToMenu = false
    }
    BackHandler(enabled = showGroups) { leavingToMenu = true; showGroups = false; onExitLeft() }
    BackHandler(enabled = !showGroups) { showGroups = true }

    menuFor?.let { (ch, p) ->
        val t = System.currentTimeMillis()
        val actions = buildList {
            add(MenuAction(if (ch.favorite) "Remove from Favorites" else "Add to Favorites") {
                scope.launch { core.setFavorite(ContentKind.LIVE, ch.sourceId, ch.key, !ch.favorite) }
            })
            if (p != null && p.stop <= t && ch.hasCatchup) add(MenuAction("Watch from start", formatRange(p.start, p.stop)) { activate(ch, p) })
            if (p != null && p.isLive(t) && ch.hasCatchup) add(MenuAction("Restart this programme") {
                guarded(ch.locked, ch.name) {
                    request({ core.playCatchup(ch, p) }) { req -> LiveSession.start(vm.channels, ch, req); nav.push(Screen.LivePlayer) }
                }
            })
            add(MenuAction("Play fullscreen") {
                guarded(ch.locked, ch.name) {
                    request({ core.playChannel(ch) }) { req -> playerController.play(req); LiveSession.start(vm.channels, ch, req); nav.push(Screen.LivePlayer) }
                }
            })
            add(MenuAction("Hide channel") { scope.launch { core.setHidden(ContentKind.LIVE, ch.sourceId, ch.key, true) } })
            if (core.hasPin()) add(MenuAction(if (ch.locked) "Unlock channel" else "Lock channel") {
                guarded(true, "Change lock") { scope.launch { core.setLocked(ContentKind.LIVE, ch.sourceId, ch.key, !ch.locked) } }
            })
        }
        ContextMenu(ch.name, p?.title, actions) { menuFor = null }
    }
    groupMenu?.let { g ->
        val actions = buildList {
            if (g.type == GroupType.CATEGORY) {
                add(MenuAction("Move up") { scope.launch { core.moveGroup(g, -1) } })
                add(MenuAction("Move down") { scope.launch { core.moveGroup(g, 1) } })
                add(MenuAction("Hide group") { scope.launch { core.setGroupHidden(g, true) } })
                if (core.hasPin()) add(MenuAction(if (g.locked) "Unlock group" else "Lock group") {
                    guarded(true, "Change lock") { scope.launch { core.setGroupLocked(g, !g.locked) } }
                })
            }
        }
        if (actions.isEmpty()) groupMenu = null else ContextMenu(g.name, g.sourceName, actions) { groupMenu = null }
    }
    gate?.let { g ->
        PinDialog("Parental PIN", "Enter the PIN to open \"${g.title}\"", onSubmit = { pin ->
            if (core.unlock(pin)) { gate = null; g.action(); true } else false
        }, onDismiss = { gate = null })
    }
}

@Composable
private fun GuideHeader(vm: GuideViewModel, now: Long) {
    val ch = vm.focusedChannel()
    val p = vm.focusedProgramme()
    Row(
        Modifier.fillMaxWidth().height(196.dp).background(Brush.verticalGradient(listOf(Palette.Surface, Palette.Background)))
            .padding(start = 28.dp, end = 24.dp, top = 20.dp, bottom = 12.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(vm.group?.name ?: "", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
                if (ch != null) {
                    Text("  ·  ${ch.number?.let { "$it  " } ?: ""}${ch.name}", style = MaterialTheme.typography.labelLarge, color = Palette.TextDim, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                p?.title ?: if (ch != null) ch.name else "",
                style = MaterialTheme.typography.headlineSmall, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (p != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.isLive(now)) { Badge("LIVE", Palette.Live.copy(alpha = 0.2f), Palette.Live); Spacer(Modifier.width(8.dp)) }
                    else if (p.stop <= now && ch?.hasCatchup == true) { Badge("CATCH-UP"); Spacer(Modifier.width(8.dp)) }
                    Dim(listOfNotNull(formatRange(p.start, p.stop), p.episodeNum, p.category).joinToString("  ·  "))
                }
                if (p.isLive(now)) ProgressBar(p.progress(now), Modifier.width(220.dp).padding(top = 8.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    p.description ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            } else if (ch != null) {
                Dim("No programme information")
            }
        }
        Spacer(Modifier.width(24.dp))
        val playing = playerController.current?.isLive == true
        Box(
            Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                VideoSurface(Modifier.fillMaxSize())
                playerController.status?.let { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp)) }
            } else {
                Dim("Press OK to preview")
            }
        }
    }
}

@Composable
private fun GuideGrid(
    vm: GuideViewModel,
    now: Long,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    onActivate: (Channel, Programme?) -> Unit,
    onMenu: (Channel, Programme?) -> Unit,
    onExitLeft: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longPressed by remember { mutableStateOf(false) }
    val minTime = now - core.settings.guidePastDays * 24 * HOUR
    val maxTime = now + core.settings.guideFutureDays * 24 * HOUR

    // Lazy-load programmes for rows on screen (plus a margin).
    LaunchedEffect(vm.lineupVersion, vm.windowStart) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }
            .collect { (first, count) -> vm.ensureLoaded((first - 10).coerceAtLeast(0)..(first + count + 20)) }
    }
    LaunchedEffect(vm.row) {
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        if (vm.row <= first || vm.row >= last) {
            listState.scrollToItem((vm.row - 2).coerceAtLeast(0))
        }
    }

    fun current(): Programme? = vm.focusedProgramme()

    fun moveTime(forward: Boolean) {
        val viewportMs = VIEWPORT_MINUTES * MINUTE
        val p = current()
        val list = vm.focusedChannel()?.let { vm.programmes[it.id] }.orEmpty()
        if (forward) {
            val target = p?.stop ?: (align(vm.focusTime) + SLOT)
            if (target >= maxTime) return
            vm.focusTime = maxOf(target, vm.windowStart)
            val next = list.firstOrNull { it.start >= target }
            val visibleEnd = (next?.start ?: target) + 20 * MINUTE
            if (visibleEnd > vm.windowStart + viewportMs) vm.windowStart = align(next?.start ?: target)
        } else {
            val target = (p?.start ?: align(vm.focusTime - 1)) - 1
            if (target < minTime) { onExitLeft(); return }
            val prev = list.lastOrNull { it.start <= target && it.stop > target }
            vm.focusTime = target
            val start = prev?.start ?: align(target)
            if (start < vm.windowStart) vm.windowStart = maxOf(align(start), align(minTime))
            if (prev == null && align(target) < vm.windowStart) vm.windowStart = align(target)
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                val isOk = code == NativeKeyEvent.KEYCODE_DPAD_CENTER || code == NativeKeyEvent.KEYCODE_ENTER || code == NativeKeyEvent.KEYCODE_NUMPAD_ENTER
                if (isOk) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 1 && !longPressed) {
                            longPressed = true
                            vm.focusedChannel()?.let { onMenu(it, current()) }
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyUp) {
                        if (!longPressed) vm.focusedChannel()?.let { onActivate(it, current()) }
                        longPressed = false
                        return@onPreviewKeyEvent true
                    }
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { if (vm.row < vm.channels.lastIndex) vm.row++; true }
                    Key.DirectionUp -> { if (vm.row > 0) vm.row--; true }
                    Key.DirectionRight -> { moveTime(true); true }
                    Key.DirectionLeft -> { moveTime(false); true }
                    Key.PageDown, Key.ChannelDown -> { vm.row = (vm.row + 8).coerceAtMost(vm.channels.lastIndex); true }
                    Key.PageUp, Key.ChannelUp -> { vm.row = (vm.row - 8).coerceAtLeast(0); true }
                    Key.Menu -> { vm.focusedChannel()?.let { onMenu(it, current()) }; true }
                    Key.MediaFastForward -> { vm.windowStart += 2 * HOUR; vm.focusTime = vm.windowStart; true }
                    Key.MediaRewind -> {
                        vm.windowStart = maxOf(vm.windowStart - 2 * HOUR, align(minTime)); vm.focusTime = vm.windowStart; true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val stripWidth = maxWidth - CHANNEL_WIDTH
        val dpPerMs = stripWidth.value / (VIEWPORT_MINUTES * MINUTE).toFloat()
        val windowStart = vm.windowStart
        val windowEnd = windowStart + VIEWPORT_MINUTES * MINUTE

        Column(Modifier.fillMaxSize()) {
            TimeRuler(windowStart, windowEnd, dpPerMs, now)
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                    itemsIndexed(vm.channels, key = { _, c -> c.id }) { index, ch ->
                        GuideRow(
                            channel = ch,
                            programmes = vm.programmes[ch.id],
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                            dpPerMs = dpPerMs,
                            now = now,
                            isFocusedRow = focused && index == vm.row,
                            focusTime = vm.focusTime,
                            playing = playerController.current?.channelId == ch.id,
                        )
                    }
                }
                if (now in windowStart until windowEnd) {
                    val x = CHANNEL_WIDTH + ((now - windowStart) * dpPerMs).dp
                    Box(Modifier.offset(x = x).width(2.dp).fillMaxHeight().background(Palette.Live.copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(windowStart: Long, windowEnd: Long, dpPerMs: Float, now: Long) {
    Box(Modifier.fillMaxWidth().height(30.dp).background(Palette.Background)) {
        Text(
            app.tuner.tv.util.formatDay(windowStart), style = MaterialTheme.typography.labelMedium, color = Palette.TextDim,
            modifier = Modifier.padding(start = 28.dp).align(Alignment.CenterStart),
        )
        var t = windowStart
        while (t < windowEnd) {
            val x = CHANNEL_WIDTH + ((t - windowStart) * dpPerMs).dp
            Row(Modifier.offset(x = x).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(1.dp).height(12.dp).background(Palette.Outline))
                Text(
                    "  " + formatTime(t), style = MaterialTheme.typography.labelMedium,
                    color = if (now in t until t + SLOT) Palette.Accent else Palette.TextDim,
                )
            }
            t += SLOT
        }
    }
}

@Composable
private fun GuideRow(
    channel: Channel,
    programmes: List<Programme>?,
    windowStart: Long,
    windowEnd: Long,
    dpPerMs: Float,
    now: Long,
    isFocusedRow: Boolean,
    focusTime: Long,
    playing: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 2.dp)) {
        Row(
            Modifier.width(CHANNEL_WIDTH).fillMaxHeight().padding(start = 16.dp, end = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isFocusedRow) Palette.SurfaceFocus else Palette.Surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                channel.number?.toString() ?: "", style = MaterialTheme.typography.labelMedium,
                color = if (playing) Palette.Accent else Palette.TextDim, modifier = Modifier.widthIn(min = 34.dp), maxLines = 1, softWrap = false,
            )
            Spacer(Modifier.width(4.dp))
            Artwork(channel.logo, channel.name, Modifier.size(width = 44.dp, height = 32.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit, fontSize = 11)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) Palette.Accent else Palette.Text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (channel.favorite) Text("★", fontSize = 10.sp, color = Palette.Warning)
                    if (channel.hasCatchup) Text("⟲", fontSize = 10.sp, color = Palette.Accent)
                    if (channel.locked) Text("🔒", fontSize = 9.sp)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val visible = programmes?.filter { it.stop > windowStart && it.start < windowEnd }.orEmpty()
            if (visible.isEmpty()) {
                ProgrammeCell(
                    title = if (programmes == null) "" else "No information",
                    subtitle = null, x = 0.dp, width = ((windowEnd - windowStart) * dpPerMs).dp,
                    focused = isFocusedRow, live = false, past = false, catchup = false, progress = null,
                )
            } else {
                for (p in visible) {
                    val s = maxOf(p.start, windowStart)
                    val e = minOf(p.stop, windowEnd)
                    ProgrammeCell(
                        title = p.title,
                        subtitle = if (p.start >= windowStart) formatTime(p.start) else "◀ " + formatTime(p.start),
                        x = ((s - windowStart) * dpPerMs).dp,
                        width = ((e - s) * dpPerMs).dp,
                        focused = isFocusedRow && focusTime >= p.start && focusTime < p.stop,
                        live = p.isLive(now),
                        past = p.stop <= now,
                        catchup = channel.hasCatchup && p.stop <= now && p.start > now - channel.catchupDays * 24 * HOUR,
                        progress = if (p.isLive(now)) p.progress(now) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    title: String,
    subtitle: String?,
    x: Dp,
    width: Dp,
    focused: Boolean,
    live: Boolean,
    past: Boolean,
    catchup: Boolean,
    progress: Float?,
) {
    val bg = when {
        focused -> Palette.Accent
        live -> Palette.SurfaceHigh
        else -> Palette.Surface
    }
    val fg = when {
        focused -> Color(0xFF00201C)
        past && !catchup -> Palette.TextDim
        else -> Palette.Text
    }
    Box(
        Modifier.offset(x = x).width((width - 3.dp).coerceAtLeast(2.dp)).fillMaxHeight()
            .clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (width > 36.dp) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (catchup) Text("⟲ ", fontSize = 11.sp, color = if (focused) fg else Palette.Accent)
                    Text(title, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (subtitle != null && width > 80.dp) {
                    Text(subtitle, fontSize = 11.sp, color = fg.copy(alpha = 0.7f), maxLines = 1)
                }
            }
        }
        if (progress != null) {
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).fillMaxWidth(progress).height(2.dp).background(if (focused) fg else Palette.Accent))
        }
    }
}

@Composable
private fun GroupsPanel(
    vm: GuideViewModel,
    focusRequester: FocusRequester,
    onSelect: (Group) -> Unit,
    onMenu: (Group) -> Unit,
    onLeft: () -> Unit,
) {
    val selectedIndex = vm.groups.indexOfFirst { it.id == vm.group?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 3).coerceAtLeast(0))
    Box(
        Modifier.width(300.dp).fillMaxHeight()
            .background(Palette.Surface)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onLeft(); true } else false
            },
    ) {
        Column(Modifier.padding(top = 24.dp)) {
            Text("Groups", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
            LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                itemsIndexed(vm.groups, key = { _, g -> g.id }) { i, g ->
                    val selected = g.id == vm.group?.id
                    FocusSurface(
                        onClick = { onSelect(g) },
                        onLongClick = { onMenu(g) },
                        modifier = Modifier.fillMaxWidth().then(if (i == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier),
                        color = if (selected) Palette.SurfaceHigh else Color.Transparent,
                        scale = 1.02f,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (g.type) { GroupType.FAVORITES -> "★  "; GroupType.RECENTS -> "↺  "; GroupType.ALL -> "≡  "; else -> "" } + g.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (selected) Palette.Accent else Palette.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (g.locked) Text("🔒", fontSize = 11.sp)
                            g.sourceName?.let { Dim(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.padding(bottom = 40.dp).clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
    ) { Text(text, color = Palette.Text) }
}
