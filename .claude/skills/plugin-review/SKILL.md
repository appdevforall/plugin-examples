---
name: cogo-plugin-review
description: Review a Code on the Go (CoGo or cotg) plugin project for submission readiness — verify the assemblePlugin build, audit actual security risks, and score the code against the submission rubric (compatibility, resource discipline, build reproducibility, native binaries, reflection ban, html documentation, tooltips and in-app help, manifest completeness). Use when the user asks to review, audit, or check a Code On The Go plugin repo.
metadata:
  author: Hal Eisen
  keywords:
  - codeonthego
  - cotg
  - plugin
  - submission-review
  - cgp
---

## When to invoke

Triggered by requests like "review this CotG plugin", "does this plugin build", or any plugin project that shows these markers:

- `build.gradle.kts` applies `com.itsaky.androidide.plugins.build`
- `libs/plugin-api.jar` and/or `libs/gradle-plugin.jar` present
- `src/main/AndroidManifest.xml` uses `plugin.*` meta-data keys (`plugin.id`, `plugin.main_class`, `plugin.permissions`, etc.)
- Main class implements `com.itsaky.androidide.plugins.IPlugin`

If none match, stop and tell the user this doesn't look like a CoGo plugin.

## Workflow

Run the four phases in order. Each phase produces a section of the final report.

### Phase 1 — Verify `assemblePlugin` builds

