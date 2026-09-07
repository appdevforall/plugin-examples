package org.appdevforall.getaimodels

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import org.appdevforall.getaimodels.download.ModelDownloader
import org.appdevforall.getaimodels.download.ModelFileGate
import org.appdevforall.getaimodels.download.VerifiedModelStore
import org.appdevforall.getaimodels.ui.ModelCatalogFragment

/**
 * Adds one editor bottom-sheet tab listing a bundled catalog of small GGUF model files, and
 * downloads them to /sdcard/Download behind a SHA-256 gate. Download only - running a model belongs
 * to the llama.cpp AI plugin, and the device's RAM is never checked.
 */
class GetAiModelsPlugin : IPlugin, UIExtension, DocumentationExtension {

    companion object {
        const val PLUGIN_ID = "org.appdevforall.getaimodels"
        const val TAB_ID = "get_ai_models_bottom_tab"

        /** Plugin-private SharedPreferences holding which entries have passed the SHA-256 gate. */
        private const val PREFS_VERIFIED = "verified_models"

        // Shared with the fragment/adapter; a tag with no entry below renders "n/a" on device.
        const val TOOLTIP_TAG_TAB = "getaimodels.tab"
        const val TOOLTIP_TAG_ROW = "getaimodels.row"
        const val TOOLTIP_TAG_DOWNLOAD = "getaimodels.download"
        const val TOOLTIP_TAG_VERIFY = "getaimodels.verify"
        const val TOOLTIP_TAG_CANCEL = "getaimodels.cancel"
        const val TOOLTIP_TAG_DELETE = "getaimodels.delete"
    }

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("GetAiModelsPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("GetAiModelsPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        return try {
            // Lambda, not an open instance: activate() is on the main thread and prefs touch disk.
            val store = VerifiedModelStore { context.getPluginSharedPreferences(PREFS_VERIFIED) }
            val gate = ModelFileGate(store, context.logger)
            // Application context, never an Activity: downloads outlive any one screen.
            val downloader = ModelDownloader(
                context.androidContext.applicationContext,
                context.logger,
                gate
            )
            GetAiModelsRuntime.attach(downloader)
            // Rebuilds row state from the persisted records; all disk work is off the main thread.
            downloader.restore()
            context.logger.info("GetAiModelsPlugin activated")
            true
        } catch (t: Throwable) {
            context.logger.error("GetAiModelsPlugin activation failed", t)
            false
        }
    }

    override fun deactivate(): Boolean {
        // In-flight transfers stay with DownloadManager but stop being tracked and verified by us.
        GetAiModelsRuntime.detach()
        context.logger.info("GetAiModelsPlugin deactivated")
        return true
    }

    override fun dispose() {
        GetAiModelsRuntime.detach()
        context.logger.info("GetAiModelsPlugin disposed")
    }

