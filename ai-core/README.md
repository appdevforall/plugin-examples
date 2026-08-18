# AI Core plugin for CodeOnTheGo

Two things in one plugin, and **mandatory for every AI feature**:

1. The **Agent** — a tool-calling chat assistant that reads, searches and edits
   the open project behind an approval gate, contributed as an editor tab plus a
   settings screen.
2. The **LLM inference router** — publishes `LlmInferenceService` through
   `SharedServices`, which [`code-suggestions-plugin`](../code-suggestions-plugin/),
   [`speech-to-text-plugin`](../speech-to-text-plugin/) and
   [`vector-search-plugin`](../vector-search-plugin/) consume at runtime.

The Agent and the router shipped as separate `ai-assistant` and `ai-core` plugins
until they were merged here; an existing install's settings and chat history are
adopted on first activation.

**AI Core ships no backend of its own.** Backends are separate plugins that
register themselves with it on activation:

- [`ai-agent-local`](../ai-agent-local/) — on-device GGUF inference through a
  bundled, prebuilt **llama.cpp** AAR. Registers as `local`.
- [`ai-agent-gemini`](../ai-agent-gemini/) — the Gemini REST API over
  `HttpURLConnection` (no third-party SDK), so it is unaffected by the host IDE's
  OkHttp version. Registers as `gemini`.

Install AI Core **plus at least one backend**, or every request fails with
`Backend '…' not found`.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK, submodule or CMake — those moved to `ai-agent-local`
with the native code.

```bash
cd ai-core
./gradlew assemblePlugin          # release  -> build/plugin/ai-core.cgp
./gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../libs/`.

## Backend registration and load order

Plugins load in parallel with no guaranteed order, so a backend plugin may
activate *before* AI Core has published its service. Each backend plugin handles
that by registering through a `PluginLifecycleListener` on AI Core's plugin id,
so it re-registers as soon as AI Core activates. Installation order does not
matter; only "at least one backend is installed" does.

## Optional backend capabilities

`LlmBackend` carries only what every backend can answer. Anything optional is a
separate interface extending it — `HistoryCapableBackend`, `ToolCallingBackend`,
`CancellableBackend`, `ConfigurableBackend` — and AI Core asks by type before it
calls. It holds no per-backend branching.

**A backend that wants multi-turn chat must implement `HistoryCapableBackend`.**
`generateStreamingWithTools` routes to `generateStreamingWithHistory` for one,
and to single-turn `generateStreaming` for a backend that declares neither
capability — so a missing declaration silently drops the conversation and
produces a plausible one-shot reply with no error. That is why both shipped
backends declare it, and why `LocalLlmBackendTest` asserts the declaration
rather than trusting behaviour to catch it.

## Key classes

Every source file sits in a package named for its layer; nothing is loose at the
root of `com/itsaky/androidide/plugins/aicore/`.

- `plugin/AiCorePlugin.kt` — plugin entry point; publishes the router, contributes
  the Agent tab and settings screen, and adopts a pre-merge install's data
- `services/LlmInferenceServiceImpl.kt` — the SharedServices-exposed router
- `backends/AiBackend.kt` — maps a stored backend setting onto a backend id
- `backends/BackendRegistry.kt` — the installed backends, as the settings
  selector sees them
- `backends/BackendFragmentFactory.kt` — loads a backend's settings pane with
  that backend plugin's own classloader
- `managers/ChatStorageManager.kt` — chat history persisted as JSON
- `logging/` — `LOG_PREFIX` (`AiCore`), prefixing every logcat tag this plugin
  writes, and `AgentTrace`, the one-stream trace of an agent run
- `fragments/`, `viewmodel/`, `tool/` — the Agent chat, its tool loop and handlers

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
