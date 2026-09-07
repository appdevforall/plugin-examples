package com.appdevforall.contractor.plugin.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracked_projects",
    indices = [Index(value = ["rootPath"], unique = true)]
)
data class TrackedProjectEntity(
    @PrimaryKey val id: String,
    val rootPath: String,
    val displayName: String,
    val clientName: String,
    val clientEmail: String?,
    val clientAddress: String?,
    val hourlyRate: Double,
    val currency: String,
    val taxRatePercent: Double,
    val notes: String?,
    val createdAt: Long,
    val isArchived: Boolean = false
)
