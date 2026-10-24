package app.tuner.tv.ui.guide

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tuner.core.Cancellable
import app.tuner.core.Changes
import app.tuner.core.model.Channel
import app.tuner.core.model.Group
import app.tuner.core.model.GroupType
import app.tuner.core.model.Programme
import app.tuner.tv.TunerApplication
import app.tuner.tv.core
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GuideViewModel : ViewModel() {
    private val prefs = TunerApplication.instance.getSharedPreferences("guide", Context.MODE_PRIVATE)

    var groups by mutableStateOf<List<Group>>(emptyList())
        private set
    var group by mutableStateOf<Group?>(null)
        private set
    var channels by mutableStateOf<List<Channel>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set

    /** Programmes per channel id, covering [loadedFrom, loadedTo). */
    val programmes = mutableStateMapOf<Long, List<Programme>>()
    private var loadedFrom = 0L
    private var loadedTo = 0L
    private val requested = HashSet<Long>()

    var row by mutableIntStateOf(0)
    var focusTime by mutableLongStateOf(System.currentTimeMillis())
    var windowStart by mutableLongStateOf(align(System.currentTimeMillis()))

    private val watcher: Cancellable
    private var loadJob: Job? = null

    init {
        // Fires once right away (the initial load), then on lineup, preference and history changes.
        watcher = core.watchChanges(Changes.CATALOG or Changes.PREFS or Changes.HISTORY) { reload(it) }
    }

    override fun onCleared() = watcher.cancel()

    private fun reload(changes: Int) {
        // Zapping only touches history: the open lineup is unaffected unless it is Recents,
        // and the group list only needs refreshing for Recents to appear.
        if (changes == Changes.HISTORY) {
            if (group?.type == GroupType.RECENTS) viewModelScope.launch { loadGroup(group, resetFocus = false) }
            else if (groups.none { it.type == GroupType.RECENTS }) reload()
            return
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val all = runCatching { core.liveGroups(false) }.getOrDefault(emptyList())
            groups = all.filter { it.type == GroupType.CATEGORY || it.type == GroupType.ALL || nonEmpty(it) }
            val saved = prefs.getString("group", null)
            val keep = group?.id ?: saved
            // Never auto-open a locked group; the user must pick it and enter the PIN.
            val allowed = groups.filter { core.isAllowed(it.locked) }
            val next = allowed.firstOrNull { it.id == keep }
                ?: allowed.firstOrNull { it.type == GroupType.FAVORITES }
                ?: allowed.firstOrNull { it.type == GroupType.CATEGORY }
                ?: allowed.firstOrNull()
            loadGroup(next, resetFocus = next?.id != group?.id)
        }
    }

    private suspend fun nonEmpty(g: Group) = runCatching { core.channels(g, false).isNotEmpty() }.getOrDefault(false)

    fun selectGroup(g: Group) {
        prefs.edit().putString("group", g.id).apply()
        viewModelScope.launch { loadGroup(g, resetFocus = true) }
    }

    private suspend fun loadGroup(g: Group?, resetFocus: Boolean) {
        group = g
        val list = if (g == null) emptyList() else runCatching { core.channels(g, false) }.getOrDefault(emptyList())
        val focusedId = channels.getOrNull(row)?.id
        val sameLineup = list.map { it.id } == channels.map { it.id }
        channels = list
        if (sameLineup) {
            // Only flags (favorite, lock…) or the guide changed: refresh loaded rows in place.
            refreshLoaded()
        } else {
            programmes.clear()
            requested.clear()
            lineupVersion++
        }
        row = if (resetFocus) 0 else list.indexOfFirst { it.id == focusedId }.coerceAtLeast(0)
        if (resetFocus) {
            val now = System.currentTimeMillis()
            focusTime = now
            windowStart = align(now)
        }
        loaded = true
    }

    /** Bumped whenever the programme cache is dropped, so the grid re-requests visible rows. */
    var lineupVersion by mutableIntStateOf(0)
        private set

    private fun refreshLoaded() {
        val ids = requested.toSet()
        val rows = channels.filter { it.id in ids }
        if (rows.isEmpty()) return
        val f = loadedFrom
        val t = loadedTo
        viewModelScope.launch {
            val fresh = runCatching { core.guide(rows, f, t) }.getOrDefault(emptyList())
            if (f == loadedFrom) fresh.forEach { programmes[it.channel.id] = it.programmes }
        }
    }

    /** Loads programmes for the given rows around the current window, in one batch query. */
    fun ensureLoaded(range: IntRange) {
        val from = windowStart - 6 * HOUR
        val to = windowStart + 12 * HOUR
        if (from < loadedFrom || to > loadedTo) {
            programmes.clear()
            requested.clear()
            lineupVersion++
            loadedFrom = windowStart - 12 * HOUR
            loadedTo = windowStart + 24 * HOUR
        }
        val wanted = range.mapNotNull { channels.getOrNull(it) }.filter { it.id !in requested }
        if (wanted.isEmpty()) return
        wanted.forEach { requested.add(it.id) }
        val f = loadedFrom
        val t = loadedTo
        loadJob = viewModelScope.launch {
            val rows = runCatching { core.guide(wanted, f, t) }.getOrDefault(emptyList())
            if (f == loadedFrom) rows.forEach { programmes[it.channel.id] = it.programmes }
        }
    }

    fun focusedChannel(): Channel? = channels.getOrNull(row)

    fun focusedProgramme(): Programme? {
        val ch = focusedChannel() ?: return null
        return programmes[ch.id]?.firstOrNull { focusTime >= it.start && focusTime < it.stop }
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val SLOT = 30 * MINUTE

        fun align(t: Long) = t - t % SLOT
    }
}
