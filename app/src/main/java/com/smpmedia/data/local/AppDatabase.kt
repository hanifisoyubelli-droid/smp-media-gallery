package com.smpmedia.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Uygulamanın Room veritabanı.
 *
 * Sürüm 2: eksik kalan SMB/yerel küçük resimleri için `missing_photos`
 * tablosunu ekler. WorkManager bu tabloyu kullanır; uygulama kapalıyken de
 * düşük öncelikli tamamlama yapılabilir.
 */
@Database(
    entities = [
        FolderMappingEntity::class,
        BackupRecordEntity::class,
        MissingPhotoEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun folderMappingDao(): FolderMappingDao
    abstract fun backupRecordDao(): BackupRecordDao
    abstract fun missingPhotoDao(): MissingPhotoDao
}