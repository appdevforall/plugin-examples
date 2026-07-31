# Minification is disabled for this plugin (see build.gradle.kts), so these rules are only a
# safety net if it is ever turned on.

# The IDE loads the plugin entry point by name from AndroidManifest's plugin.main_class.
-keep class org.appdevforall.getaimodels.GetAiModelsPlugin { *; }

# Fragments contributed to the IDE are instantiated by the host.
-keep class org.appdevforall.getaimodels.ui.** { *; }

# plugin-api is provided by the IDE at runtime (compileOnly).
-dontwarn com.itsaky.androidide.plugins.**
