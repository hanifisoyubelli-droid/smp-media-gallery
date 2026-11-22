package com.smpmedia.ui.components

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Precision
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.image.LowPriorityTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Görüntülenecek liste ve başlangıç indeksi (Cihaz/SMP/Koleksiyon ortak). */
data class PhotoViewerRequest(
    val photos: List<ViewerPhoto>,
    val initialIndex: Int,
    /** Anılar slaytı: açıkken 3.5 sn'de bir sonraki sayfaya geçer. */
    val autoPlay: Boolean = false
)

/** Tam ekran görüntüleyicide gösterilecek yerel veya SMB görseli. */
data class ViewerPhoto(
    val model: Any,
    val title: String,
    /** Disk anahtarı; küçük resimle aynı kaynağı paylaşır. */
    val baseCacheKey: String? = null,
    /** Çekim tarihi (ms); detay paneli ve Anılar bunu kullanır. */
    val dateMillis: Long = 0L,
    /** Dosya boyutu (bayt); 0 bilinmiyor. */
    val sizeBytes: Long = 0L,
    /** Medya MIME türü (image veya video); video rozetleri ve oynatma için. */
    val mimeType: String? = null,
    /**
     * Canlı fotoğraf mı? Görüntüleyici Google Photos gibi davranır: açılınca
     * hareket sessizce BİR KEZ oynar, parmak basılı kaldıkça döngüde çalar;
     * üstte "Hareketli" ibaresi görünür.
     */
    val isLivePhoto: Boolean = false,
    /**
     * SMP canlı fotoğrafının eşleşen videosunun SMB yolu (Takeout'ta JPG ile
     * aynı adta .mp4). Cihaz canlı fotoğraflarında null — orada hareket,
     * JPEG'in sonundaki trailer'dan çıkarılır (MotionPhotoExtractor).
     */
    val liveVideoSmbPath: String? = null,
    /**
     * Model [com.smpmedia.data.model.SmbImage] ise dosyanın okunacağı sunucu.
     * Canlı videonun indirmesi de bu kaynağa gider (Nextcloud çiftinde mp4
     * de WebDAV'dadır).
     */
    val cloudSource: com.smpmedia.data.model.MediaSourceKind =
        com.smpmedia.data.model.MediaSourceKind.SMB
)

/** Sayfa başına zoom/pan durumu; Pager'ın kaydırma kilidini belirler. */
private class PageZoomState {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
}

/**
 * Görüntüleyicide çözünürlük üst sınırı (piksel).
 *
 * 12 MP bir telefon fotoğrafı 4000x3000 = ~48 MB bitmap'tir; tam çözmek
 * ~700 ms sürer ve bellek önbelleğini şişirir. 2300 px sınır ~12 MB bitmap
 * ve ~200-250 ms decode demektir; 1080p ekranda 6x zoom'a kadar fark
 * edilmez. Prefetch ve görüntüleyici isteği AYNI değeri kullanmalıdır ki
 * bellek önbellek girdileri karşılıklı kullanılabilsin.
 */
private const val VIEWER_DECODE_SIZE = 2300

/**
 * Tam boy gelmeden önce gösterilen ara önizleme boyutu.
 * Cihaz fotoğraflarında MediaStore'un hazır küçük resmi bu boyutta
 * döndürülür (dosya çözme yapılmaz), böylece "bulanık/kırık önizleme"
 * yerine nete yakın bir kare görünür.
 */
private const val PREVIEW_DECODE_SIZE = 512

/**
 * Medyanın ortalanacağı alanın ALT PAYI (dp).
 *
 * Google Photos, fotoğraf/video alanını EKMANIN tam ortasına değil, ALT
 * kontrol çubuğunun ÜSTÜNDE kalan bölgeye ortalar; görüntü bu yüzden ekran
 * ortasından belirgin biçimde yukarıda durur ve asla çubukların altına girmez.
 * Bizde de alt yığın (sayaç + Paylaş/Düzenle/Ekle/Çöp kutusu + jest çubuğu)
 * ~150 dp yer kaplıyor; medyanın altından bu payı düşünce kadraj GP ile aynı
 * hiza geliyor.
 */
private const val MEDIA_BOTTOM_RESERVE_DP = 150

/**
 * Tüm listeyi alan tam ekran görüntüleyici.
 * Sağa/sola kaydırarak önceki/sonraki fotoğrafa geçilir; komşu sayfalar
 * [HorizontalPager]'ın `beyondBoundsPageCount` değeriyle erkenden hazırlanır.
 */
