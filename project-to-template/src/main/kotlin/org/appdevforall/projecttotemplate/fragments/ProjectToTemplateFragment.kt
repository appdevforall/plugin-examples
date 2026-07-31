package org.appdevforall.projecttotemplate.fragments

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.extensions.IProject
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.projecttotemplate.R
import org.appdevforall.projecttotemplate.createTemplateBundle
import java.io.File

class ProjectToTemplateFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = "org.appdevforall.projecttotemplate"
        private const val TAG = "ProjectToTemplate"
    }

    private var currentProjectText: TextView? = null
    private var templateNameInput: TextInputEditText? = null
    private var dryRunCheckbox: MaterialCheckBox? = null
    private var skipCleanupCheckbox: MaterialCheckBox? = null
    private var convertButton: MaterialButton? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var logText: TextView? = null
    private var installButton: MaterialButton? = null

    private var isRunning = false
    private var pendingCgtFile: File? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentProjectText = view.findViewById(R.id.currentProjectText)
        templateNameInput = view.findViewById(R.id.templateNameInput)
        dryRunCheckbox = view.findViewById(R.id.dryRunCheckbox)
        skipCleanupCheckbox = view.findViewById(R.id.skipCleanupCheckbox)
        convertButton = view.findViewById(R.id.convertButton)
        progressBar = view.findViewById(R.id.progressBar)
        statusText = view.findViewById(R.id.statusText)
        logText = view.findViewById(R.id.logText)
        installButton = view.findViewById(R.id.installButton)

        convertButton?.setOnClickListener { onConvertClicked() }
        installButton?.setOnClickListener { onInstallClicked() }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateConvertButtonState()
        }
        templateNameInput?.addTextChangedListener(watcher)

        refreshCurrentProject()
    }

    override fun onResume() {
        super.onResume()
        refreshCurrentProject()
    }

    private fun currentProject(): IProject? =
        PluginFragmentHelper.getServiceRegistry(PLUGIN_ID)
            ?.get(IdeProjectService::class.java)
            ?.getCurrentProject()

    private fun refreshCurrentProject() {
        val project = currentProject()
        currentProjectText?.text = project?.name ?: "No project open"
        updateConvertButtonState()
    }

    private fun updateConvertButtonState() {
        val templateName = templateNameInput?.text?.toString()?.trim().orEmpty()
        convertButton?.isEnabled = !isRunning && currentProject() != null && templateName.isNotEmpty()
    }

    private fun onConvertClicked() {
        val project = currentProject()
        if (project == null) {
            showStatus("No project is currently open.", isError = true)
            return
        }
        val templateName = templateNameInput?.text?.toString()?.trim().orEmpty()
        if (templateName.isEmpty()) {
            showStatus("Enter a template name to write into template.json.", isError = true)
            return
        }
        val dryRun = dryRunCheckbox?.isChecked ?: false
        val skipCleanup = skipCleanupCheckbox?.isChecked ?: false
        val moduleName = project.getModules().firstOrNull { it.name == "app" }?.name
            ?: project.getModules().firstOrNull()?.name
            ?: "app"

        setRunning(true)
        logText?.text = ""
        statusText?.visibility = View.GONE
        pendingCgtFile = null
        installButton?.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    createTemplateBundle(
                        projectDir = project.rootDir,
                        module = moduleName,
                        templateName = templateName,
                        skipCleanup = skipCleanup,
                        dryRun = dryRun,
                        onLine = { line ->
                            appendLogLine(line)
                        },
                    )
                }
                val summary = buildString {
                    append("Modified ${result.report.changed.size}, ")
                    append("skipped ${result.report.skipped.size}, ")
                    append("removed ${result.report.removed.size}, ")
                    append("${result.report.flagged.size} to review.\n")
                    append("Bundle: ${result.outputDir}")
                    if (result.cgtFile != null) append("\n.cgt file: ${result.cgtFile}")
                }
                showStatus(summary, isError = false)

                if (result.cgtFile != null) {
                    pendingCgtFile = result.cgtFile
                    installButton?.isEnabled = true
                    installButton?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed", e)
                showStatus("Conversion failed: ${e.message}", isError = true)
            } finally {
                setRunning(false)
            }
        }
    }

    private fun onInstallClicked() {
        val cgtFile = pendingCgtFile ?: return
        installButton?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val installed = withContext(Dispatchers.IO) {
                runCatching {
                    val templateService = PluginFragmentHelper
                        .getServiceRegistry(PLUGIN_ID)
                        ?.get(IdeTemplateService::class.java)
                        ?: return@runCatching false
                    templateService.registerTemplate(cgtFile)
                }.onFailure { Log.e(TAG, "Install failed", it) }.getOrDefault(false)
            }
            appendLogLine(
                if (installed) "[OK]      Installed template: $cgtFile"
                else "[SKIP]    Could not install template: $cgtFile"
            )
        }
    }

    private fun appendLogLine(line: String) {
        activity?.runOnUiThread {
            val current = logText?.text
            logText?.text = if (current.isNullOrEmpty()) line else "$current\n$line"
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        statusText?.apply {
            text = message
            setTextColor(
                if (isError) Color.parseColor("#B3261E") else Color.parseColor("#0D6B42")
            )
            visibility = View.VISIBLE
        }
    }

    private fun setRunning(running: Boolean) {
        isRunning = running
        progressBar?.visibility = if (running) View.VISIBLE else View.GONE
        templateNameInput?.isEnabled = !running
        dryRunCheckbox?.isEnabled = !running
        skipCleanupCheckbox?.isEnabled = !running
        updateConvertButtonState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentProjectText = null
        templateNameInput = null
        dryRunCheckbox = null
        skipCleanupCheckbox = null
        convertButton = null
        progressBar = null
        statusText = null
        logText = null
        installButton = null
    }
}
