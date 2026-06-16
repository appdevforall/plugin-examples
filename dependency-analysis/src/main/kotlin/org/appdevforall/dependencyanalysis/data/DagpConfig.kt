package org.appdevforall.dependencyanalysis.data

/**
 * Named constants for the DAGP integration, kept in the data layer so the
 * version/paths are easy to bump and the domain layer stays free of them.
 *
 * Pinned to a fixed DAGP version so the report JSON schema we parse stays stable
 * and analysis is reproducible. The implement (data) agent uses these when
 * generating the init script and resolving the report file.
 */
object DagpConfig {

    /**
     * Hard-pinned DAGP version. Do NOT use a dynamic/latest range — the parsed
     * JSON schema must stay fixed. Bump deliberately, re-verifying the report
     * shape against the parser fixture.
     *
     * NOTE: DAGP 3.x requires the *user project* to run Gradle >= 8.11 / AGP >=
     * 8.10.0. That is a user-project compatibility gate to verify on device; if a
     * supported CoGo project runs older toolchains, pin an older DAGP line here.
     */
    const val DAGP_VERSION = "3.15.0"

    /** The aggregating task that produces the cross-module report. */
    const val BUILD_HEALTH_TASK = ":buildHealth"

    /** The apply-all remediation task (rewrites build files in place). */
    const val FIX_DEPENDENCIES_TASK = ":fixDependencies"

    /**
     * Path (relative to the project root) of DAGP's aggregated, machine-readable
     * report. This is the single artifact the parser consumes.
     */
    const val REPORT_RELATIVE_PATH = "build/reports/dependency-analysis/build-health-report.json"

    /**
     * Asset subdirectory holding the bundled offline Maven repo (DAGP + its
     * transitive jars), pointed at by the generated init script's
     * `initscript { repositories { maven { url = file://... } } }` so resolution
     * is fully offline. The implement (data) agent unpacks this to disk so the
     * `file://` URI resolves (assets stay compressed in the APK otherwise).
     */
    const val BUNDLED_REPO_ASSET_DIR = "dagp-repo"

    /**
     * The DAGP project-plugin class applied to the root project from the init
     * script via `gradle.rootProject { apply plugin: ... }`. (The settings-level
     * build-health plugin can't be applied from `gradle.rootProject`.)
     */
    const val DAGP_PLUGIN_CLASS = "com.autonomousapps.DependencyAnalysisPlugin"

    /** Generous timeout for a Gradle run: first-run configuration + analysis can take minutes. */
    const val GRADLE_TIMEOUT_MS = 10 * 60_000L
}
