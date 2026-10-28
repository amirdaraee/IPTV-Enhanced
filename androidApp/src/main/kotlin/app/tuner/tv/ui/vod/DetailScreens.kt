package app.tuner.tv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.LockedException
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Episode
import app.tuner.core.model.MovieDetails
import app.tuner.core.model.PlaybackRequest
import app.tuner.core.model.SeriesDetails
import app.tuner.tv.core
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.Artwork
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.EmptyState
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.LoadingDots
import app.tuner.tv.ui.components.PinDialog
import app.tuner.tv.ui.components.PrimaryButton
import app.tuner.tv.ui.components.ProgressBar
import app.tuner.tv.ui.guide.Toast
import app.tuner.tv.ui.player.VodSession
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.util.formatDuration
import app.tuner.tv.util.formatMinutes
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun Backdrop(url: String?) {
    Box(Modifier.fillMaxSize()) {
        if (!url.isNullOrBlank()) AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Palette.Background, Palette.Background.copy(alpha = 0.85f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Background))))
    }
}

/** Starts playback, asking for the PIN when content is locked. */
@Composable
private fun rememberPlayLauncher(onMessage: (String) -> Unit): Pair<(suspend () -> PlaybackRequest) -> Unit, @Composable () -> Unit> {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<(suspend () -> PlaybackRequest)?>(null) }
    val launch: (suspend () -> PlaybackRequest) -> Unit = { block ->
        scope.launch {
            try {
                VodSession.request = block()
                nav.push(Screen.VodPlayer)
            } catch (e: LockedException) {
                pending = block
            } catch (e: Exception) {
                onMessage(e.message ?: "Could not play")
            }
        }
    }
    val dialog: @Composable () -> Unit = {
        pending?.let { block ->
            PinDialog("Parental PIN", "This title is locked", onSubmit = { pin ->
                if (core.unlock(pin)) { pending = null; launch(block); true } else false
            }, onDismiss = { pending = null })
        }
    }
    return launch to dialog
}

