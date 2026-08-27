# Addon Distribution & Repository Normalization — Design

| | |
|---|---|
| **Status** | Draft for review |
| **Owner** | Hal Eisen, App Dev For All |
| **Date** | 2026-08-27 |
| **Repo** | `appdevforall/plugin-examples` |

---

## 1. Summary

Two problems, one change.

1. **GitHub Actions artifact quota is exhausted quickly.** `plugin-examples` uploads ~1.25 GB of build artifacts per full run, for no reason other than to move files between jobs in the same workflow.
2. **Final `.cgp` files are served from an unstable GreenGeeks WordPress host.** Downloads depend on a machine we do not control and which fails often enough to matter.

Both are solved by the same move: **stop using GitHub Actions artifacts entirely and publish straight to Cloudflare R2 from the build job.** The `addons` R2 bucket becomes the distribution surface — binaries, per-plugin description pages, the catalog JSON, and the gallery web app — served over a custom domain.

The same change normalizes plugin naming and relocates plugins into a `plugins/` subdirectory, making room for the `templates/`, `snippets/`, and `code-actions/` addon types that follow.

**Scope discipline:** this change fixes the quota and sheds GreenGeeks. Anything not serving one of those two goals is explicitly out of scope (§9).

---

## 2. Problem detail

| ID | Fact | Source |
|---|---|---|
| F01 | `update-libs.yml` uploads a single `plugins-cgp` artifact — measured **530.3 MB** on 2026-08-19 — solely to hand files from the `build` job to a separate `deploy` job. | `.github/workflows/update-libs.yml:141-146`, `:163-166` |
| F02 | `build-plugins.yml` uploads the same bundle, then a **~30-leg matrix downloads all 530 MB in every leg** to extract one file and re-upload it. ~16 GB of intra-run transfer and ~1.25 GB of resident artifact storage per run, producing zero new bytes. | `build-plugins.yml:117-122`, `:126-147` |
| F03 | Three plugins dominate payload size: `ndk-installer` 202.8 MB, `ai-literacy-course` 123.8 MB, `sketch-to-ui` 92.1 MB — 68% of every run. | `gh release view build-2026-08-19-49` |
| F04 | Distribution is `scp` to `public_html/flags/plugins` on GreenGeeks, gated on `GREENGEEKS_SSH_*` secrets. | `update-libs.yml:9-10`, `:212-219` |
| F05 | Neither workflow uses `actions/cache`; both set `cache-disabled: true`, so every run re-downloads the full Gradle/Android dependency graph. | `build-plugins.yml:41-44`, `update-libs.yml:63-66` |

**The insight that shapes the design:** the artifacts are plumbing, not a deliverable. Nothing consumes them outside the run that produced them. Removing the inter-job handoff removes the artifacts, and plugin size stops mattering permanently — no retention tuning, no size thresholds, no budget to police.

F03 and F05 are real inefficiencies but are **not fixed here** (§9). Once artifacts are gone, plugin size has no quota consequence.

---

## 3. Cloudflare R2 constraints (verified)

Researched against official Cloudflare docs on 2026-08-27. These constrain the design; each is load-bearing.

