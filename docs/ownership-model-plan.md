# Document Ownership Model — Policy & Bottleneck Analysis

## Status (rolling)

Updated after the last work session, before pausing for field testing. Pick
up from here.

### Done

| Item | Scope | App commit | Server commit | What it gives |
|---|---|---|---|---|
| **LOADED-actual fixes** | mergeDocumentLines coercion + startup scrub; ForceReleaseLock / UpdateState / ForceTransitionState line clearing on LOADED; UpsertDocument LOADED-bypass of preserveLineActuals; migrations 004 / 005 | `cf0fe1e` (predecessor `6179d04`) | `7b7086a` | Phantom Факт > 0 on LOADED docs is impossible on new emissions and scrubbed for existing rows. |
| **Debug-journal auto-reset removed** | Manual control only; legacy expiry scrubbed via migration 005. | — | bundled in `7b7086a` | Tenant admin's toggle stays on until explicitly turned off. |
| **M5″** — silent re-lock with bounded retry | DOCUMENT_UPDATE rejection no longer triggers the FORBIDDEN→reconnect loop. Orchestrator re-issues STAGE_LOCK 3× / 10s; on success drains dirty edits, on give-up drops them, journals at ERROR, refreshes from server, shows AlertDialog with the dropped magnitude. | `cf0fe1e` | `989c094` | Worker scanning into a yanked doc recovers silently when possible; sees one banner + nav-back when not. |
| **M3** — held locks in `USER_LOGIN_RESULT` | Server returns the external_ids of docs locked by this `(user, device)` pair; app reasserts `heldStageLocks` before the first delta sync fires. | `a448451` | `4fdca69` | Process-death / WS-reset no longer leaves a window where inbound SYNC_DATA can overwrite worker-owned line data. |
| **M1** — cooperative force-release with hard fallback | `POST .../force-release?mode=cooperative` (default) sends `FORCE_RELEASE_REQUEST` to the device, polls for the device's STAGE_UNLOCK up to 10s, returns 200/`released_cooperative` or 408/`device_unresponsive`. Tenant-web prompts hard-release on 408. Device drops dirty edits + sends unlock + shows the LockLost banner. | `c1ddfd8` | `7f41063` |  Admin "Release lock" now negotiates a clean release when the device is online; falls back to the legacy hard yank when it's not, with the magnitude of lost data shown to both sides. |
| **M6** — preserveLineActuals matches by `product_id`, preserves line `_id` | One-shot fix that also covers M7 (renumber-tolerance). Defensive fallback to `line_number` for malformed legacy data. New unit tests for ID preservation, renumber, fallback. | — (app needs no change) | `ddd18e1` | ERP re-import no longer churns line ObjectIDs → app's mergeDocumentLines stops silently duplicating locally-dirty rows under a re-imported product. |

### Remaining (open, by descending priority)

| Item | What's needed | Why it's still worth doing |
|---|---|---|
| **M8** | Extend `applyLoadedStageReset` to PACK and DELIVERY reverts. PACK revert clears `Document.Boxes`; DELIVERY revert resets each box `Status` back to PACKED and clears courier metadata. Audit shape mirrors LOADED. | Same class of bug as the original LOADED leak, just on different per-stage data. Latent until someone force-releases a PACKING / DELIVERING doc and then re-imports. |
| **M2** | Cross-device push of `DOCUMENT_UPDATE` apply + `UpsertDocument` apply via a tenant-scoped broadcast to devices that have the doc in cache (subscription map). | Reduces stale-cache latency on non-owning devices from "next poll" to "<1s after the change." Mostly an admin / queue-dashboard UX improvement; M5″ already handles the worst case where two TSD devices are simultaneously interested in the same doc. |
| **M4** | Persist `heldStageLocks` to DataStore on the app as belt-and-braces over M3. | M3 already closes the main hole; M4 covers the edge where USER_LOGIN_RESULT was lost in transit (rare) or where the offline-first path needs to short-circuit suppression before the WS reconnect completes. |
| **M9** | Surface FAILED `OutgoingOperationEntity` rows (stage ops that exhausted `MAX_RETRIES = 5`) in the app — Profile screen badge + Debug Journal cross-reference. | Today a silently-failed STAGE_COMPLETE / STAGE_UNLOCK / STAGE_PAUSE has no UI affordance. The worker has no way to discover and re-trigger. |
| **M10** | Server-side retry-on-conflict for `DOCUMENT_UPDATE` apply when Mongo CAS fails under concurrent admin/ERP/worker writes. | Defensive; today the failure mode is the line update silently being last-write-wins after a `version` bump. Not observed in production yet. |

