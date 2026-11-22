package com.smpmedia.data.local

import androidx.room.TypeConverter
import com.smpmedia.data.model.BackupStatus

/** Room, enum alanlarını metin olarak saklar. */
class Converters {
    @TypeConverter
    fun toStatus(value: String): BackupStatus = BackupStatus.valueOf(value)

    @TypeConverter
    fun fromStatus(status: BackupStatus): String = status.name
}
