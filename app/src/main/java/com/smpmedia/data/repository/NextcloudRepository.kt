package com.smpmedia.data.repository

import android.util.Log
import android.util.Xml
import com.smpmedia.data.model.NextcloudSettings
import com.smpmedia.data.model.RemoteEntry
import com.smpmedia.data.net.NextcloudTls
import com.smpmedia.data.prefs.NextcloudSettingsStore
import com.smpmedia.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nextcloud WebDAV istemcisi.
 *
 * ## Neden SMB istemcisinden ayrı bir sınıf
 * SMB (jcifs-ng) ile HTTP (OkHttp) arasında paylaştırılacak bir kod yok;
 * taşıma katmanları tamamen farklı. Ortak olan şey ÜRETTİKLERİ şeydir:
 * [RemoteEntry] listesi. Böylece taramayı tüketen üst katman kaynak nedir
 * bilmez.
 *
 * ## ÖLÇÜLEREK alınmış üç tasarım kararı
 * Bu sunucu (Termux üzerinde PHP) üzerinde yapılan gerçek ölçümler:
 *  - her PHP isteği sabit ~0,7–4,5 sn (kimlik doğrulaması olmayan isteklerde bile)
 *  - 3–8 eşzamanlı istek PHP-FPM'i düşürüyor → nginx HTTP 502
 *  - kısa sürede çok sayıda kimlikli istek → HTTP 412 (brute-force koruması)
 *
 * Bu yüzden:
 *  1. **SERİ DİSİPLİN**: aynı dosya/klasör üzerinde eşzamanlı istek yok.
 *     SMB tarafındaki "tek bağlantı" kuralının HTTP karşılığı. Küçük resimler
 *     için de geçerli; kullanıcı şartı zaten "istekleri asla fan-out etme".
 *  2. **BACKOFF**: 412/429/502/503 görürsek istek yeniden DENENMEZ, bir
 *     anahtar bozulup üst katmana bildirilir ve sonra yeniden denenir.
 *     Israrla yeniden denemek korumayı daha da sertleştirir.
 *  3. **AYNI ANDA TEK İSTEK**: [requestGate] semaförü. Ağaç taraması ile
 *     küçük resim trafiği aynı anda koşarsa sunucu 502 veriyor; sıraya
 *     alınıyor.
 */
