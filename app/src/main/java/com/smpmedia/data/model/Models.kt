package com.smpmedia.data.model

import android.net.Uri
import java.util.Locale

/**
 * MediaStore'dan okunan tek bir fotoğraf.
 * Tarih alanları her zaman milisaniye cinsindendir.
 */
data class PhotoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateTaken: Long,
    val dateModified: Long,
    val sizeBytes: Long,
    val bucketId: String,
    val bucketName: String,
    val mimeType: String,
    val relativePath: String,
    /** Video süreleri için milisaniye; fotoğraflarda 0. Rozette "1:23" gösterilir. */
    val durationMs: Long = 0L
) {
    /** Sıralama/gruplama: EXIF tarihi yoksa son değiştirilme tarihine düşer. */
    val sortDate: Long
        get() = if (dateTaken > 0L) dateTaken else dateModified
}

/** Telefonda bulunan bir klasör (MediaStore bucket karşılığı). */
data class LocalFolder(
    val bucketId: String,
    val name: String,
    val photoCount: Int,
    val coverUri: Uri?
)

/** SMB üzerindeki bir dosya/klasör girdisi. */
data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long
)

/**
 * Coil'in UZAK kaynaktan (SMB veya Nextcloud) görsel okuması için model.
 * [lastModified] önbellek anahtarına dahil edilir; dosya değişince görsel yenilenir.
 *
 * [source] hangi sunucudan okunacağını belirler. Model adının `Smb` ile
 * başlaması tarihseldir: tüm arayüz (görüntüleyici, düzenleyici, paylaşım,
 * canlı fotoğraf) bu tek modeli taşır; kaynak alanı fetcher içinde dallanır.
 * İki ayrı model çıkarmak her UI dosyasına if/else yayardı.
 */
data class SmbImage(
    val path: String,
    val lastModified: Long,
    /** SMP zaman tünelindeki dosya bir video mu? Fetcher buna göre kare çıkarır. */
    val isVideo: Boolean = false,
    /** Dosyanın okunacağı sunucu (SMB paylaşımı veya Nextcloud). */
    val source: MediaSourceKind = MediaSourceKind.SMB,
    /** Nextcloud dosya kimliği (oc:fileid); SMB tarafında 0. */
    val fileId: Long = 0L
)

/** SMP (SMB) alanındaki bir fotoğraf. */
data class RemotePhoto(
    val path: String,
    val displayName: String,
    val parentPath: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /**
     * Fotoğraf yüklendiğinde EXIF'ten (DateTimeOriginal) öğrenilen GERÇEK
     * çekim zamanı. Kullanıcının NAS'ında toplu bir işlem binlerce dosyanın
     * değiştirme tarihini aynı güne bozdu; çekim zamanı EXIF'ten gelir,
     * yoksa [lastModified] kullanılır.
     */
    val capturedMillis: Long? = null,
    /** Zaman tünelinde video hücresi mi (oynat rozeti + InlineVideoPlayer). */
    val isVideo: Boolean = false,
    /**
     * Google Photos "Canlı Fotoğraf" çifti: bu JPEG ile AYNI ADTA bir .mp4
     * varsa basılı tutulduğunda sessiz döngülü önizleme O videoyla oynatılır
     * (Google Photos davranışı).
     */
    val liveVideoPath: String? = null,
    /** Dosyanın geldiği sunucu; küçük resim/silme/tarih işlemleri buna göre yürür. */
    val source: MediaSourceKind = MediaSourceKind.SMB,
    /** Nextcloud dosya kimliği (oc:fileid); SMB tarafında 0. */
    val fileId: Long = 0L
) {
    /** Izgarada video rozeti çizilmeli mi (video ya da canlı fotoğraf çifti). */
    val showsMotionBadge: Boolean
        get() = isVideo || liveVideoPath != null
}

