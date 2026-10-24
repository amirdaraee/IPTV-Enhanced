package app.tuner.core.xtream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Xtream panels are wildly inconsistent: numbers arrive as strings, strings as
// numbers, empty objects as [] and missing values as null, "" or "null".
// These accessors normalise all of that.

internal fun JsonObject.str(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    val c = p.content.trim()
    return if (c.isEmpty() || c == "null") null else c
}

internal fun JsonObject.long(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

internal fun JsonObject.int(key: String): Int? = long(key)?.toInt()

internal fun JsonObject.double(key: String): Double? = str(key)?.replace(',', '.')?.toDoubleOrNull()

internal fun JsonObject.bool(key: String): Boolean = when (str(key)?.lowercase()) {
    "1", "true", "yes" -> true
    else -> false
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.asObjects(): List<JsonObject> = when (this) {
    is JsonArray -> filterIsInstance<JsonObject>()
    is JsonObject -> values.filterIsInstance<JsonObject>()
    else -> emptyList()
}

/** First non-empty string from a value that may be a string or an array of strings. */
internal fun JsonObject.firstString(key: String): String? = when (val v = this[key]) {
    is JsonArray -> v.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> str(key)
    else -> null
}
