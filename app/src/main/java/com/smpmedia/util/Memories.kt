package com.smpmedia.util

import android.content.Context
import com.smpmedia.ui.components.ViewerPhoto
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Bir "Anı" kartı.
 *
 * [id] kartın anahtarıdır; aynı başlık iki kez üretilse bile çakışmaz.
 */
data class MemoryItem(
    val id: String,
    val monthsAgo: Int,
    val title: String,
    val photos: List<ViewerPhoto>
)

/**
 * Google Photos "Anılar" üreteci — her zaman 8 karta tamamlamaya çalışır.
 *
 * Kaynaklar (öncelik sırasına göre):
 * 1. Aynı gün ("3 ay önce bugün")
 * 2. Aynı ay, farklı gün, yıl dönümleri ("2 yıl önce bu ay")
 * 3. Taze anılar: bu hafta / geçen ay / bu yıl
 * 4. Yıl dönümleri ("1 yıl önce")
 * 5. Doldurma: en dolu aylar ("Ekim 2026") — 8 kutuya tamamlamak için
 *
 * Aynı fotoğraf iki karta girmesin diye erken kartlar fotoğrafları sahiplenir;
 * son turlarda fotoğraf havuzu daralırsa tekrar kullanıma izin verilir.
 */
fun buildTodayMemories(
    photos: List<ViewerPhoto>,
    maxItems: Int = 8,
    minPhotos: Int = 2
): List<MemoryItem> {
    if (photos.isEmpty()) return emptyList()

    val today = Calendar.getInstance()
    val currentYear = today.get(Calendar.YEAR)
    val currentMonth = today.get(Calendar.MONTH)
    val currentDay = today.get(Calendar.DAY_OF_MONTH)
    val now = today.timeInMillis
    val dayStart = startOfDay(now)

    val calendar = Calendar.getInstance()
    val claimed = HashSet<String>()
    val result = mutableListOf<MemoryItem>()

    fun photoId(p: ViewerPhoto): String =
        p.baseCacheKey ?: (p.title + '|' + p.dateMillis)

    /** Fotoğraf seçer: önce sahiplenilmemişler, yetmezse tümü. */
    fun pick(candidates: List<ViewerPhoto>): List<ViewerPhoto> {
        val sorted = candidates.sortedByDescending { it.dateMillis }
        val fresh = sorted.filter { photoId(it) !in claimed }
        val chosen = if (fresh.size >= minPhotos) fresh else sorted
        return chosen.take(MAX_PHOTOS_PER_MEMORY)
    }

    fun addMemory(
        id: String,
        title: String,
        monthsAgo: Int,
        candidates: List<ViewerPhoto>,
        min: Int = minPhotos
    ) {
        if (result.size >= maxItems) return
        val chosen = pick(candidates)
        if (chosen.size < min) return
        chosen.forEach { claimed += photoId(it) }
        result += MemoryItem(id = id, monthsAgo = monthsAgo, title = title, photos = chosen)
    }

    val sameDay = LinkedHashMap<Int, MutableList<ViewerPhoto>>()
    val sameMonth = LinkedHashMap<Int, MutableList<ViewerPhoto>>()
    val byYear = LinkedHashMap<Int, MutableList<ViewerPhoto>>()
    val byMonth = LinkedHashMap<Long, MutableList<ViewerPhoto>>()

    for (photo in photos) {
        if (photo.dateMillis <= 0L) continue
        // Gelecekteki (yanlış saatli) kayıtları anılara katma.
        if (photo.dateMillis > now + TimeUnit.DAYS.toMillis(1)) continue

        calendar.timeInMillis = photo.dateMillis
        val monthsAgo = (currentYear - calendar.get(Calendar.YEAR)) * 12 +
            (currentMonth - calendar.get(Calendar.MONTH))

        // Doldurma için ay grupları (yaş sınırı yok).
        val monthKey = calendar.get(Calendar.YEAR) * 100L + calendar.get(Calendar.MONTH)
        byMonth.getOrPut(monthKey) { mutableListOf() }.add(photo)

        if (monthsAgo < 1 || monthsAgo > 240) continue

        if (calendar.get(Calendar.DAY_OF_MONTH) == currentDay) {
            sameDay.getOrPut(monthsAgo) { mutableListOf() }.add(photo)
        }
        if (calendar.get(Calendar.MONTH) == currentMonth) {
            sameMonth.getOrPut(monthsAgo) { mutableListOf() }.add(photo)
        }
        if (monthsAgo >= 12) {
            byYear.getOrPut(calendar.get(Calendar.YEAR)) { mutableListOf() }.add(photo)
        }
    }

    // ── 1) Aynı gün ──
    sameDay.entries.sortedBy { it.key }.forEach { (months, group) ->
        addMemory(
            id = "day_$months",
            title = memoryTitle(months, exactDay = true),
            monthsAgo = months,
            candidates = group
        )
    }

    // ── 2) Aynı ay (yıl dönümü) ──
    sameMonth.entries
        .filter { it.key >= 12 }
        .sortedBy { it.key }
        .forEach { (months, group) ->
            addMemory(
                id = "month_$months",
                title = memoryTitle(months, exactDay = false),
                monthsAgo = months,
                candidates = group
            )
        }

    // ── 3) Taze anılar ──
    val weekAgo = dayStart - TimeUnit.DAYS.toMillis(6)
    addMemory(
        id = "this_week",
        title = "Bu hafta",
        monthsAgo = 0,
        candidates = photos.filter { it.dateMillis in weekAgo..(now + TimeUnit.DAYS.toMillis(1)) }
    )

    val thisMonthStart = startOfMonth(currentYear, currentMonth)
    addMemory(
        id = "last_month",
        title = "Geçen ay",
        monthsAgo = 1,
        candidates = photos.filter {
            it.dateMillis in startOfMonth(currentYear, currentMonth - 1) until thisMonthStart
        }
    )

    addMemory(
        id = "this_year",
        title = "Bu yılın favorileri",
        monthsAgo = 0,
        candidates = photos.filter {
            it.dateMillis >= startOfMonth(currentYear, 0) && it.dateMillis < thisMonthStart
        }
    )

    // ── 4) Yıl dönümleri ──
    byYear.entries.sortedByDescending { it.key }.forEach { (year, group) ->
        val years = currentYear - year
        addMemory(
            id = "year_$year",
            title = if (years == 1) "1 yıl önce" else "$years yıl önce",
            monthsAgo = ((currentYear - year) * 12 + currentMonth).coerceAtLeast(1),
            candidates = group
        )
    }

    // ── 5) 8 kutuya tamamla: en dolu aylar ──
    if (result.size < maxItems) {
        byMonth.entries
            .sortedWith(
                compareByDescending<Map.Entry<Long, MutableList<ViewerPhoto>>> { it.value.size }
                    .thenByDescending { it.key }
            )
            .forEach { (monthKey, group) ->
                if (result.size >= maxItems) return@forEach
                val year = (monthKey / 100).toInt()
                val month = (monthKey % 100).toInt()
                addMemory(
                    id = "fill_$monthKey",
                    title = "${MONTH_NAMES[month]} $year",
                    monthsAgo = ((currentYear - year) * 12 + (currentMonth - month))
                        .coerceAtLeast(0),
                    candidates = group,
                    // Tek fotoğraflı ay bile kutu olsun (son çare doldurma).
                    min = 1
                )
            }
    }

    return result.take(maxItems)
}

