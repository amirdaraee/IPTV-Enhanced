package app.tuner.tv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.model.ContentKind
import app.tuner.core.model.GroupType
import app.tuner.tv.core
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.Artwork
import app.tuner.tv.ui.components.ContextMenu
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.EmptyState
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.MenuAction
import app.tuner.tv.ui.components.PinDialog
import app.tuner.tv.ui.components.ProgressBar
import app.tuner.tv.ui.player.VodSession
import app.tuner.tv.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun MoviesScreen(focusRequester: FocusRequester, vm: MoviesViewModel = viewModel()) = VodBrowser(vm, ContentKind.MOVIE, focusRequester)

@Composable
fun SeriesScreen(focusRequester: FocusRequester, vm: SeriesViewModel = viewModel()) = VodBrowser(vm, ContentKind.SERIES, focusRequester)

@Composable
private fun VodBrowser(vm: VodViewModel, kind: ContentKind, focusRequester: FocusRequester) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<VodItem?>(null) }
    var gate by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) { if (!vm.loaded) vm.reload() }

    fun open(item: VodItem) {
        when (item) {
            is VodItem.MovieItem -> nav.push(Screen.MovieDetail(item.movie.id))
            is VodItem.SeriesItem -> nav.push(Screen.SeriesDetail(item.series.id))
            is VodItem.ResumeItem -> {
                val p = item.progress
                VodSession.request = app.tuner.core.model.PlaybackRequest(
                    p.url, p.title, p.subtitle, p.image, null, null, isLive = false,
                    startPositionMs = (p.positionMs - 5_000).coerceAtLeast(0), progressKey = p.key,
                    progressParentKey = p.parentKey, sourceId = p.sourceId, kind = p.kind, channelId = null,
                )
                nav.push(Screen.VodPlayer)
            }
        }
    }

    if (vm.loaded && vm.groups.isEmpty()) {
        EmptyState(
            if (kind == ContentKind.MOVIE) "No movies" else "No series",
            "Your playlists don't include any ${if (kind == ContentKind.MOVIE) "movies" else "series"}. Xtream accounts usually do.",
            Modifier.focusRequester(focusRequester),
        )
        return
    }

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            Modifier.width(240.dp).fillMaxHeight().padding(top = 24.dp, start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    if (kind == ContentKind.MOVIE) "Movies" else "Series",
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(vm.groups, key = { _, g -> g.id }) { i, g ->
                val selected = g.id == vm.group?.id
                FocusSurface(
                    onClick = { if (core.isAllowed(g.locked)) vm.select(g) else gate = { vm.select(g) } },
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(focusRequester) else Modifier),
                    color = if (selected) Palette.SurfaceHigh else Color.Transparent,
                    scale = 1.02f,
                ) {
                    Text(
                        (if (g.type == GroupType.FAVORITES) "★  " else "") + g.name,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (selected) Palette.Accent else Palette.Text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
        val gridState = rememberLazyGridState()
        LaunchedEffect(vm.group?.id) { gridState.scrollToItem(0) }
        LaunchedEffect(gridState) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { last -> if (last >= vm.items.size - 24) vm.loadMore() }
        }
        if (vm.items.isEmpty() && vm.loaded) {
            EmptyState("Nothing here", if (vm.group?.type == GroupType.FAVORITES) "Long-press OK on a poster to add it to Favorites." else "This category is empty.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(132.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 32.dp, top = 28.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                itemsIndexed(vm.items, key = { _, it -> it.id }) { _, item ->
                    PosterCard(item, onClick = { open(item) }, onLongClick = { menu = item })
                }
            }
        }
    }

    menu?.let { item ->
        val actions = buildList {
            when (item) {
                is VodItem.MovieItem -> add(MenuAction(if (item.movie.favorite) "Remove from Favorites" else "Add to Favorites") {
                    scope.launch { core.setFavorite(ContentKind.MOVIE, item.movie.sourceId, item.movie.key, !item.movie.favorite) }
                })
                is VodItem.SeriesItem -> add(MenuAction(if (item.series.favorite) "Remove from Favorites" else "Add to Favorites") {
                    scope.launch { core.setFavorite(ContentKind.SERIES, item.series.sourceId, item.series.key, !item.series.favorite) }
                })
                is VodItem.ResumeItem -> add(MenuAction("Remove from Continue watching") {
                    scope.launch { core.clearProgress(item.progress.key) }
                })
            }
            add(MenuAction("Open") { open(item) })
        }
        ContextMenu(item.title, null, actions) { menu = null }
    }
    gate?.let { action ->
        PinDialog("Parental PIN", "This category is locked", onSubmit = { pin ->
            if (core.unlock(pin)) { gate = null; action(); true } else false
        }, onDismiss = { gate = null })
    }
}

@Composable
private fun PosterCard(item: VodItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column {
        FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f), scale = 1.08f) {
            Box {
                Artwork(item.image, item.title, Modifier.fillMaxSize(), fontSize = 26)
                val fav = (item as? VodItem.MovieItem)?.movie?.favorite == true || (item as? VodItem.SeriesItem)?.series?.favorite == true
                if (fav) Text("★", color = Palette.Warning, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (item is VodItem.ResumeItem) {
                    ProgressBar(item.progress.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp), height = 4.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Palette.Text)
        val sub = when (item) {
            is VodItem.MovieItem -> listOfNotNull(item.movie.year, item.movie.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString("  ")
            is VodItem.SeriesItem -> listOfNotNull(item.series.year, item.series.genre).joinToString("  ·  ")
            is VodItem.ResumeItem -> item.progress.subtitle ?: ""
        }
        if (sub.isNotEmpty()) Dim(sub)
    }
}
