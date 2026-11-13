package app.tuner.core

import app.tuner.core.db.TunerDatabase
import app.tuner.core.model.ContentKind
import app.tuner.core.model.SourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a platform keeps the backup copy of the user's own data: their playlists, favorites,
 * hidden/locked/reordered groups, watch progress, recents and settings. The catalog and guide
 * are not included; they re-sync from the provider.
 *
 * tvOS needs this because the system may purge the database (it has to live in Caches there);
 * Android writes it to a small file that Android's backup to Google can include.
 */
interface UserDataBackup {
    fun save(snapshot: String)
    fun load(): String?
}

/**
 * A copy of the user's data shared by all of their devices (iCloud on Apple platforms). Unlike
 * [UserDataBackup] it is written to by other devices too, so the core merges rather than overwrites.
 */
interface UserDataCloud {
    fun read(): String?

    /** False when the store refused the snapshot (too large), so the cloud copy is unchanged. */
    fun write(snapshot: String): Boolean

    fun delete()
}

/** Version 1 of the backup format. Source ids are the ids at export; restore maps them to new ones. */
@Serializable
internal data class UserSnapshot(
    val version: Int = 1,
    val sources: List<SourceSnap> = emptyList(),
    val items: List<ItemPrefSnap> = emptyList(),
    val groups: List<GroupPrefSnap> = emptyList(),
    val progress: List<ProgressSnap> = emptyList(),
    val recents: List<RecentSnap> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
internal data class SourceSnap(
    val id: Long, val name: String, val type: String, val url: String, val username: String?, val password: String?,
    val epgUrls: String, val userAgent: String?, val epgOffsetMinutes: Long, val refreshHours: Long,
    val enabled: Boolean, val position: Long,
)

@Serializable
internal data class ItemPrefSnap(
    val sourceId: Long, val kind: String, val key: String,
    val favorite: Boolean, val favoriteAt: Long, val hidden: Boolean, val locked: Boolean,
)

@Serializable
internal data class GroupPrefSnap(
    val sourceId: Long, val kind: String, val key: String, val hidden: Boolean, val locked: Boolean, val position: Long?,
)

@Serializable
internal data class ProgressSnap(
    val key: String, val sourceId: Long, val kind: String, val title: String, val subtitle: String?, val image: String?,
    val url: String, val positionMs: Long, val durationMs: Long, val updatedAt: Long, val parentKey: String?,
)

@Serializable
internal data class RecentSnap(val sourceId: Long, val key: String, val watchedAt: Long)

internal object UserData {
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(db: TunerDatabase): String = encode(snapshot(db))

    fun encode(snapshot: UserSnapshot): String = json.encodeToString(UserSnapshot.serializer(), snapshot)

    fun parse(text: String): UserSnapshot? = runCatching { json.decodeFromString(UserSnapshot.serializer(), text) }.getOrNull()

    /** A newer app version wrote this; merging could drop what this version can't read. */
    fun isFromNewerVersion(snapshot: UserSnapshot) = snapshot.version > VERSION

    fun snapshot(db: TunerDatabase): UserSnapshot {
        return UserSnapshot(
            sources = db.sourceQueries.selectAll().executeAsList().map {
                SourceSnap(
                    it.id, it.name, it.type, it.url, it.username, it.password, it.epg_urls, it.user_agent,
                    it.epg_offset_minutes, it.refresh_hours, it.enabled == 1L, it.position,
                )
            },
            items = db.catalogQueries.allItemPrefs().executeAsList().map {
                ItemPrefSnap(it.source_id, it.kind, it.ckey, it.favorite == 1L, it.favorite_at, it.hidden == 1L, it.locked == 1L)
            },
            groups = db.catalogQueries.allGroupPrefs().executeAsList().map {
                GroupPrefSnap(it.source_id, it.kind, it.ckey, it.hidden == 1L, it.locked == 1L, it.custom_position)
            },
            progress = db.userQueries.allProgress().executeAsList().map {
                ProgressSnap(
                    it.pkey, it.source_id, it.kind, it.title, it.subtitle, it.image, it.url,
                    it.position_ms, it.duration_ms, it.updated_at, it.parent_key,
                )
            },
            recents = db.userQueries.allRecents().executeAsList().map { RecentSnap(it.source_id, it.ckey, it.watched_at) },
            // The wrong-PIN lockout is transient state; restoring an older value must never shorten it.
            settings = db.userQueries.allSettings().executeAsList()
                .filter { it.skey != TunerCore.PIN_FAILURES && it.skey != TunerCore.PIN_LOCKED_UNTIL && it.skey != TunerCore.CLOUD_BASE }
                .associate { it.skey to it.value_ },
        )
    }

    /**
     * Restores [text] into an empty database. Returns the number of playlists restored, or 0
     * when the snapshot is unreadable or has none (a backup of a deliberately emptied app).
     */
    fun restore(db: TunerDatabase, text: String): Int {
        val snapshot = parse(text) ?: return 0
        // Types a newer version may have added are skipped: this version can't read them back.
        val sources = snapshot.sources.filter { s -> SourceType.entries.any { it.name == s.type } }
        if (sources.isEmpty()) return 0
        fun knownKind(k: String) = ContentKind.entries.any { it.name == k }
        db.transaction {
            val ids = HashMap<Long, Long>()
            for (s in sources) {
                db.sourceQueries.restore(
                    s.name, s.type, s.url, s.username, s.password, s.epgUrls, s.userAgent,
                    s.epgOffsetMinutes, s.refreshHours, if (s.enabled) 1L else 0L, s.position,
                )
                ids[s.id] = db.sourceQueries.lastInsertId().executeAsOne()
            }
            fun key(k: String?) = rekey(k, ids)
            for (i in snapshot.items) {
                val id = ids[i.sourceId]?.takeIf { knownKind(i.kind) } ?: continue
                db.catalogQueries.restoreItemPref(id, i.kind, i.key, if (i.favorite) 1L else 0L, i.favoriteAt, if (i.hidden) 1L else 0L, if (i.locked) 1L else 0L)
            }
            for (g in snapshot.groups) {
                val id = ids[g.sourceId]?.takeIf { knownKind(g.kind) } ?: continue
                db.catalogQueries.restoreGroupPref(id, g.kind, g.key, if (g.hidden) 1L else 0L, if (g.locked) 1L else 0L, g.position)
            }
            for (p in snapshot.progress) {
                val id = ids[p.sourceId]?.takeIf { knownKind(p.kind) } ?: continue
                db.userQueries.saveProgress(key(p.key)!!, id, p.kind, p.title, p.subtitle, p.image, p.url, p.positionMs, p.durationMs, p.updatedAt, key(p.parentKey))
            }
            for (r in snapshot.recents) {
                val id = ids[r.sourceId] ?: continue
                db.userQueries.touchRecent(id, r.key, r.watchedAt)
            }
            for ((k, v) in snapshot.settings) {
                // The last and previous channel are "<sourceId>|<key>": point them at the new id.
                val value = if (k == TunerCore.LAST_CHANNEL || k == TunerCore.PREVIOUS_CHANNEL) {
                    val id = v.substringBefore('|').toLongOrNull()?.let(ids::get) ?: continue
                    "$id|${v.substringAfter('|')}"
                } else v
                db.userQueries.putSetting(k, value)
            }
        }
        return sources.size
    }

    /** Progress keys embed the source id ("movie:<id>:<key>"), so they change along with it. */
    private fun rekey(key: String?, ids: Map<Long, Long>): String? = key?.let {
        val parts = it.split(':', limit = 3)
        val id = parts.getOrNull(1)?.toLongOrNull()?.let(ids::get)
        if (parts.size == 3 && id != null) "${parts[0]}:$id:${parts[2]}" else it
    }

    // ------------------------------------------------------------------ cloud merge

    /**
     * Database ids differ per device, so the cloud copy names a source by a hash of what it
     * connects to. The ordinal keeps two identical playlists apart.
     */
    private fun cloudIds(sources: List<SourceSnap>): Map<Long, Long> {
        val seen = HashMap<String, Int>()
        return sources.sortedWith(compareBy({ it.position }, { it.id })).associate { s ->
            val identity = "${s.type}|${s.url}|${s.username.orEmpty()}"
            val ordinal = seen[identity] ?: 0
            seen[identity] = ordinal + 1
            s.id to hash("$identity|$ordinal")
        }
    }

    /** FNV-1a: must give the same result on every device, for every future app version. */
    private fun hash(text: String): Long {
        var h = -3750763034362895579L
        for (b in text.encodeToByteArray()) h = (h xor (b.toLong() and 0xff)) * 1099511628211L
        return h and Long.MAX_VALUE
    }

    /** The device-independent, sorted form of [s]: equal data on two devices gives equal values. */
    fun portable(s: UserSnapshot): UserSnapshot {
        val ids = cloudIds(s.sources)
        return UserSnapshot(
            version = VERSION,
            sources = s.sources.map { it.copy(id = ids.getValue(it.id)) }.sortedBy { it.id },
            items = s.items.mapNotNull { i -> ids[i.sourceId]?.let { i.copy(sourceId = it) } }.sortedBy { itemKey(it).toString() },
            groups = s.groups.mapNotNull { g -> ids[g.sourceId]?.let { g.copy(sourceId = it) } }.sortedBy { groupKey(it).toString() },
            progress = s.progress.mapNotNull { p ->
                ids[p.sourceId]?.let { p.copy(key = rekey(p.key, ids)!!, sourceId = it, parentKey = rekey(p.parentKey, ids)) }
            }.sortedBy { it.key },
            recents = s.recents.mapNotNull { r -> ids[r.sourceId]?.let { r.copy(sourceId = it) } }.sortedBy { recentKey(it).toString() },
            // Per device: what is on this screen right now, and whether this device syncs at all.
            settings = s.settings.filterKeys { it != TunerCore.LAST_CHANNEL && it != TunerCore.PREVIOUS_CHANNEL && it != TunerCore.CLOUD_OFF }.toList().sortedBy { it.first }.toMap(),
        )
    }

    private fun itemKey(i: ItemPrefSnap) = Triple(i.sourceId, i.kind, i.key)
    private fun groupKey(g: GroupPrefSnap) = Triple(g.sourceId, g.kind, g.key)
    private fun recentKey(r: RecentSnap) = r.sourceId to r.key

    /**
     * Three-way merge against [base], the copy both sides last agreed on: whichever side changed
     * a record since then wins, which also tells a deletion from a record the other side never
     * had. With no base nothing can count as deleted, so the two sides are simply combined.
     * Watch history keeps the newest entry; for anything else the cloud, written first, wins.
     */
    fun merge(base: UserSnapshot?, local: UserSnapshot, remote: UserSnapshot): UserSnapshot {
        val sources = merge3(base?.sources, local.sources, remote.sources, { it.id }) { _, r -> r }
        val alive = sources.mapTo(HashSet()) { it.id }
        fun <T> cloudWins(l: T, r: T) = r
        return portableOrder(UserSnapshot(
            version = VERSION,
            sources = sources,
            items = merge3(base?.items, local.items, remote.items, ::itemKey, ::cloudWins).filter { it.sourceId in alive },
            groups = merge3(base?.groups, local.groups, remote.groups, ::groupKey, ::cloudWins).filter { it.sourceId in alive },
            progress = merge3(base?.progress, local.progress, remote.progress, { it.key }) { l, r -> if (l.updatedAt > r.updatedAt) l else r }
                .filter { it.sourceId in alive },
            recents = merge3(base?.recents, local.recents, remote.recents, ::recentKey) { l, r -> if (l.watchedAt > r.watchedAt) l else r }
                .filter { it.sourceId in alive },
            settings = merge3(base?.settings?.toList(), local.settings.toList(), remote.settings.toList(), { it.first }, ::cloudWins).toMap(),
        ))
    }

    private fun portableOrder(s: UserSnapshot) = s.copy(
        sources = s.sources.sortedBy { it.id },
        items = s.items.sortedBy { itemKey(it).toString() },
        groups = s.groups.sortedBy { groupKey(it).toString() },
        progress = s.progress.sortedBy { it.key },
        recents = s.recents.sortedBy { recentKey(it).toString() },
        settings = s.settings.toList().sortedBy { it.first }.toMap(),
    )

    private fun <T, K> merge3(base: List<T>?, local: List<T>, remote: List<T>, key: (T) -> K, conflict: (T, T) -> T): List<T> {
        val b = base.orEmpty().associateBy(key)
        val l = local.associateBy(key)
        val r = remote.associateBy(key)
        return (l.keys + r.keys).mapNotNull { k ->
            val (old, mine, theirs) = Triple(b[k], l[k], r[k])
            when {
                mine == theirs -> mine
                mine == old -> theirs
                theirs == old -> mine
                // Edited on one side, deleted on the other: keep the edit.
                mine == null -> theirs
                theirs == null -> mine
                else -> conflict(mine, theirs)
            }
        }
    }

    class Applied(val changes: Int, val addedSources: List<Long>, val removedSources: List<Long>)

    /** Makes the database match [merged], touching only records that differ from [raw] (its current content). */
    fun apply(db: TunerDatabase, raw: UserSnapshot, merged: UserSnapshot): Applied {
        val local = portable(raw)
        var changes = 0
        val toLocal = HashMap(cloudIds(raw.sources).entries.associate { it.value to it.key })
        val added = ArrayList<Long>()
        val removed = ArrayList<Long>()
        fun <T, K> diff(old: List<T>, new: List<T>, key: (T) -> K, mask: Int, remove: (T) -> Unit, put: (T) -> Unit) {
            val before = old.associateBy(key)
            val after = new.associateBy(key)
            for ((k, v) in before) if (k !in after) { remove(v); changes = changes or mask }
            for ((k, v) in after) if (before[k] != v) { put(v); changes = changes or mask }
        }
        db.transaction {
            val newIds = merged.sources.mapTo(HashSet()) { it.id }
            for (s in local.sources) if (s.id !in newIds) {
                val id = toLocal.getValue(s.id)
                deleteSourceRows(db, id)
                removed += id
                changes = Changes.ALL
            }
            val before = local.sources.associateBy { it.id }
            for (s in merged.sources) {
                val old = before[s.id]
                val enabled = if (s.enabled) 1L else 0L
                if (old == null) {
                    db.sourceQueries.restore(s.name, s.type, s.url, s.username, s.password, s.epgUrls, s.userAgent, s.epgOffsetMinutes, s.refreshHours, enabled, s.position)
                    val id = db.sourceQueries.lastInsertId().executeAsOne()
                    toLocal[s.id] = id
                    added += id
                    changes = Changes.ALL
                } else if (old != s) {
                    val id = toLocal.getValue(s.id)
                    db.sourceQueries.update(s.name, s.url, s.username, s.password, s.epgUrls, s.userAgent, s.epgOffsetMinutes, s.refreshHours, enabled, id)
                    db.sourceQueries.setPosition(s.position, id)
                    changes = changes or Changes.CATALOG
                }
            }
            // A removed source's rows went with it above.
            val gone = local.sources.mapTo(HashSet()) { it.id }.apply { removeAll(newIds) }
            fun id(cloudId: Long) = toLocal.getValue(cloudId)
            diff(local.items.filter { it.sourceId !in gone }, merged.items, ::itemKey, Changes.PREFS,
                { db.catalogQueries.deleteItemPref(id(it.sourceId), it.kind, it.key) },
                { db.catalogQueries.restoreItemPref(id(it.sourceId), it.kind, it.key, if (it.favorite) 1L else 0L, it.favoriteAt, if (it.hidden) 1L else 0L, if (it.locked) 1L else 0L) })
            diff(local.groups.filter { it.sourceId !in gone }, merged.groups, ::groupKey, Changes.PREFS,
                { db.catalogQueries.deleteGroupPref(id(it.sourceId), it.kind, it.key) },
                { db.catalogQueries.restoreGroupPref(id(it.sourceId), it.kind, it.key, if (it.hidden) 1L else 0L, if (it.locked) 1L else 0L, it.position) })
            // Back from the cloud's ids to this device's, which is what progress keys hold here.
            val back = toLocal.toMap()
            diff(local.progress.filter { it.sourceId !in gone }, merged.progress, { it.key }, Changes.PROGRESS,
                { db.userQueries.deleteProgress(rekey(it.key, back)!!) },
                { db.userQueries.saveProgress(rekey(it.key, back)!!, id(it.sourceId), it.kind, it.title, it.subtitle, it.image, it.url, it.positionMs, it.durationMs, it.updatedAt, rekey(it.parentKey, back)) })
            diff(local.recents.filter { it.sourceId !in gone }, merged.recents, ::recentKey, Changes.HISTORY,
                { db.userQueries.deleteRecent(id(it.sourceId), it.key) },
                { db.userQueries.touchRecent(id(it.sourceId), it.key, it.watchedAt) })
            diff(local.settings.toList(), merged.settings.toList(), { it.first }, Changes.SETTINGS,
                { db.userQueries.removeSetting(it.first) },
                { db.userQueries.putSetting(it.first, it.second) })
        }
        return Applied(changes, added, removed)
    }

    fun deleteSourceRows(db: TunerDatabase, id: Long) {
        db.catalogQueries.deleteSourcePrefs(id)
        db.epgQueries.deleteProgrammes(id)
        db.epgQueries.deleteEpgChannels(id)
        db.userQueries.deleteSourceUserData(id)
        db.sourceQueries.delete(id)
    }
}
