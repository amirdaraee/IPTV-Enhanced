package app.tuner.core

import app.tuner.core.db.TunerDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a platform keeps the backup copy of the user's own data: their playlists, favorites,
 * hidden/locked/reordered groups, watch progress, recents and settings. The catalog and guide
 * are not included; they re-sync from the provider.
 *
 * tvOS needs this because the system may purge the database (it has to live in Caches there);
 * Android writes it to a small file that Android's backup to Google can include.
 * A future iCloud store would implement the same interface.
 */
interface UserDataBackup {
    fun save(snapshot: String)
    fun load(): String?
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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun export(db: TunerDatabase): String {
        val snapshot = UserSnapshot(
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
            settings = db.userQueries.allSettings().executeAsList().associate { it.skey to it.value_ },
        )
        return json.encodeToString(UserSnapshot.serializer(), snapshot)
    }

    /**
     * Restores [text] into an empty database. Returns the number of playlists restored, or 0
     * when the snapshot is unreadable or has none (a backup of a deliberately emptied app).
     */
    fun restore(db: TunerDatabase, text: String): Int {
        val snapshot = runCatching { json.decodeFromString(UserSnapshot.serializer(), text) }.getOrNull() ?: return 0
        if (snapshot.sources.isEmpty()) return 0
        db.transaction {
            val ids = HashMap<Long, Long>()
            for (s in snapshot.sources) {
                db.sourceQueries.restore(
                    s.name, s.type, s.url, s.username, s.password, s.epgUrls, s.userAgent,
                    s.epgOffsetMinutes, s.refreshHours, if (s.enabled) 1L else 0L, s.position,
                )
                ids[s.id] = db.sourceQueries.lastInsertId().executeAsOne()
            }
            // Progress keys embed the source id ("movie:<id>:<key>"); rewrite them too.
            fun key(k: String?) = k?.let {
                val parts = it.split(':', limit = 3)
                val id = parts.getOrNull(1)?.toLongOrNull()?.let(ids::get)
                if (parts.size == 3 && id != null) "${parts[0]}:$id:${parts[2]}" else it
            }
            for (i in snapshot.items) {
                val id = ids[i.sourceId] ?: continue
                db.catalogQueries.restoreItemPref(id, i.kind, i.key, if (i.favorite) 1L else 0L, i.favoriteAt, if (i.hidden) 1L else 0L, if (i.locked) 1L else 0L)
            }
            for (g in snapshot.groups) {
                val id = ids[g.sourceId] ?: continue
                db.catalogQueries.restoreGroupPref(id, g.kind, g.key, if (g.hidden) 1L else 0L, if (g.locked) 1L else 0L, g.position)
            }
            for (p in snapshot.progress) {
                val id = ids[p.sourceId] ?: continue
                db.userQueries.saveProgress(key(p.key)!!, id, p.kind, p.title, p.subtitle, p.image, p.url, p.positionMs, p.durationMs, p.updatedAt, key(p.parentKey))
            }
            for (r in snapshot.recents) {
                val id = ids[r.sourceId] ?: continue
                db.userQueries.touchRecent(id, r.key, r.watchedAt)
            }
            for ((k, v) in snapshot.settings) db.userQueries.putSetting(k, v)
        }
        return snapshot.sources.size
    }
}
