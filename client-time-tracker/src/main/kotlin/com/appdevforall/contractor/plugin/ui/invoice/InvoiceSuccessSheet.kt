package com.appdevforall.contractor.plugin.ui.invoice

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import com.appdevforall.contractor.plugin.ContractorPlugin
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.RowInvoiceFileBinding
import com.appdevforall.contractor.plugin.databinding.SheetInvoiceSuccessBinding
import com.appdevforall.contractor.plugin.domain.model.MoneyFormat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import java.io.File

class InvoiceSuccessSheet : BottomSheetDialogFragment() {

    private var _binding: SheetInvoiceSuccessBinding? = null
    private val binding get() = _binding!!

    override fun getTheme(): Int = R.style.PluginBottomSheetDialog

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        return PluginFragmentHelper.getPluginInflater(
            ContractorPlugin.PLUGIN_ID,
            super.onGetLayoutInflater(savedInstanceState)
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetInvoiceSuccessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val result = requireResult()

        binding.dialogInvoiceNumber.text = result.invoiceNumber
        binding.dialogTotal.text = MoneyFormat.format(result.total, result.currency)
        binding.dialogPeriod.text = "${result.periodStartIsoDate}  to  ${result.periodEndIsoDate}"

        binding.filesContainer.removeAllViews()
        val rowInflater = LayoutInflater.from(binding.root.context)
        for (path in result.outFiles) {
            val file = File(path)
            val rowBinding = RowInvoiceFileBinding.inflate(rowInflater, binding.filesContainer, false)
            rowBinding.fileName.text = file.name
            rowBinding.root.setOnClickListener { openFile(file) }
            binding.filesContainer.addView(rowBinding.root)
        }

        binding.btnDone.setOnClickListener { dismiss() }
        binding.btnShareAll.setOnClickListener { shareAll(result) }
    }

    private fun openFile(file: File) {
        val activity = requireActivity()
        val uri = runCatching {
            FileProvider.getUriForFile(activity, hostFileProviderAuthority(activity), file)
        }.getOrNull() ?: run {
            showOpenError()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(file.extension))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { showOpenError() }
    }

    private fun shareAll(result: GenerationResult) {
        val activity = requireActivity()
        if (result.outFiles.isEmpty()) {
            showShareError()
            return
        }
        val authority = hostFileProviderAuthority(activity)
        val uris = ArrayList<Uri>()
        for (path in result.outFiles) {
            runCatching { FileProvider.getUriForFile(activity, authority, File(path)) }
                .onSuccess { uris += it }
        }
        if (uris.isEmpty()) {
            showShareError()
            return
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching { startActivity(Intent.createChooser(intent, result.invoiceNumber)) }
            .onFailure { showShareError() }
    }

    private fun showOpenError() {
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.error_open_unavailable_title)
            .setMessage(R.string.error_open_unavailable_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showShareError() {
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.error_share_unavailable_title)
            .setMessage(R.string.error_share_unavailable_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Use the HOST app's registered FileProvider, not one declared in the plugin's manifest.
     * The plugin is loaded via DexClassLoader, never installed as a real Android package, so
     * any <provider> tag in its AndroidManifest.xml is ignored by the system. The host
     * declares `${applicationId}.providers.fileprovider` with `root-path` whitelisted, which
     * routes correctly and is visible to other apps (ES File Explorer, Drive, etc.).
     */
    private fun hostFileProviderAuthority(activity: android.content.Context): String =
        "${activity.packageName}.providers.fileprovider"

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "pdf" -> "application/pdf"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "csv" -> "text/csv"
        else -> "*/*"
    }

    private fun requireResult(): GenerationResult {
        val args = requireArguments()
        return getParcelableCompat(args, ARG_RESULT)
            ?: error("InvoiceSuccessSheet requires a $ARG_RESULT argument")
    }

    @Suppress("DEPRECATION")
    private fun getParcelableCompat(bundle: Bundle, key: String): GenerationResult? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(key, GenerationResult::class.java)
        } else {
            bundle.getParcelable(key) as? GenerationResult
        }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RESULT = "result"

        fun newInstance(result: GenerationResult): InvoiceSuccessSheet =
            InvoiceSuccessSheet().apply {
                arguments = Bundle().apply { putParcelable(ARG_RESULT, result) }
            }
    }
}
