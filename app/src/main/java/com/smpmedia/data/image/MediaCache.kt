package com.smpmedia.data.image

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Medya önbelleğinin TEK adres kaynağı.
 *
 * Neden `context.cacheDir` DEĞİL?
 * Android `cacheDir` içeriğini "geçici" sayar: telefonun deposu daraldığında
 * sistem bu klasörü kullanıcıya sormadan boşaltır; Ayarlar → "Önbelleği
 * temizle" de aynısını yapar. Yani burada ısıtılmış 30 GB'lık önbellek bir
 * gece içinde sessizce buharlaşabilir ve uygulama tekrar "yavaş galeri" gibi
 * görünür.
 *
 * `getExternalFilesDir()` ise UYGULAMAYA ÖZEL KALICI depodur: sistem onu
 * kendiliğinden temizlemez, yalnızca uygulama kaldırılınca silinir. Google
 * Photos önbelleğini burada tutar; "bir kez açtığım fotoğraf hep anında
 * açılsın" davranışının gerçek temeli budur.
 *
 * Dikkat: kalıcı depo kendiliğinden dolunca sistem bizi kurtarmayacağı için
 * BURAYA yazan her katmanın kendi boyut sınırı olmak zorundadır
 * ([trimOldest], [SHARED_MAX_BYTES]).
 */
object MediaCache {

    private const val ROOT_NAME = "media_cache"
    private const val PREFS = "media_cache_prefs"
    private const val KEY_MIGRATED = "moved_to_persistent_v1"

    /** Eski → yeni kopyalama tamponu; küçük WebP'lerde de verimli olsun. */
    private const val COPY_BUFFER_BYTES = 256 * 1024

    /** Taşıma ilerlemesi bu aralıkla loglanır (ve aynı yerde nefes alınır). */
    private const val PROGRESS_EVERY_FILES = 500L

    /** Izgaranın I/O önceliğini ezmemek için her adımda bekleme. */
    private const val THROTTLE_MS = 120L

    /** SMB küçük resimleri (WebP, ~5-25 KB). Sınırı ThumbnailCache yönetir. */
    const val THUMBS = "thumbs"

    /** Coil'in tam boy kaynak önbelleği (görüntüleyici için). Sınırı Coil yönetir. */
    const val IMAGE_CACHE = "image_cache"

    /** İzlemek/ paylaşmak için NAS'tan indirilen tam dosyalar. */
    const val SHARED = "shared"

    /** Motion Photo JPEG'inden ayıklanan mp4 kırıkları. */
    const val MOTION = "motion"

    /**
     * `shared/` altındaki video/fotoğraf kopyaları için tavan.
     *
     * Eskiden `cacheDir` içindeydi diye sınırı yoktu; sistem zaten bir gün
     * silecekti. Kalıcı depoya taşındığına göre TAVAN ŞART, yoksa birkaç
     * ayda telefonun tamamı video kopyası dolar.
     */
    const val SHARED_MAX_BYTES = 40L * 1024L * 1024L * 1024L

    private val migrating = AtomicBoolean(false)

    @Volatile
    private var rootDir: File? = null

    /**
     * Kalıcı kök: `Android/data/com.smpmedia/files/media_cache`.
     * Dış depo bir şekilde erişilemezse (nadir) uygulama iç depoya düşer;
     * önemli olan çökmemek ve önbelleğin çalışmaya devam etmesidir.
     */
    fun root(context: Context): File {
        rootDir?.let { if (it.isDirectory) return it }
        synchronized(this) {
            rootDir?.let { if (it.isDirectory) return it }
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(base, ROOT_NAME)
            if (!dir.exists()) dir.mkdirs()
            rootDir = dir
            return dir
        }
    }

    /** Kök altındaki alt klasör; yoksa oluşturulur. */
    fun dir(context: Context, name: String): File =
        root(context).resolve(name).apply { if (!exists()) mkdirs() }

    fun thumbs(context: Context): File = dir(context, THUMBS)

    fun imageCache(context: Context): File = dir(context, IMAGE_CACHE)

    fun shared(context: Context): File = dir(context, SHARED)

    fun motion(context: Context): File = dir(context, MOTION)