@Composable
fun PhotoViewerDialog(
    photos: List<ViewerPhoto>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    autoPlay: Boolean = false,
    onPhotoDeleted: ((ViewerPhoto) -> Unit)? = null
) {
    if (photos.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ViewerScreen(
            photos = photos,
            initialIndex = initialIndex,
            autoPlay = autoPlay,
            onDismiss = onDismiss,
            onPhotoDeleted = onPhotoDeleted
        )
    }
}

@Composable
fun ViewerScreen(
    photos: List<ViewerPhoto>,
    initialIndex: Int,
    autoPlay: Boolean = false,
    onDismiss: () -> Unit,
    onPhotoDeleted: ((ViewerPhoto) -> Unit)? = null
) {
    if (photos.isEmpty()) return

    val safeIndex = initialIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeIndex,
        pageCount = { photos.size }
    )
    // Her sayfa kendi zoom durumunu tutar; zoom > 1 iken Pager kaydırması kapanır.
    val zoomStates = remember(photos) { MutableList(photos.size) { PageZoomState() } }

    val viewerContext = LocalContext.current

    // ── NAS benzeri sıralı okuma (sequential read-ahead) ──
    // Kullanıcı N numaralı fotoğrafı incelerken N+1 ve N+2'nin TAM boyutu,
    // N-1 ise tek tampon olarak Coil bellek önbelleğine indirilir. Parmak
    // kaydırdığı anda kare bellekten gelir — diskin okuma kafasını komşu
    // sektörlerde hazır tutmak gibi. `LowPriorityTag` nedeniyle prefetchGate
    // (3 izin) arkasında koşar; mevcut sayfanın yüklemesini asla bloklamaz.
    LaunchedEffect(pagerState.currentPage) {
        val current = pagerState.currentPage
        val imageLoader = viewerContext.imageLoader
        val running = mutableListOf<Disposable>()
        try {
            // İleri yönde 2 sayfa, geri yönde 1 sayfa (kaydırma genelde ileriye).
            for (offset in intArrayOf(1, 2, -1)) {
                val neighbor = photos.getOrNull(current + offset) ?: continue
                val key = neighbor.baseCacheKey ?: continue
                // Video sayfaları görsel prefetch'i almaz: oynatıcı dosyayı
                // kendi çeker; boşuna ağ istekleri biriktirme.
                if (isVideoMime(neighbor.mimeType)) continue
                val isSmb = neighbor.model is com.smpmedia.data.model.SmbImage
                // Yerel/USB görsellerde GERİ yönlü prefetch yoktur (USB okuma
                // kafası ileri akışta verimlidir); SMB'de ±2 sayfa önden gelir.
                if (!isSmb && offset < 0) continue
                val memoryKey = MemoryCache.Key(CacheKeyHelper.fullMemoryKey(key))
                // Zaten bellekte olan sayfa için ağa hiç çıkma.
                if (imageLoader.memoryCache?.get(memoryKey) != null) continue

                running += imageLoader.enqueue(
                    ImageRequest.Builder(viewerContext)
                        .data(neighbor.model)
                        .memoryCacheKey(memoryKey)
                        .diskCacheKey(key) // Küçük resimle aynı kaynak: tek indirme.
                        // Görüntüleyicinin kendi isteğiyle AYNI boyut: bellek
                        // anahtarı tutmalı ki prefetch sonucu direkt kullanılsın.
                        .size(VIEWER_DECODE_SIZE, VIEWER_DECODE_SIZE)
                        .precision(Precision.INEXACT)
                        .tag(LowPriorityTag)
                        .build()
                )
            }
        } finally {
            // ── NAS preemptive I/O ──
            // Coil 2'nin kuyruk önceliği YOKTUR; tek doğru yol, sayfa
            // değiştiği anda yarım kalmış eski prefetch'leri iptal edip
            // okuma şeridini görüntülenen fotoğrafın isteğine bırakmaktır.
            running.forEach { it.dispose() }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        Log.d(
            "SmpMedia",
            "Görüntüleyici sayfa: ${pagerState.currentPage + 1}/${photos.size}"
        )
    }

    // ── Anılar slaytı ──
    // autoPlay açıkken (Anı kartından açıldıysa) 3.5 saniyede bir sonraki
    // kareye yumuşak geçiş yapar; son karede başa sarar (Google Photos "slideshow").
    if (autoPlay && photos.size > 1) {
        LaunchedEffect(pagerState.currentPage) {
            delay(3500)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % photos.size)
        }
    }

    val currentPage = pagerState.currentPage.coerceIn(0, photos.lastIndex)
    val currentPhoto = photos[currentPage]

    // ── Detay paneli durumu (sayfa değişince kapanır ve yeniden sorulur) ──
    var detailsOpen by remember(currentPage) { mutableStateOf(false) }
    var details by remember(currentPage) { mutableStateOf<PhotoDetails?>(null) }
    // Yerleşik düzenleyici (kırp + çevir): sayfa değişince kendiliğinden kapanır.
    var editOpen by remember(currentPage) { mutableStateOf(false) }

    // ── Google Photos tarzı bilgi çubukları ──
    // Tek dokunuşta üst + alt çubuk BİRLİKTE gizlenir/görünür. Durum sayfa
    // düzeyinde tutulur: yana kaydırınca hangi düzendeyse öyle devam eder.
    var chromeVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    val favPrefs = remember(viewerContext) {
        viewerContext.getSharedPreferences(
            "viewer_favorites",
            android.content.Context.MODE_PRIVATE
        )
    }
    val favoriteKey = currentPhoto.baseCacheKey ?: currentPhoto.title
    var isFavorite by remember(favoriteKey) {
        mutableStateOf(favPrefs.getBoolean(favoriteKey, false))
    }

    LaunchedEffect(detailsOpen, currentPage, chromeVisible) {
        if (details == null && (detailsOpen || chromeVisible)) {
            // Çubuk açıkken konum satırı için EXIF/geocode yüklenir; hızlı
            // kaydırmada her sayfa için ağa çıkmamak adına kısa bekleme var.
            delay(450)
            details = runCatching { ViewerActions.loadDetails(viewerContext, currentPhoto) }
                .getOrNull()
        }
    }
    // Panel açıkken geri tuşu önce paneli kapatsın.
    if (detailsOpen) {
        BackHandler(onBack = { detailsOpen = false })
    }
    // Yerleşik düzenleyici açıksa geri tuşu onu kapatır (düzenlenen kopya
    // kaydedilmemiş olsa bile kaybolmaz — kullanıcıya "Kaydet" hatırlatılır).
    if (editOpen) {
        BackHandler(onBack = { editOpen = false })
    }

    // ── Silme onayı + paylaş/düzenle hazırlık meşguliyeti ──
    var confirmDelete by remember(currentPage) { mutableStateOf(false) }
    var addOpen by remember(currentPage) { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    // Hazırlık yüzdesi (-1: toplam boyut bilinmiyor, yalnızca spinner döner).
    var actionProgress by remember { mutableStateOf(-1) }
    // Yüzde DEĞİŞİNCE yazılır: kopya 256 KB bloklarla akar, her blokta
    // durum yazmak ekranı gereksiz yere yeniden kompozlar (kasma nedeni).
    val updateActionProgress: (Long, Long) -> Unit = { written, total ->
        if (total > 0L) {
            val pct = ((written * 100) / total).toInt().coerceIn(0, 99)
            if (pct != actionProgress) actionProgress = pct
        }
    }
    val actionScope = rememberCoroutineScope()

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Fotoğrafı sil") },
            text = {
                Text(
                    "Bu fotoğraf kalıcı olarak silinecek:\n${currentPhoto.title}\n\n" +
                        "Bu işlem geri alınamaz."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onPhotoDeleted?.invoke(currentPhoto)
                    }
                ) {
                    Text("Sil", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") }
            }
        )
    }

    BackHandler(onBack = onDismiss)
    // Kompozisyon her yeniden çizildiğinde log basmasın: yalnızca liste
    // değiştiğinde (ilk açılışta) bir kez.
    remember(photos, safeIndex) {
        Log.d(
            "SmpMedia",
            "Görüntüleyici açıldı: ${photos[safeIndex].title} (${safeIndex + 1}/${photos.size})"
        )
        true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            // 0 bırakılır: komşu sayfaların tam boy yüklemesini yukarıdaki
            // LaunchedEffect prefetch'i DÜŞÜK ÖNCELİKLE yapar. beyondViewport
            // açıksaydı komşu sayfalar kendi (yüksek öncelikli) isteklerini de
            // başlatır, aynı dosya ÇİFT indirilirdi — NAS hattında çift trafik.
            beyondViewportPageCount = 0,
            // Zoom yapılmışken Pager kaydırmasını kapat ki pan çalışsın.
            userScrollEnabled = zoomStates[currentPage].zoom <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            ViewerPage(
                photo = photos[pageIndex],
                zoomState = zoomStates[pageIndex],
                isCurrent = pageIndex == currentPage,
                showControls = chromeVisible,
                onTap = { chromeVisible = !chromeVisible },
                onSwipeUp = {
                    chromeVisible = true
                    detailsOpen = true
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Üst bilgi: geri · tarih/saat/konum · favori · menü (GP düzeni) ──
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ViewerTopBar(
                photo = currentPhoto,
                details = details,
                isFavorite = isFavorite,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onBack = onDismiss,
                onToggleFavorite = {
                    isFavorite = !isFavorite
                    favPrefs.edit().putBoolean(favoriteKey, isFavorite).apply()
                },
                onOpenDetails = {
                    menuOpen = false
                    detailsOpen = true
                },
                onDelete = if (onPhotoDeleted != null) {
                    {
                        menuOpen = false
                        confirmDelete = true
                    }
                } else null
            )
        }

        // ── Alt yığın: detay paneli → sayaç → aksiyon çubuğu ──
        // Google Photos düzeni: resmin üzerinde yukarı kaydırınca (veya
        // Detaylar ile) bilgi paneli açılır; en altta paylaş/düzenle/ekle/sil.
        //
        // ÖNEMLİ: Android 15+ Diyalog pencereleri zorla edge-to-edge'dir;
        // ComposeView yüksekliği ekranın altına (sürdürme çubuğu kadar) taşar.
        // Dialog kendi WindowInsets'ını 0 bildirir; gerçek yükseklik Activity
        // WindowManager'ından okunur ve kadraj ekran dibine çekilir.
        val navInsetPx = remember(viewerContext) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    viewerContext.getSystemService(android.view.WindowManager::class.java)
                        .currentWindowMetrics.windowInsets
                        .getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                }.getOrDefault(0)
            } else {
                0
            }
        }
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
        Column(
            modifier = Modifier
                .padding(bottom = with(androidx.compose.ui.platform.LocalDensity.current) {
                    navInsetPx.toDp()
                })
        ) {
            AnimatedVisibility(
                visible = detailsOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                ViewerDetailsPanel(
                    photo = currentPhoto,
                    details = details,
                    onOpenMap = { lat, lon ->
                        ViewerActions.openInMaps(viewerContext, lat, lon, currentPhoto.title)
                    },
                    onClose = { detailsOpen = false }
                )
            }

            if (!detailsOpen) {
                Text(
                    text = "${currentPage + 1} / ${photos.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            ViewerActionBar(
                photo = currentPhoto,
                detailsOpen = detailsOpen,
                canDelete = onPhotoDeleted != null,
                onShare = {
                    // Çift tıklama koruması: İkinci kopya işi aynı dosyaya
                    // yazmaz, NAS boşuna yorulmaz (buton meşgulse yok sayılır).
                    if (!actionBusy) {
                        actionBusy = true
                        actionProgress = -1
                        actionScope.launch {
                            val uri = ViewerActions.prepareShareUri(
                                viewerContext,
                                currentPhoto
                            ) { written, total ->
                                updateActionProgress(written, total)
                            }
                            actionBusy = false
                            if (uri != null) {
                                ViewerActions.share(
                                    viewerContext,
                                    uri,
                                    ViewerActions.mimeTypeOf(viewerContext, currentPhoto)
                                )
                            } else {
                                Toast.makeText(
                                    viewerContext,
                                    "Fotoğraf hazırlanamadı",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                },
                onEdit = {
                    // Yerleşik düzenleyici (kullanıcı isteği: kırp + çevir).
                    // Video kareleri düzenlenemez; canlı fotoğraf DURGUN
                    // haliyle editöre girer (hareket kopyaya taşınmaz).
                    if (isVideoMime(currentPhoto.mimeType)) {
                        Toast.makeText(
                            viewerContext,
                            "Video düzenleme desteklenmiyor",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        editOpen = true
                    }
                },
                onAdd = { addOpen = true },
                onDelete = { confirmDelete = true }
            )
        }
        }

        // ── "Ekle": fotoğrafı SMP'deki bir klasöre kopyala (GP albüm mantığı) ──
        if (addOpen) {
            AddToFolderDialog(
                photo = currentPhoto,
                onDismiss = { addOpen = false },
                onCopied = {
                    addOpen = false
                    Toast.makeText(viewerContext, "Klasöre eklendi", Toast.LENGTH_SHORT).show()
                }
            )
        }

        if (actionBusy) {
            // Spinner + yüzde: büyük SMB videolarında "ne oluyor?" belirsizliği
            // kullanıcıyı butona tekrar tekrar basmaya itiyordu.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center)
            ) {
                CircularProgressIndicator(color = Color.White)
                if (actionProgress >= 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Paylaşım hazırlanıyor %$actionProgress",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                }
            }
        }

        // ── YERLEŞİK DÜZENLEYİCİ (kırp + çevir) ──
        // Son çocuk: her şeyin üstünde tam ekran kaplar; SMP fotoğrafında
        // orijinali bir kez indirir, sonuç galeriye KOPYA olarak yazılır.
        if (editOpen) {
            PhotoEditorScreen(
                photo = currentPhoto,
                onDismiss = { editOpen = false }
            )
        }
    }
}

/** Üst çubuk (GP): geri · tarih + saat/konum · favori · taşma menüsü. */
@Composable
private fun ViewerTopBar(
    photo: ViewerPhoto,
    details: PhotoDetails?,
    isFavorite: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenDetails: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Geri",
                    tint = Color.White
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatChromeDate(photo.dateMillis),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1
                )
                val subtitle = buildString {
                    append(formatChromeTime(photo.dateMillis))
                    details?.address?.let { append(" · $it") }
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Favori",
                    tint = if (isFavorite) Color(0xFFFFC107) else Color.White
                )
            }

            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Diğer",
                        tint = Color.White
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { onMenuOpenChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text("Detaylar") },
                        onClick = onOpenDetails
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("Sil") },
                            onClick = onDelete
                        )
                    }
                }
            }
        }
    }
}