| ID | Finding | Consequence |
|---|---|---|
| F06 | **R2 has no static-website hosting.** `PutBucketWebsite` is Not Implemented; no index-document or error-document setting exists; buckets are flat; "public buckets do not let you list the bucket contents at the root of your (sub) domain." | `https://<host>/` returns **404**, not `index.html`. Uploading `index.html` is necessary but not sufficient — it is a *routing* gap, not a content gap. See §6.3. |
| F07 | **Overwriting a key does not purge the edge cache.** "The old (previous) object will continue to be served to clients until the cache TTL expires… or the cache is purged." Cached 404s persist identically. | With stable latest-only keys (§5), **every publish must call the purge API**. Non-optional. |
| F08 | Cloudflare caches **by file extension, not MIME type**. `.cgp` is not on the default list; neither is HTML or JSON. Files >512 MB are not cached at all on Free/Pro/Business. | Without an explicit Cache Rule nothing is cached and every download reaches R2. **Accepted** — egress is free, so this is latency, not cost. |
| F09 | **No OIDC or keyless auth for R2.** S3-compatible API tokens only. Four scopes exist; `Object Read & Write` can be scoped to a single bucket, and object-scoped tokens deliberately fail against the Cloudflare REST API. | Two GitHub repo secrets. Use `Object Read & Write` scoped to `addons` only. The cache-purge call needs a *separate* token (REST API). |
| F10 | **Wrangler has no bulk upload** — one object at a time, 315 MB cap. | Use the `aws` CLI against the R2 S3 endpoint, consistent with existing App Dev For All tooling. AWS CLI v2 defaults to CRC64NVME checksums, which R2 supports; it is the language SDKs defaulting to CRC32 that break on R2, not the CLI. |
| F11 | `Content-Type` controls render-vs-download. Whether R2 *infers* it on upload is undocumented. | Never rely on inference. `aws s3 sync` guesses from extension client-side: `.html` → `text/html`, unknown `.cgp` → `application/octet-stream`. Both correct, and octet-stream matches what appdevforall.org already returns for `.cgp`. |
| F12 | **Cost is effectively zero.** Egress free at any volume; free tier covers 10 GB storage, 1M Class A ops, 10M Class B ops per month. | ~1 GB of `.cgp` is $0/month. No cost argument against this migration. |

**Verified live state (2026-08-27):** the `addons` bucket exists and is empty; the local `cloudflare` AWS profile authenticates against it and is already object-scoped (`ListBuckets` denied, bucket access succeeds); `appdevforall.org` is on Cloudflare nameservers (`jobs.ns`, `carioca.ns`), satisfying the custom-domain prerequisite; no custom domain is live on any candidate hostname yet.

---

## 4. Decisions

| # | Decision | Rationale |
|---|---|---|
| D01 | Publish directly to R2 from the build job. **No `actions/upload-artifact` anywhere in the repo.** | The only change that fixes the quota permanently rather than tuning it. |
| D02 | **Stable latest-only keys** — `plugins/<slug>.cgp`, overwritten each release. | Simplest URLs; matches the prototype. Cost is the mandatory purge (F07). |
| D03 | **Hard cut** from `scp` to R2 in one PR, preceded by a manual validation upload. | Smallest diff, no half-migrated state. Validating the bucket by hand first removes the "fix forward while downloads are broken" risk without a dual-publish phase. |
| D04 | GitHub Releases are **untouched**. | Release assets don't consume Actions artifact storage, so they're irrelevant to both goals. |
| D05 | The whole site — gallery shell, catalog, descriptions, binaries — is served from the `addons` bucket over a custom domain. **No Cloudflare Pages.** | One vendor, one deploy, no second hosting product to own. |
| D06 | Gallery source and per-plugin description HTML live in **`plugin-examples`**. | One PR adds a plugin, its docs, and its metadata; one workflow publishes everything. No cross-repo contract. |
| D07 | Curated metadata lives in **per-plugin `addon.json`**. | Contributors add theirs alongside their code; no central file to merge-conflict on; a missing file is a loud CI failure. |
| D08 | **The directory name is authoritative.** Display name, `.cgp` filename, and doc filename derive from it mechanically. No override field. | Removes the drift the naming standard exists to close, and makes it CI-enforceable with no escape hatch. |
| D09 | **`plugin.id`, `namespace`, and `applicationId` are frozen.** | `plugin.id` is the installed-plugin identity on user devices *and* the tooltip category key (`plugin_<pluginId>`). Renaming orphans installed copies and silently renders every tooltip as `n/a`. The naming standard doesn't govern it. |
| D10 | **Both HTML docs stay.** They serve different audiences (§7). | Not duplication. Deduplicating them would degrade both. |
| D11 | The six `ai-*` plugins are **frozen in place** for this change. | Other PRs are in flight; avoiding conflicts is worth a mixed layout. |
| D12 | `pebble-custom-function-template-installer` (PCFInstaller) is **excluded** entirely. | Already in `SKIP_PLUGINS`; never builds in CI; has no real name or description. |
| D13 | `catalog.json` carries a `kind` discriminator from day one. | The only forward-looking concession, and it's free. Lets `templates`/`snippets`/`code-actions` slot in later without a schema break. |

