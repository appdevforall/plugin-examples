package com.codeonthego.snippets.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.codeonthego.snippets.R
import com.codeonthego.snippets.SnippetEntry
import com.codeonthego.snippets.SnippetsConfig
import com.codeonthego.snippets.SnippetsConfigParser
import com.codeonthego.snippets.SnippetsPlugin
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

class SnippetManagerFragment : Fragment() {

    private var listContainer: LinearLayout? = null
    private var emptyView: TextView? = null
    private var snippetCount: TextView? = null
    private val snippets = mutableListOf<SnippetEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
        } catch (e: Exception) {
            Log.w("SnippetManager", "Services not yet available", e)
        }
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_snippet_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listContainer = view.findViewById(R.id.snippetList)
        emptyView = view.findViewById(R.id.emptyView)
        snippetCount = view.findViewById(R.id.snippetCount)

        view.findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener {
            showEditor(null, -1)
        }
    }

    override fun onResume() {
        super.onResume()
        loadAndRender()
    }

    private fun loadAndRender() {
        val file = SnippetsPlugin.instance?.getSnippetsFile() ?: return
        val config = SnippetsConfigParser.parse(file)
        snippets.clear()
        config?.snippets?.let { snippets.addAll(it) }
        renderList()
    }

    private fun renderList() {
        val container = listContainer ?: return
        container.removeAllViews()

        if (snippets.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            snippetCount?.text = ""
            return
        }

        emptyView?.visibility = View.GONE
        snippetCount?.text = "${snippets.size} snippets"

        snippets.forEachIndexed { index, entry ->
            container.addView(createSnippetCard(entry, index))
        }
    }

    private fun createSnippetCard(entry: SnippetEntry, index: Int): View {
        val inflater = layoutInflater
        val card = inflater.inflate(R.layout.item_snippet_card, listContainer, false)

        card.findViewById<TextView>(R.id.snippetPrefix).text = entry.prefix
        card.findViewById<TextView>(R.id.snippetDescription).text = entry.description
        card.findViewById<TextView>(R.id.tagLanguage).text = entry.language
        card.findViewById<TextView>(R.id.tagScope).text = entry.scope
        card.findViewById<TextView>(R.id.bodyPreview).text =
            entry.body.joinToString(" ").let { if (it.length > 60) it.take(60) + "..." else it }

        (card as? MaterialCardView)?.setOnClickListener { showEditor(entry, index) }

        card.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener {
            confirmDelete(index)
        }

        return card
    }

    private fun showEditor(existing: SnippetEntry?, index: Int) {
        val ctx = context ?: return
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_snippet_editor, null)

        val prefixInput = dialogView.findViewById<TextInputEditText>(R.id.inputPrefix)
        val descInput = dialogView.findViewById<TextInputEditText>(R.id.inputDescription)
        val langSpinner = dialogView.findViewById<Spinner>(R.id.spinnerLanguage)
        val scopeSpinner = dialogView.findViewById<Spinner>(R.id.spinnerScope)
        val bodyInput = dialogView.findViewById<TextInputEditText>(R.id.inputBody)

        val languages = arrayOf("java", "xml", "kotlin", "groovy", "json")
        val scopes = arrayOf("global", "local", "member", "top-level")
        langSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, languages)
        scopeSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, scopes)

        if (existing != null) {
            prefixInput.setText(existing.prefix)
            descInput.setText(existing.description)
            langSpinner.setSelection(languages.indexOf(existing.language).coerceAtLeast(0))
            scopeSpinner.setSelection(scopes.indexOf(existing.scope).coerceAtLeast(0))
            bodyInput.setText(existing.body.joinToString("\n"))
        } else {
            langSpinner.setSelection(0)
            scopeSpinner.setSelection(1)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing != null) "Edit Snippet" else "New Snippet")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val prefix = prefixInput.text?.toString()?.trim() ?: ""
                val desc = descInput.text?.toString()?.trim() ?: ""
                val lang = langSpinner.selectedItem?.toString() ?: ""
                val scope = scopeSpinner.selectedItem?.toString() ?: ""
                val body = bodyInput.text?.toString()?.split("\n") ?: emptyList()

                if (prefix.isEmpty() || desc.isEmpty() || lang.isEmpty() || scope.isEmpty()) {
                    Toast.makeText(ctx, "All fields are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val entry = SnippetEntry(
                    language = lang,
                    scope = scope,
                    prefix = prefix,
                    description = desc,
                    body = body
                )

                if (existing != null && index >= 0) {
                    snippets[index] = entry
                } else {
                    snippets.add(entry)
                }

                save()
                renderList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(index: Int) {
        val ctx = context ?: return
        val entry = snippets.getOrNull(index) ?: return

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete Snippet")
            .setMessage("Delete \"${entry.prefix}\"?")
            .setPositiveButton("Delete") { _, _ ->
                snippets.removeAt(index)
                save()
                renderList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val file = SnippetsPlugin.instance?.getSnippetsFile() ?: return
        try {
            SnippetsConfigParser.write(file, SnippetsConfig(snippets.toList()))
            SnippetsPlugin.instance?.invalidateCache()
            SnippetsPlugin.instance?.refreshRegistry()
            Toast.makeText(requireContext(), "Snippets saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SnippetManager", "Failed to save snippets", e)
            Toast.makeText(requireContext(), "Failed to save", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val PLUGIN_ID = "com.codeonthego.snippets"
    }
}