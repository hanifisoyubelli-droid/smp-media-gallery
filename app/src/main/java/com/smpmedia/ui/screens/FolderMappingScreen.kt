package com.smpmedia.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smpmedia.ui.viewmodel.FolderMappingViewModel
import com.smpmedia.util.formatDateTime

/**
 * İki adımlı eşleştirme:
 *  1) Telefondaki bir klasör (MediaStore bucket) seçilir.
 *  2) SMB üzerinde hedef klasör gezilip seçilir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderMappingScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    initialBucketId: String = "",
    viewModel: FolderMappingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Koleksiyonlar sekmesinden gelindiyse ilgili klasörü seçili bırak.
    LaunchedEffect(initialBucketId) {
        if (initialBucketId.isNotBlank()) {
            viewModel.selectBucket(initialBucketId)
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Klasör Eşleştirme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (!state.settingsConfigured) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("SMB ayarları gerekli", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Klasör eşleştirmek için önce sunucu IP adresini ve " +
                                "paylaşım adını kaydedin.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onOpenSettings) {
                            Text("Ayarları Aç")
                        }
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text("1. Yerel klasör", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (state.localFolders.isEmpty()) {
                Text(
                    text = "Fotoğraf izni verilmedi veya klasör bulunamadı.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items = state.localFolders, key = { it.bucketId }) { folder ->
                        FilterChip(
                            selected = folder.bucketId == state.selectedBucketId,
                            onClick = { viewModel.selectBucket(folder.bucketId) },
                            label = { Text("${folder.name} (${folder.photoCount})") }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("2. SMB hedef klasörü", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = viewModel::goUp,
                    enabled = state.smbPath.isNotBlank()
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Üst klasör")
                }
                Text(
                    text = if (state.smbPath.isBlank()) "/" else "/${state.smbPath}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = state.smbFolders, key = { it.path }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(formatDateTime(entry.lastModified))
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                        },
                        modifier = Modifier.clickable { viewModel.openSmbPath(entry.path) }
                    )
                }
                if (state.smbFolders.isEmpty() && !state.loading) {
                    item {
                        Text(
                            text = "Bu klasörde alt klasör yok. Yine de burayı hedef " +
                                "seçebilirsiniz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::addMapping,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Seçili klasörü eşleştir")
            }
        }
    }
}
