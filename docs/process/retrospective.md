# Retrospectives

## 2026-07-09 — Flutter Template plugin: review + rebuild on the CoGo template system (ADFA-3857, PR #44)

### Time Breakdown
| Started | Phase | 👤 Hands-On Time | 🤖 Agent Time | Problems |
|---------|-------|-----------------|---------------|----------|
| Jul 9 10:16pm | PR #43 review — `/plugin-review` on `pair`, post inline PR comments | █ 5m | ██ 12m | |
| Jul 10 1:35am | Scope + plan — Jira triage, dev-assets/Pebble study (3 Explore agents), AskUserQuestion, approved plan | ████ 20m | ████ 22m | |
| Jul 10 1:49am | Build — rebuild submission as headless `IdeTemplateService` installer, author 5 Pebble `.cgt` templates | ██ 12m | █████ 50m | ⚠ `pubspec name:` newline-trim bug (caught on device) |
| Jul 10 2:30am | Icons — Flutter day/night plugin icons + 5 template thumbnails (ImageMagick) | █ 6m | ███ 25m | ⚠ Glide plugin-icon disk cache showed stale PCF icon |
| Jul 10 2:44am | Verify + ship — device install/generate/substitute, commit, push, PR #44 | ██ 10m | ████ 22m | ⚠ LeakCanary hijacked `monkey` launch; emulator UI flakiness |
| Jul 10 4:15am | Retro + root consolidation — shared repo-root `libs/`+wrapper, docs | █ 5m | ██ 15m | |

### Metrics
| Metric | Duration |
|--------|----------|
| Total wall-clock (active, excl. two long idle gaps) | ~3h |
| Hands-on | ~1h (rough; the analyzer over-counts AskUserQuestion/plan text as typing) |
| Automated agent time | ~2h 20m |
| Idle/away (overnight + compaction gap) | ~16h |
| Retro analysis time | ~10 min |

### Key Observations
- **Rebuild-not-patch was the right call, and verifying the API first avoided PR #43's failure mode.** Ali's submission wrote hardcoded Dart to `/sdcard` (broken under scoped storage) because his bundled `plugin-api.jar` lacked `IdeTemplateService`. PR #43 had just failed by calling an *unreleased* API; here I confirmed `IdeTemplateService`/`CgtTemplateBuilder` exist in the **repo-root** `libs/` before building. Trusting the root jar (not a per-plugin copy) is what made the installer approach safe.
- **Device verification earned its keep — again.** `assemblePlugin` + `unzip -l` looked clean, but installing on the emulator and actually generating a project surfaced the `pubspec.yaml` `name:` corruption (Pebble trimmed the newline after a bare `${{APP_NAME | lower}}`, merging it into `description:`). This is the second consecutive session where build-success masked a real defect.
- **Two IDE-side quirks cost real time:** LeakCanary intercepting the `monkey` LAUNCHER intent (opened its Leaks screen instead of the IDE), and Glide's path-keyed plugin-icon disk cache showing the stale PCF icon after reinstall. Both are now documented so they're one-line fixes next time.
- **Explore agents front-loaded the design well.** Three parallel Explore agents (dev-assets pattern, Pebble mechanics, PCFInstaller shape) delivered the whole recipe before any code was written — the build phase had almost no false starts.

### Feedback
**What worked:** The plan-first approach (Explore agents → AskUserQuestion on the 5-variant/SDK scope → approved plan) meant the rebuild went cleanly. Device verification caught the pubspec bug.
**What didn't:** "i feel like leak canary slowed us down" — the `monkey`-launch detour into LeakCanary's UI was avoidable. Emulator UI-automation flakiness (ANRs, empty bounds) made the final device re-verify not worth it.

