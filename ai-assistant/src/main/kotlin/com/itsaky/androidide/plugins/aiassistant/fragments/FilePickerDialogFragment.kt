package com.itsaky.androidide.plugins.aiassistant.fragments

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * Dialog for picking files to add as context.
 */
class FilePickerDialogFragment : DialogFragment() {

    private var onFilesSelected: ((List<File>) -> Unit)? = null
    private var currentDirectory: File? = null
    private val selectedFiles = mutableSetOf<File>()

    companion object {
        private const val ARG_START_PATH = "start_path"

        fun newInstance(
            startPath: String? = null,
            onSelected: (List<File>) -> Unit
        ): FilePickerDialogFragment {
            return FilePickerDialogFragment().apply {
                arguments = Bundle().apply {
                    // Use provided path or default to storage root
                    val path = startPath ?: "/storage/emulated/0"
                    putString(ARG_START_PATH, path)
                }
                onFilesSelected = onSelected
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val startPath = arguments?.getString(ARG_START_PATH) ?: "/storage/emulated/0"
        currentDirectory = File(startPath)

        if (!currentDirectory!!.exists()) {
            // Fallback to root if start path doesn't exist
            currentDirectory = File("/storage/emulated/0")
        }

        if (!currentDirectory!!.exists()) {
            return MaterialAlertDialogBuilder(requireContext())
                .setTitle("Error")
                .setMessage("Storage directory not found")
                .setPositiveButton("OK") { _, _ -> dismiss() }
                .create()
        }

        return showDirectoryPicker(currentDirectory!!)
    }

    private fun showDirectoryPicker(directory: File): Dialog {
        val files = directory.listFiles()?.sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy { it.name }
        ) ?: emptyList()

        val items = mutableListOf<String>()
        val fileList = mutableListOf<File>()

        // Add parent directory option if not at root
        if (directory.parentFile != null && directory.parentFile!!.exists()) {
            items.add("📁 ..")
            fileList.add(directory.parentFile!!)
        }

        // Add directories and files
        files.forEach { file ->
            val prefix = if (file.isDirectory) "📁 " else "📄 "
            val suffix = if (selectedFiles.contains(file)) " ✓" else ""
            items.add("$prefix${file.name}$suffix")
            fileList.add(file)
        }

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            items
        )

        val listView = ListView(requireContext()).apply {
            this.adapter = adapter
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Files: ${directory.name}")
            .setView(listView)
            .setPositiveButton("Add Selected") { _, _ ->
                onFilesSelected?.invoke(selectedFiles.toList())
                dismiss()
            }
            .setNegativeButton("Cancel") { _, _ -> dismiss() }
            .setNeutralButton("Toggle All") { _, _ ->
                // Toggle all files in current directory
                val currentFiles = files.filter { it.isFile }
                if (selectedFiles.containsAll(currentFiles)) {
                    selectedFiles.removeAll(currentFiles.toSet())
                } else {
                    selectedFiles.addAll(currentFiles)
                }
                // Refresh dialog
                dismiss()
                showDirectoryPicker(directory).show()
            }
            .create().apply {
                listView.setOnItemClickListener { _, _, position, _ ->
                    val file = fileList[position]

                    if (file.isDirectory) {
                        // Navigate to directory
                        dismiss()
                        showDirectoryPicker(file).show()
                    } else {
                        // Toggle file selection
                        if (selectedFiles.contains(file)) {
                            selectedFiles.remove(file)
                        } else {
                            selectedFiles.add(file)
                        }
                        // Refresh display
                        items[position] = if (selectedFiles.contains(file)) {
                            "📄 ${file.name} ✓"
                        } else {
                            "📄 ${file.name}"
                        }
                        adapter.notifyDataSetChanged()
                    }
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onFilesSelected = null
    }
}
