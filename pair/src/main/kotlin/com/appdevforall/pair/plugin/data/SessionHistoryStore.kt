package com.appdevforall.pair.plugin.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SessionHistoryStore(private val file: File) {

    private val lock = Any()
    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<StoredSession>> = _sessions.asStateFlow()

    fun record(address: String, port: Int, role: SessionRole) = synchronized(lock) {
        val id = "$address:$port"
        val existing = _sessions.value.firstOrNull { it.id == id }
        val updated = StoredSession(
            id = id,
            customName = existing?.customName,
            address = address,
            port = port,
            role = role,
            lastConnectedMillis = System.currentTimeMillis(),
        )
        commit(_sessions.value.filterNot { it.id == id } + updated)
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        val trimmed = name.trim().ifBlank { null }
        commit(
            _sessions.value.map {
                if (it.id == id) it.copy(customName = trimmed) else it
            },
        )
    }

    fun delete(id: String) = synchronized(lock) {
        commit(_sessions.value.filterNot { it.id == id })
    }

    private fun commit(next: List<StoredSession>) {
        val capped = next
            .sortedByDescending { it.lastConnectedMillis }
            .take(MAX_SESSIONS)
        _sessions.value = capped
        persist(capped)
    }

    private fun load(): List<StoredSession> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length())
            .mapNotNull { index -> parse(array.optJSONObject(index)) }
            .sortedByDescending { it.lastConnectedMillis }
            .take(MAX_SESSIONS)
    }.getOrDefault(emptyList())

    private fun parse(obj: JSONObject?): StoredSession? {
        if (obj == null) return null
        return runCatching {
            StoredSession(
                id = obj.getString(KEY_ID),
                customName = if (obj.isNull(KEY_NAME)) null else obj.optString(KEY_NAME).ifBlank { null },
                address = obj.getString(KEY_ADDRESS),
                port = obj.getInt(KEY_PORT),
                role = SessionRole.valueOf(obj.getString(KEY_ROLE)),
                lastConnectedMillis = obj.getLong(KEY_TS),
            )
        }.getOrNull()
    }

    private fun persist(sessions: List<StoredSession>) {
        runCatching {
            val array = JSONArray()
            sessions.forEach { session ->
                val obj = JSONObject()
                obj.put(KEY_ID, session.id)
                obj.put(KEY_NAME, session.customName ?: JSONObject.NULL)
                obj.put(KEY_ADDRESS, session.address)
                obj.put(KEY_PORT, session.port)
                obj.put(KEY_ROLE, session.role.name)
                obj.put(KEY_TS, session.lastConnectedMillis)
                array.put(obj)
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.writeText(array.toString())
                temp.delete()
            }
        }
    }

    private companion object {
        const val MAX_SESSIONS = 8
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ADDRESS = "address"
        const val KEY_PORT = "port"
        const val KEY_ROLE = "role"
        const val KEY_TS = "ts"
    }
}