**Explicitly dismissed:** existing links to `appdevforall.org/flags/plugins/*.cgp` breaking at cutover is **not a concern** and needs no mitigation.

---

## 5. R2 bucket layout

```
addons/
  index.html   styles.css   app.js       # gallery shell, from plugin-examples/gallery/
  catalog.json                           # canonical, kind-discriminated
  plugins.json                           # filtered view the gallery loads
  plugins/<slug>.cgp                     # stable latest-only keys (D02)
  descriptions/<slug>.html               # per-plugin gallery page
  staging/<slug>.cgp                     # workflow_dispatch QA builds
```

`templates/`, `snippets/`, and `code-actions/` prefixes are added when those addon types exist. No `templates.json` / `snippets.json` filtered views until there is a consumer for them.

**Cache-Control**, set explicitly at upload (F07/F08):

| Object class | Cache-Control | Reason |
|---|---|---|
| `catalog.json`, `plugins.json` | `no-cache` | Must never be stale; it's the index of everything else. |
| `index.html`, `styles.css`, `app.js` | `max-age=300` | Short enough that a gallery fix lands quickly. |
| `descriptions/*.html` | `max-age=300` | Same. |
| `plugins/*.cgp` | `max-age=3600` | Bytes change only on release, and the purge covers that case. |

**Purge (F07):** after every publish, `POST /zones/{zone_id}/purge_cache` with the exact URLs written. Free tier allows 100 URLs per request, which comfortably covers ~24 plugins × 2 files plus the shell.

---

## 6. Workflow changes

### 6.1 `update-libs.yml` — the release path

Today: build → `upload-artifact` → separate `deploy` job → `download-artifact` → `scp` loop.

After:

```
build job:
  scripts/update-libs.sh          # refresh libs/, build every plugin
  stage  -> deploy-staging/<slug>.cgp
  aws s3 sync deploy-staging/     s3://addons/plugins/      --endpoint-url $R2_ENDPOINT
  aws s3 sync descriptions/       s3://addons/descriptions/ --endpoint-url $R2_ENDPOINT
  uv run scripts/build_catalog.py                           # -> catalog.json, plugins.json
  aws s3 sync gallery/            s3://addons/              --endpoint-url $R2_ENDPOINT
  POST /zones/{id}/purge_cache
  softprops/action-gh-release     # unchanged (D04)
```

**Deleted:** both `upload-artifact` calls, both `download-artifact` calls, the entire `deploy` job, the `scp` loop and its md5 verification, and the `GREENGEEKS_SSH_PRIVATE_KEY` / `GREENGEEKS_SSH_HOST` / `GREENGEEKS_SSH_USER` secrets and vars.

**New secrets:** `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` (Object Read & Write, scoped to `addons`), `R2_ACCOUNT_ID`, plus `CLOUDFLARE_PURGE_TOKEN` and `CLOUDFLARE_ZONE_ID` for the purge call — a *separate* token, since object-scoped tokens cannot call the REST API (F09).

### 6.2 `build-plugins.yml` — the QA path

The ~30-leg publish matrix is **deleted**. A `workflow_dispatch` build uploads to `s3://addons/staging/<slug>.cgp` and prints the URL in the job summary — hittable directly from a device, which beats downloading a zip to a laptop and pushing it over `adb`.

### 6.3 Serving the site root (F06)

