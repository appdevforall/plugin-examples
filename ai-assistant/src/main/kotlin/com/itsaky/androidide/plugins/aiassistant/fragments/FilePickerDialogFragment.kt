package com.itsaky.androidide.plugins.aiassistant.fragments

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.PathGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dialog for picking project files to add as chat context.
 *
 * The dialog is created once and refreshed **in place** as the user navigates
 * or toggles selection. It never dismisses-and-recreates itself: doing so
 * detaches the fragment from its context, after which a follow-up
 * `requireContext()` throws `IllegalStateException: not attached to a context`
 * (previously crashed on the second "Toggle All").
 *
 * Navigation is confined to the start path supplied by the caller (the open
 * project root), so the picker can't be used to reach arbitrary files on the
 * device. All disk I/O runs off the main thread; see [computeListing].
 */
class FilePickerDialogFragment : DialogFragment() {

    private var onFilesSelected: ((List<File>) -> Unit)? = null
    private val selectedFiles = mutableSetOf<File>()

    /** Root the picker is confined to; navigation can never go above this. */
    private lateinit var rootDirectory: File
    private lateinit var currentDirectory: File

    // Reused across in-place refreshes so we never rebuild the Dialog/Fragment.
    private val rows = mutableListOf<FileRow>()
    private lateinit var listAdapter: FileRowAdapter
    private var alertDialog: AlertDialog? = null

    companion object {
        private const val ARG_START_PATH = "start_path"
        private const val PARENT_NAME = ".."

        fun newInstance(
            startPath: String? = null,
            onSelected: (List<File>) -> Unit
        ): FilePickerDialogFragment {
            return FilePickerDialogFragment().apply {
                // Default to the current project root when no path is given.
                arguments = Bundle().apply {
                    putString(ARG_START_PATH, startPath ?: PathGuard.projectRoot())
                }
                onFilesSelected = onSelected
            }
        }
    }

    /** One navigable entry: a directory to descend into or a selectable file. */
    private data class FileRow(
        val file: File,
        val displayName: String,
        val isDirectory: Boolean,
    )

    /** Result of a directory scan, computed off the main thread. */
    private data class Listing(val rows: List<FileRow>, val directory: File)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = ContextThemeWrapper(requireContext(), R.style.PluginTheme)
        listAdapter = FileRowAdapter(context)
        val listView = ListView(context).apply {
            adapter = listAdapter
            setOnItemClickListener { _, _, position, _ -> onItemClicked(position) }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.file_picker_title)
            .setView(listView)
            .setPositiveButton(R.string.file_picker_add_selected) { _, _ ->
                onFilesSelected?.invoke(selectedFiles.toList())
            }
            .setNegativeButton(android.R.string.cancel, null)
            // Wired in setOnShowListener so the click doesn't auto-dismiss the dialog.
            .setNeutralButton(R.string.file_picker_toggle_all, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                toggleAllInCurrentDirectory()
            }
        }
        alertDialog = dialog
        loadInitial()
        return dialog
    }

    /**
     * Resolve the confined root from the start path and load its listing.
     * The start path passed by the caller IS the confinement root — the picker
     * confines navigation to the open project and starts there.
     */
    private fun loadInitial() {
        val rootPath = arguments?.getString(ARG_START_PATH) ?: PathGuard.projectRoot()
        lifecycleScope.launch {
            val listing = withContext(Dispatchers.IO) {
                rootDirectory = File(rootPath).canonicalOrAbsolute()
                if (!rootDirectory.exists()) {
                    null
                } else {
                    currentDirectory = rootDirectory
                    computeListing(rootDirectory)
                }
            }
            if (listing == null) {
                alertDialog?.setTitle(getString(R.string.file_picker_error_not_found))
            } else {
                applyListing(listing)
            }
        }
    }

    /** Recompute the listing for [directory] off-thread and apply it in place. */
    private fun populate(directory: File) {
        lifecycleScope.launch {
            val listing = withContext(Dispatchers.IO) {
                currentDirectory = directory
                computeListing(directory)
            }
            applyListing(listing)
        }
    }

    /** Pure disk I/O — must run on a background dispatcher. */
    private fun computeListing(directory: File): Listing {
        val newRows = mutableListOf<FileRow>()

        // Offer ".." only while the parent is still inside the confined root.
        val parent = directory.parentFile
        if (parent != null && parent.exists() && isWithinRoot(parent) &&
            canonical(directory) != canonical(rootDirectory)
        ) {
            newRows.add(FileRow(parent, PARENT_NAME, isDirectory = true))
        }

        directory.listFiles()
            ?.filter { isWithinRoot(it) }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.forEach { file -> newRows.add(FileRow(file, file.name, file.isDirectory)) }

        return Listing(newRows, directory)
    }

    /** Swap in a freshly computed [listing] — main thread only. */
    private fun applyListing(listing: Listing) {
        rows.clear()
        rows.addAll(listing.rows)
        listAdapter.notifyDataSetChanged()
        alertDialog?.setTitle(titleFor(listing.directory))
    }

    private fun onItemClicked(position: Int) {
        val row = rows[position]
        if (row.isDirectory) {
            populate(row.file)
        } else {
            if (!selectedFiles.remove(row.file)) selectedFiles.add(row.file)
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun toggleAllInCurrentDirectory() {
        val currentFiles = rows.filter { !it.isDirectory }.map { it.file }
        if (currentFiles.isEmpty()) return
        if (selectedFiles.containsAll(currentFiles)) {
            selectedFiles.removeAll(currentFiles.toSet())
        } else {
            selectedFiles.addAll(currentFiles)
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun titleFor(dir: File): String =
        getString(R.string.file_picker_title_current, dir.name)

    /** True if [file] resolves (symlinks included) to a path at or below [rootDirectory]. */
    private fun isWithinRoot(file: File): Boolean = try {
        val root = rootDirectory.canonicalFile.toPath().normalize()
        file.canonicalFile.toPath().normalize().startsWith(root)
    } catch (e: Exception) {
        false
    }

    private fun canonical(f: File): String =
        try { f.canonicalPath } catch (e: Exception) { f.absolutePath }

    private fun File.canonicalOrAbsolute(): File =
        try { canonicalFile } catch (e: Exception) { absoluteFile }

    override fun onDestroyView() {
        super.onDestroyView()
        onFilesSelected = null
        alertDialog = null
    }

    /** Binds each [FileRow] to [R.layout.item_file_picker]; the check reflects selection. */
    private inner class FileRowAdapter(context: Context) :
        ArrayAdapter<FileRow>(context, 0, rows) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_file_picker, parent, false)
            val row = getItem(position) ?: return view

            view.findViewById<ImageView>(R.id.file_picker_row_icon)
                .setImageResource(if (row.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
            view.findViewById<TextView>(R.id.file_picker_row_name).text = row.displayName
            view.findViewById<ImageView>(R.id.file_picker_row_check).isVisible =
                selectedFiles.contains(row.file)
            return view
        }
    }
}
