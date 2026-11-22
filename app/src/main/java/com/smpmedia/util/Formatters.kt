package com.smpmedia.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("tr", "TR"))
private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale("tr", "TR"))
private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale("tr", "TR"))

/** Ay başlığı: "Mart 2025". */
fun formatMonthLabel(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(monthFormatter)

/** Gün başlığı: Bugün / Dün / 12 Mart 2025. */
fun formatDayLabel(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "Bugün"
        today.minusDays(1) -> "Dün"
        else -> date.format(dayFormatter)
    }
}

/** Gruplama anahtarı olarak kullanılan gün başlangıcı (ms). */
fun dayStartMillis(epochMillis: Long): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(epochMillis)
        .atZone(zone)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}

fun formatDateTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return "-"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dateTimeFormatter)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${bytes} ${units[index]}" else "%.1f %s".format(value, units[index])
}
