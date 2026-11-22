package com.smpmedia.data.warm

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.ErrorResult
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.image.LowPriorityTag
import com.smpmedia.data.image.SmbImageFetcher
import com.smpmedia.data.image.ThumbnailCache
import com.smpmedia.data.model.RemotePhoto
import com.smpmedia.data.model.toCoilModel
import com.smpmedia.data.repository.CloudPhotoStore
import com.smpmedia.data.repository.ThrottledException
import com.smpmedia.ui.components.GRID_THUMBNAIL_SIZE
import coil.size.Precision
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Izgara ısıtma: zaman tünelindeki fotoğrafların küçük resimlerini KULLANICI
 * GELMEDEN önce sırayla indirir ve kalıcı WebP önbelleğine ([ThumbnailCache])
 * yazar. Kullanıcı kaydırdığında her hücre diskten gelir — asıl şikâyet
 * ("fotoğraflar tek tek geç açılıyor, yan çıkıyor") ancak böyle çözülür:
 * ızgaranın hızlı olması sunucunun hızıyla değil, önbelleğin doluluğuyla ilgilidir.
 *
 * ## Neden Coil isteği atarak (fetcher'ı tekrar kullanarak)?
 * [com.smpmedia.data.image.SmbImageFetcher] küçük resmi İNDİRİR, DÖNDÜRÜR
 * (EXIF/tkhd) ve WebP olarak ÖNBELLEĞE YAZAR. Isıtıcının kendi başına indirme
 * yazması ayrı bir yol yaratırdı — yarın birinde o yol fetcher'la çelişirdi
 * ("ızgarada yan, açınca dik" regresyonunun tekrar etmemesi için TEK üretim
 * yolu şart).
 *
 * ## Sunucuya saygı (Nextcloud ölçümleriyle zorunlu)
 * - TEK SERİ döngü: aynı anda tek istek (fetcher'ın kendi kapıları bunu zaten
 *   garanti eder; döngü ayrıca dosyalar arasında [GAP_MS] bekler).
 * - ThrottledException/ardışık hata → uzun geri çekilme, 5 hatada durma.
 *   Isıtma "bir daha ki açılışta kaldığı yerden devam eder" tasarımıdır;
 *   sunucuyu zorlamaktansa yarım kalması evladır.
 *
 * ## Kalıcılık
 * İşlenen dosyalar kaynak BAZLI anahtarla diske yazılır (SMB/Nextcloud aynı
 * yolu taşıyabilir). Uygulama kapansa da sayaç sıfırlanmaz; 4.844 fotoğraflık
 * Nextcloud arşivi birkaç oturuşta tamamen ısınır.
 */
