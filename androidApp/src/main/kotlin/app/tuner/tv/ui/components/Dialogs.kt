package app.tuner.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.tv.ui.theme.Palette

data class MenuAction(val label: String, val detail: String? = null, val onSelect: () -> Unit)

@Composable
private fun DialogFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .widthIn(min = 420.dp, max = 560.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.SurfaceHigh)
                .padding(24.dp),
        ) { content() }
    }
}

/** Long-press menu: a titled list of actions. Selecting an action dismisses the menu first. */
@Composable
fun ContextMenu(title: String, subtitle: String?, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    DialogFrame(onDismiss) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text, maxLines = 2)
            if (subtitle != null) Dim(subtitle)
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(actions) { i, a ->
                    FocusSurface(
                        onClick = { onDismiss(); a.onSelect() },
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        color = Palette.Surface,
                        scale = 1.02f,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (a.detail != null) Dim(a.detail)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    DialogFrame(onDismiss) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Palette.TextDim)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(confirm, onClick = { onDismiss(); onConfirm() }, modifier = Modifier.focusRequester(focus))
                PrimaryButton("Cancel", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/**
 * Four-digit PIN entry with an on-screen keypad; number keys on the remote work too.
 * [onSubmit] returns false to signal a wrong PIN (the field shakes clear).
 */
@Composable
fun PinDialog(title: String, message: String, onSubmit: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun press(d: String) {
        if (pin.length >= 4) return
        pin += d
        error = false
        if (pin.length == 4) {
            if (!onSubmit(pin)) {
                error = true
                pin = ""
            }
        }
    }
    DialogFrame(onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = when (e.key) {
                    Key.Zero, Key.NumPad0 -> "0"; Key.One, Key.NumPad1 -> "1"; Key.Two, Key.NumPad2 -> "2"
                    Key.Three, Key.NumPad3 -> "3"; Key.Four, Key.NumPad4 -> "4"; Key.Five, Key.NumPad5 -> "5"
                    Key.Six, Key.NumPad6 -> "6"; Key.Seven, Key.NumPad7 -> "7"; Key.Eight, Key.NumPad8 -> "8"
                    Key.Nine, Key.NumPad9 -> "9"; else -> null
                }
                if (digit != null) { press(digit); true } else false
            },
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.Text)
            Spacer(Modifier.height(4.dp))
            Text(if (error) "Wrong PIN, try again" else message, color = if (error) Palette.Live else Palette.TextDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(9.dp))
                            .background(if (i < pin.length) Palette.Accent else Palette.Outline),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { r, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { c, label ->
                            FocusSurface(
                                onClick = {
                                    when (label) {
                                        "⌫" -> pin = pin.dropLast(1)
                                        "✓" -> if (pin.isNotEmpty() && !onSubmit(pin)) { error = true; pin = "" }
                                        else -> press(label)
                                    }
                                },
                                modifier = Modifier.width(72.dp).height(52.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(focus) else Modifier),
                                color = Palette.Surface,
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 20.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