/** SMB bağlantı ayarları. Şifre EncryptedSharedPreferences içinde tutulur. */
data class SmbSettings(
    val host: String = "",
    val share: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val port: Int = 445,
    val autoBackup: Boolean = true,
    val intervalHours: Int = 6,
    /**
     * Bu telefonun ait olduğu kullanıcı klasörü (paylaşıma göreli,
     * ör. "usb1_1/Ayse"). Fotoğraf zaman tüneli ve yedekleme hedefleri bu
     * klasöre bağlanır; ayarlardan farklı bir klasör seçilebilir.
     */
    val userFolder: String = DEFAULT_USER_FOLDER
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && share.isNotBlank()

    companion object {
        /** Boş: kullanıcı ayarlardan kendi klasörünü seçer. */
        const val DEFAULT_USER_FOLDER = ""
    }
}

/**
 * Nextcloud bağlantı ayarları.
 *
 * SMP (modem USB paylaşımı) yerine Nextcloud'un tercih edilme nedeni hızdır:
 * aynı arşiv HTTP üzerinden ~9 MB/s akarken, modemin Samba + USB 2.0 yığını
 * tek akışta ~3 MB/s veriyordu. Üstüne bir de HTTP `Range` istekleri sayesinde
 * küçük resim için dosyanın tamamı yerine ilk bloğu okunabiliyor.
 *
 * [appPassword] HESAP ŞİFRESİ OLMAMALIDIR. Nextcloud'da
 * Ayarlar → Güvenlik → "Uygulama şifresi oluştur" ile üretilen, tek yetkisi
 * WebDAV olan ve tek tuşla iptal edilebilen anahtardır.
 */
data class NextcloudSettings(
    /** Kapalıyken uygulama SMP kaynağını kullanmaya devam eder. */
    val enabled: Boolean = false,
    /** Ör. "https://nextcloud.example.com" — sondaki eğik çizgi önemsizdir. */
    val baseUrl: String = DEFAULT_BASE_URL,
    val username: String = "",
    val appPassword: String = "",
    /**
     * Zaman tünelinin taranacağı kök (hesap köküne göreli, ör. "Photos").
     * Boş bırakılırsa hesabın TÜM klasörleri taranır.
     */
    val rootFolder: String = "",
    /**
     * Self-hosted kurulumların çoğu kendi kendine imzalı sertifika kullanır
     * (ör. Nextcloud'u LAN'da çalıştıranlar). Varsayılan KAPALI: açılmadıkça
     * yalnızca sistem/kurumsal köklere güvenilir ve sertifika hatası açıkça
     * bildirilir.
     *
     * Açıldığında yalnızca KENDİ KENDİNE İMZALI zincirler kabul edilir; geçerli
     * bir CA'ya dayanan ama süresi dolmuş/yanlış adlı sertifikalar yine REDDEDİLİR.
     * Ana makine adı doğrulaması her durumda açık kalır.
     */
    val allowSelfSigned: Boolean = false,
    /**
     * Zaman tünelinde Nextcloud mu SMP mi görüneceği.
     * İçerikler aynı olduğu için ikisi aynı anda DEĞİL, biri seçilir;
     * aksi hâlde aynı arşiv tünelde iki kez listelenirdi.
     */
    val primarySource: MediaSourceKind = MediaSourceKind.SMB
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank()

    /** Nextcloud kökeni (şema + ana makine + port), sondaki '/' olmadan. */
    val origin: String
        get() = baseUrl.trim().trimEnd('/')

    /** WebDAV taban yolu; PROPFIND ve GET isteklerinin başlangıcı. */
    val davBase: String
        get() = "$origin/remote.php/dav/files/${username.trim().trim('/')}"

    /**
     * Sunucunun HTTP mi HTTPS mi olduğunu döndürür. Şifreli bağlantıda
     * temel kimlik doğrulaması (Basic Auth) güvenli sayılır; açık HTTP'de
     * şifre yalnızca yerel ağ içinde düz metin gider.
     */
    val isSecure: Boolean
        get() = origin.lowercase(Locale.ROOT).startsWith("https://")

    companion object {
        /**
         * İlk kurulumda adres BoŞTUR: uygulamanın çalışabilmesi için kullanıcının
         * kendi sunucusunu yazması gerekir (Ayarlar → Nextcloud).
         *
         * Şemasız yazılırsa ("192.168.1.20:8080") http:// eklenir; ancak HTTPS
         * önerilir — kimlik bilgisi şifreli gitmiş olur.
         */
        const val DEFAULT_BASE_URL = ""
    }
}

