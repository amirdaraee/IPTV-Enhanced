package app.tuner.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.TunerCore
import app.tuner.core.model.Source
import app.tuner.tv.playerController
import app.tuner.tv.util.formatTime
import kotlinx.coroutines.delay

/** Languages offered for the audio and subtitle preferences (ISO 639-1 code to name). */
val LANGUAGES = listOf(
    "en" to "English", "fa" to "Persian", "ar" to "Arabic", "tr" to "Turkish", "fr" to "French",
    "de" to "German", "es" to "Spanish", "it" to "Italian", "ru" to "Russian", "hi" to "Hindi",
)

fun languageName(code: String, empty: String) = if (code.isEmpty()) empty else LANGUAGES.firstOrNull { it.first == code }?.second ?: code

/** The next value in [""] + [LANGUAGES], for settings rows that cycle on OK. */
fun nextLanguage(code: String): String {
    val codes = listOf("") + LANGUAGES.map { it.first }
    return codes[(codes.indexOf(code) + 1) % codes.size]
}

/** The time of day, updated on the minute. */
@Composable
fun Clock(modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.titleMedium, color: Color = Color.White) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - now % 60_000)
            now = System.currentTimeMillis()
        }
    }
    Text(formatTime(now), style = style, color = color, modifier = modifier)
}

private val SLEEP_MINUTES = listOf(15, 30, 60, 90, 120)

/** "Off" or the time left on the sleep timer. */
fun sleepLabel(): String {
    val at = playerController.sleepAt ?: return "Off"
    val mins = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
    return "$mins min left"
}

fun sleepActions(): List<MenuAction> = buildList {
    add(MenuAction("Off", if (playerController.sleepAt == null) "✓" else null) { playerController.setSleepTimer(0) })
    SLEEP_MINUTES.forEach { m -> add(MenuAction("$m minutes") { playerController.setSleepTimer(m) }) }
}

/** "expires in 3 days", "expires today" or "expired", for accounts close to their end date. */
fun expiryText(source: Source): String? {
    val at = source.expiresAt ?: return null
    val days = Math.floorDiv(at - System.currentTimeMillis(), TunerCore.DAY)
    return when {
        at <= System.currentTimeMillis() -> "expired"
        days == 0L -> "expires today"
        days == 1L -> "expires tomorrow"
        else -> "expires in $days days"
    }
}
