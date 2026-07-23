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

- **Gemini API key at rest.** The key is encrypted with AES/GCM under a
  hardware-backed Android Keystore secret and only the ciphertext is written to
  this plugin's private `SharedPreferences` (`AgentSettings`), as
  `enc:v1:` + base64(iv‖ciphertext). A copied prefs file — root, `adb backup`,
  forensic dump — is useless without this device's Keystore. Remove the key any
  time from **AI Settings** (or `clearGeminiApiKey()`).
  - A key stored before this plugin encrypted them is still plaintext on disk;
    it is re-encrypted in place the first time it's read
    (`SecureApiKeyStore.readAndMigrate`), so no user action is needed.
  - The key is **not** bound to user authentication
    (`setUserAuthenticationRequired` is not set), so it stays usable for
    background inference and a lock-screen credential change does not invalidate
    it. If the Keystore entry is lost anyway — the app's data is cleared, or an
    OEM Keystore drops the alias — the stored key can no longer be decrypted and
    must be re-entered; **Edit** says so instead of presenting an empty field.
    Saving surfaces a failure message rather than silently storing nothing.
  - Encryption at rest defends against *offline* recovery of the prefs file. It
    does not defend against code already running as the host IDE's UID, which can
    simply call `decrypt` — that would need a host-provided secure-storage
    service with per-plugin isolation.
- **Shared crypto across two plugins.** `ai-core` performs the Gemini calls and
  therefore has to read the same key. Both plugins run in the host IDE's process
  (same UID) and so share one Android Keystore; each ships an identical copy of
  `SecureApiKeyStore` pinned to the alias `cotg_ai_gemini_key_v1`. The two copies
  must stay byte-identical apart from their package line — if `ALIAS`,
  `TRANSFORM`, `IV_LEN` or `ENC_PREFIX` drift, `ai-core` silently fails to
  decrypt and Gemini reports "backend not available". A host-provided
  secure-storage service would remove the duplication.
- **Gemini API key in transit.** The key is sent to Google as an `x-goog-api-key`
  request header (and via the SDK on the chat path), never in a URL query string.
- **File tools are confined to the project root** — see the in-IDE help page.
- **The context-file picker is confined to the open project** and fails closed:
  with no project open it refuses to open rather than falling back to a broader
  root.

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
