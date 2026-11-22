package com.smpmedia.data.image

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.smpmedia.data.local.MissingPhotoDao
import com.smpmedia.data.local.MissingPhotoEntity
import com.smpmedia.data.model.ImageLoadState
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.MissingPhotoSource
import com.smpmedia.data.model.SmbImage
import com.smpmedia.di.IoDispatcher
import com.smpmedia.ui.components.GRID_THUMBNAIL_SIZE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Izgarada yüklenemeyen küçük resimleri takip eder ve düşük öncelikli bir
 * kuyrukla yeniden dener.
 *
 * Durum bilgisi hem bellekte (anlık ikonlar için) hem de Room'da
 * (uygulama kapalıyken WorkManager tamamlayabilsin diye) tutulur.
 */
@Singleton
class MissingPhotoRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: MissingPhotoDao,
    private val imageLoader: ImageLoader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val retryMutex = Mutex()

    private val _imageStates = MutableStateFlow<Map<String, ImageLoadState>>(emptyMap())
    val imageStates: StateFlow<Map<String, ImageLoadState>> = _imageStates.asStateFlow()

    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    private val _missingCount = MutableStateFlow(0)
    val missingCount: StateFlow<Int> = _missingCount.asStateFlow()

    private val _completed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val completed: SharedFlow<Unit> = _completed.asSharedFlow()

    /** Şu an kuyrukta olan anahtarlar; başarıda yalnızca bunlar silinir. */
    @Volatile
    private var trackedKeys: Set<String> = emptySet()

    init {
        // Room'daki kuyruğu izle: sayaç ve başlangıç durumları buradan gelir.
        scope.launch {
            dao.observeAll().collect { entities ->
                trackedKeys = entities.map { it.cacheKey }.toSet()
                _missingCount.value = entities.count { it.attempts < MAX_ATTEMPTS }
                _imageStates.update { current ->
                    val restored = entities
                        .filter { it.attempts < MAX_ATTEMPTS }
                        .filterNot { entity ->
                            val state = current[entity.cacheKey]
                            state == ImageLoadState.LOADED || state == ImageLoadState.PENDING
                        }
                        .associate { it.cacheKey to ImageLoadState.FAILED }
                    if (restored.isEmpty()) current else current + restored
                }
            }
        }
    }

    /**
     * Görünür alandaki istek başladı.
     *
     * BİLEREK BOŞ: Daha önce her istek için tüm harita kopyalanıyordu
     * (`it + (key to PENDING)`). Izgara 1000+ görsel içerdiğinde her küçük
     * resim, 1000 girişlik yeni bir HashMap tahsis ediyordu; kaydırma
     * sırasında bu, saniyeler içinde yüzlerce MB çöp ve 100+ ms'lik GC
     * duraklamaları (görünür takılma) üretiyordu. UI'da artık bekleme
     * rozeti gösterilmediği için PENDING durumunu izlemeye gerek yok.
     */
    fun reportLoading(cacheKey: String) {
        // No-op (yukarıdaki açıklamaya bakın).
    }

    /** Görsel başarıyla yüklendi. */
    fun reportLoaded(cacheKey: String) {
        if (cacheKey in trackedKeys) {
            scope.launch { dao.delete(cacheKey) }
        }
        // Daha önce hata olarak işaretlenmişse işareti kaldır (nadir, küçük map).
        if (_imageStates.value.containsKey(cacheKey)) {
            _imageStates.update { it - cacheKey }
        }
    }

    /** Görsel yüklenemedi; deneme sayısını artır ve kuyruğa yaz. */
    fun reportFailed(
        cacheKey: String,
        source: MissingPhotoSource,
        model: String,
        lastModified: Long = 0L,
        error: String? = null
    ) {
        _imageStates.update { it + (cacheKey to ImageLoadState.FAILED) }
        scope.launch {
            val previous = dao.getByKey(cacheKey)
            val attempts = (previous?.attempts ?: 0) + 1
            dao.upsert(
                MissingPhotoEntity(
                    cacheKey = cacheKey,
                    source = source.name,
                    model = model,
                    lastModified = lastModified,
                    attempts = attempts,
                    lastError = error?.take(MAX_ERROR_LENGTH),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Kuyruktaki eksikleri [maxParallel] eşzamanlı istekle tamamlar.
     * Tümü bittiğinde [completed] akışına tek bir olay bırakır.
     */
    suspend fun retryMissing(limit: Int = DEFAULT_LIMIT, maxParallel: Int = DEFAULT_PARALLEL): Boolean =
        retryMutex.withLock {
            if (_retrying.value) return false

            val queue = withContext(ioDispatcher) { dao.getRetryable(MAX_ATTEMPTS, limit) }
            if (queue.isEmpty()) return true

            _retrying.value = true
            try {
                val semaphore = Semaphore(maxParallel.coerceAtLeast(1))
                coroutineScope {
                    queue.forEach { entity ->
                        launch {
                            semaphore.acquire()
                            try {
                                retryOne(entity)
                            } finally {
                                semaphore.release()
                            }
                        }
                    }
                }

                val remaining = withContext(ioDispatcher) { dao.countRetryable(MAX_ATTEMPTS) }
                if (remaining == 0) {
                    _completed.tryEmit(Unit)
                }
                true
            } finally {
                _retrying.value = false
            }
        }

    private suspend fun retryOne(entity: MissingPhotoEntity) {
        val request = ImageRequest.Builder(context)
            .data(entity.toModel())
            .memoryCacheKey(CacheKeyHelper.thumbnailMemoryKey(entity.cacheKey))
            .diskCacheKey(entity.cacheKey)
            .size(GRID_THUMBNAIL_SIZE, GRID_THUMBNAIL_SIZE)
            .precision(Precision.INEXACT)
            // Coil 2.7'de yerleşik öncelik yok; düşük öncelik işareti.
            .tag(LowPriorityTag)
            .build()

        try {
            when (val result = imageLoader.execute(request)) {
                is SuccessResult -> reportLoaded(entity.cacheKey)
                is ErrorResult -> reportFailed(
                    cacheKey = entity.cacheKey,
                    source = entity.sourceType(),
                    model = entity.model,
                    lastModified = entity.lastModified,
                    error = result.throwable.message
                )

                else -> reportFailed(
                    cacheKey = entity.cacheKey,
                    source = entity.sourceType(),
                    model = entity.model,
                    lastModified = entity.lastModified,
                    error = "Bilinmeyen Coil sonucu"
                )
            }
        } catch (t: Throwable) {
            reportFailed(
                cacheKey = entity.cacheKey,
                source = entity.sourceType(),
                model = entity.model,
                lastModified = entity.lastModified,
                error = t.message
            )
        }
    }

    private fun MissingPhotoEntity.toModel(): Any = when (sourceType()) {
        MissingPhotoSource.LOCAL -> Uri.parse(model)
        MissingPhotoSource.SMB -> SmbImage(model, lastModified)
        // Nextcloud yedeği SMB ile AYNI yolu taşıyabilir; kaynak alanı
        // olmadan retry dosyayı yanlış sunucudan ister.
        MissingPhotoSource.NEXTCLOUD -> SmbImage(
            path = model,
            lastModified = lastModified,
            source = MediaSourceKind.NEXTCLOUD
        )
    }

    private fun MissingPhotoEntity.sourceType(): MissingPhotoSource =
        runCatching { MissingPhotoSource.valueOf(source) }
            .getOrDefault(MissingPhotoSource.LOCAL)

    companion object {
        /** Bir görsel en fazla 3 kez denenir; sonra kullanıcıya bırakılır. */
        const val MAX_ATTEMPTS = 3
        const val DEFAULT_LIMIT = 50
        const val DEFAULT_PARALLEL = 2
        private const val MAX_ERROR_LENGTH = 300
    }
}