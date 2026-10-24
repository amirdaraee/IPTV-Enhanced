package app.tuner.tv

import android.app.Application
import app.tuner.core.TunerCore
import app.tuner.core.platform.PlatformContext
import app.tuner.tv.player.PlayerController
import coil3.ImageLoader
import coil3.PlatformContext as CoilContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.MainScope
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

class TunerApplication : Application(), SingletonImageLoader.Factory {
    lateinit var core: TunerCore
        private set
    lateinit var player: PlayerController
        private set

    /** For work that must outlive a screen, such as saving progress when the player closes. */
    val appScope = MainScope()

    /**
     * One HTTP stack for streams and artwork: zapping reuses warm connections to the
     * provider instead of a new TCP/TLS handshake per channel, and logos share the pool.
     */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        core = TunerCore(PlatformContext(this))
        player = PlayerController(this, okHttp)
        core.refreshStale()
    }

    override fun newImageLoader(context: CoilContext): ImageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        // Logos and posters rarely change; keeping them on disk makes the guide and
        // VOD grids fill instantly on later launches instead of re-downloading.
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(256L * 1024 * 1024).build() }
        .build()

    companion object {
        lateinit var instance: TunerApplication
            private set
    }
}

val core: TunerCore get() = TunerApplication.instance.core
val playerController: PlayerController get() = TunerApplication.instance.player
