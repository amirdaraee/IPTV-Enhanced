package app.tuner.tv.ui.vod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tuner.core.Cancellable
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Group
import app.tuner.core.model.GroupType
import app.tuner.core.model.Movie
import app.tuner.core.model.Series
import app.tuner.core.model.WatchProgress
import app.tuner.tv.core
import kotlinx.coroutines.launch

/** One poster in a VOD grid: a movie, a series or a resumable item. */
sealed interface VodItem {
    val id: String
    val title: String
    val image: String?

    data class MovieItem(val movie: Movie) : VodItem {
        override val id = "m${movie.id}"
        override val title = movie.name
        override val image = movie.poster
    }

    data class SeriesItem(val series: Series) : VodItem {
        override val id = "s${series.id}"
        override val title = series.name
        override val image = series.cover
    }

    data class ResumeItem(val progress: WatchProgress) : VodItem {
        override val id = "p${progress.key}"
        override val title = progress.title
        override val image = progress.image
    }
}

private val CONTINUE = Group("continue", GroupType.RECENTS, ContentKind.MOVIE, 0, "", "Continue watching", null, false, false)

abstract class VodViewModel(private val kind: ContentKind) : ViewModel() {
    var groups by mutableStateOf<List<Group>>(emptyList())
        private set
    var group by mutableStateOf<Group?>(null)
        private set
    var items by mutableStateOf<List<VodItem>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    /** Item to put focus back on when returning from its detail screen. */
    var lastOpenedId: String? = null
    private var endReached = false
    private var loadingMore = false
    private val watcher: Cancellable = core.watchChanges { reload() }

    override fun onCleared() = watcher.cancel()

    fun reload() {
        viewModelScope.launch {
            val base = runCatching { core.vodGroups(kind, false) }.getOrDefault(emptyList())
            val resume = runCatching { core.continueWatching(30) }.getOrDefault(emptyList())
                .filter { if (kind == ContentKind.MOVIE) it.kind == ContentKind.MOVIE else it.kind == ContentKind.SERIES }
            groups = (if (resume.isNotEmpty()) listOf(CONTINUE.copy(kind = kind)) else emptyList()) + base
            val allowed = groups.filter { core.isAllowed(it.locked) }
            val keep = allowed.firstOrNull { it.id == group?.id }
                ?: allowed.firstOrNull { it.type == GroupType.CATEGORY }
                ?: allowed.firstOrNull()
            load(keep)
        }
    }

    fun select(g: Group) {
        viewModelScope.launch { load(g) }
    }

    private suspend fun load(g: Group?) {
        group = g
        endReached = true // blocks loadMore until the first page is in
        val page = fetch(g, 0)
        if (group?.id != g?.id) return
        items = page.distinctBy { it.id }
        endReached = page.size < PAGE
        loaded = true
    }

    fun loadMore() {
        if (endReached || loadingMore) return
        loadingMore = true
        val g = group
        viewModelScope.launch {
            val more = fetch(g, items.size)
            if (group?.id == g?.id) {
                if (more.size < PAGE) endReached = true
                items = (items + more).distinctBy { it.id }
            }
            loadingMore = false
        }
    }

    private suspend fun fetch(g: Group?, offset: Int): List<VodItem> {
        g ?: return emptyList()
        return runCatching {
            when {
                g.id == "continue" -> if (offset > 0) emptyList() else core.continueWatching(30)
                    .filter { if (kind == ContentKind.MOVIE) it.kind == ContentKind.MOVIE else it.kind == ContentKind.SERIES }
                    .map { VodItem.ResumeItem(it) }
                kind == ContentKind.MOVIE -> core.movies(g, offset, PAGE).map { VodItem.MovieItem(it) }
                else -> core.series(g, offset, PAGE).map { VodItem.SeriesItem(it) }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val PAGE = 200
    }
}

class MoviesViewModel : VodViewModel(ContentKind.MOVIE)

class SeriesViewModel : VodViewModel(ContentKind.SERIES)
