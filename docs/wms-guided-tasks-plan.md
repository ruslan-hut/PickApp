# WMS Guided Tasks — App Modification Plan

## Status

| | |
|---|---|
| Delivery | **Phases 0–3 shipped** on `master` (2026-09-09): `8b4211a` protocol plumbing, `d4f1c40` task screen + home entry, `6accf8c` document-bound flows, plus this phase's hardening and docs. The field-test matrix in §7 is the remaining sign-off. |
| App baseline | `master` @ `2d20232` (in sync with `origin/master`, checked 2026-09-07). No guided-task code existed at planning time. |
| Server baseline | Pick backend, WMS addressing module phases 0–5 delivered (Sept 2026). Contract: server repo `docs/device-api.md` § "Guided tasks", concept `docs/wms-addressing.md`, delivery record `docs/archive/wms-addressing-plan.md`. |
| Goal | The terminal renders **server-driven guided tasks** (cell recount, placement, cell move, replenishment) and runs the **document-bound guided flows** (addressed Collect, receiving) — while every tenant without the module keeps today's behaviour byte for byte. |
| Out of scope | Any warehouse logic on the device. The server owns the step machine, the texts, the route, the ledger. This plan adds a renderer and the wiring around it. |

Phases are ordered so that each one ships alone. Phase 0 is invisible to
users; Phase 1 gives the system task types; Phase 2 gives the document flows;
Phase 3 hardens and documents.

---

## 1. What the server already sends

Everything below is live on the backend; nothing here needs a server change.
Field names are the wire names.

### 1.1 Login (`POST /device/login`)

| Field | New? | Meaning for the app |
|---|---|---|
| `available_document_types[].mode` | new | `"guided"` marks a task type (`CELL_RECOUNT`, `PLACEMENT`, `CELL_MOVE`, `REPLENISH`). Absent = classic document type. The server **omits** guided types when the worker's warehouse has WMS off, so a plain client never sees them. |
| `open_tasks[]` | new | `{id, type, document_id?, step_title, started_at}` — the worker's unfinished tasks (spec F-4: offer *continue / cancel*). Absent when the module is off. |
| `held_stage_locks[]` | existing | **Includes** the Collect lock the server took for a guided document (the task engine locks through the same document machinery). See D3. |

### 1.2 Documents (`POST /device/sync`, `GET /device/documents`)

| Field | New? | Meaning |
|---|---|---|
| `collect_mode` | new | `"guided"` on LOADED / COLLECTING documents whose warehouse works them as a task (shipments: module **and** addressed picking on; receipts: module only). Absent = classic screen. **Recomputed on every list load**, so the tenant's emergency switch flips the screen on the next refresh. |
| `lines[].line_key` | new | The ERP's stable line id (optional). `line_updates` address lines by it when present. |

