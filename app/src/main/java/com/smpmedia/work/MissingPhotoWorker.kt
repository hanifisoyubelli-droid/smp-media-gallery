package com.smpmedia.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smpmedia.data.image.MissingPhotoRefresher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uygulama kapalıyken eksik küçük resimleri tamamlayan periyodik işçi.
 * Yalnızca ölçülmemiş ağ (WiFi) bağlıyken çalışır; pil dostudur.
 */
@HiltWorker
class MissingPhotoWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val missingPhotoRefresher: MissingPhotoRefresher
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        missingPhotoRefresher.retryMissing(
            limit = MAX_PER_RUN,
            maxParallel = 2
        )
        Result.success()
    } catch (t: Throwable) {
        // Geçici ağ hatalarında üstel geri çekilme ile yeniden dene.
        if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
    }

    companion object {
        private const val MAX_PER_RUN = 50
        private const val MAX_RETRIES = 3
    }
}

/** Eksik fotoğraf tamamlama işini planlar. */
@Singleton
class MissingPhotoScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<MissingPhotoWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    // Sadece WiFi/ölçülmemiş ağ: hücresel veriyi tüketmez.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        private const val TAG = "missing_photo_refresh"
        private const val UNIQUE_WORK = "missing_photo_refresh_periodic"
        private const val INTERVAL_HOURS = 12L
    }
}