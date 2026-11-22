package com.smpmedia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.model.ImageLoadState
import com.smpmedia.data.model.RemotePhoto
import com.smpmedia.data.model.toCoilModel
import com.smpmedia.ui.viewmodel.PhotoUiItem

/**
 * Yerel medya için ızgara hücresi.
 *
 * Google Photos davranışları:
 * - Video: köşede film rozeti. Live/Motion Photo: GP'nin küçük hareketli
 *   fotoğraf simgesi (MotionPhotosOn).
 * - VIDEO'DA basılı tutunca sessiz önizleme oynar; parmak kalkınca durur.
 * - CANLI FOTOĞRAFLAR ızgarada YALNIZCA durgun resim olarak görünür
 *   (kullanıcı isteği): hareket sadece görüntüleyicide basılı tutunca oynar.
 *   Böylece ızgarada trailer çıkarma + kodek maliyeti hiç oluşmaz, kaydırma
 *   akıcı kalır.
 * - Canlı/video dışı karelerde (ve canlı karelerde) basılı tutma çoklu
 *   seçimi başlatır.
 */
@Composable
fun PhotoGridCell(
    item: PhotoUiItem,
    imageStates: Map<String, ImageLoadState>,
    onImageStateChange: (String, ImageLoadState) -> Unit,
    onClick: () -> Unit,
    motionActive: Boolean = false,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val baseCacheKey = CacheKeyHelper.localKey(
        uri = item.photo.uri.toString(),
        dateModified = item.photo.dateModified,
        sizeBytes = item.photo.sizeBytes
    )

    val isVideo = isVideoMime(item.photo.mimeType)
    val isLive = !isVideo && isLivePhotoName(item.photo.displayName)
    // Kullanıcı isteği: canlı fotoğraflar ızgarada durgun resim; hareket
    // yalnızca görüntüleyicide basılı tutunca. Video eski davranışını korur.
    val animatable = isVideo

    // Video basılı tutma önizlemesi (canlı fotoğraflar kapsam dışı).
    var holdActive by remember(item.photo.uri) { mutableStateOf(false) }

    val interactionModifier = if (animatable) {
        // Uzun basıldığında önizlemeyi başlat, bırakınca durdur.
        Modifier.pointerInput(item.photo.uri) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { holdActive = true },
                onPress = {
                    tryAwaitRelease()
                    holdActive = false
                }
            )
        }
    } else {
        Modifier.pointerInput(item.photo.uri) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { onLongClick() }
            )
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(interactionModifier)
    ) {
        GridImage(
            model = item.photo.uri,
            baseCacheKey = baseCacheKey,
            imageState = imageStates[baseCacheKey] ?: ImageLoadState.PENDING,
            onImageStateChange = onImageStateChange,
            modifier = Modifier.fillMaxSize()
        )

        if (isVideo && (motionActive || holdActive)) {
            VideoLivePreview(
                uri = item.photo.uri,
                videoFile = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Tür rozeti: sol alt (sağ alt yedekleme durumu rozetine ait).
        // GP tarzı kapsül: video → oynat simgesi + süre; canlı → hareket
        // simgesi (ızgarada durgun görünür ama hareketli OLDUĞU bellidir).
        if (isVideo || isLive) {
            GpMotionBadge(
                isVideo = isVideo,
                durationMs = if (isVideo) item.photo.durationMs else 0L,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            )
        }

        StatusBadge(
            status = item.status,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
        )

        if (selectionMode) {
            SelectionOverlay(
                selected = selected,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

/** Seçim modunda hücrenin üstünde beliren daire-içi-tik. */
@Composable
fun SelectionOverlay(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.30f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Seçili",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * Yalnızca SMP kullanıcı klasöründe bulunan (cihazda durmayan) fotoğrafın
 * ızgara hücresi — Google Photos'un "bulutta" öğretileri gibi: küçük resim
 * ağdan gelir, köşesinde ince bir bulut simgesi durur.
 *
 * [deferLoad] true ise (kullanıcı kaydırıyor) AĞ İSTEĞİ YAPILMAZ; sadece yer
 * tutucu gösterilir. Kaydırma durunca false olur ve gerçek küçük resim çekilir.
 */
@Composable
fun CloudGridCell(
    photo: RemotePhoto,
    imageStates: Map<String, ImageLoadState>,
    onImageStateChange: (String, ImageLoadState) -> Unit,
    onClick: () -> Unit,
    deferLoad: Boolean = false,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val baseCacheKey = CacheKeyHelper.smbKey(
        path = photo.path,
        lastModified = photo.lastModified,
        source = photo.source
    )
    // Diskten/hafızadan gelen hücreler kaydırma başlarsa DA kaybolmasın:
    // bekleme yalnızca henüz hiç yüklenmemiş hücreler için geçerli.
    val alreadyLoaded = imageStates[baseCacheKey] == ImageLoadState.LOADED
    val isVideo = photo.isVideo

    // Canlı çiftler ızgarada DURGUN görünür (kullanıcı isteği); hareket
    // yalnızca görüntüleyicide basılı tutunca oynar. Uzun basma her zaman
    // çoklu seçimdir — ilk basışta SMP'den mp4 indirme devri kapandı.
    val interactionModifier = Modifier.pointerInput(photo.path) {
        detectTapGestures(
            onTap = { onClick() },
            onLongPress = { onLongClick() }
        )
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(interactionModifier)
    ) {
        if (deferLoad && !alreadyLoaded) {
            // Kaydırma sırasında: Coil isteği YOK (ağ boşta kalsın, kare akıcı).
            // Video hücresi GP tarzı koyu tonuyla gelir; gri değil.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isVideo) Color(0xFF26282B) else Color(0xFFE0E0E0))
            )
        } else {
            GridImage(
                model = photo.toCoilModel(isVideo),
                baseCacheKey = baseCacheKey,
                imageState = imageStates[baseCacheKey] ?: ImageLoadState.PENDING,
                onImageStateChange = onImageStateChange,
                modifier = Modifier.fillMaxSize(),
                // "Kare hazırlanıyor" hata değildir; videoda kırmızı ↺ gösterme.
                showErrorBadge = !isVideo
            )
            if (isVideo && !alreadyLoaded) {
                // Kare arka planda üretiliyor: GP'nin çevrimdışı videosu gibi
                // koyu döşeme GridImage'in AÇIK gri yer tutucusunu örter
                // (GridImage kendi yer tutucusunu her zaman çizdiği için
                // kaplama SONRA gelmeli).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF26282B))
                )
            }
        }

        // Video: GP tarzı ortada büyük oynat simgesi (kare gelse de kalır).
        if (isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Tür rozeti: sol alt (video/canlı).
        if (photo.showsMotionBadge) {
            GpMotionBadge(
                isVideo = isVideo,
                durationMs = 0L, // SMP'de süre dosya inmeden bilinmez
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
            )
        }

        // GP tarzı "yalnızca bulutta" rozeti.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = "Yalnızca SMP'de",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }

        if (selectionMode) {
            SelectionOverlay(
                selected = selected,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}
