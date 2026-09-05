# Design — Addon Distribution & Repository Normalization

| | |
|---|---|
| **Status** | Draft for review |
| **Owner** | Hal Eisen, App Dev For All |
| **Date** | 2026-09-04 |
| **Implements** | [PRD — Addon Distribution & Repository Normalization](2026-08-27-addon-distribution-prd.md) |
| **Repo** | `appdevforall/plugin-examples` |

> **Scope of this document.** Mechanism. It says how the requirements in the PRD are met — file layouts, tool contracts, object keys, headers, workflow structure, and migration order. It does not restate the requirements; it references them by ID and closes with a traceability matrix covering all 49.

---

## 1. Summary

Three changes, in this order of importance:

1. **Built addons go straight from the build job to Cloudflare R2.** No GitHub Actions artifact is written at any point. This removes the quota problem permanently rather than managing it, and makes addon size irrelevant to it forever.
2. **R2 becomes the only published home** for artifacts, description pages, source tarballs, the catalog, and the gallery. GreenGeeks is removed from the delivery path.
3. **A single Python tool owns addon identity.** It derives names from directories, assembles source tarballs, generates and validates the catalog, and uploads. Because it runs offline against fixtures, the naming and metadata rules are enforced on **every pull request**, not discovered at release time.

The tool is the load-bearing piece. Everything else is configuration.

---

## 2. Decisions

Each was taken during design and is recorded with its rationale so a later reader does not relitigate it.

| ID | Decision | Rationale |
|---|---|---|
| **D01** | A `uv`-managed Python tool, with workflows as thin drivers. | The repo is bash + Actions YAML + Gradle today, so this adds a third toolchain. It buys the one thing bash cannot: the whole pipeline runs offline against fixtures, so `check-toolchain.yml` can enforce naming and metadata on every PR. Without that, R28 is a convention, not a check. The prototype already proved this shape works. |
| **D02** | Root routing by Cloudflare **Transform Rule**, not a Worker, not a redirect. | Configuration only — no deployable code, nothing to version. **Verified live** (§4, V01). A redirect would work but changes the visible URL to `/index.html`. |
| **D03** | No cache purge. Bounded staleness via `Cache-Control` at upload. | Cloudflare does not edge-cache HTML or JSON by default, so the catalog, the pages, and the downloads are already current the instant a publish completes. Only icons and tarballs need bounding. This removes the second credential that C04 anticipated: the design needs exactly **one** bucket-scoped token. |
| **D04** | Publishing is decoupled from the `libs/` rebuild. | Today shipping one fixed addon means a ~30-minute rebuild of everything. A separate publish workflow makes a one-addon hotfix a short job and gives R09 a natural home. |
| **D05** | A source tarball is a **two-level mini-repo**, mirroring this repository's own shape. | Every existing `../libs/plugin-api.jar` reference then resolves unchanged, so there is **zero path rewriting** — the largest source of silent breakage in X2/R43 simply does not arise. It also stops `ai-agent-local`'s own `libs/` from colliding with the shared one. |
| **D06** | Gallery chrome is injected at publish time, not committed. | The 31 existing pages stay plain HTML that anyone can edit. The published site is still coherent, with no duplicated chrome in git and no manual cache-bust chore. |
| **D07** | Icons publish as-is; CSS sizes the tile. | Ships icons now at no cost. The one 24×24 icon will look soft and gets a follow-up issue rather than blocking this work. |
| **D08** | R04 is amended to bounded staleness. | Follows from D03. New text in §12.4. |
| **D09** | Curated metadata lives in a per-addon `addon.json`; the directory name remains the sole source of identity. | R12 and R23. Anything derivable is derived, never written twice. |
| **D10** | Bucket CORS allows `GET`/`HEAD` from any origin. | The content is public, unauthenticated, and read-only, so an origin allowlist protects nothing — and an Android WebView sends `Origin: null`, which an allowlist would reject. |

---

## 3. What exists today, and what happens to it