/** Zaman tünelindeki uzaktan medya hangi sunucudan geliyor? */
enum class MediaSourceKind {
    /** Modemin USB paylaşımı (SMB1/2, jcifs-ng). */
    SMB,

    /** Nextcloud WebDAV. */
    NEXTCLOUD;

    /**
     * [com.smpmedia.ui.viewmodel.stableIdentity] içinde kullanılan kısa önek.
     * İki kaynağın aynı göreli yolu çakışsa bile zamantünelinde ayrı kimlik
     * taşıması için gereklidir.
     */
    val keyPrefix: String
        get() = when (this) {
            SMB -> "smb:"
            NEXTCLOUD -> "nc:"
        }
}

/**
 * Uzaktaki bir dosya/klasör girdisinin KAYNAKTAN BAĞIMSIZ hali.
 *
 * SMB tarafı kendi [SmbEntry]'sini, Nextcloud kendi çıktısını üretir; ikisi de
 * buraya indirilir. Böylece [com.smpmedia.data.repository.CloudPhotoStore]
 * tarama sonucunu tek biçimde işler, kaynak if/else'leri listeye sıçramaz.
 */
data class RemoteEntry(
    val name: String,
    /** Kaynak köküne göreli yol, başında '/' yok. Ör. "Fotos/2019/IMG_1.JPG" */
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    /**
     * Nextcloud dosya kimliği (oc:fileid). Sunucu tarafı önizleme API'si dosya
     * ADINI değil bu kimliği ister; SMB tarafında 0'dır.
     */
    val fileId: Long = 0L,
    /** Değişim tespiti için sunucu etiketi; küçük resim önbellek anahtarına girer. */
    val etag: String = ""
)

/**
 * [RemotePhoto]'yu Coil modeline çevirir. Izgara, görüntüleyici ve eksik
 * kuyruğu AYNI dönüşümü [SmbImage] üzerinden yaptığı için iki kaynak arasındaki
 * fark (path + source + fileId) yalnızca burada, tek yerde taşınır.
 */
fun RemotePhoto.toCoilModel(isVideo: Boolean = this.isVideo): SmbImage =
    SmbImage(
        path = path,
        lastModified = lastModified,
        isVideo = isVideo,
        source = source,
        fileId = fileId
    )

/** GridImage'ın Coil'den aldığı yükleme durumu. */
enum class ImageLoadState {
    PENDING,
    LOADED,
    FAILED
}

/** Eksik görsel kuyruğundaki kaynağın türü. */
enum class MissingPhotoSource {
    LOCAL,
    SMB,

    /** Nextcloud WebDAV; yedekleme kaydı SMB kaydıyla karışmasın diye ayrıdır. */
    NEXTCLOUD
}

/** Tek bir fotoğrafın yedekleme durumu. */
enum class BackupStatus {
    PENDING,
    UPLOADING,
    SUCCESS,
    FAILED
}

/** Yedekleme sırasında ekrana yansıtılan ilerleme bilgisi. */
data class BackupProgress(
    val folderName: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val currentFile: String = ""
) {
    val percent: Int
        get() = if (total <= 0) 0 else ((done.toFloat() / total) * 100f).toInt().coerceIn(0, 100)
}

/** Bir yedekleme turunun özeti. */
data class BackupSummary(
    val uploaded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val message: String = "",
    val alreadyRunning: Boolean = false
)
