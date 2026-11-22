package com.smpmedia.data.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smpmedia.MainActivity
import com.smpmedia.R
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.repository.CloudPhotoStore
import com.smpmedia.data.repository.SmbRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Arka plan koruması" tercihi (kullanıcı isteği: uygulama arka planda da
 * çalışsın, pil optimizasyonu v.s.).
 *
 * İki parça:
 * 1. Pil optimizasyonu muafiyeti — sistem Doze sırasında uygulamanın ağ
 *    isteklerini ve uyanışlarını geciktirir; kullanıcı onayıyla bu kalkar.
 * 2. Ön plan hizmeti ([MediaSyncService]) — işlem öldürülmez, SMB oturumu
 *    ve zaman tüneli tazeliğini korur.
 *
 * Tercih kalıcıdır: uygulama yeniden başladığında hizmet otomatik devam eder.
 */
object BgGuard {

    private const val PREFS = "bg_guard_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    fun enable(context: Context) {
        setEnabledPref(context, true)
        runCatching { start(context) }
    }

    fun disable(context: Context) {
        setEnabledPref(context, false)
        runCatching { MediaSyncService.stop(context) }
    }

    private fun setEnabledPref(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Hizmeti başlatır. Android 12+ arka planda başlatmayı reddedebilir
     * (ForegroundServiceStartNotAllowedException); çağıran taraf
     * runCatching ile sarmalar — koruma "varsa iyi", uygulama çökmez.
     */
    fun start(context: Context) {
        val intent = Intent(context, MediaSyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

/** Hizmet içinden Hilt singleton'larına erişim (ViewModel yok). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun cloudPhotoStore(): CloudPhotoStore
    fun smbRepository(): SmbRepository
}

/**
 * SMP'yi arka planda "uyanık" tutan ön plan hizmeti.
 *
 * Ne yapar: 15 dakikada bir SMP kütüphanesini tazeler (CloudPhotoStore,
 * TTL'ye kendisi karar verir) ve SMB oturumunu ısıtır. Böylece telefon
 * cebindeyken bile uygulama açıldığında zaman tüneli bayat değildir ve
 * NTLM el sıkışması beklenmez.
 *
 * Ne YAPMAZ: pil harcayan sürekli tarama, indirme veya yedekleme. Yedekleme
 * zaten WorkManager'a emanet; bu hizmet yalnızca oturumu ve listeyi canlı
 * tutar. 15 dakika dengesi bilinçli: daha sık kontrol pille NAS'a yazık,
 * daha seyrekle "arka plan koruması" diye bir şey kalmaz.
 *
 * Kullanıcı Ayarlar → "Arka plan koruması" anahtarıyla açar/kapatır.
 */
class MediaSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeat: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // dataSync tipi: Android 14+ typed FGS zorunlu kılıyor; bildirim
        // kalıcı ama sessiz (IMPORTANCE_LOW).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
        startHeartbeat()
        // Sistem hizmeti bellek baskısıyla öldürürse yeniden başlatılır.
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeat?.cancel()
        super.onDestroy()
    }

    private fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (isActive) {
                runCatching {
                    val entry = EntryPointAccessors.fromApplication(
                        applicationContext,
                        SyncEntryPoint::class.java
                    )
                    val store = entry.cloudPhotoStore()
                    // SMB oturumu yalnızca SMB birincil kaynakken ısıtılır;
                    // Nextcloud seçiliyken gereksiz NTLM denemesi modemi
                    // ve pili yorar.
                    if (store.activeSource() == MediaSourceKind.SMB) {
                        entry.smbRepository().prewarmSession()
                    }
                    store.refresh()
                }
                delay(HEARTBEAT_MILLIS)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Arka plan koruması",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SMP'nin SMB bağlantısını canlı tutan hizmetin bildirimi"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SMP arka planda hazır")
            .setContentText("SMB bağlantısı canlı; galeri açılışta beklemez")
            .setContentIntent(tap)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "smp_bg_guard"
        private const val NOTIFICATION_ID = 4211
        private const val HEARTBEAT_MILLIS = 15L * 60L * 1000L

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaSyncService::class.java))
        }
    }
}
