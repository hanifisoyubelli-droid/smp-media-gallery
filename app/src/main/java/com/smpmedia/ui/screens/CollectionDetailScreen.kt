package com.smpmedia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smpmedia.data.image.CacheKeyHelper
import com.smpmedia.data.model.ImageLoadState
import com.smpmedia.ui.components.PhotoGridCell
import com.smpmedia.ui.components.PhotoViewerDialog
import com.smpmedia.ui.components.PhotoViewerRequest
import com.smpmedia.ui.components.ViewerPhoto
import com.smpmedia.ui.components.isLivePhotoName
import com.smpmedia.ui.components.isVideoMime
import com.smpmedia.ui.viewmodel.CollectionDetailViewModel

/**
 * Koleksiyon detayı: seçilen klasördeki fotoğraflar.
 * Fotoğrafa dokununca tam ekran yakınlaştırma açılır.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    bucketId: String,
    onBack: () -> Unit,
    onOpenMapping: (String) -> Unit,
    viewModel: CollectionDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var viewerRequest by remember { mutableStateOf<PhotoViewerRequest?>(null) }

    // ── Çoklu seçim (zamantünüyle aynı davranış) ──
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedKeys.isNotEmpty()

    val deleteLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts
            .StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            selectedKeys = emptySet()
        }
    }

    val deleteSelected: () -> Unit = {
        val uris = state.photos.mapNotNull { item ->
            item.photo.uri.toString().takeIf { it in selectedKeys }?.let { item.photo.uri }
        }
        if (uris.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
            val sender = runCatching {
                android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
            }.getOrNull()
            if (sender != null) {
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(sender).build()
                )
            }
        }
    }

    val shareSelected: () -> Unit = {
        val uris = state.photos.mapNotNull { item ->
            item.photo.uri.toString().takeIf { it in selectedKeys }?.let { item.photo.uri }
        }
        if (uris.isNotEmpty()) {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(
                    android.content.Intent.EXTRA_STREAM,
                    ArrayList(uris)
                )
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(android.content.Intent.createChooser(intent, "Paylaş"))
        }
    }

    // Koleksiyon içindeki tüm fotoğraflar tek listede toplanır; böylece
    // görüntüleyicide sağa/sola kaydırma bu klasörde çalışır.
    val viewerPhotos = remember(state.photos) {
        state.photos.map { item ->
            ViewerPhoto(
                model = item.photo.uri,
                title = item.photo.displayName,
                baseCacheKey = CacheKeyHelper.localKey(
                    uri = item.photo.uri.toString(),
                    dateModified = item.photo.dateModified,
                    sizeBytes = item.photo.sizeBytes
                ),
                // Koleksiyonda da canlı fotoğraflar görüntüleyicide GP gibi
                // oynasın (hareket JPEG trailer'ından çıkarılır).
                isLivePhoto = !isVideoMime(item.photo.mimeType) &&
                    isLivePhotoName(item.photo.displayName)
            )
        }
    }
    val indexByCacheKey = remember(viewerPhotos) {
        viewerPhotos.withIndex().associate { it.value.baseCacheKey to it.index }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
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
                        IconButton(onClick = shareSelected) {
                            Icon(Icons.Filled.Share, contentDescription = "Paylaş")
                        }
                        IconButton(onClick = deleteSelected) {
                            Icon(Icons.Filled.Delete, contentDescription = "Sil")
                        }
                    }
                )
            } else {
            TopAppBar(
                title = {
                    Text(
                        text = state.folderName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenMapping(bucketId) }) {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = "Klasörü eşleştir"
                        )
                    }
                }
            )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            CollectionStatusRow(
                hasMapping = state.hasMapping,
                mappingEnabled = state.mappingEnabled,
                targetLabel = state.targetLabel,
                photoCount = state.photos.size
            )
            HorizontalDivider()

            when {
                state.loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.photos.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Bu klasörde fotoğraf yok",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Cihazdan silinen fotoğraflar, Fotoğraflar > SMP " +
                                "sekmesinde klasörlerine göre görünür.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 104.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = state.photos,
                            key = { item -> item.photo.uri.toString() }
                        ) { item ->
                            PhotoGridCell(
                                item = item,
                                imageStates = emptyMap(),
                                selected = item.photo.uri.toString() in selectedKeys,
                                selectionMode = selectionMode,
                                onLongClick = {
                                    val k = item.photo.uri.toString()
                                    selectedKeys = if (k in selectedKeys) {
                                        selectedKeys - k
                                    } else {
                                        selectedKeys + k
                                    }
                                },
                                onImageStateChange = { _, _ -> /* detayda eksik kuyruğu yönetilmez */ },
                                onClick = {
                                    if (selectionMode) {
                                        val k = item.photo.uri.toString()
                                        selectedKeys = if (k in selectedKeys) {
                                            selectedKeys - k
                                        } else {
                                            selectedKeys + k
                                        }
                                        return@PhotoGridCell
                                    }
                                    val key = CacheKeyHelper.localKey(
                                        uri = item.photo.uri.toString(),
                                        dateModified = item.photo.dateModified,
                                        sizeBytes = item.photo.sizeBytes
                                    )
                                    val index = indexByCacheKey[key] ?: -1
                                    if (index >= 0) {
                                        viewerRequest = PhotoViewerRequest(viewerPhotos, index)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        }

        viewerRequest?.let { request ->
            PhotoViewerDialog(
                photos = request.photos,
                initialIndex = request.initialIndex,
                onDismiss = { viewerRequest = null }
            )
        }
    }
}

@Composable
private fun CollectionStatusRow(
    hasMapping: Boolean,
    mappingEnabled: Boolean,
    targetLabel: String?,
    photoCount: Int
) {
    val enabledColor = Color(0xFF2E7D32)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (mappingEnabled) {
                Icons.Filled.CloudDone
            } else {
                Icons.Filled.CloudOff
            },
            contentDescription = null,
            tint = if (mappingEnabled) enabledColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    mappingEnabled -> "Yedekleme açık"
                    hasMapping -> "Yedekleme kapalı"
                    else -> "Eşleştirilmemiş"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (mappingEnabled) enabledColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = targetLabel ?: "Eşleştirmek için üstteki düğmeyi kullanın",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "$photoCount fotoğraf",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
