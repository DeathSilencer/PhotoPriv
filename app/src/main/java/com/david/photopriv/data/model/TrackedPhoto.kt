package com.david.photopriv.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BackupStatus {
    PENDING,
    BACKING_UP,
    BACKED_UP,
    FAILED
}

@Entity(
    tableName = "tracked_photos",
    foreignKeys = [
        ForeignKey(
            entity = ExtractionSession::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class TrackedPhoto(
    @PrimaryKey
    val mediaStoreId: Long,
    val sessionId: Long,
    val uriString: String,
    val displayName: String,
    val dateAdded: Long,
    val dateTaken: Long = 0,
    val fileSizeBytes: Long = 0,
    val isVideo: Boolean = false,
    val mimeType: String = "image/jpeg",
    val backupStatus: BackupStatus = BackupStatus.PENDING,
    val isReProtected: Boolean = false,
    val localStagingPath: String? = null,
    val backupTimestamp: Long? = null,
    val lastError: String? = null
)

