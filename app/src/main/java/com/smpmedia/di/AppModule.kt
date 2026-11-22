package com.smpmedia.di

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smpmedia.data.local.AppDatabase
import com.smpmedia.data.local.BackupRecordDao
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.local.MissingPhotoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "smp_media.db")
            // Sürüm 1 -> 2: eşleştirme/geçmiş kayıtlarını korumak için tabloyu ekler.
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideFolderMappingDao(database: AppDatabase): FolderMappingDao =
        database.folderMappingDao()

    @Provides
    fun provideBackupRecordDao(database: AppDatabase): BackupRecordDao =
        database.backupRecordDao()

    @Provides
    fun provideMissingPhotoDao(database: AppDatabase): MissingPhotoDao =
        database.missingPhotoDao()

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS missing_photos (
                    cacheKey TEXT NOT NULL,
                    source TEXT NOT NULL,
                    model TEXT NOT NULL,
                    lastModified INTEGER NOT NULL,
                    attempts INTEGER NOT NULL,
                    lastError TEXT,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(cacheKey)
                )
                """.trimIndent()
            )
        }
    }
}