@Singleton
class GridWarmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageLoader: ImageLoader,
    private val thumbnailCache: ThumbnailCache,
    private val cloudPhotoStore: CloudPhotoStore
) {
    /** null = ısıtma yok. Ayarlar ekranı ilerleme çubuğunu buna bağlar. */
    data class Progress(
        val done: Int,
        val total: Int,
        val downloaded: Int,
        val currentName: String,
        val running: Boolean,
        val message: String? = null
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "Isıtma açık" tercihi kaynak başına tutulur (kullanıcı NC'yi istedi). */
    fun isAutoEnabled(): Boolean =
        prefs.getBoolean(KEY_AUTO + cloudPhotoStore.activeSource().keyPrefix, false)

    fun setAutoEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO + cloudPhotoStore.activeSource().keyPrefix, enabled)
            .apply()
    }

    fun isRunning(): Boolean = job?.isActive == true

    /** Zaten koşuyorsa dokunmaz; ayar açılınca ve tarama bitince çağrılır. */
    fun startIfAuto() {
        if (isRunning()) return
        if (isAutoEnabled()) start()
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            runCatching { warmCycle() }
                .onFailure { Log.w("SmpMedia", "Izgara ısıtma yarıda kesildi: $it") }
        }
    }

    /** Kullanıcı "Durdur"a bastı veya ayarı kapattı; ilerleme KAYBOLMAZ. */
    fun stop() {
        job?.cancel()
        job = null
        _progress.update { it?.copy(running = false) }
    }

    private suspend fun warmCycle() {
        val snapshot = cloudPhotoStore.state.value
        if (snapshot.loading || snapshot.photos.isEmpty()) return
        val source = snapshot.source
        val doneKey = source.keyPrefix + KEY_DONE

        // ZAMAN TÜNELİ sırasıyla ısınır: en yeni fotoğraf en görülen fotoğraftır;
        // ısıtma yarıda kalsa bile kullanıcı en çok bakacağı uçtan başlar.
        val finished = prefs.getStringSet(doneKey, emptySet()) ?: emptySet()
        val targets = snapshot.photos
            .filter { !it.isVideo && it.path !in finished }
            .sortedByDescending { it.capturedMillis ?: it.lastModified }

        if (targets.isEmpty()) {
            _progress.value = Progress(0, 0, 0, "", running = false, message = "Izgara zaten ısınmış.")
            return
        }

        val total = targets.size
        var done = 0
        var downloaded = 0
        var consecutiveErrors = 0
        var settled = ArrayList<String>()
        _progress.value = Progress(0, total, 0, "", running = true)

        for (photo in targets) {
            // Durdurma delay() üzerinden CancellationException olarak gelir;
            // buradaki kontrol yalnızca iptal bildirimini hızlandırır.
            if (job?.isCancelled == true) break

            val cacheKey = SmbImageFetcher.THUMB_VARIANT +
                CacheKeyHelper.smbKey(photo.path, photo.lastModified, source)

            // Zaten diskte olan küçük resim için ağa çıkılmaz — ısıtma
            // yeniden başladığında geçen turlar bedavaya geçer.
            // VARLIK sorgusu kullanılır (get değil): get() WebP'yi ÇÖZER ve
            // sonucu atardı; binlerce dosyada boşuna bellek + GC fırtınası.
            if (thumbnailCache.exists(cacheKey)) {
                markDone(settled, photo, doneKey)
                done++
                continue
            }

            _progress.update { it?.copy(done = done, downloaded = downloaded, currentName = photo.displayName) }

            val request = ImageRequest.Builder(context)
                .data(photo.toCoilModel())
                .size(GRID_THUMBNAIL_SIZE, GRID_THUMBNAIL_SIZE)
                .precision(Precision.INEXACT)
                .memoryCacheKey(CacheKeyHelper.thumbnailMemoryKey(cacheKey))
                // Isıtmanın amacı DİSK önbelleği. Bellek önbelleğine de
                // yazsaydık, kullanıcının hiç görmediği binlerce küçük resim
                // heap'i doldurup ızgarayı OOM'a sürüklerdi.
                .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                .diskCacheKey(cacheKey)
                // Düşük öncelik etiketi: kullanıcı kaydırırsa ısıtma isteği
                // fetcher'ın arka plan kuyruğunda bekler, görünür hücreyi
                // asla sıkıştırmaz.
                .tag(LowPriorityTag)
                .build()

            val outcome = runCatching { imageLoader.execute(request) }
            val failure = outcome.exceptionOrNull()
                ?: (outcome.getOrNull() as? ErrorResult)?.throwable

            if (failure == null) {
                downloaded++
                consecutiveErrors = 0
                markDone(settled, photo, doneKey)
                done++
            } else {
                consecutiveErrors++
                val throttled = failure is ThrottledException
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    _progress.value = Progress(
                        done, total, downloaded, photo.displayName,
                        running = false,
                        message = "Sunucuya ulaşılamıyor — ısıtma durduruldu, " +
                            "kaldığı yerden devam edebilir."
                    )
                    flush(settled, doneKey)
                    return
                }
                // ThrottledException 412/429/502 demektir: sunucu "vazgeç"
                // diyor. Kısa hata geri çekilmesinden UZUN beklenir.
                delay(if (throttled) THROTTLE_BACKOFF_MS else ERROR_BACKOFF_MS)
                continue
            }

            if (done % FLUSH_EVERY == 0) flush(settled, doneKey)
            if (done % PROGRESS_EVERY == 0) {
                _progress.update { it?.copy(done = done, downloaded = downloaded) }
            }
            // Dosyalar arası soluklanma: tek istek bile olsa PHP'ye iki
            // işlem arasında nefes payı bırakılır.
            delay(GAP_MS)
        }

        flush(settled, doneKey)
        _progress.update {
            it?.copy(
                done = done,
                downloaded = downloaded,
                currentName = "",
                running = false,
                message = if (done >= total) "Izgara ısındı — $downloaded küçük resim indirildi." else null
            )
        }
        Log.d("SmpMedia", "Izgara ısıtma turu bitti: $done/$total (indirilen $downloaded)")
    }

    private fun markDone(settled: ArrayList<String>, photo: RemotePhoto, doneKey: String) {
        settled += photo.path
        if (settled.size >= FLUSH_EVERY) flush(settled, doneKey)
    }

    /** Kalıcı "işlendi" defteri; her dosyada değil, toplu yazılır (I/O patlaması yok). */
    private fun flush(settled: ArrayList<String>, doneKey: String) {
        if (settled.isEmpty()) return
        val current = prefs.getStringSet(doneKey, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(doneKey, current + settled).apply()
        settled.clear()
    }

    private companion object {
        const val FILE = "grid_warm"
        const val KEY_AUTO = "auto_"
        const val KEY_DONE = "done"

        /** İki dosya arası nefes (QoS). */
        const val GAP_MS = 350L

        /** Bilinmeyen hatada bekleme (bozuk dosyalar arasında hızla geçmek için kısa). */
        const val ERROR_BACKOFF_MS = 2_000L

        /** 412/429/502: sunucuya süre tanınır. */
        const val THROTTLE_BACKOFF_MS = 30_000L

        /** Üst üste bu kadar hata → tur biter (ağ yok, sunucu kapalı...). */
        const val MAX_CONSECUTIVE_ERRORS = 5

        const val FLUSH_EVERY = 25
        const val PROGRESS_EVERY = 5
    }
}
