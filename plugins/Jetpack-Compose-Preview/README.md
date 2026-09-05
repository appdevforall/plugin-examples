# Jetpack Compose Preview

A plugin for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) that renders Jetpack Compose `@Preview` composables **on-device** — it compiles and runs the previews inside the IDE, without a full app build-and-run.

Surfaces as a preview action in the editor toolbar, shown only when a Kotlin file containing `@Preview` is open (on those files it also hides the built-in XML layout-preview action, so there's a single preview entry point). Tapping it opens a **Jetpack Compose Preview** screen that renders every `@Preview` in the file, honoring light/dark, `@PreviewParameter`, and per-preview background and size.

## Building

```sh
cd compose-preview
./gradlew assemblePlugin
```

The `.cgp` lands in `build/plugin/`. Install it from inside CodeOnTheGo via the Plugin Manager.

## How it works

- **Toolbar integration** — contributes an `EDITOR_TOOLBAR` action via `UIExtension.getToolbarActions()`, gated to Compose files by an `isVisibleProvider`. `getHiddenToolbarActionIds()` hides the host's XML preview action while a Compose file is open.
- **Render pipeline** — parses the file's `@Preview` functions, compiles the current editor buffer with the bundled Compose toolchain, dexes it, loads it through a child `DexClassLoader`, and invokes each composable into a host-`Activity`-backed `ComposeView`. Supports **All** (labelled cards) and **Single** display modes, `@PreviewParameter` value expansion, and per-`@Preview` `showBackground` / `widthDp` / `heightDp` / `uiMode`.
- **Project context** — resolves the module's classpaths through the official `IdeProjectService` (`getModuleContext`). When compiled intermediates are missing it shows a **Build required** screen whose button triggers a Gradle build via `IdeBuildService`, then re-renders.
- **Bundled toolchain** — `src/main/assets/compose/compose-jars.zip` carries the Compose compiler + runtime jars used to compile and run previews on-device (the host no longer ships them after extraction). Kept uncompressed in the `.cgp` (`noCompress += "zip"`).
- **Docs & tooltips** — implements `DocumentationExtension` to register tiered tooltip entries; Tier-3 HTML is served from `src/main/assets/docs/`.
- **UI** — Views + ViewBinding (`fragment_compose_preview.xml`), MVVM (`ComposePreviewViewModel`).

## Dependencies from `libs/`

- `../libs/plugin-api.jar` — the plugin API surface (compile-only; provided by the IDE at runtime).
- `../libs/gradle-plugin.jar` — the `com.itsaky.androidide.plugins.build` Gradle plugin that packages the `.cgp`.

Unlike most example plugins, this one **bundles Jetpack Compose itself** (`compose-bom` as `implementation`) into the `.cgp` and ships the on-device compile toolchain under `assets/`.

> **Note:** this plugin requires the extended `plugin-api` — `IdeProjectService.getModuleContext`, `UIExtension.getHiddenToolbarActionIds`, `ToolbarAction.isVisibleProvider`, and `IdeBuildService.executeTasks`. If `assemblePlugin` fails with unresolved references to those, the shared `libs/` jars are older than the API this plugin needs; refresh them from a CodeOnTheGo build that includes the extensions (`../scripts/update-libs.sh --local <path-to-CodeOnTheGo>`).
