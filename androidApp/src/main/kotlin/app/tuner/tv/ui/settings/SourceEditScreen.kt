package app.tuner.tv.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import app.tuner.core.model.SyncState
import app.tuner.tv.core
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.ConfirmDialog
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.LoadingDots
import app.tuner.tv.ui.components.PrimaryButton
import app.tuner.tv.ui.components.TvTextField
import app.tuner.tv.ui.theme.Palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Add or edit a playlist. After saving a new one, shows sync progress and then opens the guide. */
@Composable
fun SourceEditScreen(sourceId: Long?) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var offsetHours by remember { mutableStateOf("0") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var syncingId by remember { mutableStateOf<Long?>(null) }
    var sync by remember { mutableStateOf<SyncState?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val firstFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun form() = listOf(type, name, url, username, password, epgUrl, userAgent, offsetHours)
    var saved by remember { mutableStateOf(form()) }

    LaunchedEffect(sourceId) {
        if (sourceId != null) {
            core.source(sourceId)?.let { s ->
                type = s.type; name = s.name; url = s.url; username = s.username.orEmpty(); password = s.password.orEmpty()
                epgUrl = s.epgUrls.joinToString(", "); userAgent = s.userAgent.orEmpty(); offsetHours = (s.epgOffsetMinutes / 60.0).toString().removeSuffix(".0")
                saved = form()
            }
        }
        runCatching { firstFocus.requestFocus() }
    }

    DisposableEffect(syncingId) {
        val id = syncingId
        val watcher = if (id == null) null else core.watchSync { states -> sync = states.firstOrNull { it.sourceId == id } }
        onDispose { watcher?.cancel() }
    }
    LaunchedEffect(sync) {
        val s = sync ?: return@LaunchedEffect
        if (!s.running && s.phase != "Starting") {
            if (s.phase == "Failed") {
                error = s.error
                busy = false
                syncingId = null
            } else {
                nav.reset(Screen.Home)
            }
        }
    }
    fun leave() { if (!nav.pop()) nav.reset(Screen.Welcome) }
    // Back is easy to press by accident on a remote; don't silently throw away typing.
    // While checking or syncing, Back is ignored: falling through would close the app.
    BackHandler { if (!busy) { if (form() != saved) confirmDiscard = true else leave() } }
    if (confirmDiscard) {
        ConfirmDialog("Discard changes?", "What you entered on this screen will be lost.", "Discard", onConfirm = ::leave, onDismiss = { confirmDiscard = false }, destructive = true)
    }

    fun input() = SourceInput(
        name = name.ifBlank { if (type == SourceType.XTREAM) url.substringAfter("://").substringBefore(':').substringBefore('/') else "Playlist" },
        type = type,
        url = url.trim(),
        username = username.trim().takeIf { type == SourceType.XTREAM },
        password = password.takeIf { type == SourceType.XTREAM },
        epgUrls = epgUrl.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
        userAgent = userAgent.trim().ifEmpty { null },
        epgOffsetMinutes = ((offsetHours.replace(',', '.').toDoubleOrNull() ?: 0.0) * 60).toInt(),
    )

    fun save() {
        error = null
        if (url.isBlank()) { error = if (type == SourceType.XTREAM) "Enter the server address" else "Enter the playlist URL"; return }
        if (type == SourceType.XTREAM && (username.isBlank() || password.isBlank())) { error = "Enter your username and password"; return }
        busy = true
        scope.launch {
            try {
                if (type == SourceType.XTREAM) {
                    val acc = core.verifyXtream(url, username.trim(), password)
                    info = listOfNotNull(
                        acc.status?.let { "Account $it" },
                        acc.expiresAt?.let { "expires ${DateFormat.getDateInstance().format(Date(it))}" },
                        acc.maxConnections?.let { "$it connection${if (it == 1) "" else "s"}" },
                    ).joinToString(" · ")
                }
                if (sourceId == null) {
                    syncingId = core.addSource(input())
                } else {
                    core.updateSource(sourceId, input())
                    core.refresh(sourceId)
                    nav.pop()
                }
            } catch (e: Exception) {
                error = e.message ?: "Could not connect"
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp)) {
            Text(if (sourceId == null) "Add a playlist" else "Edit playlist", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(6.dp))
            Dim("IPTV Enhanced plays content from your own provider. It doesn't include any channels.")
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(SourceType.XTREAM to "Xtream Codes login", SourceType.M3U to "M3U playlist URL").forEachIndexed { i, (t, label) ->
                    FocusSurface(
                        onClick = { type = t },
                        color = if (type == t) Palette.AccentDim else Palette.Surface,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            val fieldMod = Modifier.width(640.dp)
            TvTextField("Name (optional)", name, { name = it }, fieldMod, placeholder = "My provider", keyboardType = KeyboardType.Text)
            Spacer(Modifier.height(16.dp))
            if (type == SourceType.XTREAM) {
                TvTextField("Server", url, { url = it }, fieldMod, placeholder = "http://provider.example:8080")
                Spacer(Modifier.height(16.dp))
                Row(fieldMod, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TvTextField("Username", username, { username = it }, Modifier.weight(1f), keyboardType = KeyboardType.Text)
                    TvTextField("Password", password, { password = it }, Modifier.weight(1f), password = true)
                }
            } else {
                TvTextField("Playlist URL", url, { url = it }, fieldMod, placeholder = "http://provider.example/playlist.m3u")
            }
            Spacer(Modifier.height(16.dp))
            TvTextField(
                if (type == SourceType.XTREAM) "Extra EPG URL (optional — the provider's guide is added automatically)" else "EPG URL (optional — read from the playlist if present)",
                epgUrl, { epgUrl = it }, fieldMod, placeholder = "http://example/epg.xml.gz",
            )
            Spacer(Modifier.height(16.dp))
            // Down from the last row goes to the main button; the nearest one under the wide
            // User-Agent field would be Cancel.
            Row(fieldMod.focusProperties { down = if (busy) FocusRequester.Default else saveFocus }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvTextField("Custom User-Agent (optional)", userAgent, { userAgent = it }, Modifier.weight(2f), keyboardType = KeyboardType.Text)
                TvTextField("Guide time shift (hours)", offsetHours, { offsetHours = it }, Modifier.weight(1f), keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
            }
            Spacer(Modifier.height(28.dp))
            error?.let { Text(it, color = Palette.Live, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(12.dp)) }
            info?.let { Text(it, color = Palette.Accent); Spacer(Modifier.height(12.dp)) }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingDots()
                    Spacer(Modifier.width(12.dp))
                    Text(
                        sync?.let { s -> if (s.count > 0) "${s.phase}… ${"%,d".format(s.count)}" else "${s.phase}…" } ?: "Connecting…",
                        color = Palette.TextDim,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(if (sourceId == null) "Add playlist" else "Save", onClick = ::save, Modifier.focusRequester(saveFocus))
                    PrimaryButton("Cancel", onClick = ::leave)
                }
            }
            Spacer(Modifier.height(80.dp))
            Box(Modifier.fillMaxWidth())
        }
    }
}
