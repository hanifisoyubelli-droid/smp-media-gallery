package com.smpmedia.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderMappingDao {

    @Query("SELECT * FROM folder_mappings ORDER BY createdAt DESC")
    fun observeMappings(): Flow<List<FolderMappingEntity>>

    @Query("SELECT * FROM folder_mappings WHERE enabled = 1 ORDER BY createdAt ASC")
    suspend fun getEnabledMappings(): List<FolderMappingEntity>

    /** Kullanıcı klasörü değişince hedef yolları yeniden yazmak için. */
    @Query("SELECT * FROM folder_mappings ORDER BY createdAt ASC")
    suspend fun getAll(): List<FolderMappingEntity>

    @Query("SELECT * FROM folder_mappings WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderMappingEntity?

    /** Aynı eşleştirme varsa -1 döner (unique index sayesinde). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(mapping: FolderMappingEntity): Long

    @Update
    suspend fun update(mapping: FolderMappingEntity)

    @Query("UPDATE folder_mappings SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE folder_mappings SET lastBackupAt = :time WHERE id = :id")
    suspend fun markBackedUp(id: Long, time: Long)

    @Query("DELETE FROM folder_mappings WHERE id = :id")
    suspend fun deleteById(id: Long)
}
