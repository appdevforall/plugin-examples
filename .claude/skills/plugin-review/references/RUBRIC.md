# Code on the Go plugin submission rubric

Verbatim criteria as provided by the reviewer. These are the authoritative grading conditions.

## 6.1 Compatibility

Plugins must run on the current stable IDE release and the immediately preceding minor version. Plugins targeting a single point release are rejected.

## 6.2 Resource discipline

- No background threads or coroutines outliving the plugin lifecycle.
- No static state retaining references to IDE objects after unload.
- No file handles, sockets, or processes left open after the contributing extension shuts down.
- LSP server processes must terminate when the workspace closes.

## 6.3 Build reproducibility

We must be able to rebuild the submitted artifact from the linked source on a clean machine using the documented build command. Submissions that only build on the author's machine are rejected.

## 6.4 Native binaries

Plugins that ship native libraries must hold the `NATIVE_CODE` permission and meet the requirements in Section 7.3 under the Sandbox bypass tier.

## 6.5 No reflection into IDE internals

Plugins use only the public `plugin-api` and `lsp/api` surfaces. Reflection against IDE classes is grounds for rejection.

## 6.6 Html documentation

Helps potential users of the plugin to decide if they want to download and install it

## Manifest

The manifest must declare every extension, every permission, the supported IDE version range, and the minimum Android SDK level.

---

## Quick reference: IDE plugin API surface

Extracted from `libs/plugin-api.jar`. Verify against the JAR shipped with the project under review if anything seems off.

### Permission keys (`com.itsaky.androidide.plugins.PluginPermission`)
- `filesystem.read`
- `filesystem.write`
- `ide.environment.write`
- `ide.settings`
- `native.code`
- `network.access`
- `project.structure`
- `system.commands`

### Common extension interfaces (`com.itsaky.androidide.plugins.extensions.*`)
- `UIExtension` — contributes UI (sidebar nav, editor tabs)
- `EditorTabExtension` — `getMainEditorTabs()` returns `EditorTabItem`s
- `BuildActionExtension` — contributes build actions
- `DocumentationExtension`
- `EditorExtension`
- `ProjectExtension`

### Lifecycle hooks (`com.itsaky.androidide.plugins.IPlugin`)
- `initialize(PluginContext)` — set up state
- `activate()` / `deactivate()`
- `dispose()` — **the place to release threads, observers, sockets, and clear static references**

### Build variants (`com.itsaky.androidide.plugins.build.PluginBuilder$BuildVariant`)
- `DEBUG` → task `assemblePluginDebug`, suffix `-debug`
- `RELEASE` → task `assemblePlugin`, no suffix

The release task produces `build/plugin/<pluginName>.cgp`.
