package com.smpmedia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.image.LowPriorityTag
import com.smpmedia.data.model.ImageLoadState
import com.smpmedia.data.model.SmbImage

/** Izgaradaki küçük resimler için hedef boyut (piksel). */
const val GRID_THUMBNAIL_SIZE = 256

/** Progressive placeholder boyutu; neredeyse anında diskten çözülür. */
private const val GRID_PLACEHOLDER_SIZE = 20

/** SMB olmayan yerel görseller için açık gri arka plan. */
private val PlaceholderBackground = Color(0xFFE0E0E0)

/**
 * Izgara hücreleri için optimize edilmiş küçük resim.
 *
 * - 200x200 istenir: cihazda ~40 KB, 160 KB'lık tam boy yerine.
 * - [CacheKeyHelper] ile yol + tarih anahtarı kullanılır; dosya değişince
 *   önbellek otomatik geçersiz olur.
 * - Bellek anahtarları boyuta göre ayrılır (`@placeholder20`, `@thumb200`,
 *   `@full`); böylece küçük bitmap tam ekran isteğiyle çakışmaz.
 * - **Yerel görseller**: 20x20 progressive placeholder gösterilir, üzerine
 *   net 200x200 biner. Boş gri kutu yerine anında bir şey görünür.
 * - **SMB görselleri**: Progressive placeholder ATLANIR; çünkü her iki
 *   istek de SMB'den tam dosya indirir ve trafiği ikiye katlar. Bunun
 *   yerine düz arka plan + crossfade kullanılır; asıl hızlandırma
 *   [com.smpmedia.data.image.ThumbnailCache] ile sağlanır.
 * - [onImageStateChange] ile başarı/hata durumu üst katmana bildirilir ve
 *   "eksikleri yenile" mekanizması tetiklenir.
 */
@Composable
fun GridImage(
    model: Any,
    baseCacheKey: String,
    imageState: ImageLoadState = ImageLoadState.PENDING,
    onImageStateChange: ((String, ImageLoadState) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    /**
     * Video hücreleri: "küçük resim hazırlanıyor" bir HATA değil normal bir
     * durumdur (kare arka planda üretiliyor). Kırmızı ↺ rozeti bu hücrelerde
     * gösterilmez; yer tutucuyu hücrenin kendisi çizer.
     */
    showErrorBadge: Boolean = true
) {
    val context = LocalContext.current

    // Progressive placeholder KALDIRILDI: hücre başına ikinci bir istek/çözme
    // demekti ve kaydırma sırasında çöp üretimini ikiye katlıyordu.
    // MediaStore küçük resmi zaten milisaniyeler içinde geliyor.

    // ImageRequest nesneleri PAHALIDIR; her yeniden çizimde yeniden üretilirse
    // kaydırma sırasında çöp toplama ve itme (jank) oluşur. `remember` ile
    // yalnızca model/anahtar değiştiğinde yeniden üretilirler.
    val fullRequest = remember(model, baseCacheKey) {
        ImageRequest.Builder(context)
            .data(model)
            .memoryCacheKey(CacheKeyHelper.thumbnailMemoryKey(baseCacheKey))
            // Disk önbelleği ızgarada KAPALI: Coil aksi hâlde kaynak dosyanın
            // tamamını (4-6 MB) diske kopyalamaya çalışır. Küçük resimler
            // bellekte tutulur; cihaz görselleri MediaProvider'dan, SMB
            // görselleri ThumbnailCache'ten anında gelir.
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .size(GRID_THUMBNAIL_SIZE, GRID_THUMBNAIL_SIZE)
            .precision(Precision.INEXACT)
            // Izgarada crossfade kapatılır: binlerce küçük resim yüklenirken
            // animasyon ekstra kare işi demek. Önbellekten anında gelir.
            .crossfade(false)
            .build()
    }

    Box(modifier = modifier) {
        // Arka plan: yükleme sırasında boş/gri görünüm.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(PlaceholderBackground)
        )

        // 2) Net küçük resim.
        AsyncImage(
            model = fullRequest,
            contentDescription = "Fotoğraf",
            contentScale = contentScale,
            onLoading = {
                onImageStateChange?.invoke(baseCacheKey, ImageLoadState.PENDING)
            },
            onSuccess = {
                onImageStateChange?.invoke(baseCacheKey, ImageLoadState.LOADED)
            },
            onError = {
                onImageStateChange?.invoke(baseCacheKey, ImageLoadState.FAILED)
            },
            modifier = Modifier.fillMaxSize()
        )

        // Video hücrelerinde FAILED bir arıza değil "kare henüz yok" demektir;
        // kırmızı ↺ gösterme (PENDING gibi nötr davran).
        val shownState = if (!showErrorBadge && imageState == ImageLoadState.FAILED) {
            ImageLoadState.PENDING
        } else {
            imageState
        }
        ImageLoadStatusOverlay(
            state = shownState,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
        )
    }
}

/**
 * Yükleme durumunu hücrenin köşesinde küçük bir ikonla gösterir.
 *
 * NOT: Yükleme sırasında DÖNEN GÖSTERGE YOK. Dönen bir animasyon her karede
 * yeniden çizim istiyordu; kaydırmada görünür onlarca hücre aynı anda
 * animasyon çalıştırınca kareler düşüyordu (takılma). Yükleme sırasında
 * yalnızca nötr arka plan kalır; hata durumunda sabit (animasyonsuz) ikon.
 */
@Composable
private fun ImageLoadStatusOverlay(
    state: ImageLoadState,
    modifier: Modifier = Modifier
) {
    when (state) {
        ImageLoadState.LOADED -> Unit // Temiz görünüm.
        ImageLoadState.PENDING -> Unit // Animasyonsuz bekleme (performans).

        ImageLoadState.FAILED -> Box(
            modifier = modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(0xFFB3261E)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Görsel yüklenemedi",
                tint = Color.White,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}