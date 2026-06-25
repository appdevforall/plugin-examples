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
            append(description)
            append("\n\n")
            append(getString(R.string.approval_args))
            append("\n")
            append(argsText)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.approval_title, toolName))
            .setMessage(message)
            .setPositiveButton(R.string.approval_approve_once) { _, _ ->
                onApprovalDecision?.invoke(ApprovalResult.APPROVED_ONCE)
                dismiss()
            }
            .setNeutralButton(R.string.approval_approve_session) { _, _ ->
                onApprovalDecision?.invoke(ApprovalResult.APPROVED_FOR_SESSION)
                dismiss()
            }
            .setNegativeButton(R.string.approval_deny) { _, _ ->
                onApprovalDecision?.invoke(ApprovalResult.DENIED)
                dismiss()
            }
            .setOnCancelListener {
                onApprovalDecision?.invoke(ApprovalResult.DENIED)
            }
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        onApprovalDecision = null
    }
}
