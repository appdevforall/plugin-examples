package org.appdevforall.getaimodels.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeTooltipService
import kotlinx.coroutines.launch
import org.appdevforall.getaimodels.GetAiModelsPlugin
import org.appdevforall.getaimodels.GetAiModelsRuntime
import org.appdevforall.getaimodels.R
import org.appdevforall.getaimodels.catalog.ByteSize
import org.appdevforall.getaimodels.catalog.CatalogEntry
import org.appdevforall.getaimodels.catalog.CatalogLoader
import org.appdevforall.getaimodels.download.DownloadEvent
import org.appdevforall.getaimodels.download.ModelDownloader
import org.appdevforall.getaimodels.net.NetworkProbe
import org.appdevforall.getaimodels.net.NetworkStatus

/**
 * The "Get AI Models" bottom-sheet tab: the catalog list plus the connectivity and metered-consent
 * gates a download passes before it starts. Owns no download state - it reflects
 * [ModelDownloader.states] and its events, so reopening the tab mid-download shows the truth.
 */
class ModelCatalogFragment : Fragment() {

    private companion object {
        const val TOOLTIP_CATEGORY = "plugin_${GetAiModelsPlugin.PLUGIN_ID}"
    }

    private var recycler: RecyclerView? = null
    private var errorView: TextView? = null
    private var adapter: ModelRowAdapter? = null
    private var tooltipService: IdeTooltipService? = null
    private var entries: List<CatalogEntry> = emptyList()

    /** Routes inflation through the host so layouts track the IDE's light/dark setting. */
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(GetAiModelsPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_model_catalog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tooltipService = runCatching {
            PluginFragmentHelper.getServiceRegistry(GetAiModelsPlugin.PLUGIN_ID)
                ?.get(IdeTooltipService::class.java)
        }.getOrNull()

        recycler = view.findViewById(R.id.recyclerModels)
        errorView = view.findViewById(R.id.tvCatalogError)

        // A bundled asset, so a failure here is a broken build, not something worth retrying.
        entries = runCatching { CatalogLoader.load(requireContext().assets) }
            .onFailure { showCatalogError(it) }
            .getOrDefault(emptyList())
        if (entries.isEmpty()) return

        val rowAdapter = ModelRowAdapter(
            entries = entries,
            onDownload = ::requestDownload,
            onCancel = ::requestCancel,
            onVerify = ::requestVerify,
            onDelete = ::confirmDelete,
            onLongPress = ::showTooltip,
            rowTooltipTag = GetAiModelsPlugin.TOOLTIP_TAG_ROW,
            downloadTooltipTag = GetAiModelsPlugin.TOOLTIP_TAG_DOWNLOAD,
            verifyTooltipTag = GetAiModelsPlugin.TOOLTIP_TAG_VERIFY,
            cancelTooltipTag = GetAiModelsPlugin.TOOLTIP_TAG_CANCEL,
            deleteTooltipTag = GetAiModelsPlugin.TOOLTIP_TAG_DELETE
        )
        adapter = rowAdapter
        recycler?.layoutManager = LinearLayoutManager(requireContext())
        recycler?.adapter = rowAdapter

        observeDownloads()
    }

