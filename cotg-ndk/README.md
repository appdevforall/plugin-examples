# cotg ndk: On-Device C++ Compiler for Code on the Go

`cotg ndk` is a native C++ build environment that runs on the device. It installs an Android
NDK, discovers the Clang toolchain and sysroot, provisions missing Android platform libraries,
configures the active project's `app/src/main/cpp` with CMake, compiles for `arm64-v8a`, and
writes the resulting `.so` into `app/src/main/jniLibs/arm64-v8a`.

The plugin surfaces as a **"COTG C++ Engine"** sidebar item and a **"C++ Compiler"** editor tab
that hosts a build console.

## Features

### Core plugin capabilities
- **Sidebar navigation**: `NavigationItem` ("COTG C++ Engine") in the `plugins` group
- **Main editor tab**: `EditorTabItem` ("C++ Compiler") hosting the compiler console
- **Themed UI**: fragment inflated through `PluginFragmentHelper.getPluginInflater`
- **Service integration**: environment and project services, plus native `sh` commands

### Plugin interfaces implemented
- `IPlugin`: core plugin lifecycle
- `UIExtension`: sidebar contribution
- `EditorTabExtension`: main editor tab contribution

### Compiler engine
- **NDK detection**: finds an installed NDK at `$ANDROID_HOME/cotgx_ultimate_ndk`, or the
  `cotgx-ndk-ultimate.tar.gz` archive in the device `Download` folder
- **Archive extraction**: extracts the NDK with the native `tar` command on first build
- **Toolchain discovery**: locates `clang`/`clang++`, sysroot, JNI headers, the C++ STL
  (`c++/v1`), and `libc++`; targets `aarch64-linux-android21`
- **Sysroot self-healing**: back-fills missing platform libraries (`liblog`, `libandroid`,
  `libEGL`, `libvulkan`, and others) from `/system/lib64` into the sysroot, with a warning when
  `liblog` cannot be resolved
- **CMake and make build**: configures with a manual toolchain (`Unix Makefiles`, `Release`)
  and builds with `-j4` (parallel) or `-j1` (safe)
- **Output**: copies built `*.so` into `jniLibs/arm64-v8a`, optionally exporting
  `libc++_shared.so`
- **Live console**: streams build output, auto-scrolls, and copies logs to the clipboard

## Architecture

```
cotg-ndk/
├── build.gradle.kts                # Build configuration (plugin API via ../libs)
├── settings.gradle.kts             # Buildscript classpath (shared ../libs)
├── proguard-rules.pro              # ProGuard keep rules
├── cotg-ndk-documentation.html     # Full plugin documentation
├── src/main/
│   ├── AndroidManifest.xml         # Plugin metadata, permissions, icons
│   ├── kotlin/com/cotg/cotgndk/
│   │   ├── cotgndk.kt              # Main plugin class (sidebar and tab contributions)
│   │   └── fragments/
│   │       └── cotgndkFragment.kt  # Compiler console and build engine
│   ├── res/
│   │   ├── drawable/ic_plugin.xml  # Monochrome "> C++" sidebar/tab icon
│   │   ├── layout/fragment_main.xml
│   │   └── values{,-night}/        # Material 3 teal-green theme
│   └── assets/
│       ├── icon_day.png            # "> C++" plugin badge (day)
│       └── icon_night.png          # "> C++" plugin badge (night)
└── README.md
```

### Plugin metadata (AndroidManifest.xml)
- **plugin.id**: `com.cotg.cotgndk`
- **plugin.name**: `cotg ndk`
- **plugin.description**: Advanced C++ Compiler and NDK Installer
- **plugin.author**: App Dev for All
- **plugin.main_class**: `com.cotg.cotgndk.cotgndk`
- **plugin.permissions**: `filesystem.read`, `filesystem.write`, `project.structure`,
  `system.commands`, `ide.environment.write`
- **plugin.icon_day / plugin.icon_night**: `assets/icon_day.png` / `assets/icon_night.png`

### Service integration
- `IdeEnvironmentService`: `getAndroidHomeDirectory()` for NDK install and sysroot work
- `IdeProjectService`: `getCurrentProject().rootDir` to locate the project to compile
- `IdeEditorTabService`: `selectPluginTab()` to open the compiler tab from the sidebar

## Building the plugin

```bash
# Build release plugin
./gradlew assemblePlugin

# Build debug plugin
./gradlew assemblePluginDebug
```

### Build output
- Release: `build/plugin/cotgndk.cgp`
- Debug: `build/plugin/cotgndk-debug.cgp`

### Installation
1. Import the `.cgp` file through Code on the Go's plugin manager.
2. The IDE discovers `com.cotg.cotgndk.cotgndk` from the manifest metadata and loads it.
3. Open **COTG C++ Engine** from the sidebar, or the **C++ Compiler** tab.

## Usage

1. Open a project that contains `app/src/main/cpp/CMakeLists.txt`.
2. Open the **C++ Compiler** tab.
3. If the NDK is not installed, tap **Download NDK** and place
   `cotgx-ndk-ultimate.tar.gz` in your `Download` folder, then reopen the tab.
4. Optionally enable **Export libc++_shared.so** and Parallel mode.
5. Tap **Compile**. The built `.so` is written to `app/src/main/jniLibs/arm64-v8a/`.

## Dependencies
- `plugin-api`: Code on the Go Plugin API (`compileOnly`, shared `../libs/plugin-api.jar`)
- `androidx.fragment`, `androidx.appcompat`, `material`: UI
- `kotlinx-coroutines-core` / `-android`: background build pipeline

### Requirements
- Android API 26+ (Android 8.0)
- Minimum IDE version: 1.0.0
- Target ABI: `arm64-v8a`
- Network once, to download the NDK archive

## License

Provided as-is for educational and development purposes. Use it as a foundation for your own
Code on the Go plugins.
