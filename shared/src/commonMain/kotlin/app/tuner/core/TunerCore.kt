package app.tuner.core

import app.cash.sqldelight.db.SqlDriver
import app.tuner.core.catchup.CatchupUrlBuilder
import app.tuner.core.db.TunerDatabase
import app.tuner.core.model.Channel
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Episode
import app.tuner.core.model.Group
import app.tuner.core.model.GroupType
import app.tuner.core.model.GuideRow
import app.tuner.core.model.Movie
import app.tuner.core.model.MovieDetails
import app.tuner.core.model.PlaybackRequest
import app.tuner.core.model.Programme
import app.tuner.core.model.ProgrammeHit
import app.tuner.core.model.SearchResults
import app.tuner.core.model.Series
import app.tuner.core.model.SeriesDetails
import app.tuner.core.model.Settings
import app.tuner.core.model.Source
import app.tuner.core.model.SourceInput
import app.tuner.core.model.SourceType
import app.tuner.core.model.SyncState
import app.tuner.core.model.WatchProgress
import app.tuner.core.platform.PlatformContext
import app.tuner.core.platform.callbackDispatcher
import app.tuner.core.platform.createBackup
import app.tuner.core.platform.createDriver
import app.tuner.core.platform.createHttpClient
import app.tuner.core.platform.nowMillis
import app.tuner.core.repo.mapChannel
import app.tuner.core.repo.mapMovie
import app.tuner.core.repo.mapProgramme
import app.tuner.core.repo.mapProgress
import app.tuner.core.repo.mapSeries
import app.tuner.core.repo.mapSource
import app.tuner.core.repo.toLong
import app.tuner.core.sync.EpgImporter
import app.tuner.core.sync.SourceSyncer
import app.tuner.core.sync.describe
import app.tuner.core.util.Sha256
import app.tuner.core.util.searchNorm
import app.tuner.core.xtream.XtreamAccount
import app.tuner.core.xtream.XtreamClient
import app.tuner.core.xtream.XtreamCredentials
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.random.Random

/** Handle returned by `watch*` functions; call [cancel] to stop receiving callbacks. */
@OptIn(ExperimentalObjCName::class)
@ObjCName("Subscription", exact = true)
fun interface Cancellable {
    fun cancel()
}

/**
 * Kinds of change reported by [TunerCore.watchChanges], as bit flags. Screens listen only
 * to what they show, so zapping (HISTORY) or VOD progress saves (PROGRESS) don't make
 * the guide re-read twenty thousand channels.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("Changes", exact = true)
object Changes {
    /** Sources added, edited, removed or synced: lineups, groups, VOD and guide data. */
    const val CATALOG = 1
    /** Favorites, hidden and locked items or groups, group order. */
    const val PREFS = 2
    /** Recently watched channels and the last/previous channel. */
    const val HISTORY = 4
    /** Movie and episode watch progress (Continue watching). */
    const val PROGRESS = 8
    const val SETTINGS = 16
    const val ALL = CATALOG or PREFS or HISTORY or PROGRESS or SETTINGS
}

/** Provider separator entries ("##### SPORTS #####", "===== UK ====="), which are not real channels. */
internal fun isDivider(name: String): Boolean {
    val t = name.trim()
    return t.length >= 3 && t.take(3).all { it == '#' || it == '=' || it == '*' || it == '-' }
}

/** Thrown by the play functions when content is parental-locked and the session is not unlocked. */
class LockedException : Exception("This content is locked. Enter the parental PIN to continue.")

