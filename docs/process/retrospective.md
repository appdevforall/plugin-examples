# Retrospectives

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
