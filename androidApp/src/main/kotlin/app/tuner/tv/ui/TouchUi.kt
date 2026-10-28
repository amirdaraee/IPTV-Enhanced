package app.tuner.tv.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * True on phones and tablets. The screens are designed for the remote; with this set they also
 * show touch hints and controls (there is no OK button to press, no remote to zap with).
 */
val LocalTouchUi = staticCompositionLocalOf { false }

/** The screen the layouts are designed for: a 1080p TV at 2x. */
private val DESIGN_WIDTH = 960.dp
private val DESIGN_HEIGHT = 540.dp

/**
 * On a touch device, scales the interface so the TV design fills the screen: the larger side that
 * still fits both 960 dp across and 540 dp down. A phone in landscape (about 410 dp tall) is scaled
 * down so the guide, its header and the dialogs keep the height they need; a tablet (about
 * 1280×800 dp) is scaled up, which keeps the layout as full as on a TV and the touch targets large.
 */
@Composable
fun TouchUi(touch: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTouchUi provides touch) {
        if (!touch) return@CompositionLocalProvider content()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val scale = minOf(constraints.maxWidth / DESIGN_WIDTH.value, constraints.maxHeight / DESIGN_HEIGHT.value)
            val scaled = Density(scale, density.fontScale.coerceAtMost(1.15f))
            CompositionLocalProvider(LocalDensity provides scaled) { content() }
        }
    }
}
