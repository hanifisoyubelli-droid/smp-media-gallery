package com.smpmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smpmedia.data.image.MissingPhotoRefresher
import com.smpmedia.data.local.BackupRecordDao
import com.smpmedia.data.model.BackupStatus
import com.smpmedia.data.model.ImageLoadState
import com.smpmedia.data.model.MissingPhotoSource
import com.smpmedia.data.model.PhotoItem
import com.smpmedia.data.model.RemotePhoto
import com.smpmedia.data.prefs.GalleryFilterStore
import com.smpmedia.data.repository.CloudPhotoStore
import com.smpmedia.data.repository.PhotoRepository
import com.smpmedia.data.warm.GridWarmer
import com.smpmedia.util.dayStartMillis
import com.smpmedia.util.formatDayLabel
import com.smpmedia.util.formatMonthLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class PhotoUiItem(
    val photo: PhotoItem,
    val status: BackupStatus
)

/**
 * Birleşik zaman tüneli öğesi (Google Photos davranışı):
 * - [Device]: telefonda duran fotoğraf; yedekleme durum rozetiyle gösterilir.
 * - [Cloud] : yalnızca SMP kullanıcı klasöründe olan (cihazda bulunmayan)
 *   fotoğraf; bulut rozetiyle gösterilir, ağdan küçük resmiyle yüklenir.
 */
sealed interface GalleryUiItem {
    data class Device(val item: PhotoUiItem) : GalleryUiItem
    data class Cloud(val photo: RemotePhoto) : GalleryUiItem
}

/** Yukarıdaki iki türün ortak tarih erişimi (gruplama sırası için). */
val GalleryUiItem.sortMillis: Long
    get() = when (this) {
        is GalleryUiItem.Device -> item.photo.sortDate
        // EXIF çekim zamanı öğrenildiyse GERÇEK tarih kullanılır — NAS'taki
        // bozuk değiştirme tarihleri böylece zamanla düzelir.
        is GalleryUiItem.Cloud -> photo.capturedMillis ?: photo.lastModified
    }

/**
 * İkincil sıralama anahtarı: her yeniden sorgu/taramada AYNI kalan kimlik.
 *
 * Zaman damgaları sık sık eşittir (toplu dışa aktarma ve WhatsApp fotoğrafları
 * aynı saniyeye yazılır). Eşitlikte sırayı sorgu/kitar sırasına bırakırsak
 * her MediaStore yeniden okumasında ve her bulut güncellemesinde aynı gün
 * içindeki yerler değişir — kullanıcı şikâyeti: "aşağı kaydırınca aynı
 * tarihteki fotoğraflar yer değiştiriyor". Bu anahtarla sıra TAM belirlenimci
 * olur ve bir daha asla kımıldamaz.
 */
val GalleryUiItem.stableIdentity: String
    get() = when (this) {
        is GalleryUiItem.Device -> item.photo.uri.toString()
        // Kaynak ÖNEKİ zorunlu: kullanıcı SMP → Nextcloud geçiş yaptığındaki
        // aynı yollu dosyalar SMB kayıtlarıyla çakışmasın (ikisi aynı anda
        // tünelde olmasa da önbellekte/anahtarda karışma riski var).
        is GalleryUiItem.Cloud -> photo.source.keyPrefix + photo.path
    }

data class PhotoGroup(
    val dateMillis: Long,
    val dateLabel: String,
    val monthLabel: String = "",
    val showMonthHeader: Boolean = false,
    val photos: List<GalleryUiItem> = emptyList()
)

data class MainUiState(
    val groups: List<PhotoGroup> = emptyList(),
    val totalPhotos: Int = 0,
    val backedUpCount: Int = 0,
    val permissionGranted: Boolean = false,
    val loading: Boolean = true,
    /** SMP'de bulunup cihazda olmayan (zamantüneliyle birleştirilen) fotoğraf sayısı. */
    val cloudOnlyCount: Int = 0,
    /** SMP tarama hatası; ızgaranın üstünde ince bir şerit olarak gösterilir. */
    val cloudError: String? = null
)

/**
 * "Fotoğraflar" üst çubuğundaki dişli ile açılan klasör seçim satırı.
 * [key] kalıcı anahtardır (`local:<bucketId>` / `smb:<yol>`).
 * [subtitle] SMP satırlarında "alt klasörler dahil" gibi kısa bir açıklamadır.
 */
