package app.tuner.core.sync

import app.tuner.core.db.TunerDatabase
import app.tuner.core.io.streamText
import app.tuner.core.model.Programme
import app.tuner.core.model.Source
import app.tuner.core.util.epgNorm
import app.tuner.core.util.searchNorm
import app.tuner.core.xmltv.XmltvChannel
import app.tuner.core.xmltv.XmltvParser
import io.ktor.client.HttpClient

/**
 * Imports XMLTV guides for a source. Programmes outside the retention window are
 * dropped while parsing, rows are staged under the negated source id in batches
 * and swapped in atomically, and channels are then matched to guide ids.
 */
internal class EpgImporter(private val db: TunerDatabase, private val http: HttpClient) {

    /** Returns the number of programmes imported, or throws if every URL failed. */
    suspend fun import(source: Source, from: Long, to: Long, progress: ProgressSink): Int {
        val urls = (source.epgUrls + source.autoEpgUrls).distinct()
        val epg = db.epgQueries
        val staging = -source.id
        db.transaction { epg.deleteProgrammes(staging); epg.deleteEpgChannels(staging) }
        if (urls.isEmpty()) {
            matchChannels(source.id)
            return 0
        }

        val offset = source.epgOffsetMinutes * 60_000L
        val programmes = ArrayList<Programme>(BATCH)
        val channels = ArrayList<XmltvChannel>()
        var total = 0
        var failures = 0
        var lastError: Throwable? = null

        fun flush() {
            if (programmes.isEmpty() && channels.isEmpty()) return
            db.transaction {
                for (c in channels) {
                    val key = c.id.lowercase()
                    epg.insertEpgChannel(staging, key, epgNorm(c.id))
                    for (n in c.displayNames) epg.insertEpgChannel(staging, key, epgNorm(n))
                }
                for (p in programmes) {
                    epg.insertProgramme(
                        staging, p.channelEpgId.lowercase(), p.start + offset, p.stop + offset,
                        p.title, p.description, p.category, p.icon, p.episodeNum, searchNorm(p.title),
                    )
                }
            }
            total += programmes.size
            programmes.clear()
            channels.clear()
            progress.report("Guide", total)
        }

        for (url in urls) {
            try {
                val parser = XmltvParser(
                    onChannel = { channels.add(it) },
                    onProgramme = { p ->
                        val start = p.start + offset
                        val stop = p.stop + offset
                        if (stop > from && start < to) programmes.add(p)
                    },
                )
                http.streamText(url, source.userAgent, parser::feed) {
                    if (programmes.size >= BATCH) flush()
                }
                flush()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failures++
                lastError = e
                programmes.clear()
                channels.clear()
            }
        }
        if (failures == urls.size && lastError != null) {
            db.transaction { epg.deleteProgrammes(staging); epg.deleteEpgChannels(staging) }
            throw lastError
        }
        progress.report("Saving guide", total)
        db.transaction {
            // Deleted during the import: drop the staged guide instead of orphaning it.
            if (db.sourceQueries.selectById(source.id).executeAsOneOrNull() == null) {
                epg.deleteProgrammes(staging); epg.deleteEpgChannels(staging)
                return@transaction
            }
            epg.deleteProgrammes(source.id); epg.promoteProgrammes(source.id)
            epg.deleteEpgChannels(source.id); epg.promoteEpgChannels(source.id)
        }
        matchChannels(source.id)
        return total
    }

    /** Resolves channel.epg_key: tvg-id match first, then normalised name match. */
    fun matchChannels(sourceId: Long) {
        val epg = db.epgQueries
        val byName = HashMap<String, String>()
        val ids = HashSet<String>()
        epg.allEpgChannels().executeAsList().forEach { row ->
            ids.add(row.epg_key)
            if (row.name_norm.isNotEmpty()) byName.getOrPut(row.name_norm) { row.epg_key }
        }
        epg.distinctProgrammeKeys().executeAsList().forEach { ids.add(it) }
        val refs = db.catalogQueries.channelEpgRefs(sourceId).executeAsList()
        db.transaction {
            for (ref in refs) {
                val id = ref.epg_id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                val key = when {
                    id != null && id in ids -> id
                    else -> byName[epgNorm(ref.name)] ?: id?.let { byName[epgNorm(it)] } ?: id
                }
                db.catalogQueries.setChannelEpgKey(key, ref.id)
            }
        }
    }

    companion object {
        const val BATCH = 5_000
    }
}
