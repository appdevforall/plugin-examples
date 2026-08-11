package com.itsaky.androidide.plugins.aiagentlocal.preferences

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext

/**
 * This plugin's own settings store, and the one-time move of its settings out of AI Core's.
 *
 * These values describe *this* backend — which model file to run, its checksum — so they belong in
 * this plugin's storage. They used to live in the agent plugin's preferences, which meant this
 * backend could not be configured, or even report itself available, unless that plugin happened to
 * be installed and to have published its own context first.
 */
internal object LocalLlmPreferences {

    /** This plugin's preferences file. Namespaced to this plugin by the host. */
    private const val FILE = "LocalLlmSettings"

    const val KEY_MODEL_PATH = "local_llm_model_path"
    const val KEY_MODEL_NAME = "local_llm_model_name"
    const val KEY_MODEL_SHA256 = "local_llm_model_sha256"
    const val KEY_SIMPLE_PROMPT = "use_simple_local_prompt"

    /** Set once [migrateIfNeeded] has run, so a value changed since is never overwritten. */
    private const val KEY_MIGRATED = "migrated_from_agent_settings"

    /** Everything this backend owns; anything else in the old shared file is not ours to take. */
    private val OWNED_KEYS = listOf(
        KEY_MODEL_PATH, KEY_MODEL_NAME, KEY_MODEL_SHA256, KEY_SIMPLE_PROMPT,
    )

    /** The host's own store, from before any of this was a plugin. */
    private const val HOST_LEGACY_FILE = "LlamaPrefs"

    /** This plugin's id before it was renamed from `ai-backend-local`; see [OWN_LEGACY_FILE]. */
    private const val LEGACY_PLUGIN_ID = "com.itsaky.androidide.plugins.ailocal"

    /**
     * This same file, under the plugin id this plugin had before the rename. The host namespaces a
     * plugin's preferences by plugin id, so changing the id points [of] at an empty file and a
     * configured device would look unconfigured.
     */
    private val OWN_LEGACY_FILE = "plugin_${LEGACY_PLUGIN_ID}_$FILE"

    /**
     * Files that may still hold this backend's values, newest first.
     *
     * Every one is read because plugins load in parallel with no ordering: an older file may not
     * have been folded into a newer one yet, so stopping at the first that holds anything can miss
     * a value the next one still has.
     */
    private val LEGACY_FILES = listOf(
        OWN_LEGACY_FILE,
        legacyFileName(LEGACY_PLUGIN_ID),
        legacyFileName("com.itsaky.androidide.plugins.aicore"),
        legacyFileName("com.itsaky.androidide.plugins.aiassistant"),
        HOST_LEGACY_FILE,
    )

    /** Name the host gives a plugin's preferences file; see `PluginContextImpl`. */
    private fun legacyFileName(pluginId: String) = "plugin_${pluginId}_AgentSettings"

    /**
     * This plugin's preferences.
     *
     * @param context this plugin's own context — never another plugin's
     */
    fun of(context: PluginContext): SharedPreferences =
        context.getPluginSharedPreferences(FILE)

    /**
     * Copies this backend's settings out of every store in [LEGACY_FILES], once.
     *
     * Copies rather than moves: the old file is not this plugin's to prune, and leaving it intact
     * means downgrading still finds its settings. Call before anything reads a setting.
     *
     * @param context this plugin's own context
     */
    fun migrateIfNeeded(context: PluginContext) {
        val prefs = of(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        try {
            for (fileName in LEGACY_FILES) {
                val legacy = context.getAppSharedPreferences(fileName) ?: continue
                if (copyOwnedValues(legacy, prefs)) {
                    context.logger.info("LocalLlmPreferences: adopted settings from $fileName")
                }
            }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        } catch (e: Exception) {
            // Not fatal, and deliberately not marked migrated: a fresh install has nothing to
            // copy, and a failure here should get another chance rather than stranding the user
            // with an unconfigured backend.
            context.logger.error("LocalLlmPreferences: could not migrate settings", e)
        }
    }

    /**
     * @return true when at least one value was taken from [legacy]
     */
    private fun copyOwnedValues(legacy: SharedPreferences, into: SharedPreferences): Boolean {
        val editor = into.edit()
        var copied = 0
        for (key in OWNED_KEYS) {
            // A value set here already post-dates the old one, so it must not be overwritten.
            if (!legacy.contains(key) || into.contains(key)) continue
            when (val value = legacy.all[key]) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Float -> editor.putFloat(key, value)
                else -> continue
            }
            copied++
        }
        editor.apply()
        return copied > 0
    }
}
