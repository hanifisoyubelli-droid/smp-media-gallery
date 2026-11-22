package com.smpmedia.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yerel küçük resim önbelleği.
 *
 * Google Photos'un akıcılığının sırrı, bir kez çözülen küçük resimleri
 * cihazda saklayıp sonraki kaydırmalarda ağ hiç kullanmadan göstermesidir.
 * SMB üzerinden gelen fotoğraflar için de aynı mantık uygulanır:
 *
 * - İlk görüntüleme: SMB'den tam dosya okunur, 200x200 küçültülür,
 *   bu önbelleğe WebP olarak yazılır ve Coil disk önbelleğine de kaydedilir.
 * - Sonraki görüntülemeler: 200x200 WebP doğrudan diskten okunur
 *   (~5 KB, milisaniyeler içinde). SMB'ye hiç gidilmez.
 *
 * Önbellek boyutu 10 GB ile sınırlıdır; sınır aşılınca en eski erişilenler
 * LRU benzeri temizlikle atılır. Her küçük resim ~5-25 KB olduğundan 10 GB
 * ≈ 800.000 fotoğraf eder; 1 TB'lık (≈200.000 fotoğraflık) kütüphanenin
 * TAMAMI bir kez gezildikten sonra diskte kalmaya devam eder.
 */
@Singleton
class ThumbnailCache @Inject constructor(
    @ApplicationContext context: Context
) {
    /**
     * Kalıcı depoda durur; sistem bu klasörü kendiliğinden SİLMEZ.
     * Yer kontrolü tamamen [trimIfNeeded]in kendi sınırıyle yapılır.
     */
    private val cacheRoot: File = MediaCache.thumbs(context)

    /** Maksimum önbellek boyutu (bayt). 10 GB ≈ 800.000 küçük resim. */
    private val maxBytes = 10L * 1024L * 1024L * 1024L

    /**
     * Önbellekten küçük resmi okur. Yoksa null döner.
     * @param baseCacheKey [CacheKeyHelper] ile üretilen anahtar.
     * @param maxSize Decode edilecek maksimum boyut (piksel).
     */
    fun get(baseCacheKey: String, maxSize: Int = GRID_THUMBNAIL_SIZE): Bitmap? {
        val file = fileFor(baseCacheKey) ?: return null
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, options)?.also {
                // Dosyanın erişim zamanını güncelle (LRU temizlik için).
                file.setLastModified(System.currentTimeMillis())
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Küçük resim diskte VAR MI? (Çözmez — ısıtma döngüsü binlerce kez sorar.) */
    fun exists(baseCacheKey: String): Boolean {
        val file = fileFor(baseCacheKey) ?: return false
        return file.exists() && file.length() > 0L
    }

    /** Küçük resmi önbelleğe yazar. */
    fun put(baseCacheKey: String, bitmap: Bitmap) {
        val file = fileFor(baseCacheKey) ?: return
        try {
            file.outputStream().use { out ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 80, out)
            }
            file.setLastModified(System.currentTimeMillis())
            // Aşırı büyüdüyse eski dosyaları temizle.
            trimIfNeeded()
        } catch (_: Throwable) {
            // Yazma başarısız olsa bile uygulama çalışmaya devam eder.
        }
    }

    /** Anahtarı dosya adına çevirir. */
    private fun fileFor(key: String): File? {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
        return cacheRoot.resolve("$hash.webp")
    }

    /** Toplam boyut sınırı aşıldıysa en eski dosyaları siler. */
    private fun trimIfNeeded() {
        val files = cacheRoot.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return

        // En eski erişim zamanına göre sırala.
        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    /** Tüm önbelleği siler (ayarlar ekranından çağrılabilir). */
    fun clear() {
        cacheRoot.listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Izgara küçük resmi hedef boyutu (GridImage ile aynı). */
        const val GRID_THUMBNAIL_SIZE = 256
    }
}