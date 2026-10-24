package app.tuner.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.PrimaryButton
import app.tuner.tv.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun WelcomeScreen() {
    val nav = LocalNavigator.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(Palette.Background, Palette.Surface, Palette.AccentDim.copy(alpha = 0.35f))))
            .padding(96.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Tuner", style = MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 1.6f), color = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text("A free, open-source TV guide and player for your IPTV subscription.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Dim("Works with M3U playlists, XMLTV guides and Xtream Codes accounts. Your data stays on this device.", maxLines = 2)
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton("Add your playlist", onClick = { nav.push(Screen.EditSource(null)) }, modifier = Modifier.focusRequester(focus).width(260.dp))
        }
    }
}
