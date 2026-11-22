package com.smpmedia.util

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Dosya ADINDAN çekim tarihi çıkarmaya çalışır.
 *
 * Neden gerekli: kullanıcının NAS'ında toplu bir işlem, binlerce fotoğrafın
 * değiştirme tarihini AYNI güne (ör. 18.10.2026 07:27) bozdu. Oysa telefon
 * ve Google Photos dışa aktarımları çekim tarihini dosya adına yazar:
 * - "1534579012000<hash>.jpg" → 13 haneli milisaniye epoch'u (Google)
 * - "IMG_20220115_103012.jpg", "PXL_...", "VID_..." → YYYYMMDD_HHMMSS
 * - "Screenshot_20220115-103012.png" → YYYYMMDD-HHMMSS
 * - "IMG-20260406-WA0007.jpg" → YYYYMMDD (saat bilgisi yok)
 *
 * Bulunamazsa null döner; çağıran taraf sunucu tarihine düşer.
 */
object FileNameDate {

    private val leadingDigits = Regex("^(\\d{10,})")

    /** YYYYMMDD ve opsiyonel HHMMSS/HH-MM-SS bölümleri. Tire her sınıfta BAŞTA. */
    private val ymd = Regex(
        "(19\\d{2}|20\\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\\d|3[01])" +
            "(?:[-_.](\\d{2})[-:.]?(\\d{2})(?:[-:.]\\d{2})?)?"
    )

    /** Alt sınır: 01.01.2000 — hash/ad çöplerinin tarih sanılmasını engeller. */
    private const val MIN_MILLIS = 946_684_800_000L

    private fun maxMillis(): Long = System.currentTimeMillis() + 2L * 86_400_000L

    /** Dosya adından güvenilir bir çekim zamanı çıkarırsa epoch ms. */
    fun parse(name: String): Long? {
        // 1) Sonda/başta epoch: "1534579012000<hash>" (13 hane ms, 10 hane sn).
        leadingDigits.find(name)?.groupValues?.get(1)?.let { digits ->
            val millis = when {
                digits.length >= 13 -> digits.substring(0, 13).toLongOrNull()
                digits.length >= 10 -> digits.substring(0, 10).toLongOrNull()?.times(1000L)
                else -> null
            }
            if (millis != null && millis in MIN_MILLIS..maxMillis()) return millis
        }

        // 2) YYYYMMDD [+ HHMMSS] deseni.
        val match = ymd.find(name) ?: return null
        val g = match.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        // Saat yoksa öğle vakti: yerel saat dilimi fotoğrafı yanlışlıkla
        // bir önceki/sonraki güne kaydıramaz.
        val hour = g.getOrNull(4)?.toIntOrNull() ?: 12
        val minute = g.getOrNull(5)?.toIntOrNull() ?: 0
        if (hour > 23 || minute > 59) return parseFromParts(year, month, day)

        val millis = parseFromParts(year, month, day, hour, minute) ?: return null
        return if (millis in MIN_MILLIS..maxMillis()) millis else null
    }

    private fun parseFromParts(
        year: Int, month: Int, day: Int, hour: Int = 12, minute: Int = 0
    ): Long? = runCatching {
        GregorianCalendar(year, month - 1, day, hour, minute, 0).apply {
            isLenient = false
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }.getOrNull()
}