### Open questions (verify before designing remaining items)

1. **Cross-device pressure**: when M2 ships, the broadcast cadence should
   be debounced to avoid hammering admin dashboards on bursty scan
   sessions. Pick a window (50ms? 200ms?) and confirm with the customer's
   warehouse rate.
2. **M8 box-status semantics**: when reverting DELIVERING → DELIVERY, do
   we treat already-PICKED_UP boxes as "courier had them" (preserve
   pickup metadata + status) or as "wipe the whole stage" (reset to
   PACKED, clear all courier fields)? [CLAUDE.md](../CLAUDE.md) doesn't pin this; the
   user needs to decide before code lands.
3. **M9 UX**: surface FAILED ops as a list (Profile badge → list view) or
   as a single counter ("3 sync errors — tap to retry")? The list is
   richer but requires more code; the counter is a low-effort first cut.
4. **Field-test signals from current work**: anything we should look for
   in the Debug Journal during the upcoming test phase? The new event
   types to watch are `LOCK_LOST_RECOVERED` (INFO — verify silent recovery
   actually fires) and `LOCK_LOST_EDIT_DROPPED` (ERROR — every occurrence
   warrants an admin-side correlation check).

---

## Context

The earlier LOADED-phantom-actuals catalog was closed by client +
server fixes that landed on `master`. The bug was a single symptom of a
broader policy: **who owns document data when?**

The user articulated that policy. This analysis (a) codifies it,
(b) maps which mechanism enforces each rule today, and (c) enumerates the
bottlenecks where the policy still leaks or could break under load.

This is reconnaissance — the output is a punch list of risks, with code
references so each item can be chased directly. The Status section above
tracks which items have been implemented.

---

## 1. The ownership policy (formalized)

A document moves through three ownership regimes that mirror the state
machine. Each regime has a different "source of truth" and a different
set of allowed mutators.

| Regime | States | Source of truth | ERP writes | Device writes | Server's role |
|---|---|---|---|---|---|
| **ERP-owned (idle)** | LOADED, PACK, DELIVERY | ERP | allowed | none expected | merge ERP pushes; broadcast on poll |
| **Worker-owned (in-process)** | COLLECTING, PACKING, DELIVERING | Device holding lock | **blocked** (`erp_sync_blocked=true`) | authoritative; debounced flush via DOCUMENT_UPDATE | gatekeeper; serializes; refuses ERP writes |
| **Worker-frozen (post-stage)** | COLLECTED, PACKED, DELIVERED, SENT, ERROR | Device's last state (snapshot) | allowed for *header* fields; line actuals / boxes preserved | none | merge with `preserveLineActuals` + `preserveBoxes` |

Three corollaries:

1. **On lock acquisition, the device assumes responsibility for content.**
   Subsequent ERP requests must be ignored (server-side) and inbound sync
   echoes for the doc must be suppressed (app-side).
2. **At stage completion the server respects the device's data as the new
   baseline.** ERP can keep editing the header, but per-line `actual_quantity`,
   `batch_number`, `is_completed`, and `Document.Boxes` are immutable from
   the ERP side from that moment forward.
3. **The device is responsible for not losing the worker's actions.** Local
   persistence (`is_dirty` flag, debounced flush, foreground resync,
   reconnect-resync) carries unsent edits across crashes, WS drops, and
   offline shifts.

---

## 2. Enforcement mechanisms today

### 2.1 Server side
- `erp_sync_blocked: bool` on `Document` — set by `AcquireLockForStage`,
  cleared by terminal / start-state transitions. `ERPDocumentService.UpsertDocument`
  returns `ErrDocumentLocked` when set.
