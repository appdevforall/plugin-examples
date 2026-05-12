package com.codeonthego.xkcdrandom

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext

/**
 * Random-xkcd demo plugin — entry point.
 *
 * Every plugin starts with an [IPlugin] class that the host loads via
 * `DexClassLoader`. The host reflectively instantiates this class by
 * the fully-qualified name in `plugin.main_class` (see manifest), then
 * drives the lifecycle:
 *
 *   initialize → activate → (use) → deactivate → dispose
 *
 * Subsequent commits opt this class into [UIExtension] (for a
 * bottom-sheet tab) and [DocumentationExtension] (for the in-IDE help
 * tooltip + Tier-3 walkthrough).
 */
class XkcdRandomPlugin : IPlugin {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.codeonthego.xkcdrandom"
    }

    override fun initialize(context: PluginContext): Boolean {
        // initialize() returns Boolean — the IDE skips activate() if this
        // returns false. Wrap in try/catch so a stray exception in our
        // setup can't crash the host IDE.
        return try {
            this.context = context
            context.logger.info("XkcdRandomPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("XkcdRandomPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("XkcdRandomPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("XkcdRandomPlugin deactivated")
        return true
    }

    override fun dispose() {
        context.logger.info("XkcdRandomPlugin disposed")
    }
}
