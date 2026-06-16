# ADFA-3834 — Dependency Analysis plugin: implementation handoff

**Status:** skeleton scaffolded and committed; **core logic is stubbed.** This doc is the
authoritative spec to finish it. Read it top to bottom before writing code.

- **Ticket:** ADFA-3834 — Add a CoGo plugin that runs the autonomousapps Dependency Analysis
  Gradle Plugin (DAGP) `:buildHealth`, reports unused / undeclared-transitive / misconfigured
  dependencies, and applies fixes via `:fixDependencies`.
- **Module:** `dependency-analysis/` (namespace `org.appdevforall.dependencyanalysis`).
- **Definition of done:** this repo's `REVIEW.md` (core gates 1–7 + plugin gates 1–9). Gate it
  with the `check-done` skill before declaring done; run `/plugin-review` before PR.
- **Product decisions (locked):** see `dependency-analysis/decisions.md`. Summary: analyze **and**
  fix in v1; fix is **apply-all** (delegate to `:fixDependencies`, no per-item select); **no
  pre-apply diff** — confirm → run → post-apply summary; single editor tab.

---

## ⚠️ Environment caveat that broke the first attempt

A prior agent run **stalled on `./gradlew assemblePlugin`** (no-progress timeout). Do **not** run an
unbounded Gradle build inline and block on it. Build with a **hard timeout in the background** and
poll, e.g.:

```sh
cd dependency-analysis
timeout 1200 ./gradlew --no-daemon clean assemblePlugin   # run backgrounded, then poll the log
```

If a build genuinely can't complete in this environment, record that verbatim and hand the
on-device/build verification back — don't fake a green build.

---

## What already exists (do not rebuild — reuse)

Good and final unless a task below says otherwise:

- **Module + build:** `settings.gradle.kts`, `build.gradle.kts` (JaCoCo wired — first in the repo:
  `jacocoTestReport` over `testDebugUnitTest`, XML+HTML, excludes `ui/**` + generated classes),
  `gradle.properties`, wrapper, `proguard-rules.pro`.
- **Manifest** (`src/main/AndroidManifest.xml`) — identity, `plugin.author = "App Dev For All"`,
  `plugin.min_ide_version = 1.0.0` (justified), `plugin.permissions = system.commands,filesystem.read`
  (justified in `decisions.md`), icon meta-data.
- **Icons** — `src/main/assets/icon_day.png` / `icon_night.png`, md5 differs from random-xkcd ✓.
- **Domain models** (`domain/AnalysisModels.kt`) — `DependencyCoordinate`, `UnusedDependency`,
  `UndeclaredTransitive`, `WrongConfiguration`, `AnalysisResult` (with `totalAdviceCount`/`isClean`),
  `FixSummary`. **These are the contract — keep the shapes.**
- **Runner contract** (`data/GradleAnalysisRunner.kt`) — `interface GradleAnalysisRunner` +
  `AnalysisProgress` / `AnalysisOutcome` / `FixOutcome` sealed types. **Implement against this.**
- **DAGP constants** (`data/DagpConfig.kt`) — version `3.15.0`, task paths, report path, offline-repo
  asset dir, plugin class, timeout.
- **Plugin entry** (`DependencyAnalysisPlugin.kt`) — `IPlugin + UIExtension + DocumentationExtension`,
  one tab (`TAB_ID`), tiered tooltip help, `getTier3DocsAssetPath() = "docs"`. Largely done.
- **Resources** — `res/values/{strings,colors,styles,dimens}.xml` + `values-night/colors.xml` in the
  BlueWave idiom (reuse `PluginButton.*`, `PluginCard`, `Plugin.Text.*`, `ThemeOverlay.Plugin`,
  `PluginBottomSheetDialog`); drawables `drag_handle`, `divider_hairline`, `ic_check`.
- **Docs** — `dependency-analysis/decisions.md`, tiered HTML help under `src/main/assets/docs/`,
  top-level help HTML, `THIRD_PARTY_LICENSES.txt` (placeholder — must be completed, see Task 2),
  README roster row, `update-libs.yml` MAP entry.

## What is STUBBED (the work to do)

1. `domain/AdviceParser.parse(json)` → `TODO`, returns `AnalysisResult.EMPTY`. (`diff()` is real.)
2. `data/GradleAnalysisRunner` — **interface only; no implementation class.** No init-script
   generation, no bundled offline repo asset.
3. `ui/DependencyAnalysisFragment` + `res/layout/fragment_dependency_analysis.xml` — intro state
   only; **no running/results/clean/setup/error states, no Analyze/Fix wiring, no fix sheets.**
4. Tests — only model/`diff` tests; **`parse` is uncovered → ≥90% gate unmet.** No fixtures.
5. Registration: `update-libs.yml` MAP entry is done. `build-plugins.yml` **auto-discovers** plugin
   modules (no MAP), so no edit is needed there — confirm the module isn't in that workflow's
   `SKIP_PLUGINS`. (REVIEW.md gate 5 says "both MAPs", but the current `build-plugins.yml` has no
   MAP; auto-discovery covers it.)

---

## Tasks (with acceptance)

