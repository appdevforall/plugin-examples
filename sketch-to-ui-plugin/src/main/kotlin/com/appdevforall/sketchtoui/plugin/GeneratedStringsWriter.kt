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

        val updated = GeneratedStringArrayMerger.merge(existing, stringsXml) ?: return false

        return service.writeFile(stringsFile, updated)
    }

    private companion object {
        const val STRINGS_XML_RELATIVE_PATH = "app/src/main/res/values/strings.xml"
    }
}

internal object GeneratedStringArrayMerger {
    private val stringArrayBlockRegex = Regex(
        """<string-array\b[^>]*\bname\s*=\s*(['"])([^'"]+)\1[^>]*>.*?</string-array>""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    private val itemRegex = Regex("""<item\b[^>]*>(.*?)</item>""", setOf(RegexOption.DOT_MATCHES_ALL))

    fun merge(existing: String?, generated: String): String? {
        val generatedArrays = parseStringArrays(generated)
        if (generatedArrays.isEmpty()) return existing

        val existingXml = existing?.takeIf { it.isNotBlank() }
            ?: return buildResourcesFile(generatedArrays)

        val result = generatedArrays.fold(MergeState(existingXml)) { state, generatedArray ->
            state.merge(generatedArray) ?: return null
        }

        return result.xml.takeIf { result.changed } ?: existingXml
    }

    private fun buildResourcesFile(arrays: List<StringArrayResource>): String {
        return "<resources>\n${arrays.joinToString("\n\n") { it.toXmlBlock() }}\n</resources>\n"
    }

    private fun MergeState.merge(generatedArray: StringArrayResource): MergeState? {
        val existingBlocks = findStringArrayBlocks(xml, generatedArray.name)
        return when {
            existingBlocks.isEmpty() -> append(generatedArray)
            existingBlocks.shouldReplaceWith(generatedArray) -> replace(existingBlocks, generatedArray)
            else -> this
        }
    }

    private fun MergeState.append(array: StringArrayResource): MergeState? {
        val insertionPoint = xml.lastIndexOf("</resources>")
        if (insertionPoint < 0) return null

        val updatedXml = xml.take(insertionPoint).trimEnd() +
            "\n\n${array.toXmlBlock()}\n" +
            xml.substring(insertionPoint)

        return copy(xml = updatedXml, changed = true)
    }

    private fun MergeState.replace(blocks: List<MatchResult>, array: StringArrayResource): MergeState {
        val replacement = array.toXmlBlock()
        val updatedXml = blocks.asReversed().foldIndexed(xml) { index, currentXml, block ->
            if (index == blocks.lastIndex) {
                currentXml.replaceRange(block.range, replacement)
            } else {
                currentXml.removeRangeWithBlankLine(block.range)
            }
        }

        return copy(xml = updatedXml, changed = true)
    }

    private fun List<MatchResult>.shouldReplaceWith(array: StringArrayResource): Boolean {
        val existingItems = parseStringArrays(first().value).firstOrNull()?.items
        return existingItems != array.items || size > 1
    }

    private fun parseStringArrays(xml: String): List<StringArrayResource> {
        return stringArrayBlockRegex.findAll(xml.wrapInResourcesIfNeeded()).mapNotNull { match ->
            val name = match.groupValues[2].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val items = itemRegex.findAll(match.value).map { itemMatch ->
                itemMatch.groupValues[1].unescapeXmlText()
            }.toList()
            StringArrayResource(name, items)
        }.toList()
    }

    private fun findStringArrayBlocks(xml: String, name: String): List<MatchResult> {
        return stringArrayBlockRegex.findAll(xml)
            .filter { it.groupValues[2] == name }
            .toList()
    }

    private fun String.wrapInResourcesIfNeeded(): String {
        val trimmed = trim()
        return if (trimmed.startsWith("<resources")) trimmed else "<resources>\n$trimmed\n</resources>"
    }

    private fun String.unescapeXmlText(): String {
        return trim()
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
    }

    private fun String.removeRangeWithBlankLine(range: IntRange): String {
        var start = range.first
        var endExclusive = range.last + 1

        if (start >= 2 && substring(start - 2, start) == "\n\n") {
            start -= 2
        } else if (start >= 1 && this[start - 1] == '\n') {
            start -= 1
        }

        if (endExclusive < length && this[endExclusive] == '\n') {
            endExclusive += 1
        }

        return removeRange(start, endExclusive)
    }

    private data class MergeState(
        val xml: String,
        val changed: Boolean = false
    )
}

private data class StringArrayResource(
    val name: String,
    val items: List<String>
) {
    fun toXmlBlock(): String {
        return buildString {
            appendLine("""    <string-array name="$name">""")
            items.forEach { item ->
                appendLine("        <item>${item.escapeXmlText()}</item>")
            }
            append("""    </string-array>""")
        }
    }

    private fun String.escapeXmlText(): String = trim()
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
