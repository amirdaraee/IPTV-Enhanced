package app.tuner.tv

import android.app.Application
import app.tuner.core.TunerCore
import app.tuner.core.platform.PlatformContext
import app.tuner.tv.player.PlayerController
import coil3.ImageLoader
import coil3.PlatformContext as CoilContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.MainScope

class TunerApplication : Application(), SingletonImageLoader.Factory {
    lateinit var core: TunerCore
        private set
    lateinit var player: PlayerController
        private set

    /** For work that must outlive a screen, such as saving progress when the player closes. */
    val appScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        instance = this
        core = TunerCore(PlatformContext(this))
        player = PlayerController(this)
        core.refreshStale()
    }

    override fun newImageLoader(context: CoilContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .build()

    companion object {
        lateinit var instance: TunerApplication
            private set
    }
}

val core: TunerCore get() = TunerApplication.instance.core
val playerController: PlayerController get() = TunerApplication.instance.player
