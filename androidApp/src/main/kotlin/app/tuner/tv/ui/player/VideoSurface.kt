package app.tuner.tv.ui.player

import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.tuner.tv.R
import app.tuner.tv.playerController

/** Renders the shared player without controls; used for the guide preview and the live player. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(modifier: Modifier = Modifier, resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT) {
    val context = LocalContext.current
    val view = remember {
        (LayoutInflater.from(context).inflate(R.layout.player_surface, null) as PlayerView).apply {
            useController = false
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            setKeepContentOnPlayerReset(true)
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }
    DisposableEffect(view) {
        view.player = playerController.player
        onDispose { view.player = null }
    }
    AndroidView(factory = { view }, modifier = modifier, update = { it.resizeMode = resizeMode })
}
