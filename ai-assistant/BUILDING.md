# Building AI Assistant Plugins - Complete Guide

This guide explains how to build the AI assistant plugins from source, including the llama.cpp dependency setup.

## Prerequisites

### Required Tools
- **Android Studio** or **Android SDK CLI tools** (API 33+)
- **Android NDK** (r26 or later) - Required for native code compilation
- **JDK 17+**
- **Git**
- **CMake** 3.22.1+ (usually bundled with Android SDK)

### System Requirements
- **macOS**, **Linux**, or **Windows** (with WSL recommended for Windows)
- **16GB RAM minimum** (32GB recommended for faster builds)
- **10GB free disk space** (for SDK, NDK, and build outputs)

---

## Step 1: Clone llama.cpp

The AI plugins depend on a customized version of llama.cpp with Android-specific improvements.

### Directory Structure

The build expects llama.cpp to be at: `../../llama.cpp/` relative to the plugin-examples repo root.

**Expected layout:**
```
cogo/
├── plugin-examples/        # This repo
│   └── ai-assistant/
└── llama.cpp/              # llama.cpp clone (sibling directory)
```

### Clone llama.cpp

```bash
# Navigate to the parent directory of plugin-examples
cd /path/to/cogo

# Clone the official llama.cpp repository
git clone https://github.com/ggml-org/llama.cpp.git

# Optional: If there's a custom fork with Android improvements
# git clone https://github.com/YOUR-ORG/llama.cpp.git -b android-optimizations
```

### Verify the structure

```bash
cd plugin-examples/ai-assistant/llama-impl/src/main/cpp
ls ../../../../../../llama.cpp/
# Should show: CMakeLists.txt, common/, ggml/, src/, examples/, etc.
```

---

## Step 2: Configure Android SDK & NDK

### Set up local.properties

Create or update `local.properties` in the `ai-assistant/` directory:

```properties
# Path to Android SDK
sdk.dir=/Users/your-username/Library/Android/sdk

# Optional: Specify NDK version if you have multiple
ndk.dir=/Users/your-username/Library/Android/sdk/ndk/26.1.10909125
```

### Install NDK via Android Studio

1. Open Android Studio
2. Go to **Tools → SDK Manager**
3. Navigate to **SDK Tools** tab
4. Check **NDK (Side by side)**
5. Check **CMake**
6. Click **Apply** to install

### Verify NDK Installation

```bash
ls $ANDROID_SDK_ROOT/ndk/
# Should show: 26.1.10909125 (or similar version)
```

---

## Step 3: Build the Plugins

### Option A: Build via Gradle (Recommended)

```bash
cd plugin-examples/ai-assistant

# Build all plugins (ai-core + ai-assistant)
./gradlew assembleV8Debug

# Or build specific ABIs for faster iteration
./gradlew assembleV7Debug     # ARMv7 (32-bit)
./gradlew assembleV8Debug     # ARM64 (64-bit, recommended)

# Build release variants
./gradlew assembleV8Release
```

### Option B: Build via Android Studio

1. Open Android Studio
2. **File → Open** → Select `plugin-examples/ai-assistant/`
3. Wait for Gradle sync
4. **Build → Make Project**
5. Find outputs in `ai-core-plugin/build/outputs/apk/` and `ai-assistant-plugin/build/outputs/apk/`

---

## Step 4: Outputs & Installation

### Build Outputs

After a successful build, you'll find APK files at:

```
ai-core-plugin/build/outputs/apk/v8/debug/ai-core-plugin-v8-debug.apk
ai-assistant-plugin/build/outputs/apk/v8/debug/ai-assistant-plugin-v8-debug.apk
```

### Rename for Installation

Rename `.apk` files to `.cgp` (CodeOnTheGo Plugin) extension:

```bash
cd ai-core-plugin/build/outputs/apk/v8/debug
mv ai-core-plugin-v8-debug.apk ai-core-plugin.cgp

cd ../../../../../ai-assistant-plugin/build/outputs/apk/v8/debug
mv ai-assistant-plugin-v8-debug.apk ai-assistant-plugin.cgp
```

### Install on Device

```bash
# Push plugins to device
adb push ai-core-plugin.cgp /sdcard/Download/
adb push ai-assistant-plugin.cgp /sdcard/Download/

# Then install via CodeOnTheGo Plugin Manager:
# 1. Open CodeOnTheGo app
# 2. Navigate to Settings → Plugins
# 3. Tap "Install from file"
# 4. Select ai-core-plugin.cgp first
# 5. Then install ai-assistant-plugin.cgp
# 6. Restart CodeOnTheGo
```

---

## Troubleshooting

### llama.cpp Not Found

**Error:**
```
CMake Error: add_subdirectory given source "../../../../../../llama.cpp" which is not an existing directory
```

**Solution:**
```bash
# Verify llama.cpp location
ls ../../llama.cpp/
# If missing, clone it as shown in Step 1
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

```
ai-assistant-plugin → ai-core-plugin → llama-impl → llama.cpp (native)
                                     ↘ llama-api (interfaces)
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
