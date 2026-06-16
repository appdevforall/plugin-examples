package org.appdevforall.dependencyanalysis.domain

/**
 * Parses DAGP's aggregated `build-health-report.json` into a classified
 * [AnalysisResult], and diffs two results into a [FixSummary].
 *
 * Pure Kotlin, no Android / no I/O — this is the JaCoCo-covered core. The data
 * layer reads the report file off the main thread and hands the raw JSON string
 * here; the UI layer renders the returned [AnalysisResult].
 *
 * JSON shape (DAGP ProjectAdvice / Advice / Coordinates): the report is a
 * top-level array of ProjectAdvice. Each `dependencyAdvice` entry is classified
 * purely by which of `fromConfiguration` / `toConfiguration` is null:
 *   - unused (remove):      from set, to == null
 *   - undeclared transitive (add): from == null, to set
 *   - wrong configuration (change): both set and different
 *
 * CONTRACT (the implement agent must keep these exact signatures):
 *   fun parse(json: String): AnalysisResult
 *   fun diff(before: AnalysisResult, after: AnalysisResult): FixSummary
 *
 * The implement agent owns the JSON-reading body. To keep this layer pure-Kotlin
 * and unit-testable on the JVM (no `org.json`/Android dependency on the unit-test
 * classpath), parse with a dependency-free reader (e.g. a small hand-rolled
 * tokenizer, or a kotlinx-serialization dependency added as testImplementation +
 * implementation). Do NOT introduce an Android-only JSON API here.
 */
object AdviceParser {

    /**
     * Parse the aggregated build-health report JSON into a classified result.
     *
     * @param json the contents of `build/reports/dependency-analysis/build-health-report.json`
     * @return classified advice; [AnalysisResult.EMPTY] for empty/no-advice reports.
     *
     * NOTE: stub — returns EMPTY so the module compiles. The implement agent
     * replaces this body with the real parser (fixture in the ticket research).
     */
    fun parse(json: String): AnalysisResult {
        // TODO(implement): parse `json` per the ProjectAdvice/Advice/Coordinates
        // shape and classify each advice by the from/to null pattern above.
        return AnalysisResult.EMPTY
    }

    /**
     * Diff a pre-fix analysis against a post-fix re-analysis to produce the
     * post-apply summary counts.
     *
     * @param before the analysis shown to the user before applying fixes
     * @param after  the fresh analysis after `:fixDependencies` re-ran `:buildHealth`
     * @return removed / added / reconfigured counts and the post-fix [AnalysisResult]
     *
     * The counts are how many items in each `before` category are no longer
     * present in `after` (i.e. were actually applied).
     */
    fun diff(before: AnalysisResult, after: AnalysisResult): FixSummary {
        val removed = before.unused.count { it !in after.unused }
        val added = before.undeclaredTransitive.count { it !in after.undeclaredTransitive }
        val reconfigured = before.wrongConfiguration.count { it !in after.wrongConfiguration }
        return FixSummary(
            removed = removed,
            added = added,
            reconfigured = reconfigured,
            reanalysis = after,
        )
    }
}
