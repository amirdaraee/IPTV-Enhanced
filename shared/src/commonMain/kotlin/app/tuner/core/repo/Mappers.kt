package app.tuner.core.repo

import app.tuner.core.model.CatchupMode
import app.tuner.core.model.Channel
import app.tuner.core.model.ContentKind
import app.tuner.core.model.Movie
import app.tuner.core.model.Programme
import app.tuner.core.model.Series
import app.tuner.core.model.Source
import app.tuner.core.model.SourceType
import app.tuner.core.model.WatchProgress
import app.tuner.core.util.splitUrls

// Positional mappers matching the generated SQLDelight query signatures, so every
// query that selects `ch.*, favorite, hidden, locked` can share one function.

@Suppress("UNUSED_PARAMETER")
internal fun mapChannel(
    id: Long, sourceId: Long, ckey: String, categoryKey: String, name: String, nameNorm: String,
    number: Long?, logo: String?, epgId: String?, epgKey: String?, url: String, remoteId: String?,
    catchupMode: String, catchupDays: Long, catchupSource: String?, userAgent: String?, referrer: String?,
    position: Long, favorite: Long, hidden: Long, locked: Long,
) = Channel(
    id = id, sourceId = sourceId, key = ckey, categoryKey = categoryKey, name = name,
    number = number?.toInt(), logo = logo, epgId = epgId, epgKey = epgKey, url = url, remoteId = remoteId,
    catchupMode = runCatching { CatchupMode.valueOf(catchupMode) }.getOrDefault(CatchupMode.NONE),
    catchupDays = catchupDays.toInt(), catchupSource = catchupSource, userAgent = userAgent, referrer = referrer,
    favorite = favorite != 0L, hidden = hidden != 0L, locked = locked != 0L,
)

@Suppress("UNUSED_PARAMETER")
internal fun mapMovie(
    id: Long, sourceId: Long, ckey: String, categoryKey: String, name: String, nameNorm: String,
    poster: String?, url: String, remoteId: String?, rating: Double?, year: String?, addedAt: Long?,
    position: Long, favorite: Long, locked: Long,
) = Movie(id, sourceId, ckey, categoryKey, name, poster, url, remoteId, rating, year, addedAt, favorite != 0L, locked != 0L)

@Suppress("UNUSED_PARAMETER")
internal fun mapSeries(
    id: Long, sourceId: Long, ckey: String, categoryKey: String, name: String, nameNorm: String,
    cover: String?, remoteId: String, plot: String?, genre: String?, rating: Double?, year: String?,
    position: Long, favorite: Long, locked: Long,
) = Series(id, sourceId, ckey, categoryKey, name, cover, remoteId, plot, genre, rating, year, favorite != 0L, locked != 0L)

@Suppress("UNUSED_PARAMETER")
internal fun mapProgramme(
    sourceId: Long, epgKey: String, start: Long, stop: Long, title: String,
    description: String?, category: String?, icon: String?, episode: String?, titleNorm: String?,
) = Programme(epgKey, start, stop, title, description, category, icon, episode)

@Suppress("UNUSED_PARAMETER")
internal fun mapSource(
    id: Long, name: String, type: String, url: String, username: String?, password: String?,
    epgUrls: String, userAgent: String?, epgOffsetMinutes: Long, refreshHours: Long, lastRefreshed: Long?,
    lastError: String?, enabled: Long, position: Long, serverTimezone: String?, expiresAt: Long?,
    maxConnections: Long?, outputFormat: String?, autoEpgUrls: String,
) = Source(
    id = id, name = name, type = enumOr(type, SourceType.M3U), url = url, username = username, password = password,
    epgUrls = splitUrls(epgUrls), autoEpgUrls = splitUrls(autoEpgUrls), userAgent = userAgent,
    epgOffsetMinutes = epgOffsetMinutes.toInt(), refreshHours = refreshHours.toInt(),
    lastRefreshed = lastRefreshed, lastError = lastError, enabled = enabled != 0L,
    expiresAt = expiresAt, maxConnections = maxConnections?.toInt(), serverTimezone = serverTimezone,
)

internal fun mapProgress(
    pkey: String, sourceId: Long, kind: String, title: String, subtitle: String?, image: String?, url: String,
    positionMs: Long, durationMs: Long, updatedAt: Long, parentKey: String?,
) = WatchProgress(
    pkey, sourceId, enumOr(kind, ContentKind.MOVIE), title, subtitle, image, url, positionMs, durationMs, updatedAt, parentKey,
)

internal fun Boolean.toLong() = if (this) 1L else 0L

/** Tolerates values written by a newer version (restored from its backup) instead of failing every read. */
internal inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: fallback
