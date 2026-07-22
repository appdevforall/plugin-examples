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

## 6.7 Tooltips and in-app help

Help must be available *inside* the running IDE, not only in the standalone 6.6 page. Code On The Go uses a three-tier help model and plugins are expected to participate fully:

- **Tier 1 (brief)** and **Tier 2 (more detail)** are delivered as tooltips. A user long-presses any UI element and sees a brief summary, then "See More" for detail.
- **Tier 3 (full)** is an exhaustive in-app web page reached from a button on the tooltip, served from the bundled documentation database — no network required.

Requirements:

- The plugin implements `DocumentationExtension` and returns its `plugin_<pluginId>` category from `getTooltipCategory()`.
- **Every UI element the plugin contributes has a tooltip.** Each `NavigationItem`, `MenuItem`, `TabItem`, FAB/toolbar action, and `EditorTabItem` carries a `tooltipTag` (or `tooltip` for `EditorTabItem`); any custom `View` the plugin shows is wired to the tooltip system. No contributed element may be left without help.
- Every `tooltipTag` resolves to a `PluginTooltipEntry` returned from `getTooltipEntries()` — no dangling tags. Each entry provides a Tier 1 `summary` and a Tier 2 `detail`.
- **Complete help is available within the app.** The plugin ships a Tier 3 bundle via `getTier3DocsAssetPath()` that comprehensively covers its functionality, and tooltips link to it through `PluginTooltipButton`s. Tier 3 must work offline (served locally); it is not a link out to the public internet.
- Tooltip and Tier 3 content is in English and readable (light background, dark body text), consistent with 6.6.

This is distinct from 6.6: 6.6 is the install-decision page that ships at the plugin's top level; 6.7 is the in-IDE tooltip + Tier 3 help wired through `DocumentationExtension`. A plugin can pass 6.6 and still fail 6.7.

## 6.8 Plugin icons and imagery

The Plugin Manager renders a day/night icon pair for every plugin, and template-installer plugins additionally render a thumbnail for each template variant on the New Project screen. Both must be present and correct.

- **Plugin icon (day/night pair).** The manifest declares `plugin.icon_day` and `plugin.icon_night` as `<meta-data>` on `<application>`, each `android:value` pointing at an in-`.cgp` path (`assets/<name>.png`). Both variants are required — the IDE picks the light or dark icon per theme, and debug installs enforce the icon (release builds skip the check). The images are real PNGs (not res-drawables, not `android:icon`, not Git LFS stubs), conventionally ~192×192.
- **Template variant thumbnails.** Every template variant a plugin registers via `CgtTemplateBuilder.thumbnailFromAssets(...)` supplies a real, distinct `thumb.png` (512×512 PNG) at the referenced asset path. Identical placeholder thumbnails shared across variants, or a missing `thumb.png` for a registered variant, is a defect — each variant must be visually distinguishable on the New Project screen.

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
- `DocumentationExtension` — contributes tooltips + Tier 3 help (see clause 6.7)
- `EditorExtension`
- `ProjectExtension`

### Tooltip / help API surface (`com.itsaky.androidide.plugins.extensions.*`)
- `DocumentationExtension` — `getTooltipCategory()`, `getTooltipEntries(): List<PluginTooltipEntry>`, `getTier3DocsAssetPath(): String?`, `onDocumentationInstall()`, `onDocumentationUninstall()`
- `PluginTooltipEntry(tag, summary /* Tier 1 */, detail /* Tier 2 */, buttons)`
- `PluginTooltipButton(description, uri /* links to Tier 3 */, order, directPath)`
- Per-element tooltip fields: `NavigationItem.tooltipTag`, `MenuItem.tooltipTag`, `TabItem.tooltipTag`, `EditorTabItem.tooltip`
- `com.itsaky.androidide.plugins.services.IdeTooltipService` — `showTooltip(anchorView, [category,] tag)` for tooltips on custom views

### Lifecycle hooks (`com.itsaky.androidide.plugins.IPlugin`)
- `initialize(PluginContext)` — set up state
- `activate()` / `deactivate()`
- `dispose()` — **the place to release threads, observers, sockets, and clear static references**

### Build variants (`com.itsaky.androidide.plugins.build.PluginBuilder$BuildVariant`)
- `DEBUG` → task `assemblePluginDebug`, suffix `-debug`
- `RELEASE` → task `assemblePlugin`, no suffix

The release task produces `build/plugin/<pluginName>.cgp`.