- `preserveLineActuals` + `preserveBoxes` + `sumLineTotals` in
  `internal/service/erp_document.go` — the only ERP write path. **As of
  M6**, line matching is by `product_id` and the existing line `_id` is
  preserved.
- Atomic CAS via Mongo `UpdateOne` with compound filter on `state` +
  `locked_by` + `assigned_user_id` + `version`. No global lock.
- LOADED-state reset on every transition into LOADED (via
  `applyLoadedStageReset` in `ForceReleaseLock`, `UpdateState`,
  `ForceTransitionState`). PACK / DELIVERY analogues are M8 (pending).
- `PauseLockInState` clears `locked_by` but **keeps `erp_sync_blocked=true`** —
  intentional: paused doc is still worker-owned.
- **As of M3**: `USER_LOGIN_RESULT` returns the device's currently-held
  in-process locks so the app can rebuild `heldStageLocks` on connect.
- **As of M1**: cooperative force-release (`FORCE_RELEASE_REQUEST` push +
  10s wait for STAGE_UNLOCK), with hard fallback via the legacy path.

### 2.2 App side
- `WORKER_AUTHORITATIVE_STATES = {COLLECTING, PACKING, DELIVERING}` —
  `SyncOrchestrator.kt:180`.
- `heldStageLocks: Set<DocumentId>` (in-memory). Suppression in
  `applyDocumentSync` fires iff `existing.state` is worker-authoritative
  **AND** `dto.id in heldStageLocks` **AND** `dto.state == existing.state`.
  **As of M3**, the set is repopulated from `USER_LOGIN_RESULT.held_stage_locks`
  on every successful login.
- Per-line `is_dirty` — every local write sets it; `mergeDocumentLines`
  preserves dirty lines' `actualQuantity` / `isCompleted` / `notes`
  against any server payload.
- Per-doc `is_dirty` — re-armed when a merge preserves any line so the
  resync worker re-picks the doc.
- Debounced doc sync + pre-action flush (before STAGE_LOCK / COMPLETE /
  UNLOCK; **not** before STAGE_PAUSE).
- `OutgoingOperationEntity` queue — **stage-lifecycle ops only**. Line
  updates use `is_dirty` rows reconciled by `resyncDirtyDocuments`.
- LOADED-actual coercion at the mapper boundary + startup scrub for
  stale local rows.
- **As of M5″**: `SyncOrchestrator.handleServerError` routes `LOCK_LOST` /
  `WRONG_STATE` codes (with `document_id`) to a silent re-lock state machine.
- **As of M1**: `SyncOrchestrator.handleForceReleaseRequest` runs the
  "exit without saving" flow on server request — drops dirty edits,
  sends STAGE_UNLOCK, emits `DocSyncEvent.LockLost` for the UI.

### 2.3 Wire / joint
- `DOCUMENT_UPDATE.actual_quantity` is **absolute** (post-increment total),
  not a delta — retries are idempotent.
- WS in-connection ordering is guaranteed; cross-reconnect ordering is not
  — the absolute semantics paper over reorder.
- `version` field on Document — bumped on every server write; used for
  CAS, never exposed to ERP as a state token.
- `protocol_version` in AUTH handshake selects v1 ObjectID vs v2 external_id
  ID shape — orthogonal to ownership.
- **As of M5″**: typed wire error codes `LOCK_LOST` / `WRONG_STATE` carry
  `document_id`. App distinguishes line-write rejection from auth failure.

---

## 3. Bottlenecks (with current status)

Each item carries its current status in **bold**. Where a mitigation has
landed, the section reads as "what was wrong + what fixed it" for future
post-mortems.

### 3.1 The architectural bottleneck — poll-only fan-out (PARTIAL)

- **3.1a — Force-release into the void.** **RESOLVED (M1 + M5″).** Admin's
  cooperative force-release explicitly tells the device to release; the
  device drops dirty edits and shows a banner. If the device is
  unresponsive, hard fallback runs and the device's next DOCUMENT_UPDATE
  trips M5″'s silent re-lock → give-up branch.
