package org.appdevforall.templatemanagerplugin.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.templatemanagerplugin.R
import org.appdevforall.templatemanagerplugin.adapters.CgtFileAdapter
import org.appdevforall.templatemanagerplugin.adapters.TemplateCardAdapter
import org.appdevforall.templatemanagerplugin.models.CgtFileItem
import org.appdevforall.templatemanagerplugin.models.TemplateMetadata
import org.appdevforall.templatemanagerplugin.models.displayName
import org.appdevforall.templatemanagerplugin.models.primaryTemplate
import org.appdevforall.templatemanagerplugin.parsing.CgtTemplateReader
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import java.io.File

class TemplateManagerPluginFragment : Fragment() {

    companion object {
        private const val TAG = "TemplateManagerPlugin"
        private const val PLUGIN_ID = "org.appdevforall.templatemanagerplugin"
        private const val TEMPLATES_SUBDIR = "templates"
        private const val TOOLTIP_TAG = "org_appdevforall_templatemanagerplugin.overview"
        // Hardcoded on purpose: the host app holds MANAGE_EXTERNAL_STORAGE, and /sdcard is a
        // near-universal compatibility symlink to the primary shared storage on Android. This
        // deliberately avoids Environment/MediaStore rather than being an oversight.
        private val DOWNLOAD_DIR = File("/sdcard/Download")
    }

    private var recyclerView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var templateService: IdeTemplateService? = null
    private var environmentService: IdeEnvironmentService? = null
    private var tooltipService: IdeTooltipService? = null
    private var refreshJob: Job? = null

