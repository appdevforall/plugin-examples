# Speech to Text plugin for CodeOnTheGo

Voice-to-code. Adds a **Voice to Code** button to the editor toolbar: tap it,
speak, and the recognized text — or code generated from it — is inserted at the
cursor. Speech recognition uses Android's `SpeechRecognizer` (on-device when
available); code generation is delegated to the companion `ai-core` plugin.

> Code generation has **no compile-time dependency** on `ai-core`; the LLM
> service is resolved at runtime. Install **`ai-core` first** for voice→code.
> Without it, the raw transcript is inserted instead.

## Architecture

```
┌──────────────────────────┐
│  speech-to-text (this)   │  ← toolbar button, SpeechRecognizer, insert at cursor
└────────────┬─────────────┘
             │ SharedServices (runtime) → LlmInferenceService (optional)
             ▼
┌──────────────────────────┐
│  ai-core                 │  ← turns the transcript into code
└──────────────────────────┘
```

## Features

- "Voice to Code" toolbar action (`UIExtension`), enabled only while a file is open
- Dynamic icon: mic (idle) → waves (recording) → spinner (processing)
- On-device speech recognition preferred, with network fallback
- Optional LLM code generation from the transcript via `ai-core`
- Inserts the result at the editor cursor

## Permissions

Android (`uses-permission`): `RECORD_AUDIO`, `INTERNET`.

Plugin (`plugin.permissions`): `filesystem.read`, `filesystem.write`,
`network.access`, `native.code`.

| Permission | Why |
|---|---|
| `RECORD_AUDIO` | capture microphone audio for recognition |
| `INTERNET` / `network.access` | network speech fallback and the Gemini backend |
| `filesystem.write` | insert transcribed/generated text into the open file |
| `filesystem.read` | read editor/file context |

`RECORD_AUDIO` is a runtime (dangerous) permission: it is requested on first tap
of the button, not at load. If denied, the plugin degrades gracefully (no voice
capture) and can still insert LLM output. Microphone audio is captured only
while recording and is not stored by the plugin. When `ai-core`'s **Gemini**
backend is selected, the transcript is sent to Google over HTTPS; the **Local**
backend keeps everything on-device.

## Building

Prerequisites: Android SDK (API 33+), JDK 17. Create `local.properties` with
`sdk.dir=...`.

```bash
cd speech-to-text-plugin
../gradlew assemblePlugin          # release  -> build/plugin/speech-to-text-plugin.cgp
../gradlew assemblePluginDebug     # debug variant
```

The build resolves `plugin-api.jar` from the repo-root `../libs/`.

## Installation

1. (Optional but recommended) Build and install **`ai-core` first** for
   voice→code (see [`../ai-core/README.md`](../ai-core/README.md)).
2. Build this plugin, install `build/plugin/speech-to-text-plugin.cgp` via
   CodeOnTheGo's Plugin Manager, and restart the IDE.
3. Open a file, tap the microphone in the editor toolbar, grant the microphone
   permission on first use, and speak.

## Key classes

- `SpeechToTextPlugin.kt` — toolbar action, `SpeechRecognizer` lifecycle,
  transcript handling, editor insertion

## License

GPL-3.0 — same as AndroidIDE / CodeOnTheGo.
