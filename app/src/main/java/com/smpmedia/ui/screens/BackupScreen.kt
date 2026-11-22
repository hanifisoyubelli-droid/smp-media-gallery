package com.smpmedia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smpmedia.data.local.BackupRecordEntity
import com.smpmedia.data.local.FolderMappingEntity
import com.smpmedia.data.model.BackupStatus
import com.smpmedia.ui.components.StatusIcon
import com.smpmedia.ui.viewmodel.BackupUiState
import com.smpmedia.ui.viewmodel.BackupViewModel
import com.smpmedia.util.formatBytes
import com.smpmedia.util.formatDateTime

/** Yedekleme durumu, eşleştirmeler ve son işlem kayıtları. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onOpenMappings: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Yedekleme") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                BackupStatusCard(
                    state = state,
                    onStart = viewModel::startNow,
                    onOpenMappings = onOpenMappings
                )
            }

            if (state.mappings.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Henüz eşleştirme yok",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Yedekleme başlamadan önce SMB ayarlarını girin ve " +
                                    "bir yerel klasörü SMB klasörüyle eşleştirin.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onOpenMappings) {
                                    Text("Klasör Eşleştir")
                                }
                                OutlinedButton(onClick = onOpenSettings) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("SMB Ayarları")
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text("Eşleştirmeler", style = MaterialTheme.typography.titleMedium)
                }
                items(items = state.mappings, key = { it.id }) { mapping ->
                    MappingRow(
                        mapping = mapping,
                        onToggle = { enabled -> viewModel.toggleMapping(mapping.id, enabled) },
                        onDelete = { viewModel.deleteMapping(mapping.id) }
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Son İşlemler", style = MaterialTheme.typography.titleMedium)
            }

            if (state.records.isEmpty()) {
                item {
                    Text(
                        text = "Henüz yedekleme kaydı yok.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(
                    items = state.records,
                    key = { record -> "${record.mappingId}_${record.localKey}" }
                ) { record ->
                    RecordRow(record)
                }
            }
        }
    }
}

@Composable
private fun BackupStatusCard(
    state: BackupUiState,
    onStart: () -> Unit,
    onOpenMappings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (state.running) "Yedekleme çalışıyor" else "Yedekleme hazır",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            if (state.running) {
                LinearProgressIndicator(
                    progress = { state.progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${state.progress.folderName} · ${state.progress.currentFile}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${state.progress.done}/${state.progress.total} fotoğraf",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "${state.mappings.count { it.enabled }} aktif eşleştirme · " +
                        "${state.successCount} başarılı · ${state.failedCount} hata",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = !state.running,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Şimdi Yedekle")
                }
                OutlinedButton(
                    onClick = onOpenMappings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Eşleştir")
                }
            }
        }
    }
}

@Composable
private fun MappingRow(
    mapping: FolderMappingEntity,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(mapping.localFolderName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "→ \\\\${mapping.smbShare}\\${mapping.smbPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                mapping.lastBackupAt?.let { time ->
                    Text(
                        text = "Son: ${formatDateTime(time)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = mapping.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Eşleştirmeyi sil")
            }
        }
    }
}

@Composable
private fun RecordRow(record: BackupRecordEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIcon(record.status)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = record.remotePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            record.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${formatDateTime(record.updatedAt)} · ${formatBytes(record.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = statusLabel(record.status),
            style = MaterialTheme.typography.labelSmall,
            color = when (record.status) {
                BackupStatus.SUCCESS -> MaterialTheme.colorScheme.primary
                BackupStatus.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun statusLabel(status: BackupStatus): String = when (status) {
    BackupStatus.PENDING -> "Bekliyor"
    BackupStatus.UPLOADING -> "Yükleniyor"
    BackupStatus.SUCCESS -> "Yedekli"
    BackupStatus.FAILED -> "Hata"
}
