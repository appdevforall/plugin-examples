package com.appdevforall.contractor.plugin.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Query("SELECT * FROM tracked_projects ORDER BY isArchived ASC, displayName COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<TrackedProjectEntity>>

    @Query("SELECT * FROM tracked_projects WHERE isArchived = 0 ORDER BY displayName COLLATE NOCASE ASC")
    fun observeActive(): Flow<List<TrackedProjectEntity>>

    @Query("SELECT * FROM tracked_projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TrackedProjectEntity?

    @Query("SELECT * FROM tracked_projects WHERE rootPath = :rootPath LIMIT 1")
    suspend fun getByRootPath(rootPath: String): TrackedProjectEntity?

    @Query("SELECT * FROM tracked_projects WHERE rootPath = :rootPath LIMIT 1")
    fun observeByRootPath(rootPath: String): Flow<TrackedProjectEntity?>

    suspend fun insert(project: TrackedProjectEntity) = insertRow(
        project.id, project.rootPath, project.displayName, project.clientName,
        project.clientEmail, project.clientAddress, project.hourlyRate, project.currency,
        project.taxRatePercent, project.notes, project.createdAt, project.isArchived
    )

    @Query(
        "INSERT INTO tracked_projects " +
            "(id, rootPath, displayName, clientName, clientEmail, clientAddress, hourlyRate, currency, taxRatePercent, notes, createdAt, isArchived) " +
            "VALUES (:id, :rootPath, :displayName, :clientName, :clientEmail, :clientAddress, :hourlyRate, :currency, :taxRatePercent, :notes, :createdAt, :isArchived)"
    )
    suspend fun insertRow(
        id: String, rootPath: String, displayName: String, clientName: String,
        clientEmail: String?, clientAddress: String?, hourlyRate: Double, currency: String,
        taxRatePercent: Double, notes: String?, createdAt: Long, isArchived: Boolean
    )

    suspend fun update(project: TrackedProjectEntity) = updateRow(
        project.id, project.rootPath, project.displayName, project.clientName,
        project.clientEmail, project.clientAddress, project.hourlyRate, project.currency,
        project.taxRatePercent, project.notes, project.createdAt, project.isArchived
    )

    @Query(
        "UPDATE tracked_projects SET " +
            "rootPath = :rootPath, displayName = :displayName, clientName = :clientName, clientEmail = :clientEmail, " +
            "clientAddress = :clientAddress, hourlyRate = :hourlyRate, currency = :currency, taxRatePercent = :taxRatePercent, " +
            "notes = :notes, createdAt = :createdAt, isArchived = :isArchived WHERE id = :id"
    )
    suspend fun updateRow(
        id: String, rootPath: String, displayName: String, clientName: String,
        clientEmail: String?, clientAddress: String?, hourlyRate: Double, currency: String,
        taxRatePercent: Double, notes: String?, createdAt: Long, isArchived: Boolean
    )

    @Query("UPDATE tracked_projects SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("DELETE FROM tracked_projects WHERE id = :id")
    suspend fun deleteById(id: String)
}
