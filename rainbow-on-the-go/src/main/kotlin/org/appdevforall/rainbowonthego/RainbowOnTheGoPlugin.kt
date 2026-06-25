package org.appdevforall.rainbowonthego

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DecorationSpan
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.EditorDecorationProvider
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * RainbowOnTheGo — tints matching parentheses, brackets, and braces by nesting depth.
 *
 * Implemented as a generic [EditorDecorationProvider]: the Code On The Go editor calls [decorate]
 * for each analyzed region and merges the returned color spans on top of the normal syntax
 * highlighting. All of the logic — bracket detection, nesting-depth tracking, theme palettes, and
 * skipping brackets inside strings/comments — lives here in the plugin; the IDE is unaware that
 * this is about brackets at all.
 */
class RainbowOnTheGoPlugin : IPlugin, EditorDecorationProvider, DocumentationExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "org.appdevforall.rainbowonthego"
        const val TOOLTIP_TAG = "rainbow.info"

        // Six colors in ROYGBV order. Index 0 = outermost nesting depth; colors cycle by depth.
        // Day palette is deeper/saturated so it stays legible on a light editor background.
        private val DAY_PALETTE = intArrayOf(
            0xFFD11C1C.toInt(), // red
            0xFFD9730D.toInt(), // orange
            0xFFB8860B.toInt(), // amber/yellow (darkened so it reads on white)
            0xFF1B8A2E.toInt(), // green
            0xFF1565C0.toInt(), // blue
            0xFF7B1FA2.toInt(), // purple
        )

        // Night palette is brighter/pastel so it pops against a dark background without glaring.
        private val NIGHT_PALETTE = intArrayOf(
            0xFFFF6B6B.toInt(), // red
            0xFFFFA94D.toInt(), // orange
            0xFFFFE066.toInt(), // yellow
            0xFF69DB7C.toInt(), // green
            0xFF4DABF7.toInt(), // blue
            0xFFDA77F2.toInt(), // purple
        )

        private fun isOpen(c: Char): Boolean = c == '(' || c == '[' || c == '{'
        private fun isClose(c: Char): Boolean = c == ')' || c == ']' || c == '}'
    }

    // --- IPlugin lifecycle ---

    override fun initialize(context: PluginContext): Boolean {
        // initialize() returns Boolean — the IDE skips activate() if this returns false.
        // Wrap in try/catch so a stray exception in our setup can't crash the host IDE.
        return try {
            this.context = context
            context.logger.info("RainbowOnTheGoPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("RainbowOnTheGoPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("RainbowOnTheGoPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("RainbowOnTheGoPlugin deactivated")
        return true
    }

    override fun dispose() {
        context.logger.info("RainbowOnTheGoPlugin disposed")
    }

    // --- EditorDecorationProvider: the rainbow logic ---

    override fun decorate(text: CharSequence, start: Int, end: Int, isDark: Boolean): List<DecorationSpan> {
        if (end <= start) return emptyList()
        val palette = if (isDark) NIGHT_PALETTE else DAY_PALETTE

        // Nesting depth (and whether we're inside a block comment) at the start of this region.
        // Line comments and single-line strings reset at newlines, so only depth and block-comment
        // state carry across lines; scan the prefix to recover them.
        var depth = 0
        var inBlock = false
        run {
            var inString = false
            var quote = ' '
            var escaped = false
            var lineComment = false
            var i = 0
            while (i < start) {
                val c = text[i]
                val c2 = if (i + 1 < start) text[i + 1] else ' '
                when {
                    c == '\n' -> { lineComment = false; inString = false; escaped = false }
                    lineComment -> { /* skip */ }
                    inBlock -> if (c == '*' && c2 == '/') { inBlock = false; i++ }
                    inString -> when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == quote -> inString = false
                    }
                    c == '/' && c2 == '/' -> { lineComment = true; i++ }
                    c == '/' && c2 == '*' -> { inBlock = true; i++ }
                    c == '#' -> lineComment = true
                    c == '"' || c == '\'' || c == '`' -> { inString = true; quote = c }
                    isOpen(c) -> depth++
                    isClose(c) -> if (depth > 0) depth--
                }
                i++
            }
        }

        // Scan the region, emitting a one-character colored span per bracket. Brackets inside
        // strings and comments are skipped so they keep their normal color.
        val spans = ArrayList<DecorationSpan>()
        var inString = false
        var quote = ' '
        var escaped = false
        var lineComment = false
        var i = start
        while (i < end) {
            val c = text[i]
            val c2 = if (i + 1 < end) text[i + 1] else ' '
            when {
                lineComment -> { /* skip to end of region */ }
                inBlock -> if (c == '*' && c2 == '/') { inBlock = false; i++ }
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> inString = false
                }
                c == '/' && c2 == '/' -> { lineComment = true; i++ }
                c == '/' && c2 == '*' -> { inBlock = true; i++ }
                c == '#' -> lineComment = true
                c == '"' || c == '\'' || c == '`' -> { inString = true; quote = c }
                isOpen(c) -> {
                    spans.add(DecorationSpan(i, i + 1, palette[depth % palette.size]))
                    depth++
                }
                isClose(c) -> {
                    if (depth > 0) depth--
                    spans.add(DecorationSpan(i, i + 1, palette[depth % palette.size]))
                }
            }
            i++
        }
        return spans
    }

    // --- DocumentationExtension: tooltip + in-IDE help ---
    //
    //   Tier 1 = summary        (one-liner)
    //   Tier 2 = detail         (HTML paragraph)
    //   Tier 3 = buttons[].uri  (HTML page served at
    //                            http://localhost:6174/plugin/<pluginId>/<uri>)

    override fun getTooltipCategory(): String = "plugin_rainbow_on_the_go"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG,
            summary = "Colors brackets by nesting depth — red, orange, yellow, green, blue, purple.",
            detail = """
                <p><b>RainbowOnTheGo</b> tints <b>()</b>, <b>[]</b> and <b>{}</b> by how deeply
                they nest, so a delimiter and its match share a color.</p>
                <ul>
                  <li>Six colors cycle red → orange → yellow → green → blue → purple.</li>
                  <li>Separate palettes are used for the light and dark editor themes.</li>
                  <li>It only adds color on top of normal highlighting — nothing else changes.</li>
                </ul>
                <p>Disable the plugin to return brackets to the editor's normal color.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "How it works",
                    uri = "index.html", // resolves to plugin/<id>/index.html
                    order = 0
                )
            )
        )
    )

    /**
     * Subdirectory under src/main/assets/ that holds the Tier 3 help. Every file under
     * assets/docs/ is indexed at install time and served from
     *   http://localhost:6174/plugin/org.appdevforall.rainbowonthego/<file>
     */
    override fun getTier3DocsAssetPath(): String? = "docs"
}