### Actions Taken
| Issue | Action Type | Change |
|-------|-------------|--------|
| LeakCanary hijacked `monkey` app-launch | CLAUDE.md + learnings.md | Documented launching via `am start -n com.itsaky.androidide/.activities.SplashActivity` (Verification §; learnings "Android / adb") |
| Pebble bare-tag newline-trim silently corrupts generated files | CLAUDE.md + learnings.md | Documented quoting values that must survive on their own line: `name: "${{APP_NAME \| lower}}"` (template-installer subsection; new learnings section) |
| Glide plugin-icon disk cache shows stale icon after reinstall | learnings.md (bug already tracked) | Documented cache-clear (`adb root` + rm `image_manager_disk_cache`). Underlying CoGo bug is already filed as **ADFA-4446** (Glide load in `PluginListAdapter.kt` lacks a content signature) — no new ticket needed |
| Per-plugin `libs/` and gradle-wrapper copies drift from repo | CLAUDE.md + code | Mandated repo-root shared `libs/` **and** a single repo-root Gradle wrapper; promoted the wrapper to root and pointed `flutter-template` at `../gradlew` / `../libs/*.jar`; deleted its local copies |

**Glide icon-cache bug — already tracked as [ADFA-4446](https://appdevforall.atlassian.net/browse/ADFA-4446)** (filed 2026-06-25). Root cause: `PluginListAdapter.kt` (~line 71) loads the extracted icon `File` with Glide and no cache signature, so `ObjectKey(File)` hashes the stable path and serves the old bitmap when content changes in place. Fix on file: `.signature(ObjectKey(iconFile.lastModified()))`. No new ticket was created; my session draft turned out to duplicate it.

## 2026-07-01 — Code review (PR #31) + AI Literacy Course ordering fix (PR #36)

### Time Breakdown
| Started (UTC) | Phase | 👤 Hands-On | 🤖 Agent | Problems |
|---|---|---|---|---|
| 00:07 | Code review (8-angle fan-out, verify, ≤10 findings) | █ 7m | █████ 47m | ⚠ scope > PR (stale local `main`) |
| 00:54 | Post findings 1–10 as PR comments | ▌ 3m | ▌ 5m | ⚠ 3 findings outside PR diff → top-level fallback |
| 00:59 | Housekeeping (main, pull, ls) | ▌ 1m | ▌ 1m | |
| 01:00 | Ordering fix — plan mode + implement | █ 6m | ██ 20m | ⚠ plan rejected → "just branch & do it" |
| 01:20 | Device-test loop (build → fix → rebuild) | ██ 15m | ███ 28m | ⚠ 2 rework cycles: install-marker gate, missing `pdfjs.zip` |
| 01:48 | Commit / push / PR + away | ▌ 1m | ▌ 3m | idle ~38m before retro |

### Metrics
| Metric | Duration |
|---|---|
| Total wall-clock | ~2h 19m |
| Hands-on (script) | 48m (35%) — inflated by `/code-review` + `/retro` command expansions counted as typing; real ≈ 25–30m |
| Automated agent time | ~51m (37%) |
| Idle / device-testing / away | ~40m (29%) |
| Retro analysis time | ~2 min |

### Key Observations
- **Code review ran against a stale local `main`** (150 files vs PR #31's 100); 3 compose-preview findings fell outside the PR diff and needed a fallback top-level comment. No `git fetch` happened first.
- **Two avoidable device round-trips** on the ordering fix: (1) the `.installed-vN` marker gate meant the fix had no effect until `INSTALL_VERSION` was bumped — I warned about it but didn't preempt it; (2) `pdfjs.zip` was never downloaded on this machine and `assemblePlugin` silently shipped a viewer-less `.cgp`.
- **Plan mode was overhead** for a ~15-line fix; user rejected the plan with "make a branch; do the work there."
- **Worked well:** the review fan-out (6 parallel finder agents, self-verified) ran with near-zero interaction; the ordering bug was root-caused on the first try and validated via a Python port of the sort (no `kotlinc` locally).

### Feedback
**What worked:** (not separately volunteered — core review + fix landed cleanly).
**What didn't:** Always work on a branch — working on `main` is rarely right. When diffing against `main`, suggest fetching recent changes to avoid stale situations. The `INSTALL_VERSION` miss was sloppy and wasted a cycle.

### Actions Taken
| Issue | Action Type | Change |
|---|---|---|
| Worked on `main`; stale-`main` diff produced phantom findings | CLAUDE.md | Added "Git workflow" section: always branch; `git fetch` + compare `origin/main` before any diff-against-main |
| `INSTALL_VERSION` not bumped with generation-logic change → dead fix on device | CLAUDE.md | Added "One-time on-device install markers" subsection: bump `INSTALL_VERSION` on any extraction/generation change |
| `assemblePlugin` silently shipped a `.cgp` missing `pdfjs.zip` | CLAUDE.md | Expanded "Asset downloads": warn assemblePlugin skips `downloadAssets` and ships broken `.cgp`; verify assets first |
| Same two build traps, cross-session | Learnings | Added "Plugin build & install gotchas" section to `docs/process/learnings.md` |

## 2026-06-08 — Importing the bookshelf plugin from contrib

### Time Breakdown
| Started (PDT) | Phase | 👤 Hands-On | 🤖 Agent | Problems |
|---|---|---|---|---|
| 08:17 | Plan (explore, write, refine) | ██ 12m | ██ 12m | |
| 08:29 | Build & verify (copy, edits, gradle build) | ██ 15m | ██ 15m | |
| 08:54 | Device install test + memory save | █ 2m | █ 2m | |
| 08:55 | Plugin-review skill + fix blockers (HTML, version, leak, dead code) | ██ 15m | ████ 21m | |
| 09:17 | Debug `ERR_NAME_NOT_RESOLVED` (plugin → PDF content → CoGo server) | ██ 15m | █████ 44m | ⚠ Searched CoGo source after user said it wasn't there; two interrupt-rejections |
| 10:01 | Pull `documentation.db`, find `//p/` typo | █ 5m | █ 6m | |
| 10:12 | Edit source-of-truth DB + identify corrupt OOPS asset | ████ 20m | ████ 23m | ⚠ Initial "missing book" framing was wrong — file is corrupt, not missing |
| 10:55 | Final cleanup, commit, push | █ 5m | █ 5m | |

### Metrics
| Metric | Duration |
|---|---|
| Total wall-clock | 3h 21m (200.6 min) |
| Hands-on (rough) | ~1h 15m (37%) |
| Automated agent time | ~2h (60%) |
| Idle / testing | small |
| Retro analysis time | ~12 min |

### Key Observations
- **Wrong-tree hunt on the `p` bug.** Pushed into CoGo source after user explicitly redirected, costing ~10 min and two interrupt-rejections. The plugin-side investigation had already proven the plugin doesn't construct URLs; I should have trusted that and asked the user one focused question instead of searching wider.
- **Premature "verified" claim.** Marked the build "verified" after `assemblePlugin` + `unzip -l`. The real test was device install + observation of the placeholder→real-PDF mutation. Captured mid-session as `feedback_plugin_verify_on_device.md`.
- **Wrong defect framing.** Plugin review initially called out `JavaJavaJavaObjectOrientedProblemSolving.pdf` as "missing book"; later analysis showed the asset was corrupt (4.8 MiB of high-entropy junk, not a PDF at all) and `JavaJavaJava.pdf` already *is* that book per its own metadata.
- **Plugin-review skill paid for itself**, catching the InputStream leak, missing HTML doc, and missing `plugin.max_ide_version` before commit.

### Feedback
**What worked:** Investigation into the `bookshelf-top.html` defect; running the plugin-review skill caught real bugs (notably the resource leak).
**What didn't:** Frustration around incomplete testing ideas. Should have suggested running plugin-review proactively instead of waiting to be told.

### Actions Taken
| Issue | Action Type | Change |
|---|---|---|
| Agent waited to be asked before running plugin-review | CLAUDE.md | Added "Proactively offer `/plugin-review`" paragraph to the "Plugin review skill" section, listing the triggering changes (new plugin import, dep change, API touch, new asset, libs/ update) |
| Agent marked builds "verified" without device-level proof | CLAUDE.md | Added new "Verification" section before "Adding a new plugin", stating build success is necessary but never sufficient and device install is the terminal verification step |
| Same as above, reinforcement | Memory | `feedback_plugin_verify_on_device.md` created mid-session — per-project memory layer reinforcing the CLAUDE.md rule |
