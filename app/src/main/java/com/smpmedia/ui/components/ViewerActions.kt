package com.smpmedia.ui.components

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.smpmedia.data.image.MediaCache
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.SmbImage
import com.smpmedia.data.repository.CloudPhotoStore
import com.smpmedia.data.repository.NextcloudRepository
import com.smpmedia.data.repository.SmbRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Compose tarafında (VM'siz ekran) Hilt singleton'larına erişim noktası. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ViewerEntryPoint {
    fun smbRepository(): SmbRepository
    fun smbSettingsStore(): com.smpmedia.data.prefs.SmbSettingsStore
    fun nextcloudRepository(): NextcloudRepository
    fun cloudPhotoStore(): CloudPhotoStore
}

private fun smbRepositoryOf(context: Context): SmbRepository? = runCatching {
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ViewerEntryPoint::class.java
    ).smbRepository()
}.getOrNull()

private fun nextcloudRepositoryOf(context: Context): NextcloudRepository? = runCatching {
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ViewerEntryPoint::class.java
    ).nextcloudRepository()
}.getOrNull()

/** Zamantüneli hangi kaynaktan besleniyor? "Ekle" hedefleri buna göre seçilir. */
private fun cloudSourceOf(context: Context): MediaSourceKind = runCatching {
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ViewerEntryPoint::class.java
    ).cloudPhotoStore().state.value.source
}.getOrDefault(MediaSourceKind.SMB)

/** Fotoğraf detay panelinde gösterilen bilgi demeti. */
data class PhotoDetails(
    val dateMillis: Long,
    val sizeBytes: Long,
    val camera: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val address: String? = null
)

/**
 * Görüntüleyici eylemleri: Paylaş, Düzenle, Detay (EXIF + konum), Harita.
 *
 * Yerel (content://) görseller doğrudan paylaşılır. SMB görseller önce
 * kalıcı media_cache/shared altına kopyalanır ve FileProvider ile yetkilendirilir —
 * sistem uygulamaları smb:// URI'sini açamaz.
 */
object ViewerActions {

    /** Fotoğrafın MIME türü; SMB'de uzantıdan, yerelde ContentResolver'dan. */
    fun mimeTypeOf(context: Context, photo: ViewerPhoto): String {
        photo.mimeType?.let { return it }
        return when (photo.model) {
            is SmbImage -> mimeFromName(photo.title)
            is Uri -> context.contentResolver.getType(photo.model as Uri)
                ?: mimeFromName(photo.title)
            else -> "image/*"
        }
    }

