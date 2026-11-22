package com.smpmedia.data.repository

import android.content.Context
import android.media.ExifInterface
import android.util.Log
import com.smpmedia.data.image.isFatalTransportError
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.RemoteEntry
import com.smpmedia.data.model.RemotePhoto
import com.smpmedia.data.prefs.NextcloudSettingsStore
import com.smpmedia.data.prefs.SmbSettingsStore
import com.smpmedia.util.FileNameDate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Zamantünelinin uzaktan medya durumu — kaynak (SMB/Nextcloud) hangisiyse o. */
data class CloudPhotosState(
    val photos: List<RemotePhoto> = emptyList(),
    val loading: Boolean = false,
    val settingsConfigured: Boolean = true,
    val error: String? = null,
    /** Taramanın yapıldığı kullanıcı klasörü; ayar değişince kendiliğinden yenilenir. */
    val scannedFolder: String = "",
    /** Liste HANGİ sunucudan tarandı; silme/tarih işlemleri bu alana göre yürür. */
    val source: MediaSourceKind = MediaSourceKind.SMB
)

/**
 * Kullanıcının arşivini tarar ve sonuçları uygulama ömrü boyunca paylaşır.
 * Taranan sunucu [activeSource] ile belirlenir: Nextcloud ayarları açık ve
 * birincil kaynak seçiliyse WebDAV, değilse SMP (modem USB paylaşımı).
 * İçerikler aynı arşivin kopyaları olduğu için ikisi BİRLİKTE değil, biri
 * seçilir — yoksa aynı fotoğraf tünelde iki kez listelenirdi (kullanıcı
 * kararı).
 *
 * Bir [Singleton] olmasının iki nedeni var:
 *
 * 1. Ana ızgara (MainViewModel) bulut fotoğraflarını zaman tüneliyle birleştirir.
 * 2. Görüntüleyiciden bir fotoğraf silinince liste YERİNDE güncellenir;
 *    pahalı yeniden taramaya gerek kalmaz.
 */
