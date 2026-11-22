package com.smpmedia.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.smpmedia.data.prefs.SmbSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** WorkManager işlerini planlar / iptal eder. */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SmbSettingsStore
) {

    /** Ayarlardaki otomatik yedekleme tercihine göre zamanlamayı günceller. */
    fun syncSchedule() {
        val settings = settingsStore.current()
        if (settings.autoBackup) {
            schedulePeriodic(settings.intervalHours.toLong())
        } else {
            cancelPeriodic()
        }
    }

    fun schedulePeriodic(intervalHours: Long) {
        val interval = intervalHours.coerceIn(1L, 24L)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(interval, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelPeriodic() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
    }

    /** "Şimdi Yedekle" butonu: aynı anda tek iş çalışır. */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    companion object {
        private const val TAG = "smb_backup"
        private const val PERIODIC_WORK = "smb_periodic_backup"
        private const val NOW_WORK = "smb_backup_now"
    }
}
