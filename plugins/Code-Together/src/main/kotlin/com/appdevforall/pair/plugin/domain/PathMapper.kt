package com.appdevforall.pair.plugin.domain

import com.itsaky.androidide.plugins.services.IdeProjectService
import java.io.File

class PathMapper(private val projectService: IdeProjectService?) {

    private fun rootPath(): String? =
        runCatching { projectService?.getCurrentProject()?.rootDir?.absolutePath }.getOrNull()

    fun toWire(absolutePath: String): String {
        val root = rootPath() ?: return absolutePath
        if (!absolutePath.startsWith(root)) return absolutePath
        return absolutePath.removePrefix(root).removePrefix(File.separator)
    }

    fun toLocal(wirePath: String): String {
        if (File(wirePath).isAbsolute) return wirePath
        val root = rootPath() ?: return wirePath
        return File(root, wirePath).absolutePath
    }

    fun toLocalChecked(wirePath: String): File? {
        if (File(wirePath).isAbsolute) return null
        val root = rootPath() ?: return null
        return runCatching {
            val rootFile = File(root).canonicalFile
            val resolved = File(rootFile, wirePath).canonicalFile
            val boundary = rootFile.path + File.separator
            if (resolved.path == rootFile.path || resolved.path.startsWith(boundary)) resolved else null
        }.getOrNull()
    }
}