The submission artifact is a `.cgp` file produced by the `assemblePlugin` Gradle task (added by the IDE's `com.itsaky.androidide.plugins.build` plugin; `assemblePluginDebug` exists for debug variant).

1. Ensure `local.properties` exists with `sdk.dir=<path>`. Resolve from `$ANDROID_HOME`, `$ANDROID_SDK_ROOT`, `~/Library/Android/sdk` (macOS), or `~/Android/Sdk` (Linux). Create if missing.
2. `chmod +x gradlew` if needed, then run `./gradlew assemblePlugin`. Allow up to 10 minutes for first-time SDK component downloads.
3. Confirm the artifact path printed by the task (typically `build/plugin/<pluginName>.cgp`).
4. Note any of the following for the report:
   - Compile warnings (deprecated API, unchecked, etc.)
   - SDK components auto-installed (build-tools / platforms)
   - Version skew (e.g. `kotlin-stdlib` newer than the Kotlin Gradle plugin)
   - `buildToolsVersion` / `compileSdk` that exceed what's commonly pre-installed
   - Disabled checks (`tasks.matching { ... }.enabled = false` blocks)

If the build fails: diagnose the root cause. Do not suggest `--no-verify`, disabling checks, or downgrading to make it pass.

### Phase 2 — Audit actual security risks

These are real-world risks, separate from rubric violations. Check each:

- **Committed signing material**: search the working tree (not just `.gitignore`) for `*.keystore`, `*.jks`, `*.p12`, `*.pem`, `release.properties`, `signing.properties`, `keystore.properties`. Confirm against `.gitignore` — the standard Android template comments out `*.jks` / `*.keystore` rules, so check whether they're active. Plaintext `storePassword` / `keyPassword` in any committed file is a leaked signing identity.
- **Other plaintext credentials**: `grep -rIE 'password|secret|token|api[_-]?key' --include='*.properties' --include='*.kts' --include='*.kt' --include='*.java' --include='*.xml'` and triage.
- **XXE**: `XmlPullParserFactory`, `DocumentBuilderFactory`, `SAXParserFactory`, `TransformerFactory`, `SchemaFactory` used on user-supplied XML without `setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)` or explicitly disabling external entities. Regex-based extraction does not introduce XXE.
- **Zip Slip**: `ZipInputStream` / `ZipFile` reads that write `entry.getName()` to disk without verifying the resolved path is `startsWith(destDir.getCanonicalPath() + File.separator)`. Reading entries to in-memory `byte[]` is safe.
- **Path traversal from UI**: user-typed filenames passed to `new File(parent, name)` must be sanitized (e.g. replace `[^a-zA-Z0-9._-]` → `_`) or rejected by a validator before write. A sanitizer that allows `.` is fine since `..` becomes `..` only after `/` collapse — verify the actual regex.
- **Insecure URLs**: `http://` schemes in code paths (Maven repo URLs aren't user-traffic and are out of scope here).
- **`mavenLocal()`** in `settings.gradle.kts` `dependencyResolutionManagement` — supply-chain risk: any locally cached artifact can shadow the published version.
- **Unsafe regex on user input**: catastrophic backtracking patterns.
- **Reflection bypassing API boundaries** — this is also rubric clause 6.6, but if reflection accesses `private`/`@hide` fields of IDE classes it's a security concern as well as a rubric fail.

Each finding gets: file:line, what's wrong in one line, concrete fix.

### Phase 3 — Score against the rubric

See [references/RUBRIC.md](references/RUBRIC.md) for the verbatim criteria. Score each clause **Pass / Partial / Fail** with file:line evidence.

#### 6.1 Compatibility — must support current stable + immediately preceding minor
- Search https://github.com/appdevforall/CodeOnTheGo/releases for the most recent YY.WW release where YY is the 2-digit year and WW is the week-of-the-year. The plugin must support the current release. Ignore releases related to "plugin-api".
- Check `AndroidManifest.xml` for both `plugin.min_ide_version` AND `plugin.max_ide_version` (or whatever range key the API exposes — verify in `plugin-api.jar`'s `PluginMetadata` class).
- Read `README.md`. If it advertises a single point release as the only supported version, that's the explicit reject pattern, even if the manifest range is wider.
- A min-only declaration with no upper bound is **Partial**, not Pass — the rule wants a *range*.

#### 6.2 Resource discipline — four sub-checks
Search the source tree:
- `Executors.new*`, `new Thread`, `GlobalScope.launch`, `CoroutineScope(...)`, `Handler` with a non-main looper → every one must have a matching `shutdown()` / `shutdownNow()` / `cancel()` in `dispose()` or the equivalent lifecycle teardown.
- `static` mutable fields. Any that hold `Context`, `View`, `PluginContext`, IDE service interfaces, or transitively reach them → must be nulled in `dispose()`. A `static volatile Main instance` set in `onInitialized` and never cleared is the canonical fail.
- `BasePlugin.dispose()` (or equivalent) implementation — if it's `{}` and the plugin registers anything stateful, that's a fail.
- `InputStream` / `OutputStream` / `ZipInputStream` / sockets → must be try-with-resources. Long-lived sockets need explicit close in lifecycle teardown.
- LSP plugins: `ProcessBuilder` / `Runtime.exec` children must be terminated when the workspace closes (look for `Process.destroy()` / `destroyForcibly()` wiring).

#### 6.3 Build reproducibility
- `README.md` must document the build command (`./gradlew assemblePlugin`). No mention = **Partial** at best.
- `settings.gradle.kts` must NOT include `mavenLocal()` in `dependencyResolutionManagement` (it's also iffy in `pluginManagement`).
- All dependency versions pinned — no `+`, `latest.release`, or unbounded ranges.
- Phase 1 already verified the build works from a clean checkout. Cite the result here.

#### 6.4 Native binaries
- `find src -name '*.so'`, look for `jniLibs/`, `externalNativeBuild`, NDK config.
- If native code present → `plugin.permissions` MUST include `native.code`.
- If absent → `native.code` MUST NOT be requested.

#### 6.5 No reflection into IDE internals
- `grep -rE 'java\.lang\.reflect|Class\.forName|getDeclaredField|getDeclaredMethod|getDeclaredConstructor|setAccessible' src --include='*.java' --include='*.kt'`
- Zero hits = Pass.
- Reflection limited to the plugin's own classes is acceptable — fail only when the target is `com.itsaky.androidide.*` or other IDE-internal types.

#### 6.6 Must have a html documentation file
- Must have the following sections: executive overview, core functionality, technical architecture, usage, key benefits
- May have other sections. If other sections are present, ensure they are relevant and correct
- Must have a white background and body text must be black.
- Must be written in English
- Must be at the top-level of the plugin. Must have the same name as the plugin with the .html extension

#### 6.7 Tooltips and in-app help
Code On The Go has a three-tier in-IDE help model: Tier 1 (brief) and Tier 2 (more detail) are tooltips; Tier 3 is a full offline web page reached from a button on the tooltip. Plugins participate through `DocumentationExtension` (all symbols verifiable in `plugin-api.jar`). This is separate from the 6.6 install-decision page — grade them independently.

- **Implements the extension**: confirm the main class (or one of the plugin's classes) implements `DocumentationExtension`. `grep -rlE 'DocumentationExtension' src --include='*.kt' --include='*.java'`. Absent → **Fail** (no in-app help at all).
- **Every UI element has a tooltip**: reuse the extension enumeration from *Manifest completeness* below. For each contributed `NavigationItem` / `MenuItem` / `TabItem` / FAB / toolbar action, confirm `tooltipTag` is set; for each `EditorTabItem`, confirm `tooltip` is set. Any custom `View` the plugin shows (dialog, fragment, bottom sheet) must be wired to the tooltip system (`IdeTooltipService.showTooltip(...)` or `View.displayTooltipOnLongPress(...)`). One or more contributed elements with no tooltip → **Fail** ("all UI elements" is the bar); a stray non-interactive view missing one → **Partial**.
- **No dangling tags**: collect every `tooltipTag`/`tooltip` value used on UI elements and every `PluginTooltipButton` referenced, then confirm each has a matching `PluginTooltipEntry.tag` returned from `getTooltipEntries()`. A tag with no entry (or an entry with empty `summary`) → **Fail**. `getTooltipCategory()` should return the `plugin_<pluginId>` form.
- **Tier 1 + Tier 2 present**: each `PluginTooltipEntry` should supply a non-empty `summary` (Tier 1) and a `detail` (Tier 2). Summary-only entries across the board → **Partial**.
- **Complete in-app help (Tier 3)**: `getTier3DocsAssetPath()` returns non-null AND the named `assets/<path>/` directory exists with real HTML content (not a stub), and at least one `PluginTooltipButton.uri` links into it. Tier 3 must be served locally/offline — a button that only opens a public `https://` URL is not in-app help. Missing or stub Tier 3 → **Fail**; present but thin/partial coverage → **Partial**.
- Tooltips are wired in code via `DocumentationExtension`, **not** through an `AndroidManifest` `plugin.*` key — do not flag the absence of a manifest entry for documentation.
- Content must be English and readable (light background, dark text), same as 6.6.

#### Manifest completeness
- **Every extension**: enumerate what the main class returns from `getMainEditorTabs()`, `getSideMenuItems()`, `getMainMenuItems()`, `getFabActions()`, `getToolbarActions()`, `getContextMenuItems()`, etc. Each contributed extension type must have a corresponding `plugin.*` meta-data entry (e.g. `plugin.sidebar_items`, `plugin.editor_tabs`). Returning items from a method without declaring the corresponding manifest entry is a fail.
- **Every permission**: every `plugin.*` permission actually used by code paths must appear in `plugin.permissions` (comma-separated). Cross-reference against the permission keys enumerated in `plugin-api.jar`'s `PluginPermission` enum (currently: `filesystem.read`, `filesystem.write`, `network.access`, `system.commands`, `ide.settings`, `ide.environment.write`, `project.structure`, `native.code`).
- **IDE version range**: both `plugin.min_ide_version` and `plugin.max_ide_version` present.
- **minSdk**: either `<uses-sdk>` in the source manifest or `minSdk =` in `build.gradle.kts` (AGP merges the latter into the final manifest, which is conventional and acceptable).

### Phase 4 — Compose the report

One combined report, three sections:

1. **Build** — pass/fail, artifact path, notable warnings, environment quirks.
2. **Security risks** — numbered findings, each with file:line + remediation. If clean, say so explicitly.
3. **Rubric scorecard** — table:

   | Clause | Verdict | Evidence |
   |---|---|---|
   | 6.1 Compatibility | Pass/Partial/Fail | file:line |
   | 6.2 Resource discipline | … | … |
   | 6.3 Build reproducibility | … | … |
   | 6.4 Native binaries | … | … |
   | 6.5 No reflection | … | … |
   | 6.6 Html documentation | … | … |
   | 6.7 Tooltips & in-app help | … | … |
   | Manifest declarations | … | … |

End with **Overall verdict**: green-light / conditional (list blockers) / block. A Fail on any clause is a blocker; a Partial is conditional.

## Mandatory rules

- Do not modify the plugin's source unless the user explicitly asks for fixes.
- Cite `file:line` for every finding. No bare assertions.
- Distinguish rubric violations from actual security risks — they can overlap but are graded separately and reported in separate sections.
- Never disable Gradle checks or skip hooks to make a build pass; diagnose root cause instead.
- If a check is N/A (e.g. no LSP server in this plugin), say so explicitly rather than silently omitting it.
- Don't recommend "add a CI workflow", "add unit tests", "write more docs", or other scope expansion beyond what the rubric requires.