| Existing | Disposition |
|---|---|
| `build-plugins.yml` `publish` job (matrix over ~29 plugins) | **Deleted.** This is P02 — ~15 GB of transfer per run producing nothing new. |
| `build-plugins.yml` `all-plugins-cgp` bundle upload | **Deleted.** This is the other half of P01. |
| `update-libs.yml` scp block + `GREENGEEKS_SSH_*` secret and vars | **Deleted.** This is P04. |
| `update-libs.yml` GitHub Release step | **Untouched**, by instruction. Releases keep shipping module-named `.cgp` files as they do now. |
| `update-libs.yml` website-filename staging loop with two legacy `case` arms | **Deleted.** Superseded by the identity rules in §6. |
| `scripts/update-libs.sh` | **Kept**, with one fix (§13.1) and its discovery block replaced by a call to the tool. |
| `scripts/check-toolchain.sh` | **Untouched.** Its broader module walk is deliberate and must not be merged with addon discovery. |
| The 31 top-level `*.html` description pages | **Kept and renamed** to `<slug>.html`. Content unchanged. |
| The 18 `src/main/assets/docs/index.html` in-IDE pages | **Untouched.** Different purpose, shipped inside the `.cgp`. |
| `libs/` | **Kept** as the shared source of truth. Not vendored per addon. |
| Four copies of the discovery predicate, three copies of the skip list | **Replaced** by one implementation (§8.1). |

---

## 4. Verified platform facts

Every fact the design depends on was checked. Nothing below is assumed.

| ID | Fact | Evidence |
|---|---|---|
| **V01** | A Transform Rule rewrites `/` to `/index.html` on the R2 custom domain. | **Verified live** against `https://addons.appdevforall.org/`: 200, byte-identical body and identical `last-modified` to `/index.html`, no 3xx, no `Location`. |
| **V02** | The rule needs no regex, so the Cloudflare **Free** plan suffices (10 active rules). | Expression `http.request.uri.path eq "/"`, action Rewrite → Path → Static → `/index.html`. |
| **V03** | R2 has no static-website hosting. `PutBucketWebsite` is unimplemented. | Cloudflare S3 API compatibility table. This is why V01 is necessary. |
| **V04** | Cloudflare does not edge-cache HTML or JSON by default. | Documented, and **confirmed live**: the root response carried `cf-cache-status: DYNAMIC`. |
| **V05** | R2 stores and honors `Content-Type`, `Cache-Control`, and `Content-Disposition` set as system metadata on `PutObject`. | Cloudflare S3 API compatibility table. |
| **V06** | Files over 512 MB are never edge-cached on Free/Pro/Business. | Affects the three large addons (P03). Irrelevant — egress is unmetered. |
| **V07** | A custom domain connected to a bucket with a CORS policy returns CORS headers automatically. | Cloudflare R2 CORS documentation, verbatim. |
| **V08** | The AWS SDK CRC32 checksum incompatibility is **historical**. | Real in January 2025; Cloudflare removed every workaround note on 2025-02-11 with the rationale "now that R2 is compatible for most clients". Current boto3 `PutObject` needs no flag. **Do not carry that workaround into the code.** |
| **V09** | `PutBucketCors` still rejects the checksum header current SDKs send. | Configure CORS via the dashboard or `wrangler`, never `aws s3api put-bucket-cors`. |
| **V10** | Changing a CORS policy on a bucket already serving traffic requires one cache purge for the hostname. | One-time setup step. HTML and JSON are unaffected (V04). |
| **V11** | The CORS policy in §12.5 is applied and behaves correctly. | **Verified live**: a GET with an `Origin` returns `access-control-allow-origin: *` and the four expected `expose-headers`; an `Origin: null` request (the WebView case) is allowed; an `OPTIONS` preflight requesting `Range` returns 204 with `allow-methods: GET, HEAD` and `max-age: 86400`; a request with no `Origin` returns no CORS headers. |
| **V12** | Cloudflare injects `/cdn-cgi/challenge-platform/scripts/jsd/main.js` into HTML responses on this zone. | Bot Fight Mode JavaScript Detections is enabled. Detecting only, not blocking — a plain `curl` received a clean 200. See risk K10. |

---

## 5. Repository layout

```
plugins/<Addon-Name>/       migrated addons (R30)
  addon.json                curated metadata (§7)
  <slug>.html               gallery description page (R32, R35)
  build.gradle.kts
  settings.gradle.kts
  src/main/...
templates/                  reserved, README only (R30)
snippets/                   reserved, README only
code-actions/               reserved, README only
libs/                       shared jars, unchanged
site/                       gallery source (§11)
  index.html
  app.js
  styles.css
  catalog.schema.json
  assets/
tools/addons/               the Python tool (§8)
  pyproject.toml
  uv.lock
  src/addons/
  tests/
  fixtures/
scripts/                    existing bash, unchanged except §13.1
```

The three reserved directories are created empty with a README in this change. Q4 in the PRD — whether the other addon types ultimately live in this repository at all — stays open; reserving the names costs nothing and settles nothing.

---

## 6. Identity derivation

