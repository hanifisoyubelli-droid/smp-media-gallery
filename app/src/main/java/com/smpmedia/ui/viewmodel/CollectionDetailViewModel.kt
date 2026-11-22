package com.smpmedia.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smpmedia.data.local.BackupRecordDao
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.model.BackupStatus
import com.smpmedia.data.model.PhotoItem
import com.smpmedia.data.repository.PhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionDetailUiState(
    val folderName: String = "",
    val photos: List<PhotoUiItem> = emptyList(),
    val loading: Boolean = true,
    val hasMapping: Boolean = false,
    val mappingEnabled: Boolean = false,
    val targetLabel: String? = null
)

/** Bir MediaStore bucket'ının içindeki fotoğrafları ve eşleştirme durumunu yükler. */
@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val photoRepository: PhotoRepository,
    recordDao: BackupRecordDao,
    mappingDao: FolderMappingDao
) : ViewModel() {

    private val bucketId: String = savedStateHandle.get<String>("bucketId").orEmpty()

    private val photos = MutableStateFlow<List<PhotoItem>>(emptyList())
    private val loading = MutableStateFlow(true)

    val uiState: StateFlow<CollectionDetailUiState> = combine(
        photos,
        recordDao.observeAll(),
        mappingDao.observeMappings(),
        loading
    ) { photoList, records, mappings, isLoading ->
        val latestStatus = records
            .groupBy { it.localUri }
            .mapValues { (_, list) ->
                list.maxByOrNull { it.updatedAt }?.status ?: BackupStatus.PENDING
            }

        val bucketMappings = mappings.filter { it.localBucketId == bucketId }
        val enabledMapping = bucketMappings.firstOrNull { it.enabled }
        val target = enabledMapping?.let { mapping ->
            buildString {
                append(mapping.smbShare)
                if (mapping.smbPath.isNotBlank()) {
                    append('/').append(mapping.smbPath)
                }
            }
        }

        CollectionDetailUiState(
            folderName = photoList.firstOrNull()?.bucketName
                ?: bucketMappings.firstOrNull()?.localFolderName
                ?: "Koleksiyon",
            photos = photoList
                .sortedByDescending { it.sortDate }
                .map { photo ->
                    PhotoUiItem(
                        photo = photo,
                        status = latestStatus[photo.uri.toString()] ?: BackupStatus.PENDING
                    )
                },
            loading = isLoading,
            hasMapping = bucketMappings.isNotEmpty(),
            mappingEnabled = enabledMapping != null,
            targetLabel = target
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionDetailUiState()
    )

    init {
        load()
    }

    fun load() {
        if (bucketId.isBlank()) {
            loading.value = false
            return
        }
        viewModelScope.launch {
            loading.value = true
            photos.value = photoRepository.getPhotosForBucket(bucketId)
            loading.value = false
        }
    }
}