    /**
     * Küçük resim indirmesinin GEÇİCİ klasörünü boşaltır (açılışta bir kez).
     *
     * [com.smpmedia.data.image.SmbImageFetcher] dosyayı akıştan buraya yazar,
     * çözüp WebP önbelleğine koyduktan sonra SİLER. Uygulama o anda
     * öldürülürse (OOM, sistem, güncelleme) yarım dosya kalır; birkaç ayda
     * onlarca GB olur. cacheDir zaten sistem sıkışınca silebilir, ama emin
     * olduğumuz tek an burası: açılışta hiçbir indirme aktif değildir.
     */
    fun purgeThumbTemp(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "thumb_tmp")
            if (!dir.isDirectory) return
            val files = dir.listFiles() ?: return
            var bytes = 0L
            for (file in files) {
                bytes += file.length()
                file.delete()
            }
            if (files.isNotEmpty()) {
                Log.i("SmpMedia", "Gecici kucuk resim dosyalari temizlendi: " +
                    "${files.size} dosya (${bytes / 1024 / 1024} MB)")
            }
        }
    }

    /**
     * Bir klasördeki en ESKİ dosyalardan başlayarak [maxBytes] altına düşene
     * kadar siler (LRU). [keep] verilirse o dosya asla silinmez — az önce
     * indirilen videonun daha sayfadan çıkmadan yok edilmesini engeller.
     */
    fun trimOldest(directory: File, maxBytes: Long, keep: File? = null) {
        runCatching {
            val files = directory.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            for (file in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                if (keep != null && file.absolutePath == keep.absolutePath) continue
                total -= file.length()
                file.delete()
            }
        }
    }

    /**
     * Eski sürümde `cacheDir` altında biriken önbelleği bir kez kalıcı
     * depoya taşır (kullanıcı ısıttığı küçük resimlerin boşa gitmesini
     * görmez, NAS da boşuna yeniden taranmaz).
     *
     * Neden tek seferde `renameTo` ile klasör taşıyamıyoruz? `cacheDir`
     * gerçek bir ext4 yolu, `getExternalFilesDir` ise FUSE üzerinden görünen
     * ayrı bir bağlama noktası; ikisi arasındaki `rename()` EXDEV verir.
     * O yüzden dosya dosya deniyoruz: önce `renameTo` (aynı filesystem ise
     * anlık), olmazsa kopyala-sil.
     *
     * Coil'in `image_cache` günlüğü (journal) BİLEREK taşınmaz: açık bir
     * günlükle aynı klasöre eski girdileri yazmak önbelleği bozar. Orası
     * zaten izlendikçe kendiliğinden dolan tam boy dosyalar; siliyoruz ve
     * yer geri kazanılıyor.
     *
     * Bu fonksiyondaki loglar bilinçli olarak ASCII yazılır (ş/ı/ö yok):
     * `adb logcat | findstr Onbellek` ile tarayıcıdan filtrelenebilsin.
     */
    fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        if (!migrating.compareAndSet(false, true)) return

        runCatching {
            // Taşıma, ızgara ve SMB küçük resmi yüklemeleriyle I/O kuyruğunda
            // yarışmasın: en düşük öncelikte koşar ve her adımda nefes alır.
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            )
            var movedFiles = 0L
            var movedBytes = 0L
            for (name in listOf(THUMBS, MOTION, SHARED)) {
                val from = File(context.cacheDir, name)
                if (!from.isDirectory) continue
                val to = dir(context, name)
                var dirMoved = 0L
                var dirBytes = 0L
                // walkTopDown tembel: listFiles() 100.000 küçük resim için
                // File nesnelerini bir anda yığına yığardı.
                from.walkTopDown().maxDepth(1).forEach { old ->
                    if (!old.isFile) return@forEach
                    // Yarıda kalan indirme; taşımaya değmez.
                    if (old.name.endsWith(".part") || old.name.endsWith(".tmp")) {
                        old.delete()
                        return@forEach
                    }
                    // Boyutu TAŞIMADAN önce oku: taşınan dosya sorulunca
                    // zaten silinmiş olur ve 0 döner.
                    val size = old.length()
                    if (moveFile(old, to.resolve(old.name))) {
                        dirMoved++
                        dirBytes += size
                        if (dirMoved % PROGRESS_EVERY_FILES == 0L) {
                            Log.i(
                                "SmpMedia",
                                "Onbellek tasiyor [$name]: $dirMoved dosya " +
                                    "(${dirBytes / 1024 / 1024} MB)"
                            )
                            Thread.sleep(THROTTLE_MS)
                        }
                    }
                }
                movedFiles += dirMoved
                movedBytes += dirBytes
                runCatching { from.delete() } // boşaldıysa yok olur, değilse kalır
            }

            val legacyImageCache = File(context.cacheDir, IMAGE_CACHE)
            val reclaimed = if (legacyImageCache.isDirectory) {
                legacyImageCache.listFiles()?.sumOf { it.length() } ?: 0L
            } else {
                0L
            }
            runCatching { legacyImageCache.deleteRecursively() }

            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            Log.i(
                "SmpMedia",
                "Onbellek kalici depoya tasindi: $movedFiles dosya " +
                    "(${movedBytes / 1024 / 1024} MB), eski tam boy onbellek " +
                    "temizlendi (${reclaimed / 1024 / 1024} MB) -> ${root(context)}"
            )
        }.onFailure {
            // Taşıma yarıda kalsa bile önbellek yeni yerde çalışır; eski
            // dosyalar en kötü ihtimalle yer kaplar. Bayrağı YAZMIYORUZ ki
            // bir sonraki açılışta kaldığı yerden denensin.
            Log.w("SmpMedia", "Önbellek taşıması tamamlanamadı", it)
        }
        migrating.set(false)
    }

    /**
     * [source] dosyasını [dest] adresine taşır; başarılıysa kaynağı siler.
     * Boyut BAŞTA okunur: `renameTo` başarılı olursa kaynak artık yoktur ve
     * sonradan sorulan `length()` 0 döner, doğrulama yanlışlıkla patlar.
     */
    private fun moveFile(source: File, dest: File): Boolean = runCatching {
        val size = source.length()
        if (size == 0L) {
            source.delete()
            return@runCatching false
        }
        // Zaten taşınmışsa kaynağı temizlemek yeter.
        if (dest.exists() && dest.length() == size) {
            source.delete()
            return@runCatching false
        }
        if (source.renameTo(dest)) return@runCatching true
        // FUSE üzerinden rename() EXDEV verir → kopyala-sil. Kopya ÖNCE
        // geçici ada yazılır: doğrudan [dest]e yazsaydık, kullanıcı az önce
        // o videoyu açtıysa oynatıcının açık dosyasını ortadan keserdik.
        val tmp = File(dest.parentFile, dest.name + ".migrating")
        runCatching { tmp.delete() }
        source.inputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
        val ok = tmp.length() == size && tmp.renameTo(dest)
        if (ok) source.delete() else runCatching { tmp.delete() }
        ok
    }.getOrDefault(false)
}
