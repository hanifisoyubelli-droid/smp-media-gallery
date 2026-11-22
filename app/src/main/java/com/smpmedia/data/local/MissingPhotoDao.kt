package com.smpmedia.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Eksik kalan fotoğraf küçük resimleri için basit kalıcı kuyruk. */
@Dao
interface MissingPhotoDao {

    @Query("SELECT * FROM missing_photos ORDER BY updatedAt ASC")
    fun observeAll(): Flow<List<MissingPhotoEntity>>

    @Query("SELECT * FROM missing_photos WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getByKey(cacheKey: String): MissingPhotoEntity?

    @Query(
        """
        SELECT * FROM missing_photos
        WHERE attempts < :maxAttempts
        ORDER BY updatedAt ASC
        LIMIT :limit
        """
    )
    suspend fun getRetryable(maxAttempts: Int, limit: Int): List<MissingPhotoEntity>

    @Query("SELECT COUNT(*) FROM missing_photos WHERE attempts < :maxAttempts")
    suspend fun countRetryable(maxAttempts: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MissingPhotoEntity)

    @Query("DELETE FROM missing_photos WHERE cacheKey = :cacheKey")
    suspend fun delete(cacheKey: String)
}