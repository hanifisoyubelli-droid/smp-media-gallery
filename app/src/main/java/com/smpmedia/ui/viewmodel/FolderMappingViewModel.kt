package com.smpmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smpmedia.data.local.BackupRecordDao
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.local.FolderMappingEntity
import com.smpmedia.data.model.LocalFolder
import com.smpmedia.data.model.SmbEntry
import com.smpmedia.data.prefs.SmbSettingsStore
import com.smpmedia.data.repository.PhotoRepository
import com.smpmedia.data.repository.SmbRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderMappingUiState(
    val localFolders: List<LocalFolder> = emptyList(),
    val selectedBucketId: String? = null,
    val mappings: List<FolderMappingEntity> = emptyList(),
    val smbPath: String = "",
    val smbFolders: List<SmbEntry> = emptyList(),
    val loading: Boolean = false,
    val settingsConfigured: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class FolderMappingViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    private val smbRepository: SmbRepository,
    private val mappingDao: FolderMappingDao,
    private val recordDao: BackupRecordDao,
    private val settingsStore: SmbSettingsStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(FolderMappingUiState())
    val uiState: StateFlow<FolderMappingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            mappingDao.observeMappings().collect { mappings ->
                _uiState.update { it.copy(mappings = mappings) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val folders = photoRepository.getLocalFolders()
            val configured = settingsStore.current().isConfigured

            _uiState.update { state ->
                state.copy(
                    localFolders = folders,
                    settingsConfigured = configured,
                    selectedBucketId = state.selectedBucketId ?: folders.firstOrNull()?.bucketId,
                    loading = false
                )
            }

            if (configured) {
                // Gezinti kullanıcının klasöründen başlasın: yeni eşleştirmeler
                // doğal olarak "usb1_1/<Kullanıcı>/..." altına düşsün.
                val start = _uiState.value.smbPath.ifBlank {
                    settingsStore.current().userFolder.trim('/')
                }
                openSmbPath(start)
            }
        }
    }

    fun selectBucket(bucketId: String) {
        _uiState.update { it.copy(selectedBucketId = bucketId) }
    }

    fun openSmbPath(path: String) {
        if (!settingsStore.current().isConfigured) {
            _uiState.update {
                it.copy(
                    settingsConfigured = false,
                    message = "Önce SMB sunucu bilgilerini kaydedin."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            smbRepository.listDirectory(path)
                .onSuccess { entries ->
                    _uiState.update {
                        it.copy(
                            smbPath = path.trim('/'),
                            smbFolders = entries.filter { entry -> entry.isDirectory },
                            loading = false
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            loading = false,
                            message = SmbRepository.describeError(error)
                        )
                    }
                }
        }
    }

    fun goUp() {
        val path = _uiState.value.smbPath.trim('/')
        val parent = path.substringBeforeLast('/', "")
        openSmbPath(parent)
    }

    fun addMapping() {
        val state = _uiState.value
        val bucket = state.localFolders.firstOrNull { it.bucketId == state.selectedBucketId }
        if (bucket == null) {
            _uiState.update { it.copy(message = "Önce bir yerel klasör seçin.") }
            return
        }
        val settings = settingsStore.current()
        if (!settings.isConfigured) {
            _uiState.update { it.copy(message = "Önce SMB ayarlarını girin.") }
            return
        }

        viewModelScope.launch {
            val id = mappingDao.insert(
                FolderMappingEntity(
                    localBucketId = bucket.bucketId,
                    localFolderName = bucket.name,
                    smbShare = settings.share,
                    smbPath = state.smbPath.trim('/')
                )
            )
            val target = if (state.smbPath.isBlank()) "/" else "/${state.smbPath.trim('/')}"
            _uiState.update {
                it.copy(
                    message = if (id == -1L) {
                        "Bu eşleştirme zaten mevcut."
                    } else {
                        "${bucket.name} → $target eşleştirildi."
                    }
                )
            }
        }
    }

    fun toggleMapping(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            mappingDao.setEnabled(id, enabled)
        }
    }

    fun deleteMapping(id: Long) {
        viewModelScope.launch {
            recordDao.deleteByMapping(id)
            mappingDao.deleteById(id)
            _uiState.update { it.copy(message = "Eşleştirme silindi.") }
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }
}
