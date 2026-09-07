package com.appdevforall.pair.plugin.domain

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LoopbackSuppression {

    private val depth = AtomicInteger(0)

    // The editor updates its buffer synchronously inside replaceRange, but the resulting
    // DocumentChangeEvent is delivered to observers on a LATER (and variably-delayed) main-loop
    // tick. A counter that is released "after the next tick" therefore can't reliably cover the
    // echo. Instead we record the exact content we just applied per file; when the echo finally
    // arrives, the file's current content equals that recorded content, so the observer can
    // recognize and drop it regardless of timing. A genuine local edit changes the content away
    // from the recorded value and passes through.
    private val appliedContentByFile: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // Where the local caret was left after applying a remote edit/snapshot. The editor moves the
    // caret to the edit site, but that is NOT a local cursor move — broadcasting it would make our
    // marker jump to wherever the peer just typed. The cursor poll skips this position until the
    // user actually moves elsewhere.
    @Volatile private var autoCaret: Triple<String, Int, Int>? = null

    fun enter() {
        depth.incrementAndGet()
    }

    fun exit() {
        depth.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    fun isSuppressed(): Boolean = depth.get() > 0

    fun recordApplied(wirePath: String, content: String) {
        appliedContentByFile[wirePath] = content
    }

    fun isEchoOfApplied(wirePath: String, currentContent: String?): Boolean {
        if (currentContent == null) return false
        return appliedContentByFile[wirePath] == currentContent
    }

    fun clearApplied() {
        appliedContentByFile.clear()
        autoCaret = null
    }

    fun recordAutoCaret(wireFile: String, line: Int, column: Int) {
        autoCaret = Triple(wireFile, line, column)
    }

    fun isAutoCaret(wireFile: String, line: Int, column: Int): Boolean =
        autoCaret == Triple(wireFile, line, column)
}
