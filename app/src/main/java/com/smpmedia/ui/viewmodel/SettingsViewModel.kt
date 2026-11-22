package com.smpmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.model.MediaSourceKind
import com.smpmedia.data.model.NextcloudSettings
import com.smpmedia.data.model.SmbEntry
import com.smpmedia.data.model.SmbSettings
import com.smpmedia.data.prefs.NextcloudSettingsStore
import com.smpmedia.data.prefs.SmbSettingsStore
import com.smpmedia.data.repository.CloudPhotoStore
import com.smpmedia.data.repository.NextcloudRepository
import com.smpmedia.data.repository.SmbRepository
import com.smpmedia.data.warm.GridWarmer
import com.smpmedia.work.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val host: String = "",
    val share: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val autoBackup: Boolean = true,
    val intervalHours: Int = 6,
    /** Bu telefonun kullanıcı klasörü (paylaşıma göreli, ör. "usb1_1/Ayse"). */
    val userFolder: String = SmbSettings.DEFAULT_USER_FOLDER,
    val testing: Boolean = false,
    val message: String? = null
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && share.isNotBlank()
}

/** Ayarlardaki SMB klasör gezgini (kullanıcı klasörü seçimi). */
data class FolderBrowserState(
    val visible: Boolean = false,
    val path: String = "",
    val folders: List<SmbEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

/**
 * Ayarlardaki Nextcloud kartının durumu. SMB formundan AYRI tutuldu:
 * SMB alanları [SettingsUiState] içindeki onlarca yere dokunmadan iki
 * sunucu yan yana yönetilebilsin.
 */
data class NextcloudUiState(
    val enabled: Boolean = false,
    val baseUrl: String = NextcloudSettings.DEFAULT_BASE_URL,
    val username: String = "",
    val appPassword: String = "",
    val rootFolder: String = "",
    /** Kendi imzaladığı sertifikaya (kendinden imzalı veya özel CA) güvenilsin mi. */
    val allowSelfSigned: Boolean = false,
    val primarySource: MediaSourceKind = MediaSourceKind.SMB,
    val testing: Boolean = false,
    val message: String? = null
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SmbSettingsStore,
    private val smbRepository: SmbRepository,
    private val backupScheduler: BackupScheduler,
    private val mappingDao: FolderMappingDao,
    private val cloudPhotoStore: CloudPhotoStore,
    private val nextcloudSettingsStore: NextcloudSettingsStore,
    private val nextcloudRepository: NextcloudRepository,
    private val gridWarmer: GridWarmer
) : ViewModel() {

    private val _uiState = MutableStateFlow(settingsStore.current().toUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _browser = MutableStateFlow(FolderBrowserState())
    val browser: StateFlow<FolderBrowserState> = _browser.asStateFlow()

    fun updateHost(value: String) = _uiState.update { it.copy(host = value) }

    fun updateShare(value: String) = _uiState.update { it.copy(share = value) }

    fun updateDomain(value: String) = _uiState.update { it.copy(domain = value) }

    fun updateUsername(value: String) = _uiState.update { it.copy(username = value) }

    fun updatePassword(value: String) = _uiState.update { it.copy(password = value) }

    fun updateAutoBackup(enabled: Boolean) = _uiState.update { it.copy(autoBackup = enabled) }

    fun updateInterval(hours: Int) = _uiState.update { it.copy(intervalHours = hours) }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    fun save() {
        val state = _uiState.value
        if (!state.isConfigured) {
            _uiState.update {
                it.copy(message = "Sunucu IP adresi ve paylaşım adı zorunludur.")
            }
            return
        }
        val oldSettings = settingsStore.current()
        val newSettings = state.toSettings()
        settingsStore.save(newSettings)
        backupScheduler.syncSchedule()

        // Kullanıcı klasörü değiştiyse yedekleme hedefleri de taşınır:
        // "usb1_1/Ayse/WhatsApp" → "usb1_1/Ahmet/WhatsApp" gibi.
        val oldFolder = oldSettings.userFolder.trim('/')
        val newFolder = newSettings.userFolder.trim('/')
        viewModelScope.launch {
            var moved = 0
            if (oldFolder != newFolder) {
                mappingDao.getAll().forEach { mapping ->
                    val rebased = rebase(mapping.smbPath.trim('/'), oldFolder, newFolder)
                    if (rebased != null && rebased != mapping.smbPath) {
                        // Hedefte aynı eşleştirme varsa unique index patlar; sessiz geç.
                        val updated = runCatching {
                            mappingDao.update(mapping.copy(smbPath = rebased))
                            true
                        }.getOrDefault(false)
                        if (updated) moved++
                    }
                }
            }
            // Yeni klasörün fotoğrafları hemen görünsün.
            cloudPhotoStore.invalidate()
            cloudPhotoStore.refresh(force = true)
            _uiState.update {
                it.copy(
                    message = if (oldFolder != newFolder) {
                        if (moved > 0) {
                            "Kullanıcı klasörü değişti — $moved yedekleme hedefi taşındı."
                        } else {
                            "Kullanıcı klasörü değişti."
                        }
                    } else {
                        "Ayarlar kaydedildi."
                    }
                )
            }
        }
    }

    /**
     * Eski kullanıcı öneki taşıma: "usb1_1/Ayse/WhatsApp" + Ayse→Ahmet
     * = "usb1_1/Ahmet/WhatsApp". Öneki taşımayan yol (elle girilmiş farklı
     * hedef) olduğu gibi bırakılır (null).
     */
    private fun rebase(path: String, oldFolder: String, newFolder: String): String? {
        if (oldFolder.isBlank()) return null // nereden taşındığı bilinemez
        val suffix = when {
            path.equals(oldFolder, ignoreCase = true) -> ""
            path.startsWith("$oldFolder/", ignoreCase = true) ->
                path.substring(oldFolder.length + 1)
            else -> return null
        }
        return if (newFolder.isBlank()) suffix else listOf(newFolder, suffix)
            .filter { it.isNotBlank() }
            .joinToString("/")
    }

    // ── Kullanıcı klasörü gezgini ───────────────────────────────────────

    /** Paylaşım kökünden itibaren klasör gezgini açar. */
    fun openFolderBrowser() {
        if (!settingsStore.current().isConfigured) {
            _uiState.update {
                it.copy(message = "Önce sunucu bilgilerini kaydedin.")
            }
            return
        }
        _browser.value = FolderBrowserState(visible = true, loading = true)
        browseTo("")
    }

    fun closeFolderBrowser() {
        _browser.update { it.copy(visible = false) }
    }

    fun browseInto(entry: SmbEntry) {
        if (!entry.isDirectory) return
        browseTo(entry.path)
    }

    fun browseUp() {
        val path = _browser.value.path.trim('/')
        if (path.isEmpty()) return
        browseTo(path.substringBeforeLast('/', ""))
    }

    private fun browseTo(path: String) {
        viewModelScope.launch {
            _browser.update { it.copy(loading = true, error = null) }
            smbRepository.listDirectory(path)
                .onSuccess { entries ->
                    _browser.update {
                        it.copy(
                            path = path.trim('/'),
                            folders = entries.filter { e -> e.isDirectory },
                            loading = false,
                            error = null
                        )
                    }
                }
                .onFailure { error ->
                    _browser.update {
                        it.copy(
                            loading = false,
                            error = SmbRepository.describeError(error)
                        )
                    }
                }
        }
    }

    /** Gezilmekte olan klasörü kullanıcı klasörü olarak seçer (kaydet gerekir). */
    fun chooseCurrentFolder() {
        val path = _browser.value.path.trim('/')
        _browser.update { it.copy(visible = false) }
        _uiState.update {
            it.copy(
                userFolder = path,
                message = "Klasör seçildi: /${path.ifBlank { "kök" }} — " +
                    "etkili olması için Kaydet'e dokunun."
            )
        }
    }

    fun testConnection() {
        val state = _uiState.value
        if (!state.isConfigured) {
            _uiState.update {
                it.copy(message = "Test için sunucu IP adresi ve paylaşım adı gerekli.")
            }
            return
        }
        if (state.testing) return

        viewModelScope.launch {
            _uiState.update { it.copy(testing = true) }
            val result = smbRepository.testConnection(state.toSettings())
            val message = result.fold(
                onSuccess = { "Bağlantı başarılı. Paylaşım erişilebilir." },
                onFailure = { SmbRepository.describeError(it) }
            )
            _uiState.update { it.copy(testing = false, message = message) }
        }
    }

    // ── Izgara ısıtma ───────────────────────────────────────────────────

    /** null = hiç ısıtma görülmedi; ayarlar ekranı bu akışı gözler. */
    val gridWarm: StateFlow<GridWarmer.Progress?> = gridWarmer.progress

    /** Anahtarın başlangıç değeri (kaynak bazlı tercih GridWarmer'da durur). */
    fun isWarmAutoEnabled(): Boolean = gridWarmer.isAutoEnabled()

    fun setWarmAutoEnabled(enabled: Boolean) {
        gridWarmer.setAutoEnabled(enabled)
        if (enabled) gridWarmer.start() else gridWarmer.stop()
    }

    fun startGridWarm() = gridWarmer.start()

    fun stopGridWarm() = gridWarmer.stop()

    // ── Nextcloud ───────────────────────────────────────────────────────

    private val _ncState = MutableStateFlow(nextcloudSettingsStore.current().toNcUiState())
    val ncState: StateFlow<NextcloudUiState> = _ncState.asStateFlow()

    fun updateNcEnabled(value: Boolean) = _ncState.update { it.copy(enabled = value) }

    fun updateNcBaseUrl(value: String) = _ncState.update { it.copy(baseUrl = value) }

    fun updateNcUsername(value: String) = _ncState.update { it.copy(username = value) }

    fun updateNcAppPassword(value: String) = _ncState.update { it.copy(appPassword = value) }

    fun updateNcRootFolder(value: String) = _ncState.update { it.copy(rootFolder = value) }

    /**
     * Kendi imzalı sertifika izni. KAPALI varsayılır; açıldığında yalnızca
     * kendi kendini imzalamış zincirler kabul edilir (NextcloudTls), ana makine
     * adı doğrulaması açık kalır.
     */
    fun updateNcAllowSelfSigned(value: Boolean) = _ncState.update { it.copy(allowSelfSigned = value) }

    fun updateNcPrimarySource(value: MediaSourceKind) =
        _ncState.update { it.copy(primarySource = value) }

    fun consumeNcMessage() = _ncState.update { it.copy(message = null) }

    /**
     * Nextcloud ayarlarını kaydeder. Zaman tüneli Nextcloud'a çevrildiyse
     * ama erişim bilgileri eksikse kullanıcı uyarılır — yine de kaydedilir,
     * çünkü [CloudPhotoStore.activeSource] eksik ayarda SMP'ye düşer ve
     * galeri boş kalmaz.
     */
    fun saveNextcloud() {
        val state = _ncState.value
        if (state.primarySource == MediaSourceKind.NEXTCLOUD && !state.isConfigured) {
            _ncState.update {
                it.copy(
                    message = "Zaman tüneli Nextcloud seçili ama adres/kullanıcı/şifre " +
                        "eksik — galeri SMP kaynağında kalmaya devam eder."
                )
            }
        }
        nextcloudSettingsStore.save(state.toNcSettings())
        // Kaynak ya da kök değişmiş olabilir: liste bayattır, hemen yeniden tara.
        cloudPhotoStore.invalidate()
        nextcloudRepository.invalidateListings()
        cloudPhotoStore.refresh(force = true)
        _ncState.update {
            it.copy(message = "Nextcloud ayarları kaydedildi; zaman tüneli yeniden taranıyor.")
        }
    }

    /** Alanlardaki DEĞERLERLE test eder (kaydetmek gerekmez). */
    fun testNextcloud() {
        val state = _ncState.value
        if (!state.isConfigured) {
            _ncState.update {
                it.copy(message = "Adres, kullanıcı adı ve uygulama şifresi zorunlu.")
            }
            return
        }
        if (state.testing) return
        viewModelScope.launch {
            _ncState.update { it.copy(testing = true) }
            val result = nextcloudRepository.testConnection(state.toNcSettings())
            val message = result.fold(
                onSuccess = { "Bağlantı başarılı. Nextcloud yanıt veriyor." },
                onFailure = { NextcloudRepository.describeError(it) }
            )
            _ncState.update { it.copy(testing = false, message = message) }
        }
    }
}

private fun SmbSettings.toUiState() = SettingsUiState(
    host = host,
    share = share,
    domain = domain,
    username = username,
    password = password,
    autoBackup = autoBackup,
    intervalHours = intervalHours,
    userFolder = userFolder
)

private fun SettingsUiState.toSettings() = SmbSettings(
    host = host,
    share = share,
    domain = domain,
    username = username,
    password = password,
    autoBackup = autoBackup,
    intervalHours = intervalHours,
    userFolder = userFolder
)

private fun NextcloudSettings.toNcUiState() = NextcloudUiState(
    enabled = enabled,
    baseUrl = baseUrl,
    username = username,
    appPassword = appPassword,
    rootFolder = rootFolder,
    allowSelfSigned = allowSelfSigned,
    primarySource = primarySource
)

private fun NextcloudUiState.toNcSettings() = NextcloudSettings(
    enabled = enabled,
    baseUrl = baseUrl,
    username = username,
    appPassword = appPassword,
    rootFolder = rootFolder,
    allowSelfSigned = allowSelfSigned,
    primarySource = primarySource
)