    private fun observeDownloads() {
        val downloader = GetAiModelsRuntime.downloader() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { downloader.states.collect { adapter?.submitStates(it) } }
                launch { downloader.events.collect(::onDownloadEvent) }
            }
        }
    }

    /** Connectivity and metered gates, then hand off to [ModelDownloader]. */
    private fun requestDownload(entry: CatalogEntry) {
        val downloader = GetAiModelsRuntime.downloader() ?: run {
            snack(getString(R.string.downloader_unavailable))
            return
        }

        when (NetworkProbe.current(requireContext())) {
            NetworkStatus.UNAVAILABLE -> snack(getString(R.string.no_network_message))

            NetworkStatus.METERED -> confirmMetered(entry) {
                startDownload(downloader, entry, allowOverMetered = true)
            }

            NetworkStatus.UNMETERED ->
                startDownload(downloader, entry, allowOverMetered = false)
        }
    }

    /** Stops an in-flight transfer. DownloadManager removes the partial file itself. */
    private fun requestCancel(entry: CatalogEntry) {
        GetAiModelsRuntime.downloader()?.cancel(entry.id)
    }

    /** Re-hashes a file already on disk; local bytes only, so no connectivity gate applies. */
    private fun requestVerify(entry: CatalogEntry, path: String) {
        val downloader = GetAiModelsRuntime.downloader() ?: run {
            snack(getString(R.string.downloader_unavailable))
            return
        }
        downloader.verifyExisting(entry, path)
    }

    /**
     * Confirms first: this removes gigabytes irreversibly and there is no trash. The dialog names the
     * exact file, and a row whose file has changed since verification gets a blunter warning.
     */
    private fun confirmDelete(entry: CatalogEntry, path: String, wasVerified: Boolean) {
        val message = if (wasVerified) {
            getString(
                R.string.delete_dialog_message,
                entry.name,
                ByteSize.format(entry.sizeBytes),
                path
            )
        } else {
            getString(R.string.delete_dialog_message_changed, entry.name, path)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.delete_dialog_confirm) { _, _ ->
                GetAiModelsRuntime.downloader()?.deleteDownloadedFile(entry, path)
                    ?: snack(getString(R.string.downloader_unavailable))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startDownload(
        downloader: ModelDownloader,
        entry: CatalogEntry,
        allowOverMetered: Boolean
    ) {
        downloader.enqueue(entry, allowOverMetered)
        snack(getString(R.string.snack_download_started, entry.name))
    }

    private fun confirmMetered(entry: CatalogEntry, onProceed: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.metered_dialog_title)
            .setMessage(
                getString(
                    R.string.metered_dialog_message,
                    entry.name,
                    ByteSize.format(entry.sizeBytes)
                )
            )
            .setPositiveButton(R.string.metered_dialog_proceed) { _, _ -> onProceed() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun onDownloadEvent(event: DownloadEvent) {
        when (event) {
            is DownloadEvent.Verified -> snack(
                getString(R.string.snack_verified, event.modelName)
            )

            is DownloadEvent.ChecksumFailed -> offerRedownload(event.entryId, event.modelName)

            // Nothing was deleted, so there is no re-download to offer - the row's Retry covers it.
            is DownloadEvent.VerifyFailed -> snack(
                getString(R.string.snack_verify_failed, event.modelName)
            )

            is DownloadEvent.Cancelled -> snack(
                getString(R.string.snack_download_cancelled, event.modelName)
            )

            is DownloadEvent.Deleted -> snack(getString(R.string.snack_deleted, event.modelName))

            is DownloadEvent.DeleteFailed -> snack(
                getString(R.string.snack_delete_failed, event.modelName)
            )

            is DownloadEvent.TransportFailed -> snack(
                getString(R.string.snack_download_failed, event.modelName, event.reason)
            )
        }
    }

    /** The checksum gate deleted the file; the Gherkin requires offering the download again. */
    private fun offerRedownload(entryId: String, modelName: String) {
        val entry = entries.firstOrNull { it.id == entryId } ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.checksum_dialog_title)
            .setMessage(getString(R.string.checksum_dialog_message, modelName))
            .setPositiveButton(R.string.checksum_dialog_retry) { _, _ -> requestDownload(entry) }
            .setNegativeButton(R.string.action_not_now, null)
            .show()
    }

    private fun showTooltip(anchor: View, tooltipTag: String) {
        val service = tooltipService ?: run {
            snack(getString(R.string.help_unavailable))
            return
        }
        // Explicit-category overload; the 2-arg one resolves elsewhere and renders "n/a".
        service.showTooltip(anchor, TOOLTIP_CATEGORY, tooltipTag)
    }

    /**
     * Snackbar, never Toast: a Toast from a plugin adds a window under a package id that is not a real
     * installed UID, throwing SecurityException and taking the IDE down with it.
     */
    private fun snack(message: String) {
        val root = view ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showCatalogError(cause: Throwable) {
        errorView?.visibility = View.VISIBLE
        recycler?.visibility = View.GONE
        Log.e("GetAiModels", "Could not read the bundled catalog", cause)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recycler?.adapter = null
        recycler = null
        errorView = null
        adapter = null
        tooltipService = null
    }
}