    /**
     * One bottom-sheet tab beside Build Output / App Logs. The factory returns a fresh fragment each
     * time the tab is shown; download state lives in [ModelDownloader], not in the fragment.
     */
    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = TAB_ID,
            title = "Get AI Models",
            fragmentFactory = { ModelCatalogFragment() },
            order = 210,
            tooltipTag = TOOLTIP_TAG_TAB
        )
    )

    // Must be exactly "plugin_<pluginId>", or the host's lookup misses and tooltips render "n/a".
    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_TAB,
            summary = "<b>Get AI Models</b><br>Download curated, small GGUF language-model files " +
                "to your Downloads folder.",
            detail = """
                <h3>Get AI Models</h3>
                <p>Lists a bundled catalog of open-licence <code>.gguf</code> model files. One row
                per file: a model at one quantization. Tap a row to read what it is good and bad
                at; tap <b>Download</b> to fetch it.</p>
                <h4>What happens on download</h4>
                <ol>
                    <li>Android's download manager fetches the file to
                    <code>/sdcard/Download</code>; progress appears in the system notification.</li>
                    <li>On a metered connection you are warned first and can cancel.</li>
                    <li>When the transfer finishes the file's SHA-256 is compared with the
                    catalog. A mismatch deletes the file and offers a re-download.</li>
                </ol>
                <h4>What it does not do</h4>
                <p>It does not run models. Loading a downloaded <code>.gguf</code> is the job of a
                separate AI plugin. The minimum-RAM figure is informational only - your device's
                memory is not checked, and nothing here tracks which models you already have.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Get AI Models guide",
                    uri = "index.html",
                    order = 0
                )
            )
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_ROW,
            summary = "One catalog row = one model file. Tap to expand for strengths, weaknesses, " +
                "publisher, and minimum RAM.",
            detail = """
                <h3>Catalog rows</h3>
                <p>Each row is a single downloadable file - a model at one quantization - so the
                same model can appear more than once. The compact line under the name reads
                <b>parameters &middot; quantization &middot; on-disk size</b>.</p>
                <p>Expanding a row adds:</p>
                <ul>
                    <li>a strengths-and-weaknesses paragraph written for this exact file;</li>
                    <li>the <b>publisher</b> of the GGUF, which is often a re-uploader rather than
                    the model's author;</li>
                    <li>the <b>minimum RAM</b> we would want to see before trying to run it -
                    informational only, never enforced;</li>
                    <li>licence and context-window size.</li>
                </ul>
                <p>Every file is pinned to a specific upload revision, so the catalog's checksum
                stays valid. <b>Check the licence line before using a model:</b> most are Apache-2.0,
                but the catalog also lists entries under restricted licences at the maintainer's
                request.</p>
            """.trimIndent()
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_DOWNLOAD,
            summary = "Downloads this file to /sdcard/Download, then verifies its SHA-256.",
            detail = """
                <h3>Download</h3>
                <p>Hands the file to Android's download manager, which shows progress in the system
                notification area. The button is then replaced by a <b>Cancel</b> control whose
                background fills as the file arrives, so the row still reports progress if you dismiss
                that notification. It becomes <b>Verifying</b> and finally <b>Downloaded</b>, which is
                remembered across restarts for as long as the file stays put.</p>
                <ul>
                    <li><b>No connection</b> - nothing starts, and you get a message saying so.</li>
                    <li><b>Metered connection</b> - you are warned with the download size and can
                    proceed or cancel. A download started on Wi-Fi will not silently continue over
                    cellular data; it pauses instead, and the row says so.</li>
                    <li><b>Checksum mismatch</b> - the file is deleted and the button becomes
                    <b>Retry</b>. A partial or tampered file is never kept.</li>
                </ul>
                <p>Files always land in <code>/sdcard/Download</code>; there is no other
                destination and no in-app progress bar.</p>
            """.trimIndent()
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_CANCEL,
            summary = "Shows how far the download has got, and stops it. The fill behind the label " +
                "is the progress.",
            detail = """
                <h3>While a download is running</h3>
                <p>The Download button is replaced by this control. Its background fills up as bytes
                arrive, the label shows the percentage, and the line beneath the model name gives the
                exact figures - for example <i>1.4 GB of 4.68 GB</i>.</p>
                <p>Tapping it <b>cancels</b> the download and discards the partial file. Nothing is
                left half-written in <code>/sdcard/Download</code>.</p>
                <h4>When it says Paused</h4>
                <ul>
                    <li><b>Waiting for Wi-Fi</b> - the download started on Wi-Fi and the device is now
                    on mobile data. It resumes by itself when Wi-Fi returns; it will not spend your
                    data allowance behind your back. Cancel and start again if you would rather
                    download it over mobile data.</li>
                    <li><b>No connection</b> - waiting for any usable network.</li>
                    <li><b>Retrying</b> - a transient error; Android will try again shortly.</li>
                </ul>
                <p>This row is deliberately readable on its own, because the system download
                notification can be dismissed - and once it is gone, this is the only place left that
                can tell you whether anything is still happening.</p>
            """.trimIndent()
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_DELETE,
            summary = "Permanently deletes this downloaded file from your device. Asks first.",
            detail = """
                <h3>Delete file</h3>
                <p>Removes the <code>.gguf</code> this plugin downloaded for that row, freeing the
                space. You are asked to confirm first, and the confirmation names the exact file and
                its full path so you can see what is about to go. There is no undo and no trash - but
                the row returns to <b>Download</b>, so you can always fetch it again.</p>
                <h4>What it will and will not touch</h4>
                <p>Delete only appears on a row this plugin has downloaded and recorded, and it only
                ever removes <i>that</i> file, at the exact path it was written to. A file you put in
                <code>/sdcard/Download</code> yourself is never a candidate, even if it happens to
                share the catalogued name.</p>
                <h4>Two things worth knowing</h4>
                <ul>
                    <li>If a model is currently <b>loaded by an AI plugin</b>, the row clears but the
                    disk space is not reclaimed until that plugin releases the file. Unload the model
                    first if you are deleting to free space.</li>
                    <li>The system Downloads list may keep showing the file until Android next scans
                    the folder. It is gone; only the listing is stale.</li>
                </ul>
                <p>On a row marked as changed, the file on disk is no longer the one that was
                verified - the confirmation says so, because in that case it may be something you put
                there deliberately.</p>
            """.trimIndent()
        ),
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_VERIFY,
            summary = "Re-computes this file's SHA-256 and compares it with the catalog. Nothing is " +
                "deleted.",
            detail = """
                <h3>Verify a file again</h3>
                <p>A verified download is remembered across restarts, and on start-up each remembered
                file is re-checked cheaply: is it still there, and is it still the size that was
                hashed? That catches a deleted or truncated file instantly and for free.</p>
                <p>What it cannot catch is a file swapped for a <i>different</i> file of exactly the
                same length. This action closes that gap by hashing the bytes again. It takes a few
                seconds to about half a minute depending on the file.</p>
                <ul>
                    <li><b>Match</b> - the row stays verified and the record is refreshed.</li>
                    <li><b>Mismatch</b> - the row reports the failure and offers a fresh download.
                    The file on disk is <b>left in place</b>: it was not written by this download, so
                    removing it is your call, not the plugin's.</li>
                </ul>
                <p>A row whose file has changed size shows <b>Verify</b> in place of Download until
                you re-check it, rather than claiming a checksum that no longer holds.</p>
            """.trimIndent()
        )
    )

    /** Tier 3 offline help: everything under src/main/assets/docs/, entry point index.html. */
    override fun getTier3DocsAssetPath(): String = "docs"

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("Installing GetAiModelsPlugin documentation")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("Removing GetAiModelsPlugin documentation")
    }
}
