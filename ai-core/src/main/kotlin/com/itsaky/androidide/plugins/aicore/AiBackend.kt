package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.services.LlmInferenceService

/**
 * LLM backends AI Core registers, pairing each backend id with the AgentSettings preference
 * value the AI Assistant stores for it. Single source of truth so nothing routes on hardcoded
 * backend strings.
 *
 * @property id backend id used for registration and [LlmInferenceService.LlmConfig]
 * @property preferenceValue value stored under [PREFERENCE_KEY] in [PREFERENCE_FILE]
 */
enum class AiBackend(val id: String, val preferenceValue: String) {
    LOCAL("local", "LOCAL_LLM"),
    GEMINI("gemini", "GEMINI");

    companion object {
        /** SharedPreferences file, owned by the AI Assistant plugin, holding the backend selection. */
        const val PREFERENCE_FILE = "AgentSettings"

        /** Key under which the selected backend is stored in [PREFERENCE_FILE]. */
        const val PREFERENCE_KEY = "ai_backend_preference"

        /** Sentinel [LlmInferenceService.LlmConfig.backendId] meaning "route to the user-selected backend". */
        const val AUTO = "auto"

        /**
         * Resolves the backend the user selected in settings.
         *
         * @param preferenceValue value read from [PREFERENCE_KEY], or null when unset
         * @return the matching backend, or [LOCAL] when the value is unset or unrecognized
         */
        fun fromPreference(preferenceValue: String?): AiBackend =
            entries.firstOrNull { it.preferenceValue == preferenceValue } ?: LOCAL
    }
}
