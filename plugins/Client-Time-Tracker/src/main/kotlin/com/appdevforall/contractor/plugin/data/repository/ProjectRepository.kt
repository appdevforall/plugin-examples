package com.appdevforall.contractor.plugin.data.repository

import com.appdevforall.contractor.plugin.data.db.dao.ProjectDao
import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ProjectRepository(private val dao: ProjectDao) {

    fun observeAll(): Flow<List<TrackedProjectEntity>> = dao.observeAll()
    fun observeActive(): Flow<List<TrackedProjectEntity>> = dao.observeActive()

    suspend fun getById(id: String): TrackedProjectEntity? = dao.getById(id)
    suspend fun getByRootPath(rootPath: String): TrackedProjectEntity? = dao.getByRootPath(rootPath)
    fun observeByRootPath(rootPath: String): Flow<TrackedProjectEntity?> = dao.observeByRootPath(rootPath)

    suspend fun register(
        rootPath: String,
        displayName: String,
        clientName: String,
        clientEmail: String?,
        clientAddress: String?,
        hourlyRate: Double,
        currency: String,
        taxRatePercent: Double,
        notes: String?
    ): TrackedProjectEntity {
        val entity = TrackedProjectEntity(
            id = UUID.randomUUID().toString(),
            rootPath = rootPath,
            displayName = displayName,
            clientName = clientName,
            clientEmail = clientEmail?.takeIf { it.isNotBlank() },
            clientAddress = clientAddress?.takeIf { it.isNotBlank() },
            hourlyRate = hourlyRate,
            currency = currency,
            taxRatePercent = taxRatePercent,
            notes = notes?.takeIf { it.isNotBlank() },
            createdAt = System.currentTimeMillis(),
            isArchived = false
        )
        dao.insert(entity)
        return entity
    }

    suspend fun update(project: TrackedProjectEntity) = dao.update(project)
    suspend fun setArchived(id: String, archived: Boolean) = dao.setArchived(id, archived)
    suspend fun delete(id: String) = dao.deleteById(id)
}
