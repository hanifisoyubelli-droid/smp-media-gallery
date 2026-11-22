package com.smpmedia.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.smpmedia.data.model.SmbEntry
import com.smpmedia.data.model.SmbSettings
import com.smpmedia.data.prefs.SmbSettingsStore
import com.smpmedia.di.IoDispatcher
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * jcifs-ng tabanlı SMB istemcisi.
 *
 * Her işlem için yeni bir oturum açmak yerine [SmbSession] kullanılır; böylece
 * yüzlerce fotoğraf yüklenirken NTLM el sıkışması tekrarlanmaz.
 */
@Singleton
class SmbRepository @Inject constructor(
    private val settingsStore: SmbSettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    // ── Paylaşılan oturum ──────────────────────────────────────────────
    // Görsel yüklemeleri (Coil fetcher) aynı SMB bağlantısını kullanır.
    // Böylece her küçük resim için NTLM el sıkışması (~500 ms) tekrarlanmaz;
    // tek bir TCP bağlantısı üzerinden peş peşe istekler akar.
    private val sessionLock = Any()
    @Volatile private var sharedSession: SmbSession? = null
    @Volatile private var sharedSettingsHash: Int = 0

    /**
     * Modem USB paylaşımları çoğunlukla SMB1, NAS cihazları SMB2/3 konuşur.
     * Aralığı geniş tutarak her iki dünyayı da destekliyoruz.
     *
     * Agresif performans ayarları:
     * - 256 KB tamponlar: daha az paket, daha yüksek verim
     * - 80 eşzamanlı istek/bağlantı: daha fazla paralel okuma
     * - 512 başlangıç kredisi: hemen büyük okuma yapabilme
     * - TCP_NODELAY: küçük paketlerde Nagle gecikmesini kaldır
     */
    private val baseProperties = Properties().apply {
        // Türkçe/Kiril karakterli klasör adlarında varsayılan kodlama bazı
        // sunucularda bozuluyor; adların tel üzerinde UTF-8 (UTF-16LE) ile
        // gittiğini açıkça sabitle.
        setProperty("jcifs.charset", "UTF-8")
        setProperty("jcifs.smb.client.enableSMB2", "true")
        setProperty("jcifs.smb.client.minVersion", "SMB1")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")
        setProperty("jcifs.smb.client.dfs.disabled", "true")
        setProperty("jcifs.smb.client.connTimeout", "15000")
        // 20 sn: 30 sn'lik cevap zaman aşımında ölü istek izin/queue'u 30 sn
        // dolu tutuyordu; 20 sn yeterince cömert (64 KB'lık TEK okuma için) ve
        // arızayı iki kat hızlı fark edip backoff'lu yeniden denemeye geçer.
        setProperty("jcifs.smb.client.responseTimeout", "20000")
        setProperty("jcifs.smb.client.soTimeout", "25000")
        // SMB okuma/yazma tamponları: 256 KB. Büyük tamponlar daha az paket
        // trafiği demektir; her fotoğraf yüklemede round-trip sayısı azalır.
        setProperty("jcifs.smb.client.rcv_buf_size", "262144")
        setProperty("jcifs.smb.client.snd_buf_size", "262144")
        // SMB1 ReadAndX istek bloğu: 64 KB. Sunucuyla pazarlıkta bu büyüklük
        // açıkça İSTENİR; istenmezse bazı modem/NAS firmware'leri 16 KB'a
        // düşürüyor ve aynı dosya 4 kat fazla gidiş-dönüşle taşınıyor.
        // jcifs, sunucunun kabul ettiğinden fazlasını otomatik kullanmaz.
        setProperty("jcifs.smb.client.rsize", "65536")
        // Eşzamanlı istek sayısı (SMB1 multiplexing): 4. Bu modem NAS SMB2'yi
        // reddedip SMB1'e düşüyor (log: SmbComReadAndX) ve SMB1'de sunucu tek
        // taşıma üzerinde ancak birkaç isteği sindirebiliyor; 80 bekleyen okuma
        // onun posta kutusunu şişirip "timedout waiting for response" çığlığı
        // üretti (11.08 tüm-kırmızı ızgara). QoS kapılarıyla uyumlu: 4.
        setProperty("jcifs.smb.client.maxMultiplexed", "4")
        // SMB2 kredi (flow control) ayarları: büyük okumalar için hemen yeterli
        // kredi olsun; küçük okuma bekleme süreleri ortadan kalkar.
        setProperty("jcifs.smb.client.initialCredits", "512")
        setProperty("jcifs.smb.client.maxCredits", "8192")
        // TCP_NODELAY: küçük paketlerde Nagle algoritmasını devre dışı bırak.
        // Thumbnail okumalarında ~10-40ms gecikme tasarrufu sağlar.
        setProperty("jcifs.smb.client.tcpNoDelay", "true")
        // İmza (signing) tercih etme: CPU yükünü azaltır. Sunucu zorunlu
        // kılmıyorsa kapalı kalır; zorunluysa jcifs otomatik açar.
        setProperty("jcifs.smb.client.signingPreferred", "false")
        setProperty("jcifs.netbios.cachePolicy", "120")
    }

    /**
     * jcifs-ng yapılandırmasını ilk bağlantıdan önce sistem özelliklerine de
     * yazar. Bazı jcifs-ng sürümleri global ayarları okurken system property
     * tabanlı yapılandırmayı kullanır; Properties ile birlikte vermek
     * tutarlılık sağlar.
     */
    private fun applySystemProperties() {
        System.setProperty("jcifs.smb.client.rcv_buf_size", "262144")
        System.setProperty("jcifs.smb.client.snd_buf_size", "262144")
        System.setProperty("jcifs.smb.client.rsize", "65536")
        System.setProperty("jcifs.smb.client.responseTimeout", "20000")
        System.setProperty("jcifs.smb.client.maxMultiplexed", "4")
        System.setProperty("jcifs.smb.client.initialCredits", "512")
        System.setProperty("jcifs.smb.client.tcpNoDelay", "true")
    }

    /** Yeni bir SMB oturumu açar. Kullanımdan sonra mutlaka close() çağrılmalıdır. */
    fun openSession(settings: SmbSettings = settingsStore.current()): SmbSession {
        require(settings.isConfigured) {
            "SMB sunucu adresi ve paylaşım adı zorunludur."
        }
        // Tampon/timeout ayarlarını bağlantıdan önce uygula.
        applySystemProperties()
        val base = BaseContext(PropertyConfiguration(baseProperties))
        return try {
            val authenticator = NtlmPasswordAuthenticator(
                settings.domain,
                settings.username,
                settings.password
            )
            SmbSession(base, base.withCredentials(authenticator), settings)
        } catch (t: Throwable) {
            runCatching { base.close() }
            throw t
        }
    }

    /**
     * Paylaşılan SMB oturumu döndürür. Görsel yüklemeleri aynı bağlantıyı
     * kullanır; böylece NTLM el sıkışması sadece bir kez yapılır.
     *
     * - Ayarlar değiştiyse eski oturum kapatılıp yenisi açılır.
     * - Bağlantı koptuğunda [invalidateSharedSession] çağrılır ve bir
     *   sonraki istek taze oturum açar.
     * - Bu oturum çağrılmayan yerde kapatılmamalıdır; ömür boyu yaşar.
     */
    fun openSharedSession(): SmbSession {
        synchronized(sessionLock) {
            val settings = settingsStore.current()
            val hash = settings.hashCode()
            val current = sharedSession
            if (current != null && sharedSettingsHash == hash) {
                return current
            }
            // Eski oturumu temizle.
            runCatching { current?.close() }
            val session = openSession(settings)
            sharedSession = session
            sharedSettingsHash = hash
            return session
        }
    }

    /**
     * Paylaşılan oturumu geçersiz kır. Bir sonraki istek yeni oturum açar.
     *
     * [expected] verildiğinde YALNIZCA halen kullanımdaki oturum O nesneyse
     * kırılır. Bayat bir isteğin (örneğin yenisi çoktan açılmışken gecikmeli
     * hata veren eski oturumun) taze bağlantıyı yanlışlıkla kapatması engellenir
     * — NAS denetleyicilerinin "yalnızca arızalı diski gruptan çıkar" ilkesi.
     */
    fun invalidateSharedSession(expected: SmbSession? = null) {
        synchronized(sessionLock) {
            if (expected != null && sharedSession !== expected) {
                return // Oturum çoktan yenilenmiş; dokunma.
            }
            runCatching { sharedSession?.close() }
            sharedSession = null
            sharedSettingsHash = 0
        }
    }

    /**
     * Uygulama açılışında çağrılır; paylaşılan SMB oturumunu arka planda
     * ısıtır. Böylece kullanıcı SMP sekmesine geçtiğinde NTLM el sıkışması
     * (~500 ms - 1 sn) çoktan tamamlanmış olur ve ilk küçük resim beklemeden
     * görünür. Ayar tanımlı değilse sessizce çıkar.
     */
    suspend fun prewarmSession() = withContext(ioDispatcher) {
        if (!settingsStore.current().isConfigured) return@withContext
        runCatching { openSharedSession() }
    }

    /** Bağlantıyı paylaşım kökünü listeleyerek doğrular. */
    suspend fun testConnection(settings: SmbSettings): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                openSession(settings).use { session ->
                    val root = session.directory("")
                    if (!root.exists()) {
                        throw IOException(
                            "Paylaşım bulunamadı: ${settings.host}/${settings.share}"
                        )
                    }
                    if (!root.isDirectory) {
                        throw IOException("Belirtilen yol bir klasör değil.")
                    }
                    // Erişim iznini gerçekten doğrulamak için dizini oku.
                    root.listFiles()
                    Unit
                }
            }
        }

    /** Verilen göreli yolu listeler (tek seferlik oturum açar). */
    suspend fun listDirectory(path: String): Result<List<SmbEntry>> =
        withContext(ioDispatcher) {
            runCatching {
                openSession().use { session -> listDirectory(session, path) }
            }
        }

    /** Açık bir oturum üzerinden dizin listeler. */
    suspend fun listDirectory(session: SmbSession, path: String): List<SmbEntry> =
        withContext(ioDispatcher) {
            val dir = session.directory(path)
            if (!dir.exists()) throw IOException("Klasör bulunamadı: /${path.trim('/')}")
            if (!dir.isDirectory) throw IOException("Bu yol bir klasör değil: /${path.trim('/')}")

            val children = dir.listFiles() ?: emptyArray()
            children
                .mapNotNull { child -> readEntry(child, path) }
                .sortedWith(
                    compareByDescending<SmbEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
        }

    /**
     * Paylaşım kökünden başlayarak görsel dosyalarını klasör klasör tarar.
     * SMP sekmesi, cihazdan silinmiş olsa bile sunucudaki yedekleri gösterebilsin diye
     * Room kayıtlarına bağlı kalmadan doğrudan SMB'yi okur.
     *
     * [maxFiles]: NAS'ta 8.000+ fotoğraflı gerçek kütüphaneler var (55 GB);
     * eski 5.000 sınırı klasörlerin yarısını görünmez kılıyordu.
     */
    suspend fun listImagesRecursive(
        rootPath: String = "",
        maxDepth: Int = 6,
        maxFiles: Int = 20000
    ): Result<List<SmbEntry>> = withContext(ioDispatcher) {
        runCatching {
            openSession().use { session ->
                val result = ArrayList<SmbEntry>()
                val queue = ArrayDeque<Pair<String, Int>>()
                queue.add(rootPath to 0)
                // TEŞHİS: hangi klasör listelenemedi ve neden? Türkçe/boşluklu
                // klasör adlarında sessiz başarısızlık şüphesi var; log olmadan
                // "klasör listede yok" ile "ağ hatası" ayırt edilemiyordu.
                var dirsVisited = 0
                var failures = 0

                while (queue.isNotEmpty() && result.size < maxFiles) {
                    val (path, depth) = queue.removeFirst()
                    if (depth > maxDepth) continue

                    // Erişilemeyen tek bir klasör tüm taramayı bozmasın.
                    val entries = try {
                        dirsVisited++
                        listDirectory(session, path)
                    } catch (t: Throwable) {
                        failures++
                        if (failures <= 15) {
                            Log.w("SmpMedia", "Tarama klasörü listelenemedi: /$path → $t")
                        }
                        emptyList()
                    }
                    // TEŞHİS: üst iki katmanın içeriğini logla — "Google Fotoğraf"
                    // listeleniyor mu, alt klasörleri kuyruğa giriyor mu?
                    if (depth <= 2) {
                        val dirs = entries.count { it.isDirectory }
                        // TEŞHİS: uzantı filtresinden geçen sayı — "kayıt" ile
                        // "görsel" farklıysa hangi dosyalar eleniyor demektir.
                        val imgs = entries.count { !it.isDirectory && isImageFile(it.name) }
                        val vids = entries.count { !it.isDirectory && isVideoFile(it.name) }
                        Log.d(
                            "SmpMedia",
                            "Tarama listesi /$path → ${entries.size} kayıt " +
                                "($dirs klasör, $imgs görsel, $vids video)"
                        )
                    }
                    for (entry in entries) {
                        if (result.size < maxFiles) {
                            if (entry.isDirectory) {
                                queue.add(entry.path to depth + 1)
                            } else if (isImageFile(entry.name) || isVideoFile(entry.name)) {
                                // Videolar da zaman tüneline girer (Google Photos
                                // gibi): rozetli hücre + dokununca oynatıcıda açılır.
                                result.add(entry)
                            }
                        }
                    }
                }

                Log.d(
                    "SmpMedia",
                    "SMB özyinelemeli tarama: kök=/$rootPath gezilen=$dirsVisited " +
                        "başarısız=$failures dosya=${result.size}" +
                        if (result.size >= maxFiles) " (SINIRA ULAŞILDI)" else ""
                )
                result.sortedByDescending { it.lastModified }
            }
        }
    }

    /** SMB üzerinde klasör yoksa (gerekiyorsa ara klasörlerle) oluşturur. */
    suspend fun ensureDirectory(session: SmbSession, path: String) =
        withContext(ioDispatcher) {
            val clean = path.trim().trim('/')
            if (clean.isEmpty()) return@withContext

            val dir = session.directory(clean)
            if (dir.exists()) {
                if (!dir.isDirectory) throw IOException("Hedef yol bir dosya: /$clean")
                return@withContext
            }
            // jcifs-ng'de mkdirs() void döner; başarıyı exists() ile doğruluyoruz.
            dir.mkdirs()
            if (!dir.exists()) {
                throw IOException("SMB klasörü oluşturulamadı: /$clean")
            }
        }

    /**
     * Yerel içerik URI'sini SMB'ye yükler.
     * Önce ".part" dosyasına yazılır, başarılıysa hedefe taşınır; böylece
     * yarıda kalan yükleme hedefte bozuk dosya bırakmaz.
     */
    suspend fun upload(
        session: SmbSession,
        contentResolver: ContentResolver,
        localUri: Uri,
        remotePath: String,
        lastModified: Long,
        totalBytes: Long,
        onProgress: (Int) -> Unit = {}
    ) = withContext(ioDispatcher) {
        val cleanPath = remotePath.trim().trim('/')
        require(cleanPath.isNotEmpty()) { "Uzak dosya yolu boş olamaz." }

        val parent = cleanPath.substringBeforeLast('/', "")
        ensureDirectory(session, parent)

        val target = session.file(cleanPath)
        val temp = session.file("$cleanPath.part")
        if (temp.exists()) temp.delete()

        val input = contentResolver.openInputStream(localUri)
            ?: throw IOException("Yerel dosya okunamadı: $localUri")

        input.use { source ->
            SmbFileOutputStream(temp).use { output ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    if (totalBytes > 0L) {
                        onProgress(((sent * 100) / totalBytes).toInt().coerceIn(0, 100))
                    }
                }
                output.flush()
            }
        }

        if (target.exists()) {
            // delete() hata durumunda istisna fırlatır; başarıyı exists() ile kontrol et.
            runCatching { target.delete() }
            if (target.exists()) {
                runCatching { temp.delete() }
                throw IOException("Eski dosya silinemedi: /$cleanPath")
            }
        }

        val renamed = runCatching { temp.renameTo(target) }.isSuccess && target.exists()
        if (!renamed) {
            // Bazı eski SMB sunucuları rename desteklemez: kopyalayıp geçici dosyayı sil.
            temp.getInputStream().use { source ->
                SmbFileOutputStream(target).use { output -> source.copyTo(output) }
            }
            runCatching { temp.delete() }
        }

        // SMB tarafındaki tarih, telefonla aynı olsun (mümkünse).
        runCatching { target.setLastModified(lastModified) }
        onProgress(100)
    }

    private fun readEntry(child: SmbFile, parentPath: String): SmbEntry? = runCatching {
        // jcifs-ng listFiles() ile gelen adlar GERÇEKTİR (sunucudan UTF-16
        // olarak okundu); URL'deki gibi yüzde-kodlu değildir. Bu yüzden burada
        // URLDecoder KULLANILMAZ — "100%20yıl" gibi gerçek bir ad bozulurdu.
        val rawName = child.name.trimEnd('/')
        if (rawName.isBlank()) return@runCatching null

        val name = rawName
        val path = if (parentPath.isBlank()) name else "${parentPath.trim('/')}/$name"
        val directory = child.isDirectory

        SmbEntry(
            name = name,
            path = path,
            isDirectory = directory,
            sizeBytes = if (directory) 0L else child.length(),
            lastModified = if (directory) 0L else child.lastModified()
        )
    }.getOrNull()

    /** SMP taramasında fotoğraf olarak kabul edilen uzantılar. */
    private fun isImageFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in IMAGE_EXTENSIONS
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp"
        )

        /** SMP taramasında video olarak kabul edilen uzantılar (GP zaman tüneli). */
        fun isVideoFile(name: String): Boolean {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return extension in VIDEO_EXTENSIONS
        }

        /**
         * Zaman tünelinde gösterilen videolar. AVI/WMV gibi MediaPlayer'ın
         * çoğu telefonda çözümleyemediği kapsayıcılar bilinçli olarak dışarıda;
         * listeye eklemek sonradan tek satırdır.
         */
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "3gp"
        )

        /** Kullanıcıya gösterilecek Türkçe hata mesajı üretir. */
        fun describeError(error: Throwable): String {
            val root = rootCause(error)
            val raw = root.message.orEmpty()
            return when {
                root is UnknownHostException ->
                    "Sunucu bulunamadı. IP adresini kontrol edin."

                root is ConnectException ->
                    "Sunucuya bağlanılamadı. Modem USB paylaşımı açık mı?"

                root is SocketTimeoutException ->
                    "Bağlantı zaman aşımına uğradı. Ağ bağlantısını kontrol edin."

                raw.contains("logon failure", ignoreCase = true) ||
                    raw.contains("LOGON_FAILURE", ignoreCase = true) ->
                    "Kullanıcı adı veya şifre hatalı."

                raw.contains("access denied", ignoreCase = true) ||
                    raw.contains("ACCESS_DENIED", ignoreCase = true) ->
                    "Erişim reddedildi. Paylaşım izinlerini kontrol edin."

                raw.contains("bad network name", ignoreCase = true) ||
                    raw.contains("BAD_NETWORK_NAME", ignoreCase = true) ||
                    raw.contains("not found", ignoreCase = true) ->
                    "Paylaşım veya klasör bulunamadı."

                root is SmbException ->
                    "SMB hatası: ${raw.ifBlank { "bilinmeyen hata" }}"

                else -> raw.ifBlank { "Bilinmeyen hata" }
            }
        }

        private fun rootCause(error: Throwable): Throwable {
            var current = error
            var depth = 0
            while (current.cause != null && current.cause !== current && depth < 10) {
                current = current.cause!!
                depth++
            }
            return current
        }
    }
}