The directory name is the only input. Everything else is computed. There is no override (R27).

| Derived value | Rule | Example |
|---|---|---|
| Display name | Directory, hyphens replaced by spaces | `Rainbow-on-the-Go` → `Rainbow on the Go` |
| Slug | Directory, lowercased | `Rainbow-on-the-Go` → `rainbow-on-the-go` |
| Artifact key | `dl/<slug>.cgp` | `dl/rainbow-on-the-go.cgp` |
| Page key | `p/<slug>.html` | `p/rainbow-on-the-go.html` |
| Tarball key | `src/<slug>-src.tar.gz` | `src/rainbow-on-the-go-src.tar.gz` |
| Icon key | `p/<slug>.png` | `p/rainbow-on-the-go.png` |
| Type | Parent directory, singularised | `plugins/` → `plugin` |

Directory names use MixedCase words separated by hyphens, with small words left lowercase (R24). The small-word list is a constant in the tool, not a per-addon setting.

`addons check` fails when any of the following disagree with the directory: the `pluginName` configured in `build.gradle.kts`, the `plugin.name` in `AndroidManifest.xml`, the description-page filename, or the `<title>` of that page. This is the enforcement R28 requires, and it runs on every pull request.

**Runtime identity is explicitly out of scope of this derivation.** `plugin.id`, `namespace`, and `applicationId` are never touched (R29). A rename changes what a user reads; it must not change what the IDE keys on. `addons check` treats a changed `plugin.id` as an error.

---

## 7. `addon.json`

One per addon, beside `build.gradle.kts`. It holds only what cannot be derived.

```json
{
  "summary": "Colours matching brackets in the editor.",
  "description": "A longer paragraph shown on the card and the description page.",
  "tags": ["editor", "readability"],
  "origin": "appdevforall",
  "license": "AGPL-3.0-or-later",
  "minAppVersion": "1.4.0"
}
```

Community addons carry an author (R15, R36–R38):