`https://addons.appdevforall.org/index.html` works with zero configuration. Only the bare hostname 404s. Closed with a zone-level **Redirect Rule** (`/` → `/index.html`) — a dashboard setting, no code, no Worker. To be verified live during setup; if Redirect Rules don't apply to R2 custom domains, the fallback is a ~15-line Worker.

**This is a manual setup step, not code.** It cannot be inferred from the repo.

### 6.4 Plugin discovery must match two roots

Freezing the `ai-*` plugins (D11) leaves a **mixed layout**: of the 31 plugin directories, 24 move under `plugins/`, six `ai-*` stay at the top level, and PCFInstaller stays put and unbuilt. All four discovery globs must match both roots during the transition, with un-migrated directories falling back to the current lowercase-dirname slug rule:

| File | Line | Current |
|---|---|---|
| `scripts/update-libs.sh` | 128 | `*/build.gradle.kts` |
| `.github/workflows/build-plugins.yml` | 74 | `*/build.gradle.kts` |
| `.github/workflows/update-libs.yml` | 107 | `*/build.gradle.kts` |
| `.githooks/pre-push` | 20-24 | `*/build.gradle.kts` |

This makes the migration incremental rather than big-bang, and must be built in deliberately rather than discovered later.

---

## 7. Repository layout

```
plugins/<Mixed-Case-Name>/
  build.gradle.kts          # ../libs/*.jar      -> ../../libs/*.jar
  settings.gradle.kts       # buildscript classpath, same change
  addon.json                # description, hashtags, origin, license, author
  <slug>.html               # gallery page; uploaded to descriptions/
  src/main/assets/docs/index.html   # in-app help; untouched
templates/  snippets/  code-actions/    # placeholders, unchanged
gallery/                    # index.html, styles.css, app.js
scripts/build_catalog.py
```

### 7.1 Why two HTML files per plugin is correct (D10)

They are different documents for different moments:

- **`<slug>.html` (top level → gallery)** is *information scent*. It answers "should I download this at all?" for someone browsing the catalog who has never seen the plugin.
- **`src/main/assets/docs/index.html` (Tier 3, in-app)** is reference documentation. It answers "how do I use every feature of this?" for someone who has already installed it.

Collapsing them would make the gallery page too long to skim and the in-app page too thin to use. **Do not deduplicate these.**

### 7.2 Also requires updating

`README.md` Examples table (26 rows, missing 5 plugins that do ship); `CLAUDE.md` — which lists plugin directories inline *and* still documents a `MAP` array deleted in `4d59a12`; `scripts/check-toolchain.sh` per-plugin comments.

---

## 8. Naming

Per `plugin-naming-standards.md`: the directory name is the single human decision; everything else derives mechanically.

- **Display name** = directory with hyphens → spaces.
- **Slug** = lowercased directory → `<slug>.cgp` and `<slug>.html`.

**One refinement to the standard:** small words stay lowercase in the directory, so `Rainbow-on-the-Go` → "Rainbow on the Go" rather than the clumsy "Rainbow On The Go". Still purely mechanical, no judgment call.

### 8.1 Rename table — 24 plugins in scope