### Task 1 — Domain: `AdviceParser.parse` + fixture-driven tests  *(blocks everything; do first)*

DAGP's aggregated report (`build/reports/dependency-analysis/build-health-report.json`,
`getFinalAdvicePathV2`) is a JSON **array of `ProjectAdvice`**:

```jsonc
// ProjectAdvice: { projectPath, dependencyAdvice: Advice[], pluginAdvice, moduleAdvice, warning, shouldFail }
// Advice:        { coordinates: {...}, fromConfiguration: String?, toConfiguration: String? }
[
  {
    "projectPath": ":app",
    "dependencyAdvice": [
      { "coordinates": { "identifier": "com.squareup.okhttp3:okhttp", "resolvedVersion": "4.12.0" },
        "fromConfiguration": "implementation", "toConfiguration": null },          // UNUSED (remove)
      { "coordinates": { "identifier": "com.squareup.okio:okio", "resolvedVersion": "3.6.0" },
        "fromConfiguration": null, "toConfiguration": "implementation" },          // UNDECLARED transitive (add)
      { "coordinates": { "identifier": "com.google.guava:guava", "resolvedVersion": "33.0.0-jre" },
        "fromConfiguration": "api", "toConfiguration": "implementation" }          // WRONG CONFIG (change)
    ],
    "pluginAdvice": [], "moduleAdvice": [], "shouldFail": false
  }
]
```

