package com.appdevforall.contractor.plugin.data.repository

import com.appdevforall.contractor.plugin.data.db.dao.SessionDao
import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class SessionRepository(private val dao: SessionDao) {

    fun observeForProject(projectId: String): Flow<List<WorkSessionEntity>> =
        dao.observeForProject(projectId)

    fun observeForProjectInRange(projectId: String, from: Long, to: Long): Flow<List<WorkSessionEntity>> =
        dao.observeForProjectInRange(projectId, from, to)

    fun observeInRange(from: Long, to: Long): Flow<List<WorkSessionEntity>> =
        dao.observeInRange(from, to)

    fun observeTotalMillis(projectId: String): Flow<Long> = dao.observeTotalMillis(projectId)

    suspend fun getInRange(projectId: String, from: Long, to: Long) =
        dao.getInRange(projectId, from, to)

    suspend fun sumDurationMillisInRange(projectId: String, from: Long, to: Long): Long =
        dao.sumDurationMillisInRange(projectId, from, to)

    suspend fun getActiveForProject(projectId: String): WorkSessionEntity? =
        dao.getActiveForProject(projectId)

    suspend fun getAllActive(): List<WorkSessionEntity> = dao.getAllActive()

    fun observeOpenSessionProjectIds(): Flow<List<String>> = dao.observeOpenSessionProjectIds()

    suspend fun getById(id: String): WorkSessionEntity? = dao.getById(id)

    suspend fun startSession(projectId: String, startTime: Long): WorkSessionEntity {
        val entity = WorkSessionEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            startTime = startTime,
            endTime = null,
            lastHeartbeatAt = startTime,
            saveCount = 0,
            editEventCount = 0,
            notes = null
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun heartbeat(sessionId: String, ts: Long, editDelta: Int = 0, saveDelta: Int = 0) {
        dao.heartbeat(sessionId, ts, editDelta, saveDelta)
    }

    suspend fun closeSession(sessionId: String, endTime: Long) {
        dao.closeSession(sessionId, endTime)
    }

    suspend fun upsert(session: WorkSessionEntity) = dao.upsert(session)
    suspend fun update(session: WorkSessionEntity) = dao.update(session)
    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun addManual(
        projectId: String,
        startTime: Long,
        endTime: Long,
        notes: String?
    ): WorkSessionEntity {
        val entity = WorkSessionEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            startTime = startTime,
            endTime = endTime,
            lastHeartbeatAt = endTime,
            saveCount = 0,
            editEventCount = 0,
            notes = notes?.takeIf { it.isNotBlank() }
        )
        dao.upsert(entity)
        return entity
    }
}
