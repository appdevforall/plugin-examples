package com.itsaky.androidide.plugins.aicore.backends

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

    /**
     * The backend to act on given what is installed, for every caller that has to answer that
     * question: the settings selector, the chat's status line, and its availability check.
     *
     * They must agree, or one launch can route to one backend while the label names another. The
     * order is deliberate — the stored choice first, then [DEFAULT_ID], and only then whatever came
     * first, which is registration or sort order and so effectively arbitrary.
     *
     * @param storedId the persisted selection, or null when nothing has been chosen
     * @param installedIds ids of the backends currently registered
     * @return the id to act on, or null when nothing is installed
     */
    fun preferredId(storedId: String?, installedIds: Collection<String>): String? =
        storedId?.takeIf { it in installedIds }
            ?: DEFAULT_ID.takeIf { it in installedIds }
            ?: installedIds.firstOrNull()
}
