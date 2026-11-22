package com.smpmedia.data.repository

import android.content.ContentResolver
import com.smpmedia.data.local.BackupRecordDao
import com.smpmedia.data.local.BackupRecordEntity
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.model.BackupProgress
import com.smpmedia.data.model.BackupStatus
import com.smpmedia.data.model.BackupSummary
import com.smpmedia.data.prefs.SmbSettingsStore
import com.smpmedia.di.IoDispatcher
import com.smpmedia.util.photoContentKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Yedekleme akışının beyni:
 *  - eşleştirilmiş her klasörü gezer,
 *  - fotoğrafın hash'ini Room'daki kayıtlarla karşılaştırır,
 *  - yeni/değişmiş dosyaları SMB'ye yükler,
 *  - sonucu tekrar yüklemeyi önleyecek şekilde kaydeder.
 */
@Singleton
class BackupRepository @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val smbRepository: SmbRepository,
    private val mappingDao: FolderMappingDao,
    private val recordDao: BackupRecordDao,
    private val settingsStore: SmbSettingsStore,
    private val contentResolver: ContentResolver,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _progress = MutableStateFlow(BackupProgress())
    val progress: StateFlow<BackupProgress> = _progress.asStateFlow()

    suspend fun runBackup(): BackupSummary = withContext(ioDispatcher) {
        if (!_isRunning.compareAndSet(false, true)) {
            return@withContext BackupSummary(
                alreadyRunning = true,
                message = "Yedekleme zaten çalışıyor."
            )
        }
        try {
            executeBackup()
        } finally {
            _isRunning.value = false
            _progress.value = BackupProgress()
        }
    }

    private suspend fun executeBackup(): BackupSummary {
        val settings = settingsStore.current()
        if (!settings.isConfigured) {
            return BackupSummary(
                message = "SMB ayarları eksik. Ayarlar ekranından sunucu bilgilerini girin."
            )
        }
        if (!photoRepository.hasImagePermission()) {
            return BackupSummary(message = "Fotoğraflara erişim izni verilmedi.")
        }

        val mappings = mappingDao.getEnabledMappings()
        if (mappings.isEmpty()) {
            return BackupSummary(message = "Eşleştirilmiş klasör bulunamadı.")
        }

        var uploaded = 0
        var failed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        smbRepository.openSession(settings).use { session ->
            for (mapping in mappings) {
                val photos = photoRepository.getPhotosForBucket(mapping.localBucketId)
                if (photos.isEmpty()) {
                    mappingDao.markBackedUp(mapping.id, System.currentTimeMillis())
                    continue
                }

                _progress.value = BackupProgress(
                    folderName = mapping.localFolderName,
                    done = 0,
                    total = photos.size
                )

                for ((index, photo) in photos.withIndex()) {
                    _progress.value = _progress.value.copy(
                        done = index,
                        currentFile = photo.displayName
                    )

                    val key = photoContentKey(photo)
                    val existing = recordDao.get(mapping.id, key)
                    if (existing?.status == BackupStatus.SUCCESS) {
                        skipped++
                        _progress.value = _progress.value.copy(done = index + 1)
                        continue
                    }

                    val remotePath = joinRemotePath(mapping.smbPath, photo.displayName)
                    val baseRecord = BackupRecordEntity(
                        mappingId = mapping.id,
                        localKey = key,
                        localUri = photo.uri.toString(),
                        displayName = photo.displayName,
                        remotePath = remotePath,
                        sizeBytes = photo.sizeBytes,
                        lastModified = photo.dateModified,
                        status = BackupStatus.UPLOADING,
                        attempts = (existing?.attempts ?: 0) + 1,
                        updatedAt = System.currentTimeMillis()
                    )
                    recordDao.upsert(baseRecord)

                    val result = runCatching {
                        smbRepository.upload(
                            session = session,
                            contentResolver = contentResolver,
                            localUri = photo.uri,
                            remotePath = remotePath,
                            lastModified = photo.dateModified,
                            totalBytes = photo.sizeBytes
                        )
                    }

                    val now = System.currentTimeMillis()
                    if (result.isSuccess) {
                        uploaded++
                        recordDao.upsert(
                            baseRecord.copy(
                                status = BackupStatus.SUCCESS,
                                errorMessage = null,
                                updatedAt = now
                            )
                        )
                    } else {
                        failed++
                        val error = SmbRepository.describeError(
                            result.exceptionOrNull() ?: IOException("Bilinmeyen hata")
                        )
                        errors += "${photo.displayName}: $error"
                        recordDao.upsert(
                            baseRecord.copy(
                                status = BackupStatus.FAILED,
                                errorMessage = error,
                                updatedAt = now
                            )
                        )
                    }

                    _progress.value = _progress.value.copy(done = index + 1)
                }

                mappingDao.markBackedUp(mapping.id, System.currentTimeMillis())
            }
        }

        val message = buildString {
            append("$uploaded fotoğraf yüklendi, $skipped atlandı, $failed hata.")
            errors.firstOrNull()?.let { append(" İlk hata: $it") }
        }
        return BackupSummary(
            uploaded = uploaded,
            failed = failed,
            skipped = skipped,
            message = message
        )
    }

    private fun joinRemotePath(folder: String, fileName: String): String {
        val cleanFolder = folder.trim().trim('/')
        return if (cleanFolder.isEmpty()) fileName else "$cleanFolder/$fileName"
    }
}
