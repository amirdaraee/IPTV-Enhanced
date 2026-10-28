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

/** The height the layouts are designed for: a 1080p TV at 2x. */
private val DESIGN_HEIGHT = 540.dp

/**
 * On a touch device shorter than a TV, scales the interface down so every screen still gets the
 * height it was laid out for. A phone in landscape is about 410 dp tall; the guide, its header and
 * the dialogs need 540. Tablets and TVs keep their own density.
 */
@Composable
fun TouchUi(touch: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTouchUi provides touch) {
        if (!touch) return@CompositionLocalProvider content()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            if (maxHeight >= DESIGN_HEIGHT) return@BoxWithConstraints content()
            val scaled = Density(constraints.maxHeight / DESIGN_HEIGHT.value, density.fontScale.coerceAtMost(1.15f))
            CompositionLocalProvider(LocalDensity provides scaled) { content() }
        }
    }
}
