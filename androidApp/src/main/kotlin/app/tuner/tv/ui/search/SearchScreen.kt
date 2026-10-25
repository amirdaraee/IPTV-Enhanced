package app.tuner.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.tuner.core.LockedException
import app.tuner.core.model.Channel
import app.tuner.core.model.SearchResults
import app.tuner.tv.core
import app.tuner.tv.ui.LocalNavigator
import app.tuner.tv.ui.Screen
import app.tuner.tv.ui.components.Artwork
import app.tuner.tv.ui.components.Dim
import app.tuner.tv.ui.components.EmptyState
import app.tuner.tv.ui.components.FocusSurface
import app.tuner.tv.ui.components.SectionTitle
import app.tuner.tv.ui.components.TvTextField
import app.tuner.tv.ui.guide.Toast
import app.tuner.tv.ui.player.LiveSession
import app.tuner.tv.ui.theme.Palette
import app.tuner.tv.util.formatRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(focusRequester: FocusRequester) {
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    var query by app.tuner.tv.ui.home.HomeState::searchQuery
    var results by remember { mutableStateOf<SearchResults?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(core.recentSearches()) }
    val labels = app.tuner.tv.ui.components.rememberSourceLabels()

    /** A result was opened: keep the query for next time. */
    fun saveQuery() {
        core.rememberSearch(query)
        recent = core.recentSearches()
    }

    LaunchedEffect(query) {
        delay(300)
        results = if (query.trim().length < 2) null else runCatching { core.search(query) }.getOrNull()
    }
    LaunchedEffect(message) { if (message != null) { delay(3000); message = null } }

    fun playChannel(ch: Channel, lineup: List<Channel>) {
        saveQuery()
        scope.launch {
            try {
                val req = core.playChannel(ch)
                LiveSession.start(lineup, ch, req)
                nav.push(Screen.LivePlayer)
            } catch (e: LockedException) {
                message = e.message
            } catch (e: Exception) {
                message = e.message
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Background).padding(start = 32.dp, top = 28.dp, end = 32.dp)) {
        TvTextField(
            label = "Search channels, programmes, movies and series",
            value = query,
            onValueChange = { query = it },
            placeholder = "Type at least 2 letters",
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
            modifier = Modifier.width(560.dp).focusRequester(focusRequester),
        )
        Spacer(Modifier.height(20.dp))
        val r = results
        when {
            r == null && query.isBlank() && recent.isNotEmpty() -> {
                SectionTitle("Recent searches")
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = RowBleed, contentPadding = RowPadding) {
                    items(recent) { q ->
                        FocusSurface(onClick = { query = q }) {
                            Text(q, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                    item(key = "clear") {
                        FocusSurface(onClick = { core.clearRecentSearches(); recent = emptyList() }) {
                            Dim("Clear", Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        }
                    }
                }
            }
            r == null -> Dim("Tip: search finds programmes on air in the next 24 hours too.")
            r.isEmpty -> EmptyState("No results", "Nothing matches \"$query\".")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp)) {
                if (r.channels.isNotEmpty()) item {
                    SectionTitle("Channels")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = RowBleed, contentPadding = RowPadding) {
                        item(key = "add-all") {
                            FocusSurface(
                                onClick = {
                                    scope.launch {
                                        val n = runCatching { core.setFavorites(r.channels, true) }.getOrDefault(0)
                                        message = if (n == 0) "Already in Favorites" else "Added $n channel${if (n == 1) "" else "s"} to Favorites"
                                    }
                                },
                                modifier = Modifier.width(200.dp),
                            ) {
                                Row(Modifier.padding(10.dp).height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("★", color = Palette.Warning, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Add all to Favorites", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                }
                            }
                        }
                        items(r.channels, key = { it.id }) { ch ->
                            FocusSurface(onClick = { playChannel(ch, r.channels) }, modifier = Modifier.width(200.dp)) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Artwork(ch.logo, ch.name, Modifier.size(width = 48.dp, height = 34.dp), contentScale = ContentScale.Fit, fontSize = 11)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        labels[ch.sourceId]?.let { Dim(it) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (r.programmes.isNotEmpty()) item {
                    SectionTitle("On TV")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = RowBleed, contentPadding = RowPadding) {
                        items(r.programmes) { hit ->
                            FocusSurface(onClick = { playChannel(hit.channel, listOf(hit.channel)) }, modifier = Modifier.width(260.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(hit.programme.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Dim(formatRange(hit.programme.start, hit.programme.stop))
                                    Dim(listOfNotNull(hit.channel.name, labels[hit.channel.sourceId]).joinToString(" · "))
                                }
                            }
                        }
                    }
                }
                if (r.movies.isNotEmpty()) item {
                    SectionTitle("Movies")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = RowBleed, contentPadding = RowPadding) {
                        items(r.movies, key = { it.id }) { m ->
                            Column(Modifier.width(120.dp)) {
                                FocusSurface(onClick = { saveQuery(); nav.push(Screen.MovieDetail(m.id)) }, modifier = Modifier.width(120.dp).height(180.dp), scale = 1.08f) {
                                    Artwork(m.poster, m.name, Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(m.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                labels[m.sourceId]?.let { Dim(it) }
                            }
                        }
                    }
                }
                if (r.series.isNotEmpty()) item {
                    SectionTitle("Series")
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = RowBleed, contentPadding = RowPadding) {
                        items(r.series, key = { it.id }) { s ->
                            Column(Modifier.width(120.dp)) {
                                FocusSurface(onClick = { saveQuery(); nav.push(Screen.SeriesDetail(s.id)) }, modifier = Modifier.width(120.dp).height(180.dp), scale = 1.08f) {
                                    Artwork(s.cover, s.name, Modifier.fillMaxSize())
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(s.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                labels[s.sourceId]?.let { Dim(it) }
                            }
                        }
                    }
                }
            }
        }
    }
    message?.let { Toast(it) }
}

// Rows bleed into the page margin so a focused card's scale-up isn't clipped,
// while the first card stays aligned with the section headings.
private val RowBleed = Modifier.offset(x = (-12).dp)
private val RowPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
