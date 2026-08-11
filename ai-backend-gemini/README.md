# AI Gemini Backend plugin for CodeOnTheGo

Google Gemini API inference for CodeOnTheGo's AI plugins. Registers itself as the
`gemini` backend with [`ai-core`](../ai-core/)'s `LlmInferenceService`, which is
what `ai-core`'s Agent chat, `code-suggestions-plugin`, `speech-to-text-plugin` and
`vector-search-plugin` actually talk to.

Calls the Generative Language REST API directly over `HttpURLConnection` rather
than the google-genai SDK: the SDK bundles OkHttp 4.x, but plugins run in the
host IDE's classloader where `okhttp3` resolves to the host's older OkHttp, and
that mismatch crashed generation with a `NoSuchMethodError`.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`. This plugin uses the shared wrapper at the repo root:

```bash
cd ai-backend-gemini
../gradlew assemblePlugin          # release  -> build/plugin/ai-backend-gemini.cgp
../gradlew assemblePluginDebug     # debug variant
```

## API key handling

The key is entered in **AI Core → Agent settings**, not here. It is stored
encrypted (AES/GCM under a hardware-backed Android Keystore secret) and sent as
an `x-goog-api-key` **header**, never in a URL query string.

`SecureApiKeyStore.kt` is the only copy; the key written
there decrypts here — both plugins share one Keystore because they run in the host
app's process. The `verifySecureApiKeyStoreParity` task in `build.gradle.kts` fails
the build if the crypto constants drift, because that failure would otherwise only
surface on a device as "backend not available".

## Installation

Install **`ai-core` as well** — without the router this plugin has nothing to
register with. Order does not matter: this plugin re-registers when it sees
ai-core activate. Copy `build/plugin/ai-backend-gemini.cgp` to the device, install
via CodeOnTheGo's Plugin Manager, then restart the IDE.

## Cross-plugin contract

This plugin's own settings pane calls `GeminiBackend.listModels()` and `listModels(String)`
reflectively (see its `ReflectiveGeminiCatalogGateway`) to populate the model
picker and to verify a key before it is saved. Those two signatures, and the
`ListModels HTTP <code>` message shape thrown by `fetchAvailableModels`, are a
contract — `proguard-rules.pro` pins the methods.

## Key classes

- `GeminiPlugin.kt` — plugin entry point; registers the backend with ai-core
- `GeminiBackend.kt` — the REST transport, streaming (SSE) and model catalog
- `GeminiErrorFormatter.kt` — turns an API failure into one translated sentence
- `SecureApiKeyStore.kt` — AES/GCM at rest

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