@Composable
fun MovieDetailScreen(movieId: Long) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<MovieDetails?>(null) }
    var failed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val (play, pinDialog) = rememberPlayLauncher { message = it }
    BackHandler { nav.pop() }

    LaunchedEffect(movieId) {
        val movie = core.movie(movieId)
        if (movie == null) { failed = true; return@LaunchedEffect }
        details = runCatching { core.movieDetails(movie) }.getOrNull() ?: MovieDetails(movie, null, null, null, null, null, null, null, null)
    }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }

    val d = details
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (d == null) {
            if (failed) EmptyState("Not found", "This movie is no longer in your playlist.") else LoadingDots(Modifier.align(Alignment.Center))
            return@Box
        }
        Backdrop(d.backdrop ?: d.movie.poster)
        Row(Modifier.fillMaxSize().padding(48.dp)) {
            Artwork(d.movie.poster, d.movie.name, Modifier.width(220.dp).height(330.dp), fontSize = 36)
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(d.movie.name, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Dim(listOfNotNull(d.movie.year, d.genre, d.durationSecs?.let(::formatMinutes), d.movie.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString("  ·  "))
                Spacer(Modifier.height(16.dp))
                Text(d.plot ?: "No description available.", style = MaterialTheme.typography.bodyLarge, color = Palette.Text, maxLines = 6, overflow = TextOverflow.Ellipsis)
                d.director?.let { Spacer(Modifier.height(10.dp)); Dim("Director: $it") }
                d.cast?.let { Dim("Cast: $it", maxLines = 2) }
                Spacer(Modifier.height(24.dp))
                val p = d.progress
                if (p != null && !p.watched) {
                    ProgressBar(p.fraction, Modifier.width(320.dp))
                    Spacer(Modifier.height(4.dp))
                    Dim("${formatDuration(p.positionMs)} of ${formatDuration(p.durationMs)}")
                    Spacer(Modifier.height(12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(if (p != null && !p.watched) "Resume" else "Play", onClick = { play { core.playMovie(d.movie) } }, modifier = Modifier.focusRequester(focus))
                    if (p != null && !p.watched) PrimaryButton("Play from start", onClick = {
                        play { core.playMovie(d.movie).copy(startPositionMs = 0) }
                    })
                    PrimaryButton(if (d.movie.favorite) "★ Favorite" else "☆ Favorite", onClick = {
                        scope.launch {
                            core.setFavorite(ContentKind.MOVIE, d.movie.sourceId, d.movie.key, !d.movie.favorite)
                            details = d.copy(movie = d.movie.copy(favorite = !d.movie.favorite))
                        }
                    })
                }
            }
        }
        message?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
    }
    LaunchedEffect(d != null) { if (d != null) runCatching { focus.requestFocus() } }
    pinDialog()
}

@Composable
fun SeriesDetailScreen(seriesId: Long) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<SeriesDetails?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val (play, pinDialog) = rememberPlayLauncher { message = it }
    BackHandler { nav.pop() }

    suspend fun load() {
        val s = core.seriesById(seriesId) ?: run { error = "This series is no longer in your playlist."; return }
        try {
            val d = core.seriesDetails(s)
            details = d
            if (season == null) {
                // Start on the season of the most recently watched episode, else the first.
                season = d.episodes.filter { it.progress != null }.maxByOrNull { it.progress!!.updatedAt }?.season ?: d.seasons.firstOrNull()
            }
        } catch (e: Exception) {
            error = e.message ?: "Could not load episodes"
        }
    }
    LaunchedEffect(seriesId) { load() }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }

    val d = details
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        if (d == null) {
            if (error != null) EmptyState("Couldn't load series", error!!) else LoadingDots(Modifier.align(Alignment.Center))
            return@Box
        }
        Backdrop(d.backdrop ?: d.series.cover)
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 40.dp)) {
            Row {
                Artwork(d.series.cover, d.series.name, Modifier.width(120.dp).height(180.dp), fontSize = 24)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.series.name, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Dim(listOfNotNull(d.series.year, d.genre, "${d.seasons.size} season${if (d.seasons.size == 1) "" else "s"}").joinToString("  ·  "))
                    Spacer(Modifier.height(10.dp))
                    Text(d.plot ?: "", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(if (d.series.favorite) "★ Favorite" else "☆ Favorite", onClick = {
                        scope.launch {
                            core.setFavorite(ContentKind.SERIES, d.series.sourceId, d.series.key, !d.series.favorite)
                            details = d.copy(series = d.series.copy(favorite = !d.series.favorite))
                        }
                    })
                }
            }
            Spacer(Modifier.height(14.dp))
            // The focused chip grows a little; the padding keeps the row from clipping it, and the
            // offset keeps the chips lined up with the poster above.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                modifier = Modifier.offset(x = (-6).dp),
            ) {
                items(d.seasons) { s ->
                    FocusSurface(
                        onClick = { season = s },
                        color = if (s == season) Palette.AccentDim else Palette.Surface,
                        modifier = if (s == season) Modifier.focusRequester(focus) else Modifier,
                    ) {
                        Text("Season $s", modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            val episodes = d.episodes.filter { it.season == season }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
                items(episodes, key = { it.remoteId }) { e -> EpisodeRow(e) { play { core.playEpisode(d.series, e) } } }
            }
        }
        message?.let { Toast(it, Modifier.align(Alignment.BottomCenter)) }
    }
    LaunchedEffect(d != null) { if (d != null) { delay(100); runCatching { focus.requestFocus() } } }
    pinDialog()
}

@Composable
private fun EpisodeRow(e: Episode, onClick: () -> Unit) {
    FocusSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(96.dp), scale = 1.02f) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(136.dp).fillMaxHeight()) {
                Artwork(e.image, e.title, Modifier.fillMaxSize(), fontSize = 16)
                e.progress?.let { ProgressBar(it.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(6.dp)) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${e.episode}. ${e.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Dim(listOfNotNull(e.durationSecs?.let(::formatMinutes), if (e.progress?.watched == true) "Watched" else null).joinToString("  ·  "))
                e.plot?.let { Dim(it, maxLines = 2) }
            }
            if (e.progress?.watched == true) Text("✓", color = Palette.Accent, modifier = Modifier.size(24.dp))
        }
    }
}