/** "3 ay önce bugün" / "2 yıl önce bugün" / "2 yıl önce bu ay". */
private fun memoryTitle(months: Int, exactDay: Boolean): String {
    val suffix = if (exactDay) "bugün" else "bu ay"
    return if (months >= 12 && months % 12 == 0) {
        "${months / 12} yıl önce $suffix"
    } else {
        "$months ay önce $suffix"
    }
}

private const val MAX_PHOTOS_PER_MEMORY = 20

private val MONTH_NAMES = arrayOf(
    "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
    "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
)

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun startOfMonth(year: Int, monthIndex: Int): Long = Calendar.getInstance().apply {
    set(Calendar.YEAR, year)
    set(Calendar.MONTH, monthIndex)
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * Anılar "gün aşırı" yenilenir: iki gün içinde tekrar hesaplanmaz.
 * Sonuç SharedPreferences'ta saklanır, uygulama yeniden açılsa bile
 * aynı kartlar gösterilir (boşuna iş + kapak zıplaması olmaz).
 */
object MemoriesCache {
    private const val PREFS = "memories_cache"
    private const val KEY_DAY = "last_day"
    private const val KEY_DATA = "data"
    private const val REFRESH_EVERY_DAYS = 1L

    fun load(context: Context, photos: List<ViewerPhoto>): List<MemoryItem> {
        if (photos.isEmpty()) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = currentDayIndex()
        val last = prefs.getLong(KEY_DAY, Long.MIN_VALUE)

        if (last != Long.MIN_VALUE && today - last < REFRESH_EVERY_DAYS) {
            decode(prefs.getString(KEY_DATA, null), photos)?.let { return it }
        }

        val fresh = buildTodayMemories(photos, maxItems = TARGET_COUNT)
        prefs.edit()
            .putLong(KEY_DAY, today)
            .putString(KEY_DATA, encode(fresh))
            .apply()
        return fresh
    }

    /** Kullanıcı "yenile" isterse günü sıfırlar. */
    fun invalidate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_DAY).remove(KEY_DATA).apply()
    }

    private const val TARGET_COUNT = 8

    private fun currentDayIndex(): Long =
        System.currentTimeMillis() / TimeUnit.DAYS.toMillis(1)

    private fun encode(items: List<MemoryItem>): String = items.joinToString("\n") { memory ->
        val keys = memory.photos.mapNotNull { it.baseCacheKey ?: it.title }
            .joinToString("\u0001") { it.replace("|", " ") }
        "${memory.id}|${memory.monthsAgo}|${memory.title}|$keys"
    }

    private fun decode(data: String?, photos: List<ViewerPhoto>): List<MemoryItem>? {
        if (data.isNullOrBlank()) return null
        val byKey = photos.associateBy { it.baseCacheKey ?: it.title }
        val items = data.lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            val ids = parts[3].split('\u0001').filter { it.isNotBlank() }
            val resolved = ids.mapNotNull { byKey[it] }
            if (resolved.isEmpty()) return@mapNotNull null
            MemoryItem(
                id = parts[0],
                monthsAgo = parts[1].toIntOrNull() ?: 0,
                title = parts[2],
                photos = resolved
            )
        }.toList()
        return items.takeIf { it.isNotEmpty() }
    }
}
