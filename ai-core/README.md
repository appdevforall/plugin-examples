# AI Core plugin for CodeOnTheGo

The **LLM inference router** for CodeOnTheGo's AI plugins. It publishes
`LlmInferenceService` through `SharedServices`, which other plugins — e.g. the
sibling [`ai-assistant`](../ai-assistant/) — consume at runtime.

**AI Core ships no backend of its own.** Backends are separate plugins that
register themselves with it on activation:

- [`ai-backend-local`](../ai-backend-local/) — on-device GGUF inference through a
  bundled, prebuilt **llama.cpp** AAR. Registers as `local`.
- [`ai-backend-gemini`](../ai-backend-gemini/) — the Gemini REST API over
  `HttpURLConnection` (no third-party SDK), so it is unaffected by the host IDE's
  OkHttp version. Registers as `gemini`.

Install AI Core **plus at least one backend**, or every request fails with
`Backend '…' not found`.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. No NDK, submodule or CMake — those moved to `ai-backend-local`
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

`LlmBackend` declares six abstract methods plus three defaulted ones
(`generateStreamingWithTools`, `generateStreamingWithHistory`, `getConfigSpecs`),
and `LlmInferenceService.CancellableBackend` marks a backend whose streaming can
be stopped. AI Core uses those directly — it holds no per-backend branching.

**A backend that wants multi-turn chat must override `generateStreamingWithTools`,
not just `generateStreamingWithHistory`.** The plugin API's default for the tools
method routes to single-turn `generateStreaming`, so inheriting it silently drops
the conversation and produces a plausible one-shot reply with no error. That is
why `LocalLlmBackend` overrides both, and why `LocalLlmBackendTest` asserts the
overrides exist rather than trusting behaviour to catch it.

## Key classes

- `AiCorePlugin.kt` — plugin entry point; publishes the service
- `LlmInferenceServiceImpl.kt` — the SharedServices-exposed router
- `AiBackend.kt` — maps the AI Assistant backend setting onto a backend id

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
