package com.appdevforall.pair.plugin.data

import org.json.JSONObject
import java.io.File

class DeviceSettingsStore(private val file: File) {

    private val lock = Any()

    fun deviceName(): String? = synchronized(lock) {
        read().optString(KEY_DEVICE_NAME).takeIf { it.isNotBlank() }
    }

    fun setDeviceName(name: String) = synchronized(lock) {
        write(read().put(KEY_DEVICE_NAME, name))
    }

    fun showPeerCursors(): Boolean = synchronized(lock) {
        read().optBoolean(KEY_SHOW_PEER_CURSORS, true)
    }

    fun setShowPeerCursors(enabled: Boolean) = synchronized(lock) {
        write(read().put(KEY_SHOW_PEER_CURSORS, enabled))
    }

    private fun read(): JSONObject = runCatching {
        if (file.exists()) JSONObject(file.readText()) else JSONObject()
    }.getOrDefault(JSONObject())

    private fun write(obj: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(obj.toString())
            if (!temp.renameTo(file)) {
                file.writeText(obj.toString())
                temp.delete()
            }
        }
    }

    private companion object {
        const val KEY_DEVICE_NAME = "deviceName"
        const val KEY_SHOW_PEER_CURSORS = "showPeerCursors"
    }
}
