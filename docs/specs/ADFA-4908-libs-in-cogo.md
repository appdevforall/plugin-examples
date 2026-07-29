# ADFA-4908 — Plugins must build both in CI and standalone inside Code On The Go

Status: spec / design (implementation blocked on CoGo-side ADFA-4911 + ADFA-4913)
Branch: `ADFA-4908-libs-in-cogo`

## Context / problem

Every plugin resolves the CoGo API and the `com.itsaky.androidide.plugins.build`
Gradle plugin through a **relative path** to the repo-root `libs/` in two places:

- `settings.gradle.kts` buildscript: `classpath(files("../libs/plugin-api.jar"))`,
  `classpath(files("../libs/gradle-plugin.jar"))`
- `build.gradle.kts`: `compileOnly(files("../libs/plugin-api.jar"))` (+ a few
  `testImplementation`); two plugins also use `../libs/{common,eventbus-events,idetooltips}.jar`.

That `../libs/` path exists in the CI checkout (whole repo) but **not** when a single
plugin folder is built standalone inside Code On The Go — so config/compile fails there.
The fix must keep **both** contexts working, be **delivery-agnostic** (users move source
to the device by arbitrary means — copy, `git clone`, zip — with no controlled export
step), and **not** commit per-plugin jar copies (this repo is expected to grow to
hundreds of plugins; N copies won't scale).

## Verified on-device reality (why this is coordinate-based and cross-repo)

Confirmed by inspecting a device and the CoGo source (`stage`):

- CoGo recognizes a plugin only if `libs/plugin-api.jar` exists at the project root
  (`ProjectValidations.isPluginProject`), and it provides the API/builder **only as flat
  files** under `.cg/plugin-api/` — there is **no** `com.itsaky` coordinate in its offline
  Maven mirror (`localMvnRepository` holds only third-party groups).
- **But** CoGo already injects that offline mirror into **both** `pluginManagement.repositories`
  and `dependencyResolutionManagement.repositories` (`COTGSettingsPlugin`). So the scalable
  answer is coordinate-based, enabled by two CoGo-side changes:
  - **ADFA-4911** — onboarding injects `plugin-api` + the builder (with POMs + plugin
    marker) into `localMvnRepository`, so they resolve by coordinate.
  - **ADFA-4913** — detect a plugin by the manifest `plugin.id` instead of the
    `libs/plugin-api.jar` file, so a plugin needs no jar on disk to be recognized/built.

**This ticket is the plugin-examples-side migration that consumes those two changes, and
is therefore blocked on ADFA-4911 + ADFA-4913 landing in a CoGo build.**

## Design (plugin-examples side)

### 1. Reference the CoGo artifacts by Maven coordinate (uniform across all plugins)

- `settings.gradle.kts`: replace the two buildscript `classpath(files("../libs/…"))`
  lines with the plugins DSL, resolved from `pluginManagement.repositories`:
  ```kotlin
  plugins { id("com.itsaky.androidide.plugins.build") version "1.0.0" }
  ```
- `build.gradle.kts`: replace `compileOnly(files("../libs/plugin-api.jar"))` (and any
  `testImplementation(files(...))`) with the coordinate:
  ```kotlin
  compileOnly("com.itsaky.androidide:plugin-api:<plugin-api-version>")
  ```
- **No separate add-on coordinates (Option A).** `plugin-api` is the single plugin SDK —
  it absorbs the public **event classes** (`com.itsaky.androidide.eventbus.events.*`) and
  the handful of shared **utils** the two plugins use, so `client-time-tracker` and
  `layout-editor` depend on `plugin-api` alone. This requires the CoGo-side `plugin-api`
  aggregation change (see Dependencies). Cleanups: drop the `idetooltips.jar` reference
  (verified **unused**); the event + util imports resolve from the aggregated `plugin-api`.
  Note the third-party `org.greenrobot:eventbus` library those two use is a normal Maven
  coordinate (resolves from `localMvnRepository`/mavenCentral), not a CoGo jar.

### 2. Provide the same coordinates in the monorepo via ONE committed Maven repo

- Add a single repo-root **`maven-repo/`** in Maven layout containing one copy of just
  **`plugin-api`** (the aggregated SDK) and **the builder**, + their POMs + the builder's
  plugin marker. ~<0.5 MB total, **one copy for all plugins** (no add-on jars under Option A).
- Each plugin's `settings.gradle.kts` declares it in both repository handlers, path
  overridable by property so it's inert on-device:
  ```kotlin
  // in pluginManagement { repositories { … } } and dependencyResolutionManagement { repositories { … } }
  maven { url = uri(providers.gradleProperty("cogoMavenRepo").orElse("../maven-repo").get()) }
  ```
  - **CI / laptop:** `../maven-repo` resolves the coordinates.
  - **On-device:** `../maven-repo` doesn't exist → Gradle treats it as an empty repo and
    falls through to CoGo's injected `localMvnRepository` (populated by ADFA-4911). Same
    coordinates resolve in both contexts, no per-plugin jars.

### 3. Produce `maven-repo/` from a single source

Update `scripts/update-libs.sh` to **publish** the CoGo modules' Maven artifacts (via the
`maven-publish` / `publishToMavenLocal` added in ADFA-4911) into the committed
`maven-repo/`, instead of copying flat jars into root `libs/`. One canonical source,
refreshed on each CoGo API update, committed **once** (not per plugin). Adjust the
`update-libs.yml` and `build-plugins.yml` workflows accordingly.

