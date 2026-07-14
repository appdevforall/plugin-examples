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
        val description: String,
        val startTime: Long = System.currentTimeMillis(),
        val elapsedMillis: Long = 0
    ) : AgentState() {
        val formattedProgress: String
            get() = "Step ${currentStepIndex + 1} of $totalSteps: $description"

        val formattedTiming: String
            get() {
                val elapsed = formatTime(elapsedMillis)
                // Estimate total time based on average time per step
                val estimatedTotal = if (currentStepIndex > 0) {
                    val avgPerStep = elapsedMillis / (currentStepIndex + 1)
                    avgPerStep * totalSteps
                } else {
                    elapsedMillis * totalSteps
                }
                val total = formatTime(estimatedTotal)
                return "($elapsed of $total)"
            }

        private fun formatTime(millis: Long): String {
            if (millis < 0) return "0.0s"
            val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(millis)
            val seconds = java.util.concurrent.TimeUnit.MILLISECONDS.toSeconds(millis) -
                         java.util.concurrent.TimeUnit.MINUTES.toSeconds(minutes)
            val remainingMillis = millis % 1000
            val totalSeconds = seconds + (remainingMillis / 1000.0)
            return if (minutes > 0) {
                String.format(java.util.Locale.US, "%dm %.1fs", minutes, totalSeconds)
            } else {
                String.format(java.util.Locale.US, "%.1fs", totalSeconds)
            }
        }
    }

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
