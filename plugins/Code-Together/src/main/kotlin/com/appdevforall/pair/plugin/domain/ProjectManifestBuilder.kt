package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.ManifestEntry
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.IdeProjectService
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class ProjectManifestBuilder(
    private val projectService: IdeProjectService?,
    private val logger: PluginLogger,
) {

    fun rootDir(): File? =
        runCatching { projectService?.getCurrentProject()?.rootDir }.getOrNull()

    fun build(): List<ManifestEntry> {
        val root = rootDir() ?: return emptyList()
        val rootPath = root.absolutePath
        val entries = ArrayList<ManifestEntry>()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in EXCLUDED_DIRS }
            .filter { it.isFile }
            .forEach { file ->
                if (file.length() > MAX_FILE_BYTES) {
                    logger.warn("PairPlugin: excluding large file '${file.name}' (${file.length()} bytes) from manifest")
                    return@forEach
                }
                val hash = sha256(file) ?: return@forEach
                val relative = file.absolutePath.removePrefix(rootPath).removePrefix(File.separator)
                entries.add(ManifestEntry(relative, file.length(), hash))
            }
        return entries
    }

    fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrNull()

    companion object {
        val EXCLUDED_DIRS: Set<String> =
            setOf("build", ".gradle", ".git", ".idea", ".cxx", "node_modules", "captures", ".kotlin")
        const val MAX_FILE_BYTES: Long = 25L * 1024 * 1024
        private const val BUFFER_BYTES = 8192
    }
}
