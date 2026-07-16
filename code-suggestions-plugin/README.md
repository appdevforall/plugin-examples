# Code Suggestions plugin for CodeOnTheGo

Inline **ghost-text** code completions. As you type, the plugin debounces, asks
an LLM for a completion at the cursor, and shows it as dimmed inline text via
`IdeEditorService.showInlineSuggestion()`.

> This plugin has **no compile-time dependency** on `ai-core`; it resolves the
> inference service at runtime through the CodeOnTheGo plugin manager
> (SharedServices). Install **`ai-core` first**, then this plugin, and configure
> a model in **AI Assistant → AI Settings**.

## Architecture

```
┌──────────────────────────┐
│  code-suggestions (this) │  ← content-change listener, debounce, ghost text
└────────────┬─────────────┘
             │ SharedServices (runtime) → LlmInferenceService
             ▼
┌──────────────────────────┐
│  ai-core                 │  ← LLM inference (local llama.cpp / Gemini)
└──────────────────────────┘
```

## Features

- Inline ghost-text completions while typing
- 800 ms debounce to reduce LLM load
- LRU cache to avoid redundant calls
- Language-aware prompts (Kotlin, Java, Python, …)
- Graceful degradation when `ai-core` isn't loaded yet (binds lazily)

## Permissions

Declared in `plugin.permissions`:

| Permission | Why |
|---|---|
| `ide.settings` | read the configured backend/model |
| `network.access` | reach the Gemini backend when that backend is selected |

The surrounding file content and cursor context are sent to the configured
backend to generate a completion — on-device for **Local**, or to Google over
HTTPS for **Gemini**. Choose the backend accordingly for sensitive code.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK or native toolchain.

```bash
cd code-suggestions-plugin
../gradlew assemblePlugin          # release  -> build/plugin/code-suggestions-plugin.cgp
../gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../libs/`.

## Installation

1. Build and install **`ai-core` first** (see [`../ai-core/README.md`](../ai-core/README.md)).
2. Build this plugin, install `build/plugin/code-suggestions-plugin.cgp` via
   CodeOnTheGo's Plugin Manager, and restart the IDE.
3. Configure a model in **AI Assistant → AI Settings**.

## Key classes

- `CodeSuggestionsPlugin.kt` — lifecycle, content-change listener, debounce
- `SuggestionProvider.kt` — prompt building, LLM call, LRU cache

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
