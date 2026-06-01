package com.appdevforall.sketchtoui.plugin

import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import java.io.File

class GeneratedStringsWriter(
    private val projectService: IdeProjectService?,
    private val fileService: IdeFileService?
) {
    fun write(stringsXml: String?): Boolean {
        if (stringsXml.isNullOrBlank()) return true
        val service = fileService ?: return false
        val projectRoot = projectService?.getCurrentProject()?.rootDir ?: return false
        val stringsFile = File(projectRoot, STRINGS_XML_RELATIVE_PATH)
        val existing = service.readFile(stringsFile)

        val updated = if (existing.isNullOrBlank()) {
            "<resources>\n${stringsXml.trim().prependIndent("    ")}\n</resources>\n"
        } else {
            val insertionPoint = existing.lastIndexOf("</resources>")
            if (insertionPoint < 0) return false
            existing.take(insertionPoint).trimEnd() +
                "\n${stringsXml.trim().prependIndent("    ")}\n" +
                existing.substring(insertionPoint)
        }

        return service.writeFile(stringsFile, updated)
    }

    private companion object {
        const val STRINGS_XML_RELATIVE_PATH = "app/src/main/res/values/strings.xml"
    }
}
