# ADR-5186 — Publish plugin `.cgp` to Cloudflare R2

**Status:** Proposed · **Ticket:** ADFA-5186
**Scope:** the storage steps of `update-libs.yml`. Not the Gallery, not dev-assets, not `build-plugins.yml`.

## Context

`update-libs.yml` builds each plugin's `.cgp` and then keeps a copy on GitHub in two places (an inter-job artifact and GitHub Release assets) while also shipping it to GreenGeeks by scp. An R2 upload step already exists in the deploy job — additive and guarded, so it skips cleanly until the credentials are set. The remaining work is to make R2 the store of record and stop keeping the `.cgp` on GitHub, without a destructive one-step cutover.

## Decision — staged, additive first

1. **Now (additive).** After the existing R2 upload, verify each object landed at the expected size — the R2 equivalent of the GreenGeeks MD5 check the workflow already runs. Nothing else changes; both backends keep publishing, so a run today behaves exactly as it does now.
2. **After R2 is validated.** Drop the `.cgp` assets from the GitHub Release (the tag and note stay for traceability). This is the step that actually stops the GitHub-side storage.
3. **Later, and dependent.** The GreenGeeks scp/MD5 steps retire only once the consumers — the website `/flags/plugins/` links and the app — point at R2. That repoint is outside this ticket, so scp stays for now.

Config is reused as-is: bucket `apk-repo`, prefix `plugins/`, the existing `CLOUDFLARE_*` variables and secret. No new credentials. No `.cgt` is built in CI today; the same prefix serves it if template publishing arrives later.

## Consequences

- The published `.cgp` gains a verified store on R2, and GitHub stops being a distribution store on the publish path once step 2 lands.
- The change is reversible per step: R2 stays additive until it verifies, and each retirement is its own small PR.
- The headline artifact-quota problem is **not** solved here — it lives in `build-plugins.yml`, which this ticket leaves untouched (see below). This is a deliberate, recorded trade-off.

## Alternatives considered

- **Move `build-plugins.yml` to R2 too.** Rejected for now: it changes the run-summary download UX and mixes ad-hoc dev builds into the published store.
- **Keep `.cgp` on the GitHub Release as the store.** Rejected as the end state, but kept transitionally until R2 is validated.

## Out of scope

- `build-plugins.yml` — unchanged. It is the larger quota consumer (each `.cgp` is uploaded twice: a bundle plus a per-plugin artifact). If quota becomes the binding constraint, the follow-up is to stop that double upload — a separate decision, not this ticket.
- dev-assets R2 migration (belongs to the GreenGeeks-retirement effort); the CoGo Gallery; the CoGo APK path (already on R2).

## Open questions

- Dedicated R2 bucket vs. a prefix inside `apk-repo` (blast radius / lifecycle).
- Reconciling the `R2_*` vs `CLOUDFLARE_*` variable names (same values, different names).
