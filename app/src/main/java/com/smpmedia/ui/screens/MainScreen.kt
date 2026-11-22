package com.smpmedia.ui.screens

import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.model.ImageLoadState
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.MissingPhotoSource
import com.smpmedia.data.model.SmbImage
import com.smpmedia.data.model.toCoilModel
import com.smpmedia.ui.components.CloudGridCell
import com.smpmedia.ui.components.PhotoGridCell
import com.smpmedia.ui.components.PhotoViewerDialog
import com.smpmedia.ui.components.PhotoViewerRequest
import com.smpmedia.ui.components.PreloadGridImages
import com.smpmedia.ui.components.PreloadImage
import com.smpmedia.ui.components.TimelineScrubber
import com.smpmedia.ui.components.ViewerActions
import com.smpmedia.ui.components.ViewerPhoto
import com.smpmedia.ui.components.isLivePhotoName
import com.smpmedia.ui.components.isVideoMime
import com.smpmedia.ui.viewmodel.GalleryUiItem
import com.smpmedia.ui.viewmodel.FolderOption
import com.smpmedia.ui.viewmodel.MainUiState
import com.smpmedia.ui.viewmodel.MainViewModel
import com.smpmedia.ui.viewmodel.PhotoUiItem
import com.smpmedia.util.MemoriesCache
import com.smpmedia.util.MemoryItem
import com.smpmedia.util.hasImagePermission
import com.smpmedia.util.requiredImagePermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Google Photos benzeri ANA ekran — tek zaman tüneli.
 *
 * SMP ayrı sekme DEĞİLDİR (Google Photos'ta "cihaz/bulut" ayrımı yoktur):
 * cihazdaki fotoğraflar yeşil yedekleme rozetiyle, kullanıcı klasöründe
 * (ör. usb1_1/Ayse) bulunup cihazda olmayanlar bulut rozetiyle AYNI
 * zaman çizelgesinde görünür. Cihazda olan dosya SMP tarafında tekrar
 * listelenmez (ad + boyut tekilleştirmesi, MainViewModel içinde).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var viewerRequest by remember { mutableStateOf<PhotoViewerRequest?>(null) }

    // Görsel yükleme durumlarını kararlı bir snapshot map'e aktar.
    //
    // Neden? Durumlar bir `StateFlow<Map>` olarak geliyor ve her küçük resim
    // yüklendiğinde yeni bir map yayınlanıyordu. Bu map doğrudan ızgaraya
    // geçildiğinde, TEK bir görsel yüklenince 1000+ hücrenin tamamı yeniden
    // çiziliyordu (kaydırma takılmasının ana nedeni). `mutableStateMapOf`
    // kullanınca her hücre yalnızca kendi anahtarını okur; sadece o hücre
    // yeniden çizilir.
    val imageStates = remember { mutableStateMapOf<String, ImageLoadState>() }
    LaunchedEffect(Unit) {
        viewModel.imageStates.collect { latest ->
            // Yalnızca EKLE: harita artık sadece hataları taşıyor (küçük).
            // Silme yapılmaz; aksi hâlde başarıyla yüklenen anahtarların
            // LOADED işareti temizlenip hücreler yeniden yer tutucuya dönerdi.
            latest.forEach { (key, value) ->
                if (imageStates[key] != value) imageStates[key] = value
            }
        }
    }
    val missingCount by viewModel.missingCount.collectAsStateWithLifecycle()
    val missingRetrying by viewModel.missingRetrying.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var folderDialogOpen by remember { mutableStateOf(false) }
    val folderOptions by viewModel.folderOptions.collectAsStateWithLifecycle()
    // EXIF tarih onarımı ilerlemesi (arka planda koşar; null = iş yok).
    val repairProgress by viewModel.cloudDateRepair.collectAsStateWithLifecycle()

    // ── Çoklu seçim (Google Photos) ──
    // Fotoğrafa BASILI TUTUNCA seçim başlar; seçiliyken dokunma seçimi
    // değiştirir (açma modu). Seçim yoksa normal dokunma görüntüleyiciyi açar.
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedKeys.isNotEmpty()

    // Seçili öğelerin tekil anahtarı (cihaz: uri, SMP: yol).
    fun itemKey(ui: GalleryUiItem): String = when (ui) {
        is GalleryUiItem.Device -> ui.item.photo.uri.toString()
        is GalleryUiItem.Cloud -> "smb:${ui.photo.path}"
    }

    // ── Görüntüleyici çöp kutusu (Google Photos "Sil") ──
    // Yerel: MediaStore sistem onayı ister (createDeleteRequest → IntentSender).
    // Bulut: dosya sunucuda silinir, ızgara StateFlow üzerinden kendiliğinden güncellenir.
    val pendingDelete = remember { mutableStateOf<ViewerPhoto?>(null) }
    fun dropFromViewer(target: ViewerPhoto) {
        viewerRequest = viewerRequest?.let { req ->
            val remaining = req.photos.filterNot {
                it.baseCacheKey != null && it.baseCacheKey == target.baseCacheKey
            }
            if (remaining.isEmpty()) null else req.copy(photos = remaining)
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val target = pendingDelete.value
        pendingDelete.value = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            dropFromViewer(target)
            uiScope.launch { snackbarHostState.showSnackbar("Fotoğraf silindi") }
        }
    }
    val handlePhotoDelete: (ViewerPhoto) -> Unit = { target ->
        when (val model = target.model) {
            is Uri -> {
                val sender = ViewerActions.createLocalDeleteRequest(context, model)
                if (sender != null) {
                    pendingDelete.value = target
                    deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    uiScope.launch {
                        snackbarHostState.showSnackbar("Bu dosya için silme desteklenmiyor")
                    }
                }
            }

            is SmbImage -> viewModel.deleteCloudPhoto(model.path) { ok ->
                if (ok) {
                    dropFromViewer(target)
                    uiScope.launch { snackbarHostState.showSnackbar("SMP'den silindi") }
                } else {
                    uiScope.launch {
                        snackbarHostState.showSnackbar("Silinemedi — bağlantıyı kontrol edin")
                    }
                }
            }
        }
    }

    // Birleşik listenin düz hâli: görüntüleyici tüm zaman tünelini alır.
    // Izgaradaki sırayla birebir aynı olduğundan tıklanan hücrenin indeksi
    // doğrudan bulunur (Local/Cloud karışımı tek listede yaşar).
    val galleryPhotos = remember(state.groups) {
        buildList {
            state.groups.forEach { group ->
                group.photos.forEach { ui ->
                    when (ui) {
                        is GalleryUiItem.Device -> add(
                            ViewerPhoto(
                                model = ui.item.photo.uri,
                                title = ui.item.photo.displayName,
                                baseCacheKey = CacheKeyHelper.localKey(
                                    uri = ui.item.photo.uri.toString(),
                                    dateModified = ui.item.photo.dateModified,
                                    sizeBytes = ui.item.photo.sizeBytes
                                ),
                                dateMillis = ui.item.photo.sortDate,
                                sizeBytes = ui.item.photo.sizeBytes,
                                mimeType = ui.item.photo.mimeType,
                                // Cihaz canlı fotoğrafı (PXL_*.MP.jpg): görüntüleyici
                                // GP gibi davranır, hareket trailer'dan çıkarılır.
                                isLivePhoto = !isVideoMime(ui.item.photo.mimeType) &&
                                    isLivePhotoName(ui.item.photo.displayName)
                            )
                        )

                        is GalleryUiItem.Cloud -> add(
                            ViewerPhoto(
                                model = ui.photo.toCoilModel(),
                                title = ui.photo.displayName,
                                baseCacheKey = CacheKeyHelper.smbKey(
                                    path = ui.photo.path,
                                    lastModified = ui.photo.lastModified,
                                    source = ui.photo.source
                                ),
                                // Görüntüleyici başlığı, fotoğrafın ızgarada
                                // altında durduğu grupla AYNI tarihi gösterir
                                // (sortMillis ile aynı öncelik sırası).
                                dateMillis = ui.photo.capturedMillis ?: ui.photo.lastModified,
                                sizeBytes = ui.photo.sizeBytes,
                                // Video MIME'ı taşınır: ViewerScreen böylece
                                // InlineVideoPlayer sayfasını açar (GP gibi).
                                mimeType = if (ui.photo.isVideo) {
                                    videoMimeFromName(ui.photo.displayName)
                                } else {
                                    null
                                },
                                // SMP canlı fotoğrafı: eşleşen .mp4 indirilip
                                // görüntüleyicide GP gibi oynatılır.
                                isLivePhoto = !ui.photo.isVideo &&
                                    ui.photo.liveVideoPath != null,
                                liveVideoSmbPath = ui.photo.liveVideoPath,
                                // Canlı videonun hangi sunucudan ineceği
                                // (Nextcloud çiftinde mp4 de WebDAV'da).
                                cloudSource = ui.photo.source
                            )
                        )
                    }
                }
            }
        }
    }
    val galleryIndexByKey = remember(galleryPhotos) {
        galleryPhotos.withIndex().associate { it.value.baseCacheKey to it.index }
    }

    // ── Çoklu seçim eylemleri ──
    // Seçili ViewerPhoto'nun anahtarı: cihaz uri'si / SMP yolu.
    fun viewerKey(photo: ViewerPhoto): String = when (val m = photo.model) {
        is Uri -> m.toString()
        is SmbImage -> "smb:${m.path}"
        else -> photo.baseCacheKey ?: photo.title
    }

    val selectedPhotos = remember(galleryPhotos, selectedKeys) {
        galleryPhotos.filter { viewerKey(it) in selectedKeys }
    }

    val multiDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedKeys = emptySet()
            uiScope.launch { snackbarHostState.showSnackbar("Seçilen fotoğraflar silindi") }
        }
    }

    // Seçilenleri topluca sil: cihaz için tek sistem onayı, SMP için sunucuda.
    val deleteSelection: () -> Unit = {
        selectedPhotos.filter { it.model is SmbImage }.forEach { photo ->
            (photo.model as SmbImage).let { smb ->
                viewModel.deleteCloudPhoto(smb.path) { }
            }
        }
        val uris = selectedPhotos.mapNotNull { it.model as? Uri }
        if (uris.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
            val sender = runCatching {
                android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
            }.getOrNull()
            if (sender != null) {
                multiDeleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
        } else if (selectedPhotos.isNotEmpty()) {
            selectedKeys = emptySet()
            uiScope.launch { snackbarHostState.showSnackbar("SMP'den silindi") }
        }
    }

    // Seçilenleri topluca paylaş (cihaz doğrudan; SMP önce önbelleğe).
    val shareSelection: () -> Unit = {
        val localUris = selectedPhotos.mapNotNull { it.model as? Uri }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (localUris.isNotEmpty()) {
            intent.putParcelableArrayListExtra(
                android.content.Intent.EXTRA_STREAM,
                ArrayList(localUris)
            )
            context.startActivity(android.content.Intent.createChooser(intent, "Paylaş"))
        } else {
            uiScope.launch {
                snackbarHostState.showSnackbar("SMP fotoğrafları hazırlanıyor…")
                val prepared = selectedPhotos.mapNotNull {
                    ViewerActions.prepareShareUri(context, it)
                }
                if (prepared.isNotEmpty()) {
                    val cloudIntent =
                        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "*/*"
                            putParcelableArrayListExtra(
                                android.content.Intent.EXTRA_STREAM,
                                ArrayList(prepared)
                            )
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(
                        android.content.Intent.createChooser(cloudIntent, "Paylaş")
                    )
                }
            }
        }
    }

    // ── Anılar ──
    // Gün aşırı yenilenir: iki gün içinde aynı 8 kutu korunur (boşuna
    // hesap + kapak zıplaması yok). Bulut öğeleri de tarihleriyle dahildir.
    val memories = remember(galleryPhotos) {
        // SMP videoları anı kartlarına GİRMEZ: kareleri arka planda henüz
        // üretilmemiş olabilir → gri kapak kartı olurdu. Cihaz videoları
        // eskiden beri dahil (kapakları yerelden anında çözülür).
        MemoriesCache.load(
            context,
            galleryPhotos.filterNot {
                val model = it.model
                model is SmbImage && model.isVideo
            }
        )
    }

    // Uygulama öne geldiğinde eksikleri otomatik tamamla.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.retryMissingOnResume()
        }
    }

    // Tüm eksikler bittiğinde kısa bir bilgi göster.
    LaunchedEffect(viewModel) {
        viewModel.missingCompleted.collect {
            snackbarHostState.showSnackbar("Eksik fotoğraflar tamamlandı")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermission()
    }

    // İlk açılışta izin iste; kullanıcı reddederse ekrandaki buton tekrar sorar.
    LaunchedEffect(Unit) {
        if (!hasImagePermission(context)) {
            permissionLauncher.launch(requiredImagePermissions())
        }
    }

    // SMP kullanıcı klasörü arka planda taranır; bulut fotoğrafları hazır
    // olunca zaman tünelinin içine kendiliğinden dağılır (sekme yok, bekleme yok).
    LaunchedEffect(Unit) {
        viewModel.refreshCloud()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Seçim modunda üst çubuk: kapat · "N seçildi" · paylaş · sil.
            if (selectionMode) {
                TopAppBar(
                    title = { Text("${selectedKeys.size} seçildi") },
                    navigationIcon = {
                        IconButton(onClick = { selectedKeys = emptySet() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Seçimi kapat"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = shareSelection) {
                            Icon(Icons.Filled.Share, contentDescription = "Paylaş")
                        }
                        IconButton(onClick = deleteSelection) {
                            Icon(Icons.Filled.Delete, contentDescription = "Sil")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Fotoğraflar") },
                    actions = {
                        Text(
                            text = "${state.backedUpCount}/${state.totalPhotos}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        // Dişli: zamantünelinde hangi klasörlerin görüneceğini seçtirir
                        // (cihaz klasörleri + SMP klasörleri). Eski "yenile" oku kaldırıldı.
                        IconButton(onClick = { folderDialogOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Klasörler")
                        }
                    }
                )
            }

            // (Not: "N eksik" toplu yenileme butonu kaldırıldı — her hücrenin
            // kendi üstünde tekil yenileme rozeti zaten var; uygulama öne
            // gelince eksikler arka planda otomatik tamamlanmaya devam eder.)

            // EXIF tarih onarımı ilerlemesi: ince çubuk + tek satır bilgi.
            repairProgress?.let { rp ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { rp.done.toFloat() / rp.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Fotoğraf tarihleri düzeltiliyor: ${rp.done}/${rp.total}" +
                            " (${rp.fixed} düzeltildi)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            UnifiedGallery(
                state = state,
                imageStates = imageStates,
                memories = memories,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onMemoryClick = { memoryPhotos ->
                    // Anı kartı slayt gösterisi olarak açılır (otomatik ilerler).
                    viewerRequest = PhotoViewerRequest(memoryPhotos, 0, autoPlay = true)
                },
                onRequestPermission = {
                    permissionLauncher.launch(requiredImagePermissions())
                },
                onCloudRetry = { viewModel.refreshCloud(force = true) },
                onCloudSetup = onOpenSettings,
                onImageStateChange = { ui, key, loadState ->
                    // Yerel snapshot harita DOĞRUDAN güncellenir. Daha önce bu
                    // yol ViewModel → StateFlow → kopya dönüşüydü ve her küçük
                    // resimde binlerce girişlik map kopyalanıyordu (GC fırtınası).
                    // PENDING yazılmaz: bekleme rozeti yok, gereksiz yazım olur.
                    if (loadState != ImageLoadState.PENDING) {
                        imageStates[key] = loadState
                    }
                    when (ui) {
                        is GalleryUiItem.Device -> viewModel.reportImageState(
                            cacheKey = key,
                            state = loadState,
                            source = MissingPhotoSource.LOCAL,
                            model = ui.item.photo.uri.toString(),
                            lastModified = ui.item.photo.dateModified
                        )

                        is GalleryUiItem.Cloud -> {
                            // Videolar eksik kuyruğuna YAZILMAZ: "küçük resim
                            // hazırlanıyor" bir arıza değil, warmer'ın sıraya
                            // aldığı normal bir iştir.
                            if (!ui.photo.isVideo) {
                                viewModel.reportImageState(
                                    cacheKey = key,
                                    state = loadState,
                                    source = when (ui.photo.source) {
                                        MediaSourceKind.SMB -> MissingPhotoSource.SMB
                                        MediaSourceKind.NEXTCLOUD ->
                                            MissingPhotoSource.NEXTCLOUD
                                    },
                                    model = ui.photo.path,
                                    lastModified = ui.photo.lastModified
                                )
                            }
                        }
                    }
                },
                onPhotoClick = { ui ->
                    // Seçim modunda dokunma seçimi açar/kapatır.
                    if (selectionMode) {
                        val k = itemKey(ui)
                        selectedKeys = if (k in selectedKeys) selectedKeys - k else selectedKeys + k
                        return@UnifiedGallery
                    }
                    val key = when (ui) {
                        is GalleryUiItem.Device -> CacheKeyHelper.localKey(
                            uri = ui.item.photo.uri.toString(),
                            dateModified = ui.item.photo.dateModified,
                            sizeBytes = ui.item.photo.sizeBytes
                        )

                        is GalleryUiItem.Cloud -> CacheKeyHelper.smbKey(
                            path = ui.photo.path,
                            lastModified = ui.photo.lastModified,
                            source = ui.photo.source
                        )
                    }
                    val index = galleryIndexByKey[key] ?: -1
                    Log.d(
                        "SmpMedia",
                        "Zaman tüneli fotoğrafı açılıyor (${if (ui is GalleryUiItem.Cloud) "SMP" else "cihaz"}): $key"
                    )
                    if (index >= 0) {
                        viewerRequest = PhotoViewerRequest(galleryPhotos, index)
                    }
                },
                selectedKeys = selectedKeys,
                selectionMode = selectionMode,
                onLongSelect = { ui ->
                    val k = itemKey(ui)
                    selectedKeys = if (k in selectedKeys) selectedKeys - k else selectedKeys + k
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    viewerRequest?.let { request ->
        PhotoViewerDialog(
            photos = request.photos,
            initialIndex = request.initialIndex,
            autoPlay = request.autoPlay,
            onDismiss = { viewerRequest = null },
            onPhotoDeleted = handlePhotoDelete
        )
    }

    if (folderDialogOpen) {
        FolderFilterDialog(
            folders = folderOptions,
            onToggle = viewModel::toggleFolder,
            onShowAll = viewModel::showAllFolders,
            onRefreshCloud = { viewModel.refreshCloud(force = true) },
            onRepairDates = {
                // Diyaloğu kapat ki ilerleme çubuğu zamantünelinin üstünde görünsün.
                folderDialogOpen = false
                viewModel.repairCloudDates()
            },
            onDismiss = { folderDialogOpen = false }
        )
    }
}

/**
 * "Fotoğraflar" dişlisi ile açılan klasör seçimi.
 * Cihaz (MediaStore bucket) ve SMP klasörleri tek listede; işaretli olanlar
 * zamantüneline dahil edilir. Varsayılan: hepsi görünür.
 */
@Composable
private fun FolderFilterDialog(
    folders: List<FolderOption>,
    onToggle: (String) -> Unit,
    onShowAll: () -> Unit,
    onRefreshCloud: () -> Unit,
    onRepairDates: () -> Unit,
    onDismiss: () -> Unit
) {
    // SMP klasörleri ÜSTTE tutulur: uzun cihaz listesinde ekranın altına
    // kaçıp "SMP listesi hiç yok" izlenimi vermesin (kullanıcı şikâyeti).
    val cloudFolders = folders.filter { it.isCloud }
    val deviceFolders = folders.filterNot { it.isCloud }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Görünecek klasörler") },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                Text(
                    text = "Fotoğraflar zaman tünelinde yalnızca işaretli klasörler görünür. " +
                        "SMP'de bir üst klasörü işaretleyince alt klasörlerdeki " +
                        "fotoğraflar da gelir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (folders.isEmpty()) {
                    Text(
                        text = "Henüz klasör bulunamadı. \"SMP yenile\" ile tarayın.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn {
                        if (cloudFolders.isNotEmpty()) {
                            item(key = "header-smp") { FolderSectionHeader("SMP (bulut)") }
                            lazyColumnItems(cloudFolders, key = { it.key }) { folder ->
                                FolderFilterRow(folder, onToggle)
                            }
                        }
                        if (deviceFolders.isNotEmpty()) {
                            item(key = "header-cihaz") { FolderSectionHeader("Cihazda") }
                            lazyColumnItems(deviceFolders, key = { it.key }) { folder ->
                                FolderFilterRow(folder, onToggle)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tamam") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onShowAll) { Text("Tümü") }
                TextButton(onClick = onRefreshCloud) { Text("SMP yenile") }
                // Adında tarih olmayan SMP fotoğraflarının EXIF tarihini okuyup
                // hem zaman tünelini hem NAS'taki dosya tarihini düzeltir.
                TextButton(onClick = onRepairDates) { Text("Tarih onar") }
            }
        }
    )
}

/** Bölüm başlığı: "SMP (bulut)" / "Cihazda". Küçük, çentik gibi durur. */
@Composable
private fun FolderSectionHeader(title: String) {
    Text(
        text = title.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 2.dp)
    )
}

/**
 * Tek klasör satırı: kutucuk · simge · ad (+ SMP'de "alt klasörler dahil"
 * notu) · fotoğraf sayısı. Satırın tamamı tıklanabilir.
 */
@Composable
private fun FolderFilterRow(folder: FolderOption, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(folder.key) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = folder.visible,
            onCheckedChange = { onToggle(folder.key) }
        )
        Icon(
            imageVector = if (folder.isCloud) {
                Icons.Outlined.Cloud
            } else {
                Icons.Outlined.Folder
            },
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (folder.subtitle != null) {
                Text(
                    text = folder.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = "${folder.count}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Birleşik zaman tüneli ızgarası: ay başlığı → gün başlığı → fotoğraflar.
 * Hücre türleri: cihaz (PhotoGridCell) ve bulut (CloudGridCell).
 */
@Composable
private fun UnifiedGallery(
    state: MainUiState,
    imageStates: Map<String, ImageLoadState>,
    memories: List<MemoryItem>,
    modifier: Modifier,
    onMemoryClick: (List<ViewerPhoto>) -> Unit,
    onRequestPermission: () -> Unit,
    onCloudRetry: () -> Unit,
    onCloudSetup: () -> Unit,
    onImageStateChange: (GalleryUiItem, String, ImageLoadState) -> Unit,
    onPhotoClick: (GalleryUiItem) -> Unit,
    selectedKeys: Set<String> = emptySet(),
    selectionMode: Boolean = false,
    onLongSelect: (GalleryUiItem) -> Unit = {}
) {
    when {
        !state.permissionGranted -> PermissionRequired(
            modifier = modifier,
            onRequest = onRequestPermission
        )

        state.loading -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }

        state.groups.isEmpty() -> EmptyGallery(modifier = modifier)

        else -> {
            val gridState = rememberLazyGridState()
            val hasMemories = memories.isNotEmpty()
            val hasCloudError = state.cloudError != null

            // LazyVerticalGrid item sırasıyla birebir eşleşen ön yükleme
            // listesi. Anı/başlık sıralarında yuva boştur (null).
            val preloadRequests = remember(state.groups, hasMemories) {
                buildList<PreloadImage?> {
                    if (hasMemories) add(null) // anı sırası
                    state.groups.forEach { group ->
                        if (group.showMonthHeader) add(null)
                        add(null) // gün başlığı
                        group.photos.forEach { ui ->
                            when (ui) {
                                is GalleryUiItem.Device -> add(
                                    PreloadImage(
                                        model = ui.item.photo.uri,
                                        baseCacheKey = CacheKeyHelper.localKey(
                                            uri = ui.item.photo.uri.toString(),
                                            dateModified = ui.item.photo.dateModified,
                                            sizeBytes = ui.item.photo.sizeBytes
                                        )
                                    )
                                )

                                is GalleryUiItem.Cloud ->
                                    // Bulut görselleri kaydırma ön yükleme
                                    // kuyruğuna GİRMEZ. Bu NAS SMB1 konuşuyor
                                    // (log: SmbComReadAndX): tek taşıma üzerinde
                                    // okumalar SIRAYA girer, paralelleşmez.
                                    // Read-ahead eklemek hız değil, zaman aşımı
                                    // getirir — tüm ızgarayı kırmızıya boyadı
                                    // (11.08 regresyonu). Hücre kaydırma
                                    // durunca kendi yüklemesini yapar ve
                                    // sonucu diske önbellekler; İKİNCİ geçişte
                                    // anında açılır (Google Photos davranışı).
                                    add(null)
                            }
                        }
                    }
                }
            }
            PreloadGridImages(gridState = gridState, requests = preloadRequests)

            // ── Hareketli küçük resimler (video + Motion Photo) ──
            // NAS boştayma kuralı: kaydırma AKARKEN hiçbir önizleme oynatılmaz;
            // kullanıcı duraklayınca (600 ms) görünen ilk hareketli hücre
            // sessiz döngülü önizlemeye başlar. Uzun basma hücreyi seçer.
            // Yalnızca CİHAZ VİDEOLARI oynatılır; canlı fotoğraflar ızgarada
            // durgun resim olarak görünür (kullanıcı isteği — hareket yalnızca
            // görüntüleyicide) ve SMP hücreleri statik kalır.
            val motionCells = remember(state.groups, hasMemories) {
                buildList<Pair<String, Uri>?> {
                    if (hasMemories) add(null)
                    state.groups.forEach { group ->
                        if (group.showMonthHeader) add(null)
                        add(null)
                        group.photos.forEach { ui ->
                            val animatable = ui is GalleryUiItem.Device &&
                                isVideoMime(ui.item.photo.mimeType)
                            add(
                                if (ui is GalleryUiItem.Device && animatable) {
                                    CacheKeyHelper.localKey(
                                        uri = ui.item.photo.uri.toString(),
                                        dateModified = ui.item.photo.dateModified,
                                        sizeBytes = ui.item.photo.sizeBytes
                                    ) to ui.item.photo.uri
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
            }
            // Sağ kenar göstergesi için: sıra numarası → ait olduğu ay etiketi.
            // Izgara sıralarıyla BİREBİR eşleşir (hata şeridi ve anı sırası
            // kendi etiketine sahip değildir; onlar aşağıdaki ilk erişilebilir
            // etiketle doldurulur ki kaydırma sırasında etiket hiç boş kalmasın).
            val monthLabelByIndex = remember(state.groups, hasMemories, hasCloudError) {
                val raw = buildList<String?> {
                    if (hasCloudError) add(null)
                    if (hasMemories) add(null)
                    state.groups.forEach { group ->
                        if (group.showMonthHeader) add(group.monthLabel)
                        add(group.monthLabel)
                        group.photos.forEach { add(group.monthLabel) }
                    }
                }
                val filled = ArrayList<String?>(raw.size)
                var fallback: String? = null
                raw.asReversed().forEach { value ->
                    fallback = value ?: fallback
                    filled.add(fallback)
                }
                filled.reverse()
                filled
            }

            var activeMotionKey by remember { mutableStateOf<String?>(null) }
            // Kaydırma "tam" durdu mu? Parmak kalktıktan hemen sonra değil,
            // kısa bir süre sakin kalınca true olur. Art arda hızlı kaydırmalarda
            // her duruşta SMB isteği başlatıp iptal etmeyi (churn) önler; bulut
            // hücreleri de yalnızca bu anda ağdan yükler (Google Photos davranışı).
            var gridSettled by remember { mutableStateOf(true) }
            // Sağ kenar göstergesiyle sürükleme olurken ızgara "kaydırılıyor"
            // sayılmaz (scrollToItem bunu tetiklemez); bulut hücreleri yine de
            // yüklemeye başlamamalı — kare kare atlarken NAS'a yüzlerce istek
            // gider (SMB1 tek taşıma). Bu bayrak o gürültüyü keser.
            var isScrubbing by remember { mutableStateOf(false) }
            LaunchedEffect(gridState, motionCells) {
                snapshotFlow { gridState.isScrollInProgress }.collect { scrolling ->
                    if (scrolling) {
                        gridSettled = false
                        activeMotionKey = null
                    } else {
                        delay(180)
                        gridSettled = true
                        // Hareketli önizleme yalnızca uzun süre sakin kalınca
                        // (900 ms) başlar. Her duruşta MediaPlayer/kodek açmak
                        // yerine Google Photos gibi "gerçekten durdun" anını
                        // bekler; kaydırma sırasında hiç kodek çalışmaz.
                        delay(900)
                        val hit = gridState.layoutInfo.visibleItemsInfo
                            .firstNotNullOfOrNull { info -> motionCells.getOrNull(info.index) }
                        activeMotionKey = hit?.first
                    }
                }
            }

            // Izgara + sağ kenar göstergesi aynı kutuda üst üste biner.
            Box(modifier = modifier) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // ── SMP erişim hatası şeridi ──
                // Izgarayı kilitlemez; cihaz fotoğrafları kaydırılabilir kalır.
                if (hasCloudError) {
                    item(
                        key = "cloud_error",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "cloudError"
                    ) {
                        CloudErrorBanner(
                            message = state.cloudError.orEmpty(),
                            onRetry = onCloudRetry,
                            onOpenSettings = onCloudSetup
                        )
                    }
                }

                // ── Anılar sırası (Google Photos "Anılar") ──
                // Her gün içeriği değişir: bugün hangi güne denk geliyorsa.
                if (hasMemories) {
                    item(
                        key = "memories_row",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "memories"
                    ) {
                        MemoryCarousel(memories = memories, onClick = onMemoryClick)
                    }
                }

                state.groups.forEach { group ->
                    if (group.showMonthHeader) {
                        item(
                            key = "month_${group.monthLabel}",
                            span = { GridItemSpan(maxLineSpan) },
                            // contentType: Compose, aynı türden öğelerin
                            // kompozisyonunu yeniden kullanabilir; başlık ile
                            // fotoğraf kutusu karışmaz → kararlı kaydırma.
                            contentType = "monthHeader"
                        ) {
                            Text(
                                text = group.monthLabel,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(
                                    start = 8.dp,
                                    end = 8.dp,
                                    top = 16.dp,
                                    bottom = 4.dp
                                )
                            )
                        }
                    }
                    item(
                        key = "header_${group.dateMillis}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "dateHeader"
                    ) {
                        Text(
                            text = group.dateLabel,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
                        )
                    }
                    items(
                        items = group.photos,
                        key = { ui ->
                            when (ui) {
                                is GalleryUiItem.Device -> ui.item.photo.uri.toString()
                                is GalleryUiItem.Cloud -> "smb_photo_${ui.photo.path}"
                            }
                        },
                        contentType = { ui ->
                            when (ui) {
                                is GalleryUiItem.Device -> "photo"
                                is GalleryUiItem.Cloud -> "smbPhoto"
                            }
                        }
                    ) { ui ->
                        when (ui) {
                            is GalleryUiItem.Device -> {
                                val cellKey = CacheKeyHelper.localKey(
                                    uri = ui.item.photo.uri.toString(),
                                    dateModified = ui.item.photo.dateModified,
                                    sizeBytes = ui.item.photo.sizeBytes
                                )
                                PhotoGridCell(
                                    item = ui.item,
                                    imageStates = imageStates,
                                    motionActive = activeMotionKey == cellKey,
                                    selected = ui.item.photo.uri.toString() in selectedKeys,
                                    selectionMode = selectionMode,
                                    onLongClick = { onLongSelect(ui) },
                                    onImageStateChange = { key, loadState ->
                                        onImageStateChange(ui, key, loadState)
                                    },
                                    onClick = { onPhotoClick(ui) }
                                )
                            }

                            is GalleryUiItem.Cloud -> CloudGridCell(
                                photo = ui.photo,
                                imageStates = imageStates,
                                deferLoad = !gridSettled || isScrubbing,
                                selected = "smb:${ui.photo.path}" in selectedKeys,
                                selectionMode = selectionMode,
                                onLongClick = { onLongSelect(ui) },
                                onImageStateChange = { key, loadState ->
                                    onImageStateChange(ui, key, loadState)
                                },
                                onClick = { onPhotoClick(ui) }
                            )
                        }
                    }
                }
            }

            // ── Sağ kenar hızlı kaydırma göstergesi (Google Photos) ──
            // Kaydırma başlayınca belirir; "Ay Yılı" etiketi parmakla kavranıp
            // aşağı/yukarı sürüklenerek kronolojide hızlıca gezinilir.
            TimelineScrubber(
                gridState = gridState,
                labelForIndex = { index -> monthLabelByIndex.getOrNull(index) },
                onScrubbing = { scrubbing -> isScrubbing = scrubbing },
                modifier = Modifier.matchParentSize()
            )
            }
        }
    }
}

/** Video dosya uzantısı → MIME (InlineVideoPlayer/MediaPlayer bu türleri çözer). */
private fun videoMimeFromName(name: String): String =
    when (name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

/** SMP tarama hatası: tek satırlık, ızgarayı bloklamayan bilgi şeridi. */
@Composable
private fun CloudErrorBanner(    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "SMP fotoğrafları alınamadı: $message",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onRetry) { Text("Yinele") }
        TextButton(onClick = onOpenSettings) { Text("Ayarlar") }
    }
}

/**
 * Anılar bölümü (Google Photos): "Anılar" başlığı + TEK satırda 8 kart.
 *
 * Kullanıcı isteği: 4+4 alt alta değil, yan yana 8 anı; tek sıra hâlinde
 * yana kaydırılabilir. Kart sayısı daima 8'e tamamlanır (Memories.kt),
 * içerik günde bir yenilenir ve kart kapakları sabit kalır.
 */
@Composable
private fun MemoryCarousel(
    memories: List<MemoryItem>,
    onClick: (List<ViewerPhoto>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Anılar",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            lazyRowItems(memories.take(8), key = { "memory_${it.id}" }) { memory ->
                MemoryCard(memory = memory, onClick = onClick)
            }
        }
    }
}

/** Tek anı kartı: kapak SABİT (dönen slayt/animasyon yok). */
@Composable
private fun MemoryCard(
    memory: MemoryItem,
    onClick: (List<ViewerPhoto>) -> Unit
) {
    val context = LocalContext.current
    // Kapak: her zaman ilk fotoğraf. Anlık değişim yok.
    val cover = remember(memory) { memory.photos.firstOrNull() }

    Box(
        modifier = Modifier
            .width(126.dp)
            .height(168.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick(memory.photos) }
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover.baseCacheKey?.let { key ->
                    ImageRequest.Builder(context)
                        .data(cover.model)
                        .memoryCacheKey(CacheKeyHelper.thumbnailMemoryKey(key))
                        .diskCacheKey(key)
                        .size(256, 256)
                        .build()
                } ?: cover.model,
                contentDescription = memory.title,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Alt karartma + başlık katmanı (okunabilirlik için).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                ),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(modifier = Modifier.padding(6.dp)) {
                Text(
                    text = memory.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PermissionRequired(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Fotoğraflara erişim gerekli", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Yedeklenecek fotoğrafları listelemek için galeri izni verin.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest) {
            Text("İzin Ver")
        }
    }
}

@Composable
private fun EmptyGallery(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Henüz fotoğraf yok", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Galerinizde ve SMP kullanıcı klasörünüzde görünen fotoğraflar " +
                "burada tarihe göre listelenir.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
