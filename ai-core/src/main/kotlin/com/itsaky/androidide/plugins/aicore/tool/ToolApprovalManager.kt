package com.itsaky.androidide.plugins.aicore.tool

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages user approval for tool execution.
 * Tools that modify system state require explicit user approval.
 */
class ToolApprovalManager {
    private val TAG = "$LOG_PREFIX.ToolApprovalManager"

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

    /**
     * Tools that can never be blanket-approved for the session, however the user answers. Session
     * approval is keyed by tool name alone, so one tap would hand a small model unreviewed write
     * access to the whole project; a destructive edit is re-confirmed every time.
     */
    private val neverSessionApproved = setOf("edit_file")

    // Concurrent: written from the dialog's coroutine, read from the next tool call's thread.
    private val sessionApprovedTools: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Serialises the request-and-wait section of [ensureApproved]. There is one [pendingApproval]
     * slot and one dialog, so a second concurrent request would overwrite the first and strand its
     * caller until the timeout — and this class offers an API that looks safe to call anywhere.
     */
    private val requestLock = Mutex()

    // Volatile: completed from the main thread, published and cleared from a background coroutine.
    @Volatile
    private var pendingApproval: CompletableDeferred<ApprovalDecision>? = null

    private val _currentApprovalRequest = MutableStateFlow<ApprovalRequest?>(null)

    /**
     * The pending approval request, or null when none is outstanding. A flow rather than a
     * plain field so the UI is woken when a request appears, instead of polling for one.
     */
    val currentApprovalRequest: StateFlow<ApprovalRequest?> = _currentApprovalRequest.asStateFlow()


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
            AgentTrace.detail("APPROVAL", "$toolName skipped=auto-approved")
            return ApprovalResponse(approved = true)
        }

        // Check if already approved for this session
        if (sessionApprovedTools.contains(toolName)) {
            AgentTrace.detail("APPROVAL", "$toolName skipped=session-approved")
            return ApprovalResponse(approved = true)
        }

        // Locked from publishing to clearing, so a second caller waits instead of replacing it.
        val result = requestLock.withLock {
            if (sessionApprovedTools.contains(toolName)) {
                AgentTrace.detail("APPROVAL", "$toolName skipped=session-approved while queued")
                return ApprovalResponse(approved = true)
            }

            val request = ApprovalRequest(
                toolName = toolName,
                args = args,
                description = handler.description
            )

            val deferred = CompletableDeferred<ApprovalDecision>()
            pendingApproval = deferred
            _currentApprovalRequest.value = request

            Log.d(TAG, "Requesting approval for $toolName (timeout: ${APPROVAL_TIMEOUT_MS}ms)")
            AgentTrace.stage("APPROVAL", "$toolName dialog=shown", AgentTrace.previewArgs(args))

            try {
                // Wait for user decision with timeout
                withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() }
            } finally {
                _currentApprovalRequest.value = null
                pendingApproval = null
            }
        }
        AgentTrace.stage("APPROVAL", "$toolName choice=${result?.result ?: "TIMEOUT"}")

        // Handle timeout or decision
        return when (result?.result) {
            ApprovalResult.APPROVED_ONCE -> {
                Log.d(TAG, "Approval granted (once) for $toolName")
                ApprovalResponse(approved = true)
            }
            ApprovalResult.APPROVED_FOR_SESSION -> {
                if (toolName in neverSessionApproved) {
                    Log.d(TAG, "Approval granted (once; $toolName is never session-approved)")
                } else {
                    Log.d(TAG, "Approval granted (session) for $toolName")
                    sessionApprovedTools.add(toolName)
                }
                ApprovalResponse(approved = true)
            }
            ApprovalResult.CORRECTED -> {
                // Only this attempt is denied; a tool failure is the channel the loop re-feeds.
                val correction = result.correction?.trim().orEmpty()
                Log.d(TAG, "User requested a correction for $toolName")
                ApprovalResponse(
                    approved = false,
                    denialMessage = if (correction.isEmpty()) {
                        "User rejected this $toolName call and asked you to revise it."
                    } else {
                        "User rejected this $toolName call and asked you to revise it: " +
                            "\"$correction\". Apply that instruction and try again."
                    }
                )
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
     * Submit user's approval decision.
     * @param result what the user chose.
     * @param correction for [ApprovalResult.CORRECTED], the instruction to relay to the model.
     */
    fun submitApproval(result: ApprovalResult, correction: String? = null) {
        if (pendingApproval?.isActive == true) {
            pendingApproval?.complete(ApprovalDecision(result, correction))
            // Also cleared here: the deferred resumes only on the next dispatch, the dialog now.
            _currentApprovalRequest.value = null
            Log.d(TAG, "Approval decision submitted: $result")
        }
    }

    /** Cancels the pending approval request, for a user who stops waiting on it. */
    fun cancelPendingApproval() {
        if (pendingApproval?.isActive == true) {
            pendingApproval?.complete(ApprovalDecision(ApprovalResult.DENIED))
            Log.d(TAG, "Pending approval cancelled by user")
        }
        // Cleared here too: a cancelled run may never resume to do it, stranding the dialog.
        _currentApprovalRequest.value = null
    }

    /**
     * Check if there's a pending approval request.
     */
    fun hasPendingApproval(): Boolean {
        return _currentApprovalRequest.value != null && pendingApproval?.isActive == true
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

    /**
     * The user rejected this attempt but described what to do instead; the instruction rides
     * back to the model as a tool failure so it can retry.
     */
    CORRECTED,
    DENIED
}

/**
 * A decision plus the free text that only [ApprovalResult.CORRECTED] carries.
 * @property result what the user chose.
 * @property correction the user's instruction, when correcting.
 */
data class ApprovalDecision(
    val result: ApprovalResult,
    val correction: String? = null
)
