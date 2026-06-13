# Definition of Done — plugin-examples

This is the complete definition of done for this repo. The `plan-for-done` and `check-done`
skills read **this file** — there is no separate base. Everything you must prove before
declaring work done lives here.

**The bar:** before declaring work done / ready for review (or opening a PR), prove each
applicable gate is met **with evidence**, or flag it with a written reason. "Builds and the happy
path works" is necessary, never sufficient. Skip a gate only when it genuinely doesn't apply —
never because it's untested.

The file has two parts:

- **Core gates** apply to *every* change.
- **Plugin gates** add what a CoGo plugin specifically needs. A plugin builds into a **`.cgp`**
  (a CoGo plugin package — a signed Android APK the IDE loads at runtime via `DexClassLoader`),
  so it can be fully built, installed, and pass happy-path QA while still shipping placeholder
  assets, half-wired features, stale docs, or manifest mistakes — none of which crash. The plugin
  gates catch that class of defect.

Apply the plugin gates at **design time** (pick conventional approaches and coherent names up
front) and again at **completion** (run the sweep at the end; report what it found, even
"nothing"). Treat any unchecked box as fixed or explicitly flagged to the reviewer with a reason.

---

# Core gates (every change)

1. **Builds clean.** Report the actual build result, not "should compile." State the build target
   and that it's green.
2. **Tests — logic AND behavior.** Tests catch regressions a green build can't.
   - Logic/unit tests for the change. **≥90% line *and* branch coverage on non-UI code** (domain /
     data / logic), verified with a real coverage report (e.g. JaCoCo), not by eye. UI (Fragments,
     Activities, GL surfaces) is exempt — it's covered by on-device QA (gate 6). Rare
     unreachable-defensive branches may be skipped *with a noted reason*.
   - **Coverage is a floor, not the goal.** Each test must *fail if the code were wrong* (the
     mutation-test mindset): assert real behavior, not just "didn't throw"; test the contract, not
     internals; cover meaningful edge / boundary / error cases; stay deterministic and isolated. A
     line executed with no assertion that catches a plausible bug is coverage theater.
   - **Never suppress coverage to hit the number** (no exclude config, `@Suppress`, or dropping a
     real class from the denominator). If something is hard to test, refactor it for testability.
3. **No file I/O on the main thread.** Blocking I/O on the UI thread freezes the app. Run every
   file read / write / `mkdirs` / `delete` / `listFiles` / stream off the main thread (e.g. on an
   I/O dispatcher). Make the guarantee **structural** — a data-layer function that wraps its own
   I/O internally — rather than trusting every caller to remember.
4. **Structure / single-responsibility / layering.** Sound structure keeps a change's blast radius
   small. Walk the coupling-&-cohesion audit and SRP checks in `architecture.md`: UI-vs-logic
   separation, dependencies flowing toward the domain, no god-objects, no domain types stranded in
   UI packages. Apply at design time (`plan-for-done`) and again at review time.
5. **Code review pass.** Reviewers are the last line before shipping; run a substantive
   code-review pass and fix blockers in-branch.
6. **On-device QA + UX review for any UI surface.** Code-only tests can't see runtime UX bugs. Any
   change that adds or alters a user-visible surface gets an `android-qa` walk on a real device
   against a `test-cases.md` flow doc, with a captioned recording. Cover the full flow — list every
   test-case ID rather than a range (a range hides omissions), and confirm by frame, not just by
   caption (a caption asserts intent; only a frame proves capture). Once the flows pass clean, run
   a `ux-review` on the working end-to-end flow: **critical findings block ship** (fix before the
   PR); important findings go in the PR followups.
7. **Docs updated.** Stale docs mislead the next reader. Record non-obvious decisions and durable
   gotchas in the repo's decisions / learnings docs, and update any README / help / roster the
   change makes stale.

---

# Plugin gates

## 1. Naming coherence

A plugin's name appears in ~8 places; they must tell one story, or the plugin looks half-renamed.

- [ ] **Module folder** uses the lowercase convention (e.g. `apk-viewer`).
- [ ] **`rootProject.name`** (settings.gradle.kts) matches the folder.
- [ ] **`namespace` + `applicationId`** (build.gradle.kts) are `org.appdevforall.<plugin>` — the
  repo standard. (Some older plugins use `com.example.*` / `com.codeonthego.*`; don't add to that
  inconsistency.)
