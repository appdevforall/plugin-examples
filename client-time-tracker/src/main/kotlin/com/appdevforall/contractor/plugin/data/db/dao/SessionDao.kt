package com.appdevforall.contractor.plugin.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM work_sessions WHERE projectId = :projectId ORDER BY startTime DESC")
    fun observeForProject(projectId: String): Flow<List<WorkSessionEntity>>

    @Query("""
        SELECT * FROM work_sessions
        WHERE projectId = :projectId
          AND startTime >= :from
          AND startTime < :to
        ORDER BY startTime ASC
    """)
    suspend fun getInRange(projectId: String, from: Long, to: Long): List<WorkSessionEntity>

    @Query("""
        SELECT * FROM work_sessions
        WHERE startTime >= :from AND startTime < :to
        ORDER BY startTime DESC
    """)
    fun observeInRange(from: Long, to: Long): Flow<List<WorkSessionEntity>>

    @Query("""
        SELECT * FROM work_sessions
        WHERE projectId = :projectId AND startTime >= :from AND startTime < :to
        ORDER BY startTime DESC
    """)
    fun observeForProjectInRange(projectId: String, from: Long, to: Long): Flow<List<WorkSessionEntity>>

    @Query("SELECT * FROM work_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkSessionEntity?

    @Query("SELECT * FROM work_sessions WHERE projectId = :projectId AND endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveForProject(projectId: String): WorkSessionEntity?

    @Query("SELECT * FROM work_sessions WHERE endTime IS NULL")
    suspend fun getAllActive(): List<WorkSessionEntity>

    /** Distinct project IDs that currently have an open (un-ended) session. */
    @Query("SELECT DISTINCT projectId FROM work_sessions WHERE endTime IS NULL")
    fun observeOpenSessionProjectIds(): Flow<List<String>>

    @Query("SELECT IFNULL(SUM(IFNULL(endTime, lastHeartbeatAt) - startTime), 0) FROM work_sessions WHERE projectId = :projectId AND startTime >= :from AND startTime < :to")
    suspend fun sumDurationMillisInRange(projectId: String, from: Long, to: Long): Long

    @Query("SELECT IFNULL(SUM(IFNULL(endTime, lastHeartbeatAt) - startTime), 0) FROM work_sessions WHERE projectId = :projectId")
    fun observeTotalMillis(projectId: String): Flow<Long>

    suspend fun upsert(session: WorkSessionEntity) = upsertRow(
        session.id, session.projectId, session.startTime, session.endTime,
        session.lastHeartbeatAt, session.saveCount, session.editEventCount, session.notes
    )

    @Query(
        "INSERT OR REPLACE INTO work_sessions " +
            "(id, projectId, startTime, endTime, lastHeartbeatAt, saveCount, editEventCount, notes) " +
            "VALUES (:id, :projectId, :startTime, :endTime, :lastHeartbeatAt, :saveCount, :editEventCount, :notes)"
    )
    suspend fun upsertRow(
        id: String, projectId: String, startTime: Long, endTime: Long?,
        lastHeartbeatAt: Long, saveCount: Int, editEventCount: Int, notes: String?
    )

    suspend fun update(session: WorkSessionEntity) = updateRow(
        session.id, session.projectId, session.startTime, session.endTime,
        session.lastHeartbeatAt, session.saveCount, session.editEventCount, session.notes
    )

    @Query(
        "UPDATE work_sessions SET " +
            "projectId = :projectId, startTime = :startTime, endTime = :endTime, lastHeartbeatAt = :lastHeartbeatAt, " +
            "saveCount = :saveCount, editEventCount = :editEventCount, notes = :notes WHERE id = :id"
    )
    suspend fun updateRow(
        id: String, projectId: String, startTime: Long, endTime: Long?,
        lastHeartbeatAt: Long, saveCount: Int, editEventCount: Int, notes: String?
    )

    @Query("UPDATE work_sessions SET endTime = :endTime, lastHeartbeatAt = :endTime WHERE id = :id")
    suspend fun closeSession(id: String, endTime: Long)

    @Query("UPDATE work_sessions SET lastHeartbeatAt = :ts, editEventCount = editEventCount + :editDelta, saveCount = saveCount + :saveDelta WHERE id = :id")
    suspend fun heartbeat(id: String, ts: Long, editDelta: Int, saveDelta: Int)

    @Query("DELETE FROM work_sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}
