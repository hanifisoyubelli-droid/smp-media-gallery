package com.smpmedia.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import com.smpmedia.util.Mp4Rotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import android.media.ExifInterface

/**
 * Cihazdaki fotoğraf/video için MediaStore'un HAZIR küçük resmini kullanır.
 *
 * Neden? Coil varsayılan olarak content:// adresini tam boy dosya gibi okur:
 * 4-6 MB'lık JPEG açılır, büyük bir bitmap'e çözülür, sonra 256 px'e
 * küçültülür. Kaydırma sırasında onlarca hücre bunu yapınca saniyeler içinde
 * yüzlerce MB çöp oluşuyor ve GC duraklamaları (100+ ms) kareleri düşürüyordu.
 *
 * MediaProvider'ın küçük resim önbelleği zaten cihazda hazırdır;
 * [android.content.ContentResolver.loadThumbnail] bunu birkaç milisaniyede
 * döndürür — ağ yok, tam dosya çözme yok, büyük ara bitmap yok.
 *
 * ── YÖN GARANTİSİ ──
 * MediaStore küçük resmi fotoğraflarda EXIF döndürmesini genelde uygular,
 * ANCAK videolarda dönüş yalnızca `tkhd` matrisindeyse (Pixel 9/10 kamera
 * dosyaları) uygulanmaz ve hücre YAN çıkar. Bu yüzden sonuç doğrulanır:
 * dönüş 90/270 ise ve küçük resim hâlâ YATAY ise burada döndürülür.
 * Böylece "ızgarada yan, açınca düz" sınıfı hatalar kapanır.
 *
 * Yalnızca ≤512 px isteklerde devreye girer; görüntüleyici (tam boy) istekleri
 * varsayılan Coil hattında kalır. API < 29'da yedek olarak akıştan örnekleme
 * ile çözülür.
 */
class MediaThumbnailFetcher(
    private val uri: Uri,
    private val options: Options,
    private val context: Context
) : Fetcher {

    private fun requestedPx(): Int {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: return 0
        val h = (options.size.height as? Dimension.Pixels)?.px ?: return 0
        return maxOf(w, h)
    }

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val px = requestedPx().coerceAtLeast(MIN_PX)

        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(px, px), null)
            }.getOrNull()?.let { forceUpright(it) } ?: decodeFallback(px)
        } else {
            decodeFallback(px)
        }

        DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            // Cihazda hazır durumdaki küçük resim: diskten geldi sayılır.
            dataSource = DataSource.DISK
        )
    }

    /**
     * MediaStore küçük resmi YAN üretmişse düzeltir.
     *
     * Dönüş açısı iki kaynaktan gelir: fotoğraflarda EXIF Orientation,
     * videolarda MP4 `tkhd` matrisi (bkz. [Mp4Rotation]). Görselin zaten dik
     * olduğu durumlarda DOKUNMAZ — çift döndürme olmaz.
     */
    private fun forceUpright(bitmap: Bitmap): Bitmap {
        val rotation = rotationCache[uri] ?: run {
            val r = desiredRotation()
            rotationCache[uri] = r
            r
        }
        if (rotation == 0) return bitmap

        val portrait = bitmap.height > bitmap.width
        // 90/270: MediaStore döndürmeyi uygulamışsa görsel portredir; dokunma.
        if ((rotation == 90 || rotation == 270) && portrait) return bitmap
        // 180: yön farkı boyuttan anlaşılmaz, her zaman uygulanır.
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    /** EXIF (fotoğraf) → MP4 tkhd (video) sırasıyla istenen dönüş açısı. */
    private fun desiredRotation(): Int {
        val exifDeg = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (
                    ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSPOSE -> 90
                    ExifInterface.ORIENTATION_ROTATE_180,
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSVERSE -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        if (exifDeg != 0) return exifDeg
        // Video ise tkhd matrisinden oku (fotoğrafta 0 döner).
        return Mp4Rotation.fromUri(context, uri)
    }

    /**
     * MediaStore küçük resmi üretemezse (bozuk dosya, desteklenmeyen biçim)
     * akıştan örnekleyerek çöz. Tam dosyayı belleğe almamak için
     * iki geçişli (önce sınırlar, sonra örnekleme) yol kullanılır.
     *
     * EXIF döndürmesi burada ZORUNLU olarak uygulanır: MediaStore'a hiç
     * uğramadan çözülen görsel aksi hâlde sensör yönünde (yan) görünür.
     */
    private fun decodeFallback(px: Int): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Görsel çözümlenemedi: $uri")
        }

        val sample = calcInSampleSize(
            bounds.outWidth, bounds.outHeight, px, px
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: throw IOException("Görsel çözümlenemedi: $uri")

        val exifOrientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val oriented = applyOrientation(decoded, exifOrientation)
        // EXIF hiçbir yön söylemediyse video olabilir: tkhd matrisi devreye girer
        // (Pixel kamera videolarında dönüş yalnızca oradadır).
        if (exifOrientation == ExifInterface.ORIENTATION_NORMAL &&
            oriented.height < oriented.width
        ) {
            val mp4 = Mp4Rotation.fromUri(context, uri)
            if (mp4 != 0) {
                val m = Matrix().apply { postRotate(mp4.toFloat()) }
                return runCatching {
                    Bitmap.createBitmap(
                        oriented, 0, 0, oriented.width, oriented.height, m, true
                    ).also { if (it !== oriented) oriented.recycle() }
                }.getOrDefault(oriented)
            }
        }
        return oriented
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
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
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            if (data.scheme != "content") return null
            val w = (options.size.width as? Dimension.Pixels)?.px ?: return null
            val h = (options.size.height as? Dimension.Pixels)?.px ?: return null
            // Yalnızca ızgara küçük resimleri; görüntüleyici varsayılan hatta kalır.
            if (maxOf(w, h) > MAX_THUMBNAIL_PX) return null
            return MediaThumbnailFetcher(data, options, context)
        }
    }

    private companion object {
        /**
         * 256 px: ızgara hücreleri. 512 px: görüntüleyicide tam boy
         * gelmeden önce gösterilen ara önizleme ([MAX_THUMBNAIL_PX]).
         * Daha büyük istekler (tam boy ~2300 px) varsayılan Coil
         * hattına düşer.
         */
        const val MAX_THUMBNAIL_PX = 512
        const val MIN_PX = 32

        /**
         * Dönüş açısı önbelleği: her hücre kaydırmasında EXIF/tkhd taraması
         * (onlarca ms) ızgarayı yavaşlatırdı.
         */
        val rotationCache = ConcurrentHashMap<Uri, Int>()
    }
}