- **3.1b — Cross-device contamination latency.** **OPEN (M2).** Other
  devices subscribed to the same tenant still see updates only on the
  next delta-sync poll.
- **3.1c — STAGE_LOCK-while-stale.** **OPEN (M2).** Mitigated indirectly
  by the LOADED-actual coercion, which protects the actuals; plan
  changes from a stale cache still surprise the worker.

### 3.2 In-memory `heldStageLocks` lost on process death (RESOLVED)

**Closed by M3.** `USER_LOGIN_RESULT` now carries the (user, device)'s
held locks and the orchestrator reasserts the set before the first
delta sync. **M4** (persistent local copy) is the optional belt-and-braces.

### 3.3 STAGE_PAUSE window (OPEN)

Pause releases the device's `heldStageLocks` entry but server keeps
`erp_sync_blocked=true`. Worker-touched lines survive via per-line dirty;
untouched lines can still be replaced by a stale server snapshot during
the pause. **No mitigation planned yet.** Low priority — only worker
edits matter, and dirty preservation already covers them.

### 3.4 DOCUMENT_UPDATE accepted on a state that no longer owns the worker (RESOLVED)

**Server-side guard was already in place** (`DocumentService.UpdateDocumentLines`
rejects with Forbidden / Conflict on `locked_by` mismatch / non-in-process
state). **M5″** closed the app-side gap: rejections route to silent
re-lock instead of the FORBIDDEN reconnect loop.

### 3.5 Line ObjectID instability across ERP re-imports (RESOLVED by M6)

`preserveLineActuals` now matches by `product_id` and the existing line `_id` is
preserved. App's `mergeDocumentLines` works correctly because
line ids stay stable across re-imports.

### 3.6 `preserveLineActuals` keyed only on `line_number` (RESOLVED by M6)

Solved as a side effect of M6: ERP can renumber freely; actuals follow
the product.

### 3.7 PACK / DELIVERY analogues of the LOADED-revert bug (OPEN — M8)

Analogous to the LOADED bug:
- `PACKING → PACK` revert: stale `Document.Boxes` linger.
- `DELIVERING → DELIVERY` revert: stale `DocumentBox.Status` transitions
  linger.

`applyLoadedStageReset` has a TODO comment marking this as the right
extension point.

### 3.8 No worker-facing signal on force-release / admin state change (RESOLVED for force-release)

**M1** delivers a deterministic signal for cooperative force-release.
**M5″** delivers a signal as a side effect of the worker's next
DOCUMENT_UPDATE after a hard release. Admin **state-change** actions
(via `PUT /tenant/documents/{id}/state`) still don't notify the device
proactively — would benefit from M2's broadcast.

### 3.9 OutgoingOperationEntity FAILED rows are invisible (OPEN — M9)

Queued stage ops that hit `MAX_RETRIES=5` are marked FAILED in the DB
but the UI doesn't surface this.

### 3.10 Resource bottlenecks on the device (OPEN — not yet sized)

Scan throughput, merge cost on large docs, foreground resync memory
spike, DebugJournal cap. Needs a profiling pass with realistic data.

### 3.11 Resource bottlenecks on the server (OPEN — M10)

Poll-only sync scales linearly with connected devices × poll frequency.
Mongo `version` CAS conflicts not retried on the server — `DOCUMENT_UPDATE`
apply silently loses one of two racing writes.

---

## 4. Triage — mitigation index

