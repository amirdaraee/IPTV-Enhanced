package app.tuner.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * Shows a licence text from the app's assets (the repository's `licenses/` folder), full screen.
 * It's a dialog so that Back returns focus to the row that opened it. Each paragraph takes focus,
 * so the D-pad scrolls through it.
 */
@Composable
fun LicenseDialog(file: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs = remember(file) { paragraphs(context.assets.open(file).bufferedReader().use { it.readText() }) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Palette.Background)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 96.dp, vertical = 40.dp)) {
                item { Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 16.dp)) }
                itemsIndexed(paragraphs) { i, p ->
                    FocusSurface(
                        onClick = {}, scale = 1f, color = Color.Transparent, focusedColor = Palette.Surface,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Text(p, style = MaterialTheme.typography.bodyMedium, color = Palette.Text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

/** Splits on blank lines and rejoins the hard-wrapped lines of each paragraph; list items keep their own line. */
private fun paragraphs(text: String): List<String> =
    text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.map { p ->
        p.lines().joinToString("") { line -> if (line.trimStart().startsWith("- ")) "\n" + line.trim() else " " + line.trim() }
            .replace(Regex(" {2,}"), " ").trim()
    }
