# AI Assistant plugin for CodeOnTheGo

An in-IDE AI chat assistant with tool calling. It provides the chat UI, settings,
and the agent tool-loop (read/write files, list/search the project, add
dependencies, run Gradle sync, etc.), and delegates all model inference to the
sibling [`ai-core`](../ai-core/) plugin.

> This is the **frontend** plugin. It has **no compile-time dependency** on
> `ai-core`; the two communicate at runtime through the CodeOnTheGo plugin
> manager (SharedServices). Install **`ai-core` first**, then `ai-assistant`.

## Architecture

```
┌──────────────────────────┐
│  ai-assistant  (this)    │  ← Chat UI, settings, agent tool-loop
└────────────┬─────────────┘
             │ SharedServices (runtime)
             ▼
┌──────────────────────────┐
│  ai-core                 │  ← LLM inference: local (llama.cpp) + Gemini
└──────────────────────────┘
```

## Features

- Chat UI (RecyclerView + Markdown rendering via Markwon)
- Agent tool-loop with per-tool approval dialogs
- Session persistence
- Works with either backend exposed by `ai-core` (on-device GGUF or Gemini API)

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. This plugin needs no NDK, submodule, or native toolchain.

```bash
cd ai-assistant
./gradlew assemblePlugin          # release  -> build/plugin/ai-assistant.cgp
./gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../libs/`.

## Installation

1. Build and install **`ai-core` first** (see [`../ai-core/README.md`](../ai-core/README.md)).
2. Build this plugin, copy `build/plugin/ai-assistant.cgp` to the device.
3. Install via CodeOnTheGo's Plugin Manager, then restart the IDE.
4. Open **AI Settings** to pick a local model or configure a Gemini API key.

## Key classes

- `AiAssistantPlugin.kt` — plugin entry point / lifecycle
- `fragments/ChatFragment.kt`, `viewmodel/ChatViewModel.kt` — chat UI + state
- `fragments/AiSettingsFragment.kt`, `viewmodel/AiSettingsViewModel.kt` — model/backend config
- `tool/` — the agent tool-loop (executor, router, per-tool handlers, approval)

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
