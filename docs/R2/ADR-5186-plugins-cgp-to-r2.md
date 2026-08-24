# ADR-5186 — Publish plugin `.cgp` to Cloudflare R2

**Status:** Proposed · **Ticket:** ADFA-5186
**Scope:** the storage steps of `update-libs.yml` **and** `build-plugins.yml`. Not the CoGo Gallery, not dev-assets, not the CoGo APK path (already on R2).

## Context

Two workflows put a plugin's `.cgp` on GitHub. `update-libs.yml` builds each `.cgp`, keeps it in a GitHub Release and an inter-job artifact, and also ships it to GreenGeeks by scp. `build-plugins.yml` uploads each `.cgp` twice as run artifacts (a bundle plus a per-plugin artifact).

The binding problem is the GitHub **artifact** quota: a couple of very large plugins nearly exhaust it in a single run, and `build-plugins.yml`'s double upload is the largest consumer. The GitHub **Release** draws on a separate quota and is not the problem.

## Decision — one cutover to R2 as the only plugin store

Cloudflare R2 becomes the single distribution store for plugin `.cgp`, in one change — no stacked PRs, no staged "additive-only" run:

1. **R2 is the store of record.** Upload each `.cgp` to R2 and verify each object landed at the expected size (the R2 equivalent of the GreenGeeks MD5 check the workflow runs today). Because R2 is now the only backend, a missing R2 credential **fails** the run rather than skipping silently.
2. **Stop the GitHub artifact uploads** that consume the quota — the inter-job artifact in `update-libs.yml` and the double `.cgp` upload in `build-plugins.yml`. This is why `build-plugins.yml` is in scope: it must change in sync, or the quota problem remains. Defining how it leaves the quota without stepping on production builds is the open decision below.
3. **Retire the GreenGeeks scp/MD5 publish.** R2 is the only backend; consumers are pointed at the R2 URL in the same change (below), so there is nothing to wait for.
4. **Keep the GitHub Release.** Its `.cgp` assets stay for traceability — a different quota from artifacts, so it is not the constraint.

## Storage layout

- Dedicated bucket **`addons`**.
- Prefix **`plugins/`** now; the same bucket later gains `templates/`, `snippets/`, and `code-actions/` (a prefix is a folder within the bucket).
- Public retrieval URL: `https://addons.appdevforall.org/plugins/<PLUGIN>.cgp`.
- **Object key** = the plugin's canonical website filename (the name `update-libs.yml` already normalizes to); that is the `<PLUGIN>` above.
- Publishing **overwrites** the key — latest wins, one current object per plugin. R2 keeps no version history; the versioned history lives in the GitHub Release (useful if necessary).
- Reuses the existing Cloudflare credentials and secret — no new credentials.

## Consequences

- Plugin `.cgp` has one verified store (R2 `addons`) at a stable public URL; GitHub and GreenGeeks stop being distribution stores on the publish path.
- The artifact-quota problem is actually solved: neither workflow keeps `.cgp` in GitHub artifact storage any more.
- Consumers — the website `/flags/plugins/` links and the app — are switched to the `addons.appdevforall.org` URL in sync with this change. The repoint happens now, not later.
- The GitHub Release remains for traceability; a rollback means re-enabling the previous publish steps.

## Alternatives considered

- **Stage it (keep both backends, retire GitHub/GreenGeeks in later PRs).** Rejected: the intended behavior is R2-only, and the team wants that now in one PR rather than a sequence of transitional states.

## Open decision — build-plugins presentation and cleanup

Getting `build-plugins.yml`'s `.cgp` off GitHub artifact storage is settled; it uploads to R2 like `update-libs.yml`. It builds arbitrary CodeOnTheGo refs (a single plugin or all) and publishes nothing to users, so two things remain to decide:

- **Presentation.** Preview builds must not overwrite the production `plugins/` keys — a separate prefix (e.g. `plugins-preview/`).
- **Cleanup (optional).** A new build overwrites the previous one, so active plugins do not pile up. Stale objects can be removed by date — an R2 lifecycle rule, or a scheduled workflow. Do it in a workflow, or leave it out of scope.

## Out of scope

- The CoGo Gallery, the dev-assets R2 migration, and the CoGo APK path (this last one already on R2).