/** "Ekle": SMP kullanıcı klasöründeki alt klasörlerden birine kopyalar. */
@Composable
private fun AddToFolderDialog(
    photo: ViewerPhoto,
    onDismiss: () -> Unit,
    onCopied: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        folders = ViewerActions.listSubfolders(context)
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Klasöre ekle") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)) {
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                } else if (folders.isEmpty()) {
                    Text("SMP kullanıcı klasöründe alt klasör bulunamadı.")
                } else {
                    LazyColumn {
                        items(folders) { folder ->
                            Text(
                                text = folder.substringAfterLast('/'),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        busy = true
                                        scope.launch {
                                            val ok = ViewerActions.copyToFolder(context, photo, folder)
                                            busy = false
                                            if (ok) onCopied()
                                        }
                                    }
                                    .padding(vertical = 12.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
                if (busy) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Kapat") }
        }
    )
}

// Üst çubuk başlığı: "9 Ekim 2026" — yıl her zaman görünür (kullanıcı
// isteği): resim açılınca ve fotoğraflar arasında kaydırırken tam tarih.
private fun formatChromeDate(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("d MMMM yyyy", Locale("tr")).format(Date(millis))

private fun formatChromeTime(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("HH:mm", Locale("tr")).format(Date(millis))

/** Alt aksiyon çubuğu (GP düzeni): Paylaş · Düzenle · Ekle · Çöp kutusu. */
@Composable
private fun ViewerActionBar(
    photo: ViewerPhoto,
    detailsOpen: Boolean,
    canDelete: Boolean,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onAdd: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                )
            )
            // NOT: Dialog penceresinde navigationBarsPadding() anormal
            // (ekranı aşan) inset döndürüp çubuğu ekran dışına itiyor;
            // sabit alt boşluk güvenli — Dialog zaten ekranın tamamını kaplar.
            //
            // 86dp: Android gesture (sürdürme) çizgisi + sistem çubuğu payı.
            // Ekran 2364 px; jest alanı son ~117 px (2247'den aşağısı).
            // Etiket ve ikonlar bu alana girmesin diye çubuk yukarı çekilir.
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .padding(bottom = 86.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionBarButton(Icons.Filled.Share, "Paylaş", onShare)
        ActionBarButton(Icons.Filled.Edit, "Düzenle", onEdit)
        ActionBarButton(Icons.Filled.Add, "Ekle", onAdd)
        if (canDelete) {
            ActionBarButton(
                icon = Icons.Filled.Delete,
                label = "Çöp kutusu",
                onClick = onDelete,
                tintedError = true
            )
        }
    }
}

@Composable
private fun ActionBarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    tintedError: Boolean = false
) {
    val tint = when {
        tintedError -> MaterialTheme.colorScheme.error
        highlighted -> Color.White
        else -> Color.White.copy(alpha = 0.92f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

@Composable
private fun ViewerPage(
    photo: ViewerPhoto,
    zoomState: PageZoomState,
    isCurrent: Boolean = false,
    showControls: Boolean = true,
    onTap: () -> Unit = {},
    onSwipeUp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isVideo = isVideoMime(photo.mimeType)

    // ── CANLI FOTOĞRAF (Google Photos davranışı) ──
    // Sayfa açıkken hareket otomatik BİR KEZ oynar; parmak basılı kaldıkça
    // döngüde çalar. Video dosyası tembel çözülür: SMP'de eşleşen .mp4 bir
    // kez indirilir (shared/ önbelleği), cihazda JPEG trailer'ından çıkarılır.
    val isLive = !isVideo && photo.isLivePhoto
    var liveFile by remember(photo) { mutableStateOf<File?>(null) }
    var liveLoading by remember(photo) { mutableStateOf(false) }
    var liveAuto by remember(photo) { mutableStateOf(false) }
    var liveHold by remember(photo) { mutableStateOf(false) }
    var liveTried by remember(photo) { mutableStateOf(false) }

    LaunchedEffect(isCurrent) {
        if (isCurrent && isLive && !liveTried) {
            liveTried = true
            liveLoading = true
            liveFile = withContext(Dispatchers.IO) {
                when {
                    photo.liveVideoSmbPath != null -> ViewerActions.localFileFor(
                        context,
                        com.smpmedia.data.model.SmbImage(
                            path = photo.liveVideoSmbPath,
                            lastModified = 0L,
                            isVideo = true,
                            source = photo.cloudSource
                        )
                    )

                    photo.model is Uri ->
                        MotionPhotoExtractor.videoFor(context, photo.model as Uri)

                    else -> null
                }
            }
            liveLoading = false
            if (liveFile != null) liveAuto = true
        }
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val viewWidth = constraints.maxWidth.toFloat()
        val viewHeight = constraints.maxHeight.toFloat()

        // ── JEST MOTORU (Google Photos davranışı) ──
        // transformable her zaman bağlıydı: tek parmak sürüklemeyi de
        // tüketiyor, Pager kaydırmasını ve İKİ PARMAKLA YAKINLAŞTIRMAYI
        // kilitliyordu (zoom>1 iken ekleniyordu → zoom'a GİRİLEMIYORDU).
        // Kural: 2+ parmak → zoom (her zaman); tek parmak VE zoom>1 → pan;
        // zoom==1'de tek parmak HİÇBİR ŞEY tüketmez → Pager akıcı kayar.
        val panClamp: (Float, Offset) -> Offset = { zoom, off ->
            val maxX = viewWidth * (zoom - 1f) / 2f
            val maxY = viewHeight * (zoom - 1f) / 2f
            Offset(off.x.coerceIn(-maxX, maxX), off.y.coerceIn(-maxY, maxY))
        }

        // ── HATA KURTARMA ──
        // Bazı fotoğraflarda tam boy okuma geçici olarak başarısız olabilir
        // (USB anlık okuma hatası, SMB oturum yenilemesi). Otomatik 2 kez
        // yeniden dene; gerçek hatayı logla; son çare net küçük resmi göster.
        var retryToken by remember(photo) { mutableIntStateOf(0) }
        val fullRequest = remember(photo, retryToken) {
            ImageRequest.Builder(context)
                .data(photo.model)
                .apply {
                    photo.baseCacheKey?.let { key ->
                        // Tam ekranda bellekteki büyük bitmap'i ayrı tut.
                        memoryCacheKey(CacheKeyHelper.fullMemoryKey(key))
                        // Disk önbelleği küçük resimle ortak: kaynak dosya tek.
                        diskCacheKey(key)
                    }
                }
                // EKRANA UYGUN çöz: 12 MP orijinali tam çözmek 40-50 MB
                // bitmap + ~700 ms decode demektir. 2300 px üst sınır,
                // 6x zoom'a kadar gözle fark edilemeyen kaliteyle
                // ~3x daha hızlı açılır ve 1/4 bellek yakar.
                // (Google Photos da görüntüleyicide ekran çözünürlüğü
                // + zoom payı kadar çözer.)
                .size(VIEWER_DECODE_SIZE, VIEWER_DECODE_SIZE)
                .precision(Precision.INEXACT)
                // Küçük resimden tam görsele geçiş yumuşak solma ile olsun.
                .crossfade(200)
                // Gerçek hatayı logla: "bazı resimler yüklenemiyor" sorununun
                // KÖK NEDENİ (decode mu, USB mi, SMB mi) ancak böyle bulunur.
                .listener(object : ImageRequest.Listener {
                    override fun onError(request: ImageRequest, result: coil.request.ErrorResult) {
                        Log.w(
                            "SmpMedia",
                            "Tam görsel yüklenemedi: ${photo.title}",
                            result.throwable
                        )
                    }
                })
                .build()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(photo.baseCacheKey ?: photo.title) {
                    detectTapGestures(
                        // Tek dokunuş: üst/alt bilgi çubuklarını aç/kapat (GP).
                        onTap = { onTap() },
                        // Canlı fotoğrafta parmak basılı → hareket döngüde oynar
                        // (bırakınca pinch motoru liveHold=false'a çeker). (GP)
                        onLongPress = { if (isLive) liveHold = true },
                        onDoubleTap = {
                            if (isVideo) return@detectTapGestures
                            // Yakınlaştırma ANİMASYONLA açılsın (Google Photos
                            // gibi): ani sıçrama yerine ~160 ms'lik akıcı geçiş.
                            val target = if (zoomState.zoom > 1f) 1f else 2.5f
                            val start = zoomState.zoom
                            if (target == 1f) zoomState.offset = Offset.Zero
                            scope.launch {
                                val frames = 10
                                repeat(frames) { i ->
                                    // easeOutQuad: hızlı başlar, yumuşak durur.
                                    val t = (i + 1) / frames.toFloat()
                                    val eased = 1f - (1f - t) * (1f - t)
                                    zoomState.zoom = start + (target - start) * eased
                                    delay(16)
                                }
                                zoomState.zoom = target
                            }
                        }
                    )
                }
                .pointerInput(viewWidth, viewHeight) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        var prevSpan = 0f
                        // Dikey süpürme takibi: parmak izi (Google Photos'ta
                        // resmi yukarı kaydırınca bilgi paneli açılır).
                        var downX = firstDown.position.x
                        var downY = firstDown.position.y
                        var lastX = downX
                        var lastY = downY
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size == 1) {
                                lastX = pressed[0].position.x
                                lastY = pressed[0].position.y
                            }
                            if (pressed.size >= 2) {
                                val p0 = pressed[0].position
                                val p1 = pressed[1].position
                                val span = (p0 - p1).getDistance()
                                if (prevSpan > 1f && span > 1f) {
                                    val newZoom =
                                        (zoomState.zoom * (span / prevSpan)).coerceIn(1f, 6f)
                                    zoomState.zoom = newZoom
                                    zoomState.offset =
                                        if (newZoom <= 1.001f) {
                                            Offset.Zero
                                        } else {
                                            panClamp(newZoom, zoomState.offset)
                                        }
                                }
                                prevSpan = span
                                // Tüket: pinch sırasında Pager kaymasın.
                                pressed.forEach { it.consume() }
                            } else if (pressed.size == 1 && zoomState.zoom > 1f) {
                                val change = pressed[0]
                                // Compose 1.7 PointerChange'de positionChange() yok.
                                val pan = change.position - change.previousPosition
                                if (pan != Offset.Zero) {
                                    zoomState.offset =
                                        panClamp(
                                            zoomState.zoom,
                                            Offset(
                                                zoomState.offset.x + pan.x,
                                                zoomState.offset.y + pan.y
                                            )
                                        )
                                    change.consume()
                                }
                            }
                            if (pressed.size < 2) prevSpan = 0f
                            if (event.changes.none { it.pressed }) {
                                // Parmak kalktı → basılı-oynatma döngüsünü kes.
                                liveHold = false
                                val dy = lastY - downY
                                val dx = lastX - downX
                                // Yeterli ve dikey-ağırlıklı yukarı kaydırma →
                                // detay panelini aç. Yatay kaydırmalar Pager'a kalır.
                                if (dy < -140f && -dy > kotlin.math.abs(dx) * 1.2f) {
                                    onSwipeUp()
                                }
                                break
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isVideo) {
                // ── Uygulama İÇİ video oynatma ──
                // Önceden sistem oynatıcısına devrediliyordu; artık görüntüleyici
                // içinde oynar. Yakınlaştırma/kaydırma videoda kapalı.
                InlineVideoPlayer(
                    model = photo.model,
                    showControls = showControls,
                    isCurrent = isCurrent,
                    onTap = onTap,
                    // GP hizası: video alt çubuğun üstünde ortalanır.
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = MEDIA_BOTTOM_RESERVE_DP.dp)
                )
            } else {
            SubcomposeAsyncImage(
                model = fullRequest,
                contentDescription = "Fotoğraf görüntüleniyor",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    // GP hizası: fotoğraf alt kontrol çubuğunun üstündeki
                    // alanda ortalanır (ekranın tam ortasında değil).
                    .fillMaxSize()
                    .padding(bottom = MEDIA_BOTTOM_RESERVE_DP.dp)
                    .graphicsLayer(
                        scaleX = zoomState.zoom,
                        scaleY = zoomState.zoom,
                        translationX = zoomState.offset.x,
                        translationY = zoomState.offset.y
                    ),
                // ── Google Photos davranışı: SPINNER YOK ──
                // Tam boy yüklenene kadar ızgaradan bellekte duran küçük resim
                // gösterilir. ÖNEMLİ: bulanıklaştırma YOK ve kırpma YOK —
                // ikisi de "önce bulanık, sonra net" sıçraması yaratıyordu.
                // Küçük resim tam görselle AYNI çerçevede (Fit) durur; tam boy
                // gelince 200 ms solma ile yerini alır (kesintisiz görünür).
                loading = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ThumbnailBackdrop(photo = photo)
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.9f),
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .size(24.dp)
                        )
                    }
                },
                error = {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Yarışmacı davranışı: tam boy bir türlü gelmese bile
                        // kullanıcının önünde NET küçük resim dursun; uçan
                        // "Görsel yüklenemedi" yazısı yerine fotoğrafı görür.
                        ThumbnailBackdrop(photo = photo)
                        // Geçici hatalar (USB/SMB dalgalanması) için sessiz
                        // otomatik yeniden deneme; en son kullanıcıyı bilgilendir.
                        LaunchedEffect(Unit) {
                            if (retryToken < 2) {
                                delay(350)
                                retryToken++
                            }
                        }
                        if (retryToken >= 2) {
                            Text(
                                text = "Tam boyut yüklenemedi — küçük resim gösteriliyor",
                                color = Color.White.copy(alpha = 0.9f),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            )

                // ── CANLI FOTOĞRAF OYNATMASI (Google Photos) ──
                // Hareket, fotoğrafın tam çerçevesini kaplayan sessiz bir
                // yüzeyde oynar; klip bitince (veya parmak kalkınca) yüzey
                // kaldırılır ve alttaki fotoğrafa dönülür. Yakınlaştırılmışken
                // oynatılmaz — kullanıcı o an durgun kareyi inceliyordur.
                val motionFile = liveFile
                if (isLive && motionFile != null && (liveAuto || liveHold) &&
                    zoomState.zoom <= 1.01f
                ) {
                    LivePhotoViewer(
                        videoFile = motionFile,
                        loop = liveHold,
                        onFinished = {
                            liveAuto = false
                            liveHold = false
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = MEDIA_BOTTOM_RESERVE_DP.dp)
                    )
                }
            }
        }

        // ── "HAREKETLİ" ibaresi (Google Photos üst etiketi) ──
        // Sol üstte, araç çubuğunun hemen altında; hareket kaynağı ilk kez
        // SMP'den indirilirken yanında ufak bir spinner döner.
        if (isLive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.MotionPhotosOn,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Hareketli",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
                if (liveLoading && liveFile == null) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 1.5.dp,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(11.dp)
                    )
                }
            }
        }
    }
}

