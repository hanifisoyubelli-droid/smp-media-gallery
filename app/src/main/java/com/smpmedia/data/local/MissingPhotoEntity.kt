package com.smpmedia.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Uygulama kapalıyken de tamamlanması gereken eksik görseller.
 *
 * [model] alanı Coil modelinin metin karşılığıdır:
 * - LOCAL: content://... URI'si
 * - SMB: paylaşım içindeki dosya yolu
 */
@Entity(tableName = "missing_photos")
data class MissingPhotoEntity(
    @PrimaryKey
    val cacheKey: String,
    val source: String,
    val model: String,
    val lastModified: Long = 0L,
    val attempts: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)