package com.appdevforall.contractor.plugin.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_sessions",
    foreignKeys = [
        ForeignKey(
            entity = TrackedProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("projectId"),
        Index("startTime"),
        Index(value = ["projectId", "endTime"])
    ]
)
data class WorkSessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val startTime: Long,
    val endTime: Long?,
    val lastHeartbeatAt: Long,
    val saveCount: Int = 0,
    val editEventCount: Int = 0,
    val notes: String? = null
)