@Singleton
class NextcloudRepository @Inject constructor(
    private val settingsStore: NextcloudSettingsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    private val clientLock = Any()
    @Volatile private var sharedClient: OkHttpClient? = null
    @Volatile private var clientSettingsHash: Int = 0

    /**
     * Sunucuya aynı anda GİDEN istek sayısı. SMB tarafında bu işi modem
     * yapıyordu (ikinci bağlantıyı reddediyordu); burada yapıyoruz çünkü
     * kanıtlandı ki 3+ eşzamanlı istek PHP-FPM'i düşürüyor.
     */
    private val requestGate = kotlinx.coroutines.sync.Semaphore(REQUESTS_IN_FLIGHT)

    /**
     * Uzun GET akışları (görüntüleyici tam boyu, video indirmesi, küçük
     * resim tam dosya okuması) için AYRI tek izin. Akış açık kaldığı sürece
     * PHP işçisi meşguldür; izin akış kapanınca iade edilir (bkz. openStream).
     */
    private val streamGate = kotlinx.coroutines.sync.Semaphore(1)

    /** Kısa önbellek: aynı klasör iki kez tarandığında sunucuya tekrar gidilmez. */
    private val listingCache = ConcurrentHashMap<String, CachedListing>()

    /** PHP'nin yavaşlığı ölçüldü: okuma zaman aşımı cömert olmalı. */
    private fun buildClient(settings: NextcloudSettings): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Keep-alive: TCP kurulumunu tekrar tekrar ödemeyiz. PHP maliyetini
            // düşürmez ama en azından el sıkışma bedelini kaldırır.
            .connectionPool(
                okhttp3.ConnectionPool(
                    MAX_IDLE_CONNECTIONS,
                    KEEP_ALIVE_SECONDS,
                    TimeUnit.SECONDS
                )
            )
            // 401'i yut: OkHttp'in otomatik yeniden denemesi brute-force
            // sayacını doldurur. Hata üst katmana tek seferde iletilir.
            .authenticator { _, _ -> null }
            .retryOnConnectionFailure(false)

        // Sunucu sertifikası sistem kökleriyle doğrulanamıyorsa (kendi kendine
        // imzalı / özel CA) kullanıcı izin VERMİŞSE kabul edilir. Ana makine adı
        // doğrulaması her zaman açık. Ayrıntı: NextcloudTls.
        NextcloudTls.install(builder) { settings.allowSelfSigned }
        return builder.build()
    }

    /** Ayarlar değiştiyse istemci yeniden kurulur (aynı adresse korunur). */
    private fun client(): OkHttpClient {
        val settings = settingsStore.current()
        val hash = listOf(settings.baseUrl, settings.username, settings.allowSelfSigned).hashCode()
        sharedClient?.let { if (clientSettingsHash == hash) return it }
        synchronized(clientLock) {
            sharedClient?.let { if (clientSettingsHash == hash) return it }
            runCatching { sharedClient?.connectionPool?.evictAll() }
            return buildClient(settings).also {
                sharedClient = it
                clientSettingsHash = hash
            }
        }
    }

    fun isConfigured(): Boolean = settingsStore.current().isConfigured

    // ── Yardımcılar ────────────────────────────────────────────────────

    /**
     * WebDAV yolu → tam URL.
     *
     * Adlar URL'e kodlanır (Türkçe karakterler ve boşluk zorunlu). SMB
     * tarafının tam TERSİ: jcifs-ng yüzde kodlarını geri çözmez, Nextcloud
     * ise çözer. Aynı yardımcı iki tarafta da kullanılamaz.
     */
    private fun fileUrl(settings: NextcloudSettings, relativePath: String): String {
        val builder = StringBuilder(settings.davBase)
        builder.append('/')
        relativePath.trim().trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
            .forEach { segment ->
                builder.append(encode(segment)).append('/')
            }
        // Sondaki fazla '/' WebDAV'da sorun değil; PROPFIND klasör ister,
        // GET dosya ister — çağıran tarafla tutarlı bırakıyoruz.
        return builder.toString().trimEnd('/')
    }

    private fun encode(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private fun auth(settings: NextcloudSettings): String =
        Credentials.basic(settings.username, settings.appPassword, Charsets.UTF_8)

    /**
     * Tek bir HTTP isteğini SERİ olarak yürütür. [block] içinde gövde okunur;
     * Response okuyucu bloğu bitince otomatik kapatılır.
     */
    private suspend fun <T> execute(request: Request, block: (okhttp3.Response) -> T): T =
        requestGate.withPermit {
            client().newCall(request).execute().use(block)
        }

    // ── Bağlantı testi ────────────────────────────────────────────────

    /**
     * Ayarlar ekranındaki "Bağlantıyı test et".
     * Önce sunucunun kendisine bakılır (kimlik istemez, ucuzdur), sonra
     * WebDAV kökü listelenir. İki adım ayrı ayrı raporlanır ki kullanıcı
     * "sunucu yok" ile "şifre yanlış"ı karıştırmasın.
     */
    suspend fun testConnection(settings: NextcloudSettings): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                if (!settings.isConfigured) {
                    throw IOException("Adres, kullanıcı ve uygulama şifresi zorunlu.")
                }
                // 1) Sunucu ayakta mı?
                val statusRequest = Request.Builder()
                    .url("${settings.origin}/status.php")
                    .get()
                    .build()
                client().newCall(statusRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            "Nextcloud yanıt vermedi (HTTP ${response.code})."
                        )
                    }
                }
                // 2) WebDAV erişilebilir mi, kimlik kabul ediliyor mu?
                val request = Request.Builder()
                    .url("${settings.davBase}/")
                    .header("Authorization", auth(settings))
                    .header("Depth", "0")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                    .build()
                client().newCall(request).execute().use { response ->
                    when (response.code) {
                        401, 403 -> throw IOException(
                            "Kabul edilmedi (HTTP ${response.code}). Uygulama şifresini kontrol edin."
                        )

                        207 -> Unit
                        else -> throw IOException("WebDAV beklenmedik yanıt verdi: HTTP ${response.code}")
                    }
                }
                "Bağlantı doğrulandı."
            }
        }

    // ── Dizin listeleme ───────────────────────────────────────────────

    /**
     * Tek klasörü listeler (PROPFIND Depth: 1).
     *
     * Nextcloud `Depth: infinity` kabul ETMEZ (performans nedeniyle kilitli),
     * bu yüzden ağaç SMB tarafındaki gibi klasör klasör gezilir. PHP başına
     * ~2,4 sn maliyet olduğundan [LISTING_TTL_MS] boyunca sonuç bellekte
     * tutulur: aynı klasör ızgarada yukarı aşağı kaydırırken tekrar sorgulanmaz.
     */
    suspend fun listDirectory(
        relativePath: String,
        settings: NextcloudSettings = settingsStore.current(),
        useCache: Boolean = true
    ): List<RemoteEntry> = withContext(ioDispatcher) {
        val clean = relativePath.trim().trim('/')
        val cacheKey = clean.lowercase(Locale.ROOT)
        if (useCache) {
            listingCache[cacheKey]?.takeIf {
                System.currentTimeMillis() - it.millis < LISTING_TTL_MS
            }?.let { return@withContext it.entries }
        }

        val url = "${settings.davBase}/" +
            if (clean.isEmpty()) "" else clean.split('/').filter { it.isNotEmpty() }
                .joinToString("/") { encode(it) } + "/"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", auth(settings))
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()

        val entries = execute(request) { response ->
            when {
                response.code == 401 -> throw AuthRejectedException(
                    "Kullanıcı adı veya uygulama şifresi hatalı."
                )

                response.code == 403 -> throw IOException("Bu klasöre erişiminiz yok.")
                response.code == 404 -> throw IOException("Klasör bulunamadı: /$clean")
                response.code == 207 -> Unit
                response.code == 412 || response.code == 429 -> throw ThrottledException(
                    "Sunucu istekleri geçici olarak yavaşlattı (HTTP ${response.code})."
                )

                response.code == 502 || response.code == 503 -> throw ThrottledException(
                    "Nextcloud meşgul (HTTP ${response.code}); lütfen biraz sonra yine deneyin."
                )

                else -> throw IOException("Listeleme başarısız: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IOException("Sunucu boş yanıt verdi.")
            parseMultistatus(body, clean, settings.username)
        }
        listingCache[cacheKey] = CachedListing(entries, System.currentTimeMillis())
        entries.sortedWith(
            compareByDescending<RemoteEntry> { it.isDirectory }
                .thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    /**
     * d:multistatus cevabını ayrıştırır.
     *
     * ÖNEMLİ: Nextcloud, bir propstat bloğunun <d:status> etiketini
     * özelliklerden SONRA yazar. Yani "bu değer gerçekten bulundu mu?"
     * sorusu değer okunurken bilinemez. Bu yüzden değerler önce bir tamponda
     * biriktirilir, propstat bittiğinde status 200 ise kayda işlenir.
     * Doğrudan kayda yazsaydık, "404 Not Found" dönen boş <oc:fileid/>
     * etiketi kimliği 0 yapardı — zararsız görünür ama sunucu tarafı
     * önizleme API'si sonra VAR OLMAYAN bir dosyayı ister.
     *
     * İlk kayıt listelenen klasörün KENDİSİDİR ve [buildEntry] içinde elenir.
     */
    private fun parseMultistatus(
        xml: String,
        parentPath: String,
        userPrefix: String
    ): List<RemoteEntry> {
        val result = ArrayList<RemoteEntry>()
        runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(xml.byteInputStream(), null)

            // Yanıt düzeyinde KESİNLEŞMİŞ alanlar.
            var inResponse = false
            var href = ""
            var length = ""
            var lastModified = ""
            var fileid = ""
            var isCollection = false

            // propstat tamponu: status okunana kadar değere güvenilmez.
            var inPropstat = false
            var statusOk = false
            var bufLength = ""
            var bufModified = ""
            var bufFileid = ""

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "response" -> {
                            inResponse = true
                            href = ""
                            length = ""
                            lastModified = ""
                            fileid = ""
                            isCollection = false
                        }

                        "propstat" -> {
                            inPropstat = true
                            statusOk = false
                            bufLength = ""
                            bufModified = ""
                            bufFileid = ""
                        }
                        // href YALNIZCA response'un doğrudan çocuğuyken
                        // dosyanın kimliğidir; propstat içindeki href hata
                        // metinlerinin içinde de geçebilir.
                        "href" -> if (inResponse && !inPropstat) {
                            href = parser.nextText().safeDecode()
                        }

                        "status" -> if (inPropstat) {
                            statusOk = parser.nextText().contains("200")
                        }

                        "getcontentlength" -> if (inPropstat) bufLength = parser.nextText()
                        "getlastmodified" -> if (inPropstat) bufModified = parser.nextText()
                        "fileid" -> if (inPropstat) bufFileid = parser.nextText()
                        "collection" -> if (inResponse) isCollection = true
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    when (parser.name) {
                        "propstat" -> {
                            if (inPropstat && statusOk) {
                                if (bufLength.isNotEmpty()) length = bufLength
                                if (bufModified.isNotEmpty()) lastModified = bufModified
                                if (bufFileid.isNotEmpty()) fileid = bufFileid
                            }
                            inPropstat = false
                        }

                        "response" -> if (inResponse) {
                            inResponse = false
                            buildEntry(
                                href = href,
                                parentPath = parentPath,
                                userPrefix = userPrefix,
                                length = length,
                                lastModified = lastModified,
                                fileid = fileid,
                                isCollection = isCollection
                            )?.let(result::add)
                        }
                    }
                }
                event = parser.next()
            }
        }.onFailure {
            Log.w("SmpMedia", "WebDAV yanıtı ayrıştırılamadı ($parentPath): $it")
        }
        return result
    }

    private fun buildEntry(
        href: String,
        parentPath: String,
        userPrefix: String,
        length: String,
        lastModified: String,
        fileid: String,
        isCollection: Boolean
    ): RemoteEntry? {
        // href "/remote.php/dav/files/ayse/Fotos/2019/" biçimindedir.
        // Kullanıcı adı segmenti ("ayse/") YOLUN PARÇASI DEĞİLDİR: WebDAV
        // tabanı zaten kullanıcı köküdür. Soyulmazsa tarama "ayse/Fotos/…"
        // diye istek atıp 404 alır (ölçüldü).
        val marker = "/files/"
        val tail = href.substringAfter(marker, "")
        if (tail.isEmpty()) return null
        val user = userPrefix.trim('/')
        val relative = if (user.isNotEmpty() && tail.startsWith("$user/")) {
            tail.substring(user.length + 1)
        } else {
            tail
        }
        val path = relative.trim('/')
        if (path.isEmpty()) return null // listelenen klasörün kendisi
        val name = path.substringAfterLast('/')
        if (name.isBlank()) return null
        // Aynı klasörün kendisi tekrar geldiyse elenir.
        if (path == parentPath.trim('/')) return null
        return RemoteEntry(
            name = name,
            path = path,
            isDirectory = isCollection,
            sizeBytes = length.toLongOrNull() ?: 0L,
            lastModified = parseHttpDate(lastModified),
            fileId = fileid.toLongOrNull() ?: 0L
        )
    }

    /** "Fri, 20 Nov 2026 21:15:58 GMT" → epoch ms. RFC 1123. */
    private fun parseHttpDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.parse(raw.trim())?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun String.safeDecode(): String =
        runCatching { java.net.URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    // ── Özyinelemeli tarama ───────────────────────────────────────────

    /**
     * Kökten başlayarak tüm medya dosyalarını toplar. SMB tarafındaki
     * [SmbRepository.listImagesRecursive] ile aynı sözleşme: düz liste,
     * son değiştirilmeye göre azalan.
     *
     * PHP maliyeti klasör başınadır; bu yüzden tarama SERİdir ve bir klasör
     * okunamazsa tarama DURMAZ, o dal atlanır.
     */
    suspend fun listImagesRecursive(
        rootPath: String = "",
        maxDepth: Int = 8,
        maxFiles: Int = 20000,
        settings: NextcloudSettings = settingsStore.current()
    ): Result<List<RemoteEntry>> = withContext(ioDispatcher) {
        runCatching {
            val result = ArrayList<RemoteEntry>()
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(rootPath.trim().trim('/') to 0)
            var dirsVisited = 0
            var failures = 0
            var throttled = 0

            while (queue.isNotEmpty() && result.size < maxFiles) {
                val (path, depth) = queue.removeFirst()
                if (depth > maxDepth) continue
                val entries = try {
                    dirsVisited++
                    listDirectory(path, settings)
                } catch (t: Throwable) {
                    // Kimlik reddedildi: kalan klasörleri denemek yalnızca
                    // brute-force sayacını büyütür. Taramayı HEMEN durdur.
                    if (t is AuthRejectedException) throw t
                    if (t is ThrottledException) {
                        throttled++
                        // Sunucu yavaşlattı: klasörü sona ekle, kısa mola ver.
                        if (throttled <= MAX_THROTTLE_RETRIES) {
                            queue.addLast(path to depth)
                            delay(THROTTLE_BACKOFF_MS)
                        } else {
                            failures++
                        }
                    } else {
                        failures++
                        if (failures <= 15) {
                            Log.w("SmpMedia", "Nextcloud taraması /$path → $t")
                        }
                    }
                    emptyList()
                }
                for (entry in entries) {
                    if (result.size >= maxFiles) break
                    if (entry.isDirectory) {
                        queue.add(entry.path to depth + 1)
                    } else if (isImageFile(entry.name) || SmbRepository.isVideoFile(entry.name)) {
                        result.add(entry)
                    }
                }
            }
            Log.d(
                "SmpMedia",
                "Nextcloud tarama: kök=/$rootPath gezilen=$dirsVisited " +
                    "başarısız=$failures yavaşlatma=$throttled dosya=${result.size}"
            )
            result.sortedByDescending { it.lastModified }
        }
    }

    // ── Dosya okuma ───────────────────────────────────────────────────

    /**
     * Dosyanın BİR BÖLÜMÜNÜ okur (HTTP Range). Küçük resim için dosyanın
     * tamamını indirmenin yolu budur: EXIF başlığı + ilk sıkıştırılmış
     * blok dosyanın başındadır.
     *
     * Sunucu Range'i desteklemezse (HTTP 200) tüm gövde döner; çağıran
     * [maxBytes] kadarını kullandığı için sonuç yine doğrudur.
     */
    suspend fun readRange(
        relativePath: String,
        from: Long,
        to: Long,
        settings: NextcloudSettings = settingsStore.current()
    ): ByteArray = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(fileUrl(settings, relativePath))
            .header("Authorization", auth(settings))
            .header("Range", "bytes=$from-$to")
            .get()
            .build()
        execute(request) { response ->
            when {
                response.code == 404 -> throw IOException("Dosya bulunamadı: /$relativePath")
                response.code == 401 -> throw IOException("Uygulama şifresi kabul edilmedi.")
                response.code == 412 || response.code == 429 ||
                    response.code == 502 || response.code == 503 ->
                    throw ThrottledException("Nextcloud yoğun (HTTP ${response.code}).")

                !response.isSuccessful -> throw IOException(
                    "Okuma başarısız: HTTP ${response.code}"
                )
            }
            val body = response.body ?: throw IOException("Boş yanıt.")
            val limit = (to - from + 1).coerceAtMost(MAX_RANGE_BYTES).toInt()
            body.byteStream().use { input -> readUpTo(input, limit) }
        }
    }

    /** Tam dosyayı okur (görüntüleyici/dışa aktarma). Akış SERİ kullanım içindir.
     *
     * SERİLİK BURADA DA KORUNUR: PHP-FPM yanıtı akış boyunca meşgul eder
     * (nginx statik değil, PHP'den geçiyor). Akış [streamGate] izniyle açılır;
     * izin, akış KAPATILDIĞINDA iade edilir. Böylece aynı anda en fazla bir
     * uzun indirme koşar. Küçük istekler (PROPFIND/Range) kendi kapısından
     * geçtiği için toplam eşzamanlılık en fazla 2'de kalır — ölçülen çökme
     * eşiği (3+) aşılır.
     */
    suspend fun openStream(
        relativePath: String,
        settings: NextcloudSettings = settingsStore.current()
    ): InputStream = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(fileUrl(settings, relativePath))
            .header("Authorization", auth(settings))
            .get()
            .build()
        streamGate.acquire()
        val response = try {
            client().newCall(request).execute()
        } catch (t: Throwable) {
            streamGate.release()
            throw t
        }
        if (!response.isSuccessful) {
            val code = response.code
            runCatching { response.close() }
            streamGate.release()
            throw when (code) {
                404 -> IOException("Dosya bulunamadı: /$relativePath")
                401 -> IOException("Uygulama şifresi kabul edilmedi.")
                412, 429, 502, 503 -> ThrottledException("Nextcloud yoğun (HTTP $code).")
                else -> IOException("İndirme başarısız: HTTP $code")
            }
        }
        val raw = response.body?.byteStream() ?: run {
            runCatching { response.close() }
            streamGate.release()
            throw IOException("Sunucu boş yanıt verdi.")
        }
        // İzin YALNIZCA bir kez iade edilir (close iki kez çağrılabilir).
        // Çağıran akışı hiç kapatmazsa izin düşer — ama tüm çağrı
        // noktalarımız use { } / ForwardingSource.close ile kapatıyor.
        var released = false
        object : java.io.FilterInputStream(raw) {
            override fun close() {
                runCatching { raw.close() }
                runCatching { response.close() }
                if (!released) {
                    released = true
                    streamGate.release()
                }
            }
        }
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val buffer = java.io.ByteArrayOutputStream(minOf(limit, 128 * 1024))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val want = minOf(chunk.size, limit - total)
            val read = input.read(chunk, 0, want)
            if (read < 0) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toByteArray()
    }

    // ── Silme ─────────────────────────────────────────────────────────

    /** Görüntüleyicinin çöp kutusu: dosyayı sunucudan siler. */
    suspend fun delete(
        relativePath: String,
        settings: NextcloudSettings = settingsStore.current()
    ): Boolean = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(fileUrl(settings, relativePath))
            .header("Authorization", auth(settings))
            .delete()
            .build()
        runCatching {
            execute(request) { response ->
                val ok = response.code == 204 || response.code == 200 || response.code == 205
                if (ok) {
                    // Silinen dosyanın klasör kaydı bayatladı.
                    listingCache.remove(relativePath.substringBeforeLast('/', "").lowercase(Locale.ROOT))
                }
                ok
            }
        }.onFailure {
            Log.w("SmpMedia", "Nextcloud silme başarısız: $relativePath", it)
        }.getOrDefault(false)
    }

    /** Bayatlamış tüm listeleme kayıtlarını düşürür (elle yenilemede). */
    fun invalidateListings() = listingCache.clear()

    // ── Görüntüleyici/yedekleme yardımcıları ──────────────────────────

    /**
     * Dosya boyutunu HEAD isteğiyle öğrenir (Content-Length). İndirme
     * ilerlemesinin yüzdede gösterilmesi gerekir; WebDAV PROPFIND'ın tamamı
     * tek soruluk HEAD'den pahalıdır. Bilinemeyen boyut 0 döner — arayüz
     * ilerlemesiz "İndiriliyor…" gösterir.
     */
    suspend fun fileSize(
        relativePath: String,
        settings: NextcloudSettings = settingsStore.current()
    ): Long = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(fileUrl(settings, relativePath))
            .header("Authorization", auth(settings))
            .head()
            .build()
        runCatching {
            execute(request) { response ->
                if (response.isSuccessful) response.body?.contentLength() ?: 0L else 0L
            }
        }.getOrDefault(0L)
    }

    /**
     * Sunucu İÇİ dosya kopyalar (HTTP COPY): dosya telefona inmeden, sunucu
     * diskinden diske gider. "Ekle" eyleminin Nextcloud karşılığı — SMB
     * tarafındaki akış kopyasının ucuz alternatifi.
     */
    suspend fun copyFile(
        fromPath: String,
        toPath: String,
        settings: NextcloudSettings = settingsStore.current()
    ): Boolean = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(fileUrl(settings, fromPath))
            .header("Authorization", auth(settings))
            // Destination tam URL olmalı ve kodlanmış kalmalı (dosya adı
            // Türkçe/boşluklu olabilir — fileUrl zaten kodluyor).
            .header("Destination", fileUrl(settings, toPath))
            .header("Overwrite", "F")
            .method("COPY", null)
            .build()
        runCatching {
            execute(request) { response ->
                response.code == 201 || response.code == 204 || response.code == 205
            }
        }.onFailure {
            Log.w("SmpMedia", "Nextcloud kopyalama başarısız: $fromPath → $toPath", it)
        }.getOrDefault(false)
    }

    /**
     * Klasör yolunun her kademesini MKCOL ile kurar (mkdir -p). Sabre var
     * olan klasör için 405 döner; bu NORMAL sayılır ve hata değildir.
     */
    suspend fun ensureFolders(
        folderPath: String,
        settings: NextcloudSettings = settingsStore.current()
    ): Boolean = withContext(ioDispatcher) {
        val segments = folderPath.trim().trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
        var built = true
        for (index in segments.indices) {
            val partial = segments.take(index + 1).joinToString("/") { encode(it) }
            val request = Request.Builder()
                .url("${settings.davBase}/$partial/")
                .header("Authorization", auth(settings))
                .method("MKCOL", null)
                .build()
            val ok = runCatching {
                execute(request) { response ->
                    response.code == 201 || response.code == 405 || response.code == 204
                }
            }.getOrDefault(false)
            if (!ok) {
                built = false
                break
            }
        }
        built
    }

    /**
     * Küçük dosyayı YENİ olarak yükler (PUT). Tam boyut bilindiği için
     * chunked transfer KULLANILMAZ — nginx/PHP katmanı chunked PUT'u
     * bazen reddediyor; bellekten tek gövde daha güvenli. "Ekle" fotoğrafları
     * (birkaç MB) içindir; video gibi dev dosyalar tasarlanmamıştır.
     */
    suspend fun upload(
        relativePath: String,
        bytes: ByteArray,
        settings: NextcloudSettings = settingsStore.current()
    ): Boolean = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url(fileUrl(settings, relativePath))
            .header("Authorization", auth(settings))
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        runCatching {
            execute(request) { response ->
                val ok = response.code == 201 || response.code == 204
                if (ok) {
                    listingCache.remove(
                        relativePath.substringBeforeLast('/', "").lowercase(Locale.ROOT)
                    )
                }
                ok
            }
        }.onFailure {
            Log.w("SmpMedia", "Nextcloud yükleme başarısız: $relativePath", it)
        }.getOrDefault(false)
    }

    companion object {
        /** Görsel kabul edilen uzantılar — SMB tarafıyla AYNI liste. */
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp"
        )

        fun isImageFile(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTENSIONS

        /**
         * WebDAV istenen özellikler.
         *  - getcontentlength/getlastmodified: zaman tüneli sırası
         *  - resourcetype               : klasör mü dosya mı
         *  - oc:fileid                  : sunucu tarafı önizleme API'si için
         */
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""" +
                """<d:prop><d:getcontentlength/><d:getlastmodified/>""" +
                """<d:resourcetype/><oc:fileid/></d:prop></d:propfind>"""

        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        /** Sunucu aynı anda EN FAZLA bu kadar istek kaldırabiliyor (ölçüldü). */
        private const val REQUESTS_IN_FLIGHT = 1

        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val READ_TIMEOUT_MS = 120_000L
        private const val WRITE_TIMEOUT_MS = 60_000L
        private const val MAX_IDLE_CONNECTIONS = 2
        private const val KEEP_ALIVE_SECONDS = 60L

        /** Klasör listeleme önbelleği süresi. */
        private const val LISTING_TTL_MS = 10 * 60_000L

        /** Range üst sınırı: 2 MB'tan büyük bölge istenmez. */
        private const val MAX_RANGE_BYTES = 2L * 1024L * 1024L

        private const val MAX_THROTTLE_RETRIES = 3
        private const val THROTTLE_BACKOFF_MS = 4_000L

        /**
         * Kullanıcıya gösterilecek Türkçe hata mesajı ([SmbRepository.describeError]
         * ile aynı görev; Nextcloud hataları zaten Türkçe üretildiği için burada
         * yalnızca taşıma hataları çevrilir).
         */
        fun describeError(error: Throwable): String {
            var root: Throwable = error
            var depth = 0
            while (root.cause != null && root.cause !== root && depth < 10) {
                root = root.cause!!
                depth++
            }
            val raw = root.message.orEmpty()
            return when {
                root is ThrottledException || error is ThrottledException ->
                    raw.ifBlank { "Nextcloud geçici olarak yavaşlattı; birazdan deneyin." }

                root is UnknownHostException ->
                    "Nextcloud sunucusu bulunamadı. Adresi kontrol edin."

                root is ConnectException ->
                    "Nextcloud'a bağlanılamadı. Sunucu açık mı, port doğru mu?"

                root is SocketTimeoutException ->
                    "Nextcloud yanıt vermedi (zaman aşımı). Sunucu yoğun olabilir."

                // Sertifika adı uyuşmuyor: "Trust my own certificate" anahtarı
                // açılsa bile bu hata DÜŞER — bilinçli davranış (bkz. NextcloudTls).
                root is javax.net.ssl.SSLPeerUnverifiedException ->
                    "Sertifika bu sunucu adı için düzenlenmemiş (SAN/CN uyuşmuyor)."

                root is javax.net.ssl.SSLException ->
                    raw.ifBlank { "Sunucu ile TLS el sıkışması başarısız oldu." }

                else -> raw.ifBlank { "Bilinmeyen Nextcloud hatası" }
            }
        }
    }

    private data class CachedListing(val entries: List<RemoteEntry>, val millis: Long)
}

/**
 * Sunucu istek akışını geçici olarak kesti (412/429/502/503).
 *
 * Ayırt edilmesinin nedeni: bu hata "kullanıcının yapacağı bir şey yok"
 * hatası DEĞİLDİR. Israrla yeniden denemek Nextcloud'un brute-force
 * korumasını sertleştirir (ölçüldü: art arda istekler sonrası 412). Üst
 * katman bunu "birazdan tekrar dene" olarak gösterir, kırmızı ızgaraya
 * dönüştürmez.
 */
class ThrottledException(message: String) : IOException(message)

/**
 * Sunucu kimlik bilgilerini REDDETTİ (HTTP 401). Tarama bu hatada devam
 * ETMEZ: yanlış şifreyle 241 klasörü tek tek denemek, Nextcloud'un kaba
 * kuvvet korumasını besleyip doğru şifreli isteklerin de reddedilmesine yol
 * açıyordu (ölçüldü: açılışta boş şifreyle tarama → sonraki testte 401).
 */
class AuthRejectedException(message: String) : IOException(message)