    private val items = mutableListOf<CgtFileItem>()
    private val adapter = CgtFileAdapter(
        items,
        onInstall = ::installTemplate,
        onUninstall = ::uninstallTemplate,
        onDetails = ::showDetails,
        onDelete = ::confirmDeleteDownloadFile,
        onViewTemplates = ::showTemplateList,
        onLongPress = ::showHelpTooltip
    )

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setupServices()
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.recyclerView)
        emptyView = view.findViewById(R.id.tvEmpty)

        recyclerView?.layoutManager = LinearLayoutManager(requireContext())
        recyclerView?.adapter = adapter

        refreshTemplates()
    }

    override fun onResume() {
        super.onResume()
        refreshTemplates()
    }

    /**
     * Re-scans the templates directory and /sdcard/Download and rebuilds the card list.
     * The directory listing + per-file zip parsing runs on a background dispatcher (a
     * Downloads folder can hold many/large .cgt bundles); results are applied on the main
     * thread. Any in-flight scan is cancelled first so rapid refreshes don't race.
     */
    private fun refreshTemplates() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { scanTemplates() }
            items.clear()
            items.addAll(scanned)
            adapter.notifyDataSetChanged()
            emptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** Blocking file/zip scan of both locations; call off the main thread. */
    private fun scanTemplates(): List<CgtFileItem> {
        // The host registers a plugin's templates as "plugin_<pluginId>_<originalName>"
        // (IdeTemplateServiceImpl.prefixedName), and unregisterTemplate() re-applies that
        // prefix. So the original name is only recoverable for files carrying THIS plugin's
        // prefix; the store also holds the IDE's bundled templates and other plugins'
        // templates, which don't.
        val prefix = "plugin_${PLUGIN_ID}_"

        val installedItems = templatesDirectory()
            ?.listFiles { f -> f.isFile && f.name.endsWith(".cgt", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                val unregisterName = if (file.name.startsWith(prefix)) {
                    file.name.removePrefix(prefix)
                } else {
                    Log.w(
                        TAG,
                        "Installed template '${file.name}' does not carry this plugin's prefix; " +
                            "it was not registered by this plugin, so uninstall won't apply to it."
                    )
                    file.name
                }
                runCatching { parseCgtFile(file, installed = true, unregisterName = unregisterName) }.getOrNull()
            }
            ?: emptyList()

        val downloadItems = DOWNLOAD_DIR
            .listFiles { f -> f.isFile && f.name.endsWith(".cgt", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching { parseCgtFile(file, installed = false, unregisterName = file.name) }.getOrNull()
            }
            ?: emptyList()

        return installedItems + downloadItems
    }

    private fun templatesDirectory(): File? {
        val ideHome = environmentService?.getIdeHomeDirectory() ?: return null
        return File(ideHome, TEMPLATES_SUBDIR)
    }

    /**
     * Parses a .cgt (which may bundle multiple templates) into a card item, or null if it
     * contains no template.json. Actual zip/JSON parsing lives in [CgtTemplateReader].
     */
    private fun parseCgtFile(file: File, installed: Boolean, unregisterName: String): CgtFileItem? {
        val templates = CgtTemplateReader.readTemplates(file.inputStream())
        if (templates.isEmpty()) return null
        return CgtFileItem(
            // Display the original filename: the host stores installed templates with a
            // "plugin_<pluginId>_" prefix, which unregisterName already strips off.
            file = file,
            name = unregisterName,
            templates = templates,
            installed = installed,
            unregisterName = unregisterName
        )
    }

    private fun installTemplate(item: CgtFileItem) {
        val service = templateService
        if (service == null) {
            Toast.makeText(context, "Template service is not available", Toast.LENGTH_SHORT).show()
            return
        }
        val success = service.registerTemplate(item.file)
        if (success) {
            item.file.delete()
        }
        service.reloadTemplates()
        refreshTemplates()
        Toast.makeText(
            context,
            if (success) "Installed ${item.displayName}" else "Failed to install ${item.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun uninstallTemplate(item: CgtFileItem) {
        val service = templateService
        if (service == null) {
            Toast.makeText(context, "Template service is not available", Toast.LENGTH_SHORT).show()
            return
        }

        // Restore a copy to Downloads BEFORE unregistering. Unregister deletes the
        // template-store copy, so if the restore fails we must not proceed — otherwise
        // the user's only copy would be lost.
        val restoredFile = File(DOWNLOAD_DIR, item.unregisterName)
        val restored = runCatching { item.file.copyTo(restoredFile, overwrite = true) }.isSuccess
        if (!restored) {
            Toast.makeText(
                context,
                "Failed to restore ${item.displayName} to Downloads",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val success = service.unregisterTemplate(item.unregisterName)
        if (!success) {
            restoredFile.delete()
        }

        service.reloadTemplates()
        refreshTemplates()
        Toast.makeText(
            context,
            if (success) "Uninstalled ${item.displayName}" else "Failed to uninstall ${item.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Shows the plugin's help tooltip (registered via DocumentationExtension) anchored to a card. */
    private fun showHelpTooltip(anchor: View) {
        val service = tooltipService
        if (service == null) {
            Toast.makeText(context, "Help is not available", Toast.LENGTH_SHORT).show()
            return
        }
        service.showTooltip(anchor, TOOLTIP_TAG)
    }

    private fun confirmDeleteDownloadFile(item: CgtFileItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${item.displayName}?")
            .setMessage("This permanently deletes the file from Downloads.")
            .setPositiveButton("Delete") { _, _ -> deleteDownloadFile(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDownloadFile(item: CgtFileItem) {
        val success = item.file.delete()
        refreshTemplates()
        Toast.makeText(
            context,
            if (success) "Deleted ${item.displayName}" else "Failed to delete ${item.displayName}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** File-level details for a single-template .cgt (multi-template files use [showTemplateList]). */
    private fun showDetails(item: CgtFileItem) {
        val primary = item.primaryTemplate
        val message = buildString {
            append("File: ${item.displayName}\n")
            append("Status: ${if (item.installed) "Installed" else "Not installed"}\n")
            append("Location: ${item.file.absolutePath}\n")
            append("Version: ${primary.version}\n\n")
            append(primary.description)
            if (primary.optionalTags.isNotEmpty()) {
                append("\n\nOptional parameters:")
                primary.optionalTags.forEach { append("\n  • $it") }
            }
        }
        showScrollableDialog(primary.name.ifBlank { item.displayName }, message)
    }

    /** Sub-screen: one card per template bundled inside a multi-template .cgt. */
    private fun showTemplateList(item: CgtFileItem) {
        // Use the plugin-themed inflater context so item_template.xml resolves plugin
        // resources (chip drawable, theme attrs) instead of the host activity's theme.
        val pluginContext = layoutInflater.context
        val recycler = RecyclerView(pluginContext).apply {
            layoutManager = LinearLayoutManager(pluginContext)
            adapter = TemplateCardAdapter(item.templates) { template -> showTemplateDetails(template) }
            clipToPadding = false
            val vertical = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, vertical, 0, vertical)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Templates in ${item.displayName}")
            .setView(recycler)
            .setPositiveButton("Close", null)
            .show()
    }

    /** Details for a single template selected from the [showTemplateList] sub-screen. */
    private fun showTemplateDetails(template: TemplateMetadata) {
        val message = buildString {
            append("Version: ${template.version}\n\n")
            append(template.description)
            if (template.optionalTags.isNotEmpty()) {
                append("\n\nOptional parameters:")
                template.optionalTags.forEach { append("\n  • $it") }
            }
        }
        showScrollableDialog(template.name.ifBlank { "(unnamed)" }, message)
    }

    private fun showScrollableDialog(title: String, message: String) {
        val padding = (24 * resources.displayMetrics.density).toInt()
        val textView = TextView(requireContext()).apply {
            text = message
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, 0)
        }
        val scrollView = ScrollView(requireContext()).apply {
            addView(textView)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    fun setupServices() {
        runCatching {
            val serviceRegistry = PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            templateService = serviceRegistry?.get(IdeTemplateService::class.java)
            environmentService = serviceRegistry?.get(IdeEnvironmentService::class.java)
            tooltipService = serviceRegistry?.get(IdeTooltipService::class.java)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView?.adapter = null
        recyclerView = null
        emptyView = null
    }
}
