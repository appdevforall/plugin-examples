package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import kotlinx.coroutines.CompletableDeferred

/**
 * Manages user approval for tool execution.
 */
class ToolApprovalManager {
    private val TAG = "ToolApprovalManager"
    
    // Tools that don't require approval
    private val autoApprovedTools = setOf(
        "read_file",
        "list_files",
        "search_project",
        "get_current_datetime"
    )
    
    // Tools approved for this session
    private val sessionApprovedTools = mutableSetOf<String>()
    
    // Pending approval request
    private var pendingApproval: CompletableDeferred<ApprovalResult>? = null
    private var currentApprovalRequest: ApprovalRequest? = null
    
    /**
     * Check if a tool needs approval and request it if needed.
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
        
        Log.d(TAG, "Requesting approval for $toolName")
        
        // Wait for user decision (will be completed by submitApproval)
        val result = pendingApproval!!.await()
        
        currentApprovalRequest = null
        pendingApproval = null
        
        return when (result) {
            ApprovalResult.APPROVED_ONCE -> {
                ApprovalResponse(approved = true)
            }
            ApprovalResult.APPROVED_FOR_SESSION -> {
                sessionApprovedTools.add(toolName)
                ApprovalResponse(approved = true)
            }
            ApprovalResult.DENIED -> {
                ApprovalResponse(
                    approved = false,
                    denialMessage = "User denied permission to execute $toolName"
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
        pendingApproval?.complete(result)
    }
    
    /**
     * Clear all session-approved tools.
     */
    fun clearSessionApprovals() {
        sessionApprovedTools.clear()
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
