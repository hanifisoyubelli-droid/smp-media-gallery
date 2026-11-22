package com.smpmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.model.LocalFolder
import com.smpmedia.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** Koleksiyonlar ekranındaki tek bir klasör satırı. */
data class CollectionItem(
    val folder: LocalFolder,
    val backupEnabled: Boolean,
    val targetLabel: String?,
    val mappingCount: Int
)

data class CollectionsUiState(
    val collections: List<CollectionItem> = emptyList(),
    val loading: Boolean = true,
    val permissionGranted: Boolean = false
)

/**
 * Telefondaki MediaStore klasörlerini, Room'daki eşleştirmelerle birleştirir.
 * Bir klasörün en az bir etkin eşleştirmesi varsa "yedekleme açık" kabul edilir.
 */
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val photoRepository: PhotoRepository,
    mappingDao: FolderMappingDao
) : ViewModel() {

    private val folders = MutableStateFlow<List<LocalFolder>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val permission = MutableStateFlow(photoRepository.hasImagePermission())

    val uiState: StateFlow<CollectionsUiState> = combine(
        folders,
        mappingDao.observeMappings(),
        loading,
        permission
    ) { folderList, mappings, isLoading, granted ->
        val mappingsByBucket = mappings.groupBy { it.localBucketId }

        CollectionsUiState(
            collections = folderList
                .map { folder ->
                    val bucketMappings = mappingsByBucket[folder.bucketId].orEmpty()
                    val enabledMappings = bucketMappings.filter { it.enabled }
                    CollectionItem(
                        folder = folder,
                        backupEnabled = enabledMappings.isNotEmpty(),
                        targetLabel = enabledMappings.firstOrNull()?.let { mapping ->
                            val path = buildString {
                                append(mapping.smbShare)
                                if (mapping.smbPath.isNotBlank()) {
                                    append('/').append(mapping.smbPath)
                                }
                            }
                            if (enabledMappings.size > 1) {
                                "$path +${enabledMappings.size - 1}"
                            } else {
                                path
                            }
                        },
                        mappingCount = bucketMappings.size
                    )
                }
                .sortedBy { it.folder.name.lowercase(Locale.ROOT) },
            loading = isLoading,
            permissionGranted = granted
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionsUiState(permissionGranted = permission.value)
    )

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            permission.value = photoRepository.hasImagePermission()
            folders.value = photoRepository.getLocalFolders()
            loading.value = false
        }
    }
}