- [ ] **`plugin.id`** (manifest) matches the namespace.
- [ ] **`plugin.main_class`** points at the real main class in its real package.
- [ ] **Main class name** matches the plugin (no leftover name from a copied template).
- [ ] **`.cgp` pluginName** (`pluginBuilder { pluginName }` in build.gradle.kts) is coherent.
- [ ] **User-facing display names agree** — `plugin.name`, `<application android:label>`, `app_name`,
  the tab title, in-UI headings — one canonical name (document any deliberate exception).
- [ ] **Resource-id prefixes, tooltip tags, test env vars** carry no old name.
- [ ] A `grep -rni "<oldname>"` over `src`, `build.gradle.kts`, and the manifest returns nothing
  unexpected.

## 2. No placeholder or carried-over assets

Copied template content shipped as-is makes a plugin look unfinished.

- [ ] **Icons** (`icon_day.png` / `icon_night.png`) are the plugin's own — `md5sum` differs from the
  `random-xkcd` reference plugin's (the canonical copy-from template).
- [ ] Icons match the shared style (monochrome glyph, transparent background) and render correctly on
  both light and dark surfaces.
- [ ] No strings, layouts, or sample data copied from a template still name the wrong plugin/feature.

## 3. Feature complete — nothing half-wired

A partially built feature that doesn't crash still misleads the user.

- [ ] **Every acceptance criterion is implemented** — re-read the ticket; confirm the full set, not
  just the demo path. Intentional descopes are written down (ticket + `decisions.md`), never a
  silent omission.
- [ ] **Each feature is end-to-end** (data → logic → UI, happy path *and* edges) or absent — nothing
  partially built ships as if complete.
- [ ] **Every UI control does something** — no button/toggle/menu item wired to a stub or no-op.
- [ ] Deferred features are hidden or clearly marked unavailable, and documented in `decisions.md`.
- [ ] Grep for stub signatures (`TODO` / `FIXME` / `not yet` / `return emptyList()` on a feature
  path) — each is intentional + documented, or removed.

## 4. No vestigial scaffolding

Dead code from removed or abandoned features confuses the next author.

- [ ] Removed features leave nothing behind: no orphan enum values, unused string resources, dead
  branches, or files for a feature that no longer exists.
- [ ] Comments describe the current state, not a past one.
- [ ] **No dead parallel architecture** — no DI module / ViewModel / abstraction the actual UI
  bypasses. Pick one wiring and delete the other.

## 5. Docs match shipped reality

Docs are how a user decides whether to install; they must be accurate.

- [ ] README and any tutorial describe what the code actually does — correct file formats, correct
  counts, no aspirational claims stated as fact.
- [ ] **In-IDE HTML help present** — a top-level `<plugin>.html` plus the tiered help the reference
  plugins ship (HTML, not raw Markdown). Submission rubric §6.6.
- [ ] Tooltips and other user-facing help name the plugin correctly and list real capabilities.
- [ ] `THIRD_PARTY_LICENSES` is current; new bundled assets (fonts, binaries, models) have
  attribution.
- [ ] **Listed in the top-level `README.md` roster** (name + one-line description) — otherwise the
  plugin is invisible to anyone browsing the repo.
- [ ] **Registered to deploy** — added to the `MAP` array in both `.github/workflows/build-plugins.yml`
  and `.github/workflows/update-libs.yml`. CI builds every module, but only those in the `MAP` get a
  website filename and ship.

## 6. Metadata + conventions (diff against a reference plugin)

The manifest is the plugin's contract with the host; over- or under-declaring breaks load or trust.

- [ ] `plugin.author` is exactly `App Dev For All`.
- [ ] `plugin.min_ide_version` is justified — the fleet default unless a real host-API dependency
  requires higher (and sibling plugins on the same API agree). Declare a **supported IDE version
  range**, not a single point release (rubric §6.1: must run on current stable + the immediately
  preceding minor).
- [ ] `plugin.permissions` lists **exactly** what's used — `native.code` iff a `.so` is bundled,
  `network.access` iff outbound HTTP. The manifest declares every extension, every permission, the
  IDE version range, and the minimum Android SDK level.
- [ ] Version, icon meta-data, and sidebar/tab declarations are present and correct.
- [ ] **Use Pebble/CGT templates for emitted files.** Project templates (the files a plugin scaffolds
  into a new user project) ship as `.peb` / static assets under `assets/templates/…` rendered by
  CoGo's CGT framework — not inline Kotlin raw strings (which hide emitted code from lint and carry a
  string-interpolation footgun). Keep only genuinely dynamic bits programmatic.

