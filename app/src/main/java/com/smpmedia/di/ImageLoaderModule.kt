package com.smpmedia.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.smpmedia.data.image.MediaCache
import com.smpmedia.data.image.ImageMemoryGuard
import com.smpmedia.data.image.MediaThumbnailFetcher
import com.smpmedia.data.image.SmbImageFetcher
import com.smpmedia.data.image.ThumbnailCache
import com.smpmedia.data.net.NextcloudTls
import com.smpmedia.data.prefs.NextcloudSettingsStore
import com.smpmedia.data.repository.CloudPhotoStore
import com.smpmedia.data.repository.NextcloudRepository
import com.smpmedia.data.repository.SmbRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Coil'in bellek ve disk önbelleğini 1 TB'lık fotoğraf kütüphanesine göre
 * yapılandırır.
 *
 * Neden 1/4 (yüzde 25) bellek?
 * Android'de uygulama başına ayrılan heap sınırlıdır. Fotoğraf galerisi gibi
 * çok sayıda küçük resim gösteren uygulamalarda bitmap'lerin heap'i tamamen
 * doldurup OOM üretmesi engellenmelidir. maxMemory'nin 1/4'ü, 120 Hz'de
 * kaydırma sırasında bile akıcı bir pencere verir; kalan %75 Compose, Room,
 * SMB tamponları ve JVM'e yeterli yaşam alanı bırakır. 1/2 OOM risklidir,
 * 1/8 ise gereksiz düşüktür.
 *
 * Neden 30 GB disk + 10 GB WebP thumbnail önbelleği?
 * 1 TB ≈ 200.000 fotoğraf (5 MB ortalama). 30 GB Coil disk önbelleği ~6.000
 * tam boy fotoğrafı (viewer için) yerelde tutar. 10 GB `ThumbnailCache`
 * ayrıca ~200.000 EXIF tabanlı WebP küçük resmini (tanesi ~15-25 KB) saklar.
 * Böylece ızgara tekrar açıldığında küçük resimler ağa hiç çıkmadan diskt
 * yüklenir; yalnızca viewer'da tam boy dosya SMB'den çekilir.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    /**
     * maxMemory oranı.
     *
     * 1/4 (25%) = "sıfır sıkıntı" agresif ayar.
     * - 256 MB heap → 64 MB önbellek → ~250 küçük resim (256x256 ARGB_8888)
     * - 512 MB heap → 128 MB önbellek → ~500 küçük resim
     * - 1 GB heap → 256 MB önbellek → ~1000 küçük resim
     *
     * Kalan %75; Compose, Room, SMB tamponları ve JVM için yeterli yaşam
     * alanı bırakır. 1/2 (%50) OOM riski yaratır, 1/8 ise gereksiz düşüktür.
     */
    private const val BELLEK_ORANI = 0.25

    /**
     * Disk önbelleği: Coil'in tam boy kaynak dosya önbelleği.
     *
     * 1 TB disk hesabı:
     * - ~200.000 fotoğraf (5 MB ortalama) = 1 TB toplam
     * - 30 GB disk önbelleği = ~6.000 tam boy fotoğraf (viewer için)
     * - Kalan ~970 GB: ThumbnailCache (10 GB) + kullanıcı dosyaları
     */
    private const val DISK_ONBELLEK_BAYT = 30L * 1024L * 1024L * 1024L

    /**
     * Coil'in görsel getirme (fetcher) eşzamanlılığı.
     * SMB okumaları yavaş olduğu için görünür alanı hızlı doldurmak adına
     * 16 istek aynı anda yürütülür; aynı ana bilgisayara (SMB sunucusu)
     * giden eşzamanlı istek 8 ile sınırlanır.
     */
    private const val MAX_REQUESTS = 16
    private const val MAX_REQUESTS_PER_HOST = 8

    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        smbRepository: SmbRepository,
        nextcloudRepository: NextcloudRepository,
        nextcloudSettingsStore: NextcloudSettingsStore,
        thumbnailCache: ThumbnailCache,
        cloudPhotoStore: CloudPhotoStore
    ): ImageLoader = ImageLoader.Builder(context)
        // SMB üzerindeki dosyaları Coil'in kendi fetcher'ı ile oku.
        .components {
            // Cihaz fotoğraflarında MediaStore'un hazır küçük resmi:
            // tam boy dosya çözme/tahsisatını tamamen kaldırır (GC fırtınası).
            add(MediaThumbnailFetcher.Factory(context))
            // EXIF'ten öğrenilen gerçek çekim zamanı bulut listesine bildirilir
            // (NAS'ta bozulan dosya tarihleri zaman tüneline böylece yansımaz).
            // Model hangi kaynağı taşıyorsa (SMB/Nextcloud) fetcher oradan okur.
            add(
                SmbImageFetcher.Factory(smbRepository, nextcloudRepository, thumbnailCache) {
                    path, millis, source ->
                    cloudPhotoStore.reportCapturedDate(path, millis, source)
                }
            )
            // Video URI'lerinin ilk karesini çöz: ızgarada videolar da
            // gerçek görselle görünsün (Google Photos gibi).
            add(coil.decode.VideoFrameDecoder.Factory())
        }
        // Paralel indirme: OkHttp tarafı (uzak HTTP kaynakları) için 16/8.
        .okHttpClient {
            val builder = OkHttpClient.Builder()
                .dispatcher(
                    Dispatcher().apply {
                        maxRequests = MAX_REQUESTS
                        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
                    }
                )
            // Coil'in kendi OkHttp istemcisi (uzak HTTP/HTTPS kaynakları için).
            // Kullanıcının "kendi imzalı sertifikaya güven" tercihi HER
            // el sıkışmada yeniden okunur: ayarda değişiklik yapınca
            // uygulamanın yeniden başlatılması gerekmez.
            NextcloudTls.install(builder) { nextcloudSettingsStore.current().allowSelfSigned }
            builder.build()
        }
        // Coil kendi fetcher/decoder işlerinde IO'yu sınırlı paralellikte çalıştırır.
        .dispatcher(Dispatchers.IO.limitedParallelism(MAX_REQUESTS))
        .fetcherDispatcher(Dispatchers.IO.limitedParallelism(MAX_REQUESTS))
        // Bellek önbelleği: maxMemory / 8.
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(BELLEK_ORANI)
                .build()
        }
        // Disk önbelleği: KALICI depoda (Android/data/…/files/media_cache)
        // 30 GB. cacheDir bilinçli olarak kullanılmıyor: sistem orayı
        // depolama daraldığında sessizce boşalttığı için ısıtılmış önbellek
        // bir gecede yok olabiliyordu (bkz. MediaCache).
        .diskCache {
            DiskCache.Builder()
                .directory(MediaCache.imageCache(context))
                .maxSizeBytes(DISK_ONBELLEK_BAYT)
                .build()
        }
        .crossfade(150) // Kısa geçiş: daha canlı his (250ms yerine 150ms)
        .build()
        // Bellek sigortası OOM anında bu örneğin bellek önbelleğini
        // boşaltabilsin (bkz. ImageMemoryGuard).
        .also { ImageMemoryGuard.register(it) }
}