@Singleton
class CloudPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbRepository: SmbRepository,
    private val settingsStore: SmbSettingsStore,
    private val nextcloudRepository: NextcloudRepository,
    private val nextcloudSettingsStore: NextcloudSettingsStore
) {
    private val _state = MutableStateFlow(CloudPhotosState())
    val state: StateFlow<CloudPhotosState> = _state.asStateFlow()

    // ViewModel'lerden bağımsız yaşıyor: ekran kapalıyken de tarama bitebilir.
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var loaded = false
    @Volatile private var scanning = false

    /**
     * Zamantünelinin şu anki birincil kaynağı. Nextcloud yalnızca AYARLARI
     * KULLANILABİLİR durumdaysa (adres+şifre dolu, etkin) ve kullanıcı
     * birincil kaynak olarak seçildiyse öne geçer; aksi hâlde SMP çalışmaya
     * devam eder — bozuk Nextcloud ayarı galeriyi asla boşaltmamalı.
     */
    fun activeSource(): MediaSourceKind {
        val nc = nextcloudSettingsStore.current()
        return if (nc.enabled && nc.isConfigured && nc.primarySource == MediaSourceKind.NEXTCLOUD) {
            MediaSourceKind.NEXTCLOUD
        } else {
            MediaSourceKind.SMB
        }
    }

    /** Seçili kaynak kullanıma hazır mı ve tarama kökü ne? */
    private fun sourceRoot(source: MediaSourceKind): Pair<Boolean, String> = when (source) {
        MediaSourceKind.SMB -> {
            val s = settingsStore.current()
            s.isConfigured to s.userFolder.trim('/')
        }

        MediaSourceKind.NEXTCLOUD -> {
            val s = nextcloudSettingsStore.current()
            s.isConfigured to s.rootFolder.trim('/')
        }
    }

    /**
     * Tarama kökündeki TÜM medya dosyalarını getirir. SMB [SmbRepository.SmbEntry]
     * ve Nextcloud [RemoteEntry] farklı türler; ikisi de [RemoteEntry]'ye
     * indirgenir ki liste kurma kodu kaynaktan bağımsız tek bir yol olsun.
     */
    private suspend fun scanMedia(
        source: MediaSourceKind,
        folder: String
    ): Result<List<RemoteEntry>> = when (source) {
        MediaSourceKind.SMB ->
            smbRepository.listImagesRecursive(rootPath = folder).map { entries ->
                entries.map {
                    RemoteEntry(
                        name = it.name,
                        path = it.path,
                        isDirectory = it.isDirectory,
                        sizeBytes = it.sizeBytes,
                        lastModified = it.lastModified
                    )
                }
            }

        MediaSourceKind.NEXTCLOUD -> nextcloudRepository.listImagesRecursive(rootPath = folder)
    }

    /**
     * Tarama kökünü tarar. [force] false ise ve tarama zaten yapılmışsa
     * hiçbir şey yapmaz (ızgaraya her dönüşte ağa yük binmesin). Kaynak veya
     * kök klasör değiştiyse sonuç bayattır ve kendiliğinden yeniden taranır.
     */
    fun refresh(force: Boolean = false) {
        val source = activeSource()
        val (configured, folder) = sourceRoot(source)
        // Teşhis: açılışta hangi kaynak/kök ile tarama yapıldığını gösterir.
        // "Ayarlar kaydedilmedi mi?" sorusunun cevabı buradadır. Şifre asla
        // yazılmaz; yalnızca uzunluk + SHA-256'nın ilk 8 hanesi (PC'de aynı
        // parmak izi hesaplanarak "kayıtlı şifre doğru mu?" kesin yanıtlanır).
        run {
            val nc = nextcloudSettingsStore.current()
            Log.d(
                "SmpMedia",
                "Bulut tazeleme: kaynak=$source yapılandırılmış=$configured " +
                    "kök='$folder' kullanıcı='${nc.username}' " +
                    "şifreUzunluk=${nc.appPassword.length} " +
                    "şifreHash=${passwordFingerprint(nc.appPassword)}"
            )
        }
        if (!configured) {
            _state.update {
                it.copy(
                    settingsConfigured = false,
                    photos = emptyList(),
                    loading = false,
                    source = source
                )
            }
            return
        }
        val stale = force || !loaded ||
            _state.value.scannedFolder != folder ||
            _state.value.source != source
        if (scanning || !stale) return

        scanning = true
        storeScope.launch {
            try {
                _state.update {
                    it.copy(
                        loading = true,
                        settingsConfigured = true,
                        error = null,
                        source = source
                    )
                }
                val result = scanMedia(source, folder)
                result
                    .onSuccess { entries ->
                        loaded = true
                        // NAS'ta toplu bir işlem değiştirme tarihlerini bozmuş olabilir.
                        // Tarih kaynağı sırası: 1) dosya adı  2) içindeki YIL
                        // KLASÖRÜ (ör. "Google Fotoğraf/2019/…" → 2019'da bir gün)
                        // 3) sunucu tarihi.
                        //
                        // GOOGLE PHOTOS "CANLI FOTOĞRAF" EŞLEŞTİRMESİ: Takeout
                        // dışa aktarımı canlı fotoğrafları iki dosya olarak saklar
                        // (IMG_1234.JPG + IMG_1234.MP4). Google Photos bu çifti
                        // TEK hücre gösterir: fotoğraf + canlı rozeti; mp4 kendi
                        // başına zaman tünelinde görünmez. Aynı kuralı kuruyoruz;
                        // uzun basınca sessiz önizleme mp4 ile oynatılır.
                        val videoByBase = HashMap<String, String>()
                        val photoBases = HashSet<String>()
                        for (e in entries) {
                            if (SmbRepository.isVideoFile(e.name)) {
                                videoByBase[mediaBaseKey(e.path)] = e.path
                            } else {
                                photoBases += mediaBaseKey(e.path)
                            }
                        }
                        var fromName = 0
                        var fromFolder = 0
                        var livePairs = 0
                        var videoTiles = 0
                        _state.update {
                            it.copy(
                                photos = entries.mapNotNull { entry ->
                                    val isVideo = SmbRepository.isVideoFile(entry.name)
                                    val base = mediaBaseKey(entry.path)
                                    if (isVideo) {
                                        // Eşleşmiş mp4 = canlı fotoğrafın video
                                        // parçası; hücreyi fotoğraf temsil eder.
                                        if (base in photoBases) return@mapNotNull null
                                        // "İşlenemeyen videolar" klasörü: kullanıcı
                                        // bunları bilerek ayırmış; bozuk dosyaları
                                        // zaman tünelinde gösterip oynatma hatası
                                        // ürettirme.
                                        if (entry.path.contains("işlenemeyen", ignoreCase = true)) {
                                            return@mapNotNull null
                                        }
                                        videoTiles++
                                    }
                                    val liveVideo =
                                        if (!isVideo) videoByBase[base] else null
                                    if (liveVideo != null) livePairs++

                                    val nameDate = FileNameDate.parse(entry.name)
                                    if (nameDate != null) fromName++
                                    val folderDate = if (nameDate == null) {
                                        dateFromYearFolder(entry.path)?.also { fromFolder++ }
                                    } else {
                                        null
                                    }
                                    RemotePhoto(
                                        path = entry.path,
                                        displayName = entry.name,
                                        parentPath = entry.path.substringBeforeLast('/', ""),
                                        sizeBytes = entry.sizeBytes,
                                        lastModified = nameDate ?: folderDate ?: entry.lastModified,
                                        // Oturum İÇİNDE sıra hiç değişmesin diye
                                        // EXIF tarihi burada, tek seferde uygulanır
                                        // (bkz. reportCapturedDate).
                                        capturedMillis = learnedDate(source, entry.path),
                                        isVideo = isVideo,
                                        liveVideoPath = liveVideo,
                                        source = source,
                                        fileId = entry.fileId
                                    )
                                },
                                loading = false,
                                error = null,
                                scannedFolder = folder,
                                source = source
                            )
                        }
                        Log.d(
                            "SmpMedia", "Bulut tarama bitti [$source]: /$folder → " +
                                entries.size + " medya (adından: $fromName, " +
                                "yıl klasöründen: $fromFolder, video: $videoTiles, " +
                                "canlı çift: $livePairs)"
                        )
                    }
                    .onFailure { error ->
                        _state.update {
                            it.copy(
                                loading = false,
                                error = when (source) {
                                    MediaSourceKind.SMB -> SmbRepository.describeError(error)
                                    MediaSourceKind.NEXTCLOUD ->
                                        NextcloudRepository.describeError(error)
                                }
                            )
                        }
                    }
            } finally {
                scanning = false
            }
        }
    }

    /** Ayarlar değiştiğinde çağrılır; bir sonraki [refresh] kesinlikle yeniden tarar. */
    fun invalidate() {
        loaded = false
        nextcloudRepository.invalidateListings()
    }

    /** Şifrenin ilk 8 haneli SHA-256 özeti — günlükte gizli bilgi taşımaz. */
    private fun passwordFingerprint(password: String): String {
        if (password.isEmpty()) return "bos"
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }.take(8)
        } catch (_: Throwable) {
            "hata"
        }
    }

    /**
     * Canlı fotoğraf eşleştirme anahtarı: klasör + uzantısız ad (küçük harf).
     * "Google Fotoğraf/2021/IMG_1.JPG" ile "…/IMG_1.MP4" aynı anahta buluşur.
     */
    private fun mediaBaseKey(path: String): String {
        val folder = path.substringBeforeLast('/', "").lowercase(Locale.ROOT)
        val name = path.substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase(Locale.ROOT)
        return "$folder/$name"
    }

    /**
     * EXIF tarih defterinin anahtarı. SMB kayıtları GEÇMİŞE DÖNÜK UYUMLULUK
     * için yalın yoldur (binlerce dosyanın tarihi bu anahtarla öğrenildi);
     * Nextcloud kayıtları "nc:" öneki taşır. Aynı yol iki sunucuda da varsa
     * (arşiv ikisinde de var) öğretilen tarihler birbirini ezmez.
     */
    private fun dateKey(source: MediaSourceKind, path: String): String =
        if (source == MediaSourceKind.NEXTCLOUD) "nc:$path" else path

    /**
     * Dosyanın yolundaki KLASÖR adlarında yıl arar
     * ("Google Fotoğraf/2019 yılına ait fotoğraflar/…" → 2019). Bulursa o yıla
     * ait sabit bir gün döndürür (15 Haziran, öğle). Kullanıcı isteği: adında
     * tarih olmayan fotoğraflar klasörünün yılına göre zaman tünelinde
     * dağılsın — ay/gün önemli değil. Dosya ADI bu aramaya DAHİL DEĞİLDİR;
     * uzun hash adlı dosyalar yanlışlıkla yıl üretebilirdi.
     * EXIF gerçek tarihi öğrenildiğinde (fotoğraf yüklendikçe) O tarih öne
     * geçer; bu yalnızca akıllı bir başlangıç değeridir.
     */
    private fun dateFromYearFolder(path: String): Long? {
        val folders = path.substringBeforeLast('/', "").split('/')
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val year = folders.firstNotNullOfOrNull { segment ->
            YEAR_IN_FOLDER.find(segment)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1980..currentYear }
        } ?: return null
        return GregorianCalendar(year, Calendar.JUNE, 15, 12, 0, 0)
            .apply { set(Calendar.MILLISECOND, 0) }
            .timeInMillis
    }

    // ── EXIF çekim tarihi düzeltmesi (kendi kendine iyileşme, kalıcı) ──
    // NAS'taki toplu işlem dosya tarihlerini bozduğu için, fotoğraf yüklendiğinde
    // SmbImageFetcher EXIF'ten gerçek çekim zamanını bildirir. Öğrenilen tarih
    // ANLIK olarak listeye İŞLENMEZ: kaydırma sırasında derindeyken bir öğenin
    // başka güne "zıplaması" tüm zaman tünelini altında kaydırırdı (kullanıcı
    // şikâyeti: "açılıp açılıp yer değiştiriyorlar"). Bunun yerine tarih
    // diske yazılır ve SONRAKİ taramada capturedMillis olarak zamantüneline
    // girer — oturum boyunca sıra hiç değişmez, kronoloji her açılışta bir
    // adım daha gerçeğe yaklaşarak kendiliğinden yerine oturur.
    private val exifDatePrefs by lazy {
        context.getSharedPreferences("exif_dates", Context.MODE_PRIVATE)
    }

    /**
     * Görsel fetcher EXIF DateTimeOriginal okuduğunda çağrılır
     * ([com.smpmedia.data.image.SmbImageFetcher] her iki kaynakta da aynı
     * bildirimi yapar; kaynak yalnızca defter anahtarını ayırır).
     */
    fun reportCapturedDate(
        path: String,
        millis: Long,
        source: MediaSourceKind = MediaSourceKind.SMB
    ) {
        // Çöp tarih koruması (onarım akışıyla aynı sınırlar).
        if (millis < MIN_SENSE_MILLIS ||
            millis > System.currentTimeMillis() + 2L * 86_400_000L
        ) return
        val key = dateKey(source, path)
        if (exifDatePrefs.getLong(key, 0L) == millis) return
        exifDatePrefs.edit().putLong(key, millis).apply()
    }

    /** Diskte öğrenilmiş çekim tarihi (yoksa null). Taramada uygulanır. */
    private fun learnedDate(source: MediaSourceKind, path: String): Long? =
        exifDatePrefs.getLong(dateKey(source, path), 0L).takeIf { it > 0L }

    /**
     * Uzaktaki bir fotoğrafı kalıcı olarak siler (görüntüleyicinin çöp kutusu).
     * Dosyanın HANGİ sunucuda olduğu listeden ([RemotePhoto.source]) gelir;
     * listede yoksa o anki birincil kaynak varsayılır. Yanlış sunucudaki
     * aynı adlı dosyayı silmemek için sıralama önemli. Başarıda liste yerinde
     * güncellenir.
     */
    suspend fun deletePhoto(path: String): Boolean {
        val photo = _state.value.photos.firstOrNull { it.path == path }
        val source = photo?.source ?: activeSource()
        val ok = if (source == MediaSourceKind.NEXTCLOUD) {
            nextcloudRepository.delete(path)
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    smbRepository.openSharedSession().file(path).delete()
                }.onFailure {
                    Log.w("SmpMedia", "SMB silme başarısız: $path", it)
                }.isSuccess
            }
        }
        if (ok) {
            _state.update { it.copy(photos = it.photos.filterNot { photo -> photo.path == path }) }
        }
        return ok
    }

    // ── Tarih onarımı: EXIF'ten gerçek çekim zamanını NAS'a yaz ─────────
    // Kullanıcının NAS'ında toplu bir işlem binlerce dosyanın değiştirme
    // tarihini aynı ana bozdu. Adında tarih OLMAYAN fotoğrafların dosya
    // başlığı okunur; EXIF DateTimeOriginal bulunursa:
    //  1) NAS'taki dosyanın tarihi düzeltilir (kalıcı onarım — sonraki
    //     taramalar ve diğer uygulamalar da doğru tarihi görür),
    //  2) zaman tüneli anında güncellenir (capturedMillis).
    // İş QoS'a saygılıdır: 3 eşzamanlı okuma, tarama/indirmeleri bloklamaz.

    /** Onarım ilerlemesi; null = çalışma yok. */
    data class RepairProgress(val done: Int, val total: Int, val fixed: Int)

    private val _repair = MutableStateFlow<RepairProgress?>(null)
    val repair: StateFlow<RepairProgress?> = _repair.asStateFlow()

    @Volatile private var repairing = false

    /** Denenmiş dosyalar (onarıldı/EXIF yok) — her açılışta yeniden okunmaz. */
    private val repairPrefs = context.getSharedPreferences(
        "date_repair", Context.MODE_PRIVATE
    )

    /** Adında tarih olmayan SMP fotoğraflarını EXIF'ten onarır. */
    fun startDateRepair() {
        if (repairing) return
        repairing = true
        storeScope.launch {
            try {
                repairDatesFromExif()
            } catch (t: Throwable) {
                Log.w("SmpMedia", "Tarih onarımı yarıda kesildi: $t")
            } finally {
                repairing = false
                _repair.value = null
            }
        }
    }

    private suspend fun repairDatesFromExif() = withContext(Dispatchers.IO) {
        // Onarım SMB'ye özgüdür: dosyanın üzerine tarih YAZMAK gerekir.
        // WebDAV'da sunucu istemcinin mtime'ını yok sayar (Nextcloud kendi
        // kaydını tutar); aynı işi Nextcloud'a yaptırmak dosyayı baştan
        // yüklemeyi gerektirirdi — 35 GB'lık arşiv için kabul edilemez.
        // Nextcloud kopyasının tarihi, SMB tarafı onarıldıktan sonra
        // senkronizasyonla kendiliğinden düzelir.
        if (_state.value.source != MediaSourceKind.SMB) {
            Log.d("SmpMedia", "Tarih onarımı atlandı: birincil kaynak Nextcloud")
            return@withContext
        }
        val doneSet = repairPrefs.getStringSet(KEY_REPAIR_DONE, emptySet())?.toSet()
            ?: emptySet()
        // Onarım kapsamı: daha önce işlenmemiş TÜM bulut fotoğrafları.
        // Her dosyada: NAS'taki tarih okunur; güvenilir tarih (EXIF > ad >
        // klasör yılı) ondan farklıysa dosyanın kendisine yazılır — böylece
        // "Google Fotoğraf" klasöründeki fotoğraflar PC'den bakınca da doğru
        // tarihli görünür. Zaten doğru olanlara dokunulmaz.
        val targets = _state.value.photos.filter { photo -> photo.path !in doneSet }
        if (targets.isEmpty()) {
            Log.d("SmpMedia", "Tarih onarımı: işlenmemiş dosya yok, atlandı")
            return@withContext
        }
        // Açılış küçük resim trafiği dindikten sonra başla: SMB bağlantı
        // havuzu doluyken "Failed to connect" hatası alma riskini azaltır.
        delay(REPAIR_START_DELAY_MS)
        Log.d("SmpMedia", "Tarih onarımı başladı: ${targets.size} dosya")
        // Küçük resimlerin SAATLERDİR sorunsuz kullandığı PAYLAŞILMIŞ oturumda,
        // TEK akışla koşar: NAS (modeme bağlı USB sunucu) ikinci bir oturumu
        // yeni denemede reddediyor — ayrı oturum açmak "Failed to connect"
        // demek. Tek akış + dosyalar arası soluklanma mevcut bağlantıyı zorlamaz.
        val session = try {
            smbRepository.openSharedSession()
        } catch (t: Throwable) {
            Log.w("SmpMedia", "Tarih onarımı: oturum açılamadı: $t")
            return@withContext
        }
        try {
            val total = targets.size
            val done = AtomicInteger(0)
            val written = AtomicInteger(0) // NAS'a tarihi yazılan dosya sayısı
            val readErrors = AtomicInteger(0)
            val aborted = AtomicBoolean(false)
            val settled = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val corrections = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()
            val gate = Semaphore(REPAIR_PARALLELISM)

            _repair.value = RepairProgress(0, total, 0)
            coroutineScope {
                targets.map { photo ->
                    launch {
                        gate.withPermit {
                            if (aborted.get()) return@withPermit
                            val isJpeg = photo.path.lowercase(Locale.ROOT)
                                .substringAfterLast('.', "") in EXIF_DATE_EXTENSIONS
                            // ZAMANTÜNELİNİN kullandığı güvenilir tarih:
                            // EXIF (biliniyorsa) > dosya adı > klasör yılı.
                            val metaDate = photo.capturedMillis
                                ?: FileNameDate.parse(photo.displayName)
                                ?: dateFromYearFolder(photo.path)
                            val file = session.file(photo.path)

                            // Dosyanın NAS'taki ŞU ANKİ tarihini oku (tek
                            // meta veri sorusu — ucuz). Taşıma hatasında bir
                            // kez bekleyip dene; yine olmazsa bu turu bitir.
                            var outcome = runCatching { file.lastModified }
                            val first = outcome.exceptionOrNull()
                            if (first != null && first.isFatalTransportError()) {
                                delay(REPAIR_RETRY_DELAY_MS)
                                outcome = runCatching { file.lastModified }
                            }
                            outcome.onFailure {
                                val n = readErrors.incrementAndGet()
                                if (n <= 5) {
                                    Log.w("SmpMedia", "Onarım sorgu hatası #$n: $it")
                                }
                                if (it.isFatalTransportError()) {
                                    smbRepository.invalidateSharedSession(session)
                                    aborted.set(true)
                                }
                            }
                            if (outcome.isSuccess) {
                                val nasMillis = outcome.getOrNull() ?: 0L
                                // Güvenilir tarih yoksa (ad/klasör/EXIF-bellek)
                                // JPEG başlığını oku; diğer türlerde arayacak
                                // bir şey yok.
                                val exifOutcome: Result<Long?>? =
                                    if (metaDate == null && isJpeg) {
                                        var ex = runCatching { readExifDate(file) }
                                        if (ex.exceptionOrNull()?.isFatalTransportError()
                                            == true
                                        ) {
                                            delay(REPAIR_RETRY_DELAY_MS)
                                            ex = runCatching { readExifDate(file) }
                                        }
                                        ex.onFailure {
                                            val n = readErrors.incrementAndGet()
                                            if (n <= 5) {
                                                Log.w("SmpMedia", "Onarım okuma hatası #$n: $it")
                                            }
                                            if (it.isFatalTransportError()) {
                                                smbRepository.invalidateSharedSession(session)
                                                aborted.set(true)
                                            }
                                        }
                                        ex
                                    } else {
                                        null
                                    }
                                // Başlık okunamadı (taşıma hatası) → settled'a
                                // EKLENMEZ; sonraki basışta yeniden denenir.
                                if (exifOutcome == null || exifOutcome.isSuccess) {
                                    val target = metaDate ?: exifOutcome?.getOrNull()
                                    // Kullanıcı isteği: DOSYANIN KENDİSİNE tarih
                                    // işlensin (gün/ay önemli değil). Yalnızca
                                    // NAS tarihi HÂLÂ yanlışsa yaz — doğru
                                    // dosyalara (ör. WhatsApp yedekleri)
                                    // hiç dokunulmaz, iş hızlanır.
                                    if (target != null &&
                                        kotlin.math.abs(nasMillis - target) > 2L * 3_600_000L
                                    ) {
                                        runCatching { file.setLastModified(target) }
                                            .onSuccess {
                                                written.incrementAndGet()
                                                if (metaDate == null) {
                                                    // Yeni öğrenilen EXIF tarihi
                                                    // zaman tüneline de işlensin.
                                                    corrections.add(photo.path to target)
                                                }
                                            }
                                    }
                                    // Sonuç ne olursa olsun "işlendi": sonraki
                                    // çalışmada boşuna yeniden sorgulanmaz.
                                    settled.add(photo.path)
                                }
                            }
                            val d = done.incrementAndGet()
                            if (d % PROGRESS_EVERY == 0) {
                                _repair.value = RepairProgress(d, total, written.get())
                            }
                            if (d % FLUSH_EVERY == 0) {
                                flushCorrections(corrections)
                                persistSettled(settled)
                            }
                            // Dosyalar arası soluklanma: tek akış bile olsa aynı
                            // oturumu paylaşan küçük resim isteklerini sıkıştırmasın.
                            delay(REPAIR_GAP_MS)
                        }
                    }
                }.forEach { it.join() }
            }
            flushCorrections(corrections)
            persistSettled(settled)
            Log.d(
                "SmpMedia", "Tarih onarımı bitti: $total dosya işlendi, " +
                    "${written.get()} dosyaya tarih yazıldı, ${readErrors.get()} hata"
            )
        } finally {
            // Paylaşılan oturum BİZİM değil — kapatılmaz; yalnız ilerleme temizlenir.
            _repair.value = null
        }
    }

    /** Birikmiş EXIF tarihlerini tek seferde state'e işler (kaydırma jank'i yok). */
    private fun flushCorrections(queue: java.util.Queue<Pair<String, Long>>) {
        if (queue.isEmpty()) return
        val batch = LinkedHashMap<String, Long>()
        while (true) {
            val item = queue.poll() ?: break
            batch[item.first] = item.second
        }
        // Kalıcı deftere de yaz: onarılan tarih sonraki taramalarda hazır olsun.
        val editor = exifDatePrefs.edit()
        batch.forEach { (path, millis) -> editor.putLong(path, millis) }
        editor.apply()
        _state.update { state ->
            var changed = false
            val next = state.photos.map { photo ->
                val millis = batch[photo.path]
                if (millis != null && photo.capturedMillis != millis) {
                    changed = true
                    photo.copy(capturedMillis = millis)
                } else {
                    photo
                }
            }
            if (changed) state.copy(photos = next) else state
        }
    }

    /** Denenmiş dosyaları kalıcı kaydeder; sonraki açılışlarda atlanır. */
    private fun persistSettled(queue: java.util.Queue<String>) {
        if (queue.isEmpty()) return
        val batch = ArrayList<String>()
        while (true) {
            val item = queue.poll() ?: break
            batch.add(item)
        }
        val current = repairPrefs.getStringSet(KEY_REPAIR_DONE, emptySet())?.toSet()
            ?: emptySet()
        repairPrefs.edit().putStringSet(KEY_REPAIR_DONE, current + batch).apply()
    }

    /**
     * Dosyanın başından EXIF DateTimeOriginal okur (önce 64 KB, bulunamazsa
     * 256 KB). Bulunamazsa null — çağıran dosyayı "denendi" sayar.
     */
    private fun readExifDate(file: jcifs.smb.SmbFile): Long? {
        val buf = ByteArray(EXIF_HEAD_MAX)
        var read = 0
        file.getInputStream().use { input ->
            while (read < EXIF_HEAD_SMALL) {
                val n = input.read(buf, read, EXIF_HEAD_SMALL - read)
                if (n < 0) break
                read += n
            }
            if (read >= 256 && parseExifMillis(buf, read) == null) {
                // Ağaç büyük gömülü önizlemeler tarih bölümünü ileri iter.
                while (read < EXIF_HEAD_MAX) {
                    val n = input.read(buf, read, EXIF_HEAD_MAX - read)
                    if (n < 0) break
                    read += n
                }
            }
        }
        if (read < 256) return null
        return parseExifMillis(buf, read)
    }

    private fun parseExifMillis(buf: ByteArray, size: Int): Long? = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(buf, 0, size))
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)?.time
            ?: return null
        // Çöp tarih koruması: 1990 öncesi ve gelecek tarihler reddedilir.
        if (parsed < MIN_SENSE_MILLIS || parsed > System.currentTimeMillis() + 2L * 86_400_000L) {
            null
        } else {
            parsed
        }
    }.getOrNull()

    private companion object {
        /** "Denendi" kaydedilen dosya yollarının tutulduğu anahtar. */
        const val KEY_REPAIR_DONE = "done_paths"

        /** EXIF tarihi yalnızca bu uzantılarda aranır (PNG/WEBP'te yoktur). */
        val EXIF_DATE_EXTENSIONS = setOf("jpg", "jpeg")

        /** NAS'a saygılı eşzamanlı okuma sayısı (sMB sunucusu çoklu akışta
         * bağlantıreddediyor; onarım tek akışla sakin koşar). */
        const val REPAIR_PARALLELISM = 1

        /** Onarımın açılış küçük resim trafiğinden sonra başlaması için bekleme. */
        const val REPAIR_START_DELAY_MS = 6_000L

        /** Taşıma hatasında aynı dosya için bekleme + tekrar deneme. */
        const val REPAIR_RETRY_DELAY_MS = 1_200L

        /** Onarımın dosyalar arası soluklanma süresi (QoS). */
        const val REPAIR_GAP_MS = 150L

        /** EXIF başlığı için önce küçük, yetmezse büyük okuma boyutu. */
        const val EXIF_HEAD_SMALL = 64 * 1024
        const val EXIF_HEAD_MAX = 256 * 1024

        /** 01.01.1990 — öncesi tarihler çöp sayılır. */
        const val MIN_SENSE_MILLIS = 631_152_000_000L

        /** İlerleme ve state güncelleme aralıkları (UI jank'i olmaması için). */
        const val PROGRESS_EVERY = 25
        const val FLUSH_EVERY = 100

        /** Klasör ADININ İÇİNDEKI yıl deseni: "2019 yılına ait fotoğraflar". */
        val YEAR_IN_FOLDER = Regex("(19\\d{2}|20\\d{2})")
    }
}
