package com.smpmedia.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Yerel klasör (MediaStore bucket) ile SMB hedef klasörü arasındaki eşleştirme.
 * Aynı bucket + paylaşım + yol ikinci kez eklenemez.
 */
@Entity(
    tableName = "folder_mappings",
    indices = [
        Index(value = ["localBucketId", "smbShare", "smbPath"], unique = true)
    ]
)
data class FolderMappingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val localBucketId: String,
    val localFolderName: String,
    val smbShare: String,
    /** Paylaşım köküne göre göreli klasör yolu; kök için boş string. */
    val smbPath: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastBackupAt: Long? = null
)