`coordinates` is a Moshi sealed type keyed by a `type` discriminator in real DAGP output; the
fields we need are `identifier` and `resolvedVersion` (verify the exact key names against a real
`build-health-report.json` from a DAGP 3.15.0 run during the on-device spike — adjust the parser if
they differ). Classification rule (already documented in `AdviceParser`'s KDoc):

| from | to | category |
|------|----|----------|
| set  | null | `UnusedDependency` (remove) |
| null | set  | `UndeclaredTransitive` (add) |
| set  | set (≠) | `WrongConfiguration` (change) |

**Do:**
- Implement `parse(json: String): AnalysisResult`. Flatten `dependencyAdvice` across all
  `ProjectAdvice` entries (apply-all v1 — flat lists). Return `AnalysisResult.EMPTY` for `[]` or
  reports with no `dependencyAdvice`.
- **Keep `domain/` pure-Kotlin and JVM-unit-testable** — do NOT use `org.json` (Android-only on the
  unit classpath). Use kotlinx-serialization or Gson added as `implementation` **and**
  `testImplementation`, or a tiny hand-rolled reader. Per `architecture.md`, no Android, no I/O here.
- Handle malformed/missing JSON without crashing the parse call (throw a typed error the data layer
  maps to `AnalysisOutcome.Failure`, or return EMPTY — pick one and test it).
- Tests + fixtures under `src/test/resources/`: unused-only, undeclared-only, wrong-config, mixed,
  empty/healthy, malformed. Mutation-mindset assertions (assert parsed identifiers/configs/counts,
  not just "didn't throw"). **≥90% line AND branch on `domain/` + the data parsing path**, proven by
  `jacocoTestReport` (paste the real numbers in the PR). Never suppress coverage to hit it.

### Task 2 — Data: `GradleAnalysisRunner` impl + offline DAGP

Implement a `DefaultGradleAnalysisRunner` (or similar) satisfying `GradleAnalysisRunner`:
- Resolve `IdeCommandService` via the plugin `PluginContext` (shared as
  `DependencyAnalysisPlugin.pluginContext`). Run `:buildHealth` and `:fixDependencies` via
  `executeCommand(CommandSpec.GradleTask(task, ["--init-script", <generatedInitScriptPath>]),
  DagpConfig.GRADLE_TIMEOUT_MS)`.
- Collect `CommandExecution.getOutput(): Flow<CommandOutput>` → map to `AnalysisProgress`
  (`StdOut`→`Output`, `StdErr`→`Error`, `ExitCode`→`Finished`) on the `progress` Flow; `await()` →
  map `CommandResult.Success/Failure/Cancelled` → `AnalysisOutcome`/`FixOutcome`. `cancel()` cancels
  the execution.
- Read the report file (`DagpConfig.REPORT_RELATIVE_PATH` under the project root) via the host file
  service (`IdeFileService` — host-mediated, **off the main thread** on `Dispatchers.IO`), hand the
  raw JSON to `AdviceParser.parse`. Get the project root from the project service
  (`IdeProjectService` / `IProject.getRootDir()`; note `getCurrentProject()` is nullable in practice
  — guard it → `AnalysisOutcome.NoProject`).
- `applyFixes(before)`: run `:fixDependencies`, then re-run `:buildHealth`, parse, and
  `AdviceParser.diff(before, after)` → `FixSummary` → `FixOutcome.Success`.
- **Generate the init script** (Groovy) that puts DAGP on its own `initscript { repositories {
  maven { url 'file://<unpacked dagp-repo>' } }; dependencies { classpath
  'com.autonomousapps:dependency-analysis-gradle-plugin:3.15.0' } }` and applies it via
  `gradle.rootProject { apply plugin: 'com.autonomousapps.dependency-analysis' }`. Detect
  "DAGP not applicable / no analyzable project" → `AnalysisOutcome.SetupNeeded`.
- **Bundle the offline repo:** produce `src/main/assets/dagp-repo/` containing DAGP 3.15.0 + its
  transitive jars (so resolution is fully offline — CoGo is offline-first; **no `network.access`**).
  Unpack assets to disk at runtime so the `file://` URI resolves (assets stay zipped in the APK
  otherwise; set `android.packaging.jniLibs.useLegacyPackaging` only if shipping `.so`, which we are
  not). Complete `THIRD_PARTY_LICENSES.txt` with every bundled jar's license (Apache-2.0 for DAGP).

### Task 3 — UI: states + actions + fix sheets (BlueWave idiom)

Build out `DependencyAnalysisFragment` + `fragment_dependency_analysis.xml` and add a
`BottomSheetDialogFragment` for the fix flow. Match `client-time-tracker` exactly (border-only
`MaterialCardView`, eyebrow `Plugin.Text.Label`, mono `tnum` counts, `PluginButton.Filled/.Text`,
bottom-sheet drag handle + action row). States:
- **intro** (exists) — explanatory copy + "Analyze dependencies" → `runner.analyze()` on a
  `viewLifecycleOwner` scope, collecting `runner.progress` concurrently.
- **running** — live streamed log + elapsed + **Cancel** (`runner.cancel()`).
- **results** — summary counts + three grouped card lists (remove / declare-directly / change-config
  with `api → implementation`) + "Fix issues".
- **clean** — "all clear" when `AnalysisResult.isClean`.
- **setup-needed** — for `AnalysisOutcome.SetupNeeded` (DAGP couldn't be applied).
- **error** — for `Failure` (show the message) and `NoProject`.
- **Fix sheet** — lightweight confirm ("Apply N fixes? This edits app/build.gradle" + caution) →
  `runner.applyFixes(before)` → **post-apply summary** (removed/added/reconfigured + Re-analyze).
  **No pre-apply diff.**
- Cancel in-flight runner work in `onDestroyView`. Every control must do something real (gate 3).
- If the fix may edit a `build.gradle` open in an editor, reconcile the editor buffer + disk (gate 7)
  — or, since `:fixDependencies` writes to disk via Gradle, document why buffer reconciliation is
  N/A and how stale-buffer is handled (e.g. prompt to reload). Put the decision in `decisions.md`.

### Task 4 — Registration + finish docs
- `update-libs.yml` MAP entry is present. `build-plugins.yml` auto-discovers modules — verify
  `dependency-analysis` is **not** in its `SKIP_PLUGINS` and builds via auto-discovery; no MAP edit.
- Ensure the top-level in-IDE help file is named per gate 5 (`<plugin>.html`) and the tiered help
  describes real, shipped behavior.

### Task 5 — Verify against REVIEW.md
- `./gradlew clean assemblePlugin` green (background + timeout per the caveat above) — report it.
- `jacocoTestReport` ≥90% line+branch on non-UI — paste real numbers.
- Run the REVIEW.md **60-second sweep** (naming residue, TODO/stub greps must be clean, icon md5,
  metadata, both deploy MAPs). The current `TODO`/"fills in"/"SCAFFOLD" markers must all be gone.
- `check-done`, then `/plugin-review` → land report at
  `docs/product/plans/ADFA-3834/plugin-review-<yyyy-mm-dd>.md`.
- **On-device (needs a real device, do not fake):** the emitted analysis must run **offline on
  device** — verify DAGP applies to the root via the init script, the bundled repo resolves with
  network off, the report path is correct, `:fixDependencies` rewrites build files, and the
  re-analyze timing is acceptable. Then `android-qa` walk vs a `test-cases.md` + `ux-review`.

---

## Spike findings (from the failed workflow's research phase — verify on device)

- **DAGP 3.15.0** is current stable (not 2.19.0 from stale web summaries). It requires the **user
  project** to run **Gradle ≥ 8.11 / AGP ≥ 8.10.0**. If a supported CoGo project runs older
  toolchains, pin an older DAGP line in `DagpConfig`. **Verify CoGo's on-device Gradle/AGP.**
- **Init-script application is the right mechanism:** DAGP on its own `initscript` classpath +
  `gradle.rootProject { apply ... }` satisfies the "applied to root before evaluation" constraint
  without editing the user's build files.
- **Report:** `build/reports/dependency-analysis/build-health-report.json` (`getFinalAdvicePathV2`),
  a list of `ProjectAdvice`. Human-readable twins: `build-health-report.txt` (+ console).
- **Offline:** DAGP is not in CoGo's bundled offline Maven repo → bundle it under `assets/dagp-repo/`.
  This is the central on-device risk: confirm the full transitive set resolves with network off.

### Device-only verification (cannot be done off a device)
1. CoGo's on-device Gradle/AGP vs DAGP 3.15.0 minimums.
2. Init-script application to the root project on a real CoGo project.
3. Offline resolution from the bundled `assets/dagp-repo/` with connectivity off.
4. Exact report JSON path + field key names from a real run.
5. `:fixDependencies` rewrite behavior + fix-then-reanalyze UX timing.
