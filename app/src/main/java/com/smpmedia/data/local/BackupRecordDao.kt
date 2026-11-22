package com.smpmedia.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.smpmedia.data.model.BackupStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupRecordDao {

    @Query("SELECT * FROM backup_records ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<BackupRecordEntity>>

    @Query("SELECT * FROM backup_records ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BackupRecordEntity>>

    @Query("SELECT * FROM backup_records WHERE mappingId = :mappingId AND localKey = :localKey LIMIT 1")
    suspend fun get(mappingId: Long, localKey: String): BackupRecordEntity?

    @Upsert
    suspend fun upsert(record: BackupRecordEntity)

    @Query("DELETE FROM backup_records WHERE mappingId = :mappingId")
    suspend fun deleteByMapping(mappingId: Long)

    @Query("SELECT COUNT(*) FROM backup_records WHERE status = :status")
    fun observeCountByStatus(status: BackupStatus): Flow<Int>

    @Query(
        "UPDATE backup_records SET status = :status, errorMessage = NULL, updatedAt = :time " +
            "WHERE mappingId = :mappingId AND localKey = :localKey"
    )
    suspend fun markSucceeded(mappingId: Long, localKey: String, status: BackupStatus, time: Long)
}
