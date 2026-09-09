# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PickApp is an offline-first Android warehouse management app for handheld terminal devices. It manages picking, receiving, and inventory operations with barcode scanning and real-time REST synchronization.

**Package**: `ua.com.programmer.pick`

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (minified + shrunk)
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests
./gradlew clean                  # Clean build artifacts
```

Single-module project — all code lives in `:app`.

## Tech Stack

- **Language**: Kotlin, JVM target 11
- **UI**: Jetpack Compose + Material 3 (single Activity)
- **DI**: Hilt (with KSP, not kapt)
- **Database**: Room v9 with 13 entities, schema exported to `app/schemas/`
- **Networking**: Retrofit 3 + OkHttp 5 (REST device transport)
- **Async**: Kotlin Coroutines + Flow + StateFlow
- **Background**: WorkManager (15-min periodic sync)
- **Scanning**: CameraX + ML Kit barcode, hardware scanner via KeyEvent interception
- **Storage**: DataStore (encrypted) for credentials, Room for app data
- **Versions**: Managed via `gradle/libs.versions.toml` version catalog

## Architecture

Clean Architecture with three layers, all in a single module:

```
app/src/main/java/ua/com/programmer/pick/
├── presentation/   # MVVM: Screens (Compose) + ViewModels + UiState/UiEvent
│   └── task/       #   Generic renderer for server-driven guided WMS tasks
├── domain/         # Models + Repository interfaces
├── data/           # Repository impls, Room DB, Retrofit APIs, sync, mappers
│   ├── local/      #   database/ (entities, DAOs) + preferences/ (DataStore)
│   ├── remote/     #   api/, dto/, interceptor/, transport/
│   ├── mapper/     #   Entity ↔ Domain conversions
│   ├── repository/ #   Repository implementations
│   ├── sync/       #   SyncOrchestrator, SyncScheduler
│   └── worker/     #   WorkManager workers
├── core/           # DI modules, scanner service, utilities
└── ui/theme/       # Compose theme (colors, typography)
```

### Key Patterns

- **MVVM per screen**: Each screen has a ViewModel exposing `StateFlow<UiState>` and handling `UiEvent`
- **Repository pattern**: Domain layer defines interfaces, data layer implements them
- **Offline-first**: Room DB is source of truth; changes queue in `OutgoingOperationEntity` for later sync
- **Complete-set sync**: Every document sync response from the server is the **complete set** for the current user/filter. The app always purges local documents not present in the response. There is no delta/partial document sync — if the server sends 0 documents, all local non-dirty documents are removed.
- **Transport seam**: `SyncTransport` interface (package `data/remote/transport`), with `RestTransport` as the sole implementation. REST is connectionless — `connect()` just marks the transport connected and `loginUser()` authenticates over HTTP. Every operation maps a request `SyncMessage` to a `DeviceRestClient` REST call and emits the result on `incomingMessages`, so `SyncOrchestrator`'s handlers stay transport-agnostic. `requiresPolling = true` drives active polling while a document is being worked. The legacy WebSocket transport was removed at cutover.

### Guided WMS Tasks

Available only where the tenant has the `wms_addressing` module and the
worker's warehouse has it switched on. Everything below is invisible — and
every classic path byte-for-byte unchanged — everywhere else. Contract:
server repo `docs/device-api.md` § "Guided tasks"; app plan
`docs/wms-guided-tasks-plan.md`.

- **The screen is a renderer.** One `TaskScreen` renders any step from
  `title / hint / lock_info / rows / actions / expect`. Unknown `expect`
  values fall back to `NONE`, unknown action codes render as plain buttons and
  are sent back as-is. **No per-flow code and no branch on step ids or task
  type** — a new server-side operation is a new set of steps, not an app
  release.
- **Online-only.** Guided actions are never queued in
  `OutgoingOperationEntity`. Offline the step stays visible and inputs are
  disabled.
- **Idempotency.** `operation_id` is a fresh UUID per action, reused verbatim
  across retries (3 attempts, 1/2/4 s), so a dropped connection replays the
  original response instead of applying a confirm twice. A response carrying a
  server error code is a verdict, not a hiccup — it is never retried.
- **Guided documents never enter `heldStageLocks`.** The task engine takes the
  Collect lock server-side; a classic claim on top would arm the cooperative
  force-release path. `reassertHeldStageLocksFromLogin` skips every id in
  `open_tasks[].document_id`, and both document purge paths exempt the active
  task's document.
- **The task screen is the heartbeat.** Since no stage lock is held, the
  orchestrator's active poll never runs for a task; `TaskViewModel` drives the
  same 8 s `requestDeltaSyncIfStale` cadence while resumed, which extends the
  server-side cell / line locks.
- **`line_updates` go straight to Room** via
  `DocumentRepository.applyServerLineUpdates` — matched by `line_key`, else by
  `line_number` — leaving `is_dirty` untouched. They are the server's own
  values; arming the dirty flag would push them back as worker edits.
- **Nothing about a task is persisted.** Not the id, not the step, not a
  draft. Resume comes from the login `open_tasks` list or
  `GET /device/tasks/open`. Same rule as document lock state.
- **`collect_mode` picks the detail screen's primary action, per load.**
  `"guided"` → the guided bar; absent → today's *Take into work*. An in-flight
  guided document whose flag disappears is finished classically — every
  confirmed line was already mirrored into the document.
- **Every string inside a step is server-authored** in the tenant's locale and
  shown verbatim. App strings cover only the chrome around it.
- **No role branching.** The server decides which types, documents and tasks
  the worker sees.

### Document Workflow

Core domain concept — documents flow through states:
`LOADED → COLLECTING → COLLECTED → DELIVERING → DELIVERED → SENT → ERROR`

User roles: `COLLECTOR` (assembles orders), `COURIER` (delivers boxes), `ADMINISTRATOR`

Three document types: `INCOMING_RECEIPT`, `OUTGOING_SHIPMENT`, `INVENTORY`

### Server-Driven Architecture (Critical)

The app is a **thin display layer**. All business logic and data filtering lives on the server.

**Document loading:** The app sends `DOCUMENT_LIST_REFRESH` (with optional `document_type`) or `SYNC_REQUEST` — the server decides which documents to return based on the user's role, warehouse assignment, and queue state. The app does NOT filter, select, or request specific documents by role. All roles use the same sync flow. Every response is the **complete set** — the app purges any local documents not in the response.

**Document deletion:** When documents are deleted on the server (via ERP sync or admin action), the server returns `deleted_ids` in the sync response. The app also purges stale documents on every sync response (complete-set principle).

**Document locking:** Viewing a document does NOT lock it. Locking (`DOCUMENT_LOCK`) is always an explicit user action ("Take into work" button). The app sends the lock request to the server, waits for confirmation, and only then updates local state. No optimistic/offline locking — if the server is unreachable, the lock fails.

**Scanning and updates:** Product barcode lookup uses the local Room DB (populated by sync). Line quantity changes are saved locally and synced to the server. The server is the source of truth for document state transitions.

**Do NOT add app-side role logic.** If different roles need different behavior (different documents, different actions), implement it on the server and let the app display whatever the server sends. Do NOT add `document_type` filtering to `SYNC_REQUEST` — use `DOCUMENT_LIST_REFRESH` for that.

### Hilt DI Modules (in `core/di/`)

- `AppModule`: Coroutine dispatchers (`@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`)
- `NetworkModule`: Retrofit, OkHttp client, Gson, auth interceptors
- `DatabaseModule`: Room database, all DAOs, demo data seeding
- `RepositoryModule`: Binds repository interfaces to implementations

### Barcode Scanning (`core/scanner/`)

- `BarcodeService`: Unified interface for scanning
- `HardwareScannerManager`: Physical barcode scanner via keyboard events
- `CameraScannerManager`: Camera-based scanning via CameraX + ML Kit
- `GS1Parser`: Parses GS1 DataMatrix barcodes

## Conventions

- Commit messages follow conventional commits: `type(scope): description`
- UI strings go in `res/values/strings.xml` (Ukrainian localization in `values-uk/`)
- Room migrations: bump version in `AppDatabase`, schema exports to `app/schemas/`
- Backend base URL: configured via secrets gradle plugin (`local.properties`)
- Backend server project: `~/projects/pick` (Go, REST)
