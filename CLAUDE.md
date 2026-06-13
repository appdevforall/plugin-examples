# CLAUDE.md

Guidance for AI agents (and developers) working in this repository. Auto-loaded into every session here.

## What this repo is

A collection of **reference plugins** for [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) (CoGo) — an on-device Android IDE. Each top-level folder (`Beepy/`, `apk-viewer/`, `markdown-preview/`, `keystore-generator/`, `random-xkcd/`, `ndk-installer-plugin/`, `sketch-to-ui-plugin/`, …) is an **independent Gradle project** that builds a `.cgp` file — the packaged plugin format CoGo installs via its Plugin Manager. The only thing tying the folders together is the shared `libs/` jars at the repo root (see Architecture).

These are **teaching examples** for future plugin authors. Keep them clear, minimal, and well-commented — not architecturally elaborate.

## Shared workflow (the `adfa-agent` plugin)

This repo is a client of the [`common-agent-setup`](https://github.com/appdevforall/common-agent-setup) marketplace. `.claude/settings.json` enables the **`adfa-agent`** plugin, which supplies the shared workflow skills: `plan-for-done`, `check-done`, `prepare-pr`, `android-qa`, `ux-review`, `retro`. If they're missing, run `/plugin install adfa-agent`.

- **Plan first** (plan mode). `plan-for-done` checks the plan against the definition of done before you build.
- **`check-done`**** gates completion.** It (and `plan-for-done`) reads **this repo's ****`REVIEW.md`** — the complete definition of done (the plugin-specific submission gates), nothing from the plugin itself. Don't call a plugin done until `check-done` is green.
- **Conventions** (layering, dependency direction, SRP checks) live in this repo's `architecture.md` (at the repo root). Not restated here.

## Build commands

Build one plugin:

```sh
cd Beepy                          # or any plugin folder
./gradlew assemblePlugin          # release .cgp -> build/plugin/<pluginName>.cgp
./gradlew assemblePluginDebug     # debug variant
```

Rebuild the shared `libs/` jars and then every plugin from scratch:

```sh
./scripts/update-libs.sh                          # default: github.com/appdevforall/CodeOnTheGo@stage
./scripts/update-libs.sh --ref <branch-or-tag>
./scripts/update-libs.sh --local ../CodeOnTheGo   # use an existing checkout instead of cloning
```

The script clones CoGo into `.cache/CodeOnTheGo/` on first run, rebuilds both jars, copies them into `libs/`, then runs `assemblePlugin` for every example (auto-detected by scanning for `build.gradle.kts` files that apply `com.itsaky.androidide.plugins.build`).

Each plugin needs a `local.properties` with `sdk.dir=...` (path to your Android SDK). The committed root `local.properties` is harmless leftover.

> **Always ****`clean`**** before re-staging a debug build.** Re-running `assemblePluginDebug` without `clean` produces a stub `.cgp` — a known plugin-builder bug where the first run deletes the source APK. Use `./gradlew clean assemblePluginDebug`.

## Architecture

### `libs/` is the load-bearing piece

Every plugin depends on two jars in the repo-root `libs/`, referenced via `../libs/*.jar`:

- **`plugin-api.jar`** — the IDE-side API (`IPlugin`, `PluginContext`, `BuildStatusListener`, `IdeBuildService`, …). Each plugin uses it as **`compileOnly`** (compiled against, but not bundled — the IDE provides it at runtime) AND puts it on the **buildscript classpath** so the Gradle plugin can resolve its symbols at configuration time.
  - **Its ABI must match the installed host.** "ABI" here means the exact class/method shapes the host exposes. A plugin built against a stale `plugin-api.jar` whose shapes differ from the running host throws `NoSuchMethodError` / `LinkageError` at startup — and the host's per-plugin `catch` does **not** catch these, so it crashes the IDE. Refresh the jar (via the script) whenever the host API changes.
- **`gradle-plugin.jar`** — provides the Gradle plugin id `com.itsaky.androidide.plugins.build` that every plugin applies. It is the output of CoGo's `plugin-api/plugin-builder/` module and packages the compiled Android library into a `.cgp`.

**A plugin folder is not standalone** — copy `libs/` along if you move one. Refresh the jars via the script above, or via the **Update libs from CodeOnTheGo** GitHub Action (which also commits the jars, cuts a release, and deploys `.cgp` files to the website).

### Plugin module shape

A plugin is an Android **application** module (even though it installs as a library) with:

1. **`build.gradle.kts`** — applies `com.android.application`, `org.jetbrains.kotlin.android`, and `com.itsaky.androidide.plugins.build`. Configures `pluginBuilder { pluginName = "..." }`. Uses `compileOnly(files("../libs/plugin-api.jar"))` — never `implementation`.
2. **`settings.gradle.kts`** — declares the two jars on the buildscript classpath, plus AGP and Kotlin.
3. **`src/main/AndroidManifest.xml`** — declares plugin identity as `<meta-data>` entries on `<application>`: `plugin.id`, `plugin.name`, `plugin.version` (from `${pluginVersion}`), `plugin.description`, `plugin.author`, `plugin.main_class`, `plugin.min_ide_version`, and optional `plugin.permissions`.
4. **Main class** — implements `com.itsaky.androidide.plugins.IPlugin`. Lifecycle: `initialize(PluginContext) → activate() → deactivate() → dispose()`. Access IDE services via `context.services.get(SomeService::class.java)`; the Android `Context` is `context.androidContext`.
  - **In ****`dispose()`****, release everything you acquired** — threads, listeners, sockets — and clear any static references to IDE objects. Leaks that survive an enable/disable cycle are a submission-rubric reject.

**Permission strings** (comma-separated in `plugin.permissions`): `filesystem.read`, `filesystem.write`, `network.access`, `native.code`, `system.commands`, `ide.settings`, `ide.environment.write`, `project.structure`. Request only what you use — over-requesting is a rubric reject.

### Silent-failure gotchas

Each of these fails with **no logcat error** — the plugin simply goes missing from Plugin Manager after restart. Symptom is always the same; the fix differs:

- **Release ****`.cgp`**** is unsigned → host rejects it.** Wire a `signingConfig` (e.g. a `releaseDebugKey` pointing at `~/.android/debug.keystore`).
- **Debug builds trip an icon-asset validator** that release skips. Ship `assets/icon_day.png` + `assets/icon_night.png` (96×96 RGBA) AND declare them as `plugin.icon_day` / `plugin.icon_night` meta-data.
- **Bundling native ****`.so`**** libraries** requires both `plugin.permissions=native.code` AND `android.packaging.jniLibs.useLegacyPackaging = true` (so the `.so` extracts to disk at install time instead of staying compressed in the APK).
- **Plugin Fragments crash when Android restores them by class name** (e.g. on rotation), because the plugin's classes load via `DexClassLoader` — a separate classloader the host's default `FragmentFactory` can't see. Fix: install a plugin-classloader-aware `FragmentFactory` on every host `FragmentManager` that hosts a plugin Fragment. Canonical pattern: `learnings.md` in the agent docs.

### Convention: AAR metadata checks are disabled

Most plugins end with:

```kotlin
tasks.matching {
    it.name.contains("checkDebugAarMetadata") ||
    it.name.contains("checkReleaseAarMetadata")
}.configureEach { enabled = false }
```

This is intentional. AAR metadata checks validate library-module packaging; the `application`-as-library packaging trick used here trips them. Keep the block.

### Asset downloads (rare)

Some plugins (currently `ndk-installer-plugin`) register a `downloadAssets` task that fetches a tarball at build time and verifies it by MD5. `scripts/update-libs.sh` runs that task before `assemblePlugin` when the build file references it.

## Adding a new plugin

1. **Copy ****`random-xkcd/`** — the canonical starting template (small but complete; includes the in-IDE help-HTML pattern submissions are expected to follow).
2. **Update identity** in `settings.gradle.kts` (`rootProject.name`), `build.gradle.kts` (`pluginBuilder { pluginName }` + `android { namespace, applicationId }`), and `src/main/AndroidManifest.xml` (`plugin.id`, `plugin.name`, `plugin.main_class`). Use the `org.appdevforall.<plugin>` namespace.
3. **Add a row** to the README's Examples table.
4. **To ship via the website**, add it to the `MAP` array in `.github/workflows/build-plugins.yml` and `.github/workflows/update-libs.yml`.
5. **Before calling it done**, walk `REVIEW.md` and run the `cogo-plugin-review` skill (below).

## Plugin review skill

`.claude/skills/plugin-review/` is the `cogo-plugin-review` skill — invoke via `/plugin-review` (or `/cotg-plugin-review`). It builds the plugin, audits its security, and scores it against the submission rubric (the `REVIEW.md` §9 gate).
