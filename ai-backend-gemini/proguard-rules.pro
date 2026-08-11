# AI Gemini Backend Plugin ProGuard Rules

# Keep plugin entry point
-keep public class com.itsaky.androidide.plugins.aigemini.GeminiPlugin {
    public <methods>;
}

# Keep the backend: ai-assistant resolves listModels reflectively across the
# plugin classloader boundary, because listModels is not on LlmBackend. Renaming
# or stripping it breaks the model picker and key verification silently.
-keep public class com.itsaky.androidide.plugins.aigemini.GeminiBackend {
    public <methods>;
}

# Keep plugin-api interfaces
-keep interface com.itsaky.androidide.plugins.** { *; }
