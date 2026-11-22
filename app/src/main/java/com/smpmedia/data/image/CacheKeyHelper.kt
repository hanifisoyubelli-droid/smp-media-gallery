package com.smpmedia.data.image

import com.smpmedia.data.model.MediaSourceKind

/**
 * Coil önbellek anahtarlarını üretir.
 *
 * Anahtara dosya yolunun yanında son değiştirilme tarihini (ve yerel
 * dosyalarda boyutu) de ekliyoruz. Böylece SMB'deki bir dosya güncellenince
 * eski anahtar geçersiz kalır ve Coil yeni sürümü indirir; eski bayat görsel
 * gösterilmez.
 */
object CacheKeyHelper {

    /**
     * Uzaktan dosya: yol + son değiştirilme tarihi + KAYNAK.
     * v4: video karelerinde dönüş açısı artık tkhd matrisinden okunuyor
     * (yalnızca MMR'e güvenen eski kareler yan görünüyordu); önceki tüm
     * küçük resimler bu etiketle geçersiz kılınır.
     *
     * Anahtar SUNUCU İSMİ ile başlar: aynı dosya adı hem SMB paylaşımında hem
     * Nextcloud'da varsa (kullanıcının arşivi ikisinde de var) iki farklı
     * dosyanın küçük resmi çakışmaz.
     */
    fun smbKey(
        path: String,
        lastModified: Long,
        source: MediaSourceKind = MediaSourceKind.SMB
    ): String {
        val scheme = when (source) {
            MediaSourceKind.SMB -> "smb://"
            MediaSourceKind.NEXTCLOUD -> "nc://"
        }
        return "$scheme$path?m=$lastModified&v=4"
    }

    /** Yerel MediaStore dosyası: content URI + tarih + boyut. */
    fun localKey(uri: String, dateModified: Long, sizeBytes: Long): String =
        "local:$uri?m=$dateModified&s=$sizeBytes"

    /**
     * Izgara küçük resmi için bellek anahtarı.
     * Disk anahtarı ortaktır (kaynak dosya tek), bellek anahtarı ise boyuta
     * göre ayrılır; böylece 200x200 bitmap tam ekran isteğiyle karışmaz.
     */
    fun thumbnailMemoryKey(baseKey: String): String = "$baseKey@thumb200"

    /** Tam ekran görüntüleyici için bellek anahtarı. */
    fun fullMemoryKey(baseKey: String): String = "$baseKey@full"

    /**
     * Progressive placeholder için çok küçük (20x20) bellek anahtarı.
     * Aynı kaynağın disk anahtarından ayrıdır; böylece net küçük resim
     * geldiğinde placeholder ile çakışmaz.
     */
    fun placeholderMemoryKey(baseKey: String): String = "$baseKey@placeholder20"

    /** Progressive placeholder için disk anahtarı (200x200 kaynağından ayrı). */
    fun placeholderDiskKey(baseKey: String): String = "$baseKey#placeholder20"
}
