# AI Assistant Plugins for CodeOnTheGo

A collection of AI-powered plugins that enable on-device LLM inference and intelligent code assistance in CodeOnTheGo (AndroidIDE).

> **📖 New Builder? Start with [BUILDING.md](BUILDING.md)** for complete setup instructions including llama.cpp dependency.

## Overview

This is a multi-module project containing plugins that work together to provide AI capabilities:

### Modules

1. **plugin-api** - CodeOnTheGo Plugin SDK (shared dependency)
2. **llama-api** - Kotlin interfaces for llama.cpp integration
3. **llama-impl** - Native implementation of llama.cpp for Android with JNI bindings
4. **ai-core-plugin** - Backend plugin providing LLM inference services
5. **ai-assistant-plugin** - Frontend plugin with chat UI for AI interaction

### llama.cpp Dependency

This project requires [llama.cpp](https://github.com/ggml-org/llama.cpp) to be cloned as a **sibling directory** to plugin-examples:

```
cogo/
├── plugin-examples/        # This repo
│   └── ai-assistant/
└── llama.cpp/              # Required: clone from https://github.com/ggml-org/llama.cpp
```

See [BUILDING.md](BUILDING.md) for detailed setup instructions.

### Architecture

```
┌──────────────────────────┐
│  ai-assistant-plugin     │  ← Chat UI, Settings
│  (Frontend)              │
└────────────┬─────────────┘
             │ SharedServices
             ▼
┌──────────────────────────┐
│  ai-core-plugin          │  ← LLM Backend
│  (Backend Service)       │
└────────────┬─────────────┘
             │
             ▼
┌──────────────────────────┐
│  llama-impl              │  ← Native llama.cpp
│  (JNI + C++)            │
└────────────┬─────────────┘
             │
             ▼
┌──────────────────────────┐
│  llama-api               │  ← Kotlin Interfaces
└──────────────────────────┘
```

## Features

- **On-device LLM inference** - Run GGUF models locally on Android
- **Plugin-based architecture** - Uses SharedServices for cross-plugin communication
- **Modern chat UI** - RecyclerView with Markdown rendering via Markwon
- **Content URI resolution** - Seamlessly load models from Android file picker
- **Thread-safe native library loading** - Proper JNI initialization

## Building

> **📖 See [BUILDING.md](BUILDING.md)** for complete build instructions, including:
> - llama.cpp setup and dependency resolution
> - NDK installation and configuration
> - Troubleshooting common build issues
> - Development workflow tips

### Quick Start

**Prerequisites:**
- Android SDK (API 33+)
- JDK 17+

The native llama.cpp library ships prebuilt as `ai-core-plugin/libs/v8/llama-v8-release.aar`,
so a normal build needs **no git submodule, NDK, or CMake**. (Those are only
needed to regenerate that AAR — see [BUILDING.md](BUILDING.md#updating-llamacpp-regenerating-the-aar).)

**Build Commands:**

```bash
# Build each plugin (release .cgp)
./gradlew :ai-core-plugin:assemblePlugin
./gradlew :ai-assistant-plugin:assemblePlugin
```

**Output Locations:**
- `ai-core-plugin/build/plugin/ai-core.cgp`
- `ai-assistant-plugin/build/plugin/ai-assistant.cgp`

> Use `assemblePluginDebug` instead of `assemblePlugin` for the debug variant.

## Installation

1. **Build both plugins** (ai-core and ai-assistant)
2. **Copy the `.cgp` files** (`build/plugin/*.cgp`) to the device Downloads folder
3. **Install via AndroidIDE Plugin Manager**
   - Install ai-core-plugin first
   - Then install ai-assistant-plugin
4. **Restart AndroidIDE** to load the plugins
5. **Configure model**:
   - Download a GGUF format model
   - Place in Downloads folder
   - Open AI Settings in plugin
   - Select model file

## Usage

1. Open AndroidIDE
2. Navigate to the AI Assistant tab
3. Configure your model path in Settings
4. Start chatting with the AI

## Requirements

- **AndroidIDE** v2.x or later
- **Android 13+** (API 33+)
- **ARM64 device** (arm64-v8a or armeabi-v7a)
- **GGUF model file** (e.g., Llama, Mistral, Phi)

## Recent Improvements

### JNI Library Loading Fix
Fixed `UnsatisfiedLinkError` by ensuring the native library loads before any static method calls. Uses NativeLibraryLoader pattern with wrapper methods.

### Content URI Resolution
Added automatic conversion of Android file picker `content://` URIs to file paths that native code can read. Scans Downloads folder for .gguf files when direct resolution fails.

### Plugin Context Resource Inflation
Fixed "No package ID" errors by using plugin context for all resource inflation, preventing conflicts with host app resources.

### LiveData Threading
Fixed threading violations by using `postValue()` instead of `setValue()` when updating LiveData from background coroutines.

## Development

### Project Structure

```
ai-assistant/
├── plugin-api/               # Plugin SDK
├── llama-api/                # Kotlin LLM interface
├── llama-impl/               # Native implementation
│   ├── src/main/cpp/         # JNI C++ code
│   └── src/main/java/        # Kotlin JNI bindings
├── ai-core-plugin/           # Backend service
│   └── src/main/kotlin/      # LLM backend implementation
└── ai-assistant-plugin/      # Frontend UI
    ├── src/main/kotlin/      # Chat UI, ViewModels
    └── src/main/res/         # Layouts, resources
```

### Key Classes

**llama-impl:**
- `LLamaAndroid.kt` - Main JNI interface with static configuration methods
- `llama-android.cpp` - Native llama.cpp integration

**ai-core-plugin:**
- `AiCorePlugin.kt` - Plugin entry point
- `LocalLlmBackend.kt` - LLM inference service implementation

**ai-assistant-plugin:**
- `AiAssistantPlugin.kt` - Plugin entry point
- `ChatFragment.kt` - Main chat UI
- `ChatViewModel.kt` - Chat state management
- `ChatAdapter.kt` - RecyclerView adapter with Markdown support
- `AiSettingsFragment.kt` - Model configuration UI

### Dependencies

- AndroidIDE Plugin API
- llama.cpp (native library)
- Kotlin Coroutines & Flow
- AndroidX (ViewModel, LiveData, RecyclerView)
- Markwon (Markdown rendering)
- SLF4J (Logging)

## License

GPL-3.0 - Same as AndroidIDE

## Contributing

Contributions are welcome! Please ensure:
- Code follows existing style
- Native code builds for both arm64-v8a and armeabi-v7a
- Plugins work independently (ai-core can function without ai-assistant)
- Test on physical ARM64 device

## Troubleshooting

**Plugin not loading:**
- Restart AndroidIDE after installation
- Check logcat for errors: `adb logcat | grep -E "Plugin|llama"`

**Model not loading:**
- Ensure file is valid GGUF format
- Check file is in Downloads folder and accessible
- Try using file path instead of content URI

**Native library errors:**
- Verify device is ARM64 or ARMv7
- Check NDK was installed during build
- Ensure llama.cpp native libraries are included in APK
