package app.tuner.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.Changes
import app.tuner.core.model.Channel
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Group
import app.tuner.core.model.Source
import app.tuner.core.model.SourceType
import app.tuner.core.model.SyncState
import app.tuner.tv.BuildConfig
import app.tuner.tv.core
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.ConfirmDialog
import app.tuner.tv.ui.components.ContextMenu
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.MenuAction
import app.tuner.tv.ui.components.PinDialog
import app.tuner.tv.ui.components.SectionTitle
import app.tuner.tv.ui.theme.Palette
import kotlinx.coroutines.launch

private sealed interface PinFlow {
    data object Verify : PinFlow
    data object Enter : PinFlow
    data class Confirm(val first: String) : PinFlow
}

@Composable
fun SettingsScreen(focusRequester: FocusRequester) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<Source>>(emptyList()) }
    var syncStates by remember { mutableStateOf<Map<Long, SyncState>>(emptyMap()) }
    var settings by remember { mutableStateOf(core.settings) }
    var sourceMenu by remember { mutableStateOf<Source?>(null) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    var afterVerify by remember { mutableStateOf<(() -> Unit)?>(null) }
    var hiddenGroups by remember { mutableStateOf<List<Group>?>(null) }
    var hiddenChannels by remember { mutableStateOf<List<Channel>?>(null) }
    var hasPin by remember { mutableStateOf(core.hasPin()) }

    DisposableEffect(Unit) {
        val a = core.watchChanges(Changes.CATALOG) { scope.launch { sources = core.sources() } }
        val b = core.watchSync { list -> syncStates = list.associateBy { it.sourceId } }
        onDispose { a.cancel(); b.cancel() }
    }

    fun update(transform: (app.tuner.core.model.Settings) -> app.tuner.core.model.Settings) {
        settings = transform(settings)
        core.updateSettings(settings)
    }

    /** Settings that weaken parental control require the PIN first. */
    fun protected(action: () -> Unit) {
        if (!core.hasPin() || core.isAllowed(true)) action() else { afterVerify = action; pinFlow = PinFlow.Verify }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.Background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 40.dp, end = 64.dp, top = 28.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(12.dp)) }
        item { SectionTitle("Playlists") }
        items(sources, key = { it.id }) { s ->
            SourceRow(s, syncStates[s.id]) { sourceMenu = s }
        }
        item {
            SettingRow("Add playlist", "M3U or Xtream Codes", Modifier.focusRequester(focusRequester)) { nav.push(Screen.EditSource(null)) }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("TV guide") }
        item {
            SettingRow("Keep past programmes", "${settings.guidePastDays} day(s)") {
                update { it.copy(guidePastDays = next(listOf(1, 2, 3, 7), it.guidePastDays)) }
            }
        }
        item {
            SettingRow("Load future programmes", "${settings.guideFutureDays} days") {
                update { it.copy(guideFutureDays = next(listOf(3, 7, 14), it.guideFutureDays)) }
            }
        }
        item { SettingRow("Time format", if (settings.use24h) "24-hour" else "12-hour") { update { it.copy(use24h = !it.use24h) } } }
        item { SettingRow("Refresh all playlists and guides now", null) { sources.forEach { core.refresh(it.id) } } }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("Playback") }
        item {
            SettingRow("Info bar timeout", "${settings.overlayTimeoutSec} s") {
                update { it.copy(overlayTimeoutSec = next(listOf(3, 4, 6, 10), it.overlayTimeoutSec)) }
            }
        }
        item {
            SettingRow("Start with last channel", if (settings.autoplayLastChannel) "On" else "Off") {
                update { it.copy(autoplayLastChannel = !it.autoplayLastChannel) }
            }
        }
        item {
            SettingRow("Prefer HLS for Xtream live", if (settings.preferHlsLive) "On (applies after refresh)" else "Off (MPEG-TS)") {
                update { it.copy(preferHlsLive = !it.preferHlsLive) }
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("Parental control") }
        item {
            SettingRow(if (hasPin) "Change PIN" else "Set a PIN", if (hasPin) "PIN is set" else "Lock channels and groups with a 4-digit PIN") {
                protected { pinFlow = PinFlow.Enter }
            }
        }
        if (hasPin) item {
            SettingRow("Remove PIN", null) { protected { core.clearPin(); hasPin = false } }
        }
        item {
            SettingRow("Hidden groups", null) {
                protected { scope.launch { hiddenGroups = core.liveGroups(true).filter { it.hidden } } }
            }
        }
        item {
            SettingRow("Hidden channels", null) {
                protected { scope.launch { hiddenChannels = core.hiddenChannels() } }
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("About") }
        item {
            SettingRow("IPTV Enhanced ${BuildConfig.VERSION_NAME}", "Free and open-source (GPL-3.0). IPTV Enhanced provides no content.") {}
        }
    }

    LaunchedEffect(Unit) { sources = core.sources() }

    sourceMenu?.let { s ->
        ContextMenu(s.name, s.url.substringBefore('?'), listOf(
            MenuAction("Refresh now") { core.refresh(s.id) },
            MenuAction("Edit") { nav.push(Screen.EditSource(s.id)) },
            MenuAction(if (s.enabled) "Disable" else "Enable") {
                scope.launch {
                    core.updateSource(s.id, app.tuner.core.model.SourceInput(
                        s.name, s.type, s.url, s.username, s.password, s.epgUrls, s.userAgent, s.epgOffsetMinutes, s.refreshHours, !s.enabled,
                    ))
                }
            },
            MenuAction("Delete") { confirmDelete = s },
        )) { sourceMenu = null }
    }
    confirmDelete?.let { s ->
        ConfirmDialog("Delete ${s.name}?", "Its channels, favorites and watch history will be removed from this device.", "Delete", onConfirm = {
            scope.launch {
                core.deleteSource(s.id)
                if (core.sources().isEmpty()) nav.reset(Screen.Welcome)
            }
        }, onDismiss = { confirmDelete = null })
    }
    when (val flow = pinFlow) {
        PinFlow.Verify -> PinDialog("Parental PIN", "Enter your current PIN", onSubmit = { pin ->
            if (core.unlock(pin)) { pinFlow = null; afterVerify?.invoke(); afterVerify = null; true } else false
        }, onDismiss = { pinFlow = null })
        PinFlow.Enter -> PinDialog("New PIN", "Choose a 4-digit PIN", onSubmit = { pin ->
            if (pin.length == 4) { pinFlow = PinFlow.Confirm(pin); true } else false
        }, onDismiss = { pinFlow = null })
        is PinFlow.Confirm -> PinDialog("Confirm PIN", "Enter the same PIN again", onSubmit = { pin ->
            if (pin == flow.first) { core.setPin(pin); hasPin = true; pinFlow = null; true } else false
        }, onDismiss = { pinFlow = null })
        null -> Unit
    }
    hiddenGroups?.let { list ->
        val actions = list.map { g -> MenuAction(g.name, "Show") { scope.launch { core.setGroupHidden(g, false) } } }
            .ifEmpty { listOf(MenuAction("No hidden groups") {}) }
        ContextMenu("Hidden groups", "Select a group to show it again", actions) { hiddenGroups = null }
    }
    hiddenChannels?.let { list ->
        val actions = list.map { c -> MenuAction(c.name, "Show") { scope.launch { core.setHidden(ContentKind.LIVE, c.sourceId, c.key, false) } } }
            .ifEmpty { listOf(MenuAction("No hidden channels") {}) }
        ContextMenu("Hidden channels", "Select a channel to show it again", actions) { hiddenChannels = null }
    }
}

private fun <T> next(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

@Composable
private fun SettingRow(title: String, value: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = modifier.fillMaxWidth(), scale = 1.01f) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (value != null) Dim(value)
        }
    }
}

@Composable
private fun SourceRow(source: Source, sync: SyncState?, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), scale = 1.01f) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(10.dp))
                    Dim(if (source.type == SourceType.XTREAM) "Xtream" else "M3U")
                    if (!source.enabled) { Spacer(Modifier.width(10.dp)); Dim("Disabled") }
                }
                val status = when {
                    sync?.running == true -> "${sync.phase}…" + if (sync.count > 0) " ${"%,d".format(sync.count)}" else ""
                    source.lastError != null -> source.lastError!!
                    source.lastRefreshed != null -> "Updated ${relative(source.lastRefreshed!!)}" +
                        (source.expiresAt?.let { " · account expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}" } ?: "")
                    else -> "Never updated"
                }
                Text(
                    status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (source.lastError != null && sync?.running != true) Palette.Warning else Palette.TextDim,
                )
            }
            Box { Dim("Options") }
        }
    }
}

private fun relative(t: Long): String {
    val mins = (System.currentTimeMillis() - t) / 60_000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / 1440} days ago"
    }
}
