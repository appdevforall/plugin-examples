package org.appdevforall.dependencyanalysis.domain

/**
 * Pure-Kotlin domain models for dependency advice.
 *
 * This file is the testable core: no Android imports, no I/O. Everything here
 * is derived from DAGP's aggregated `build-health-report.json` by [AdviceParser].
 * The UI layer renders these; the data layer produces the raw JSON these are
 * parsed from.
 *
 * CONTRACT: these class names and shapes are the API the UI and data agents must
 * conform to. Keep them stable.
 */

/** A single Gradle dependency coordinate, e.g. `com.squareup.okhttp3:okhttp:4.12.0`. */
data class DependencyCoordinate(
    /** `group:artifact`, e.g. `com.squareup.okhttp3:okhttp`. */
    val identifier: String,
    /** Resolved version, e.g. `4.12.0`. May be empty for project/flat coordinates. */
    val resolvedVersion: String = "",
) {
    /** Display form: `identifier:version` when a version is known, else `identifier`. */
    val display: String
        get() = if (resolvedVersion.isBlank()) identifier else "$identifier:$resolvedVersion"
}

/**
 * A dependency that is declared but not used by the project's code.
 * DAGP advice shape: `fromConfiguration` set, `toConfiguration == null`.
 */
data class UnusedDependency(
    val coordinate: DependencyCoordinate,
    /** The configuration it is currently (wrongly) declared on, e.g. `implementation`. */
    val fromConfiguration: String,
)

/**
 * A dependency used directly by code but only present transitively (undeclared).
 * DAGP advice shape: `fromConfiguration == null`, `toConfiguration` set.
 */
data class UndeclaredTransitive(
    val coordinate: DependencyCoordinate,
    /** The configuration it should be declared on, e.g. `implementation`. */
    val toConfiguration: String,
)

/**
 * A dependency declared on the wrong configuration (e.g. `api` -> `implementation`).
 * DAGP advice shape: both `fromConfiguration` and `toConfiguration` set and different.
 */
data class WrongConfiguration(
    val coordinate: DependencyCoordinate,
    val fromConfiguration: String,
    val toConfiguration: String,
)

/**
 * The full result of one `:buildHealth` analysis, classified by category and
 * grouped flat across all modules (per the apply-all v1 decision).
 */
data class AnalysisResult(
    val unused: List<UnusedDependency> = emptyList(),
    val undeclaredTransitive: List<UndeclaredTransitive> = emptyList(),
    val wrongConfiguration: List<WrongConfiguration> = emptyList(),
) {
    /** Total number of actionable advice items across all categories. */
    val totalAdviceCount: Int
        get() = unused.size + undeclaredTransitive.size + wrongConfiguration.size

    /** True when there is no actionable advice — the "all clear" UI state. */
    val isClean: Boolean
        get() = totalAdviceCount == 0

    companion object {
        /** An empty, clean result. */
        val EMPTY = AnalysisResult()
    }
}

/**
 * The post-apply summary, computed by diffing the pre-fix [AnalysisResult]
 * against a fresh re-analysis. Counts feed the post-apply summary sheet.
 *
 * `removed` = unused dependencies dropped; `added` = undeclared transitives now
 * declared; `reconfigured` = misconfigured dependencies moved.
 */
data class FixSummary(
    val removed: Int,
    val added: Int,
    val reconfigured: Int,
    /** The fresh analysis produced by the post-fix re-run of `:buildHealth`. */
    val reanalysis: AnalysisResult,
) {
    /** Total number of dependency changes the fix applied. */
    val totalChanged: Int
        get() = removed + added + reconfigured
}