/**
 * The single entry point the Android TV and tvOS apps use. Every suspend
 * function runs its work off the main thread; observer callbacks are delivered
 * on the main thread. All suspend functions are annotated with @Throws so that
 * failures surface as Swift errors instead of crashing.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("Tuner", exact = true)
class TunerCore internal constructor(
    driver: SqlDriver,
    private val http: HttpClient,
    private val backup: UserDataBackup? = null,
) {

    constructor(context: PlatformContext) : this(createDriver(context), createHttpClient(context), createBackup(context))

    private val db = TunerDatabase(driver)

    /** Playlists restored from [backup] at start-up because the database was empty (e.g. purged by tvOS). */
    val restoredSources: Int = restoreFromBackup()

    // A failure in background work (refreshStale, the backup autosave) must never take the app down.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
    private val syncer = SourceSyncer(db, http) { settings.preferHlsLive }
    private val epgImporter = EpgImporter(db, http)
    private val syncLocks = HashMap<Long, Mutex>()
    private val locksGuard = Mutex()

    /** One counter per [Changes] bit; observers diff successive snapshots. */
    private val counters = MutableStateFlow(LongArray(KINDS))
    private val syncStates = MutableStateFlow<Map<Long, SyncState>>(emptyMap())
    private var sessionUnlocked = false

    var settings: Settings = loadSettings()
        private set

    init {
        if (backup != null) keepBackupCurrent(backup)
    }

    // ------------------------------------------------------------------ observation

    /**
     * Calls [callback] with the [Changes] bits (within [kinds]) that changed since the
     * previous call. The first call happens right away with all of [kinds] set, so a
     * screen can load its initial data from the same callback.
     */
    fun watchChanges(kinds: Int, callback: (Int) -> Unit): Cancellable {
        var last: LongArray? = null
        val job: Job = scope.launch(callbackDispatcher) {
            counters.collect { now ->
                val prev = last
                last = now
                var mask = 0
                for (i in 0 until KINDS) if (prev == null || prev[i] != now[i]) mask = mask or (1 shl i)
                mask = mask and kinds
                if (mask != 0) callback(mask)
            }
        }
        return Cancellable { job.cancel() }
    }

    /** Called with the state of every source that has synced during this session. */
    fun watchSync(callback: (List<SyncState>) -> Unit): Cancellable =
        observe(syncStates) { callback(it.values.toList()) }

    private fun <T> observe(flow: MutableStateFlow<T>, callback: (T) -> Unit): Cancellable {
        val job: Job = scope.launch(callbackDispatcher) { flow.collect { callback(it) } }
        return Cancellable { job.cancel() }
    }

    private fun changed(kinds: Int) = counters.update { old ->
        LongArray(KINDS) { i -> if (kinds and (1 shl i) != 0) old[i] + 1 else old[i] }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    // ------------------------------------------------------------------ user data backup

    private fun restoreFromBackup(): Int {
        val saved = backup?.load() ?: return 0
        if (db.sourceQueries.selectAll().executeAsList().isNotEmpty()) return 0
        return UserData.restore(db, saved)
    }

    /** Writes the snapshot at start-up and after changes settle; skips writes that change nothing. */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun keepBackupCurrent(backup: UserDataBackup) {
        var last: String? = null
        scope.launch(Dispatchers.IO) {
            counters.debounce(1500).collect {
                val snapshot = runCatching { UserData.export(db) }.getOrNull() ?: return@collect
                if (snapshot != last) {
                    runCatching { backup.save(snapshot) }
                    last = snapshot
                }
            }
        }
    }

    /** The user's own data (playlists, favorites, progress, settings) as JSON, for backup or transfer. */
    @Throws(Exception::class)
    suspend fun exportUserData(): String = io { UserData.export(db) }

    // ------------------------------------------------------------------ settings

    fun updateSettings(newSettings: Settings) {
        settings = newSettings
        val q = db.userQueries
        db.transaction {
            q.putSetting("guide_past_days", newSettings.guidePastDays.toString())
            q.putSetting("guide_future_days", newSettings.guideFutureDays.toString())
            q.putSetting("use_24h", newSettings.use24h.toString())
            q.putSetting("overlay_timeout", newSettings.overlayTimeoutSec.toString())
            q.putSetting("autoplay_last", newSettings.autoplayLastChannel.toString())
            q.putSetting("prefer_hls", newSettings.preferHlsLive.toString())
            q.putSetting("audio_lang", newSettings.audioLanguage)
            q.putSetting("subtitle_lang", newSettings.subtitleLanguage)
            q.putSetting("autoplay_next", newSettings.autoplayNextEpisode.toString())
        }
        changed(Changes.SETTINGS)
    }

    private fun loadSettings(): Settings {
        val q = db.userQueries
        fun get(key: String) = q.getSetting(key).executeAsOneOrNull()
        val d = Settings()
        return Settings(
            guidePastDays = get("guide_past_days")?.toIntOrNull() ?: d.guidePastDays,
            guideFutureDays = get("guide_future_days")?.toIntOrNull() ?: d.guideFutureDays,
            use24h = get("use_24h")?.toBooleanStrictOrNull() ?: d.use24h,
            overlayTimeoutSec = get("overlay_timeout")?.toIntOrNull() ?: d.overlayTimeoutSec,
            autoplayLastChannel = get("autoplay_last")?.toBooleanStrictOrNull() ?: d.autoplayLastChannel,
            preferHlsLive = get("prefer_hls")?.toBooleanStrictOrNull() ?: d.preferHlsLive,
            audioLanguage = get("audio_lang") ?: d.audioLanguage,
            subtitleLanguage = get("subtitle_lang") ?: d.subtitleLanguage,
            autoplayNextEpisode = get("autoplay_next")?.toBooleanStrictOrNull() ?: d.autoplayNextEpisode,
        )
    }

    /** Platform defaults applied only if the user never changed the setting (tvOS prefers HLS). */
    fun setPlatformDefaults(preferHlsLive: Boolean) {
        if (db.userQueries.getSetting("prefer_hls").executeAsOneOrNull() == null) {
            updateSettings(settings.copy(preferHlsLive = preferHlsLive))
        }
    }

    // ------------------------------------------------------------------ sources

    @Throws(Exception::class)
    suspend fun sources(): List<Source> = io { db.sourceQueries.selectAll(::mapSource).executeAsList() }

    @Throws(Exception::class)
    suspend fun source(id: Long): Source? = io { db.sourceQueries.selectById(id, ::mapSource).executeAsOneOrNull() }

    @Throws(Exception::class)
    suspend fun hasSources(): Boolean = sources().isNotEmpty()

    /** Enabled accounts whose subscription ends within [days] days (or has already ended). */
    @Throws(Exception::class)
    suspend fun expiringSources(days: Int): List<Source> {
        val limit = nowMillis() + days * DAY
        return sources().filter { s -> s.enabled && s.expiresAt.let { it != null && it < limit } }
    }

    /** Checks Xtream credentials without saving anything. */
    @Throws(Exception::class)
    suspend fun verifyXtream(server: String, username: String, password: String): XtreamAccount =
        XtreamClient(http, XtreamCredentials(server, username, password)).authenticate()

    /** Saves a source and starts its first sync in the background. */
    @Throws(Exception::class)
    suspend fun addSource(input: SourceInput): Long {
        val id = io {
            db.transactionWithResult {
                db.sourceQueries.insert(
                    input.name.trim().ifEmpty { "Playlist" }, input.type.name, normaliseUrl(input), input.username?.trim(),
                    input.password, input.epgUrls.joinToString("\n"), input.userAgent?.trim()?.ifEmpty { null },
                    input.epgOffsetMinutes.toLong(), input.refreshHours.toLong(), input.enabled.toLong(),
                )
                db.sourceQueries.lastInsertId().executeAsOne()
            }
        }
        changed(Changes.CATALOG)
        refresh(id)
        return id
    }

    @Throws(Exception::class)
    suspend fun updateSource(id: Long, input: SourceInput) {
        io {
            db.sourceQueries.update(
                input.name.trim(), normaliseUrl(input), input.username?.trim(), input.password,
                input.epgUrls.joinToString("\n"), input.userAgent?.trim()?.ifEmpty { null },
                input.epgOffsetMinutes.toLong(), input.refreshHours.toLong(), input.enabled.toLong(), id,
            )
        }
        changed(Changes.CATALOG)
    }

    @Throws(Exception::class)
    suspend fun deleteSource(id: Long) {
        io {
            db.transaction {
                db.catalogQueries.deleteSourcePrefs(id)
                db.epgQueries.deleteProgrammes(id)
                db.epgQueries.deleteEpgChannels(id)
                db.userQueries.deleteSourceUserData(id)
                db.sourceQueries.delete(id)
            }
        }
        syncStates.update { it - id }
        changed(Changes.ALL)
    }

    private fun normaliseUrl(input: SourceInput) =
        if (input.type == SourceType.XTREAM) XtreamCredentials.normaliseServer(input.url) else input.url.trim()

    /** True while any source is syncing. */
    fun sourcesSyncing(): Boolean = syncStates.value.values.any { it.running }

    /** Starts a background sync of one source (no-op if one is already running). */
    fun refresh(sourceId: Long) {
        scope.launch { refreshNow(sourceId) }
    }

    /** Syncs every enabled source whose data is older than its refresh interval. */
    fun refreshStale() {
        scope.launch {
            val now = nowMillis()
            sources().filter { it.enabled }.forEach { s ->
                val age = now - (s.lastRefreshed ?: 0L)
                if (age > s.refreshHours * 3_600_000L) refreshNow(s.id)
            }
            io { db.epgQueries.pruneProgrammes(now - settings.guidePastDays * DAY) }
        }
    }

    /** Syncs a source and its guide; returns an error message, or null on success. */
    @Throws(Exception::class)
    suspend fun refreshNow(sourceId: Long): String? {
        val lock = locksGuard.withLock { syncLocks.getOrPut(sourceId) { Mutex() } }
        // tryLock, not isLocked + withLock: two callers could both see "free" and sync twice.
        if (!lock.tryLock()) {
            // A sync is already running: wait for it and report its outcome.
            lock.withLock { }
            return syncStates.value[sourceId]?.error
        }
        return try { syncLocked(sourceId) } finally { lock.unlock() }
    }

    private suspend fun syncLocked(sourceId: Long): String? {
        run {
            val source = source(sourceId) ?: return "Source not found"
            fun report(phase: String, count: Int, running: Boolean = true, error: String? = null) =
                syncStates.update { it + (sourceId to SyncState(sourceId, running, phase, count, error)) }
            report("Starting", 0)
            return try {
                val kept = withContext(Dispatchers.IO) { syncer.sync(source, ::report) }
                // Deleted while syncing: nothing was saved; forget its sync state too.
                val fresh = source(sourceId)
                if (!kept || fresh == null) {
                    syncStates.update { it - sourceId }
                    return "Source removed"
                }
                changed(Changes.CATALOG)
                val now = nowMillis()
                val guideError = try {
                    withContext(Dispatchers.IO) {
                        epgImporter.import(fresh, now - settings.guidePastDays * DAY, now + settings.guideFutureDays * DAY, ::report)
                    }
                    null
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    "Guide: ${describe(e)}"
                }
                if (source(sourceId) == null) {
                    syncStates.update { it - sourceId }
                    return "Source removed"
                }
                io { db.sourceQueries.markRefreshed(nowMillis(), sourceId) }
                guideError?.let { msg -> io { db.sourceQueries.markError(msg, sourceId) } }
                report("Done", 0, running = false, error = guideError)
                changed(Changes.CATALOG)
                guideError
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = describe(e)
                io { db.sourceQueries.markError(msg, sourceId) }
                report("Failed", 0, running = false, error = msg)
                changed(Changes.CATALOG)
                msg
            }
        }
    }

    // ------------------------------------------------------------------ groups

    /** Live groups: Favorites, Recents, All channels, then provider categories. */
    @Throws(Exception::class)
    suspend fun liveGroups(includeHidden: Boolean): List<Group> = io {
        val specials = listOf(
            Group("fav", GroupType.FAVORITES, ContentKind.LIVE, 0, "", "Favorites", null, false, false),
            Group("recent", GroupType.RECENTS, ContentKind.LIVE, 0, "", "Recent", null, false, false),
            Group("all", GroupType.ALL, ContentKind.LIVE, 0, "", "All channels", null, false, false),
        )
        specials + categoryGroups(ContentKind.LIVE, includeHidden)
    }

    @Throws(Exception::class)
    suspend fun vodGroups(kind: ContentKind, includeHidden: Boolean): List<Group> = io {
        val fav = Group("fav", GroupType.FAVORITES, kind, 0, "", "Favorites", null, false, false)
        val recent = Group("recent", GroupType.RECENTS, kind, 0, "", "Recently added", null, false, false)
        (if (kind == ContentKind.MOVIE) listOf(fav, recent) else listOf(fav)) + categoryGroups(kind, includeHidden)
    }

    private fun categoryGroups(kind: ContentKind, includeHidden: Boolean): List<Group> {
        val rows = db.catalogQueries.groups(kind.name) { sourceId, k, ckey, name, _, hidden, locked, _, sourceName ->
            Group("c:$sourceId:$k:$ckey", GroupType.CATEGORY, kind, sourceId, ckey, name, sourceName, hidden != 0L, locked != 0L)
        }.executeAsList()
        val multiSource = rows.map { it.sourceId }.distinct().size > 1
        val labels = if (multiSource) labelsBySource() else emptyMap()
        return rows.filter { includeHidden || !it.hidden }
            .map { it.copy(sourceName = labels[it.sourceId]) }
    }

    /**
     * A name per source for telling same-named channels, groups and titles apart: its own name,
     * plus the Xtream username or the playlist file name when two sources share a name. Empty with
     * a single source, where a label would be noise.
     */
    @Throws(Exception::class)
    suspend fun sourceLabels(): Map<Long, String> = io { labelsBySource() }

    private fun labelsBySource(): Map<Long, String> {
        val sources = db.sourceQueries.selectAll(::mapSource).executeAsList()
        if (sources.size < 2) return emptyMap()
        val count = sources.groupingBy { it.name }.eachCount()
        return sources.associate { s ->
            val detail = if (s.type == SourceType.XTREAM) s.username?.takeIf { it.isNotBlank() } ?: "Xtream"
                else s.url.substringBefore('?').substringAfterLast('/').ifBlank { "M3U" }
            s.id to if ((count[s.name] ?: 0) > 1) "${s.name} ($detail)" else s.name
        }
    }

    @Throws(Exception::class)
    suspend fun setGroupHidden(group: Group, hidden: Boolean) = groupPref(group) {
        db.catalogQueries.setGroupHidden(hidden.toLong(), group.sourceId, group.kind.name, group.key)
    }

    @Throws(Exception::class)
    suspend fun setGroupLocked(group: Group, locked: Boolean) = groupPref(group) {
        db.catalogQueries.setGroupLocked(locked.toLong(), group.sourceId, group.kind.name, group.key)
    }

    /** Moves a category up (negative [delta]) or down in the user's custom order. */
    @Throws(Exception::class)
    suspend fun moveGroup(group: Group, delta: Int) {
        if (group.type != GroupType.CATEGORY) return
        io {
            val all = categoryGroups(group.kind, includeHidden = true).toMutableList()
            val from = all.indexOfFirst { it.id == group.id }
            if (from < 0) return@io
            val to = (from + delta).coerceIn(0, all.lastIndex)
            all.add(to, all.removeAt(from))
            db.transaction {
                all.forEachIndexed { i, g ->
                    db.catalogQueries.upsertGroupPrefInsert(g.sourceId, g.kind.name, g.key)
                    db.catalogQueries.setGroupPosition(i.toLong(), g.sourceId, g.kind.name, g.key)
                }
            }
        }
        changed(Changes.PREFS)
    }

    private suspend fun groupPref(group: Group, block: () -> Unit) {
        if (group.type != GroupType.CATEGORY) return
        io {
            db.transaction {
                db.catalogQueries.upsertGroupPrefInsert(group.sourceId, group.kind.name, group.key)
                block()
            }
        }
        changed(Changes.PREFS)
    }

    // ------------------------------------------------------------------ channels

    /** Refuses to list a locked category until the PIN has been entered (checked against the DB, not the caller's copy). */
    private fun requireGroupAllowed(group: Group) {
        if (group.type != GroupType.CATEGORY) return
        val locked = db.catalogQueries.groupLocked(group.sourceId, group.kind.name, group.key).executeAsOneOrNull() == 1L
        requireAllowed(locked)
    }

    @Throws(Exception::class)
    suspend fun channels(group: Group, includeHidden: Boolean): List<Channel> = io {
        requireGroupAllowed(group)
        val q = db.catalogQueries
        when (group.type) {
            GroupType.FAVORITES -> q.favoriteChannels(::mapChannel).executeAsList()
            GroupType.RECENTS -> q.recentChannels(::mapChannel).executeAsList()
            GroupType.ALL -> q.allChannels(MAX_ALL, ::mapChannel).executeAsList()
            GroupType.CATEGORY -> q.channelsInGroup(group.sourceId, group.key, includeHidden.toLong(), ::mapChannel).executeAsList()
        }
    }

    @Throws(Exception::class)
    suspend fun channel(id: Long): Channel? = io { db.catalogQueries.channelById(id, ::mapChannel).executeAsOneOrNull() }

    @Throws(Exception::class)
    suspend fun hiddenChannels(): List<Channel> = io { db.catalogQueries.hiddenChannels(::mapChannel).executeAsList() }

    /** Programmes for each channel overlapping [from, to), in channel order. */
    @Throws(Exception::class)
    suspend fun guide(channels: List<Channel>, from: Long, to: Long): List<GuideRow> = io {
        val keys = channels.mapNotNull { it.epgKey }.distinct()
        val byKey = HashMap<String, MutableList<Programme>>()
        keys.chunked(400).forEach { chunk ->
            db.epgQueries.programmesInWindow(chunk, from, to, ::mapProgramme).executeAsList().forEach {
                byKey.getOrPut(it.channelEpgId) { ArrayList() }.add(it)
            }
        }
        channels.map { GuideRow(it, it.epgKey?.let { k -> byKey[k] }.orEmpty()) }
    }

    /** Current programme first (if any), then upcoming ones. */
    @Throws(Exception::class)
    suspend fun upcoming(channel: Channel, limit: Int): List<Programme> = io {
        val key = channel.epgKey ?: return@io emptyList()
        db.epgQueries.nextProgrammes(key, nowMillis(), limit.toLong(), ::mapProgramme).executeAsList()
    }

    // ------------------------------------------------------------------ preferences

    @Throws(Exception::class)
    suspend fun setFavorite(kind: ContentKind, sourceId: Long, key: String, favorite: Boolean) = itemPref(kind, sourceId, key) {
        db.catalogQueries.setFavorite(favorite.toLong(), nextFavoriteStamp(kind), sourceId, kind.name, key)
    }

    /**
     * Favorites are listed by favorite_at. New ones go after every existing one: stamps from a
     * bulk add (one per channel) or a reorder (small numbers) must never run ahead of a later add.
     */
    private fun nextFavoriteStamp(kind: ContentKind): Long {
        val latest = db.catalogQueries.maxFavoriteAt(kind.name).executeAsOne().latest ?: 0L
        return maxOf(nowMillis(), latest + 1)
    }

    /**
     * Adds (or removes) many channels at once, e.g. every search result or a whole group,
     * in one transaction. Divider entries like "##### SPORTS #####" are skipped.
     * Returns the number of channels changed.
     */
    @Throws(Exception::class)
    suspend fun setFavorites(channels: List<Channel>, favorite: Boolean): Int {
        val targets = channels.filter { !isDivider(it.name) && it.favorite != favorite }
        if (targets.isEmpty()) return 0
        io {
            val q = db.catalogQueries
            db.transaction {
                val first = nextFavoriteStamp(ContentKind.LIVE)
                targets.forEachIndexed { i, ch ->
                    q.ensureItemPref(ch.sourceId, ContentKind.LIVE.name, ch.key)
                    // Keep the order they were given in: Favorites sorts by time added.
                    q.setFavorite(favorite.toLong(), first + i, ch.sourceId, ContentKind.LIVE.name, ch.key)
                }
            }
        }
        changed(Changes.PREFS)
        return targets.size
    }

    /**
     * Moves a favorite channel up (negative [delta]) or down in the Favorites list.
     * Favorites are ordered by the time they were added, so the list is renumbered in its new order.
     */
    @Throws(Exception::class)
    suspend fun moveFavorite(channel: Channel, delta: Int) {
        io {
            val q = db.catalogQueries
            val list = q.favoriteChannels(::mapChannel).executeAsList().toMutableList()
            val from = list.indexOfFirst { it.id == channel.id }
            if (from < 0) return@io
            val to = (from + delta).coerceIn(0, list.lastIndex)
            if (to == from) return@io
            list.add(to, list.removeAt(from))
            // Small numbers keep the reordered list ahead of anything favorited later (stamped with the time).
            db.transaction {
                list.forEachIndexed { i, ch -> q.setFavoriteAt(i.toLong(), ch.sourceId, ContentKind.LIVE.name, ch.key) }
            }
        }
        changed(Changes.PREFS)
    }

    @Throws(Exception::class)
    suspend fun setHidden(kind: ContentKind, sourceId: Long, key: String, hidden: Boolean) = itemPref(kind, sourceId, key) {
        db.catalogQueries.setItemHidden(hidden.toLong(), sourceId, kind.name, key)
    }

    @Throws(Exception::class)
    suspend fun setLocked(kind: ContentKind, sourceId: Long, key: String, locked: Boolean) {
        io {
            db.transaction {
                // A playlist that repeats an entry gets "key", "key#2"…; a lock must cover every copy.
                for (k in copyKeys(kind, sourceId, key)) {
                    db.catalogQueries.ensureItemPref(sourceId, kind.name, k)
                    db.catalogQueries.setItemLocked(locked.toLong(), sourceId, kind.name, k)
                }
            }
        }
        changed(Changes.PREFS)
    }

    /** An item and its repeated copies in the same playlist (see SourceSyncer's uniqueKey). */
    private fun copyKeys(kind: ContentKind, sourceId: Long, key: String): List<String> {
        val base = key.replace(COPY_SUFFIX, "")
        val candidates = when (kind) {
            ContentKind.LIVE -> db.catalogQueries.channelKeysWithPrefix(sourceId, base).executeAsList()
            ContentKind.MOVIE -> db.catalogQueries.movieKeysWithPrefix(sourceId, base).executeAsList()
            else -> emptyList()
        }
        return (candidates.filter { it == base || COPY_ONLY.matches(it.removePrefix(base)) } + key).distinct()
    }

    private suspend fun itemPref(kind: ContentKind, sourceId: Long, key: String, block: () -> Unit) {
        io {
            db.transaction {
                db.catalogQueries.ensureItemPref(sourceId, kind.name, key)
                block()
            }
        }
        changed(Changes.PREFS)
    }

    // ------------------------------------------------------------------ VOD

    @Throws(Exception::class)
    suspend fun movies(group: Group, offset: Int, limit: Int): List<Movie> = io {
        requireGroupAllowed(group)
        val q = db.catalogQueries
        when (group.type) {
            GroupType.FAVORITES -> q.favoriteMovies(::mapMovie).executeAsList()
            GroupType.RECENTS -> q.recentMovies(limit.toLong(), ::mapMovie).executeAsList()
            GroupType.ALL -> emptyList()
            GroupType.CATEGORY -> q.moviesInGroup(group.sourceId, group.key, limit.toLong(), offset.toLong(), ::mapMovie).executeAsList()
        }
    }

    @Throws(Exception::class)
    suspend fun series(group: Group, offset: Int, limit: Int): List<Series> = io {
        requireGroupAllowed(group)
        val q = db.catalogQueries
        when (group.type) {
            GroupType.FAVORITES -> q.favoriteSeries(::mapSeries).executeAsList()
            GroupType.CATEGORY -> q.seriesInGroup(group.sourceId, group.key, limit.toLong(), offset.toLong(), ::mapSeries).executeAsList()
            else -> emptyList()
        }
    }

    @Throws(Exception::class)
    suspend fun movie(id: Long): Movie? = io { db.catalogQueries.movieById(id, ::mapMovie).executeAsOneOrNull() }

    @Throws(Exception::class)
    suspend fun seriesById(id: Long): Series? = io { db.catalogQueries.seriesById(id, ::mapSeries).executeAsOneOrNull() }

    @Throws(Exception::class)
    suspend fun movieDetails(movie: Movie): MovieDetails {
        val progress = progress(movieKey(movie))
        val source = source(movie.sourceId)
        val remote = movie.remoteId
        if (source?.type != SourceType.XTREAM || remote == null) {
            return MovieDetails(movie, null, null, null, null, null, null, null, progress)
        }
        val info = runCatching { syncer.xtreamClient(source).vodInfo(remote) }.getOrNull()
        return MovieDetails(
            movie, info?.plot, info?.cast, info?.director, info?.genre, info?.durationSecs,
            info?.backdrop, info?.trailer, progress,
        )
    }

    @Throws(Exception::class)
    suspend fun seriesDetails(series: Series): SeriesDetails {
        val source = source(series.sourceId) ?: throw IllegalStateException("Source removed")
        val client = syncer.xtreamClient(source)
        val info = client.seriesInfo(series.remoteId)
        val progressByKey = io {
            db.userQueries.progressByParent(seriesKey(series), ::mapProgress).executeAsList().associateBy { it.key }
        }
        val episodes = info.episodes.map { e ->
            Episode(
                e.id, e.season, e.episode, e.title, e.plot, e.image, e.durationSecs,
                client.streamUrl(ContentKind.SERIES, e.id, e.extension),
                progressByKey[episodeKey(series.sourceId, e.id)],
            )
        }
        return SeriesDetails(
            series, info.plot ?: series.plot, info.cast, info.genre ?: series.genre, info.backdrop,
            episodes.map { it.season }.distinct().sorted(), episodes,
        )
    }

    /** The episode after the one [request] plays, ready to play; null at the end of the series or for other content. */
    @Throws(Exception::class)
    suspend fun nextEpisode(request: PlaybackRequest): PlaybackRequest? {
        if (request.kind != ContentKind.SERIES) return null
        val parts = request.progressParentKey?.split(':', limit = 3) ?: return null
        val sourceId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val key = parts.getOrNull(2) ?: return null
        val series = io { db.catalogQueries.seriesByKey(sourceId, key, ::mapSeries).executeAsOneOrNull() } ?: return null
        val episodes = seriesDetails(series).episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        val index = episodes.indexOfFirst { episodeKey(series.sourceId, it.remoteId) == request.progressKey }
        val next = episodes.getOrNull(index + 1)?.takeIf { index >= 0 } ?: return null
        return playEpisode(series, next)
    }

    @Throws(Exception::class)
    suspend fun continueWatching(limit: Int): List<WatchProgress> =
        io { db.userQueries.continueWatching(limit.toLong(), ::mapProgress).executeAsList() }

    private suspend fun progress(key: String): WatchProgress? =
        io { db.userQueries.progressByKey(key, ::mapProgress).executeAsOneOrNull() }

    // ------------------------------------------------------------------ playback

    @Throws(Exception::class)
    suspend fun playChannel(channel: Channel): PlaybackRequest {
        requireAllowed(channel.locked)
        io {
            db.transaction {
                val q = db.userQueries
                val current = q.getSetting(LAST_CHANNEL).executeAsOneOrNull()
                val id = "${channel.sourceId}|${channel.key}"
                if (current != null && current != id) q.putSetting(PREVIOUS_CHANNEL, current)
                q.putSetting(LAST_CHANNEL, id)
                q.touchRecent(channel.sourceId, channel.key, nowMillis())
                q.trimRecent()
            }
        }
        changed(Changes.HISTORY)
        return PlaybackRequest(
            channel.url, channel.name, null, channel.logo, channel.userAgent, channel.referrer,
            isLive = true, startPositionMs = 0, progressKey = null, progressParentKey = null,
            sourceId = channel.sourceId, kind = ContentKind.LIVE, channelId = channel.id,
        )
    }

    /** Archive playback for a past programme; null when the channel has no catch-up. */
    @Throws(Exception::class)
    suspend fun playCatchup(channel: Channel, programme: Programme): PlaybackRequest? {
        requireAllowed(channel.locked)
        val source = source(channel.sourceId) ?: return null
        val xtream = if (source.type == SourceType.XTREAM) syncer.xtreamClient(source) else null
        val now = nowMillis()
        if (programme.start >= now || programme.start < now - channel.catchupDays * DAY) return null
        val url = CatchupUrlBuilder.build(channel, programme.start, programme.stop, now, xtream, source.serverTimezone)
            ?: return null
        return PlaybackRequest(
            url, programme.title, channel.name, channel.logo, channel.userAgent, channel.referrer,
            isLive = false, startPositionMs = 0, progressKey = null, progressParentKey = null,
            sourceId = channel.sourceId, kind = ContentKind.LIVE, channelId = channel.id,
        )
    }

    /** The channel watched before the current one, for "last channel" toggling. */
    @Throws(Exception::class)
    suspend fun previousChannel(): Channel? = settingChannel(PREVIOUS_CHANNEL)

    @Throws(Exception::class)
    suspend fun lastChannel(): Channel? = settingChannel(LAST_CHANNEL)

    private suspend fun settingChannel(key: String): Channel? = io {
        val value = db.userQueries.getSetting(key).executeAsOneOrNull() ?: return@io null
        val sourceId = value.substringBefore('|').toLongOrNull() ?: return@io null
        db.catalogQueries.channelByKey(sourceId, value.substringAfter('|'), ::mapChannel).executeAsOneOrNull()
    }

    @Throws(Exception::class)
    suspend fun playMovie(movie: Movie): PlaybackRequest {
        requireAllowed(movie.locked)
        val p = progress(movieKey(movie))
        val source = source(movie.sourceId)
        return PlaybackRequest(
            movie.url, movie.name, movie.year, movie.poster, source?.userAgent, null,
            isLive = false, startPositionMs = resumePosition(p), progressKey = movieKey(movie), progressParentKey = null,
            sourceId = movie.sourceId, kind = ContentKind.MOVIE, channelId = null,
        )
    }

    @Throws(Exception::class)
    suspend fun playEpisode(series: Series, episode: Episode): PlaybackRequest {
        requireAllowed(series.locked)
        val key = episodeKey(series.sourceId, episode.remoteId)
        val p = progress(key)
        val source = source(series.sourceId)
        return PlaybackRequest(
            episode.url, episode.title, "${series.name} · S${episode.season} E${episode.episode}", episode.image ?: series.cover,
            source?.userAgent, null, isLive = false, startPositionMs = resumePosition(p), progressKey = key,
            progressParentKey = seriesKey(series), sourceId = series.sourceId, kind = ContentKind.SERIES, channelId = null,
        )
    }

    /** Records VOD progress; call periodically and when playback stops. */
    @Throws(Exception::class)
    suspend fun saveProgress(request: PlaybackRequest, positionMs: Long, durationMs: Long) {
        val key = request.progressKey ?: return
        if (positionMs < 5_000) return
        io {
            db.userQueries.saveProgress(
                key, request.sourceId, request.kind.name, request.title, request.subtitle, request.image, request.url,
                positionMs, durationMs, nowMillis(), request.progressParentKey,
            )
        }
        changed(Changes.PROGRESS)
    }

    /**
     * Resumes a "Continue watching" entry. The owning movie or series is looked
     * up so parental locks apply exactly as when opening it from its category.
     */
    @Throws(Exception::class)
    suspend fun playProgress(progress: WatchProgress): PlaybackRequest {
        val locked = io {
            val (kind, ref) = when (progress.kind) {
                ContentKind.MOVIE -> "movie" to progress.key
                else -> "series" to (progress.parentKey ?: "")
            }
            val parts = ref.split(':', limit = 3)
            val sourceId = parts.getOrNull(1)?.toLongOrNull()
            val key = parts.getOrNull(2)
            // Fail closed: an entry we can't tie back to the catalog is treated as locked.
            if (sourceId == null || key == null || parts[0] != kind) return@io true
            if (kind == "movie") db.catalogQueries.movieByKey(sourceId, key, ::mapMovie).executeAsOneOrNull()?.locked ?: true
            else db.catalogQueries.seriesByKey(sourceId, key, ::mapSeries).executeAsOneOrNull()?.locked ?: true
        }
        requireAllowed(locked)
        val source = source(progress.sourceId)
        return PlaybackRequest(
            progress.url, progress.title, progress.subtitle, progress.image, source?.userAgent, null,
            isLive = false, startPositionMs = resumePosition(progress), progressKey = progress.key,
            progressParentKey = progress.parentKey, sourceId = progress.sourceId, kind = progress.kind, channelId = null,
        )
    }

    @Throws(Exception::class)
    suspend fun clearProgress(key: String) {
        io { db.userQueries.deleteProgress(key) }
        changed(Changes.PROGRESS)
    }

    private fun resumePosition(p: WatchProgress?): Long =
        if (p == null || p.watched) 0 else (p.positionMs - 5_000).coerceAtLeast(0)

    private fun movieKey(m: Movie) = "movie:${m.sourceId}:${m.key}"
    private fun seriesKey(s: Series) = "series:${s.sourceId}:${s.key}"
    private fun episodeKey(sourceId: Long, episodeId: String) = "episode:$sourceId:$episodeId"

    // ------------------------------------------------------------------ search

    @Throws(Exception::class)
    suspend fun search(query: String): SearchResults {
        // Capped: a pathological query would make SQLite reject the LIKE pattern.
        val q = searchNorm(query.take(MAX_QUERY))
        if (q.length < 2) return SearchResults(emptyList(), emptyList(), emptyList(), emptyList())
        return io {
            val c = db.catalogQueries
            val now = nowMillis()
            // Same folding as the channel search; % _ \ are escaped for LIKE … ESCAPE '\'.
            val pattern = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val programmes = db.epgQueries.searchProgrammes(from = now, to = now + DAY, query = pattern, limit = 50) {
                    _, key, start, stop, title, desc, cat, icon, ep, _, channelId, _, _ ->
                channelId to Programme(key, start, stop, title, desc, cat, icon, ep)
            }.executeAsList().mapNotNull { (channelId, programme) ->
                c.channelById(channelId, ::mapChannel).executeAsOneOrNull()?.let { ProgrammeHit(programme, it) }
            }
            SearchResults(
                c.searchChannels(q, 50, ::mapChannel).executeAsList(),
                c.searchMovies(q, 50, ::mapMovie).executeAsList(),
                c.searchSeries(q, 50, ::mapSeries).executeAsList(),
                programmes,
            )
        }
    }

    /** Searches the user opened a result from, newest first. */
    fun recentSearches(): List<String> =
        db.userQueries.getSetting(RECENT_SEARCHES).executeAsOneOrNull()?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    /** Remembers [query] at the top of [recentSearches] (case-insensitive, at most [MAX_RECENT_SEARCHES]). */
    fun rememberSearch(query: String) {
        val q = query.trim().replace('\n', ' ')
        if (q.length < 2) return
        val list = (listOf(q) + recentSearches().filter { !it.equals(q, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
        db.userQueries.putSetting(RECENT_SEARCHES, list.joinToString("\n"))
        changed(Changes.SETTINGS)
    }

    fun clearRecentSearches() {
        db.userQueries.removeSetting(RECENT_SEARCHES)
        changed(Changes.SETTINGS)
    }

    // ------------------------------------------------------------------ parental PIN

    fun hasPin(): Boolean = db.userQueries.getSetting(PIN_HASH).executeAsOneOrNull() != null

    fun setPin(pin: String) {
        val salt = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        db.transaction {
            db.userQueries.putSetting(PIN_SALT, salt)
            db.userQueries.putSetting(PIN_HASH, Sha256.hex(salt + pin))
        }
        sessionUnlocked = false
        changed(Changes.SETTINGS) // so the backup copy carries the new PIN
    }

    fun clearPin() {
        db.transaction {
            db.userQueries.removeSetting(PIN_HASH)
            db.userQueries.removeSetting(PIN_SALT)
            db.userQueries.removeSetting(PIN_FAILURES)
            db.userQueries.removeSetting(PIN_LOCKED_UNTIL)
        }
        changed(Changes.SETTINGS)
    }

    /**
     * Verifies the PIN; on success, locked content stays unlocked until [lockSession].
     * After 5 wrong attempts further tries are refused for a growing period
     * (30 s, doubling, capped at 15 min); the counter survives restarts.
     */
    fun unlock(pin: String): Boolean {
        val q = db.userQueries
        val hash = q.getSetting(PIN_HASH).executeAsOneOrNull() ?: return true
        if (pinLockoutRemainingMs() > 0) return false
        val salt = q.getSetting(PIN_SALT).executeAsOneOrNull().orEmpty()
        val ok = Sha256.hex(salt + pin) == hash
        if (ok) {
            sessionUnlocked = true
            q.removeSetting(PIN_FAILURES)
            q.removeSetting(PIN_LOCKED_UNTIL)
        } else {
            val failures = (q.getSetting(PIN_FAILURES).executeAsOneOrNull()?.toIntOrNull() ?: 0) + 1
            q.putSetting(PIN_FAILURES, failures.toString())
            if (failures >= MAX_PIN_ATTEMPTS) {
                val delay = (30_000L shl (failures - MAX_PIN_ATTEMPTS).coerceAtMost(5)).coerceAtMost(15 * 60_000L)
                q.putSetting(PIN_LOCKED_UNTIL, (nowMillis() + delay).toString())
            }
        }
        return ok
    }

    /** Milliseconds until PIN entry is allowed again after too many wrong attempts (0 = allowed). */
    fun pinLockoutRemainingMs(): Long {
        val until = db.userQueries.getSetting(PIN_LOCKED_UNTIL).executeAsOneOrNull()?.toLongOrNull() ?: return 0
        return (until - nowMillis()).coerceAtLeast(0)
    }

    private fun requireAllowed(locked: Boolean) {
        if (!isAllowed(locked)) throw LockedException()
    }

    fun lockSession() {
        sessionUnlocked = false
    }

    /** True when [locked] content may be shown without asking for the PIN. */
    fun isAllowed(locked: Boolean): Boolean = !locked || sessionUnlocked || !hasPin()

    fun close() {
        scope.coroutineContext[Job]?.cancel()
        http.close()
    }

    companion object {
        const val DAY = 86_400_000L
        private const val KINDS = 5
        /** A safety cap on "All channels", above the largest lineups seen in practice. */
        private const val MAX_ALL = 50_000L
        private val COPY_SUFFIX = Regex("#\\d+$")
        private val COPY_ONLY = Regex("#\\d+")
        internal const val LAST_CHANNEL = "last_channel"
        internal const val PREVIOUS_CHANNEL = "previous_channel"
        private const val PIN_HASH = "pin_hash"
        private const val PIN_SALT = "pin_salt"
        internal const val PIN_FAILURES = "pin_failures"
        internal const val PIN_LOCKED_UNTIL = "pin_locked_until"
        private const val MAX_PIN_ATTEMPTS = 5
        private const val RECENT_SEARCHES = "recent_searches"
        private const val MAX_RECENT_SEARCHES = 8
        private const val MAX_QUERY = 200
    }
}