| Current directory | → Directory | Display name | Slug | `.cgp` name changes? |
|---|---|---|---|---|
| `Beepy` | `Voice-Alerts` | Voice Alerts | `voice-alerts` | yes |
| `apk-viewer` | `APK-Analyzer` | APK Analyzer | `apk-analyzer` | no |
| `bookshelf` | `Bookshelf` | Bookshelf | `bookshelf` | no |
| `client-time-tracker` | `Client-Time-Tracker` | Client Time Tracker | `client-time-tracker` | no |
| `code-suggestions-plugin` | `Code-Suggestions` | Code Suggestions | `code-suggestions` | yes |
| `compose-preview` | `Jetpack-Compose-Preview` | Jetpack Compose Preview | `jetpack-compose-preview` | yes |
| `cotg-ndk` | `CotG-NDK` | CotG NDK | `cotg-ndk` | yes |
| `flutter-template` | `Flutter-Templates` | Flutter Templates | `flutter-templates` | yes |
| `get-ai-models` | `Get-AI-Models` | Get AI Models | `get-ai-models` | no |
| `icons-repository` | `Icons-Repository` | Icons Repository | `icons-repository` | yes |
| `keystore-generator` | `Keystore-Generator` | Keystore Generator | `keystore-generator` | no |
| `layout-editor` | `Layout-Editor` | Layout Editor | `layout-editor` | no |
| `markdown-preview` | `Markdown-Previewer` | Markdown Previewer | `markdown-previewer` | no |
| `ndk-installer-plugin` | `NDK-Installer` | NDK Installer | `ndk-installer` | no |
| `pair-programming-plugin` | `Code-Together` | Code Together | `code-together` | yes |
| `project-to-template` | `Project-to-Template` | Project to Template | `project-to-template` | no |
| `python-tools` | `Python-Tools` | Python Tools | `python-tools` | no |
| `rainbow-on-the-go` | `Rainbow-Brackets` | Rainbow Brackets | `rainbow-brackets` | yes |
| `random-xkcd` | `Random-XKCD` | Random XKCD | `random-xkcd` | no |
| `sketch-to-ui-plugin` | `Sketch-to-UI` | Sketch to UI | `sketch-to-ui` | no |
| `snippets` | `Favorite-Snippets` | Favorite Snippets | `favorite-snippets` | yes |
| `speech-to-text-plugin` | `Speech-to-Text` | Speech to Text | `speech-to-text` | yes |
| `template-manager` | `Template-Manager` | Template Manager | `template-manager` | yes |
| `vector-search-plugin` | `Vector-Search` | Vector Search | `vector-search` | yes |

**Frozen in place (D11)** — still build and publish from the top level, slug unchanged: `ai-agent-gemini`, `ai-agent-local`, `ai-agent-mcp`, `ai-agent-openai`, `ai-core`, `ai-literacy-course`.

**Excluded (D12):** `pebble-custom-function-template-installer`.

### 8.2 Side effects

- `icons-repository` loses its hardcoded `plugin.version = 1.0.1` (the only plugin not using `${pluginVersion}`) and its odd `IconsRepository-Plugin.cgp` filename.
- `ndk-installer` drops "(64-bit only)" from its display name; that detail moves into the `addon.json` description.
- `apk-viewer`'s `applicationId` is `com.example.sampleplugin` under a plugin whose `plugin.id` is `com.example.apkanalyzer` — a copy-paste leftover. **Frozen anyway** per D09; noted for a future change.

### 8.3 The two NDK plugins

Both ship. Git history establishes which is which:

| Plugin | Introduced | By | Disposition |
|---|---|---|---|
| `ndk-installer-plugin` | 2026-04-23 | Joel Menchavez `<joelmenchavez@appdevforall.org>` | Ours, and first. Renames to `NDK-Installer`, follows the standard. |
| `cotg-ndk` | 2026-06-02 | Daniel Alome (ADFA-3596) | Second; a faster reimplementation. Preserved as `CotG-NDK` — `cotg` is retained deliberately even though first-party naming otherwise avoids the shorthand. |

Daniel Alome is an App Dev For All employee, so **both are first-party** (`origin: appdevforall`, author `danielalome@appdevforall.org`). The `community` origin remains in the schema, currently unused.

---

## 9. Out of scope

Each of these is a real improvement and none of them serves the two goals:

- **Shrinking the three giant plugins** (F03). Once artifacts are gone, size has no quota consequence.
- **`actions/cache` for Gradle** (F05). Real CI-minute waste; separate change.
- **Deduplicating the two HTML docs.** Rejected on the merits (§7.1), not deferred.
- **GitHub Releases** (D04).
- **`plugin.id` / namespace normalization** (D09).
- **Templates, snippets, and code-actions content.** This change only makes room.
- **Migrating the `ai-*` plugins** (D11). A follow-up once in-flight PRs land.

