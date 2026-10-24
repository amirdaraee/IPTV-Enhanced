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

    suspend fun sync(source: Source, progress: ProgressSink) {
        clearStaging(source.id)
        try {
            when (source.type) {
                SourceType.M3U -> syncM3u(source, progress)
                SourceType.XTREAM -> syncXtream(source, progress)
            }
        } catch (e: Throwable) {
            clearStaging(source.id)
            throw e
        }
        progress.report("Saving", 0)
        db.transaction {
            val id = source.id
            catalog.deleteAllCategories(id); catalog.promoteCategories(id)
            catalog.deleteChannels(id); catalog.promoteChannels(id)
            catalog.deleteMovies(id); catalog.promoteMovies(id)
            catalog.deleteSeries(id); catalog.promoteSeries(id)
        }
    }

    private fun clearStaging(id: Long) = db.transaction {
        catalog.deleteAllCategories(-id)
        catalog.deleteChannels(-id)
        catalog.deleteMovies(-id)
        catalog.deleteSeries(-id)
    }

    // ---------------------------------------------------------------- M3U

    private suspend fun syncM3u(source: Source, progress: ProgressSink) {
        val staging = -source.id
        val pending = ArrayList<M3uEntry>(BATCH)
        val groups = HashMap<Pair<ContentKind, String>, Int>()
        var liveIndex = 0
        var vodIndex = 0
        var headerEpg: List<String> = emptyList()

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
                            ckey = m3uKey(e, group),
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
                            ckey = m3uKey(e, group),
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
    }

    private fun m3uKey(e: M3uEntry, group: String) = "$group|${e.name}|${e.tvgId.orEmpty()}"

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

        fun cats(kind: ContentKind, list: List<app.tuner.core.xtream.XtreamCategory>, used: Set<String?>) {
            db.transaction {
                list.forEachIndexed { i, c -> catalog.insertCategory(staging, kind.name, c.id, c.name, i.toLong()) }
                if (null in used || used.any { id -> list.none { it.id == id } }) {
                    catalog.insertCategory(staging, kind.name, UNCATEGORIZED, UNCATEGORIZED, list.size.toLong())
                }
            }
        }

        // Catalogs are streamed one at a time and written in batches as they arrive,
        // so memory stays flat however large the provider is.
        val usedLive = HashSet<String?>()
        var count = 0
        client.streams(ContentKind.LIVE) { batch ->
            db.transaction {
                for (s in batch) {
                    count++
                    usedLive += s.categoryId
                    catalog.insertChannel(
                        source_id = staging,
                        ckey = s.id,
                        category_key = s.categoryId ?: UNCATEGORIZED,
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
        cats(ContentKind.LIVE, liveCats, usedLive)

        // Movies and series are optional extras: a provider failing them must not
        // fail the live lineup. Whatever arrived before an error is kept.
        val usedVod = HashSet<String?>()
        var vodCount = 0
        optional {
            client.streams(ContentKind.MOVIE) { batch ->
                db.transaction {
                    for (m in batch) {
                        vodCount++
                        usedVod += m.categoryId
                        catalog.insertMovie(
                            source_id = staging,
                            ckey = m.id,
                            category_key = m.categoryId ?: UNCATEGORIZED,
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

        val usedSeries = HashSet<String?>()
        var seriesCount = 0
        optional {
            client.series { batch ->
                db.transaction {
                    for (s in batch) {
                        seriesCount++
                        usedSeries += s.categoryId
                        catalog.insertSeries(
                            source_id = staging,
                            ckey = s.id,
                            category_key = s.categoryId ?: UNCATEGORIZED,
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
    }
}

class SyncException(message: String) : Exception(message)

internal fun describe(e: Throwable): String = when (e) {
    is SyncException, is app.tuner.core.xtream.XtreamException, is app.tuner.core.io.DownloadException -> e.message ?: "Error"
    else -> redact(e.message ?: e::class.simpleName ?: "Unknown error")
}
