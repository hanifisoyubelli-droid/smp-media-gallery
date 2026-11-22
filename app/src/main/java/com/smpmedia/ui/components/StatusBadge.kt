package com.smpmedia.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.smpmedia.data.model.BackupStatus

/** Yedekleme durumuna karşılık gelen ikon. */
fun statusIcon(status: BackupStatus): ImageVector = when (status) {
    BackupStatus.SUCCESS -> Icons.Filled.CloudDone
    BackupStatus.UPLOADING -> Icons.Filled.CloudUpload
    BackupStatus.FAILED -> Icons.Filled.Error
    BackupStatus.PENDING -> Icons.Outlined.CloudQueue
}

/** Fotoğraf ızgarasında kullanılan küçük, arka planlı durum rozeti. */
@Composable
fun StatusBadge(status: BackupStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = statusIcon(status),
            contentDescription = null,
            tint = when (status) {
                BackupStatus.SUCCESS -> Color(0xFF66E07A)
                BackupStatus.FAILED -> Color(0xFFFF8A80)
                BackupStatus.UPLOADING -> Color.White
                BackupStatus.PENDING -> Color.White
            },
            modifier = Modifier.size(15.dp)
        )
    }
}

/** Listelerde kullanılan renkli durum ikonu. */
@Composable
fun StatusIcon(status: BackupStatus, modifier: Modifier = Modifier) {
    Icon(
        imageVector = statusIcon(status),
        contentDescription = null,
        tint = when (status) {
            BackupStatus.SUCCESS -> Color(0xFF2E7D32)
            BackupStatus.FAILED -> MaterialTheme.colorScheme.error
            BackupStatus.UPLOADING -> MaterialTheme.colorScheme.primary
            BackupStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.size(20.dp)
    )
}
