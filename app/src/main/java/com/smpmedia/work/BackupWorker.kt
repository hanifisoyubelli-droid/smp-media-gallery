package com.smpmedia.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smpmedia.data.prefs.SmbSettingsStore
import com.smpmedia.data.repository.BackupRepository
import com.smpmedia.data.repository.SmbRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager işçisi. Periyodik veya "Şimdi Yedekle" ile tetiklenir.
 * Bağlantı hatasında üstel geri çekilme (backoff) ile yeniden denenir.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val settingsStore: SmbSettingsStore
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = settingsStore.current()
        if (!settings.isConfigured) {
            return Result.success(
                workDataOf(KEY_MESSAGE to "SMB ayarları eksik. Yedekleme atlandı.")
            )
        }

        return try {
            val summary = backupRepository.runBackup()
            val output = workDataOf(
                KEY_UPLOADED to summary.uploaded,
                KEY_FAILED to summary.failed,
                KEY_SKIPPED to summary.skipped,
                KEY_MESSAGE to summary.message
            )
            Result.success(output)
        } catch (t: Throwable) {
            // Geçici ağ hatalarında yeniden dene; kalıcı hatalarda işi bitir.
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(
                    workDataOf(KEY_MESSAGE to SmbRepository.describeError(t))
                )
            }
        }
    }

    companion object {
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_SKIPPED = "skipped"
        const val KEY_MESSAGE = "message"
        private const val MAX_RETRIES = 5
    }
}
