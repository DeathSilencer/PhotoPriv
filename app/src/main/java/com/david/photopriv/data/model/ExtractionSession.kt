package com.david.photopriv.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "extraction_sessions")
data class ExtractionSession(
    @PrimaryKey(autoGenerate = true)
    val sessionId: Long = 0,
    val startTime: Long, // UNIX timestamp en segundos (T0)
    val endTime: Long? = null,
    val isActive: Boolean = true
)
