package com.itsaky.androidide.plugins.aiassistant.fragments

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aiassistant.R
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalRequest
import com.itsaky.androidide.plugins.aiassistant.tool.ApprovalResult
import org.json.JSONObject

/**
 * Dialog for approving tool execution.
 */
class ApprovalDialogFragment : DialogFragment() {

    private var onApprovalDecision: ((ApprovalResult) -> Unit)? = null

    companion object {
        private const val ARG_TOOL_NAME = "tool_name"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_ARGS = "args"

        fun newInstance(
            request: ApprovalRequest,
            onDecision: (ApprovalResult) -> Unit
        ): ApprovalDialogFragment {
            return ApprovalDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TOOL_NAME, request.toolName)
                    putString(ARG_DESCRIPTION, request.description)
                    putString(ARG_ARGS, formatArgs(request.args))
                }
                onApprovalDecision = onDecision
            }
        }

        private fun formatArgs(args: Map<String, Any?>): String {
            if (args.isEmpty()) return "{}"
            return try {
                val json = JSONObject()
                args.forEach { (key, value) ->
                    json.put(key, value)
                }
                json.toString(2)
            } catch (e: Exception) {
                args.toString()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val toolName = arguments?.getString(ARG_TOOL_NAME) ?: "unknown"
        val description = arguments?.getString(ARG_DESCRIPTION) ?: ""
        val argsText = arguments?.getString(ARG_ARGS) ?: "{}"

        val message = buildString {
            append("🔒 Tool Approval Required\n\n")
            append(description)
            append("\n\n")
            append(getString(R.string.approval_args))
            append("\n")
            append(argsText)
            append("\n\n")
            append("Please choose an option below:")
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("⚠️ Confirm: $toolName")
            .setMessage(message)
            .setPositiveButton("✓ Run Now") { _, _ ->
                onApprovalDecision?.invoke(ApprovalResult.APPROVED_ONCE)
                dismiss()
            }
            .setNeutralButton("✓ Always Allow") { _, _ ->
                onApprovalDecision?.invoke(ApprovalResult.APPROVED_FOR_SESSION)
                dismiss()
            }
            .setNegativeButton("✗ Deny") { _, _ ->
                onApprovalDecision?.invoke(ApprovalResult.DENIED)
                dismiss()
            }
            .setOnCancelListener {
                onApprovalDecision?.invoke(ApprovalResult.DENIED)
            }
            .create()

        // Prevent accidental dismissal
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onApprovalDecision = null
    }
}
