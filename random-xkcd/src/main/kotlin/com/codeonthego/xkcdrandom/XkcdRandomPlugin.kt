package com.codeonthego.xkcdrandom

import com.codeonthego.xkcdrandom.fragments.XkcdPanelFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension

/**
 * Random-xkcd demo plugin. Three goals:
 *   1. Show a random xkcd comic in the bottom-sheet "XKCD" tab.
 *   2. Demonstrate the tap surface — tap / double-tap / triple-tap.
 *   3. Be a small, readable "how to write a CoGo plugin" example.
 *
 * Reading order:
 *   - this file: lifecycle + tab registration + tooltip / docs wiring
 *   - [XkcdPanelFragment]: the bottom-sheet UI + tap handling
 *   - [com.codeonthego.xkcdrandom.net.XkcdApiClient]: HTTP, single file
 *   - [com.codeonthego.xkcdrandom.ui.TapCountClassifier]: the 1/2/3 tap
 *     state machine, with unit tests
 */
class XkcdRandomPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.codeonthego.xkcdrandom"
        const val TAB_ID = "xkcd_bottom_tab"
        const val TOOLTIP_TAG_TAB = "xkcd.tab"
    }

    override fun initialize(context: PluginContext): Boolean {
        // initialize() returns Boolean — the IDE skips activate() if this
        // returns false. Wrap in try/catch so a stray exception in our
        // setup can't crash the host IDE.
        return try {
            this.context = context
            context.logger.info("XkcdRandomPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("XkcdRandomPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("XkcdRandomPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("XkcdRandomPlugin deactivated")
        return true
    }

    override fun dispose() {
        context.logger.info("XkcdRandomPlugin disposed")
    }

    // --- UIExtension: register the bottom-sheet tab ---

    /**
     * Register one bottom-sheet tab. The IDE shows it next to the eight
     * built-in tabs (Build Output, App Logs, …) plus tabs from other
     * plugins. `order` controls our position among plugin tabs only.
     *
     * The fragmentFactory returns a *new* fragment each time the tab is
     * shown — never reuse a single Fragment instance, fragments have
     * lifecycle expectations the IDE manages.
     */
    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = TAB_ID,
            title = "XKCD",
            fragmentFactory = { XkcdPanelFragment() },
            order = 200
        )
    )

    // --- DocumentationExtension: three-tier tooltip on the tab ---
    //
    // CoGo's per-plugin help API. Long-pressing the bottom-sheet tab
    // shows Tier 1; the tooltip's "See More" button reveals Tier 2;
    // a button inside Tier 2 opens Tier 3 as a full HTML page.
    //
    //   Tier 1 = `summary`        (one-liner)
    //   Tier 2 = `detail`         (HTML paragraph)
    //   Tier 3 = `buttons[].uri`  (HTML page the IDE serves at
    //                              http://localhost:6174/plugin/<pluginId>/<uri>)
    //
    // The Tier 3 source lives under src/main/assets/docs/ and is
    // indexed at install time by the host's Tier3AssetWalker.

    override fun getTooltipCategory(): String = "plugin_xkcd"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_TAB,
            summary = "Random xkcd comic. Tap to roll a new one.",
            detail = """
                <p>This panel pulls a random comic from <b>xkcd.com</b>.</p>
                <ul>
                  <li><b>Tap</b> — fetch a new random comic.</li>
                  <li><b>Double-tap</b> — copy the comic's URL to the clipboard.</li>
                  <li><b>Triple-tap</b> — copy the comic image to the clipboard
                      (paste it into Messages or any image-aware app).</li>
                </ul>
                <p>Fetches use HTTPS only.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Code walkthrough",
                    uri = "index.html",  // resolves to plugin/<id>/index.html
                    order = 0
                )
            )
        )
    )

    /**
     * Subdirectory under src/main/assets/ that holds the Tier 3 walkthrough.
     * Every file under assets/docs/ is indexed by Tier3AssetWalker at
     * install time and served from
     *   http://localhost:6174/plugin/com.codeonthego.xkcdrandom/<file>
     *
     * Files reference each other with relative paths (e.g. css/walkthrough.css).
     */
    override fun getTier3DocsAssetPath(): String? = "docs"
}
