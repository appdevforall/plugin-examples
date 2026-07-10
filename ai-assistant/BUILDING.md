# Building AI Assistant Plugins - Complete Guide

This guide explains how to build the AI assistant plugins.

> **TL;DR** — A normal build needs only the Android SDK + JDK 17. The native
> llama.cpp library ships **prebuilt** as `ai-core-plugin/libs/v8/llama-v8-release.aar`,
> so you do **not** need the git submodule, the NDK, or CMake to build the
> plugins. Those are only required when *regenerating* that AAR (see
> [Updating llama.cpp](#updating-llamacpp-regenerating-the-aar)).

## Prerequisites

### Required for a normal build
- **Android Studio** or **Android SDK CLI tools** (API 33+)
- **JDK 17+**
- **Git**

### Additionally required only to regenerate the native AAR
- **Android NDK** (r26 or later)
- **CMake** 3.22.1+ (usually bundled with Android SDK)
- The `subprojects/llama.cpp` git submodule checked out

### System Requirements
- **macOS**, **Linux**, or **Windows** (with WSL recommended for Windows)
- **16GB RAM minimum** (32GB recommended for faster builds)
- **10GB free disk space** (for SDK, NDK, and build outputs)

---

## Step 1: Configure the Android SDK

Create or update `local.properties` in the `ai-assistant/` directory:

```properties
# Path to Android SDK (required)
sdk.dir=/Users/your-username/Library/Android/sdk

# NDK path — only needed to regenerate the native AAR, not for a normal build
ndk.dir=/Users/your-username/Library/Android/sdk/ndk/27.0.12077973
```

That's the only setup a normal build needs. The native llama.cpp library is
already committed as `ai-core-plugin/libs/v8/llama-v8-release.aar` (the JNI
wrapper plus `.so` files for arm64-v8a, armeabi-v7a, x86 and x86_64), with its
tiny interface jar at `ai-core-plugin/libs/llama-api.jar`.

---

## Step 2: Build the Plugins

### Option A: Build via Gradle (Recommended)

```bash
cd plugin-examples/ai-assistant

# Build each plugin as a release .cgp
./gradlew :ai-core-plugin:assemblePlugin
./gradlew :ai-assistant-plugin:assemblePlugin

# Debug variant
./gradlew :ai-core-plugin:assemblePluginDebug
```

### Option B: Build via Android Studio

1. Open Android Studio
2. **File → Open** → Select `plugin-examples/ai-assistant/`
3. Wait for Gradle sync
4. Run the `assemblePlugin` task for each module from the Gradle panel

---

## Step 3: Outputs & Installation

### Build Outputs

`assemblePlugin` writes ready-to-install `.cgp` files directly:

```
ai-core-plugin/build/plugin/ai-core.cgp
ai-assistant-plugin/build/plugin/ai-assistant.cgp
```

No renaming needed.

### Install on Device

```bash
# Push plugins to device
adb push ai-core-plugin/build/plugin/ai-core.cgp /sdcard/Download/
adb push ai-assistant-plugin/build/plugin/ai-assistant.cgp /sdcard/Download/

# Then install via CodeOnTheGo Plugin Manager:
# 1. Open CodeOnTheGo app
# 2. Navigate to Settings → Plugins
# 3. Tap "Install from file"
# 4. Select ai-core.cgp first
# 5. Then install ai-assistant.cgp
# 6. Restart CodeOnTheGo
```

---

## Updating llama.cpp (regenerating the AAR)

You only need this when your llama.cpp fork changes. It requires the NDK/CMake
toolchain and the submodule; a normal build does not.

```bash
cd plugin-examples/ai-assistant

# One command: inits the submodule, compiles :llama-impl + :llama-api from
# source, and copies the fresh artifacts into ai-core-plugin/libs/.
./scripts/rebuild-llama-aar.sh

# To point at a specific fork commit first:
git -C subprojects/llama.cpp fetch origin
git -C subprojects/llama.cpp checkout <commit-or-branch>
./scripts/rebuild-llama-aar.sh
```

Then commit the refreshed `ai-core-plugin/libs/v8/llama-v8-release.aar` and
`ai-core-plugin/libs/llama-api.jar`. The `:llama-impl` / `:llama-api` Gradle
modules load only while the submodule is checked out, so they never affect a
normal build.

> Reproducibility: the AAR is a compiled binary, so ideally regenerate it in CI
> with a pinned NDK/CMake and record its checksum, rather than from an arbitrary
> laptop.

---

## Troubleshooting

### Native library not found (`UnsatisfiedLinkError`)

The prebuilt AAR bundles arm64-v8a, armeabi-v7a, x86 and x86_64. If you rebuilt
it with a restricted ABI set, confirm your device's ABI is included:

```bash
unzip -l ai-core-plugin/libs/v8/llama-v8-release.aar | grep 'jni/'
```

### llama.cpp Not Found (only when regenerating the AAR)

**Error:**
```
CMake Error: add_subdirectory given source ".../subprojects/llama.cpp" which is not an existing directory
```

**Solution:**
```bash
# The submodule is required only for regeneration. Initialize it:
git submodule update --init --recursive
```

### NDK Not Found

**Error:**
```
NDK is not installed
```

**Solution:**
1. Install NDK via Android Studio (see Step 2)
2. Or download directly: https://developer.android.com/ndk/downloads
3. Update `local.properties` with correct `ndk.dir` path

### CMake Version Mismatch

**Error:**
```
CMake 3.22.1 or higher is required
```

**Solution:**
```bash
# Install via Android Studio SDK Manager (SDK Tools → CMake)
# Or via Homebrew on macOS:
brew install cmake

# Verify version
cmake --version
```

### Out of Memory During Build

**Error:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
Create or update `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m
```

### Native Library Loading Errors

**Error:**
```
java.lang.UnsatisfiedLinkError: dlopen failed: library "llama-android" not found
```

**Solution:**
1. Ensure you built the correct ABI for your device (v8 for 64-bit ARM)
2. Check that native libraries are included in the APK:
   ```bash
   unzip -l ai-assistant-plugin.apk | grep "lib/"
   # Should show: lib/arm64-v8a/libllama-android.so
   ```

### Content URI Resolution Errors

**Error:**
```
Failed to resolve content URI to file path
```

**Solution:**
1. Place GGUF model file in `/sdcard/Download/` directory
2. Grant storage permissions to CodeOnTheGo app
3. Use file picker in AI Settings to select model

---

## Development Workflow

### Incremental Builds

For faster iteration during development:

```bash
# Only rebuild changed modules
./gradlew :ai-assistant-plugin:assembleV8Debug

# Clean specific module
./gradlew :ai-core-plugin:clean

# Full clean (when CMake cache is stale)
./gradlew clean
rm -rf .gradle .cxx build
```

### Testing Native Code Changes

When modifying `llama-android.cpp` or `llama.cpp` sources:

```bash
# Clean CMake cache to force recompilation
rm -rf llama-impl/.cxx

# Rebuild
./gradlew :llama-impl:assembleV8Debug
```

### Debugging with Logcat

```bash
# Watch plugin logs
adb logcat | grep -E "Plugin|llama|AiCore|AiAssistant"

# Filter JNI errors
adb logcat | grep -E "UnsatisfiedLinkError|JNI"

# Full verbose output
adb logcat -v time '*:V'
```

---

## Architecture Notes

### llama.cpp Integration

The native integration works as follows:

1. **llama-impl/src/main/cpp/CMakeLists.txt** references `llama.cpp/` via `add_subdirectory()`
2. **llama-android.cpp** provides JNI bindings to llama.cpp's C++ API
3. **LLamaAndroid.kt** wraps the JNI calls with Kotlin-friendly interfaces
4. **ai-core-plugin** uses the llama-api/llama-impl modules to provide LLM inference services
5. **ai-assistant-plugin** consumes the services via SharedServices plugin API

### Multi-Module Dependencies

Default build (self-contained — consumes the prebuilt artifacts):

```
ai-assistant-plugin → (SharedServices) → ai-core-plugin → libs/v8/llama-v8-release.aar (native)
                                                         ↘ libs/llama-api.jar (interfaces)
```

Regeneration path (only when rebuilding the AAR from source):

```
llama-impl → llama.cpp (native, git submodule)
llama-api  → interfaces
   ⇒ scripts/rebuild-llama-aar.sh copies their output into ai-core-plugin/libs/
```

Both plugins depend on `plugin-api` from the parent `plugin-examples/libs/` directory.

---

## Customizations to llama.cpp

This build uses llama.cpp with the following Android-specific modifications:

### Custom Patches Applied

1. **Content URI Resolution** - Converts Android file picker URIs to native paths
2. **JNI Thread Safety** - Ensures native library loads before static calls
3. **Tool Calling** - JSON-based function calling for agentic workflows
4. **Chat History** - Persistent conversation state management
5. **Memory Optimizations** - Reduced memory footprint for mobile devices

### Upstream Compatibility

The modifications are designed to be compatible with upstream llama.cpp. To update to a newer llama.cpp version:

```bash
cd ../llama.cpp
git fetch origin
git merge origin/master

# Resolve any conflicts in Android-specific code
# Test the build
cd ../plugin-examples/ai-assistant
./gradlew assembleV8Debug
```

---

## Building for Production

### Release Build

```bash
# Build optimized release variants
./gradlew assembleV8Release

# Outputs with ProGuard/R8 optimization
ai-core-plugin/build/outputs/apk/v8/release/ai-core-plugin-v8-release.apk
ai-assistant-plugin/build/outputs/apk/v8/release/ai-assistant-plugin-v8-release.apk
```

### Code Signing

For distribution, sign the APKs:

```bash
# Generate keystore (one-time)
keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias ai-plugins

# Sign APK
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 \
  -keystore release-key.jks \
  ai-core-plugin-v8-release.apk ai-plugins
```

### Size Optimization

Current release sizes:
- **ai-core-plugin**: ~5 MB (includes llama.cpp native libraries)
- **ai-assistant-plugin**: ~2 MB (UI and business logic)

To reduce size:
1. Build only arm64-v8a ABI (skip v7/x86)
2. Enable R8 full mode in `gradle.properties`
3. Strip debug symbols from native libraries

---

## Contributing

When contributing changes to the AI plugins:

1. Test on **physical ARM64 device** (emulators may not support native code)
2. Verify both **ai-core** and **ai-assistant** plugins work independently
3. Ensure **llama.cpp submodule** updates are documented
4. Run ProGuard/R8 release build to catch reflection issues
5. Update this BUILDING.md with any new dependencies or steps

---

## Additional Resources

- [llama.cpp Official Repo](https://github.com/ggml-org/llama.cpp)
- [AndroidIDE Plugin Documentation](https://www.appdevforall.org/codeonthego/help/exp-plugins-top.html)
- [GGUF Model Downloads](https://huggingface.co/models?library=gguf)
- [Android NDK Documentation](https://developer.android.com/ndk)

---

**Last Updated:** 2026-06-24
**Tested with:**
- Android SDK 34
- NDK 26.1.10909125
- llama.cpp commit b6521
- Gradle 8.7
