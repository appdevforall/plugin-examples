# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository purpose

Reference plugins for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) (CoGo / CotG). Each top-level folder (`Beepy/`, `apk-viewer/`, `markdown-preview/`, `keystore-generator/`, `snippets/`, `random-xkcd/`, `icons-repository/`, `ndk-installer-plugin/`, `sketch-to-ui-plugin/`) is an independent Gradle project that builds a `.cgp` plugin installable via the CoGo Plugin Manager. They're held together only by the shared `libs/` jars at the repo root.

## Common commands

Build one plugin:

```sh
cd Beepy   # or any plugin folder
./gradlew assemblePlugin           # release .cgp -> build/plugin/<pluginName>.cgp
./gradlew assemblePluginDebug      # debug variant
```

Build every plugin from scratch (after rebuilding libs):

```sh
./scripts/update-libs.sh                          # default: github.com/appdevforall/CodeOnTheGo@stage
./scripts/update-libs.sh --ref <branch-or-tag>
./scripts/update-libs.sh --local ../CodeOnTheGo   # use an existing checkout instead of cloning
```

The script clones CoGo into `.cache/CodeOnTheGo/` on first run, rebuilds both jars, copies them into `libs/`, then runs `assemblePlugin` for every example. It auto-detects examples by scanning for `build.gradle.kts` files that apply `com.itsaky.androidide.plugins.build`.

`local.properties` must contain `sdk.dir=...`. The committed `local.properties` at the repo root is harmless leftover; each plugin needs its own.

## Architecture

### `libs/` is the load-bearing piece

Every plugin depends on two jars in the repo-root `libs/`:

- **`plugin-api.jar`** — the IDE-side API surface (`IPlugin`, `PluginContext`, `BuildStatusListener`, `IdeBuildService`, etc.). Each plugin uses it as `compileOnly` (provided by the IDE at runtime) AND as `buildscript classpath` so the Gradle plugin can resolve symbols at configuration time.
- **`gradle-plugin.jar`** — the Gradle plugin with id `com.itsaky.androidide.plugins.build`, applied by every plugin. It's the output of CoGo's `plugin-api/plugin-builder/` module (separate from CoGo's `gradle-plugin/` module, which is unrelated despite the name). It packages the compiled Android library into a `.cgp`.

Both jars are referenced via `../libs/*.jar`. **A plugin folder is not standalone in isolation** — copy `libs/` along if you move one elsewhere. When CoGo's API changes, refresh via the script above or the **Update libs from CodeOnTheGo** GitHub Action (which also commits the refreshed jars, cuts a release, and deploys `.cgp` files to the website).

### Plugin shape

A plugin is an Android *application* module (despite installing as a library) with:

1. **`build.gradle.kts`** applies `com.android.application`, `org.jetbrains.kotlin.android`, and `com.itsaky.androidide.plugins.build`. Configures `pluginBuilder { pluginName = "..." }`. Uses `compileOnly(files("../libs/plugin-api.jar"))` — never `implementation`.
2. **`settings.gradle.kts`** declares the two jars on the buildscript classpath plus AGP and Kotlin.
3. **`src/main/AndroidManifest.xml`** declares plugin identity as `<meta-data>` entries on `<application>`: `plugin.id`, `plugin.name`, `plugin.version` (resolved from `${pluginVersion}`), `plugin.description`, `plugin.author`, `plugin.main_class`, `plugin.min_ide_version`, and optional `plugin.permissions`.
4. **Main class** implements `com.itsaky.androidide.plugins.IPlugin`. Lifecycle: `initialize(PluginContext) → activate() → deactivate() → dispose()`. Services are obtained via `context.services.get(SomeService::class.java)` (e.g. `IdeBuildService` for build hooks). Android `Context` is `context.androidContext`.

Available permission strings (declared comma-separated in `plugin.permissions`): `filesystem.read`, `filesystem.write`, `network.access`, `system.commands`, `ide.settings`, `project.structure`.

### Convention: AAR metadata checks are disabled

Most plugins end with:

```kotlin
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
```

This is intentional — the `application`-as-library packaging trips those checks. Keep it.

### Asset downloads (rare)

Some plugins (currently `ndk-installer-plugin`) register a `downloadAssets` task that fetches a tarball at build time with MD5 verification. `scripts/update-libs.sh` runs it automatically before `assemblePlugin` when the build file references it.

## Verification

**`./gradlew assemblePlugin` succeeding is not verification** — it only proves the plugin compiles and packages. Real verification for these plugins is device-level: push the built `.cgp` to a connected emulator/device, install through CoGo's Plugin Manager, exercise the feature end-to-end, and observe the expected behavior (UI element appears, file written, build hook fires, DB row replaced, etc.).

If device verification isn't possible in-session, say so explicitly rather than calling the change verified. Build success is necessary but never sufficient — this applies especially to plugins that mutate IDE state (`documentation.db`, settings, filesystem, project structure).

## Adding a new plugin

1. Copy `random-xkcd/` — it's the canonical starting template (small but complete, includes the in-IDE help HTML pattern that submissions are expected to follow).
2. Update `settings.gradle.kts` `rootProject.name`, `build.gradle.kts` `pluginBuilder { pluginName }` + `android { namespace, applicationId }`, and `src/main/AndroidManifest.xml` (`plugin.id`, `plugin.name`, `plugin.main_class`).
3. Add a row to the README's Examples table.
4. If your plugin should ship via the website, add it to the `MAP` array in `.github/workflows/update-libs.yml` so the filename mapping picks it up. (`.github/workflows/build-plugins.yml` needs no change — it auto-discovers plugins by scanning `*/build.gradle.kts` for the plugin-builder Gradle plugin.)

## Plugin review skill

`.claude/skills/plugin-review/` contains the `cogo-plugin-review` skill — invoke via `/plugin-review` or `/cotg-plugin-review` when the user asks to review, audit, or check a CoGo plugin for submission readiness. It builds, audits security, and scores against the submission rubric.

**Proactively offer `/plugin-review`** (don't wait for the user to ask) after any substantive change to a plugin: importing a new plugin folder, modifying dependencies, touching the `IPlugin`/`PluginContext` API surface, adding shipped assets, or updating `libs/`. It has caught real defects (resource leaks, missing manifest entries, missing in-IDE help HTML) that aren't visible from a clean `assemblePlugin` build.
