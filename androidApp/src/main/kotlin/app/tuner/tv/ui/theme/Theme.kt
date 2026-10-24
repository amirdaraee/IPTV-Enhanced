package app.tuner.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object Palette {
    val Background = Color(0xFF080B12)
    val Surface = Color(0xFF111723)
    val SurfaceHigh = Color(0xFF1A2232)
    val SurfaceFocus = Color(0xFF26324A)
    val Outline = Color(0xFF2A3446)
    val Accent = Color(0xFF5EEAD4)
    val AccentDim = Color(0xFF1F5C56)
    val Text = Color(0xFFE8EDF5)
    val TextDim = Color(0xFF8A96AB)
    val Live = Color(0xFFFF5A5F)
    val Warning = Color(0xFFFFB020)
}

private val colors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color(0xFF00201C),
    secondary = Palette.Accent,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextDim,
    border = Palette.Outline,
    error = Palette.Live,
)

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TunerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
