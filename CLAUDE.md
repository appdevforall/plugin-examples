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

## Git workflow

- **Always work on a branch.** Never commit directly to `main` — branch first (`git switch -c ...`) even for a one-line fix. Working on `main` is almost never right for this repo.
- **Fetch before you diff against `main`.** Any time you compute or reason about a diff against `main` (code review, PR base, "what changed"), run `git fetch origin` first and compare against `origin/main`. A stale local `main` produces phantom findings — a `/code-review` here once flagged 3 issues that were outside the actual PR diff because local `main` was ~50 files behind `origin/main`. When a diff-against-main is requested, suggest fetching first.

## Architecture

### `libs/` is the load-bearing piece

Every plugin depends on two jars in the repo-root `libs/`:

- **`plugin-api.jar`** — the IDE-side API surface (`IPlugin`, `PluginContext`, `BuildStatusListener`, `IdeBuildService`, etc.). Each plugin uses it as `compileOnly` (provided by the IDE at runtime) AND as `buildscript classpath` so the Gradle plugin can resolve symbols at configuration time.
- **`gradle-plugin.jar`** — the Gradle plugin with id `com.itsaky.androidide.plugins.build`, applied by every plugin. It's the output of CoGo's `plugin-api/plugin-builder/` module (separate from CoGo's `gradle-plugin/` module, which is unrelated despite the name). It packages the compiled Android library into a `.cgp`.

There is also **one shared Gradle wrapper at the repo root** (`gradlew` + `gradle/wrapper/`). New plugins should use it — build them with `cd <plugin> && ../gradlew assemblePlugin` rather than bundling a per-plugin `gradlew`/`gradle/wrapper/` copy. (`flutter-template` follows this; most older plugins still carry their own local wrapper and can be migrated opportunistically.)

Both jars are referenced via `../libs/*.jar`. **Always use the repo-root `libs/` jars and the repo-root Gradle wrapper — never bundle per-plugin copies.** A plugin that ships its own `libs/plugin-api.jar` / `libs/gradle-plugin.jar` (e.g. copied from another plugin) can drift out of sync with the rest of the repo; point `build.gradle.kts` (`compileOnly`) and `settings.gradle.kts` (buildscript `classpath`) at `../libs/*.jar` and delete any local `libs/`. The root `plugin-api.jar` already carries the full API surface (including `IdeTemplateService`/`CgtTemplateBuilder`), so newer sub-APIs do not justify a local copy. **A plugin folder is not standalone in isolation** — copy the root `libs/` along if you move one elsewhere. When CoGo's API changes, refresh via the script above or the **Update libs from CodeOnTheGo** GitHub Action (which also commits the refreshed jars, cuts a release, and deploys `.cgp` files to the website).

### Plugin shape

A plugin is an Android *application* module (despite installing as a library) with:

1. **`build.gradle.kts`** applies `com.android.application`, `org.jetbrains.kotlin.android`, and `com.itsaky.androidide.plugins.build`. Configures `pluginBuilder { pluginName = "..." }`. Uses `compileOnly(files("../libs/plugin-api.jar"))` — never `implementation`.
2. **`settings.gradle.kts`** declares the two jars on the buildscript classpath plus AGP and Kotlin.
3. **`src/main/AndroidManifest.xml`** declares plugin identity as `<meta-data>` entries on `<application>`: `plugin.id`, `plugin.name`, `plugin.version` (resolved from `${pluginVersion}`), `plugin.description`, `plugin.author`, `plugin.main_class`, `plugin.min_ide_version`, and optional `plugin.permissions`.
4. **Main class** implements `com.itsaky.androidide.plugins.IPlugin`. Lifecycle: `initialize(PluginContext) → activate() → deactivate() → dispose()`. Services are obtained via `context.services.get(SomeService::class.java)` (e.g. `IdeBuildService` for build hooks). Android `Context` is `context.androidContext`.

Available permission strings (declared comma-separated in `plugin.permissions`): `filesystem.read`, `filesystem.write`, `network.access`, `system.commands`, `ide.settings`, `project.structure`.

### In-app help wiring (tooltips + Tier 3, `DocumentationExtension`)

Every plugin with UI implements `com.itsaky.androidide.plugins.extensions.DocumentationExtension`. This wiring is fixed and foundational — get **all** of it right or the tooltip renders the literal string **`n/a`** at runtime. The build stays green and the manifest looks fine, so **only device long-press testing catches a mistake** (this bit us once). All symbols are in `plugin-api.jar`.

1. **Category is `"plugin_<pluginId>"` — exactly.** `getTooltipCategory()` MUST return `"plugin_"` + the full `plugin.id` (e.g. `"plugin_org.appdevforall.templatemanagerplugin"`). The host registers your entries under this string **and** derives the same string when resolving a lookup. Any other value — a short slug, a dotless/underscore form — silently mismatches → `n/a`.
2. **Entries.** `getTooltipEntries()` returns `PluginTooltipEntry(tag, summary, detail, buttons)`: `summary` = Tier 1 (one line shown on long-press), `detail` = Tier 2 (HTML behind "See more"). Keep the `tag` in one shared `const val` used by steps 3–4.
3. **Look tooltips up with the 3-arg overload.** Call `IdeTooltipService.showTooltip(anchorView, category, tag)` and pass `category = "plugin_<pluginId>"` explicitly. **Never use the 2-arg `showTooltip(view, tag)`** — it resolves under a different default category and renders `n/a` even when the entry is registered correctly. Param order is `(anchorView, category, tag)`.
4. **Attach tags to UI.** Set `tooltipTag = <that same tag>` on every contributed `NavigationItem` / `TabItem` / menu item / FAB; `EditorTabItem` instead takes a literal `tooltip = "..."` string. A contributed element with no tooltip fails review clause 6.7.
5. **Tier 3 (offline page).** Override `getTier3DocsAssetPath()` to return an assets subdir name (convention: `"docs"`), ship real HTML at `src/main/assets/<dir>/index.html` (white background, black text, English), and link it from an entry via `PluginTooltipButton(description, uri = "index.html", order = 0)` — leave `directPath` false (`true` targets the host's shared docs tree, not your bundle).

Debug a mismatch against the on-device store (`adb root` first):
`sqlite3 /data/data/com.itsaky.androidide/databases/documentation.db "SELECT c.category, t.tag, substr(t.summary,1,40) FROM Tooltips t JOIN TooltipCategories c ON c.id=t.categoryId WHERE c.category LIKE 'plugin_%'"`. If the row is present but the tooltip still shows `n/a`, the bug is the **lookup** (step 1 or 3), not registration. (The unused `ide_tooltip_table` is a red herring — plugin entries live in `Tooltips` + `TooltipCategories`.)

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

Some plugins (`ndk-installer-plugin`, `ai-literacy-course`) register a `downloadAssets` task that fetches large files at build time with pinned-MD5 verification. These assets are **not committed to git** (e.g. `ai-literacy-course` pulls a ~110 MB course ZIP plus `pdfjs.zip`). `scripts/update-libs.sh` runs `downloadAssets` automatically before `assemblePlugin` when the build file references it.

**Gotcha: a bare `./gradlew assemblePlugin` does NOT run `downloadAssets` and does not warn when the assets are missing** — it silently packages a broken `.cgp` (e.g. a course with no PDF viewer). When building such a plugin by hand, run `./gradlew downloadAssets assemblePlugin` (or the script), and confirm the expected files exist under `src/main/assets/` (or `unzip -l` the `.cgp`) before trusting it.

### One-time on-device install markers

Plugins that extract bundled assets on-device once (currently `ai-literacy-course`, via `CourseInstaller`) gate the work behind a marker file named from a version constant (`INSTALL_VERSION` → `.installed-vN`). If the marker for the current version exists, extraction **and** any post-extraction generation (e.g. `CourseShell.generate()`) are skipped entirely.

**Any change to extraction OR post-extraction generation logic must bump `INSTALL_VERSION`.** Otherwise the change compiles and packages cleanly but has zero effect on existing installs — they keep the stale extracted tree, and it looks like "my fix didn't work" (costing a device round-trip). Bumping the constant forces a clean re-extract. On a device with a prior install, confirm the marker version changed (or wipe plugin data) before concluding a fix works — see Verification below.

### Template-installer plugins (Pebble `.cgt`)

Some plugins are headless template installers (`flutter-template`, `pebble-custom-function-template-installer`): on `activate()` they register project templates with `IdeTemplateService` (building each `.cgt` from Pebble `.peb` skeletons under `src/main/assets/templates/<Variant>/`), and unregister on `deactivate()`. The templates then appear on the New Project screen beside the core ones. The source-of-truth skeletons follow the pattern in `~/src/dev-assets/templates`.

**Pebble gotcha:** a bare `${{TAG}}` at end-of-line loses its trailing newline to Pebble's newline-trimming and merges with the next line (this silently produced invalid YAML by collapsing `name:` and `description:` into one line). Ensure a non-newline character follows `}}` — the convention is to quote the value, e.g. `name: "${{APP_NAME | lower}}"`.

## Verification

**`./gradlew assemblePlugin` succeeding is not verification** — it only proves the plugin compiles and packages. Real verification for these plugins is device-level: push the built `.cgp` to a connected emulator/device, install through CoGo's Plugin Manager, exercise the feature end-to-end, and observe the expected behavior (UI element appears, file written, build hook fires, DB row replaced, etc.).

If device verification isn't possible in-session, say so explicitly rather than calling the change verified. Build success is necessary but never sufficient — this applies especially to plugins that mutate IDE state (`documentation.db`, settings, filesystem, project structure).

**Launching CoGo via adb.** Do **not** launch with `monkey` or a bare LAUNCHER intent (`adb shell monkey -p com.itsaky.androidide …`) — debug builds bundle **LeakCanary**, which registers its own launcher activity, so the intent can open LeakCanary's "Leaks" screen or a disambiguation chooser instead of the IDE. Start the explicit component: `adb shell am start -n com.itsaky.androidide/.activities.SplashActivity`. When re-verifying a plugin **icon** change under the same plugin id, note that the Plugin Manager caches icons via Glide (`cache/image_manager_disk_cache`) keyed by path without mtime invalidation — the old icon persists until that cache is cleared (`adb root`, delete the dir, restart) or you install on a clean device.

## Adding a new plugin

1. Copy `random-xkcd/` — it's the canonical starting template (small but complete, includes the in-IDE help HTML pattern that submissions are expected to follow).
2. Update `settings.gradle.kts` `rootProject.name`, `build.gradle.kts` `pluginBuilder { pluginName }` + `android { namespace, applicationId }`, and `src/main/AndroidManifest.xml` (`plugin.id`, `plugin.name`, `plugin.main_class`).
3. Add a row to the README's Examples table.
4. If your plugin should ship via the website, add it to the `MAP` array in `.github/workflows/update-libs.yml` so the filename mapping picks it up. (`.github/workflows/build-plugins.yml` needs no change — it auto-discovers plugins by scanning `*/build.gradle.kts` for the plugin-builder Gradle plugin.)

## Plugin review skill

`.claude/skills/plugin-review/` contains the `cogo-plugin-review` skill — invoke via `/plugin-review` or `/cotg-plugin-review` when the user asks to review, audit, or check a CoGo plugin for submission readiness. It builds, audits security, and scores against the submission rubric.

**Proactively offer `/plugin-review`** (don't wait for the user to ask) after any substantive change to a plugin: importing a new plugin folder, modifying dependencies, touching the `IPlugin`/`PluginContext` API surface, adding shipped assets, or updating `libs/`. It has caught real defects (resource leaks, missing manifest entries, missing in-IDE help HTML) that aren't visible from a clean `assemblePlugin` build.
