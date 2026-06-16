# dependency-analysis

A Code on the Go plugin that audits your project's Gradle dependencies and fixes
the easy wins in one tap. It runs the
[autonomousapps Dependency Analysis Gradle Plugin](https://github.com/autonomousapps/dependency-analysis-gradle-plugin)
(DAGP) `:buildHealth` task against the open project and reports dependencies that
are:

- **unused** — declared but not referenced by code (safe to remove);
- **undeclared transitive** — used directly but only present transitively
  (should be declared);
- **misconfigured** — on the wrong configuration, e.g. `api` vs `implementation`
  (should be moved).

A single **Fix dependencies** action delegates to DAGP's `:fixDependencies`,
which rewrites your `build.gradle`(`.kts`) files in place, then re-analyzes and
shows a removed / added / reconfigured summary. Long-press the **Dependencies**
tab for in-IDE help.

## How it works

- Analysis is **non-invasive**: a generated Groovy init script applies DAGP to
  the project on the fly (`--init-script`), so your build files aren't touched to
  analyze. Advice is read from
  `build/reports/dependency-analysis/build-health-report.json`.
- DAGP and its dependencies are **bundled offline** under `assets/dagp-repo/`, so
  analysis works without network access.
- The fix is **apply-all**: there's no per-item selection and no pre-apply diff.
  You confirm, Gradle rewrites the files, and a post-apply summary plus
  re-analysis confirms the result.

## Architecture

Layered `domain/` · `data/` · `ui/` (see `../architecture.md`):

- **`domain/`** — pure Kotlin advice models (`AnalysisResult`, `UnusedDependency`,
  `UndeclaredTransitive`, `WrongConfiguration`, `FixSummary`) and `AdviceParser`.
  The testable core; covered by JaCoCo.
- **`data/`** — `GradleAnalysisRunner` drives `IdeCommandService` and reads the
  report off the main thread; `DagpConfig` holds the pinned version and paths.
- **`ui/`** — the bottom-sheet tab Fragment and confirm / summary sheets.

## Permissions

Exactly `system.commands` (run the two Gradle tasks) and `filesystem.read` (read
the report). No network access — DAGP is bundled. The build-file edits are done
by Gradle, so no write/structure permissions are requested.

## Build

```sh
cd dependency-analysis
cp local.properties.example local.properties   # set sdk.dir
./gradlew assemblePlugin                        # release .cgp -> build/plugin/
./gradlew jacocoTestReport                       # coverage over domain + data
```

The full guide lives in `src/main/assets/docs/index.html` (Tier-3 in-IDE help)
and `dependency-analysis-documentation.html` (top-level docs page).
