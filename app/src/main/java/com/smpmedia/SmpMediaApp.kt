package com.smpmedia

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.smpmedia.data.image.MediaCache
import com.smpmedia.data.repository.SmbRepository
import com.smpmedia.data.sync.BgGuard
import com.smpmedia.work.BackupScheduler
import com.smpmedia.work.MissingPhotoScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hilt kökü, WorkManager ve Coil yapılandırması.
 * Varsayılan WorkManager başlatıcısı manifestte kaldırıldığı için
 * WorkerFactory'yi burada sağlıyoruz.
 *
 * Coil'in bellek (maxMemory/4) ve disk (30 GB) önbelleği
 * [com.smpmedia.di.ImageLoaderModule] içinde yapılandırılır; burada sadece
 * Hilt tarafından üretilen tek örneği Coil'e bildiriyoruz.
 */
@HiltAndroidApp
class SmpMediaApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var backupScheduler: BackupScheduler

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var missingPhotoScheduler: MissingPhotoScheduler

    @Inject
    lateinit var smbRepository: SmbRepository

    /** Uygulama ömrü boyunca yaşayan arka plan işleri (SMB ısıtma vb.). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Kullanıcının otomatik yedekleme tercihini WorkManager'a yansıt.
        backupScheduler.syncSchedule()
        // Uygulama kapalıyken eksik küçük resimleri tamamlayan periyodik iş.
        missingPhotoScheduler.schedule()
        // SMB oturumunu (NTLM el sıkışması ~0.5-1 sn) kullanıcı SMP sekmesine
        // geçmeden ARKA PLANDA ısıt: ilk küçük resim bağlantı beklemaz.
        // Ayarlar tanımlı değilse prewarmSession sessizce çıkar.
        appScope.launch { smbRepository.prewarmSession() }
        // Eski sürüm önbelleğini (cacheDir) bir KEZ kalıcı depoya taşı.
        // cacheDir'i sistem depolama daralınca sessizce boşalttığı için
        // ısıtılmış küçük resimler bir gecede yok olabiliyordu; kalıcı
        // depoda önbellek uygulama silinene kadar kalır. Sadece ilk
        // açılışta iş yapar, arka planda koşar, ağa çıkmaz.
        appScope.launch { MediaCache.migrateLegacy(this@SmpMediaApp) }
        // Çökme/güncelleme yüzünden yarıda kalan küçük resim indirmelerinin
        // geçici dosyalarını temizle (yer + karışıklık olmasın).
        appScope.launch { MediaCache.purgeThumbTemp(this@SmpMediaApp) }
        // "Arka plan koruması" açıksa işlem yeniden doğduğunda hizmeti de
        // devam ettir; kullanıcı anahtarı bir kez açar, sonra unutmaz.
        if (BgGuard.isEnabled(this)) runCatching { BgGuard.start(this) }
    }

    /** Coil, Hilt modülünde yapılandırılmış önbellekli ImageLoader'ı kullanır. */
    override fun newImageLoader(): ImageLoader = imageLoader
}
