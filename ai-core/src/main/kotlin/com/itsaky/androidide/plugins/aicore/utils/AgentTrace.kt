package com.itsaky.androidide.plugins.aicore.utils

import android.util.Log
import com.itsaky.androidide.plugins.aicore.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * One log stream for a whole agent run: prompt, model turns, parsing, guards, approval, result.
 * Every line shares one tag, a per-run id, elapsed ms and a sequence number (`adb logcat -s
 * AIAgentTrace:*`). Runs never overlap, so the current run is object state, not a parameter.
 */
object AgentTrace {

    const val TAG = "AIAgentTrace"

    /** Cap on any previewed value; enough to recognise a snippet, too short to be a dump. */
    const val PREVIEW_CHARS = 120

    /**
     * Whether previewed content (prompt, code snippets, model replies) reaches logcat: debug only.
     * The structured head of each line is what a trace is read for and always logs; a release build
     * has no reason to write the user's source into a log it does not own.
     */
    private val CONTENT_LOGGING = BuildConfig.DEBUG

    @Volatile
    private var runId: String = "-"

    @Volatile
    private var runStartMs: Long = 0L

    private val runCounter = AtomicInteger()
    private val sequence = AtomicInteger()

    /**
     * Starts a new traced run and logs the prompt that opened it.
     * @param backend the backend id serving this run.
     * @param prompt the user's message.
     * @param contextFiles how many context files were attached.
     * @return the new run id (also used implicitly by every later call).
     */
    fun beginRun(backend: String, prompt: String, contextFiles: Int): String {
        runId = "r${runCounter.incrementAndGet()}"
        runStartMs = System.currentTimeMillis()
        sequence.set(0)
        stage(
            "PROMPT",
            "backend=$backend chars=${prompt.length} contextFiles=$contextFiles",
            preview(prompt),
        )
        return runId
    }

    /**
     * Closes the current run.
     * @param outcome how it ended (a loop stop reason, "cancelled", or "error").
     * @param turns model turns executed, when known.
     */
    fun endRun(outcome: String, turns: Int? = null) {
        stage("DONE", "outcome=$outcome" + (turns?.let { " turns=$it" } ?: ""))
        runId = "-"
    }

    /**
     * Logs a milestone in the run at INFO — the lines you want when following the flow.
     * @param stage short uppercase phase name (PROMPT, LLM, TOOL, APPROVAL, EXEC, …).
     * @param detail structured `key=value` facts.
     * @param preview optional free text, already previewed by the caller.
     */
    fun stage(stage: String, detail: String, preview: String? = null) {
        Log.i(TAG, line(stage, detail, preview))
    }

    /**
     * Logs a supporting fact at DEBUG — filtered out of a normal `-s AIAgentTrace:I` read.
     * @param stage short uppercase phase name.
     * @param detail structured `key=value` facts.
     * @param preview optional free text, already previewed by the caller.
     */
    fun detail(stage: String, detail: String, preview: String? = null) {
        Log.d(TAG, line(stage, detail, preview))
    }

    /**
     * Logs a rejected or failed step at WARN. A guard refusing to act is normal operation, not an
     * error, but it is the thing you go looking for when a tool "did nothing".
     * @param stage short uppercase phase name.
     * @param detail structured `key=value` facts.
     * @param reason the refusal or failure message.
     */
    fun refusal(stage: String, detail: String, reason: String) {
        Log.w(TAG, line(stage, detail, preview(reason)))
    }

    /**
     * Flattens and truncates a value for logging.
     * @param value any argument, prompt, or response text.
     * @param limit maximum characters to keep.
     * @return a single-line, length-capped rendering, quoted, or `null` for a null value.
     */
    fun preview(value: Any?, limit: Int = PREVIEW_CHARS): String {
        if (value == null) return "null"
        val flat = value.toString().replace("\n", "⏎").replace("\r", "")
        return if (flat.length <= limit) "\"$flat\""
        else "\"${flat.take(limit)}…\"(${flat.length} chars)"
    }

    /**
     * Renders tool-call arguments as `key=preview` pairs, so a call is readable in one line
     * without its snippets swamping the log.
     * @param args the call arguments.
     * @return the rendered argument list.
     */
    fun previewArgs(args: Map<String, Any?>): String =
        args.entries.joinToString(" ") { "${it.key}=${preview(it.value, 60)}" }

    private fun line(stage: String, detail: String, preview: String?): String {
        val elapsed = if (runStartMs == 0L) 0 else System.currentTimeMillis() - runStartMs
        val head = "[$runId +${elapsed}ms #${sequence.incrementAndGet()}] $stage | $detail"
        return if (preview == null || !CONTENT_LOGGING) head else "$head | $preview"
    }
}
