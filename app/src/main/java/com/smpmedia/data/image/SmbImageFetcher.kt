package com.smpmedia.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.util.Log
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.SmbImage
import com.smpmedia.data.repository.NextcloudRepository
import com.smpmedia.data.repository.SmbRepository
import com.smpmedia.data.repository.ThrottledException
import com.smpmedia.util.Mp4Rotation
import jcifs.smb.SmbFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okio.ForwardingSource
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Coil'in SMB üzerindeki bir dosyayı okumasını sağlar.
 *
 * Hızlandırma stratejisi (katman katman):
 * 1. **Yerel thumbnail önbelleği** → milisaniyeler (0 KB ağ) — QoS kuyruğuna bile girmez
 * 2. **İstek birleştirme (coalescing)** → aynı dosyayı isteyen ön yükleme +
 *    görünür istek tek indirme yapar; ikinci istek sonucu bedavaya alır (NAS'ların
 *    istek merge'i gibi)
 * 3. **EXIF gömülü küçük resim** → ~100ms (256 KB ağ) — JPEG'lerin %95'i
 *    dosya başında tam çözünürlüklü küçük bir önizleme taşır.
 * 4. **Tam dosya decode** → ~500ms (5 MB ağ) — EXIF olmayan formatlar
 * 5. **Paylaşılan SMB oturumu** → NTLM el sıkışması 1 kez
 *
 * **QoS (hizmet kalitesi) zamanlayıcısı:** Bir NAS, ön okuma (read-ahead)
 * trafiğinin kullanıcıya anlık sunulan istekleri asla bloklamamasını
 * garanti eder. Burada aynı ilke iki ayrı semaförle uygulanır:
 * - `visibleGate` (8 izin): kullanıcının beklediği görünü r alan/görüntüleyici
 *   istekleri.
 * - `prefetchGate` (3 izin): [LowPriorityTag] etiketli arka plan istekleri.
 * Görünür bir istek, ön yükleme dolu olsa bile ASLA beklemez; en kötü
 * durumda kendi kuyruğundaki diğer görünür isteği bekler.
 *
 * **Nextcloud ([MediaSourceKind.NEXTCLOUD]) yolu:** aynı katmanlar WebDAV
 * üzerinden tekrarlanır (thumbnail önbelleği → birleştirme → tam dosya
 * decode). Ek kural: sunucu kırılgandır (PHP + brute-force koruması), bu
 * yüzden 412/429/502 gören istek ANINDA yeniden denenmez; uzun geri
 * çekilerek en fazla iki kez denenir. Asıl serileştirme
 * [NextcloudRepository] içindeki tek izinli semaforun işidir.
 */
class SmbImageFetcher(
    private val data: SmbImage,
    private val options: Options,
    private val repository: SmbRepository,
    private val nextcloudRepository: NextcloudRepository,
    private val thumbnailCache: ThumbnailCache,
    /**
     * EXIF'ten öğrenilen gerçek çekim zamanı bildirimi (yol, epoch ms, kaynak).
     * CloudPhotoStore zamantünelini doğru güne taşımak için kullanır.
     */
    private val onCapturedDate: (String, Long, MediaSourceKind) -> Unit = { _, _, _ -> }
) : Fetcher {

    /**
     * İsteğin hedef boyutu küçük bir thumbnail mı?
     * 256 px altı istekler fetcher içinde çözümlenir.
     */
    private fun isThumbnailRequest(): Boolean {
        val w = options.size.width
        val h = options.size.height
        val wp = (w as? coil.size.Dimension.Pixels)?.px ?: return false
        val hp = (h as? coil.size.Dimension.Pixels)?.px ?: return false
        return wp <= 256 && hp <= 256
    }

    /**
     * CacheKeyHelper ile aynı anahtar formatı + küçük resim ÜRETİM sürümü.
     * "rot2": yön (EXIF dönüş) mantığı değiştiğinde diskteki eski kopyalar
     * okunmasın diye sürüm yükseltilir. Dönüş düzeltmesi öncesi üretilmiş
     * YATAY WebP'ler anahtarları değişmediği için sonsuza dek gösteriliyordu
     * (kullanıcı şikâyeti: "küçük resimde yatay, açınca dikey").
     */
    private val cacheKey: String
        get() = THUMB_VARIANT +
            CacheKeyHelper.smbKey(data.path, data.lastModified, data.source)

    override suspend fun fetch(): FetchResult {
        // BELLEK SİGORTASI: OOM süreci öldürür (kullanıcı: "uygulama sürekli
        // duruyor"). Tek bir küçük resmin üretilememesi ise zararsızdır:
        // Coil önbelleğini boşaltıp sıradan hataya çeviriyoruz; hücre yer
        // tutucu çizer ve görsel sonraki denemede gelir.
        return try {
            fetchInner()
        } catch (oom: OutOfMemoryError) {
            ImageMemoryGuard.trim()
            throw IOException("Bellek yetersiz: /${data.path}", oom)
        }
    }

    private suspend fun fetchInner(): FetchResult {
        // ── 1) Yerel thumbnail önbelleği (0 KB ağ, QoS kuyruğuna bile girmez) ──
        if (isThumbnailRequest()) {
            thumbnailCache.get(cacheKey)?.let { bitmap ->
                return DrawableResult(
                    drawable = BitmapDrawable(options.context.resources, bitmap),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            }
        }

        // ── 1b) Videolar: EXIF önizlemesiz, akıştan çözülemez ──
        // KARE YALNIZCA video zaten telefona inmişse (görüntüleyicide
        // izlenmiş ya da canlı önizleme ile indirilmiş) üretilir. ARKA PLANDA
        // toplu video indirme DENEMEZ: SMB1 NAS, ızgara trafiğiyle karışan
        // uzun toplu akışa socket reset ile cevap veriyor ve tüm ızgara
        // kırmızıya dönüyor (08.11 20:01 — 29× "Failed to connect" kanıtı).
        // İzlenmemiş video hücresi GP çevrimdışı görünümü gibi kalır:
        // koyu döşeme + oynat simgesi (hücre bunu kendisi çizer).
        // Tam boy istek: görüntüleyici videoyu Coil ile DEĞİL InlineVideoPlayer
        // ile açar; buraya düşen tam boy isteğe dosyanın tamamını indirip
        // kare çözmek en pahalı hata olurdu — anında reddet.
        if (data.isVideo) {
            if (isThumbnailRequest()) {
                localVideoFrameOrNull()?.let { bitmap ->
                    thumbnailCache.put(cacheKey, bitmap)
                    return DrawableResult(
                        drawable = BitmapDrawable(options.context.resources, bitmap),
                        isSampled = true,
                        dataSource = DataSource.DISK
                    )
                }
            }
            throw VideoThumbnailPendingException(data.path)
        }

        // ── QoS zamanlayıcı: arka plan ve görünür trafi ayrı kuyruklardır ──
        val isBackground = options.tags.tag(LowPriorityTag::class.java) != null
        val gate = if (isBackground) prefetchGate else visibleGate

        // ── 2b) Nextcloud yolu ──
        // WebDAV tarafında SUNUCUNUN kendisi seri olmak ZORUNDA (ölçüldü:
        // 3+ eşzamanlı PHP isteği PHP-FPM'i düşürüyor); [NextcloudRepository]
        // içindeki tek izinli semafor bunu garantiler. Buradaki gate yalnızca
        // kuyruk düzenini (görünür > ön yükleme) korur.
        if (data.source == MediaSourceKind.NEXTCLOUD) {
            return gate.withPermit { fetchNextcloud() }
        }

        return gate.withPermit {
            // TEK BAĞLANTI DİSİPLİNİ: bu klasastaki modem-NAS İKİNCİ bir SMB
            // bağlantısını reddediyor ("Failed to connect: 0.0.0.0<00>").
            // Eski kod paylaşılan oturum alınamayınca openSession() ile isteğe
            // özel oturum açıyordu; 11 eşzamanlı istek = 11 yeni bağlantı =
            // topyekûn ret = tüm ızgara kırmızı (11.53 logs). Artık İKİNCİ
            // oturum ASLA açılmaz: taşıma hatasında paylaşılan oturum kırılır
            // ve aynı istek geri çekilerek (backoff) tek bağlantı üzerinden
            // yeniden denenir. NAS toparlandığı an ilk deneme bağlanır.
            var lastError: Throwable = IOException("SMB görseli yüklenemedi: /${data.path}")
            for (attempt in 1..FETCH_ATTEMPTS) {
                coroutineContext[Job]?.ensureActive()
                val session = try {
                    repository.openSharedSession()
                } catch (t: Throwable) {
                    lastError = t
                    if (attempt < FETCH_ATTEMPTS) {
                        delay(FETCH_RETRY_DELAY_MS * attempt); continue
                    }
                    throw t
                }
                try {
                    val file = session.file(data.path)
                    if (!file.exists() || file.isDirectory) {
                        throw SmbFileNotFoundException(data.path)
                    }

                    val result = if (isThumbnailRequest()) {
                        fetchThumbnail(file)
                    } else {
                        fetchFullSource(file)
                    }
                    return result
                } catch (t: Throwable) {
                    // GEÇİCİ TEŞHİS LOGU: toplu kırmızı rozet regresyonunun kök
                    // nedenini yakalamak için (ilk 30 hatayı loglar, spam yapmaz).
                    val n = errorLogCount.incrementAndGet()
                    if (n <= 30) {
                        Log.w(
                            "SmpMedia",
                            "Görsel fetch hatası #$n [${data.path.substringAfterLast('/')}]: " +
                                t::class.java.simpleName + ": " + t.message,
                            t
                        )
                    }
                    // HATA İZOLASYONU (NAS'ların hata domain'i gibi): oturumu
                    // YALNIZCA gerçek taşıma hataları düşürür.
                    // - dispose() ile iptal edilen ön yükleme → Cancellation
                    // - bozuk/kod çözümlenemeyen dosya → decode hatası
                    // Bunlar oturumu VURMAZ; yoksa tek bir iptal edilen prefetch
                    // uçan 10 isteğin bağlantısını öldürüp tüm ızgarayı kırmızıya
                    // boyar (görülen regresyon).
                    if (t.isFatalTransportError()) {
                        repository.invalidateSharedSession(session)
                    }
                    lastError = t
                    if (t.isFatalTransportError() && attempt < FETCH_ATTEMPTS) {
                        delay(FETCH_RETRY_DELAY_MS * attempt)
                        continue
                    }
                    throw t
                }
            }
            throw lastError
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Video küçük resmi: YALNIZCA telefona inmiş dosyadan kare
    // ─────────────────────────────────────────────────────────────────

    /**
     * Video daha önce izlendiyse (InlineVideoPlayer) ya da canlı önizlemeyle
     * indirildiyse dosya kalıcı `media_cache/shared` altındadır; ilk kareyi
     * oradan çıkarır.
     * Ad kuralı ViewerActions.localFileFor ile BİREBİR aynı olmalı (aynı
     * dosyayı bulalım). Dosya yoksa null — ağa asla çıkılmaz.
     */
    private fun localVideoFrameOrNull(): Bitmap? = runCatching {
        val safeName = data.path.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(MediaCache.shared(options.context), safeName)
        if (!file.exists() || file.length() <= 0L) return null

        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(file.absolutePath)
            // Telefon videoları sensör formatında (YATAY) saklanır; gerçek yön
            // yalnızca meta veridedir. Pixel 9/10 dosyalarında dönüş YALNIZCA
            // MP4 tkhd matrisinde olabildiği için MMR burada 0 raporlayabiliyor
            // (sahada "videolar ızgarada yan" şikâyeti). Bu yüzden açı,
            // görüntüleyiciyle AYNI kaynaktan ([Mp4Rotation]) okunur.
            val rotation = Mp4Rotation.fromFile(file)
            val raw = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            )
            if (raw == null || rotation % 360 == 0) {
                raw
            } else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height, matrix, true
                )
                if (rotated !== raw) raw.recycle()
                rotated
            }
        } finally {
            runCatching { retriever.release() }
        } ?: return null

        // 256 px ızgara karesine küçült (1080p kare ~8 MB; ızgarada çöp olur).
        val scale = ThumbnailCache.GRID_THUMBNAIL_SIZE.toFloat() /
            maxOf(frame.width, frame.height)
        if (scale < 1f) {
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== frame) frame.recycle() }
        } else {
            frame
        }
    }.onFailure {
        Log.d("SmpMedia", "Video karesi çözülemedi (${data.path}): ${it.message}")
    }.getOrNull()

    // ─────────────────────────────────────────────────────────────────
    // Thumbnail yolu: birleştirme → EXIF → tam decode
    // ─────────────────────────────────────────────────────────────────

    /**
     * Aynı dosyayı isteyen eşzamanlı istekleri birleştirir: ilk istek
     * (leader) indirir, diğerleri (follower) sonucu bellekten devralır.
     * Böylece ön yükleme + tıklama aynı anda gelirse dosya TEK kez iner.
     */
    private suspend fun fetchThumbnail(file: SmbFile): FetchResult {
        val own = CompletableDeferred<Bitmap>()
        val leader = inFlight.putIfAbsent(cacheKey, own)

        if (leader != null) {
            // ── Follower: leader'ın indirdiği bitmap'i bekle (0 KB ek ağ) ──
            val shared = runCatching {
                withTimeoutOrNull(MERGE_WAIT_MS) { leader.await() }
            }.getOrNull()

            if (shared != null) {
                return DrawableResult(
                    drawable = BitmapDrawable(options.context.resources, shared),
                    isSampled = true,
                    dataSource = DataSource.MEMORY_CACHE
                )
            }
            // Leader başarısız/zaman aşımı: tek seferlik kendimiz indir.
            return downloadThumbnail(file)
        }

        // ── Leader: indir, yayan, bekleyenleri uyandır ──
        try {
            val result = downloadThumbnail(file)
            (result as? DrawableResult)
                ?.let { (it.drawable as? BitmapDrawable)?.bitmap }
                ?.let(own::complete)
                ?: own.completeExceptionally(IOException("Thumbnail üretilemedi"))
            return result
        } catch (t: Throwable) {
            own.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(cacheKey, own)
        }
    }

    private suspend fun downloadThumbnail(file: SmbFile): FetchResult {
        // ── 3) Tam dosya decode ──
        // ESKİ 3. ADIM (EXIF gömülü önizleme, ~256 KB) BİLİNÇLİ KALDIRILDI:
        // 09.11.2026 incelemesinde görüldü ki bazı dosyaların gömülü önizlemesi
        // ANA görüntüyle çelişiyor — bayrak NORMAL, önizleme içerik olarak
        // yan yatmış (WhatsApp/Takeout yeniden kodlamaları). Meta veriden bu
        // yalan AYIRT EDİLEMİYOR; sonuç: "küçük resim yan, açınca doğru"
        // (kullanıcı: "çok fazla resim bu şekilde"). Görüntüleyici ana
        // görüntüye bayrak uygular ve HEP doğru; küçük resmi de AYNI yoldan
        // üretmek tek garanti. Bedeli: ilk görüntülemede tam dosya iner
        // (sonra webp önbellekten anında; NAS trafiği yine seri tek akış).
        // SMB1 kuralı: başlayan okuma TAMAMLANIR (yarıda kesmek paylaşılan
        // taşımayı öldürüyordu); iptal ancak sıradaki işe geçerken etkilidir.
        return file.getInputStream().use { input ->
            // SMB1: blok arası iptal YOK (paylaşılan taşıma korunur).
            decodeThumbnailFromStream(input, cancelPerChunk = false)
        }
    }

    /**
     * İndirilen dosyayı ızgara küçük resmine çevirir. SMB ve Nextcloud
     * yolları AYNI kodu kullanır — kullanıcı şartı: hangi sunucudan gelirse
     * gelsin, aynı dosya ızgarada birebir aynı (dik) görünmeli.
     *
     * NEDEN BAYT DİZİSİ DEĞİL, GEÇİCİ DOSYA?
     * Eski kod dosyanın TAMAMINI `ByteArray` olarak belleğe alıyordu
     * (`readBytes` / büyüyen `ByteArrayOutputStream`). 4–12 MB'lık fotoğrafta
     * bu, büyüme kopyalarıyla birlikte tepe anında 2–3 katı yer istiyor ve
     * eşzamanlı 4–5 istekte 256 MB'lık heap'i doldurup uygulamayı
     * `OutOfMemoryError` ile çökertiyordu (ölçüldü: 22:22–22:23 çökme
     * döngüsü, "SMP Media sürekli olarak duruyor"). Artık akış 128 KB'lık
     * bloklarla geçici dosyaya yazılıp BitmapFactory'ye DOSYA yolu veriliyor:
     * çözümleme `inSampleSize` ile 256 px'e iner, heap'te yalnızca ~1 MB'lık
     * sonuç bitmap'i kalır.
     */
    private suspend fun decodeThumbnailFromStream(
        input: java.io.InputStream,
        /**
         * Her blokta iptal kontrolü. HTTP'de (Nextcloud) GÜVENLİ ve
         * istenen: kullanıcı kaydırınca indirme hemen bırakılır. SMB1'de
         * DEĞİL: aktif okumayı yarıda kesmek paylaşılan taşımayı düşürüp
         * tüm eşzamanlı istekleri kırmızıya boyuyordu (11.08 regresyonu).
         */
        cancelPerChunk: Boolean = true
    ): FetchResult {
        val tempDir = File(options.context.cacheDir, TEMP_DIR).apply { if (!exists()) mkdirs() }
        val temp = File(tempDir, "t${System.nanoTime()}${(0..999).random()}.bin")
        try {
            java.io.FileOutputStream(temp).use { out ->
                val chunk = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    if (cancelPerChunk) coroutineContext[Job]?.ensureActive()
                    val n = input.read(chunk)
                    if (n < 0) break
                    out.write(chunk, 0, n)
                }
            }
            if (temp.length() <= 0L) throw IOException("Görsel boş: /${data.path}")

            // EXIF başlığı dosyanın başındadır; tam kopya çıkarmadan okunur.
            val exif = runCatching { ExifInterface(temp.absolutePath) }.getOrNull()

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)

            val sampleSize = calcInSampleSize(
                bounds.outWidth, bounds.outHeight,
                ThumbnailCache.GRID_THUMBNAIL_SIZE, ThumbnailCache.GRID_THUMBNAIL_SIZE
            )

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(temp.absolutePath, decodeOptions)
                ?: throw IOException("Görsel çözümlenemedi: /${data.path}")

            if (exif != null) reportExifDate(exif)
            val oriented = if (exif != null) applyExifOrientation(bitmap, exif) else bitmap

            thumbnailCache.put(cacheKey, oriented)
            return DrawableResult(
                drawable = BitmapDrawable(options.context.resources, oriented),
                isSampled = true,
                dataSource = DataSource.NETWORK
            )
        } catch (oom: OutOfMemoryError) {
            // BitmapFactory OOM atarsa süreç ölmesin: önbelleği boşalt,
            // sıradan hata olarak yukarı taşı (hücre yer tutucuya düşer).
            ImageMemoryGuard.trim()
            throw IOException("Bellek yetersiz: /${data.path}", oom)
        } finally {
            runCatching { temp.delete() }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Nextcloud yolu: WebDAV üzerinden aynı politika
    // ─────────────────────────────────────────────────────────────────

    /**
     * Nextcloud küçük resmi/tam boyutu. Sunucu tarafı zorunlu SERİLİK
     * [NextcloudRepository] içindeki tek izinli semaforla sağlanır; burada
     * yalnızca istek BİRLEŞTİRME (leader/follower) ve backoff yürütülür.
     */
    private suspend fun fetchNextcloud(): FetchResult {
        if (!isThumbnailRequest()) return fetchNextcloudFull()

        val own = CompletableDeferred<Bitmap>()
        val leader = inFlight.putIfAbsent(cacheKey, own)

        if (leader != null) {
            // Follower: aynı dosyayı isteyen görünür isteğin sonucunu bedavaya
            // devral (ör. ızgara + eksik kuyruğu aynı anda talep etti).
            val shared = runCatching {
                withTimeoutOrNull(MERGE_WAIT_MS) { leader.await() }
            }.getOrNull()
            if (shared != null) {
                return DrawableResult(
                    drawable = BitmapDrawable(options.context.resources, shared),
                    isSampled = true,
                    dataSource = DataSource.MEMORY_CACHE
                )
            }
            return downloadThumbnailNextcloud()
        }

        var lastError: Throwable = IOException("Nextcloud görseli yüklenemedi: /${data.path}")
        try {
            for (attempt in 1..FETCH_ATTEMPTS) {
                coroutineContext[Job]?.ensureActive()
                try {
                    val result = downloadThumbnailNextcloud()
                    (result as? DrawableResult)
                        ?.let { (it.drawable as? BitmapDrawable)?.bitmap }
                        ?.let(own::complete)
                        ?: own.completeExceptionally(IOException("Thumbnail üretilemedi"))
                    return result
                } catch (t: Throwable) {
                    val n = errorLogCount.incrementAndGet()
                    if (n <= 30) {
                        Log.w(
                            "SmpMedia",
                            "Nextcloud görsel hatası #$n [${data.path.substringAfterLast('/')}]: " +
                                t::class.java.simpleName + ": " + t.message
                        )
                    }
                    lastError = t
                    // SUNUCUYA ISRAR ETME: 412/429/502 gören istek anında
                    // yeniden denenmez — Nextcloud'un brute-force koruması
                    // üst üste denemeleri daha da sertleştirir (ölçüldü).
                    // Yalnızca geçici hatalarda, uzun bekleyerek bir kez daha.
                    val transient = t is ThrottledException || t.isFatalTransportError()
                    if (transient && attempt < FETCH_ATTEMPTS) {
                        delay(FETCH_RETRY_DELAY_MS * attempt)
                        continue
                    }
                    throw t
                }
            }
            throw lastError
        } catch (t: Throwable) {
            if (!own.isCompleted) own.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(cacheKey, own)
        }
    }

    /**
     * Dosyayı WebDAV'dan TAM indirir ve küçük resme çevirir.
     *
     * Neden sunucu tarafı önizleme API'si (`/core/preview`) DEĞİL? Ölçüldü:
     * ilk üretim 2,7 sn ve PHP yoğunken 502; ayrıca fileId gerektirir (ek
     * PROPFIND). Tam dosya indirme daha yavaş ama TEK istek ve her zaman
     * görüntüleyiciyle BİREBİR aynı pikselleri üretir — kullanıcının "yanık
     * çıkan thumbnail" şikâyetinin kök çözümü bu.
     */
    private suspend fun downloadThumbnailNextcloud(): FetchResult {
        coroutineContext[Job]?.ensureActive()
        // HTTP akışında blok arası iptal GÜVENLİDİR (SMB'nin aksine): OkHttp
        // bağlantıyı kapatır, sunucudaki diğer istekler etkilenmez.
        return nextcloudRepository.openStream(data.path).use { input ->
            decodeThumbnailFromStream(input)
        }
    }

    /** Tam boy görüntü: akış doğrudan Coil'in decode hattına verilir. */
    private suspend fun fetchNextcloudFull(): FetchResult {
        val input = nextcloudRepository.openStream(data.path)
        val rawSource = input.source().buffer()
        val forwarding = object : ForwardingSource(rawSource) {
            override fun close() {
                runCatching { super.close() }
                runCatching { input.close() }
            }
        }
        return SourceResult(
            source = ImageSource(forwarding.buffer(), options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // EXIF yardımcıları: yön (döndürme) ve gerçek çekim tarihi
    // ─────────────────────────────────────────────────────────────────

    /**
     * EXIF Orientation bayrağını bitmap'e uygular.
     *
     * Telefon kameraları portre fotoğrafları sensör formatında (YATAY) saklar
     * ve gerçek yönü yalnızca EXIF'e yazar. Bu bayrak uygulanmazsa portre
     * fotoğraflar galeride yan yatmış görünür.
     */
    private fun applyExifOrientation(bitmap: Bitmap, exif: ExifInterface): Bitmap {
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.preScale(-1f, 1f); matrix.postRotate(180f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.preScale(-1f, 1f); matrix.postRotate(90f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.preScale(-1f, 1f); matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap // NORMAL / tanımsız: dokunma.
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        }.getOrDefault(bitmap)
    }

    /**
     * EXIF DateTimeOriginal (yoksa DateTime) değerini zaman tüneline bildirir.
     * NAS'taki toplu tarih bozulmasında zamantüneli böylece kendi kendine
     * iyileşir: fotoğraf ne zaman yüklenirse gerçek günü o zaman bulunur.
     */
    private fun reportExifDate(exif: ExifInterface) {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return
        val parsed = runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
        }.getOrNull()
        if (parsed != null) onCapturedDate(data.path, parsed, data.source)
    }

    // ─────────────────────────────────────────────────────────────────
    // Tam boy yol (görüntüleyici): Coil decode pipeline
    // ─────────────────────────────────────────────────────────────────

    private fun fetchFullSource(file: SmbFile): FetchResult {
        val input = file.getInputStream()
        val rawSource = input.source().buffer()
        // Paylaşılan oturum KAPATILMAZ; sadece dosya akışı kapanır.
        val forwarding = object : ForwardingSource(rawSource) {
            override fun close() {
                runCatching { super.close() }
                runCatching { input.close() }
            }
        }
        val source = forwarding.buffer()

        return SourceResult(
            source = ImageSource(source, options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK
        )
    }

    class Factory(
        private val repository: SmbRepository,
        private val nextcloudRepository: NextcloudRepository,
        private val thumbnailCache: ThumbnailCache,
        private val onCapturedDate: (String, Long, MediaSourceKind) -> Unit = { _, _, _ -> }
    ) : Fetcher.Factory<SmbImage> {
        override fun create(data: SmbImage, options: Options, imageLoader: ImageLoader): Fetcher =
            SmbImageFetcher(
                data,
                options,
                repository,
                nextcloudRepository,
                thumbnailCache,
                onCapturedDate
            )
    }

    companion object {
        /**
         * Küçük resim indirmesinin geçici dosya klasörü. `cacheDir` BİLİNÇLİ:
         * buradaki dosyalar yarıda kalan indirmelerdir, kalıcı değer taşımaz;
         * sistem daralınca silmesi istenen yerdir (kalıcı önbellek
         * [ThumbnailCache] altındadır).
         */
        private const val TEMP_DIR = "thumb_tmp"

        /** Akış → geçici dosya kopyalama bloğu (bellek dostu). */
        private const val STREAM_CHUNK_BYTES = 128 * 1024

        /**
         * Küçük resim üretim sürümü — önbellek anahtarının başına eklenir.
         * v4: video karelerinde dönüş açısı tkhd matrisinden okunuyor
         * ([Mp4Rotation]) ve cihaz küçük resimleri EXIF/tkhd ile zorla
         * düzeltiliyor; bu sürümden önce üretilmiş yan kareler düşer.
         * Internal: [com.smpmedia.data.warm.GridWarmer] "bu dosya ısınmış mı?"
         * kontrolünde AYNI anahtarı üretmek zorundadır — iki tanım birbiriyle
         * her zaman aynı kalmalı.
         */
        internal const val THUMB_VARIANT = "v4|"

        /** Follower'ın leader için bekleyeceği en fazla süre. */
        private const val MERGE_WAIT_MS = 25_000L

        /**
         * QoS izinleri. Görünür istekler asla ön yükleme kuyruğunu beklemez.
         * Bu NAS SMB1 konuşuyor: tek taşıma üzerinde okumalar paralelleşmez,
         * SIRAYA girer. Yüksek eşzamanlılık hız getirmez, kuyruğu şişirip
         * zaman aşımı üretir (11.08 tüm-kırmızı regresyonu). Bu yüzden düşük
         * tutulur: 4 görünür + 1 ön yükleme. SMB1 tek akışta zaten seri okur.
         */
        private val visibleGate = Semaphore(VISIBLE_PERMITS)
        private val prefetchGate = Semaphore(PREFETCH_PERMITS)

        /** Devam eden thumbnail indirmeleri: cacheKey -> leader'ın bitmap'i. */
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap>>()

        /** Teşhis log sayacı: ilk 30 hatadan sonra susar (log spam'i yok). */
        private val errorLogCount = java.util.concurrent.atomic.AtomicInteger()

        private const val VISIBLE_PERMITS = 4
        private const val PREFETCH_PERMITS = 1

        /**
         * Tek bağlantı disiplini: taşıma hatasında istek en fazla bu kadar
         * denenir; denemeler arası bekleme NAS'a toparlanma payı bırakır
         * (3 sn, 6 sn). Deneme başına yeni PAYLAŞILMIŞ oturum kurulur —
         * asla isteğe özel ikinci oturum değil.
         */
        private const val FETCH_ATTEMPTS = 3
        private const val FETCH_RETRY_DELAY_MS = 3_000L
    }
}

/** SMB'de dosya bulunamadığında fırlatılan hata (oturum geçerlidir). */
class SmbFileNotFoundException(path: String) :
    IOException("SMB görseli bulunamadı: /$path")

/**
 * Video küçük resmi arka planda hazırlanıyor (warm kuyruğuna alındı).
 * Hücreler bunu HATA saymaz: GP tarzı oynat-simgesi yer tutucusu çizilir,
 * eksik kuyruğuna da yazılmaz.
 */
class VideoThumbnailPendingException(path: String) :
    IOException("Video küçük resmi hazırlanıyor: /$path")

/**
 * Hata paydasını sınıflandırır: yalnızca AĞ/TAŞIMA hataları paylaşılan SMB
 * oturumunu düşürme hakkına sahiptir.
 *
 * - İptal edilen okuma (`dispose` → InterruptedIOException) → oturum sağlam,
 *   istek kullanıcı tarafından çöpe atıldı.
 * - Decode/veri hataları → oturum sağlam, dosyanın kendisi sorunlu.
 * - Soket/köpük/zaman aşımı hataları → oturum şüpheli, yenisiyle değiştirilmeli.
 */
internal fun Throwable.isFatalTransportError(): Boolean {
    if (this is kotlinx.coroutines.CancellationException) return false
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth < 10) {
        when (cause) {
            is kotlinx.coroutines.CancellationException,
            is InterruptedIOException,
            is SmbFileNotFoundException -> return false

            is SocketTimeoutException,
            is ConnectException,
            is UnknownHostException,
            is java.net.SocketException,
            is java.io.EOFException -> return true
        }
        val message = cause.message?.lowercase(Locale.ROOT).orEmpty()
        if ("broken pipe" in message || "connection reset" in message ||
            "socket closed" in message || "network is unreachable" in message ||
            // jcifs-ng bu iki mesaji SEDDESIZ (cause'suz) atar ve NAS bağlantı
            // sınırı aşılınca en sık görülen imzalardır. Bunlar ölü sayılmazsa
            // bozuk paylaşılan oturum HİÇ yenilenmez ve ızgara sonsuza dek
            // kırmızı kalır (bu sürümde görülen tam regresyon buydu).
            "transport is disconnected" in message || "failed to connect" in message ||
            "transport killed" in message || "connection timeout" in message ||
            "timed out" in message
        ) {
            return true
        }
        cause = cause.cause?.takeIf { it !== cause }
        depth++
    }
    return false
}

/**
 * BitmapFactory için power-of-2 örnekleme oranı hesaplar.
 */
internal fun calcInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
    var sampleSize = 1
    if (width <= 0 || height <= 0) return 1
    while (width / (sampleSize * 2) >= reqWidth && height / (sampleSize * 2) >= reqHeight) {
        sampleSize *= 2
    }
    return sampleSize
}