/** Açık bir SMB bağlantısı; tüm işlemler aynı kimlik doğrulama oturumunu paylaşır. */
class SmbSession internal constructor(
    private val base: BaseContext,
    val context: CIFSContext,
    val settings: SmbSettings
) : Closeable {

    fun file(relativePath: String): SmbFile = SmbFile(url(relativePath, directory = false), context)

    fun directory(relativePath: String): SmbFile = SmbFile(url(relativePath, directory = true), context)

    override fun close() {
        runCatching { base.close() }
    }

    /**
     * URL'e yerleştirilecek klasör/dosya adini hazirlar.
     *
     * ONEMLI: jcifs-ng (2.1.10) URL yolundaki yuzde-kodlarini GERI
     * COZMEZ — oldugu gibi sunucuya gonderir. Eskiden burada yapilan
     * URLEncoder ("Google Fotoğraf" → "Google%20Foto%C4%9Fraf") sunucuda
     * gercek olmayan bir isim aramaya karsilik geliyordu ve Türkce/
     * bosluklu butun klasörler "Klasör bulunamadı" oluyordu. Artik adlar
     * GERCEK Unicode degerleriyle yazilir. Sadece URL soz dizimini
     * bozan '#' ve '?' sembol olarak kacirilir (boyle adli klasör
     * nadirdir; sunucu da onlari oldugu gibi gorur).
     */
    private fun sanitizeSegment(segment: String): String =
        segment.replace("#", "%23").replace("?", "%3F")

    private fun url(relativePath: String, directory: Boolean): String {
        val host = settings.host.trim()
            .removePrefix("smb://")
            .trimEnd('/')
        val builder = StringBuilder("smb://")
        builder.append(host)
        if (settings.port != 445) builder.append(':').append(settings.port)
        builder.append('/').append(sanitizeSegment(settings.share.trim('/')))

        val clean = relativePath.trim().trim('/')
        if (clean.isNotEmpty()) {
            clean.split('/')
                .filter { it.isNotEmpty() }
                .forEach { segment -> builder.append('/').append(sanitizeSegment(segment)) }
        }
        if (directory) builder.append('/')
        return builder.toString()
    }
}
