package com.smpmedia.ui.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.image.LowPriorityTag
import kotlinx.coroutines.flow.sample
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ön yükleme için tek bir görsel hedefi. */
data class PreloadImage(
    val model: Any,
    val baseCacheKey: String
)

/**
 * NAS tarzı akıllı ön yükleme (read-ahead).
 *
 * Bir NAS'ın disk okuma stratejisi aynen burada uygulanır:
 *
 * 1. **Hız algısı (velocity):** Parmakla yavaş geziniyorsan depo sakin demektir;
 *    disk boşta olduğu için UZAK bölgeler önbelleğe alınır (4-6 ekran).
 *    Hızlı fling sırasında ise hedef "sonraki kareyi kaçırma"dır; okuma
 *    yalnızca hemen önümüzü kapsar (yarım ekran) — NAS'ın sequential I/O'da
 *    head'i gereksiz yere ileri geri taşımemesi gibi.
 * 2. **Yön sezisi (direction bias):** Hangi yöne gidiyorsan o tarafta pencere
 *    1.5 kat genişler, arkanda 0.5 katına küçülür. NAS'lar da sequential
 *    okumada sürüklenme yönünü önceliklendirir.
 * 3. **Akış kontrolü (throttle):** Kararlar ~100 ms'de bir alınır
 *    (`sample`); her karede kuyruk yeniden düzenlenmez, CPU boşuna yanmaz.
 * 4. **İptal (flow control):** Pencere dışına çıkan beklemedeki istekler
 *    `dispose()` edilir; bağlantı artık işe yaramayan indirmelerle dolmaz.
 *
 * [requests] listesi, LazyVerticalGrid'in item sırasıyla birebir eşleşmelidir.
 * Başlık (tarih/klasör) satırları için `null` koyulur.
 */
@Composable
fun PreloadGridImages(
    gridState: LazyGridState,
    requests: List<PreloadImage?>
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader

    LaunchedEffect(gridState, requests) {
        if (requests.isEmpty()) return@LaunchedEffect

        // Halihazırda kuyruğa girmiş ön yüklemeler: anahtar -> Disposable.
        val active = HashMap<String, Disposable>()

        // Hız ölçümü: son iki karar arasındaki indeks/zaman farkı.
        var lastIndex = -1
        var lastNanos = 0L

        try {
            snapshotFlow {
                val visible = gridState.layoutInfo.visibleItemsInfo
                Triple(
                    visible.firstOrNull()?.index ?: -1,
                    visible.lastOrNull()?.index ?: -1,
                    gridState.isScrollInProgress
                )
            }
                // ~100 ms'de bir karar ver (her frame değil) → CPU ve kuyruk tasarrufu.
                .sample(READAHEAD_SAMPLE_MS)
                .collect { (first, last, scrolling) ->
                    if (first < 0 || last < 0) return@collect

                    // ── Hız ve yön ──
                    val now = System.nanoTime()
                    val dtMs = if (lastNanos == 0L) 0L else (now - lastNanos) / 1_000_000L
                    val dIndex = if (lastIndex < 0) 0 else first - lastIndex
                    lastNanos = now
                    lastIndex = first
                    // saniyede kaydırılan item sayısı
                    val velocity = if (dtMs > 0 && dIndex != 0) {
                        abs(dIndex) * 1000L / dtMs
                    } else 0L

                    // ── Pencere derinliği: hızlıysak sığ, sakinken derin ──
                    val screens = when {
                        !scrolling -> IDLE_SCREENS          // durdu: en derin read-ahead
                        velocity > FAST_FLING_ITEM_PER_S -> 0.6f
                        velocity > FLING_ITEM_PER_S -> 1.0f
                        else -> SLOW_SCROLL_SCREENS          // yavaş sürükleme
                    }

                    // ── Yön biasesı: gittiğin yönde geniş, ardında dar ──
                    val forwardBias = if (dIndex >= 0) 1.5f else 0.5f
                    val backwardBias = if (dIndex >= 0) 0.5f else 1.5f

                    val visibleCount = (last - first + 1).coerceAtLeast(1)
                    val forward = (visibleCount * screens * forwardBias).roundToInt()
                    val backward = (visibleCount * screens * backwardBias).roundToInt()
                    val start = (first - backward).coerceAtLeast(0)
                    val end = (last + forward).coerceAtMost(requests.lastIndex)

                    // Bu pencerede istenen anahtarlar.
                    val wanted = HashSet<String>()
                    for (index in start..end) {
                        requests.getOrNull(index)?.let { wanted.add(it.baseCacheKey) }
                    }

                    // Pencere dışına çıkan istekleri iptal et (ağ tıkanmasın).
                    val iterator = active.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.key !in wanted) {
                            runCatching { entry.value.dispose() }
                            iterator.remove()
                        }
                    }

                    // Yeni istekleri kuyruğa al.
                    for (index in start..end) {
                        val target = requests.getOrNull(index) ?: continue
                        if (active.containsKey(target.baseCacheKey)) continue

                        val disposable = imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(target.model)
                                .memoryCacheKey(
                                    CacheKeyHelper.thumbnailMemoryKey(target.baseCacheKey)
                                )
                                // Izgarada disk önbelleği kapalı (tam boy kaynak
                                // kopyalanmasın; çöp/GC fırtınasını önler).
                                .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                                .size(GRID_THUMBNAIL_SIZE, GRID_THUMBNAIL_SIZE)
                                .precision(Precision.INEXACT)
                                .tag(LowPriorityTag)
                                .build()
                        )
                        active[target.baseCacheKey] = disposable
                    }
                }
        } finally {
            // Ekran kapanınca/bileşen terk edilince bekleyen istekleri bırak.
            active.values.forEach { runCatching { it.dispose() } }
            active.clear()
        }
    }
}

private const val READAHEAD_SAMPLE_MS = 100L

/** Durma hâlinde okunacak derinlik (ekran cinsinden) — en derin read-ahead. */
private const val IDLE_SCREENS = 4f

/** Yavaş parmak sürüklemesinde pencere derinliği. */
private const val SLOW_SCROLL_SCREENS = 2f

/** Saniyede bu kadar item geçerse "normal fling" sayılır; pencere 1 ekrana düşer. */
private const val FLING_ITEM_PER_S = 15L

/** Saniyede bu kadar item geçerse "hızlı fling"; yalnızca yarım ekran hazırlanır. */
private const val FAST_FLING_ITEM_PER_S = 40L
