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

## Security

- **Gemini API key at rest.** When you use the Gemini backend, the API key is
  stored in this plugin's private `SharedPreferences` (`AgentSettings`). That
  store is **app-sandboxed** — other apps cannot read it — but it is **not
  encrypted at rest**, so it is recoverable on a rooted or otherwise compromised
  device. Remove it any time from **AI Settings** (or `clearGeminiApiKey()`).
  Encryption is deliberately not layered on here: the key is shared at runtime
  with the sibling `ai-core` plugin (which performs the Gemini calls), and
  `EncryptedSharedPreferences` would force both independent plugins to agree on a
  master-key alias and crypto library version — a fragile cross-plugin coupling.
  A host-provided secure-storage service is the correct long-term fix.
- **Gemini API key in transit.** The key is sent to Google as an `x-goog-api-key`
  request header (and via the SDK on the chat path), never in a URL query string.
- **File tools are confined to the project root** — see the in-IDE help page.

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