    private fun mimeFromName(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', ""))
            ?: "image/jpeg"

    /**
     * Paylaş/Düzenle için açılabilir URI üretir. Yerel: kendi URI'si.
     * SMB: kalıcı `media_cache/shared` altındaki kopya FileProvider ile
     * yetkilendirilir — sistem uygulamaları smb:// URI'sini açamaz.
     *
     * Kopya yönetimi bilinçli olarak [localFileFor]'a devredildi:
     * - Dosya zaten önbeklekteyse (izlenmiş video/görüntü) AĞA HİÇ çıkılmaz;
     *   paylaşım anında gelir — kullanıcı "bekletiyor" şikâyetinin kökü buydu.
     * - Kopya `.part` üzerinden atomik taşınır; paylaş butonuna çift basma
     *   veya oynatma sırasındaki okuma asla yarım dosya görmez.
     */
    suspend fun prepareShareUri(
        context: Context,
        photo: ViewerPhoto,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Uri? = when (val model = photo.model) {
        is Uri -> model
        is SmbImage -> withContext(Dispatchers.IO) {
            val file = localFileFor(context, model, onProgress) ?: return@withContext null
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }.onFailure {
                Log.w("SmpMedia", "FileProvider URI üretilemedi: ${photo.title}", it)
            }.getOrNull()
        }
        else -> null
    }

    /**
     * Uygulama içi video oynatma için yerel dosya.
     * SMP dosyası kalıcı `media_cache/shared` altına bir kez kopyalanır; sonraki
     * oynatmalarda dosya zaten oradadır (ağa çıkılmaz).
     *
     * [onProgress] verilirse (yazılan, toplam) bayt çiftiyle ilerleme
     * bildirilir — büyük videolarda "İndiriliyor %NN" göstermek için.
     * Kopya SIRALI bloklarla okunur: iki blok arasında iptal güvenlidir
     * (SMB üzerinde bekleyen istek yokken kapanır; taşıma ölmez).
     */
    suspend fun localFileFor(
        context: Context,
        image: SmbImage,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val nc = image.source == MediaSourceKind.NEXTCLOUD
        val repo = if (nc) nextcloudRepositoryOf(context) else smbRepositoryOf(context)
        if (repo == null) return@withContext null
        runCatching {
            val safeName = image.path.substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = MediaCache.shared(context)
            val dest = File(dir, safeName)
            // AD KURALI KAYNAKTAN BAĞIMSIZ: arşiv aynı olduğu için SMB'den
            // inmiş kopya Nextcloud isteğini de karşılar (ve tersi) — ikinci
            // indirime asla izin verilmez.
            if (dest.exists() && dest.length() > 0L) return@runCatching dest
            val tmp = File(dir, "$safeName.part")
            var total = 0L
            val source: InputStream = if (nc) {
                val nrepo = repo as NextcloudRepository
                total = nrepo.fileSize(image.path)
                nrepo.openStream(image.path)
            } else {
                val remote = (repo as SmbRepository).openSharedSession().file(image.path)
                total = runCatching { remote.length() }.getOrDefault(0L)
                remote.inputStream
            }
            source.use { input ->
                tmp.outputStream().use { output ->
                    val chunk = ByteArray(256 * 1024)
                    var written = 0L
                    while (true) {
                        // Bloklar arası iptal kontrolü (sıralı okuma — taşıma
                        // güvenli; kullanıcı çıkarsa kopya burada durur).
                        kotlinx.coroutines.yield()
                        val n = input.read(chunk)
                        if (n < 0) break
                        output.write(chunk, 0, n)
                        written += n
                        if (onProgress != null && total > 0) onProgress(written, total)
                    }
                }
            }
            runCatching { dest.delete() }
            if (!tmp.renameTo(dest)) return@runCatching null
            // Kalıcı depo kendiliğinden dolmaz; en eski kopyaları biz silmezsek
            // bir gün telefonun tamamı video kopyası olur.
            MediaCache.trimOldest(dir, MediaCache.SHARED_MAX_BYTES, keep = dest)
            dest
        }.onFailure {
            Log.w("SmpMedia", "Video önbelleğe alınamadı: ${image.path}", it)
        }.getOrNull()
    }

    /**
     * "Ekle" hedeflerinin kökü: hangi kaynak birincilse onun kullanıcı
     * klasörü. Nextcloud'da bu, taramanın yapıldığı kök klasördür
     * (CloudPhotoStore'dan okunur — ayar iki yerden okunup şaşmasın).
     */
    fun userFolder(context: Context): String = when (cloudSourceOf(context)) {
        MediaSourceKind.NEXTCLOUD -> runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ViewerEntryPoint::class.java
            ).cloudPhotoStore().state.value.scannedFolder
        }.getOrDefault("")

