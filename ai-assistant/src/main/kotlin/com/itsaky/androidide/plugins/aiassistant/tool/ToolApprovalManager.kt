package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages user approval for tool execution.
 * Tools that modify system state require explicit user approval.
 */
class ToolApprovalManager {
    private val TAG = "ToolApprovalManager"

    // Approval request timeout: 5 minutes
    private val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

    // Tools that don't require approval (read-only and safe)
    private val autoApprovedTools = setOf(
        "read_file",
        "list_files",
        "search_project",
        "open_file",
        "read_build_output",
        "gradle_sync",
        "generate_from_template",
        "get_current_datetime"
    )

    // Tools approved for this session
    private val sessionApprovedTools = mutableSetOf<String>()

    // Pending approval request
    private var pendingApproval: CompletableDeferred<ApprovalResult>? = null
    private var currentApprovalRequest: ApprovalRequest? = null
    
    /**
     * Check if a tool needs approval and request it if needed.
     * @return ApprovalResponse with approved status and optional denial message
     */
    suspend fun ensureApproved(
        toolName: String,
        handler: ToolHandler,
        args: Map<String, Any?>
    ): ApprovalResponse {
        // Check if tool doesn't require approval
        if (!handler.requiresApproval || autoApprovedTools.contains(toolName)) {
            return ApprovalResponse(approved = true)
        }

        // Check if already approved for this session
        if (sessionApprovedTools.contains(toolName)) {
            return ApprovalResponse(approved = true)
        }

        // Request user approval
        val request = ApprovalRequest(
            toolName = toolName,
            args = args,
            description = handler.description
        )

        currentApprovalRequest = request
        pendingApproval = CompletableDeferred()

        Log.d(TAG, "Requesting approval for $toolName (timeout: ${APPROVAL_TIMEOUT_MS}ms)")

        // Wait for user decision with timeout
        val result = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            pendingApproval!!.await()
        }

        currentApprovalRequest = null
        pendingApproval = null

        // Handle timeout or decision
        return when (result) {
            ApprovalResult.APPROVED_ONCE -> {
                Log.d(TAG, "Approval granted (once) for $toolName")
                ApprovalResponse(approved = true)
            }
            ApprovalResult.APPROVED_FOR_SESSION -> {
                Log.d(TAG, "Approval granted (session) for $toolName")
                sessionApprovedTools.add(toolName)
                ApprovalResponse(approved = true)
            }
            ApprovalResult.DENIED -> {
                Log.d(TAG, "Approval denied for $toolName")
                ApprovalResponse(
                    approved = false,
                    denialMessage = "User denied permission to execute $toolName"
                )
            }
            null -> {
                // Timeout occurred
                Log.w(TAG, "Approval request timed out after ${APPROVAL_TIMEOUT_MS}ms for $toolName")
                ApprovalResponse(
                    approved = false,
                    denialMessage = "Approval request timed out (no response within 5 minutes). Please try again."
                )
            }
        }
    }
    
    /**
     * Get the current pending approval request, if any.
     */
    fun getCurrentApprovalRequest(): ApprovalRequest? {
        return currentApprovalRequest
    }
    
    /**
     * Submit user's approval decision.
     */
    fun submitApproval(result: ApprovalResult) {
        if (pendingApproval?.isActive == true) {
            pendingApproval?.complete(result)
            Log.d(TAG, "Approval decision submitted: $result")
        }
    }

    /**
     * Cancel the pending approval request.
     * Useful when user wants to stop waiting for approval.
     */
    fun cancelPendingApproval() {
        if (pendingApproval?.isActive == true) {
            pendingApproval?.complete(ApprovalResult.DENIED)
            Log.d(TAG, "Pending approval cancelled by user")
        }
    }

    /**
     * Check if there's a pending approval request.
     */
    fun hasPendingApproval(): Boolean {
        return currentApprovalRequest != null && pendingApproval?.isActive == true
    }

    /**
     * Clear all session-approved tools.
     */
    fun clearSessionApprovals() {
        sessionApprovedTools.clear()
        Log.d(TAG, "Session approvals cleared")
    }
}

/**
 * Result of an approval request.
 */
data class ApprovalResponse(
    val approved: Boolean,
    val denialMessage: String? = null
)

/**
 * Pending approval request.
 */
data class ApprovalRequest(
    val toolName: String,
    val args: Map<String, Any?>,
    val description: String
)

/**
 * User's approval decision.
 */
enum class ApprovalResult {
    APPROVED_ONCE,
    APPROVED_FOR_SESSION,
    DENIED
}