```json
{
  "origin": "community",
  "author": { "name": "Aman Khan", "url": "https://github.com/aman-khan-786/cotgx-ndk" }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `summary` | string, 1–120 chars | yes | One line. Card text. |
| `description` | string | yes | Paragraph. |
| `tags` | array of `^[a-z0-9][a-z0-9-]*$`, ≥1 | yes | Lowercase, hyphenated. |
| `origin` | `appdevforall` \| `community` | yes | |
| `license` | SPDX identifier | yes | |
| `author` | `{ name, url? , email? }` | when `origin` is `community` | `email` only with that person's consent (R37). |
| `minAppVersion` | string | no | |

`additionalProperties` is false. An unknown key is an error, not a warning — a typo that silently drops a field is exactly the failure R16 forbids.

**Never in this file:** name, slug, type, any URL, any checksum, any size, or a version. Each is derived or computed. Writing one by hand creates a second source of truth, which is what P05 already cost us once.

### 7.1 Bootstrapping the 31 files

`addons scaffold` drafts an `addon.json` for each addon by extracting the first paragraph of its existing description page and the `plugin.description` from its manifest. **The output is a draft.** Every file is reviewed by a human before commit; nothing is auto-accepted into the catalog. This is a one-time migration aid, not part of the publish path.

---

## 8. The tool — `tools/addons/`

One `uv` project, one console entry point, seven subcommands. They share the addon model and the identity rules; splitting them into separate scripts would recreate the duplication that F06 already documents.

```
uv run --directory tools/addons addons <subcommand> [options]
```

### 8.1 `addons discover`

The single implementation of the build-file predicate and the skip list. Scans both `*/build.gradle.kts` and `plugins/*/build.gradle.kts` for `com.itsaky.androidide.plugins.build`, so addons can move in batches (R31). Emits JSON on stdout.

`scripts/update-libs.sh` and `.githooks/pre-push` call it instead of carrying their own copies. The skip list moves into `tools/addons/skip.txt`, one name per line with a required reason comment — today's single entry has no recorded reason, which is how it came to look published while never being built.

`scripts/check-toolchain.sh` keeps its own broader module walk. Its header explains why: subprojects such as `ai-core/llama-api` are invisible to a top-level-directory rule yet must still obey the toolchain. **Do not unify these two.**

### 8.2 `addons check`

Offline. No network, no build. Verifies:

- identity agreement across directory, `pluginName`, manifest `plugin.name`, page filename, and page `<title>` (R28);
- `addon.json` present, parseable, and schema-valid (R16);
- `author` present when `origin` is `community` (R15);
- `plugin.id`, `namespace`, and `applicationId` unchanged against the merge base (R29);
- the description page exists and is non-empty (R34).

Exit non-zero with one line per failure naming the file and the disagreement. Runs on every pull request.

### 8.3 `addons tarball <slug>`

Assembles one source tarball (§9).

### 8.4 `addons catalog`

Generates `catalog.json` (§10).

### 8.5 `addons page <slug>`

Wraps a description page in gallery chrome (§11.2).

### 8.6 `addons publish`

Uploads to R2 (§12).

### 8.7 `addons scaffold`

One-time metadata drafting (§7.1).

### 8.8 Testing

`pytest` over fixtures, no network. Every subcommand except `publish` runs fully offline. `publish` is tested against a stubbed S3 client; its argument construction — keys, `Content-Type`, `Cache-Control`, `Content-Disposition` — is asserted directly, because those are the values that silently produce the wrong browser behavior (R05) and cannot be checked any other way before a real upload.

The test suite runs in `check-toolchain.yml` alongside `addons check`.

---

## 9. Source tarballs

### 9.1 Shape

A tarball is a **two-level mini-repo**: the same shape as this repository, reduced to one addon.

```
rainbow-on-the-go-src/
  README.md                    generated (§9.4)
  gradlew                      copied from the repo root
  gradlew.bat
  gradle/wrapper/              copied from the repo root
  libs/
    plugin-api.jar             only the jars this addon references
    gradle-plugin.jar
  Rainbow-on-the-Go/
    build.gradle.kts           unmodified
    settings.gradle.kts        unmodified
    src/...
```

Build instructions are then literally the repository's own: `cd Rainbow-on-the-Go && ../gradlew assemblePlugin`.

This is the point of D05. Every `../libs/plugin-api.jar` reference already resolves, so **no file is rewritten** (R43 is met by construction, not by editing). `ai-agent-local/libs/llama-api.jar` and `pair-programming-plugin/libs/shared.jar` stay where they are inside the addon directory and never collide with the shared `libs/` one level up (X2).

### 9.2 Deriving the jar set (R41)

A fixed list is not acceptable, because the root `libs/` holds five jars and different addons reference different subsets. The tool parses `build.gradle.kts` and `settings.gradle.kts` for references matching `\.\./libs/([A-Za-z0-9._-]+\.jar)` in `compileOnly(files(...))` and buildscript `classpath(files(...))` positions, and copies exactly that set.

A referenced jar missing from `libs/` is a hard error (R47).

### 9.3 File selection (R44)

The addon's file list comes from `git ls-files` for that directory, **not** from the working tree.

This is deliberate and load-bearing. `local.properties` is gitignored but present on disk in 27 addon directories, and contains a developer's SDK path **and Sentry DSNs**. Sourcing from the index makes that leak structurally impossible rather than filtered — there is no exclusion list to forget to update. `build/`, `.gradle/`, and every other ignored path are excluded by the same mechanism.

### 9.4 Generated `README.md`

States the build command, the addon's license, its origin and author where applicable, and the URL it came from. For any addon that cannot be made self-contained, it states the prerequisite **at the top**, before the build instructions (R46).

Exactly one addon is in that category today: `ai-agent-local` depends on the `subprojects/llama.cpp` git submodule, which `git ls-files` does not descend into. Its README leads with the clone command for that submodule. This is the single deliberate exception to R40.

### 9.5 Fetched assets (R45)

Addons that fetch large assets at build time keep their `downloadAssets` task. The assets are not bundled. Those fetches must resolve to a host App Dev For All controls; moving them to the `addons` bucket is in scope of this change and is what makes K08 acceptable.

### 9.6 Verification (R48)

After assembly and before upload, every tarball is checked:

1. every jar named in the build files is present in `libs/`;
2. no member path escapes the tarball root, and no path is absolute;
3. `gradlew` and `gradle/wrapper/gradle-wrapper.properties` are present;
4. no `local.properties`, no `.gradle/`, no `build/`, no file matching a credential pattern;
5. `build.gradle.kts` and `settings.gradle.kts` are present in the addon directory.

Any failure aborts the run (R47). Nothing partial is uploaded.

**What this does not prove.** These checks establish that a tarball is well-formed. They do not establish that it compiles — a missing transitive dependency or a toolchain mismatch would pass all five and still fail at build time. This limit is accepted knowingly and recorded as K07. A periodic build-one-tarball job is the obvious later mitigation and is out of scope here.

---

## 10. The catalog

### 10.1 Generation

`addons catalog` reads every discovered addon's `addon.json`, derives identity from the directory, computes `sha256` and `size` for the built `.cgp` **and** the tarball (R14, R49), and emits `catalog.json`. It validates the result against `site/catalog.schema.json` and fails on any invalid or missing record (R16). Nothing is hand-maintained (R11).

```json
{
  "schemaVersion": 1,
  "generated": "2026-09-04T18:00:00Z",
  "items": [
    {
      "type": "plugin",
      "slug": "rainbow-on-the-go",
      "name": "Rainbow on the Go",
      "summary": "Colours matching brackets in the editor.",
      "description": "…",
      "origin": "appdevforall",
      "license": "AGPL-3.0-or-later",
      "tags": ["editor", "readability"],
      "iconUrl": "p/rainbow-on-the-go.png",
      "pageUrl": "p/rainbow-on-the-go.html",
      "sourceUrl": "https://github.com/appdevforall/plugin-examples/tree/main/plugins/Rainbow-on-the-Go",
      "download": { "url": "dl/rainbow-on-the-go.cgp", "sha256": "…", "size": 1234567 },
      "sourceTarball": { "url": "src/rainbow-on-the-go-src.tar.gz", "sha256": "…", "size": 234567 }
    }
  ]
}
```

`type` is present on every entry from the first release, so templates, snippets, and code actions join later without a schema change (R13). `schemaVersion` exists so a fielded in-app consumer can refuse a catalog it does not understand.

Cross-record rules enforced in code rather than schema: `slug` is unique, and `author` is present whenever `origin` is `community`.

### 10.2 Version

PRD Q2 — whether the catalog carries a per-addon version — is **deferred, not answered**. No `version` field ships in `schemaVersion: 1`. Adding one later is additive and breaks nothing; adding one now would require deciding where the value comes from, and the manifest's `${pluginVersion}` injection is not yet consistent across all 31 addons. Recorded as an open item (§17).

---

## 11. The gallery

### 11.1 The application

Carried forward from the prototype, whose core decisions were right: a vanilla ES module with no framework and no build step, all record text rendered through `textContent` and never `innerHTML`, filters synchronised to the URL so a filtered view is shareable (R21), debounced search, and an explicit error state instead of a blank page (R22).

Changes:

| Change | Requirement |
|---|---|
| Type and origin indicated by a glyph or shape, not colour alone. Today they are colour-only pills. | R20 |
| Addon icons shown on cards. | R17 |
| Sort by name. There is no sort at all today; cards render in file order. | — |
| `plugins.json` and `templates.json` dropped. Generated, committed, and served today, but consumed by nothing. | — |
| Unused `icon` field dropped from the schema in favour of the derived `iconUrl`. | — |
| Asset filenames carry a content hash. | §12.1 |

Mobile-first layout is already the prototype's design and is retained (R19).

### 11.2 Description pages

The 31 existing pages are self-contained HTML with an inline `<style>`. They stay that way in git — an author writes plain HTML and nothing else.

At publish time, `addons page` extracts the `<body>` content and wraps it in the gallery's header, footer, and shared stylesheet link, producing the object served at `p/<slug>.html`. The result is coherent with the gallery and always links back to it.

Two consequences worth stating. The published page differs from the file in git, so anyone diffing them will see the chrome; this is intended. And no chrome is duplicated across 31 files in the repository, which is precisely the maintenance cost the prototype incurred.

The in-IDE documentation at `src/main/assets/docs/index.html` is a different artifact for a different purpose and is not touched, not published, and not related (R32, R35).

---

## 12. Cloudflare R2

### 12.1 Object layout and headers

| Key | `Content-Type` | `Cache-Control` | Extra | Edge-cached? |
|---|---|---|---|---|
| `index.html` | `text/html` | `public, max-age=60` | | No (V04) |
| `catalog.json` | `application/json` | `public, max-age=60` | | No (V04) |
| `assets/app.<hash>.js` | `text/javascript` | `public, max-age=31536000, immutable` | | Yes |
| `assets/styles.<hash>.css` | `text/css` | `public, max-age=31536000, immutable` | | Yes |
| `p/<slug>.html` | `text/html` | `public, max-age=60` | | No (V04) |
| `p/<slug>.png` | `image/png` | `public, max-age=60` | | Yes |
| `dl/<slug>.cgp` | `application/octet-stream` | `public, max-age=60` | `Content-Disposition: attachment; filename="<slug>.cgp"` | No |
| `src/<slug>-src.tar.gz` | `application/gzip` | `public, max-age=60` | `Content-Disposition: attachment; filename="<slug>-src.tar.gz"` | Yes |
| `staging/<run-id>/…` | as above | `public, max-age=60` | as above | as above |

`Content-Type` is set explicitly on every object and never left to inference (C05). `Content-Disposition: attachment` on the two binary classes is what makes them download while pages render (R05).

The content hash in the asset filenames retires the prototype's manual `?v=` bump across ten files, and means a stylesheet change is never stale and never re-fetched unnecessarily.

### 12.2 Root routing

A zone Transform Rule: when `http.request.uri.path eq "/"`, rewrite path to the static value `/index.html`. Configuration only, Free plan, no regex. **Already in place and verified live** (V01).

### 12.3 Staging (R09)

`publish-addons.yml` with `staging: true` writes under `staging/<run-id>/` and prints the URLs to the run summary. A maintainer gets one addon on demand, at a real URL, with no artifact behind it. Staging keys are never referenced by the catalog.

Lifecycle: a bucket rule expires `staging/` objects after 30 days. Published keys have no expiry.

### 12.4 Freshness

Because Cloudflare does not edge-cache HTML or JSON by default (V04), the catalog, every description page, and every `.cgp` are current the instant a publish completes. Only icons, tarballs, and the hashed assets are cached, and the first two are bounded at 60 seconds by their `Cache-Control`. The hashed assets are never stale because their key changes with their content.

**This requires amending R04.** Its current text — "Stale content must never be served after a publish completes" — describes a purge-based design. New text:

> **R04** Published content must become current within 60 seconds of a publish completing, without manual intervention.

C04's corollary also drops. It anticipated that "publishing and cache invalidation need separate credentials"; with no invalidation step, the design needs exactly one bucket-scoped token (R08).

### 12.5 CORS (R07)

One bucket-wide rule. S3-style CORS has no key-prefix condition, so per-path policies are neither possible nor needed.

```json
[
  {
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["Range"],
    "ExposeHeaders": ["ETag", "Content-Disposition", "Accept-Ranges", "Content-Range"],
    "MaxAgeSeconds": 86400
  }
]
```

`AllowedOrigins: ["*"]` because the content is public, unauthenticated, and read-only — there is no cookie or credential for an allowlist to protect — and because an Android WebView loading local content sends `Origin: null`, which an explicit allowlist would reject. `Access-Control-Allow-Credentials` is deliberately absent: it is invalid alongside `*`, and nothing here is authenticated.

`Range` is listed because it is not a CORS-safelisted request header, so a resumable download would otherwise fail its preflight. Only four response headers need exposing; `Content-Type`, `Content-Length`, `Cache-Control`, and `Last-Modified` are safelisted already.

Applied once, out of band, via the dashboard or `wrangler r2 bucket cors set addons --file addons-cors.json` — **not** `aws s3api put-bucket-cors`, which R2 rejects (V09). Followed by one cache purge for the hostname (V10).

CORS governs browsers only. A native Android download ignores it entirely; this policy exists for a page on `appdevforall.org` and for a future in-app browser if that browser is a WebView.

### 12.6 Credentials

One R2 API token, scoped to **Object Read & Write on the `addons` bucket only**, with no account-level permission (R08). Stored as three repository secrets: account id, access key id, secret access key. The token cannot list other buckets, cannot alter bucket configuration, and cannot purge cache — it does not need to.

No `AWS_REQUEST_CHECKSUM_CALCULATION` workaround is used. That incompatibility was resolved upstream and Cloudflare removed its own guidance about it (V08); carrying it forward would be cargo cult.

### 12.7 Upload verification

After uploading, the tool re-reads each object's size and `ETag` and compares against what it sent. A mismatch fails the run. This carries forward the remote-versus-local MD5 check the scp deploy already performs — the one genuinely good pattern in the code being replaced.

---

## 13. Workflows

### 13.1 `update-libs.yml`

Returns to its name: refresh `libs/`, commit, cut a release. The scp block, the SSH key setup and teardown, the remote MD5 check, the website-filename staging loop, and the `GREENGEEKS_SSH_*` secret and variables are all deleted (G02, R10, P04). The GitHub Release step is untouched.

**One bug is fixed here.** `scripts/update-libs.sh` copies two jars into `libs/`, but `libs/` holds five. `common.jar`, `eventbus-events.jar`, and `idetooltips.jar` are never refreshed and can silently drift from the CodeOnTheGo build they were taken from. Since R41 now makes those jars part of a published deliverable, the drift stops being cosmetic. The script refreshes all five.

### 13.2 `build-plugins.yml`

The `publish` job and the bundle upload are deleted. No `upload-artifact` or `download-artifact` step remains anywhere in the repository (R01), which is what makes G01 measurable as zero.

The capability that job provided — a maintainer downloading one built addon from the run summary — is not lost. It moves to `publish-addons.yml` with `staging: true`, which is strictly better: a URL anyone can click, rather than a zip only a logged-in maintainer can retrieve.

### 13.3 `publish-addons.yml` (new)

`workflow_dispatch`. Inputs: `addon` (a slug, or `all`) and `staging` (boolean).

```
checkout → set up JDK 17 + Gradle → uv sync
  → addons check
  → build (downloadAssets when present, then assemblePlugin)
  → addons tarball
  → addons catalog
  → addons page
  → addons publish
  → print URLs to the run summary
```

The build step reuses the loop already in `scripts/update-libs.sh`, including its two hard-won rules: fall back to the repo-root wrapper when an addon has no local `gradlew`, and run `downloadAssets` before `assemblePlugin` whenever the build file references it. A bare `assemblePlugin` silently packages a broken artifact for those addons.

`addons check` runs **first**. A naming or metadata error should cost seconds, not a full build.

### 13.4 `check-toolchain.yml`

Gains `uv sync`, `addons check`, and the tool's `pytest` suite. This is the only workflow that runs automatically on pull requests, which makes it the only place naming and metadata rules can actually be enforced (R28).

---

## 14. Migration sequence

Ordered so that every step is reversible until the last one.

1. **Land the tool and the checks.** `tools/addons/`, `site/`, the schema, the tests, and the `check-toolchain.yml` wiring. Nothing publishes yet. `addons check` is advisory on this pass.
2. **Land metadata.** 31 reviewed `addon.json` files. `addons check` becomes blocking.
3. **Rename and move.** Directories renamed to the convention, description pages renamed to `<slug>.html`, addons moved under `plugins/`. `plugin.id`, `namespace`, and `applicationId` are untouched (R29). Discovery already handles both locations (R31), so this can proceed in batches.
4. **Publish to staging and verify by hand.** One addon, one page, one tarball: the page renders, the artifact downloads, the tarball extracts and builds, the catalog validates, the gallery loads. This is K03's mitigation and it is not optional.
5. **First full publish**, and apply the CORS policy plus its one-time purge.
6. **Repoint the website's links** at `addons.appdevforall.org`.
7. **Delete the scp step** and the GreenGeeks secrets from CI.
8. **Revoke the GreenGeeks credentials.** Last, not first.

Steps 1–3 are independent of Cloudflare entirely and can land while step 4 is still being arranged.

---

## 15. Risks

Carried from the PRD where still live, plus what the design introduces.

| ID | Risk | Handling |
|---|---|---|
| **K01** | Root routing not achievable by configuration. | **Closed.** Verified live (V01). |
| **K02** | A "not found" response cached before first publish persists. | HTML is not edge-cached (V04), so this cannot happen for pages or the catalog. Verify before announcing the address regardless. |
| **K03** | Switching hosts in one step leaves no rollback. | Migration step 4 — hand verification of one complete addon before any link is repointed. GreenGeeks stays live until step 8. |
| **K04** | A partial migration becomes permanent. | Discovery spans both locations by design (R31), which is what makes stalling possible. Remaining addons are tracked as explicit follow-up work. |
| **K05** | A renamed addon's in-app help breaks silently with a green build. | R29 prevents the known cause and `addons check` enforces it. Device verification is still required — a green build is not verification for anything that mutates IDE state. |
| **K06** | Crediting a community contribution incorrectly. | R36–R38 and the `author` requirement in §7. PRD Q1 stays open and blocks `cotg-ndk`, which remains on the skip list. |
| **K07** | Structural verification proves well-formed, not compilable. | Accepted knowingly (§9.6). A periodic build-one-tarball job is the later mitigation. |
| **K08** | Two addons' tarballs depend on fetched assets remaining available. | Those assets move to the `addons` bucket under this change, so the dependency is on infrastructure we control. |
| **K09** | Python is a third toolchain in a bash-and-Gradle repository. | Confined to `tools/addons/`, pinned by `uv.lock`, and exercised on every PR by its own test suite. The alternative — untested shell — was rejected in D01. |
| **K10** | Bot Fight Mode could challenge non-browser clients. | JavaScript Detections is enabled on the zone and currently detects without blocking. If Super Bot Fight Mode's block action is ever turned on, an in-app addon browser or an in-IDE download — neither of which runs JavaScript — would receive a challenge instead of the file. This hostname must stay exempt from bot challenges. |
| **K11** | `addons check` becomes a merge blocker on unrelated work. | It only inspects addon directories and only fails on identity or metadata disagreement. A change touching neither cannot trip it. |

---

## 16. Requirements traceability

| Req | Satisfied by |
|---|---|
| R01 | §13.2 — every artifact step deleted; no upload/download-artifact remains |
| R02 | §12.1 — all five object classes live in the `addons` bucket |
| R03 | §6 — key derived from slug, stable across releases |
| R04 | §12.4 — amended; HTML/JSON uncached, others bounded at 60 s |
| R05 | §12.1 — explicit `Content-Type` plus `Content-Disposition: attachment` on binaries |
| R06 | §12.2 — Transform Rule, verified live |
| R07 | §12.5 — bucket CORS policy |
| R08 | §12.6 — one bucket-scoped token, no account access |
| R09 | §12.3, §13.2 — staging prefix with URLs in the run summary |
| R10 | §13.1 — GreenGeeks removed from the delivery path |
| R11 | §10.1 — catalog generated, never hand-maintained |
| R12 | §7 — per-addon `addon.json` |
| R13 | §10.1 — `type` on every entry from release one |
| R14 | §10.1 — checksum and size computed at publish |
| R15 | §7, §8.2 — `author` required for community origin, enforced by `check` |
| R16 | §8.2, §10.1 — schema-validated, fails loudly, `additionalProperties: false` |
| R17 | §11.1 — card fields |
| R18 | §11.1 — combining type, tag, and free-text filters |
| R19 | §11.1 — mobile-first layout retained |
| R20 | §11.1 — glyph or shape, not colour alone |
| R21 | §11.1 — filter state synchronised to the URL |
| R22 | §11.1 — explicit error state |
| R23 | §6 — directory is the sole source of identity |
| R24 | §6 — MixedCase with lowercase small words |
| R25 | §6, §8.2 — one display name, agreement enforced |
| R26 | §6 — slug used unchanged for artifact and page |
| R27 | §6 — no override mechanism exists |
| R28 | §8.2, §13.4 — `addons check` on every pull request |
| R29 | §6, §8.2 — runtime identity never derived and never changed |
| R30 | §5 — `plugins/` plus three reserved siblings |
| R31 | §8.1 — discovery spans both locations |
| R32 | §11.2 — two pages, separate purposes, only one published |
| R33 | §14 — documentation updated during migration; CLAUDE.md's stale `MAP` instruction corrected |
| R34 | §8.2 — both pages required, existence checked |
| R35 | §11.2 — only the gallery page is published |
| R36 | §7 — `author` credits the originating contributor |
| R37 | §7 — name and URL required, email only by consent |
| R38 | §6 — a community addon's original naming is preserved |
| R39 | §9 — a tarball per published addon |
| R40 | §9.1 — two-level mini-repo builds standalone |
| R41 | §9.2 — jar set derived per addon by parsing build files |
| R42 | §9.1 — root wrapper copied in |
| R43 | §9.1 — met by construction; no path is rewritten |
| R44 | §9.3 — file list from `git ls-files` |
| R45 | §9.5 — fetch step retained, assets move to our host |
| R46 | §9.4 — prerequisite stated at the top of the README |
| R47 | §9.2, §9.6, §10.1 — every failure aborts the run |
| R48 | §9.6 — five structural checks on every tarball |
| R49 | §10.1 — tarball checksum and size in the catalog |

---

## 17. Open items

Nothing below blocks implementation of §14 steps 1–3.

| ID | Item | Effect |
|---|---|---|
| **O1** | PRD Q1 — how Aman Khan wishes to be credited, and whether the addon is CotGX NDK. | Blocks publishing `cotg-ndk`. It stays on the skip list until answered. Everything else proceeds. |
| **O2** | PRD Q2 — a per-addon version in the catalog. | Deferred (§10.2). Additive later. |
| **O3** | PRD Q4 — whether templates, snippets, and code actions live in this repository. | Reserved directory names cost nothing and decide nothing. |
| **O4** | The 24×24 icon on `sketch-to-ui-plugin`, and the two at 96×96. | Cosmetic. Follow-up issue, not a blocker (D07). |
| **O5** | `pebble-custom-function-template-installer` ships stale copies of both shared jars and is the sole skip-list entry with no recorded reason. | Cleanup during migration step 3. |
| **O6** | A periodic job that actually builds one published tarball. | The real mitigation for K07. Out of scope here. |
