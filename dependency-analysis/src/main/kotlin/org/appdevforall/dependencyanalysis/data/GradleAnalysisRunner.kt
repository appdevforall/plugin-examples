package org.appdevforall.dependencyanalysis.data

import kotlinx.coroutines.flow.Flow
import org.appdevforall.dependencyanalysis.domain.AnalysisResult
import org.appdevforall.dependencyanalysis.domain.FixSummary

/**
 * Data-layer contract for running DAGP against the open project.
 *
 * Implementations drive [IdeCommandService] (`:buildHealth`, `:fixDependencies`),
 * generate the offline init script, and read/parse the report — all I/O off the
 * main thread (architecture.md). The UI layer depends only on this interface and
 * the domain models, never on IdeCommandService directly.
 *
 * Progress is surfaced as a [Flow] of [AnalysisProgress]; the suspend functions
 * return the typed terminal result. Collect the progress Flow concurrently with
 * the suspend call (DAGP's command output Flow is the only live channel).
 *
 * CONTRACT: the implement (data) agent must expose exactly this interface; the UI
 * agent codes against it.
 */
interface GradleAnalysisRunner {

    /** Live progress events for the in-flight Gradle run, for the running UI. */
    val progress: Flow<AnalysisProgress>

    /**
     * Run `:buildHealth` (with the bundled-DAGP init script) and parse the
     * aggregated report into a classified [AnalysisResult].
     *
     * Suspends until the run completes. Collect [progress] concurrently for
     * live output. Cancellable via coroutine cancellation (which also cancels
     * the underlying Gradle command).
     *
     * @return an [AnalysisOutcome] describing success, setup-needed, failure, or cancellation.
     */
    suspend fun analyze(): AnalysisOutcome

    /**
     * Apply all advice by running `:fixDependencies`, then re-run `:buildHealth`
     * and diff against [before] to produce the post-apply [FixSummary].
     *
     * @param before the analysis the user confirmed applying (for the diff)
     * @return a [FixOutcome] describing success (with summary), failure, or cancellation.
     */
    suspend fun applyFixes(before: AnalysisResult): FixOutcome

    /** Cancel any in-flight Gradle command started by this runner. */
    fun cancel()
}

/** Live progress events emitted while a Gradle command runs. */
sealed interface AnalysisProgress {
    /** A line of standard output from Gradle. */
    data class Output(val line: String) : AnalysisProgress
    /** A line of standard error from Gradle. */
    data class Error(val line: String) : AnalysisProgress
    /** The command finished with this exit code. */
    data class Finished(val exitCode: Int) : AnalysisProgress
}

/** Terminal outcome of [GradleAnalysisRunner.analyze]. */
sealed interface AnalysisOutcome {
    /** Analysis completed; [result] holds the classified advice. */
    data class Success(val result: AnalysisResult) : AnalysisOutcome
    /** The project has no analyzable module / DAGP could not be applied — the setup-needed UI. */
    data object SetupNeeded : AnalysisOutcome
    /** No project is open. */
    data object NoProject : AnalysisOutcome
    /** Gradle failed; [message] is a user-facing reason. */
    data class Failure(val message: String) : AnalysisOutcome
    /** The run was cancelled. */
    data object Cancelled : AnalysisOutcome
}

/** Terminal outcome of [GradleAnalysisRunner.applyFixes]. */
sealed interface FixOutcome {
    /** Fixes applied; [summary] holds removed/added/reconfigured counts + re-analysis. */
    data class Success(val summary: FixSummary) : FixOutcome
    /** Gradle failed during fix or re-analysis; [message] is a user-facing reason. */
    data class Failure(val message: String) : FixOutcome
    /** The fix run was cancelled. */
    data object Cancelled : FixOutcome
}
