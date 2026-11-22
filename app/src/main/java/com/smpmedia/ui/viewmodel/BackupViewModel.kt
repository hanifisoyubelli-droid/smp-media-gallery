package com.smpmedia.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smpmedia.data.local.BackupRecordDao
import com.smpmedia.data.local.BackupRecordEntity
import com.smpmedia.data.local.FolderMappingDao
import com.smpmedia.data.local.FolderMappingEntity
import com.smpmedia.data.model.BackupProgress
import com.smpmedia.data.model.BackupStatus
import com.smpmedia.data.repository.BackupRepository
import com.smpmedia.work.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BackupUiState(
    val mappings: List<FolderMappingEntity> = emptyList(),
    val records: List<BackupRecordEntity> = emptyList(),
    val running: Boolean = false,
    val progress: BackupProgress = BackupProgress(),
    val message: String? = null
) {
    val successCount: Int
        get() = records.count { it.status == BackupStatus.SUCCESS }

    val failedCount: Int
        get() = records.count { it.status == BackupStatus.FAILED }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val mappingDao: FolderMappingDao,
    private val recordDao: BackupRecordDao,
    backupRepository: BackupRepository,
    private val backupScheduler: BackupScheduler
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<BackupUiState> = combine(
        mappingDao.observeMappings(),
        recordDao.observeRecent(100),
        backupRepository.isRunning,
        backupRepository.progress,
        message
    ) { mappings, records, running, progress, currentMessage ->
        BackupUiState(
            mappings = mappings,
            records = records,
            running = running,
            progress = progress,
            message = currentMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BackupUiState()
    )

    fun startNow() {
        backupScheduler.runNow()
        message.value = "Yedekleme kuyruğa alındı."
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
            message.value = "Eşleştirme ve kayıtları silindi."
        }
    }

    fun consumeMessage() {
        message.value = null
    }
}