### 4. Remove the old layout

Delete all `../libs/` references, remove the root `libs/` (superseded by `maven-repo/`),
and delete the two stray empty per-plugin dirs (`ai-core/libs/`,
`pebble-custom-function-template-installer/libs/`).

### 5. Update `CLAUDE.md`

Replace the "`libs/` is the load-bearing piece / never bundle per-plugin copies" section
with the coordinate model: one committed `maven-repo/`; on-device resolution via CoGo's
injected `localMvnRepository`; detection by `plugin.id`. Record *why* (the on-device
reality above) so it isn't "fixed" back to `../libs/`.

## Coordinates & versions

Only **two** coordinates (Option A):

| Artifact | Coordinate | Version | Notes |
|---|---|---|---|
| builder (Gradle plugin) | `com.itsaky.androidide.plugins.build` (id) | `1.0.0` | from the `plugin-builder` module; applied via plugins DSL |
| plugin-api (SDK) | `com.itsaky.androidide:plugin-api` | `<TBD>` | no coordinate today; version assigned alongside ADFA-4911. Must be the **aggregated** SDK — the plugins API **plus** the public event classes + curated utils (CoGo `plugin-api` aggregation change) |

`common` / `eventbus-events` / `idetooltips` get **no** coordinates — CoGo doesn't ship them;
they are folded into `plugin-api` (events + needed utils) or dropped (`idetooltips`, unused)
per Option A. Use a single version constant (e.g. in `gradle.properties`) so all plugins agree.

## Dependencies & sequencing

- **Blocked on ADFA-4911 + ADFA-4913** landing in a CoGo build we can test against.
- **Depends on the CoGo `plugin-api` aggregation change** (Option A): `createPluginApiJar`
  must produce a *complete* SDK — the plugins API **plus** the public event classes
  (from `eventbus-events`) and the curated utils the plugins use — so injecting `plugin-api`
  alone (ADFA-4911) satisfies every plugin. CoGo ships only `plugin-api.jar` +
  `gradle-plugin.jar` today (verified); the add-on jars are **not** shipped, so widening
  4911 to inject five is not viable — the fix is aggregation, not more artifacts.
- Until CoGo ships the injection + detection, **keep `../libs/` working** — do not remove
  it. The flip to coordinates happens only once a CoGo build with 4911+4913 is available.

## Verification

- **Monorepo / CI:** build every plugin with `../gradlew assemblePluginDebug` resolving
  from the committed `maven-repo/` (default `../maven-repo`, or `-PcogoMavenRepo=…`);
  each produces a `.cgp`. (The 5 plugins failing on stale API symbols are a separate
  libs-content issue, orthogonal to this migration.)
- **On-device (the real acceptance test):** on a CoGo build that includes ADFA-4911 +
  ADFA-4913, copy **one** plugin folder — no `libs/`, no repo root — to the device;
  confirm CoGo recognizes it (via `plugin.id`) and builds a `.cgp` **offline**, resolving
  the API/builder from the injected `localMvnRepository`.
- **Delivery-agnostic:** repeat with (a) a plain folder copy and (b) a `git clone` of just
  the subtree — both must work with no `../libs` and no special export step.

## Files touched

- All `*/settings.gradle.kts` and `*/build.gradle.kts` (coordinate references).
- New committed `maven-repo/` (single canonical Maven layout).
- `scripts/update-libs.sh` (+ `.github/workflows/update-libs.yml`, `build-plugins.yml`).
- `CLAUDE.md` (convention reversal, with rationale).
- Remove root `libs/`, `ai-core/libs/`, `pebble-custom-function-template-installer/libs/`.
- `client-time-tracker` / `layout-editor`: drop the `common`/`eventbus-events`/`idetooltips`
  `../libs/*.jar` references (idetooltips unused; the rest resolve from the aggregated
  `plugin-api`); keep `org.greenrobot:eventbus` as a normal third-party coordinate.
