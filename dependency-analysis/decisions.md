# Dependency Analysis — decisions

Decision log for the `dependency-analysis` plugin (ADFA-3834). Records the
locked product decisions and the scaffolding decisions made while standing up
the module, so later agents don't relitigate them.

## Product scope (locked, v1)

- **Scope:** analyze *and* fix, both in v1.
- **Fix granularity:** apply-all. Delegate to DAGP's `:fixDependencies` task; no
  per-item selection.
- **No pre-apply diff.** Flow: confirm ("Apply N fixes? edits app/build.gradle")
  → run → **post-apply** summary (removed/added/reconfigured + re-analyze). We do
  NOT synthesize a build.gradle diff.
- **Single editor tab**, no toolbar BuildActionExtension entry.

## DAGP integration

- Pin DAGP **3.15.0** (latest stable). Constants live in `data/DagpConfig.kt`.
- Apply DAGP to the user project via a **generated Groovy init script** passed as
  `--init-script` to `:buildHealth` — non-invasive, does not edit the user's
  build files to analyze. The init script puts DAGP on its own `initscript`
  classpath and applies the project plugin to the root.
- **Bundle DAGP + transitives offline** under `assets/dagp-repo/`, resolved via a
  `file://` init-script repository. CoGo is offline-first; a first-run network
  fetch is unreliable on-device. Consequence: **no `network.access` permission**,
  and a `THIRD_PARTY_LICENSES` asset must enumerate every bundled jar's license.
- Parse the aggregated `build/reports/dependency-analysis/build-health-report.json`
  (a list of ProjectAdvice). Classify each advice by the from/to null pattern:
  unused (from set, to null) / undeclared-transitive (from null, to set) /
  wrong-config (both set, different).

## Permissions

- Declared exactly `system.commands,filesystem.read`.
  - `system.commands` gates `IdeCommandService` (runs both Gradle tasks).
  - `filesystem.read` reads the report JSON.
- **No `filesystem.write` / `project.structure`:** the build-file edits are
  performed by Gradle (`:fixDependencies`), not by the plugin. Over-requesting
  would be a rubric reject. Re-evaluate only if a plugin-side editor-buffer write
  path is added later.

## Architecture / layering

- `domain/` — pure Kotlin: advice models (`AnalysisResult`, `UnusedDependency`,
  `UndeclaredTransitive`, `WrongConfiguration`, `FixSummary`) + `AdviceParser`.
  The JaCoCo-covered core; no Android, no I/O.
- `data/` — `GradleAnalysisRunner` (IdeCommandService + report I/O off the main
  thread), `DagpConfig` constants.
- `ui/` — the bottom-sheet tab Fragment + confirm/summary sheets. Excluded from
  the JaCoCo line/branch gate (Android-framework-bound).

## JaCoCo

- Wired in `build.gradle.kts`: `jacocoTestReport` over `testDebugUnitTest`,
  XML+HTML, excluding `ui/**`, generated `R`/`Manifest`/`BuildConfig`. No
  suppression of real domain/data logic. First plugin in the repo to wire JaCoCo.

## Signing

- CLAUDE.md lists "unsigned release .cgp → host silently rejects" as a known
  gotcha and suggests a debug-keystore `signingConfig`. The reference plugins
  (random-xkcd, client-time-tracker) do **not** wire one. The ticket explicitly
  asks for a `releaseDebugKey` signingConfig, so we wire one — but **only when
  `~/.android/debug.keystore` exists**, so a fresh checkout still configures.
  Decision: prefer correctness-on-device (signed release) over matching the
  template's omission, guarded so it never breaks configuration.

## Open / handed-off items

- Bundled `assets/dagp-repo/` offline Maven repo + `THIRD_PARTY_LICENSES` are not
  yet produced (placeholder license file shipped). Data agent owns this.
- `AdviceParser.parse` is a compiling stub returning EMPTY; domain agent owns the
  real JSON parsing + fixture-driven coverage.
- UI states (running/results/clean/setup/error), confirm + post-apply sheets, and
  open-build.gradle buffer reconciliation are scaffolded only; UI agent owns them.
- Device verification items: user-project Gradle/AGP vs DAGP minimums; init-script
  application to root; offline resolution from the bundled repo; report path; the
  fix-then-reanalyze UX timing. See ticket research DEVICE-ONLY list.
