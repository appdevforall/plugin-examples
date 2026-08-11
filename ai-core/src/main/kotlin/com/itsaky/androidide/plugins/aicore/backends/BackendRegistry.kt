package com.itsaky.androidide.plugins.aicore.backends

import com.itsaky.androidide.plugins.aicore.AiBackend
import com.itsaky.androidide.plugins.aicore.AiCorePlugin
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices

/**
 * A backend the settings selector can offer.
 *
 * @param id the backend's own id; this is what gets persisted as the selection
 * @param displayName the backend's own label, shown in the selector
 * @param settingsFragmentClassName the settings pane the backend contributes, or null if it has none
 * @param classLoader the loader that can see [settingsFragmentClassName] — the backend plugin's own,
 *   since this plugin's loader cannot see classes packaged in another `.cgp`
 */
data class BackendOption(
    val id: String,
    val displayName: String,
    val settingsFragmentClassName: String?,
    val classLoader: ClassLoader?,
)

/**
 * The backends currently installed, as this plugin sees them.
 *
 * Everything here comes from AI Core's live registry, so no provider is named anywhere in this
 * plugin: a backend that is not installed is simply absent from [options], and one that ships in a
 * `.cgp` written by someone else appears without a line of code changing here.
 */
object BackendRegistry {

    private const val TAG = "BackendRegistry"

    /**
     * Every registered backend, sorted by label so the selector's order is stable across restarts
     * (the underlying registry is a hash map).
     *
     * @return the installed backends; empty when AI Core is absent or no backend registered
     */
    fun options(): List<BackendOption> {
        val backends = try {
            service()?.availableBackends.orEmpty()
        } catch (e: Exception) {
            logError("could not list the registered backends", e)
            emptyList()
        }
        return backends.mapNotNull(::describe).sortedBy { it.displayName }
    }

    /**
     * The loader that can instantiate [fragmentClassName], found by asking each registered backend
     * which pane it contributes. Used by [BackendFragmentFactory] to rebuild a restored pane.
     *
     * @return the owning backend plugin's loader, or null if no installed backend claims the class
     */
    fun classLoaderFor(fragmentClassName: String): ClassLoader? =
        options().firstOrNull { it.settingsFragmentClassName == fragmentClassName }?.classLoader

    /**
     * The backend the user selected, as a backend id.
     *
     * @return the stored id, migrating a legacy value in passing; null when nothing is stored
     */
    fun selectedId(): String? {
        val stored = prefs()?.getString(AiBackend.PREFERENCE_KEY, null)?.trim()
        if (stored.isNullOrEmpty()) return null
        // Migrates a value written before the stored form *was* the backend id.
        return AiBackend.idFromPreference(stored)
    }

    /**
     * Persists [backendId] as the selection. Stores the backend's own id, so a backend AI Core has
     * never heard of routes correctly without anything mapping it.
     */
    fun select(backendId: String) {
        prefs()?.edit()?.putString(AiBackend.PREFERENCE_KEY, backendId)?.apply()
    }

    /**
     * Describes one backend, tolerating a backend that throws from its own accessors: one bad
     * `.cgp` must cost the user that entry in the list, not the whole settings screen.
     */
    private fun describe(backend: LlmInferenceService.LlmBackend): BackendOption? = try {
        BackendOption(
            id = backend.id,
            displayName = backend.name,
            // Only a ConfigurableBackend names a pane; anything else simply has no settings screen.
            settingsFragmentClassName = (backend as? LlmInferenceService.ConfigurableBackend)
                ?.settingsFragmentClassName?.takeIf { it.isNotBlank() },
            // The backend object is constructed by its own plugin, so its loader is by construction
            // the one that can see the pane it names.
            classLoader = backend.javaClass.classLoader,
        )
    } catch (e: Throwable) {
        logError("a registered backend could not describe itself; omitting it", e)
        null
    }

    private fun service(): LlmInferenceService? =
        SharedServices.get(LlmInferenceService::class.java)

    private fun prefs() =
        AiCorePlugin.getContext()?.getPluginSharedPreferences(AiBackend.PREFERENCE_FILE)

    private fun logError(message: String, error: Throwable) {
        AiCorePlugin.getContext()?.logger?.error("$TAG: $message", error)
    }
}