---

## 10. Acceptance criteria

1. `grep -r "upload-artifact" .github/` returns nothing.
2. `grep -ri "greengeeks" .` returns nothing; the three `GREENGEEKS_*` secrets/vars are deleted from repo settings.
3. A full `update-libs.yml` run publishes every in-scope plugin to `s3://addons/plugins/<slug>.cgp` with `Content-Type: application/octet-stream`.
4. Every plugin's gallery page is at `s3://addons/descriptions/<slug>.html` with `Content-Type: text/html`, and renders (does not download) in a browser.
5. `catalog.json` validates against `catalog.schema.json`; every record has `kind`, `sha256`, and `size` populated; `plugins.json` is the `kind == "plugin"` subset.
6. The gallery loads at `https://addons.appdevforall.org/index.html`, lists every in-scope plugin, and its download links resolve.
7. The bare hostname resolves to the gallery (Redirect Rule verified live, §6.3).
8. A second publish serves the *new* bytes, not cached ones — i.e. the purge call works.
9. `workflow_dispatch` on a single plugin produces `s3://addons/staging/<slug>.cgp` and prints the URL, with no artifact uploaded.
10. Every in-scope plugin's directory, `plugin.name`, `.cgp` filename, and doc filename agree per §8; a CI check fails the build on drift.
11. All six `ai-*` plugins still build and publish unchanged from the top level.
12. **Device verification:** at least one renamed plugin is downloaded from R2 on a device, installed via the Plugin Manager, and its feature exercised. Build success is not verification.

---

## 11. Risks

| ID | Risk | Mitigation |
|---|---|---|
| R01 | Redirect Rules may not apply to R2 custom domains (F06 leaves this unverified). | Verify during manual setup, before the cutover PR merges. Fallback: a ~15-line Worker. |
| R02 | Cached 404s persist (F07). Anything probing a URL before first publish sticks. | Manual validation upload happens *before* the custom domain is announced. |
| R03 | The hard cut (D03) has no rollback if R2 misbehaves. | Manual validation upload of one `.cgp` and one HTML page, confirmed served correctly over the custom domain, before the PR merges. |
| R04 | The mixed layout (§6.4) is a half-state that could persist indefinitely. | Track migrating the `ai-*` plugins as explicit follow-up work; the dual-root globs are transitional, not permanent. |
| R05 | Renaming 24 directories in one PR produces an unreviewable diff. | Sequence as a PR stack — plumbing first (R2 publish, artifact removal), then the move, then renames in batches. To be designed in the implementation plan. |
| R06 | A renamed plugin's tooltips silently break if `getTooltipCategory()` is touched. | D09 freezes `plugin.id`, so no category string changes. Verify on device (criterion 12) regardless. |

---

## 12. Manual setup (not code)

1. Create an R2 API token, **Object Read & Write**, scoped to `addons` only. Add `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` / `R2_ACCOUNT_ID` as repo secrets.
2. Create a second Cloudflare token with cache-purge permission on the zone. Add `CLOUDFLARE_PURGE_TOKEN` / `CLOUDFLARE_ZONE_ID`.
3. Connect the custom domain to the `addons` bucket (R2 → bucket → Settings → Public access → Custom Domains).
4. Add the zone Redirect Rule `/` → `/index.html`, and verify (R01).
5. Manually upload one `.cgp` and one HTML page; confirm both serve with correct `Content-Type` (R03).
6. After cutover: delete the `GREENGEEKS_*` secrets and vars.

---

## 13. Open items for the implementation plan

- The PR stack shape (R05).
- Whether `build_catalog.py` moves from `engineering-plumbing/extentions-webapp` or is rewritten against `addon.json` (D07 changes its input from a central `metadata.json` to per-plugin files).
- The exact `addon.json` schema and its CI validation.
- The naming-drift CI check (criterion 10).
