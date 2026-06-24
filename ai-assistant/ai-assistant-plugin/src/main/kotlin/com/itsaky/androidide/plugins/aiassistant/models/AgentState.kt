package com.itsaky.androidide.plugins.aiassistant.models

/**
 * Represents the current state of the AI agent.
 */
sealed class AgentState {
    /**
     * Agent is idle and ready to accept new messages.
     */
    object Idle : AgentState()

    /**
     * Agent is initializing (loading model, preparing context, etc.).
     */
    data class Initializing(val message: String) : AgentState()

    /**
     * Agent is thinking/reasoning about the user's request.
     */
    data class Thinking(val thought: String) : AgentState()

    /**
     * Agent is executing a tool or action.
     */
    data class Executing(
        val currentStepIndex: Int,
        val totalSteps: Int,
        val description: String
    ) : AgentState()

    /**
     * Agent is processing/generating a response.
     */
    data class Processing(val message: String) : AgentState()

    /**
     * A cancellation has been requested.
     */
    object Cancelling : AgentState()

    /**
     * Agent encountered an error.
     */
    data class Error(val message: String) : AgentState()
}
