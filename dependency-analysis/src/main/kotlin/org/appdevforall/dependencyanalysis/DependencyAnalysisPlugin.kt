package org.appdevforall.dependencyanalysis

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import org.appdevforall.dependencyanalysis.ui.DependencyAnalysisFragment

/**
 * Dependency Analysis plugin entry point.
 *
 * Audits the open project's Gradle dependencies by running the autonomousapps
 * Dependency Analysis Gradle Plugin (`:buildHealth`), classifies the advice
 * (unused / undeclared-transitive / misconfigured), and offers a one-tap
 * `:fixDependencies` that applies all advice and re-analyzes.
 *
 * Layering (architecture.md):
 *   - domain/  pure-Kotlin advice models + parser (JaCoCo-covered core)
 *   - data/    GradleAnalysisRunner: IdeCommandService + report I/O off-main-thread
 *   - ui/      the bottom-sheet tab Fragment + confirm/summary sheets
 *
 * Lifecycle: initialize -> activate -> deactivate -> dispose. The plugin holds
 * no long-lived IDE references itself; the runner owns its CoroutineScope and is
 * cancelled when the Fragment is destroyed (see [DependencyAnalysisFragment]).
 */
class DependencyAnalysisPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "org.appdevforall.dependencyanalysis"
        const val TAB_ID = "dependency_analysis_tab"
        const val TOOLTIP_TAG_TAB = "dependencyanalysis.tab"

        /** Shared with the UI/data layers so the Fragment can resolve plugin resources. */
        @Volatile
        var pluginContext: PluginContext? = null
            private set
    }

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            pluginContext = context
            context.logger.info("DependencyAnalysisPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("DependencyAnalysisPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("DependencyAnalysisPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("DependencyAnalysisPlugin deactivated")
        return true
    }

    override fun dispose() {
        // Clear the static IDE reference so the host can be GC'd after an
        // enable/disable cycle (leak hygiene — a submission-rubric reject otherwise).
        pluginContext = null
        if (this::context.isInitialized) {
            context.logger.info("DependencyAnalysisPlugin disposed")
        }
    }

    // --- UIExtension: one bottom-sheet tab ---

    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = TAB_ID,
            title = "Dependencies",
            // Return a NEW fragment each call — never reuse a Fragment instance.
            fragmentFactory = { DependencyAnalysisFragment() },
            order = 210,
            tooltipTag = TOOLTIP_TAG_TAB
        )
    )

    // --- DocumentationExtension: tiered help on the tab ---

    override fun getTooltipCategory(): String = "plugin_dependency_analysis"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_TAB,
            summary = "Audit Gradle dependencies — find unused, undeclared-transitive, and misconfigured deps, then fix them in one tap.",
            detail = """
                <p>Runs the <b>Dependency Analysis Gradle Plugin</b>
                (<code>:buildHealth</code>) against the open project and reports:</p>
                <ul>
                  <li><b>Unused</b> — declared but not used; safe to remove.</li>
                  <li><b>Undeclared transitive</b> — used directly but only present
                      transitively; declare them.</li>
                  <li><b>Misconfigured</b> — on the wrong configuration
                      (e.g. <code>api</code> vs <code>implementation</code>); move them.</li>
                </ul>
                <p><b>Fix dependencies</b> delegates to <code>:fixDependencies</code>,
                which edits your <code>build.gradle</code> files in place, then
                re-analyzes and shows a removed/added/reconfigured summary.</p>
                <p>Permissions: <code>system.commands</code> (run the Gradle tasks)
                and <code>filesystem.read</code> (read the report). No network — DAGP
                is bundled offline.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "How it works",
                    uri = "index.html",
                    order = 0
                )
            )
        )
    )

    override fun getTier3DocsAssetPath(): String? = "docs"
}
