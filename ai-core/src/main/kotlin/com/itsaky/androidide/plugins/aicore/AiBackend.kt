package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.services.LlmInferenceService

/**
 * How AI Core maps the AI Assistant backend *setting* onto a registered backend *id*.
 *
 * Deliberately not an enum: backends are contributed by separate plugins now, so AI Core cannot
 * know the full set at compile time. A new backend plugin stores its own id as the preference
 * value and needs no change here; the map below only translates the two values that predate that
 * convention and are already on users' devices.
 */
object AiBackend {

    /** SharedPreferences file, owned by the AI Assistant plugin, holding the backend selection. */
    const val PREFERENCE_FILE = "AgentSettings"

    /** Key under which the selected backend is stored in [PREFERENCE_FILE]. */
    const val PREFERENCE_KEY = "ai_backend_preference"

    /** Sentinel [LlmInferenceService.LlmConfig.backendId] meaning "route to the user-selected backend". */
    const val AUTO = "auto"

    /** Backend preferred when nothing is stored, matching the pre-split default. */
    const val DEFAULT_ID = "local"

    /**
     * Preference values written before the value *was* the backend id. Additive-only: removing an
     * entry silently strands every device that stored it.
     */
    private val LEGACY_PREFERENCE_IDS = mapOf(
        "LOCAL_LLM" to "local",
        "GEMINI" to "gemini",
    )

    /**
     * Resolves the backend id the user selected in settings.
     *
     * An unrecognized value is returned as-is rather than replaced by [DEFAULT_ID]: it is most
     * likely a backend plugin AI Core has never heard of, and the caller's availability fallback
     * handles the case where nothing is registered under it.
     *
     * @param preferenceValue value read from [PREFERENCE_KEY], or null when unset
     * @return the backend id to route to; [DEFAULT_ID] when unset
     */
    fun idFromPreference(preferenceValue: String?): String {
        val value = preferenceValue?.trim()
        if (value.isNullOrEmpty()) return DEFAULT_ID
        return LEGACY_PREFERENCE_IDS[value] ?: value
    }
}