## 7. Host-integration correctness (apply only the conditionals that fit)

Each bullet applies **only if your plugin does that thing** — integration traps that pass a build
and happy-path QA but corrupt state or the user's mental model. Skip a bullet because it doesn't
apply, never because it's untested.

- [ ] **Edits a file that may be open in an editor?** Update **both** the open editor buffer and disk
  (prefer the buffer, fall back to disk). A disk-only write leaves stale content or loses unsaved
  edits.
- [ ] **Registers a host-service listener / subscription / watch?** Remove and release it in
  `deactivate()` / `dispose()`, with a use-before-ready guard — otherwise it leaks across
  enable/disable and double-fires after reload (rubric §6.2: nothing outlives the plugin).
- [ ] **Exposes an action only valid in a context?** Gate it with `isEnabledProvider` (e.g. greyed
  unless a relevant file is active) rather than failing with a toast.
- [ ] **Bundles an ML model?** Ship a host/JVM golden test pinning its output convention (coordinate
  space, normalization, class order) — a green build won't catch a wrong convention. Model + labels
  need a `THIRD_PARTY_LICENSES` provenance line.
- [ ] **Writes generated content into the user's project?** Confirm it lands where the user expects,
  merges non-destructively (splice into existing files, don't clobber), and is idempotent on re-run.
- [ ] **Scaffolds a project / app from a template?** The emitted app MUST build **offline, on-device**
  — using CoGo's bundled offline Maven repo with network repos disabled, verified by actually
  building on a real device with connectivity off. Keep the template's dependencies inside what the
  bundled offline repo provides; anything outside is a provisioning gap to resolve up front. This is
  a separate device check from `assemblePlugin` and the android-qa walk.

## 8. Plugin-API discipline (rubric §6.3–6.5)

- [ ] **Build reproducibility** — the `.cgp` rebuilds from source on a clean machine via the
  documented command (`./gradlew assemblePlugin`, or `scripts/update-libs.sh` for a full-repo
  rebuild). No author-machine-only builds.
- [ ] **No reflection into IDE internals** — only the public `plugin-api` / `lsp/api` surfaces.
  Reflection against IDE classes is grounds for rejection.
- [ ] **Native binaries** — any `.so` holds `native.code` and meets the sandbox-bypass tier (§7.3).

## 9. The submission gauntlet — `cogo-plugin-review`

- [ ] **Run the `cogo-plugin-review` skill** (`.claude/skills/plugin-review/`, invoked via
  `/plugin-review`) — build → security audit → rubric scorecard → report. Required before human
  review: all rubric clauses Pass, security audit clean, every blocker fixed in-branch (advisory
  items may defer to PR followups with a written reason). This sits **on top of** the core code-review
  gate — run both. Land the report at `docs/product/plans/<ticket>/plugin-review-<yyyy-mm-dd>.md`.

---

## The 60-second sweep (paste-ready)

```bash
P=<plugin-dir>   # e.g. apk-viewer
OLD=<oldname>    # a prior name, if the plugin was ever renamed (else skip)
# naming residue:
grep -rniE "$OLD" "$P/src" "$P/build.gradle.kts" 2>/dev/null | grep -v /build/
# placeholders / half-finished / stale:
grep -rniE "TODO|FIXME|placeholder|stub|not yet|deferred" "$P/src" | grep -v /build/
# stub feature writes (no-op returns on a feature path):
grep -rnE "return emptyList\(\)" "$P/src/main"
# icon must not be a copy of the reference plugin:
md5sum "$P/src/main/assets/icon_day.png" random-xkcd/src/main/assets/icon_day.png
# metadata at a glance (expect org.appdevforall.<plugin>):
grep -nE "namespace|applicationId|pluginName" "$P/build.gradle.kts"
grep -A1 "plugin.(id|name|author|min_ide_version|main_class|permissions)" "$P/src/main/AndroidManifest.xml" | grep -E "value|name"
# registered to actually deploy?:
grep -q "\"$P\"" .github/workflows/update-libs.yml && echo "in update-libs MAP" || echo "MISSING from update-libs MAP"
```

Read every line of output, plus the `app_name` / `plugin.name` / label / tab strings by eye. Clean
output is a precondition for "done," not proof of it — finish the checklist above.