### 1.3 Task endpoints

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/device/tasks` | `{"type":"CELL_RECOUNT"}` or `{"document_id":"<external_id>"}` (type optional with a document) | task envelope — the first step, or the worker's already-open task of that type / document |
| GET | `/device/tasks/open` | — | **array** of task envelopes |
| GET | `/device/tasks/{id}` | — | task envelope (resume) |
| POST | `/device/tasks/{id}/actions` | `{operation_id, step_id, action, value?, quantity?}` | task envelope — the next step |
| POST | `/device/tasks/{id}/cancel` | `{"operation_id"}` optional | task envelope with `state: CANCELLED` |

Task envelope:

```json
{
  "task": { "id": "…", "type": "CELL_RECOUNT", "warehouse_id": "WH", "document_id": "",
            "state": "OPEN", "step_id": "rc_count", "metric_category": "RECOUNT",
            "started_at": 1788700000000, "updated_at": 1788700100000 },
  "step": { "id": "rc_count", "title": "…", "expect": "product",
            "rows": [ { "text": "…", "planned": 10, "actual": 5, "highlight": true } ],
            "actions": [ { "code": "confirm", "label": "…", "style": "primary" } ],
            "hint": "…", "lock_info": "…" },
  "message": { "level": "warning", "text": "…" },
  "replayed": false,
  "line_updates": [ { "line_key": "K1", "line_number": 1, "actual_quantity": 8, "is_completed": true } ]
}
```

* `step.expect` ∈ `cell | product | batch | qty | document | none`.
* `step.actions[].code` ∈ `scan | confirm | empty | skip | manual_cell | no_stock | cancel | done` (only the ones offered are listed; `scan` is never listed — it is what the scanner sends).
* `task.state` ∈ `OPEN | DONE | CANCELLED`. After `DONE`/`CANCELLED` the step is a final screen with a single `done` action.
* `message` is one-shot (about the last action), not part of the step.
* `line_updates` only on document-bound tasks: apply to the cached document, no sync round-trip.

Step-id families exist only for humans reading logs — the app never branches
on them: `rc_*` recount, `pl_*` placement, `mv_*` cell move, `rp_*`
replenishment, `co_*` guided Collect, `rv_*` receiving.

### 1.4 Idempotency, stale screens, locks

* `operation_id` is a fresh UUID per action, **reused verbatim on retry**. A repeat answers the original response with `replayed: true` (F-2).
* A `step_id` the server has moved past is ignored; the current step comes back with a warning `message`. The app just re-renders.
* Cell / line locks are extended by the device's ordinary `POST /device/sync` **and by every task action**; they expire after the warehouse's `lock_ttl_sec` when the device goes silent (F-5). Cancel and the final confirm release them.

### 1.5 Error codes the app will meet

| Code | HTTP | When | App behaviour |
|---|---|---|---|
| `FEATURE_DISABLED` | 403 | module off for the tenant | toast, leave the task screen |
| `WMS_WAREHOUSE_DISABLED` | 409 | module off for the warehouse | same |
| `GUIDED_OFF` | 409 | `{document_id}` start on a document the warehouse now works classically | toast "open it from the list" and go back; the detail screen will show the classic bar on next load |
| `NO_WAREHOUSE` | 409 | worker has no warehouse | toast |
| `LOCKED` | 409 | another worker holds the cell / line (message names the holder) | show the message; stay on the step |
| `WRONG_STATE` / `DOCUMENT_LOCKED` | 409 | document not in Collect stage / held by someone else | toast, go back |
| `DOCUMENT_WAREHOUSE` | 409 | document belongs to another warehouse | toast |
| `NOT_FOUND` | 404 | task id unknown (e.g. admin-cancelled and purged) | refresh open tasks, go home |
| `FORBIDDEN` | 403 | task type not assigned to the worker | toast |
| `BAD_REQUEST` | 400 | malformed action | log at ERROR, re-fetch the step |

---

## 2. Design decisions

**D1 — The screen is a renderer.** One `TaskScreen` renders any step from
`title / hint / lock_info / rows / actions / expect`. Unknown `expect` values
are treated as `none` (scans still forwarded); unknown action codes render as
plain buttons and are sent as-is. No per-flow code, no step-id switch.

**D2 — Guided actions are online-only.** They are never queued in
`OutgoingOperationEntity`. Offline: the step stays visible, the offline banner
shows, inputs are disabled. Network failure mid-request: retry with the same
`operation_id` (3 attempts, 1 s / 2 s / 4 s), then a *Retry* button that keeps
the same id until a response arrives.

**D3 — Guided documents never enter `heldStageLocks`.** The app has no dirty
edits to protect for them (the server mirrors every confirmed line into the
document itself), and the classic cooperative-release path (`release_requested`
→ drop edits + `STAGE_UNLOCK`) must not fire on a task-locked document.
Therefore:
* `POST /device/tasks {document_id}` does **not** add the document to `heldStageLocks`;
* `reassertHeldStageLocksFromLogin` skips every id present in `open_tasks[].document_id` of the same login response;
* the classic "Take into work" bar is not shown for `collect_mode == "guided"` documents (see D7), so no classic lock is taken by the worker either.

**D4 — The task screen is the heartbeat.** The orchestrator's active poll runs
only while `heldStageLocks` is non-empty, which by D3 is never the case for a
task. While `TaskScreen` is resumed, `TaskViewModel` calls
`syncOrchestrator.requestDeltaSyncIfStale(ACTIVE_POLL_INTERVAL_MS)` on the same
8 s cadence. That extends the device's named locks and also keeps the cached
document fresh. When the app is backgrounded the lock is allowed to expire — the
spec's intended behaviour.

**D5 — `line_updates` are written straight to Room.** Match by `line_key` when
both sides have one, else by `line_number`. Set `actual_quantity` and
`is_completed`, leave `is_dirty = false`, never emit `DOCUMENT_UPDATE`. The
classic detail screen (read-only for a guided doc) and the list progress
therefore reflect the task in real time.

**D6 — Nothing about a task is persisted on the device.** Not the task id, not
the step, not a draft. Resume comes from `open_tasks` on login and
`GET /device/tasks/open` when Home resumes. This is the same rule as
"document lock state is never client-persisted".

**D7 — `collect_mode` picks the primary action of the detail screen, per
load.** `"guided"` → bottom bar *Start guided picking* / *Resume* / *Start
receiving* (by type) which navigates to `TaskScreen(documentId)`. Absent →
today's *Take into work*. An in-flight guided document whose flag disappears
(emergency switch) is finished classically without any special handling —
the lines are already in the document.

**D8 — Texts.** Every string the worker reads inside a step comes from the
server in the tenant's locale and is shown verbatim. App strings are limited
to chrome (buttons around the step, dialogs, error toasts) and are added to
`values`, `values-uk`, `values-ru`.

**D9 — Transport seam.** New `SyncMessage` request/result types, dispatched by
`RestTransport` to `DeviceRestClient`; `DemoTransport` answers a typed
"not available in demo" result. A `GuidedTaskRepository` (domain interface +
impl) is the only caller: it generates `operation_id`s, retries, exposes
`openTasks` and the active task as `StateFlow`s.

**D10 — No role branching.** The server decides which types, documents and
tasks the worker sees. The app never checks `role` to show or hide task UI.

---

## 3. Phases

### Phase 0 — Protocol plumbing (no visible change)

Goal: parse everything in §1, store `collect_mode` / `line_key`, keep every
legacy path identical. Exit: `./gradlew :app:assembleDebug test` green, a
login against a WMS-off tenant produces the same DataStore / Room contents as
today.

| Area | File | Change |
|---|---|---|
| Wire DTOs | `data/remote/dto/DeviceDto.kt` | `AvailableDocumentType.mode: String?`; `LoginResponse.openTasks: List<OpenTask>?`; new `OpenTask`, `TaskStartRequest`, `TaskActionRequest`, `TaskCancelRequest`, `TaskResponse`, `Task`, `TaskStep`, `TaskRow`, `TaskAction`, `TaskMessage`, `TaskLineUpdate` (Gson, `@SerializedName`, nullable with defaults so an older server still parses). |
| | `data/remote/dto/DocumentDto.kt` | `collectMode: String? = null` (`collect_mode`). |
| | `data/remote/dto/DocumentLineDto.kt` | `lineKey: String? = null` (`line_key`). |
| Retrofit | `data/remote/api/DeviceApi.kt` | `taskStart`, `taskOpen` (returns `ApiEnvelope<List<TaskResponse>>`), `taskGet`, `taskAction`, `taskCancel`. |
| Client | `data/remote/api/DeviceRestClient.kt` | Thin `envelopeCall` wrappers for the five calls. `DeviceApiException.code` already carries the envelope code. |
| Seam | `data/remote/transport/SyncMessage.kt` | `MessageType.TASK_START / TASK_GET / TASK_OPEN / TASK_ACTION / TASK_CANCEL / TASK_RESULT / TASK_OPEN_RESULT`; request messages carry the DTO fields; `TaskResult(success, response: DeviceDto.TaskResponse?, errorCode, errorMessage)`; `TaskOpenResult(success, tasks, errorCode, errorMessage)`. `AvailableDocumentTypeDto.mode: String? = null`. |
| | `data/remote/transport/TransportState.kt` | `UserAuthState.Authenticated.openTasks: List<DeviceDto.OpenTask>?`, same on `UserLoginResult`. |
| | `data/remote/transport/RestTransport.kt` | `loginUser` copies `mode` and `open_tasks`; `execute()` gains the five task branches mapping `Result` → `TaskResult` / `TaskOpenResult` (failure → `errorCode = (e as? DeviceApiException)?.code`). |
| | `data/remote/transport/MessageParser.kt` | `parseUserLoginResult` reads `mode` and `open_tasks` (kept for parity; the REST path builds the result directly). |
| | `data/remote/transport/demo/DemoTransport.kt` | Task branches return `TaskResult(success=false, errorCode="DEMO_UNSUPPORTED")`; login emits no guided types. |
| Domain | `domain/model/AvailableDocumentType.kt` | `mode: String? = null`, `val isGuided get() = mode == "guided"`. |
| | `domain/model/Document.kt` | `collectMode: String? = null`, `val isGuidedCollect get() = collectMode == "guided"`. |
| | `domain/model/DocumentLine.kt` | `lineKey: String? = null`. |
| | new `domain/model/GuidedTask.kt` | `GuidedTask`, `TaskStep`, `TaskRow`, `TaskActionButton`, `TaskMessage`, `TaskLineUpdate`, `OpenTask`, enums `TaskExpect { CELL, PRODUCT, BATCH, QTY, DOCUMENT, NONE }` (+ `fromWire` defaulting to `NONE`) and `TaskState { OPEN, DONE, CANCELLED }`. |
| Room | `data/local/database/entity/DocumentEntity.kt` | `collect_mode TEXT` nullable. |
| | `data/local/database/entity/DocumentLineEntity.kt` | `line_key TEXT` nullable, index `(document_id, line_key)`. |
| | `data/local/database/AppDatabase.kt` | version 19, `MIGRATION_18_19`: two `ALTER TABLE … ADD COLUMN`, one `CREATE INDEX`. Export schema. |
| | `DocumentLineDao` | `updateActualByLineKey(documentId, lineKey, qty, completed)`, `updateActualByLineNumber(documentId, lineNumber, qty, completed)` — **new queries that leave `is_dirty` untouched**. The existing `updateActualQuantity` / `updateLineCompleted` set `is_dirty = 1` and would make the orchestrator re-send server-authored values as worker edits; they must not be used for `line_updates`. |
| Mappers | `data/mapper/DocumentMapper.kt` | carry `collectMode` and `lineKey` DTO → entity → domain (both directions of `Document`/`DocumentEntity`). |
| Config | `data/repository/DocumentTypeConfigProvider.kt`, `presentation/home/HomeViewModel.kt` | map `mode` into `AvailableDocumentType` (both parse the same JSON blob; consider having the ViewModel consume the provider instead of re-parsing). |
| Orchestrator | `data/sync/SyncOrchestrator.kt` | `reassertHeldStageLocksFromLogin(ids, excluding = openTaskDocumentIds)` (D3). The suppression block in `applyDocumentSync` is unchanged — with D3 it simply never fires for a guided doc. |

Tests (JUnit 4 + MockK, as in `DocumentDetailViewModelResolveScannedProductTest`):
* Gson round-trip of a full task envelope, an envelope with `line_updates`, an envelope without `step`, and the `open_tasks` login sample from the server doc (`TaskDtoParsingTest`).
* `AvailableDocumentTypeDto` with and without `mode` (older server).
* `DocumentMapper` carries `collect_mode` / `line_key`; a DTO without them yields nulls.
* `reassertHeldStageLocksFromLogin` excludes open-task documents.
* Room migration 18→19 via `MigrationTestHelper` (androidTest; schema JSON exported).

### Phase 1 — Generic task screen, home entry, open tasks

Goal: a worker with a guided type on the home screen can run `CELL_RECOUNT`,
`PLACEMENT`, `CELL_MOVE`, `REPLENISH` end to end, resume after a restart and
cancel. Exit: recount smoke on the local backend (§7) passes; a classic tenant
sees no new UI.

**Data**

| File | Change |
|---|---|
| new `domain/repository/GuidedTaskRepository.kt` | `suspend fun start(type: String?, documentId: String?): Result<GuidedTask>`; `get(id)`; `act(id, stepId, action, value?, quantity?, operationId)`; `cancel(id)`; `refreshOpen()`; `val openTasks: StateFlow<List<OpenTask>>`; `val active: StateFlow<GuidedTask?>`. Result failures carry the server `code` + `message`. |
| new `data/repository/GuidedTaskRepositoryImpl.kt` | Uses `SyncTransport.sendAndAwait` with the Phase 0 messages. Owns retry (D2): same `operation_id`, 3 attempts on transport failure (null result / IO), **no retry** on a server error envelope. Maps DTO → domain. Journals every call (§6). Updates `openTasks` from login (`UserAuthState.Authenticated.openTasks`) and from `refreshOpen()`; drops a task from it on `DONE`/`CANCELLED`. |
| `core/di/RepositoryModule.kt` | bind it (`@Singleton`). |

**Presentation** — new package `presentation/task/`

| File | Content |
|---|---|
| `TaskUiState.kt` | `task`, `step`, `message`, `isLoading`, `isSending`, `retryPending` (operation id kept), `qtyInput: String`, `manualInput: String`, `isOnline`, `finished: Boolean`, `errorCode`. Derived: `canAct = isOnline && !isSending && task?.state == OPEN`, `primaryAction = actions.firstOrNull { style == "primary" }`. |
| `TaskUiEvent.kt` | `ShowToast(resId, serverText?)`, `NavigateHome`, `NavigateToDocuments(documentId)`, `Vibrate(level)`. |
| `TaskViewModel.kt` | Args from `SavedStateHandle`: `taskId?`, `type?`, `documentId?`. `init`: `taskId` → `get`; `documentId` → `start(null, documentId)`; `type` → `start(type, null)`. Subscribes to `BarcodeService.scannedBarcodes` and forwards `rawValue` as `action=scan` for **every** expect while `canAct` (scans while sending are dropped and journaled). `confirmQty()` sends `confirm` with the parsed integer; `onAction(code)`: `cancel` and `no_stock` go through `ConfirmDialog` first, `manual_cell` opens the text dialog then sends with `value`, everything else is sent as-is. `done` is fire-and-forget, then navigate (document-bound → `NavigateToDocuments`, else `NavigateHome`). Heartbeat loop per D4 while the screen is resumed (`Lifecycle.repeatOnLifecycle`). Applies `line_updates` (Phase 2 hook, no-op now). Handles `replayed` silently; shows `message` as banner. Error mapping per §1.5. |
| `TaskScreen.kt` | `Scaffold` + `PickAppBar` (title = `step.title`, back = leave screen, task stays open). Body: `TaskMessageBanner` (level colours, info auto-dismiss 4 s), `hint`, `lock_info` (muted), `TaskRows` (text, `planned`/`actual` chips, `highlight` = accent border), `TaskInputPanel` by expect: `QTY` → numeric field (IME done = confirm), big keys; `CELL` / `DOCUMENT` → "enter manually" affordance (cell → `manual_cell` if offered, else nothing; document → typed value sent as `scan`); `PRODUCT` / `BATCH` / `NONE` → scan prompt only. Bottom: `TaskActionsBar` — one `Button` per action in server order, `primary` filled, `danger` error-tonal, others outlined; large targets (≥ 56 dp) for gloved use. Offline: `OfflineBanner` + disabled inputs. `finished` → the final step rendered with its single button. Optional camera scan FAB hosting `CameraScannerManager.startCamera(lifecycleOwner, previewView)` — note that no screen hosts the camera **barcode** scanner today (`LinePhotoCaptureOverlay` is photo capture), so this would be its first host; see open question 3. |
| `TaskComponents.kt` | the composables above. |

**Home**

| File | Change |
|---|---|
| `presentation/home/HomeUiState.kt` | `openTasks: List<OpenTask>`. |
| `presentation/home/HomeViewModel.kt` | collect `guidedTaskRepository.openTasks`; `refreshOpenTasks()` on resume (guarded by `isOnline`); `cancelTask(id)`. |
| `presentation/home/HomeScreen.kt` | `DocumentTypeButton` for `isGuided` types navigates to `TaskScreen(type)` and does **not** set `selectedDocumentType`; a "Unfinished tasks" section above the type list: one row per open task (`step_title`, type description from `availableDocumentTypes` by code, else code, started-at relative time) with *Continue* → `TaskScreen(taskId)` and *Cancel* → `DestructiveConfirmDialog` → cancel. Hidden when empty. |
| `presentation/navigation/Screen.kt`, `NavGraph.kt` | `Screen.Task` route `task?taskId={taskId}&type={type}&documentId={documentId}` (all optional string args); `TaskScreen` composable; `NavigateHome` pops to Home, `NavigateToDocuments` pops to Documents. |

**Strings** (en / uk / ru): `task_unfinished_title`, `task_continue`,
`task_cancel_title`, `task_cancel_message`, `task_enter_quantity`,
`task_enter_cell`, `task_enter_document`, `task_manual_entry`,
`task_offline_hint`, `task_retrying`, `task_retry`, `task_scan_prompt`,
`task_no_stock_confirm`, `task_started_ago_fmt`, and the error toasts
`task_error_feature_disabled`, `task_error_guided_off`,
`task_error_no_warehouse`, `task_error_not_found`, `task_error_demo`,
`task_error_generic`.

Tests:
* `TaskViewModelTest`: start by type / by task id; scan → `act(scan, rawValue)` with the shown `step_id`; qty confirm → `quantity`; `cancel` needs confirm; `DONE` → navigate; `message` surfaced; `replayed` not surfaced; stale-step response simply replaces state; scans dropped while `isSending`; offline → no call.
* `GuidedTaskRepositoryImplTest`: same `operation_id` across 3 transport failures; no retry on an error envelope; `openTasks` drops a finished task.

### Phase 2 — Document-bound flows (guided Collect, receiving)

Goal: a `collect_mode: "guided"` document opens the task from its detail
screen, the cached document follows the task through `line_updates`, and the
classic screen stays the fallback. Exit: the smoke's guided Collect and
receiving sequences pass from the device; a classic tenant's detail screen is
unchanged.

| File | Change |
|---|---|
| `presentation/document/DocumentDetailUiState.kt` | `isGuidedCollect = document?.isGuidedCollect == true && state in {LOADED, COLLECTING}`; `canTakeIntoWork` becomes `document != null && !hasStageLock && !isGuidedCollect`; new `canStartGuided = isGuidedCollect`; `guidedButtonLabel` by type/state (*Start guided picking* / *Resume guided picking* / *Start receiving* / *Continue receiving*). |
| `presentation/document/DocumentDetailScreen.kt` | bottom bar: `GuidedStartBar` when `canStartGuided` → `onStartGuided(document.externalId ?: id)`; lines list stays read-only (no lock claim). A small "Guided" chip in the header next to `ClientLanguageChip`. |
| `presentation/document/DocumentDetailViewModel.kt` | no lock logic for guided docs; `load()` keeps calling `clearStageLockClaim` (harmless). Emits `NavigateToTask(documentId)`. |
| `presentation/navigation/NavGraph.kt` | `DocumentDetail` → `Screen.Task(documentId)`; after `NavigateToDocuments` the detail screen is popped too. |
| `presentation/task/TaskViewModel.kt` | on every envelope with `line_updates`: `documentRepository.applyServerLineUpdates(documentId, updates)`; on `DONE` of a document task: `syncOrchestrator.requestDocumentListRefresh(selectedType)` so the list shows COLLECTED/PACK. |
| `domain/repository/DocumentRepository.kt`, `DocumentRepositoryImpl.kt` | `applyServerLineUpdates(documentRoomId, List<TaskLineUpdate>)` — resolve the document by external id (`toRoomDocumentId` logic lives in the orchestrator; expose a small `DocumentIdResolver` or pass both ids), update by `line_key` then `line_number`, recompute `total_actual`. Journal `TASK_LINE_UPDATES_APPLIED`. |
| `presentation/documents/DocumentListItem.kt` | "Guided" badge for `isGuidedCollect` (string ×3). |
| `presentation/documents/DocumentsScreen.kt`, `DocumentsViewModel.kt` | overflow action *Join receiving* visible when `selectedDocumentType == INCOMING_RECEIPT` **and** any loaded type `isGuided` (the only client-side hint that the warehouse has WMS on); it navigates to `TaskScreen(type = INCOMING_RECEIPT)` → the server's `rv_pick_doc` picker (`expect: document`). This is how a second worker joins a document the classic list hides because another worker holds its lock. |
| `data/sync/SyncOrchestrator.kt` | `purgeDocumentsOutsideVisibleSet` keeps documents referenced by `guidedTaskRepository.active` (a document in a receiving task may be locked by another worker and thus absent from `visible_ids`). Inject via a small interface to avoid a cycle. |

Behaviour notes:
* `expect: document` manual entry sends the typed number as `scan`; the server matches number or external id.
* `co_review` (`expect: none`) renders from `rows` alone — short lines are `highlight`ed by the server; `confirm` completes, `skip` returns to the open lines.
* The receiving flow's per-line lock messages ("in work — <name>") arrive as row text; nothing to render specially.
* If `POST /device/tasks {document_id}` answers `GUIDED_OFF`, the ViewModel toasts and navigates back; the next detail load shows the classic bar.

Tests:
* `DocumentDetailUiStateTest`: guided doc → no classic bar, guided bar; flag absent → classic bar; COLLECTING guided → *Resume*.
* `DocumentRepositoryImpl.applyServerLineUpdates`: by `line_key`, fallback by `line_number`, `total_actual` recomputed, `is_dirty` untouched.
* Orchestrator: purge keeps the active task's document.

### Phase 3 — Hardening, docs, release

| Item | Detail |
|---|---|
| Resume paths | Home `onResume` → `refreshOpen()`; `TaskScreen` reopened from *Continue* uses `GET /device/tasks/{id}`; a `NOT_FOUND` there clears the row. Process death mid-request: the next action with a new `operation_id` is safe because the previous one either applied (server moved on → stale-step warning) or did not. |
| Lock expiry UX | After the app was backgrounded past `lock_ttl_sec`, the first action may answer `LOCKED` (someone took the cell) — shown as the server message; the worker cancels or waits. Verify with a 60 s TTL in the tenant settings. |
| Scanner | Hardware scanner via `BarcodeService` (already global); camera FAB on the task screen if open question 3 says the pilot needs it; the task screen uses `rawValue` only and ignores `isKnownProduct` / product enrichment — the server resolves every code. |
| Feedback | Haptic on `message.level == error` and on `LOCKED`; none on info. |
| Demo | Optional: a scripted `CELL_RECOUNT` in `DemoServer` (three steps, fixed texts) so the screen can be shown without a backend. Not required for release. |
| Debug journal | §6 events; the Debug Journal screen filter gains the task id as `documentId` substitute (use `document_id` for document tasks, `task:<id>` otherwise). |
| Docs | `CLAUDE.md`: remove the stale WebSocket paragraph (the app is REST-only since the cutover), add "Guided tasks" (D1–D10 in short) and the new package. `docs/sync-protocol.md`: a "Guided tasks" section mapping the five REST calls to the new `SyncMessage`s, with the envelope. `docs/backend-spec.md`: `collect_mode`, `line_key`, `mode`, `open_tasks`. `README.md`: index entry for this plan. The Ukrainian 1C docs need no change (the ERP contract for WMS lives in the server repo's `docs/erp-api.md`). |
| Release | `version.properties` bump; store listing unchanged. Field-test checklist in §7 signed off on a Zebra device and on the camera-only build. |

---

## 4. Task screen — interaction spec

```
┌──────────────────────────────────────┐
│ ‹  Cell A-01-02 — count              │  step.title (PickAppBar)
├──────────────────────────────────────┤
│ ⚠ Ivan is counting this cell         │  message (one-shot banner)
│ Scan each product and enter …        │  hint
│ locked by you until 14:32            │  lock_info (muted)
│ ┌──────────────────────────────────┐ │
│ │ Widget · batch L-2026-09     ▌   │ │  rows[]: text, planned/actual chips,
│ │ Gadget · no batch        5/10    │ │  highlight = accent left border
│ └──────────────────────────────────┘ │
│ ┌─ expect: qty ────────────────────┐ │
│ │  [ 45           ]   Save count ▶ │ │  input panel by expect
│ └──────────────────────────────────┘ │
│ [ Shelf is empty ] [ Save count ]    │  actions in server order
│ [ Cancel ]                           │  primary = filled, danger = tonal error
└──────────────────────────────────────┘
```

* **Back** leaves the screen; the task stays open on the server and appears under *Unfinished tasks*. Only the `cancel` action closes it.
* **Scan** while `canAct`: send immediately, show a progress indicator on the actions bar, drop further scans until the response lands.
* **`expect: qty`**: the primary action submits `quantity`; the field accepts digits only; empty → primary disabled; `skip` sends without quantity.
* **`expect: cell`**: *Enter address* is shown only when the server offers `manual_cell`; the dialog result is sent as `manual_cell` with `value`.
* **`expect: document`**: *Enter number* dialog → `scan` with the typed value.
* **Final step** (`state != OPEN`): rows + hint + the single `done` button; tapping sends `done` (no await) and navigates.
* **Message levels**: `info` → auto-dismiss 4 s; `warning` / `error` → stay until the next response.

---

## 5. Navigation

| From | Trigger | To |
|---|---|---|
| Home | guided type button | `Task(type)` |
| Home | *Continue* on an open task | `Task(taskId)` |
| Home | *Cancel* on an open task | confirm → `POST cancel`, row removed |
| Document detail | guided bar | `Task(documentId)` |
| Documents list | *Join receiving* | `Task(type = INCOMING_RECEIPT)` |
| Task | `done` on a document task | pop to Documents (+ list refresh) |
| Task | `done` on a system task | pop to Home |
| Task | `FEATURE_DISABLED` / `GUIDED_OFF` / `NOT_FOUND` | toast, pop |

---

## 6. Debug journal events

Add to `DebugEventType`: `TASK_START`, `TASK_ACTION_SENT`, `TASK_ACTION_RESULT`
(payload: `step_id` before/after, `state`, `replayed`, `line_updates` count),
`TASK_ACTION_FAILED` (code, attempt), `TASK_ACTION_RETRY`, `TASK_CANCEL`,
`TASK_CLOSED`, `TASK_LINE_UPDATES_APPLIED`, `TASK_SCAN_DROPPED` (scan while
sending / offline). Use `documentId = task.document_id` when present, else
`"task:<id>"`, so the per-document filter of the Debug Journal screen groups
them.

---

## 7. Test plan

**Unit** — listed per phase above; run with `./gradlew test`.

**Local end-to-end** — the server repo's `backend/scripts/wms-smoke.sh` seeds a
tenant with the module on, a warehouse with cells, products, batches, a
collector with all guided types, an outgoing shipment and an incoming receipt.
Point the app at that server (`Settings → server URL`) and walk the matrix.
Watch the Debug Journal for the §6 events and the server audit for
`wms.*` rows.

| # | Scenario | Expect |
|---|---|---|
| T1 | Login on a WMS-off tenant | no guided buttons, no *Unfinished tasks*, documents open classically, Room identical to before (spot-check `collect_mode` null) |
| T2 | Login on a WMS-on tenant | guided buttons present; `open_tasks` empty |
| T3 | `CELL_RECOUNT`: scan cell → scan product → qty → save | ledger event on the server; done screen; `done` returns home |
| T4 | Same, kill the app after the qty step | *Unfinished tasks* shows it; *Continue* renders the same step |
| T5 | Retry: airplane mode right after tapping *Save count*, then back online | one ledger event, response `replayed: true` on the retry, no duplicate |
| T6 | Two devices scan the same cell | second gets the "…is counting this cell" message and stays on its step |
| T7 | Leave the app for `lock_ttl_sec + 10 s`, come back, act | either continues (lock re-taken) or `LOCKED` by the other device — no crash, message shown |
| T8 | Guided Collect: detail bar → cell scan → qty (less than planned) → next source → review → confirm | `line_updates` visible on the classic detail screen; document reaches COLLECTED / PACK; list refreshes |
| T9 | Tenant flips *addressed picking* off mid-task | next list load shows the classic bar; the document is finished classically with the mirrored actuals |
| T10 | Receiving with two workers | first opens from the list; second uses *Join receiving* → picker → same document; per-line lock messages appear as row text; the document completes itself after the last line |
| T11 | `REPLENISH` after T8 left a pick cell low | `rp_pick` lists the row; scan from → batch → qty → scan to → done |
| T12 | Cancel a document task | server pauses the document (COLLECTING, no lock); detail shows *Resume*; classic *Take into work* absent |
| T13 | Demo login | no guided buttons; nothing new visible |

Spec acceptance ids the matrix covers: Ф-2 (T5), Ф-4 (T4), Ф-5 (T7), В-4/В-6/В-7 (T8), В-12 (T8 variant with an empty shelf → `no_stock`), П-1/П-5/П-8 (T10), Н-1/Н-3/Н-6 (T11), 13.2 emergency switch (T9).

---

## 8. Server follow-ups noticed while planning

Not blockers; tracked here so they are not lost.

1. **Force-release on a task-locked document.** The tenant UI's document *Release lock* on a document with an open guided task should cancel the task (`AdminCancel`, which pauses the document) instead of flagging `release_requested`, which the device now ignores for guided documents (D3).
2. **WMS-on hint on login.** The app infers "warehouse has WMS" from the presence of guided types (Phase 2 *Join receiving*). A `features` or `wms_enabled` boolean on the login response would make that explicit. Optional.
3. **Second worker discovery.** The classic list hides a receiving document another worker holds; the picker covers it (known limitation in the server plan). A `visible_ids`-style "shared documents" hint could let the list show it read-only. Optional.
4. **`GET /device/tasks/open` payload** returns full envelopes; fine for a handful of tasks, worth capping server-side if a worker can accumulate many.

---

## 9. Open questions

1. **Cancel semantics on back.** Shipped as planned: *Back* leaves the task open
   (it reappears under *Unfinished tasks*) and only the server's `cancel` action
   closes it. Still worth confirming with the warehouse lead that an abandoned
   recount holding a cell lock until TTL is acceptable — F-5 says yes.
2. **Quantity unit.** *Resolved.* The server's `TaskActionRequest.Quantity` is
   `*int64`, so the wire is integer pieces; the digits-only field is correct.
3. **Camera-only devices.** *Still open — deferred.* The task screen uses the
   global `BarcodeService` (hardware scanner) only. No screen in the app hosts
   the camera **barcode** scanner yet, so adding the FAB is real work; it waits
   until the pilot fleet is known.
4. **Demo flow.** *Skipped.* `DemoTransport` answers every task message with
   `DEMO_UNSUPPORTED`, and the demo login offers no guided types, so nothing
   guided is reachable in demo mode. Build the scripted recount only if sales
   asks.

---

## 10. Deviations from this plan

Recorded where the implementation knowingly differs from §3.

1. **Full-set purge protected too.** The plan exempted the active task's
   document only in `purgeDocumentsOutsideVisibleSet`, but
   `GET /device/documents` answers `full_set: true`, and *that* branch is the
   one that would drop a joined receiving document — it is absent from the list
   the server returns to this worker, who is nonetheless working it. Both
   branches now share `SyncOrchestrator.activeTaskDocumentIds()`.
2. **No explicit list refresh on DONE.** `DocumentsScreen` already refreshes in
   a `LifecycleResumeEffect`, so navigating there from a finished task refreshes
   with the correct selected type; the planned
   `requestDocumentListRefresh(selectedType)` would have been a second
   round-trip.
3. **`refreshOpenTasks()` is gated on WMS being on.** The plan guarded it on
   `isOnline` alone, but `/device/tasks/open` sits behind the module gate, so an
   unguarded call collects a 403 on every Home resume of every classic tenant.
   It now also requires a guided type in the login catalog (or a known open
   task) — the same client-side hint the plan uses for *Join receiving*.
4. **One ERP code is known by name.** `AvailableDocumentType.CODE_INCOMING_RECEIPT`
   gates the guided bar's wording and the *Join receiving* shortcut, and nothing
   else. Server follow-up 2 (§8) would let the app drop it.
5. **No Room migration test.** The repo has no migration-test infrastructure
   (`room-testing` is not a dependency and schemas are not wired into androidTest
   assets) and migrations 16→17 and 17→18 shipped without one. 18→19 is three
   DDL statements validated against the exported `19.json` at runtime.