| ID | Status | Addresses | Sketch |
|---|---|---|---|
| **M1** | ✅ Done (`c1ddfd8` / `7f41063`) | 3.1a, 3.8 | Cooperative force-release with hard fallback. |
| **M2** | Open | 3.1b, 3.1c, 3.3 | Server pushes targeted delta on `DOCUMENT_UPDATE` / `UpsertDocument` apply to interested devices. |
| **M3** | ✅ Done (`a448451` / `4fdca69`) | 3.2 | `USER_LOGIN_RESULT` carries held locks. |
| **M4** | Open | 3.2 (belt-and-braces) | Persist `heldStageLocks` to DataStore. |
| **M5″** | ✅ Done (`cf0fe1e` / `989c094`) | 3.4 | Typed `LOCK_LOST` / `WRONG_STATE` codes + silent re-lock state machine + give-up UX. |
| **M6** | ✅ Done (`ddd18e1`, app needs no change) | 3.5, 3.6 | `preserveLineActuals` by `product_id`, preserves `_id`. |
| **M7** | ✅ Done as part of M6 | 3.6 | (folded into M6) |
| **M8** | Open | 3.7 | Extend `applyLoadedStageReset` to PACK + DELIVERY reverts. |
| **M9** | Open | 3.9 | Surface FAILED operations in app UI. |
| **M10** | Open | 3.11 | Server retry-on-conflict for DOCUMENT_UPDATE apply. |

Remaining priority order if we continue: **M8 → M2 → M4 → M9 → M10.** M8
is the only one that closes a known correctness bug (the LOADED-bug
shape, but for PACK / DELIVERY); the rest are UX / scale improvements.

---

## 5. What to watch in field tests

Before resuming work, look for these signals from a real warehouse shift
with the current `master` builds:

1. **Did the LOADED-phantom-actuals symptom recur?** Filter Debug Journal
   for `LOADED_ACTUAL_REJECTED` rows. Zero rows = the server fix is
   holding; non-zero rows = a code path we haven't covered is still
   leaking. The `source` payload field distinguishes startup scrub from
   inbound merge.
2. **How often does M5″ fire?** `LOCK_LOST_RECOVERED` (INFO) on success,
   `LOCK_LOST_EDIT_DROPPED` (ERROR) on give-up. Both have severity tags
   for easy filter. Frequent INFO = server-side lock churn we should
   chase (admin force-releases? stale state in transit?). Any ERROR =
   data loss the worker saw a banner for — correlate against the server
   audit log for `document.force_released` / `document.force_released_cooperative`.
3. **Cooperative force-release UX**: when admin uses the new flow, does
   the device's banner show the right magnitude? Does the admin's
   408-prompt timing feel reasonable?
4. **Held-lock reassertion (M3)**: when a TSD restarts mid-shift, does
   the next inbound SYNC_DATA get suppressed (look for `DOC_SYNC_SUPPRESSED`
   in Debug Journal right after login)? If suppression fires
   immediately, M3 is doing its job.

Server-side: tail the audit log for `document.force_released_cooperative`
vs `document.force_released` to see how often the cooperative path
succeeds vs falls through to hard.

---

## 6. Critical files (for the remaining items)

App:
- `app/src/main/java/ua/com/programmer/pick/data/sync/SyncOrchestrator.kt`
  — central state machine; touchpoint for M2 (broadcast intake), M4 (persistence).
- `app/src/main/java/ua/com/programmer/pick/data/repository/OutgoingOperationRepositoryImpl.kt`
  — M9 (FAILED ops surface).
- `app/src/main/java/ua/com/programmer/pick/presentation/profile/` — M9
  UI surface.

Server:
- `internal/repository/mongodb/document.go` — `applyLoadedStageReset` is
  the extension point for **M8** (add PACK / DELIVERY clauses, mirroring the
  LOADED clause).
- `internal/handler/ws/hub.go` — broadcast plumbing for **M2** (extend
  existing `Broadcast` / `SendToDevice` with a per-doc subscription map
  or a tenant-wide fan-out with client-side filtering).
- `internal/service/document.go` — `UpdateDocumentLines` is the CAS
  point for **M10**.

Contract docs:
- [backend-spec.md](backend-spec.md), [websocket-protocol.md](websocket-protocol.md) — would need updates for
  M2's broadcast section.

## 7. What this analysis does not cover

- Performance benchmarks (3.10, 3.11) — needs a profiling pass with
  realistic doc sizes.
- The `Document.Boxes` ownership semantics during pack-deliver hand-off
  beyond what M8 will address.
- Multi-tenant isolation (only tangentially relevant).
- Authentication / authorization beyond the Server-Driven Architecture
  section of [CLAUDE.md](../CLAUDE.md).
