# AI Code Suggestions plugin for Code On The Go

**Requires the AI Core addon.** This plugin holds no model of its own. It asks
AI Core for every completion, so install AI Core first and select a model in
**AI Assistant → AI Settings**. Without it, this plugin shows no completions.

Inline **ghost-text** code completions. As you type, the plugin debounces, asks
an LLM for a completion at the cursor, and shows it as dimmed inline text via
`IdeEditorService.showInlineSuggestion()`.

> The dependency is at **runtime only** — there is no compile-time dependency
> on `ai-core`. This plugin resolves `LlmInferenceService` through the Code On
> The Go plugin manager (SharedServices), and binds lazily. Until AI Core is
> active it stays silent, then enables itself when the inference service
> appears.

## Architecture

```
┌─────────────────────────────┐
│  ai-code-suggestions (this) │  ← content-change listener, debounce, ghost text
└──────────────┬──────────────┘
               │ SharedServices (runtime) → LlmInferenceService
               ▼
┌─────────────────────────────┐
│  ai-core                    │  ← LLM inference (local llama.cpp / Gemini)
└─────────────────────────────┘
```

## Features

- Inline ghost-text completions while typing
- 800 ms debounce to reduce LLM load
- LRU cache to avoid redundant calls
- Language-aware prompts (Kotlin, Java, Python, …)
- Graceful degradation when `ai-core` isn't loaded yet (binds lazily)

## Permissions

This plugin declares no `plugin.permissions` of its own — it only registers a
content-change listener and renders ghost text. Reading the configured
backend/model and any network access happen inside **`ai-core`**'s process,
which declares those permissions.

The surrounding file content and cursor context are sent to the configured
backend to generate a completion — on-device for **Local**, or to Google over
HTTPS for **Gemini**. Choose the backend accordingly for sensitive code.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK or native toolchain.

```bash
cd plugins/AI-Code-Suggestions
./gradlew assemblePlugin          # release  -> build/plugin/ai-code-suggestions.cgp
./gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../../libs/`.

## Installation

1. Build and install **`ai-core` first** (see [`../ai-core/README.md`](../ai-core/README.md)).
2. Build this plugin, install `build/plugin/ai-code-suggestions.cgp` via
   Code On The Go's Plugin Manager, and restart the IDE.
3. Configure a model in **AI Assistant → AI Settings**.

## Key classes

- `CodeSuggestionsPlugin.kt` — lifecycle, content-change listener, debounce
- `SuggestionProvider.kt` — prompt building, LLM call, LRU cache

## License

GPL-3.0 — same as AndroidIDE / Code On The Go.