/**
 * Izgaradan zaten Coil belleğinde duran küçük resmi görüntüleyicide
 * tam ekran arka plan olarak gösterir (bellek isabeti ~0 ms).
 *
 * ÖNEMLİ: **bulanıklaştırma ve kırpma yok.** Eski sürüm 16 dp blur +
 * `ContentScale.Crop` kullanıyordu; fotoğraf ekranı bulanık kaplıyor,
 * tam boy gelince birden netleşip çerçeve değiştiği için "önce bulanık,
 * sonra düzgün" sıçraması oluyordu (kullanıcı şikâyeti). Artık küçük resim
 * tam görselle aynı yerleşimde (`Fit`) durur; tam boy 200 ms solmayla
 * üstüne biner — Google Photos'un yaptığı gibi kesintisiz görünür.
 */
@Composable
private fun ThumbnailBackdrop(photo: ViewerPhoto) {
    val key = photo.baseCacheKey ?: return
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Aşama — ızgaradan bellekte duran 256 px kopya: anında görünür.
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(photo.model)
                .memoryCacheKey(CacheKeyHelper.thumbnailMemoryKey(key))
                .diskCacheKey(key)
                .size(GRID_THUMBNAIL_SIZE, GRID_THUMBNAIL_SIZE)
                .precision(Precision.INEXACT)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        // 2. Aşama — cihaz fotoğraflarında MediaStore'un hazır ~512 px
        // önizlemesi (dosya çözme yok, ~5 ms). 256 px'in 1080 px ekrana
        // 4x büyütülmesindeki kırıklığı giderir; tam boy gelince üstüne biner.
        //
        // SMB'de bu aşama YOK: orada >256 px istek "tam dosya indir"
        // demektir ve NAS'ta ikinci bir indirme trafiği yaratırdı.
        if (photo.model !is com.smpmedia.data.model.SmbImage) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.model)
                    .memoryCacheKey("$key@view_preview")
                    .size(PREVIEW_DECODE_SIZE, PREVIEW_DECODE_SIZE)
                    .precision(Precision.INEXACT)
                    .crossfade(120)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