data class FolderOption(
    val key: String,
    val label: String,
    val count: Int,
    val isCloud: Boolean,
    val visible: Boolean,
    val subtitle: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MainViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    recordDao: BackupRecordDao,
    private val missingPhotoRefresher: MissingPhotoRefresher,
    private val cloudPhotoStore: CloudPhotoStore,
    private val filterStore: GalleryFilterStore,
    private val gridWarmer: GridWarmer
) : ViewModel() {

    init {
        // Izgara ısıtma (kullanıcı isteği): tarama ilk kez dolduğunda, tercih
        // açıksa kaldığı yerden sessizce devam eder. Tarama bitmeden
        // başlatılmaz — ısıtılacak liste yoksa döngü boşuna uyanır.
        viewModelScope.launch {
            cloudPhotoStore.state
                .first { !it.loading && it.photos.isNotEmpty() }
            gridWarmer.startIfAuto()
        }
    }

    /** Izgaradaki her görselin anlık yükleme durumu (anahtar bazlı). */
    val imageStates: StateFlow<Map<String, ImageLoadState>> =
        missingPhotoRefresher.imageStates

    /** Yeniden denenmeyi bekleyen eksik görsel sayısı. */
    val missingCount: StateFlow<Int> = missingPhotoRefresher.missingCount

    /** Arka planda eksik tamamlama sürüyor mu? */
    val missingRetrying: StateFlow<Boolean> = missingPhotoRefresher.retrying

    /** Tüm eksikler bittiğinde tek seferlik olay. */
    val missingCompleted: SharedFlow<Unit> = missingPhotoRefresher.completed

    private val permission = MutableStateFlow(photoRepository.hasImagePermission())

    /** İzin yoksa boş liste; izin verilince MediaStore akışına geçilir. */
    private val photos = permission.flatMapLatest { granted ->
        if (granted) photoRepository.observePhotos() else flowOf(emptyList())
    }

    val uiState: StateFlow<MainUiState> = combine(
        photos,
        recordDao.observeAll(),
        permission,
        cloudPhotoStore.state,
        filterStore.hidden
    ) { photoList, records, granted, cloud, hidden ->
        // Aynı fotoğraf birden fazla hedefe yedeklenmiş olabilir; en güncel kaydı göster.
        val latestStatus = records
            .groupBy { it.localUri }
            .mapValues { (_, list) ->
                list.maxByOrNull { it.updatedAt }?.status ?: BackupStatus.PENDING
            }

        // ── Klasör filtresi ──
        // Kullanıcı "Fotoğraflar" üst çubuğundaki dişliden bazı klasörleri
        // gizleyebilir. Varsayılan: her şey görünür (Google Photos mantığı).
        val visiblePhotos = photoList.filter { photo ->
            GalleryFilterStore.localKey(photo.bucketId) !in hidden
        }

        // ── Bulut tekilleştirmesi ──
        // SMP'deki dosya cihazda aynı ad VE boyutta varsa zaten yerel olarak
        // görünüyor (yeşil rozet onu gösteriyor); ikinci kez listelenmez.
        val localKeys = visiblePhotos
            .mapTo(HashSet(visiblePhotos.size)) { photo ->
                dedupKey(photo.displayName, photo.sizeBytes)
            }
        val cloudOnly = cloud.photos
            // Özyinelemeli filtre: üst klasör kapatıldıysa alt klasörlerdeki
            // fotoğraflar da kapanır (kullanıcı isteği).
            .filterNot { remote ->
                GalleryFilterStore.isCloudPathHidden(remote.parentPath, hidden)
            }
            .filterNot { remote ->
                dedupKey(remote.displayName, remote.sizeBytes) in localKeys
            }

        // Google Photos gibi tek zaman tüneli: cihaz + bulut birlikte, yeni tarih üstte.
        val merged = ArrayList<GalleryUiItem>(visiblePhotos.size + cloudOnly.size)
        visiblePhotos.forEach { photo ->
            merged += GalleryUiItem.Device(
                PhotoUiItem(
                    photo = photo,
                    status = latestStatus[photo.uri.toString()] ?: BackupStatus.PENDING
                )
            )
        }
        cloudOnly.forEach { remote ->
            merged += GalleryUiItem.Cloud(remote)
        }

        val groups = mutableListOf<PhotoGroup>()
        var previousMonth = ""
        merged
            .groupBy { dayStartMillis(it.sortMillis) }
            .toSortedMap(compareByDescending<Long> { it })
            .forEach { (day, dayPhotos) ->
                val month = formatMonthLabel(day)
                groups += PhotoGroup(
                    dateMillis = day,
                    dateLabel = formatDayLabel(day),
                    monthLabel = month,
                    showMonthHeader = month != previousMonth,
                    // Deterministik sıra: önce tarih, eşitlikte kalıcı kimlik
                    // (bkz. stableIdentity) — kaydırma sırasında yer değiştirme
                    // olmaz.
                    photos = dayPhotos.sortedWith(
                        compareByDescending<GalleryUiItem> { it.sortMillis }
                            .thenBy { it.stableIdentity }
                    )
                )
                previousMonth = month
            }

        MainUiState(
            groups = groups,
            totalPhotos = visiblePhotos.size,
            backedUpCount = visiblePhotos.count {
                latestStatus[it.uri.toString()] == BackupStatus.SUCCESS
            },
            permissionGranted = granted,
            loading = false,
            cloudOnlyCount = cloudOnly.size,
            cloudError = cloud.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(permissionGranted = permission.value)
    )

    /**
     * Üst çubuktaki dişlinin listesi: cihaz klasörleri (MediaStore bucket)
     * ve SMP klasörleri (bulut). [FolderOption.visible] = zamantünelinde görünür.
     *
     * SMP tarafı TARAMA KÖKÜNE GÖRE EN ÜST klasörler halinde gruplanır:
     * `Google Fotoğraf/2020 yılına ait fotoğraflar`, `…/2021 …` gibi onlarca
     * alt klasör yerine tek bir "Google Fotoğraf" seçeneği çıkar; işaretleyince
     * altındaki HER klasörün fotoğrafları zamantüneline gelir (kullanıcı isteği).
     */
    val folderOptions: StateFlow<List<FolderOption>> = combine(
        photos,
        cloudPhotoStore.state,
        filterStore.hidden
    ) { photoList, cloud, hidden ->
        val local = photoList
            .groupBy { it.bucketId }
            .map { (bucketId, group) ->
                val key = GalleryFilterStore.localKey(bucketId)
                FolderOption(
                    key = key,
                    label = group.first().bucketName.ifBlank { "Diğer" },
                    count = group.size,
                    isCloud = false,
                    visible = key !in hidden
                )
            }

        // Taramanın yapıldığı kullanıcı klasörü (ör. usb1_1/Ayse).
        val root = cloud.scannedFolder.trim('/')
        val remote = cloud.photos
            .groupBy { photo -> GalleryFilterStore.topLevelFolder(photo.parentPath, root) }
            .map { (folder, group) ->
                val key = GalleryFilterStore.cloudKey(folder)
                val name = folder.substringAfterLast('/').ifBlank { "SMP" }
                // Alt klasörü olan bir üst klasör mü? Kullanıcıya bunun
                // ağaç olduğunu söyle; tek tek alt klasör aramasın.
                val hasChildren = group.any {
                    it.parentPath.trim('/') != folder.trim('/')
                }
                FolderOption(
                    key = key,
                    label = name,
                    count = group.size,
                    isCloud = true,
                    visible = !GalleryFilterStore.isCloudPathHidden(folder, hidden),
                    subtitle = if (hasChildren) "Alt klasörler dahil" else null
                )
            }
        // SMP klasörleri ÜSTTE: uzun cihaz listesinde ekranın altına kaçıp
        // "listede hiç yok" izlenimi vermesin (kullanıcı şikâyeti).
        remote.sortedByDescending { it.count } + local.sortedByDescending { it.count }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    fun toggleFolder(key: String) = filterStore.toggle(key)

    fun showAllFolders() = filterStore.showAll()

    fun refreshPermission() {
        permission.value = photoRepository.hasImagePermission()
    }

    /** SMP kullanıcı klasörünü (yeniden) tarar; sonuçlar zamantüneliyle birleşir. */
    fun refreshCloud(force: Boolean = false) = cloudPhotoStore.refresh(force)

    /** EXIF tabanlı tarih onarımının ilerlemesi (null = çalışma yok). */
    val cloudDateRepair: StateFlow<CloudPhotoStore.RepairProgress?> = cloudPhotoStore.repair

    /** Adında tarih olmayan SMP fotoğraflarını EXIF'ten onarır (dişlideki buton). */
    fun repairCloudDates() = cloudPhotoStore.startDateRepair()

    /** Görüntüleyiciden sildirme: dosyayı sunucuda siler. */
    fun deleteCloudPhoto(path: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch { onResult(cloudPhotoStore.deletePhoto(path)) }
    }

    /** Manuel "Eksikleri Yenile" butonu. */
    fun retryMissingPhotos() {
        viewModelScope.launch { missingPhotoRefresher.retryMissing() }
    }

    /** Uygulama öne geldiğinde otomatik tamamlama. */
    fun retryMissingOnResume() {
        viewModelScope.launch { missingPhotoRefresher.retryMissing() }
    }

    /** GridImage yükleme durumunu üst katmana taşır. */
    fun reportImageState(
        cacheKey: String,
        state: ImageLoadState,
        source: MissingPhotoSource,
        model: String,
        lastModified: Long = 0L,
        error: String? = null
    ) {
        when (state) {
            ImageLoadState.PENDING -> missingPhotoRefresher.reportLoading(cacheKey)
            ImageLoadState.LOADED -> missingPhotoRefresher.reportLoaded(cacheKey)
            ImageLoadState.FAILED -> missingPhotoRefresher.reportFailed(
                cacheKey = cacheKey,
                source = source,
                model = model,
                lastModified = lastModified,
                error = error
            )
        }
    }

    companion object {
        /** Dosya adı + boyut: "bu fotoğraf cihazda zaten var mı?" anahtarı. */
        private fun dedupKey(name: String, sizeBytes: Long): String =
            name.lowercase(Locale.ROOT) + '|' + sizeBytes
    }
}
