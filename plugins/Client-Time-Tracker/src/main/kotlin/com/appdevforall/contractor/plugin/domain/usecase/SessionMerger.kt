package com.appdevforall.contractor.plugin.domain.usecase

import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity

data class MergedSession(
    val startTime: Long,
    val endTime: Long,
    val durationMillis: Long,
    val sourceCount: Int
)

object SessionMerger {

    fun durationOf(session: WorkSessionEntity): Long {
        val end = session.endTime ?: session.lastHeartbeatAt
        return (end - session.startTime).coerceAtLeast(0L)
    }

    fun mergeAdjacent(sessions: List<WorkSessionEntity>, mergeGapMillis: Long): List<MergedSession> {
        if (sessions.isEmpty()) return emptyList()

        val sorted = sessions.sortedBy { it.startTime }
        val merged = mutableListOf<MergedSession>()

        var currentStart = sorted.first().startTime
        var currentEnd = sorted.first().endTime ?: sorted.first().lastHeartbeatAt
        var count = 1

        for (i in 1 until sorted.size) {
            val s = sorted[i]
            val sEnd = s.endTime ?: s.lastHeartbeatAt
            if (s.startTime - currentEnd <= mergeGapMillis) {
                if (sEnd > currentEnd) currentEnd = sEnd
                count++
            } else {
                merged += MergedSession(currentStart, currentEnd, currentEnd - currentStart, count)
                currentStart = s.startTime
                currentEnd = sEnd
                count = 1
            }
        }
        merged += MergedSession(currentStart, currentEnd, currentEnd - currentStart, count)
        return merged
    }
}