        MediaSourceKind.SMB -> runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                ViewerEntryPoint::class.java
            ).smbSettingsStore().current().userFolder
        }.getOrDefault("")
    }

    /** Kullanıcı klasörü altındaki alt klasörler ("Ekle" hedefleri). */
    suspend fun listSubfolders(context: Context): List<String> = withContext(Dispatchers.IO) {
        val root = userFolder(context)
        if (root.isBlank()) return@withContext emptyList()
        when (cloudSourceOf(context)) {
            MediaSourceKind.NEXTCLOUD -> {
                val repo = nextcloudRepositoryOf(context) ?: return@withContext emptyList()
                runCatching { repo.listDirectory(root) }
                    .getOrDefault(emptyList())
                    .filter { it.isDirectory }
                    .map { it.path }
            }

            MediaSourceKind.SMB -> {
                val repo = smbRepositoryOf(context) ?: return@withContext emptyList()
                repo.listDirectory(root).getOrNull()
                    ?.filter { it.isDirectory }
                    ?.map { it.path }
                    ?: emptyList()
            }
        }
    }

    /**
     * Fotoğrafı buluttaki bir klasöre kopyalar ("Ekle" eylemi).
     *  - Yerel dosya + SMB hedefi: doğrudan yüklenir.
     *  - Yerel dosya + Nextcloud hedefi: baytlar PUT ile gönderilir
     *    ("Ekle" fotoğraflar içindir; dev videolar tasarım dışıdır).
     *  - SMB dosyası: akış kopyası yapılır.
     *  - Nextcloud dosyası: SUNUCU İÇİ COPY — dosya telefona inmez, birkaç
     *    milisaniyede tamamlanır.
     */
    suspend fun copyToFolder(
        context: Context,
        photo: ViewerPhoto,
        targetFolder: String
    ): Boolean = withContext(Dispatchers.IO) {
        val folder = targetFolder.trim().trim('/')
        if (folder.isEmpty()) return@withContext false
        val target = "$folder/${photo.title}"
        runCatching {
            when (val model = photo.model) {
                is Uri -> {
                    if (cloudSourceOf(context) == MediaSourceKind.NEXTCLOUD) {
                        val repo = nextcloudRepositoryOf(context) ?: return@withContext false
                        val bytes = context.contentResolver.openInputStream(model)?.use { input ->
                            ByteArrayOutputStream().also { out ->
                                input.copyTo(out, 256 * 1024)
                            }.toByteArray()
                        } ?: return@withContext false
                        repo.ensureFolders(folder) && repo.upload(target, bytes)
                    } else {
                        val repo = smbRepositoryOf(context) ?: return@withContext false
                        repo.upload(
                            session = repo.openSharedSession(),
                            contentResolver = context.contentResolver,
                            localUri = model,
                            remotePath = target,
                            lastModified = System.currentTimeMillis(),
                            totalBytes = photo.sizeBytes
                        )
                    }
                }

                is SmbImage -> {
                    if (model.source == MediaSourceKind.NEXTCLOUD) {
                        val repo = nextcloudRepositoryOf(context) ?: return@withContext false
                        repo.ensureFolders(folder) && repo.copyFile(model.path, target)
                    } else {
                        val repo = smbRepositoryOf(context) ?: return@withContext false
                        val session = repo.openSharedSession()
                        repo.ensureDirectory(session, folder)
                        session.file(model.path).inputStream.use { input ->
                            jcifs.smb.SmbFileOutputStream(session.file(target)).use { output ->
                                input.copyTo(output, 256 * 1024)
                            }
                        }
                    }
                }

                else -> return@withContext false
            }
            true
        }.onFailure {
            Log.w("SmpMedia", "Klasöre kopyalama başarısız: $target", it)
        }.getOrDefault(false)
    }

    fun share(context: Context, uri: Uri, mime: String) {        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, "Fotoğrafı paylaş"))
        }.onFailure {
            Log.w("SmpMedia", "Paylaşım başlatılamadı", it)
        }
    }

    /**
     * Sistem/üçüncü parti düzenleyiciyi açar (ACTION_EDIT).
     *
     * Sağlamlık (kullanıcı isteği: "Düzenle kullanılabilir olsun"): EDIT için
     * kayıtlı uygulama yoksa buton ölü kalmasın — medya sistem galerisine
     * (ACTION_VIEW) önerilir; Google Photos gibi galerilerin kendi düzenleme
     * simgesi vardır. Tek editör bulunursa izin açıkça verilir: chooser
     * olmadan başlatılan intent'te URI yetkisi kendiliğinden yayılmaz.
     */
    fun edit(context: Context, uri: Uri, mime: String): Boolean {
        val editIntent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val editors = context.packageManager.queryIntentActivities(
            editIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )
        if (editors.isNotEmpty()) {
            val target = if (editors.size == 1) {
                context.grantUriPermission(
                    editors.first().activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                editIntent
            } else {
                Intent.createChooser(editIntent, "Düzenle")
            }
            return runCatching { context.startActivity(target) }
                .onFailure { Log.w("SmpMedia", "Düzenleyici açılamadı", it) }
                .isSuccess
        }
        // Editör yok: en azından galeride açılsın (oradaki kalem simgesi
        // düzenlemeyi sağlar). Amaç, butonun hiçbir şey yapmadan kaybolması
        // değil, kullanıcıyı düzenlenebilir bir yere ulaştırmaktır.
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(viewIntent, "Şu uygulama ile aç"))
        }
            .onFailure { Log.w("SmpMedia", "Açılacak uygulama bulunamadı", it) }
            .isSuccess
    }

    /** Videoyu sistem oynatıcısında açar. */
    fun playVideo(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w("SmpMedia", "Oynatıcı bulunamadı", it) }
            .isSuccess
    }

    /**
     * EXIF + konum bilgisini arka planda toplar. Yerel: fd üzerinden
     * (dosyanın tamamı okunmaz). SMB: ilk bloklar yeter — EXIF JPEG
     * başındadır; stream ihtiyaç kadarı okunur.
     */
    suspend fun loadDetails(context: Context, photo: ViewerPhoto): PhotoDetails? {
        var exif: ExifInterface? = null
        try {
            exif = when (val model = photo.model) {
                is Uri -> context.contentResolver.openFileDescriptor(model, "r")?.use { fd ->
                    runCatching { ExifInterface(fd.fileDescriptor) }.getOrNull()
                }
                is SmbImage -> withContext(Dispatchers.IO) {
                    if (model.source == MediaSourceKind.NEXTCLOUD) {
                        val nrepo = nextcloudRepositoryOf(context) ?: return@withContext null
                        runCatching {
                            // EXIF başlıkları dosyanın başındadır; HTTP Range
                            // sayesinde ilk 256 KB iner — tüm dosya değil.
                            val head = nrepo.readRange(model.path, 0L, 256L * 1024L - 1L)
                            ExifInterface(java.io.ByteArrayInputStream(head))
                        }.getOrNull()
                    } else {
                        val repo = smbRepositoryOf(context) ?: return@withContext null
                        runCatching {
                            repo.openSharedSession().file(model.path).inputStream.use { stream ->
                                // SMB akışı yavaş olmasın: EXIF başlıklar ~64 KB'tır.
                                ExifInterface(stream)
                            }
                        }.getOrNull()
                    }
                }
                else -> null
            }
        } catch (t: Throwable) {
            Log.d("SmpMedia", "EXIF okunamadı: ${photo.title}: ${t.message}")
        }

        val make = exif?.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model2 = exif?.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = listOf(make, model2)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { null }

        val latLon = FloatArray(2)
        var lat: Double? = null
        var lon: Double? = null
        if (exif != null && exif.getLatLong(latLon)) {
            lat = latLon[0].toDouble()
            lon = latLon[1].toDouble()
        }

        var address: String? = null
        if (lat != null && lon != null) {
            address = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale("tr"))
                        .getFromLocation(lat, lon, 1)
                        ?.firstOrNull()
                        ?.let { loc ->
                            listOfNotNull(loc.featureName, loc.adminArea, loc.subLocality)
                                .distinct()
                                .joinToString(", ")
                                .ifBlank { null }
                        }
                }.getOrNull()
            }
        }

        return PhotoDetails(
            dateMillis = photo.dateMillis,
            sizeBytes = photo.sizeBytes,
            camera = camera,
            lat = lat,
            lon = lon,
            address = address
        )
    }

    /** Koordinatı Google Haritalar'da açar (API anahtarı gerektirmez). */
    fun openInMaps(context: Context, lat: Double, lon: Double, label: String?) {
        val uri = if (label != null) {
            Uri.parse("geo:0,0?q=${Uri.encode("$lat,$lon ($label)")}")
        } else {
            Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure {
            Log.w("SmpMedia", "Harita açılamadı", it)
        }
    }

    /** MediaStore'a "bu dosyayı silmek istiyorum" IntentSender'ı üretir. */
    fun createLocalDeleteRequest(context: Context, uri: Uri): android.content.IntentSender? =
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            } else {
                null
            }
        }.getOrNull()
}
