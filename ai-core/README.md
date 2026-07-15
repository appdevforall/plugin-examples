# AI Core plugin for CodeOnTheGo

The shared **LLM inference backend** for CodeOnTheGo's AI plugins. It exposes an
inference service (via SharedServices) that other plugins — e.g. the sibling
[`ai-assistant`](../ai-assistant/) — consume at runtime. It ships two backends:

- **Local (on-device)** — runs GGUF models through a bundled, prebuilt
  **llama.cpp** AAR.
- **Gemini** — calls the Gemini API through the Google Generative AI SDK.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. A normal build needs **no NDK, submodule, or CMake** — the native
library is committed prebuilt.

```bash
cd ai-core
./gradlew assemblePlugin          # release  -> build/plugin/ai-core.cgp
./gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../libs/` and the native
library from `libs/v8/llama-v8-release.aar` + `libs/llama-api.jar`.

## Native llama.cpp: prebuilt by default

The plugin consumes the committed AAR, so the `llama.cpp` git submodule and the
`llama-api` / `llama-impl` source modules are **not** part of a normal build.
`settings.gradle.kts` includes those modules **only** when the submodule is
checked out (`subprojects/llama.cpp/CMakeLists.txt` exists) — which is exactly
the setup a CI checkout does *not* have, so CI builds against the prebuilt AAR.

To regenerate the AAR after bumping the llama.cpp fork, see **[BUILDING.md](BUILDING.md)**
and `scripts/rebuild-llama-aar.sh` (requires the submodule + NDK/CMake). Notes on
the fork live in [SUBMODULE_NOTES.md](SUBMODULE_NOTES.md).

## Installation

Install **`ai-core` before `ai-assistant`** — the assistant resolves this
plugin's inference service at runtime. Copy `build/plugin/ai-core.cgp` to the
device and install via CodeOnTheGo's Plugin Manager, then restart the IDE.

## Key classes

- `AiCorePlugin.kt` — plugin entry point; registers the backends
- `LocalLlmBackend.kt` — on-device GGUF inference over the llama.cpp AAR
- `GeminiBackend.kt` — Gemini API backend
- `LlmInferenceServiceImpl.kt` — the SharedServices-exposed inference service

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
