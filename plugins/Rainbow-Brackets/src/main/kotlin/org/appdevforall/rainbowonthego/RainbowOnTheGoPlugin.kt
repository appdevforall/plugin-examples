package org.appdevforall.rainbowonthego

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DecorationSpan
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.EditorDecorationProvider
import com.itsaky.androidide.plugins.extensions.FileOpenExtension
import com.itsaky.androidide.plugins.extensions.FileTabMenuItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import java.io.File
import java.util.TreeMap

/**
 * RainbowOnTheGo — tints matching parentheses, brackets, and braces by nesting depth.
 *
 * Implemented as a generic [EditorDecorationProvider]: the Code On The Go editor calls [decorate]
 * for each analyzed region and merges the returned color spans on top of the normal syntax
 * highlighting. All of the logic — bracket detection, nesting-depth tracking, theme palettes, and
 * skipping brackets inside strings/comments — lives here in the plugin; the IDE is unaware that
 * this is about brackets at all.
 */
class RainbowOnTheGoPlugin : IPlugin, EditorDecorationProvider, FileOpenExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    // --- Active-file language gate ---
    //
    // decorate() is handed only the editor text and an offset range — it gets NO language or file
    // path. To keep rainbow coloring to Java/Kotlin (and never touch Python, Rust, C/C++, etc.) we
    // observe file opens/closes via FileOpenExtension and remember the extension of the most
    // recently opened file that is still open, treating that as the "active" editor. decorate()
    // colors only when that active file is Java/Kotlin.
    //
    // Known limitation: FileOpenExtension reports opens/closes, not tab focus, so switching back to
    // an already-open tab does not update the active file. Worst case is a stale gate — usually a
    // missed coloring; a wrong-language coloring is only possible in the narrow window where a JVM
    // file was opened most recently but a non-JVM tab is refocused without reopening. Tightening
    // this fully needs a tab-focus signal (EditorTabExtension) or a language param on decorate().
    private val openFiles = LinkedHashSet<String>() // absolute paths, in most-recently-opened order

    @Volatile
    private var activeIsJvmSource = false // gate read by decorate(); defaults off so nothing is colored until a JVM file is known

    private fun isJvmSource(path: String): Boolean =
        when (path.substringAfterLast('.', "").lowercase()) {
            "java", "kt", "kts" -> true
            else -> false
        }

    companion object {
        const val PLUGIN_ID = "org.appdevforall.rainbowonthego"
        const val TOOLTIP_TAG = "rainbow.info"

        // Upper bound on cached prefix checkpoints per document; clearing on overflow caps memory.
        private const val MAX_CHECKPOINTS = 4096

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

    // --- FileOpenExtension: observe-only, used purely to track the active file's language ---

    // Never take over opening a file — this plugin only watches which file is open.
    override fun canHandleFileOpen(file: File): Boolean = false

    override fun handleFileOpen(file: File): Boolean = false

    override fun onFileOpened(file: File) {
        synchronized(openFiles) {
            openFiles.remove(file.path) // move-to-end so the newest open is "active"
            openFiles.add(file.path)
            activeIsJvmSource = isJvmSource(file.path)
        }
    }

    override fun onFileClosed(file: File) {
        synchronized(openFiles) {
            openFiles.remove(file.path)
            activeIsJvmSource = openFiles.lastOrNull()?.let(::isJvmSource) ?: false
        }
    }

    override fun getFileTabMenuItems(file: File): List<FileTabMenuItem> = emptyList()

    // --- EditorDecorationProvider: the rainbow logic ---

    override fun decorate(text: CharSequence, start: Int, end: Int, isDark: Boolean): List<DecorationSpan> {
        // Only color Java/Kotlin; leave every other language untouched. See the active-file gate above.
        if (!activeIsJvmSource) return emptyList()
        if (end <= start) return emptyList()
        val palette = if (isDark) NIGHT_PALETTE else DAY_PALETTE

        // Recover the bracket-nesting depth and block-comment state at `start`, then lex [start, end)
        // emitting one colored span per bracket. The prefix state comes from a per-document checkpoint
        // cache (see startStateAt) instead of always re-lexing [0, start) — that rescan made decoration
        // O(N^2) over a large file. Both the prefix recovery and the emitting pass go through the SAME
        // `scan` function so they can never drift in how they treat newlines, strings, or comments.
        val state = startStateAt(text, start)

        val spans = ArrayList<DecorationSpan>()
        scan(text, start, end, state, palette, out = spans)

        // `state` now reflects the lexer position at `end`; remember it so a later region beginning at
        // or after `end` can resume from here rather than rescanning from offset 0.
        recordCheckpoint(text, end, state)
        return spans
    }

    // --- Prefix-state cache: avoid re-lexing [0, start) on every decorate() call ---
    //
    // decorate() needs the lexer state (nesting depth + block-comment flag) at `start`. Recomputing it
    // from offset 0 every call is O(start), i.e. ~O(N^2) to decorate a whole file as the editor walks
    // regions down it. Instead we memoize the state at the END of each decorated region and, for a new
    // region, resume from the nearest checkpoint at or before `start`. Sequential/scrolling decoration
    // then costs ~O(N) overall.
    //
    // The cache is keyed on the text instance plus its length; either changing drops all checkpoints (a
    // fresh document snapshot, or any length-changing edit). It is purely an optimization — a miss just
    // falls back to scanning from 0, so it can never produce wrong colors EXCEPT for an in-place,
    // same-length edit to the same CharSequence instance, which this fingerprint cannot detect. Validate
    // that case on-device; if it bites, add a content version/hash guard (Phase 3 / API support).
    private val cacheLock = Any()
    private var cacheText: CharSequence? = null
    private var cacheLen = -1
    private val checkpoints = TreeMap<Int, ScanState>() // offset -> lexer state valid at that offset

    /**
     * Returns the lexer [ScanState] valid at [start], resuming from the nearest cached checkpoint at or
     * before [start] when available and otherwise lexing from offset 0. Drops the cache when the
     * document fingerprint (instance + length) changes.
     */
    private fun startStateAt(text: CharSequence, start: Int): ScanState {
        var from = 0
        val state = ScanState()
        synchronized(cacheLock) {
            if (text !== cacheText || text.length != cacheLen) {
                cacheText = text
                cacheLen = text.length
                checkpoints.clear()
            }
            checkpoints.floorEntry(start)?.let { entry ->
                from = entry.key
                state.depth = entry.value.depth
                state.inBlock = entry.value.inBlock
            }
        }
        // The expensive forward lex runs outside the lock; it only reads `text` and the local `state`.
        if (from < start) scan(text, from, start, state, palette = null, out = null)
        return state
    }

    /** Records [state] as a checkpoint valid at [offset] for the current document. */
    private fun recordCheckpoint(text: CharSequence, offset: Int, state: ScanState) {
        synchronized(cacheLock) {
            if (text !== cacheText || text.length != cacheLen) return // document changed mid-flight; drop
            if (checkpoints.size >= MAX_CHECKPOINTS) checkpoints.clear() // bound memory; rebuild lazily
            checkpoints[offset] = ScanState(state.depth, state.inBlock) // store a copy, not the live state
        }
    }

    /**
     * State that must carry across the prefix/region passes (and, in principle, across regions):
     * bracket nesting [depth] and whether we are inside a `/* */` block comment ([inBlock]). String
     * and line-comment state never crosses a newline, so it stays local to [scan].
     */
    private class ScanState(var depth: Int = 0, var inBlock: Boolean = false)

    /**
     * Lexes [text] over `[from, to)` with Java/Kotlin token rules, updating [state]. When [out] is
     * non-null, appends one [DecorationSpan] per bracket colored by nesting depth; when null, the
     * pass only advances [state] (used to recover depth/block state for the prefix).
     *
     * Brackets inside double/single/backtick-quoted spans, `//` line comments, and `/* */` block
     * comments are skipped. Single-line constructs (line comments, ordinary strings) reset at every
     * newline — that newline reset is what a previous two-loop version was missing in the emitting
     * pass, which let a single `//` comment suppress coloring for the rest of a multi-line region.
     */
    private fun scan(
        text: CharSequence,
        from: Int,
        to: Int,
        state: ScanState,
        palette: IntArray?, // only needed when `out` is non-null (the emitting pass)
        out: MutableList<DecorationSpan>?,
    ) {
        var inString = false
        var quote = ' '
        var escaped = false
        var lineComment = false
        var i = from
        while (i < to) {
            val c = text[i]
            val c2 = if (i + 1 < to) text[i + 1] else ' '
            when {
                c == '\n' -> { lineComment = false; inString = false; escaped = false }
                lineComment -> { /* skip to end of line */ }
                state.inBlock -> if (c == '*' && c2 == '/') { state.inBlock = false; i++ }
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> inString = false
                }
                c == '/' && c2 == '/' -> { lineComment = true; i++ }
                c == '/' && c2 == '*' -> { state.inBlock = true; i++ }
                c == '"' || c == '\'' || c == '`' -> { inString = true; quote = c }
                isOpen(c) -> {
                    if (out != null) out.add(DecorationSpan(i, i + 1, palette!![state.depth % palette.size]))
                    state.depth++
                }
                isClose(c) -> {
                    if (state.depth > 0) state.depth--
                    if (out != null) out.add(DecorationSpan(i, i + 1, palette!![state.depth % palette.size]))
                }
            }
            i++
        }
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
            summary = "Colors brackets by nesting depth in Java/Kotlin — red, orange, yellow, green, blue, purple.",
            detail = """
                <p><b>RainbowOnTheGo</b> tints <b>()</b>, <b>[]</b> and <b>{}</b> by how deeply
                they nest in <b>Java and Kotlin</b> files, so a delimiter and its match share a color.</p>
                <ul>
                  <li>Six colors cycle red → orange → yellow → green → blue → purple.</li>
                  <li>Separate palettes are used for the light and dark editor themes.</li>
                  <li>Only Java and Kotlin are colored — other languages are left untouched.</li>
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
