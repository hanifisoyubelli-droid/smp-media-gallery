package com.smpmedia.data.image

import android.util.Log
import coil.ImageLoader

/**
 * Bellek sigortası.
 *
 * Neden gerekli? 7.691 dosyalık Nextcloud tüneline girildiğinde uygulama
 * `OutOfMemoryError` ile ÇÖKÜP yeniden açılıyor, açılır açılmaz yine
 * çöküyordu ("SMP Media sürekli olarak duruyor"). Android'de yakalanmayan
 * `OutOfMemoryError` süreci öldürür; kullanıcı için bu "uygulama bozuk"
 * demektir. Oysa tek bir küçük resim üretilememiş olması kabul edilebilir
 * bir durumdur: hücre yer tutucu çizer, görsel sonra tekrar denenir.
 *
 * Bu yüzden fetcher OOM'u YAKALAR, buradaki sigortayı çalıştırır (Coil
 * bellek önbelleğini boşaltır) ve sıradan bir IOException'a çevirir.
 */
object ImageMemoryGuard {

    private const val TAG = "SmpMedia"

    @Volatile
    private var loader: ImageLoader? = null

    /** [com.smpmedia.di.ImageLoaderModule] tek örneği kurulduktan sonra çağırır. */
    fun register(imageLoader: ImageLoader) {
        loader = imageLoader
    }

    /**
     * Bellek tükendiğinde çağrılır: çözülmüş küçük resimlerin tuttuğu yeri
     * derhal serbest bırakır. Disk önbelleği (ThumbnailCache) SİLİNMEZ —
     * orası kalıcı kazanç, bellek baskısıyla ilgisi yok.
     */
    fun trim() {
        runCatching { loader?.memoryCache?.clear() }
            .onFailure { Log.w(TAG, "Bellek önbelleği boşaltılamadı", it) }
    }
}
