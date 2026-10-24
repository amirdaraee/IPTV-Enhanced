package app.tuner.tv.util

import app.tuner.tv.core
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val fmt24 = SimpleDateFormat("HH:mm", Locale.getDefault())
private val fmt12 = SimpleDateFormat("h:mm a", Locale.getDefault())
private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

fun formatTime(millis: Long): String = (if (core.settings.use24h) fmt24 else fmt12).format(Date(millis))

fun formatRange(start: Long, stop: Long) = "${formatTime(start)} – ${formatTime(stop)}"

fun formatDay(millis: Long): String = dayFmt.format(Date(millis))

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMinutes(secs: Long): String {
    val m = secs / 60
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
