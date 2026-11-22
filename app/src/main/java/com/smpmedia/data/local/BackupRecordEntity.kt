package com.smpmedia.data.local

import androidx.room.Entity
import androidx.room.Index
import com.smpmedia.data.model.BackupStatus

/**
 * Yedeklenen her fotoğraf için tek kayıt.
 * Birincil anahtar (mappingId, localKey) olduğundan aynı fotoğraf aynı hedefe
 * tekrar tekrar kaydedilmez; değişen dosya yeni hash üretir.
 */
@Entity(
    tableName = "backup_records",
    primaryKeys = ["mappingId", "localKey"],
    indices = [
        Index("localUri"),
        Index("status"),
        Index("updatedAt")
    ]
)
data class BackupRecordEntity(
    val mappingId: Long,
    /** photoContentKey() tarafından üretilen SHA-256 özeti. */
    val localKey: String,
    val localUri: String,
    val displayName: String,
    val remotePath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val status: BackupStatus,
    val errorMessage: String? = null,
    val attempts: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
