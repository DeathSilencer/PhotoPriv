package com.david.photopriv.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.david.photopriv.data.model.BackupStatus
import com.david.photopriv.data.model.ExtractionSession
import com.david.photopriv.data.model.TrackedPhoto
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    // --- Sesiones ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: ExtractionSession): Long

    @Update
    fun updateSession(session: ExtractionSession): Int

    @Query("SELECT * FROM extraction_sessions WHERE isActive = 1 ORDER BY sessionId DESC LIMIT 1")
    fun getActiveSession(): ExtractionSession?

    @Query("SELECT * FROM extraction_sessions WHERE isActive = 1 ORDER BY sessionId DESC LIMIT 1")
    fun getActiveSessionFlow(): Flow<ExtractionSession?>

    @Query("SELECT * FROM extraction_sessions ORDER BY sessionId DESC LIMIT 1")
    fun getLatestSessionFlow(): Flow<ExtractionSession?>

    @Query("UPDATE extraction_sessions SET isActive = 0, endTime = :endTime WHERE isActive = 1")
    fun closeAllActiveSessions(endTime: Long): Int

    // --- Fotos Rastreadas ---
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertPhotos(photos: List<TrackedPhoto>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrUpdatePhoto(photo: TrackedPhoto): Long

    @Update
    fun updatePhoto(photo: TrackedPhoto): Int

    @Query("UPDATE tracked_photos SET isReProtected = 1 WHERE mediaStoreId = :mediaStoreId")
    fun markAsProtected(mediaStoreId: Long): Int

    @Query("UPDATE tracked_photos SET backupStatus = :status, backupTimestamp = :timestamp, lastError = :error WHERE mediaStoreId = :mediaStoreId")
    fun updateBackupStatus(
        mediaStoreId: Long,
        status: BackupStatus,
        timestamp: Long? = null,
        error: String? = null
    ): Int

    @Query("UPDATE tracked_photos SET localStagingPath = :path WHERE mediaStoreId = :mediaStoreId")
    fun updateLocalStagingPath(mediaStoreId: Long, path: String?): Int

    @Query("SELECT * FROM tracked_photos WHERE sessionId = :sessionId ORDER BY dateAdded DESC")
    fun getPhotosForSession(sessionId: Long): Flow<List<TrackedPhoto>>

    @Query("SELECT * FROM tracked_photos WHERE sessionId = :sessionId")
    fun getPhotosForSessionSync(sessionId: Long): List<TrackedPhoto>

    @Query("SELECT mediaStoreId FROM tracked_photos WHERE sessionId = :sessionId")
    fun getTrackedMediaIdsForSession(sessionId: Long): List<Long>

    @Query("SELECT * FROM tracked_photos WHERE mediaStoreId = :mediaStoreId LIMIT 1")
    fun getPhotoById(mediaStoreId: Long): TrackedPhoto?

    @Query("UPDATE tracked_photos SET fileSizeBytes = :newSize WHERE mediaStoreId = :mediaStoreId")
    fun updateFileSize(mediaStoreId: Long, newSize: Long): Int

    @Query("UPDATE tracked_photos SET backupStatus = 'PENDING' WHERE sessionId = :sessionId AND backupStatus = 'BACKING_UP'")
    fun resetIncompleteUploads(sessionId: Long): Int

    @Query("SELECT * FROM tracked_photos WHERE sessionId = :sessionId AND backupStatus = 'PENDING' ORDER BY isVideo ASC, dateAdded ASC")
    fun getPendingBackupPhotos(sessionId: Long): List<TrackedPhoto>

    @Query("SELECT * FROM tracked_photos WHERE sessionId = :sessionId AND backupStatus IN ('PENDING', 'FAILED') ORDER BY isVideo ASC, dateAdded ASC")
    fun getPendingOrFailedPhotos(sessionId: Long): List<TrackedPhoto>

    @Query("SELECT COUNT(*) FROM tracked_photos WHERE sessionId = :sessionId")
    fun getTotalCount(sessionId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracked_photos WHERE sessionId = :sessionId AND isReProtected = 1")
    fun getProtectedCount(sessionId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracked_photos WHERE sessionId = :sessionId AND isReProtected = 0")
    fun getUnprotectedCount(sessionId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracked_photos WHERE sessionId = :sessionId AND backupStatus = 'BACKED_UP'")
    fun getBackedUpCount(sessionId: Long): Flow<Int>

    @Query("DELETE FROM tracked_photos WHERE mediaStoreId = :mediaStoreId")
    fun deletePhoto(mediaStoreId: Long): Int

    @Query("DELETE FROM tracked_photos WHERE sessionId = :sessionId AND backupStatus = 'FAILED'")
    fun deleteFailedPhotos(sessionId: Long): Int
}
