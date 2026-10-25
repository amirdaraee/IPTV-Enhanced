package app.tuner.core.sync

import app.tuner.core.catchup.CatchupUrlBuilder
import app.tuner.core.db.TunerDatabase
import app.tuner.core.io.redact
import app.tuner.core.io.streamText
import app.tuner.core.m3u.M3uEntry
import app.tuner.core.m3u.M3uParser
import app.tuner.core.model.CatchupMode
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Source
import app.tuner.core.model.SourceType
import app.tuner.core.util.searchNorm
import app.tuner.core.xtream.XtreamClient
import app.tuner.core.xtream.XtreamCredentials
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException

internal fun interface ProgressSink {
    fun report(phase: String, count: Int)
}

/**
 * Downloads a source and replaces its catalog atomically. New rows are staged
 * under the negated source id in batches, then swapped in with one short
 * transaction, so the UI keeps showing the old lineup until the new one is
 * complete and a failed sync leaves the old data untouched.
 */
internal class SourceSyncer(
    private val db: TunerDatabase,
    private val http: HttpClient,
    private val preferHlsLive: () -> Boolean,
) {
    private val catalog get() = db.catalogQueries

    /** Returns false when the source was deleted while syncing; its staged rows are then discarded. */
    suspend fun sync(source: Source, progress: ProgressSink): Boolean {
        clearStaging(source.id)
        val rekeys = try {
            when (source.type) {
                SourceType.M3U -> syncM3u(source, progress)
                SourceType.XTREAM -> { syncXtream(source, progress); emptyMap() }
            }
        } catch (e: Throwable) {
            clearStaging(source.id)
            throw e
        }
        progress.report("Saving", 0)
        return db.transactionWithResult {
            val id = source.id
            // Deleted mid-sync: promoting would orphan the rows under an id that no longer exists.
            if (db.sourceQueries.selectById(id).executeAsOneOrNull() == null) {
                clearStaging(id)
                return@transactionWithResult false
            }
            catalog.deleteAllCategories(id); catalog.promoteCategories(id)
            catalog.deleteChannels(id); catalog.promoteChannels(id)
            catalog.deleteMovies(id); catalog.promoteMovies(id)
            catalog.deleteSeries(id); catalog.promoteSeries(id)
            rekey(id, rekeys)
            true
        }
    }

    /** The keys the user's own data refers to in this source: prefs, recents, progress, last channels. */
    private fun userKeys(sourceId: Long): Set<String> {
        val u = db.userQueries
        val movie = "movie:$sourceId:"
        val channel = "$sourceId|"
        return buildSet {
            addAll(catalog.itemPrefKeys(sourceId).executeAsList())
            addAll(u.recentKeys(sourceId).executeAsList())
            u.progressKeys(sourceId).executeAsList().forEach { if (it.startsWith(movie)) add(it.removePrefix(movie)) }
            for (s in LAST_CHANNELS) u.getSetting(s).executeAsOneOrNull()?.let { if (it.startsWith(channel)) add(it.removePrefix(channel)) }
        }
    }

    /**
     * Moves the user's data from old item keys to the new ones (see [m3uKey]). An old key can
     * become several new ones (repeated entries used to share one key): hidden and locked carry
     * over to every copy and are merged, never cleared; favorite, history and progress go to the
     * first copy.
     */
    private fun rekey(sourceId: Long, keys: Map<String, List<String>>) {
        if (keys.isEmpty()) return
        val u = db.userQueries
        for ((old, new) in keys) {
            val prefs = catalog.itemPrefsForKey(sourceId, old).executeAsList()
            for (p in prefs) {
                new.forEachIndexed { i, key ->
                    val cur = catalog.itemPref(sourceId, p.kind, key).executeAsOneOrNull()
                    val favorite = cur?.favorite == 1L || (i == 0 && p.favorite == 1L)
                    catalog.restoreItemPref(
                        sourceId, p.kind, key,
                        favorite = if (favorite) 1L else 0L,
                        favorite_at = if (cur?.favorite == 1L) cur.favorite_at else p.favorite_at,
                        hidden = maxOf(p.hidden, cur?.hidden ?: 0L),
                        locked = maxOf(p.locked, cur?.locked ?: 0L),
                    )
                }
            }
            if (prefs.isNotEmpty()) catalog.deleteItemPrefsForKey(sourceId, old)
            u.rekeyRecent(new.first(), sourceId, old)
            u.rekeyProgress("movie:$sourceId:${new.first()}", "movie:$sourceId:$old")
        }
        for (s in LAST_CHANNELS) {
            val key = u.getSetting(s).executeAsOneOrNull()?.takeIf { it.startsWith("$sourceId|") }?.substringAfter('|') ?: continue
            keys[key]?.let { u.putSetting(s, "$sourceId|${it.first()}") }
        }
    }

    private fun clearStaging(id: Long) = db.transaction {
        catalog.deleteAllCategories(-id)
        catalog.deleteChannels(-id)
        catalog.deleteMovies(-id)
        catalog.deleteSeries(-id)
    }

    // ---------------------------------------------------------------- M3U

    /** Returns old → new keys for items the user has data under, for [rekey]. */
    private suspend fun syncM3u(source: Source, progress: ProgressSink): Map<String, List<String>> {
        val staging = -source.id
        val pending = ArrayList<M3uEntry>(BATCH)
        val groups = HashMap<Pair<ContentKind, String>, Int>()
        var liveIndex = 0
        var vodIndex = 0
        var headerEpg: List<String> = emptyList()
        // Playlists often repeat an entry as a backup stream (same group, name and tvg-id);
        // later copies get "#2", "#3"… so every key stays unique and stable across syncs.
        val keyCounts = HashMap<String, Int>()
        // Keys used to be "group|name|tvg-id"; data saved under that form moves to the current key.
        val saved = userKeys(source.id)
        val rekeys = HashMap<String, MutableList<String>>()
        fun uniqueKey(e: M3uEntry, group: String): String {
            val base = m3uKey(e, group)
            val n = (keyCounts[base] ?: 0) + 1
            keyCounts[base] = n
            val key = if (n == 1) base else "$base#$n"
            val legacy = "$group|${e.name}|${e.tvgId.orEmpty()}"
            if (legacy in saved) rekeys.getOrPut(legacy) { ArrayList(1) }.add(key)
            return key
        }

        fun flush() {
            if (pending.isEmpty()) return
            db.transaction {
                for (e in pending) {
                    val kind = if (e.kind == ContentKind.LIVE) ContentKind.LIVE else ContentKind.MOVIE
                    val group = e.group.ifBlank { UNCATEGORIZED }
                    val gk = kind to group
                    if (gk !in groups) {
                        groups[gk] = groups.size
                        catalog.insertCategory(staging, kind.name, group, group, groups.size.toLong())
                    }
                    if (kind == ContentKind.LIVE) {
                        liveIndex++
                        val mode = CatchupUrlBuilder.modeOf(e.catchup)
                        catalog.insertChannel(
                            source_id = staging,
                            ckey = uniqueKey(e, group),
                            category_key = group,
                            name = e.name,
                            name_norm = searchNorm(e.name),
                            number = (e.number ?: liveIndex).toLong(),
                            logo = e.logo,
                            epg_id = e.tvgId,
                            url = e.url,
                            remote_id = null,
                            catchup_mode = mode.name,
                            catchup_days = if (mode == CatchupMode.NONE) 0 else (e.catchupDays ?: 7).toLong(),
                            catchup_source = e.catchupSource,
                            user_agent = e.userAgent ?: source.userAgent,
                            referrer = e.referrer,
                            position = liveIndex.toLong(),
                        )
                    } else {
                        vodIndex++
                        catalog.insertMovie(
                            source_id = staging,
                            ckey = uniqueKey(e, group),
                            category_key = group,
                            name = e.name,
                            name_norm = searchNorm(e.name),
                            poster = e.logo,
                            url = e.url,
                            remote_id = null,
                            rating = null,
                            year = null,
                            added_at = null,
                            position = vodIndex.toLong(),
                        )
                    }
                }
            }
            pending.clear()
            progress.report("Channels", liveIndex + vodIndex)
        }

        progress.report("Downloading playlist", 0)
        val parser = M3uParser(onHeader = { headerEpg = it.epgUrls }, onEntry = { pending.add(it) })
        http.streamText(source.url, source.userAgent, parser::feed) {
            if (pending.size >= BATCH) flush()
        }
        parser.finish()
        flush()
        if (liveIndex + vodIndex == 0) throw SyncException("The playlist has no entries")
        db.sourceQueries.setAutoEpg(headerEpg.joinToString("\n"), source.id)
        return rekeys
    }

    /**
     * Identity of an entry across syncs, which favorites, hidden and locked flags hang on. A
     * tvg-id is the provider's stable id, so it survives the channel being renamed or moved to
     * another group; without one, the group and name are all there is.
     */
    private fun m3uKey(e: M3uEntry, group: String) =
        e.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { "tvg:$it" } ?: "$group|${e.name}"

    // ---------------------------------------------------------------- Xtream

    private suspend fun syncXtream(source: Source, progress: ProgressSink) {
        val staging = -source.id
        val client = xtreamClient(source)
        progress.report("Signing in", 0)
        val account = client.authenticate()
        val status = account.status?.lowercase()
        if (status != null && status != "active") throw SyncException("Account is ${account.status}")
        val liveExt = when {
            preferHlsLive() && (account.outputFormats.isEmpty() || "m3u8" in account.outputFormats) -> "m3u8"
            account.outputFormats.isEmpty() || "ts" in account.outputFormats -> "ts"
            else -> account.outputFormats.first()
        }
        db.sourceQueries.updateAccount(account.resolvedTimezone(), account.expiresAt, account.maxConnections?.toLong(), liveExt, source.id)
        db.sourceQueries.setAutoEpg(client.xmltvUrl, source.id)

        progress.report("Downloading lineup", 0)
        val liveCats = client.categories(ContentKind.LIVE)
        val vodCats = client.categories(ContentKind.MOVIE)
        val seriesCats = client.categories(ContentKind.SERIES)

        fun cats(kind: ContentKind, list: List<app.tuner.core.xtream.XtreamCategory>, used: Set<String>) {
            db.transaction {
                list.forEachIndexed { i, c -> catalog.insertCategory(staging, kind.name, c.id, c.name, i.toLong()) }
                if (UNCATEGORIZED in used) catalog.insertCategory(staging, kind.name, UNCATEGORIZED, UNCATEGORIZED, list.size.toLong())
            }
        }
        // Streams without a category, or in one the panel doesn't list (deleted or hidden
        // there), go to "Uncategorized" so they still have a group.
        fun categoryKey(id: String?, known: Set<String>) = id?.takeIf { it in known } ?: UNCATEGORIZED
        val knownLive = liveCats.map { it.id }.toSet()
        val knownVod = vodCats.map { it.id }.toSet()
        val knownSeries = seriesCats.map { it.id }.toSet()

        // Catalogs are streamed one at a time and written in batches as they arrive,
        // so memory stays flat however large the provider is.
        val usedLive = HashSet<String>()
        val seenLive = HashSet<String>()
        var count = 0
        client.streams(ContentKind.LIVE) { batch ->
            db.transaction {
                for (s in batch) {
                    if (!seenLive.add(s.id)) continue
                    count++
                    val category = categoryKey(s.categoryId, knownLive)
                    usedLive += category
                    catalog.insertChannel(
                        source_id = staging,
                        ckey = s.id,
                        category_key = category,
                        name = s.name,
                        name_norm = searchNorm(s.name),
                        number = (s.num ?: count).toLong(),
                        logo = s.icon,
                        epg_id = s.epgChannelId,
                        url = client.streamUrl(ContentKind.LIVE, s.id, liveExt),
                        remote_id = s.id,
                        catchup_mode = if (s.hasArchive) CatchupMode.XTREAM.name else CatchupMode.NONE.name,
                        catchup_days = if (s.hasArchive) s.archiveDays.coerceAtLeast(1).toLong() else 0,
                        catchup_source = null,
                        user_agent = source.userAgent,
                        referrer = null,
                        position = count.toLong(),
                    )
                }
            }
            progress.report("Channels", count)
        }
        // A panel hiccup can answer 200 with "[]": keep the current lineup rather than wipe it.
        // (A first sync may be empty: some accounts only have movies and series.)
        if (count == 0 && catalog.channelCount(source.id).executeAsOne() > 0) throw SyncException("The provider returned no channels")
        cats(ContentKind.LIVE, liveCats, usedLive)

        // Movies and series are optional extras: a provider failing them must not
        // fail the live lineup. Whatever arrived before an error is kept.
        val usedVod = HashSet<String>()
        val seenVod = HashSet<String>()
        var vodCount = 0
        optional {
            client.streams(ContentKind.MOVIE) { batch ->
                db.transaction {
                    for (m in batch) {
                        if (!seenVod.add(m.id)) continue
                        vodCount++
                        val category = categoryKey(m.categoryId, knownVod)
                        usedVod += category
                        catalog.insertMovie(
                            source_id = staging,
                            ckey = m.id,
                            category_key = category,
                            name = m.name,
                            name_norm = searchNorm(m.name),
                            poster = m.icon,
                            url = client.streamUrl(ContentKind.MOVIE, m.id, m.containerExtension),
                            remote_id = m.id,
                            rating = m.rating,
                            year = null,
                            added_at = m.added,
                            position = vodCount.toLong(),
                        )
                    }
                }
                progress.report("Movies", vodCount)
            }
        }
        cats(ContentKind.MOVIE, vodCats, usedVod)

        val usedSeries = HashSet<String>()
        val seenSeries = HashSet<String>()
        var seriesCount = 0
        optional {
            client.series { batch ->
                db.transaction {
                    for (s in batch) {
                        if (!seenSeries.add(s.id)) continue
                        seriesCount++
                        val category = categoryKey(s.categoryId, knownSeries)
                        usedSeries += category
                        catalog.insertSeries(
                            source_id = staging,
                            ckey = s.id,
                            category_key = category,
                            name = s.name,
                            name_norm = searchNorm(s.name),
                            cover = s.cover,
                            remote_id = s.id,
                            plot = s.plot,
                            genre = s.genre,
                            rating = s.rating,
                            year = s.releaseDate?.take(4),
                            position = seriesCount.toLong(),
                        )
                    }
                }
                progress.report("Series", seriesCount)
            }
        }
        cats(ContentKind.SERIES, seriesCats, usedSeries)
    }

    private suspend fun optional(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep the partial catalog
        }
    }

    fun xtreamClient(source: Source) = XtreamClient(
        http,
        XtreamCredentials(source.url, source.username.orEmpty(), source.password.orEmpty()),
        source.userAgent,
    )

    companion object {
        const val BATCH = 2_000
        const val UNCATEGORIZED = "Uncategorized"
        private val LAST_CHANNELS = listOf(app.tuner.core.TunerCore.LAST_CHANNEL, app.tuner.core.TunerCore.PREVIOUS_CHANNEL)
    }
}

class SyncException(message: String) : Exception(message)

internal fun describe(e: Throwable): String = when (e) {
    is SyncException, is app.tuner.core.xtream.XtreamException, is app.tuner.core.io.DownloadException -> e.message ?: "Error"
    else -> {
        val raw = e.message ?: e::class.simpleName ?: "Unknown error"
        // Apple's networking errors arrive as an NSError dump (with the request URL in it);
        // keep only its sentence, e.g. "Could not connect to the server."
        if ("NSURLErrorDomain" in raw) nsErrorText.find(raw)?.groupValues?.get(1) ?: "Can't reach the server"
        else redact(raw)
    }
}

private val nsErrorText = Regex("""Code=-?\d+ "([^"]+)"""")
