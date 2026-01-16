# PickApp TSD Implementation Plan

## Overview

This plan transforms the existing PickApp foundation into a fully functional offline-first warehouse TSD application following `app_develop_plan.md` requirements.

**Current State:** Authentication working, basic navigation, reference data models defined
**Target State:** Complete MVP with document workflows, WebSocket sync, barcode scanning, offline queue

---

## Phase 1: Document Domain Foundation

**Goal:** Establish complete document data model

### Files to Create

| File | Purpose |
|------|---------|
| `domain/model/DocumentType.kt` | Enum: INCOMING_RECEIPT, OUTGOING_SHIPMENT, INVENTORY |
| `domain/model/DocumentState.kt` | Enum: LOADED, IN_PROGRESS, COMPLETED, SENT, ERROR |
| `domain/model/Document.kt` | Core document model with header fields |
| `domain/model/DocumentLine.kt` | Line item model with quantities, batch, location |
| `data/local/database/entity/DocumentEntity.kt` | Room entity with indices |
| `data/local/database/entity/DocumentLineEntity.kt` | Room entity with FK to document |
| `data/local/database/entity/OutgoingOperationEntity.kt` | Offline queue entity |
| `data/local/database/dao/DocumentDao.kt` | CRUD + state queries |
| `data/local/database/dao/DocumentLineDao.kt` | Line operations + barcode lookup |
| `data/local/database/dao/OutgoingOperationDao.kt` | Queue management |
| `data/mapper/DocumentMapper.kt` | Entity <-> Domain conversions |

### Files to Modify

| File | Changes |
|------|---------|
| `data/local/database/AppDatabase.kt` | Add 3 new entities, DAOs, bump to version 2 |

### Database Schema

```sql
-- documents: id, external_id, type, number, date, state, client_id/name,
--            warehouse_id/name, notes, total_planned/actual, assigned_user_id,
--            taken_at, completed_at, last_modified, version, is_dirty

-- document_lines: id, document_id (FK), line_number, product_id/code/name,
--                 unit, planned_quantity, actual_quantity, batch_number,
--                 expiration_date, location_id/path, notes, is_completed, is_dirty

-- outgoing_operations: id, operation_type, entity_type, entity_id,
--                      payload (JSON), created_at, retry_count, last_error, status
```

---

## Phase 2: Repository Layer

**Goal:** Complete repository implementations for all entities

### Files to Create

| File | Purpose |
|------|---------|
| `domain/repository/DocumentRepository.kt` | Interface: CRUD, state transitions, sync |
| `domain/repository/ProductRepository.kt` | Interface: queries, barcode lookup, sync |
| `domain/repository/ClientRepository.kt` | Interface: queries, search, sync |
| `domain/repository/WarehouseRepository.kt` | Interface: queries, location lookup, sync |
| `domain/repository/OutgoingOperationRepository.kt` | Interface: queue management |
| `data/repository/DocumentRepositoryImpl.kt` | Document business logic |
| `data/repository/ProductRepositoryImpl.kt` | Product operations |
| `data/repository/ClientRepositoryImpl.kt` | Client operations |
| `data/repository/WarehouseRepositoryImpl.kt` | Warehouse + location operations |
| `data/repository/OutgoingOperationRepositoryImpl.kt` | Queue processing |
| `data/mapper/ProductMapper.kt` | Product entity <-> domain |
| `data/mapper/ClientMapper.kt` | Client entity <-> domain |
| `data/mapper/WarehouseMapper.kt` | Warehouse/location entity <-> domain |

### Files to Modify

| File | Changes |
|------|---------|
| `core/di/RepositoryModule.kt` | Bind all new repositories |
| `core/di/DatabaseModule.kt` | Provide new DAOs |

### Key Repository Methods

**DocumentRepository:**
- `getDocumentsByType(type)` - Flow of documents by type
- `takeIntoWork(documentId)` - Lock document, queue operation
- `updateLine(lineId, quantity, notes)` - Edit line, mark dirty
- `incrementLineQuantity(lineId, delta)` - Barcode scan increment
- `completeDocument(documentId)` - State transition + queue

**ProductRepository:**
- `getProductByBarcode(barcode)` - Barcode lookup for scanning

---

## Phase 3: WebSocket & Synchronization

**Goal:** Real-time and background sync

### Files to Create

| File | Purpose |
|------|---------|
| `data/remote/websocket/WebSocketManager.kt` | Connection management, reconnection |
| `data/remote/websocket/SyncMessage.kt` | Message types (DeltaUpdate, DocumentLock, Ack) |
| `data/remote/websocket/MessageParser.kt` | JSON <-> SyncMessage |
| `data/sync/SyncOrchestrator.kt` | Coordinates all sync operations |
| `data/worker/SyncWorker.kt` | Periodic background sync (WorkManager) |
| `data/worker/UploadWorker.kt` | Immediate upload on connectivity |
| `data/sync/SyncScheduler.kt` | WorkManager scheduling |
| `data/remote/dto/DocumentDto.kt` | Document DTOs for API |
| `data/remote/dto/DocumentLineDto.kt` | Line item DTOs |

### Files to Modify

| File | Changes |
|------|---------|
| `core/di/NetworkModule.kt` | Provide WebSocketManager |
| `core/Constants.kt` | WebSocket URL, sync intervals |
| `PickApplication.kt` | Initialize SyncScheduler |

### WebSocket Protocol

```
Server -> Client: DELTA_UPDATE, DOCUMENT_LOCK
Client -> Server: TAKE_INTO_WORK, DOCUMENT_UPDATE, ACK
```

### Offline Queue Processing

1. FIFO order maintained
2. Exponential backoff: 1s, 2s, 4s, 8s (max 5 retries)
3. Version-based conflict detection
4. Idempotent operations

---

## Phase 4: Barcode Scanning

**Goal:** Hardware scanner + camera fallback

### Files to Create

| File | Purpose |
|------|---------|
| `core/scanner/ScannerManager.kt` | Interface for scanner events |
| `core/scanner/ScanResult.kt` | Success/Error sealed class |
| `core/scanner/BarcodeFormat.kt` | Enum: EAN_8, EAN_13, CODE_128, QR, DATA_MATRIX |
| `core/scanner/HardwareScannerManager.kt` | Honeywell/Zebra broadcast receiver |
| `core/scanner/CameraScannerManager.kt` | CameraX + ML Kit integration |
| `core/scanner/GS1Parser.kt` | GS1 DataMatrix parsing (GTIN, batch, expiry) |
| `core/scanner/BarcodeService.kt` | Unified API for scanning + product lookup |

### Dependencies to Add

```kotlin
// build.gradle.kts
implementation("androidx.camera:camera-camera2:1.5.0")
implementation("androidx.camera:camera-lifecycle:1.5.0")
implementation("androidx.camera:camera-view:1.5.0")
implementation("com.google.mlkit:barcode-scanning:17.3.0")
```

### Hardware Scanner Support

- Honeywell: `com.honeywell.aidc.extra.BARCODE_DATA`
- Zebra: `com.symbol.datawedge.data_string`
- Generic: `device.scanner.BARCODE_READ`

---

## Phase 5: UI Implementation

TL;DR — Implement Compose screens and small shared UI components, wire ViewModels with Hilt, extend NavGraph, and add focused unit + 1–2 instrumented tests. Prioritize core flows (Splash → Login → Home → Documents → DocumentDetail) to enable end-to-end manual QA and backend sync validation.

---

### Prioritized screens/components (top-down order)
1. Splash (S)
2. Login (S) — refine existing
3. Home (M) — refine existing
4. Documents list (L)
5. Document detail (L)
6. Settings (M)
7. Profile (S)
8. Shared components + small helpers (S) — implemented alongside above as needed

---

### Screens — detailed spec

Note: file paths use repository convention: `app/src/main/java/ua/com/programmer/pick/presentation/...`

1) Splash
- Files:
  - `presentation/splash/SplashScreen.kt`
  - `presentation/splash/SplashViewModel.kt`
  - `presentation/splash/SplashUiState.kt`
- Responsibilities:
  - Short-lived entry UI that checks auth state and isOnline, then navigates to Login or Home.
  - Optionally show app logo/branding and progress if initial DB migration/sync is running.
- Inputs / outputs (data shapes):
  - Input: none
  - Output/navigation: Navigate to `Screen.Login.route` or `Screen.Home.route`
  - ViewModel may expose `isReady: Boolean` and `targetRoute: String?`
- Acceptance criteria:
  - On cold start, screen appears, reads auth state and network state, and navigates to correct route within 2s (or after DB init completes).
  - If DB/migrations fail, show an error with retry action.
- ViewModel contract:
  - public state: `StateFlow<SplashUiState>` where `SplashUiState(targetRoute: String? = null, isLoading: Boolean = true, errorMessage: String? = null)`
  - public methods: `fun start()` (triggers checks), `fun retry()`
- DI & navigation:
  - Hilt-provide: `UserRepository`, `NetworkMonitor`, `DatabaseChecker` (if exists)
  - Add `Screen.Splash` route to `NavGraph` and ensure `startDestination` can be switched to `Screen.Splash.route` in app bootstrap.
- Small components:
  - `LoadingLogo` (reusable)
- Accessibility/performance/testing:
  - Provide contentDescription for logo.
  - Keep UI light; no long-running work on main thread.
  - Unit test: `start()` chooses route based on fake UserRepository.
- Effort: S — implement first.

2) Login (existing; refine)
- Files:
  - `presentation/auth/LoginScreen.kt` (exists — refine)
  - `presentation/auth/LoginViewModel.kt` (exists — extend where needed)
  - `presentation/auth/LoginUiState.kt` (exists)
- Responsibilities:
  - Collect credentials, show offline mode indicator, handle login flow and errors.
- Inputs / outputs:
  - Input: user typed `login: String`, `password: String`
  - Output: on success navigates to Home (callback)
  - UI state: `LoginUiState` (already present)
- Acceptance criteria:
  - Empty credentials show validation error.
  - Network error shows message and enables retry.
  - Successful login triggers `onLoginSuccess`.
- ViewModel contract (already present; confirm public API):
  - state: `StateFlow<LoginUiState>` with fields: login, password, isLoading, isLoggedIn, errorMessage, isOfflineMode
  - methods: `fun login(login: String, password: String)`, `fun clearError()`
- DI & navigation:
  - Already uses Hilt and NavGraph; confirm `onLoginSuccess` moves to Home and pops Login.
- Small components:
  - `PasswordTextField` (with show/hide), `OfflineBanner`, `PrimaryButton`, `ErrorSnackbar`
- Accessibility/performance/testing:
  - Text fields: contentDescription, label semantics, keyboard type for password.
  - Test: unit tests for `login()` success/error branches, and UI test for form validation.
- Effort: S — quick polish after Splash.

3) Home (existing; add features)
- Files:
  - `presentation/home/HomeScreen.kt` (exists — extend)
  - `presentation/home/HomeViewModel.kt` (exists)
  - `presentation/home/HomeUiState.kt` (exists)
- Responsibilities:
  - Show current user, sync status, shortcuts to Documents, Settings, Profile, manual sync action.
  - Show list of recent or assigned documents (optional).
- Inputs / outputs:
  - Input: none
  - Output: navigation actions to Documents, Settings, Profile
  - UI state: `HomeUiState` (currentUser: User?, syncStates: List<SyncState>, isOnline, lastSyncTime, isLoading, errorMessage)
- Acceptance criteria:
  - Displays current user name.
  - Shows online/offline indicator and last sync time.
  - Tapping Documents navigates to Documents screen.
  - Manual sync button calls `syncData()` and updates loading indicator.
- ViewModel contract:
  - state: `StateFlow<HomeUiState>`
  - methods: `fun logout()`, `fun syncData()`, `fun refresh()`
- DI & navigation:
  - Add navigation action to `Screen.Documents` (via NavController).
  - Hilt: no new DI needed; reuse `UserRepository` and `NetworkMonitor`. Add `SyncRepository` injection if one exists.
- Small components:
  - `SyncStatusChip`, `UserRow`, `ShortcutGridItem`
- Accessibility/performance/testing:
  - Ensure dynamic content has stable keys for LazyColumn; avoid heavy recomposition.
  - Test HomeViewModel state updates with mocked network and user repos.
- Effort: M — extend current implementation.

4) Documents (list)
- Files:
  - `presentation/documents/DocumentsScreen.kt`
  - `presentation/documents/DocumentsViewModel.kt`
  - `presentation/documents/DocumentsUiState.kt`
  - `presentation/documents/DocumentListItem.kt` (component)
- Responsibilities:
  - Show paginated list of `Document` items (filter/search), support pull-to-refresh, select/open document, indicate sync state per item.
- Inputs / outputs:
  - Input: optional filter/search query (String)
  - Output: navigation to `Screen.DocumentDetail.createRoute(documentId)`, selection events, possibly bulk actions.
  - UI state shape:
    - DocumentsUiState(
        documents: List<Document>,
        isLoading: Boolean,
        isRefreshing: Boolean,
        query: String?,
        errorMessage: String?
      )
- Acceptance criteria:
  - List loads and displays documents from local DB (Room).
  - Pull-to-refresh triggers repository sync stub and refreshes list.
  - Each item displays number, date, clientName, totals, and sync icon for `isDirty`.
  - Clicking item navigates to DocumentDetail with correct documentId.
  - Search filters results locally and shows empty state when none.
- ViewModel contract:
  - state: `StateFlow<DocumentsUiState>`
  - methods:
    - `fun loadDocuments(forceRefresh: Boolean = false)`
    - `fun onSearch(query: String)`
    - `fun onDocumentClick(documentId: String)`
    - `fun clearError()`
- DI & navigation:
  - Hilt injection of `DocumentRepository` and `SyncRepository`.
  - Add `composable` for `Screen.Documents.route` in `NavGraph.kt`, using `hiltViewModel<DocumentsViewModel>()`.
- Small components/shared components:
  - `DocumentListItem.kt`, `EmptyState.kt`, `PullRefreshLayout` (use Accompanist/Compose Pull Refresh), `SyncIcon`
- Accessibility/performance/testing:
  - Use `LazyColumn` with stable keys = document.id; implement item contentDescription summarizing document.
  - Test: unit test for filtering, viewmodel load path; instrumented test: open Documents and scroll large list.
- Effort: L — central feature.

5) Document Detail
- Files:
  - `presentation/document/DocumentDetailScreen.kt`
  - `presentation/document/DocumentDetailViewModel.kt`
  - `presentation/document/DocumentDetailUiState.kt`
  - `presentation/document/DocumentLineRow.kt` (component)
  - `presentation/document/QuantityStepper.kt` (small component)
- Responsibilities:
  - Show full Document info + list of `DocumentLine`s; allow editing actual quantities, marking lines complete, scanning barcodes to fill product/quantity, persist changes locally (mark dirty), and optionally sync.
- Inputs / outputs:
  - Input: `documentId: String` (via nav arg)
  - Output: persist changes, update Document and DocumentLine records, indicate unsynced changes (isDirty).
  - UI state:
    - DocumentDetailUiState(
        document: Document?,
        lines: List<DocumentLine>,
        isLoading: Boolean,
        isSaving: Boolean,
        errorMessage: String?
      )
- Acceptance criteria:
  - Loads document and lines from DB.
  - Editing a line updates UI immediately and persists locally.
  - Mark line complete toggles `isCompleted`.
  - Unsynchronized changes show dirty indicator.
  - Back navigation returns to Documents and shows updated list.
- ViewModel contract:
  - state: `StateFlow<DocumentDetailUiState>`
  - methods:
    - `fun load(documentId: String)`
    - `fun updateLineQuantity(lineId: String, newQuantity: Double)`
    - `fun toggleLineComplete(lineId: String)`
    - `fun saveChanges()`
    - `fun scanBarcodeResult(result: String)` (optional)
- DI & navigation:
  - Hilt: `DocumentRepository`, `BarcodeScannerManager` (if scanning)
  - NavGraph: `composable(Screen.DocumentDetail.route, arguments = listOf(navArgument(SCREEN.DOCUMENT_ID_ARG)))` and provide `documentId` to ViewModel via SavedStateHandle or pass to `load()`.
- Small components/shared components:
  - `DocumentHeader`, `DocumentLineRow`, `QuantityStepper`, `ConfirmDialog`, `BarcodeScannerOverlay` (opt.)
- Accessibility/performance/testing:
  - Make quantity controls large enough for touch, add contentDescription for each row, ensure TalkBack reads line summaries.
  - Performance: large documents use LazyColumn; perform small-batch DB writes (debounce saves).
  - Tests: unit tests for `updateLineQuantity` persistence behavior; instrumented: open document, edit quantity, press back, verify DB updated.
- Effort: L — critical for correctness.

6) Settings
- Files:
  - `presentation/settings/SettingsScreen.kt`
  - `presentation/settings/SettingsViewModel.kt`
  - `presentation/settings/SettingsUiState.kt`
- Responsibilities:
  - Configure server URL, sync frequency, logout, app info, debug sync actions.
- Inputs / outputs:
  - Input: user settings form
  - Output: persist settings (SharedPreferences/DataStore), possible restart prompt
- Acceptance criteria:
  - Settings saved and reloaded across app restart.
  - Clear sync cache / re-sync actions work.
- ViewModel contract:
  - state: `StateFlow<SettingsUiState>`
  - methods: `fun saveSettings(...)`, `fun clearCache()`, `fun forceSync()`
- DI & navigation:
  - Inject SettingsRepository/DataStore.
  - Nav action from Home.
- Small components:
  - `SettingsRow`, `ConfirmDialog`
- Accessibility/performance/testing:
  - Large touch targets for toggles, test save/load behaviors.
- Effort: M

7) Profile
- Files:
  - `presentation/profile/ProfileScreen.kt`
  - `presentation/profile/ProfileViewModel.kt`
  - `presentation/profile/ProfileUiState.kt`
- Responsibilities:
  - Show & edit user profile info (name), show role, last login, deactivate/activate actions.
- Acceptance criteria:
  - Display current data; edit persists to repositories.
- ViewModel contract:
  - state: `StateFlow<ProfileUiState>`
  - methods: `fun loadProfile()`, `fun updateName(newName: String)`
- DI, components, tests:
  - Standard patterns apply.
- Effort: S

---

### ViewModel contracts (concise summary)

Follow current repo pattern: `@HiltViewModel` + `MutableStateFlow` private + `StateFlow` public.

- SplashViewModel
  - state: `StateFlow<SplashUiState(targetRoute:String?, isLoading:Boolean, errorMessage:String?)>`
  - methods: `fun start()`, `fun retry()`

- LoginViewModel (existing)
  - state: `StateFlow<LoginUiState>`
  - methods: `fun login(login: String, password: String)`, `fun clearError()`

- HomeViewModel (extend)
  - state: `StateFlow<HomeUiState>`
  - methods: `fun logout()`, `fun syncData()`, `fun refresh()`

- DocumentsViewModel
  - state: `StateFlow<DocumentsUiState(documents: List<Document>, isLoading:Boolean, isRefreshing:Boolean, query:String?, errorMessage:String?)>`
  - methods:
    - `fun loadDocuments(forceRefresh:Boolean = false)`
    - `fun onSearch(query: String)`
    - `fun onDocumentClick(documentId: String)`
    - `fun clearError()`

- DocumentDetailViewModel
  - state: `StateFlow<DocumentDetailUiState(document: Document?, lines: List<DocumentLine>, isLoading:Boolean, isSaving:Boolean, errorMessage:String?)>`
  - methods:
    - `fun load(documentId: String)`
    - `fun updateLineQuantity(lineId: String, newQuantity: Double)`
    - `fun toggleLineComplete(lineId: String)`
    - `fun saveChanges()`
    - `fun scanBarcodeResult(result: String)` (optional)

- SettingsViewModel
  - state: `StateFlow<SettingsUiState>`
  - methods: `fun saveSettings(...)`, `fun clearCache()`, `fun forceSync()`

- ProfileViewModel
  - state: `StateFlow<ProfileUiState>`
  - methods: `fun loadProfile()`, `fun updateName(String)`

---

### DI and navigation changes needed

- Navigation:
  - Add `Screen.Splash`, `Screen.Documents`, `Screen.DocumentDetail`, `Screen.Settings`, `Screen.Profile` routes to `PickNavGraph`:
    - For each new screen add a `composable(route = Screen.X.route) { val vm: XViewModel = hiltViewModel(); val uiState by vm.uiState.collectAsState(); XScreen(uiState = uiState, ...callbacks...) }`
    - DocumentDetail must use nav arg: `route = Screen.DocumentDetail.route` and retrieve `documentId` from NavBackStackEntry arguments (or use SavedStateHandle in ViewModel).
  - Optionally switch `startDestination` to `Screen.Splash.route` (recommended).

- DI (Hilt):
  - Ensure new ViewModels are annotated with `@HiltViewModel`.
  - Add a UI/feature-level DI module if needed: `presentation/di/UiModule.kt` to provide:
    - `BarcodeScannerManager` (if scanning)
    - `DateFormatter` (shared)
    - `ImageLoader` or other UI helpers
  - Reuse existing domain repositories: `DocumentRepository`, `UserRepository`, `SyncRepository`, `NetworkMonitor`, `SettingsRepository`.
  - Use `SavedStateHandle` injection for DocumentDetailViewModel to receive `documentId` if desired:
    - `class DocumentDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, private val repo: DocumentRepository)`

- Navigation helpers:
  - Add typed navigation extension helpers (optional) to centralize route creation; e.g., use existing `Screen.DocumentDetail.createRoute(id)`.

---

### Small UI & shared components to create (file names + purpose)
- `presentation/common/TopBar.kt` — app top bar (title, back, overflow)
- `presentation/common/OfflineBanner.kt` — shows offline state
- `presentation/common/ErrorSnackbar.kt` — standardized error display
- `presentation/common/EmptyState.kt` — empty list placeholder with illustration
- `presentation/common/LoadingPlaceholder.kt` — full-screen loading
- `presentation/documents/DocumentListItem.kt` — list row for Document
- `presentation/document/DocumentLineRow.kt` — row for a document line, quantity control
- `presentation/document/QuantityStepper.kt` — increase/decrease numeric control
- `presentation/common/SyncStatusChip.kt` — shows sync state
- `presentation/common/SearchBar.kt` — search control with debounce
- `presentation/common/ConfirmDialog.kt` — reusable confirm dialog
- `presentation/common/BarcodeScannerOverlay.kt` (optional) — camera overlay for scanning

Create components under `presentation/common` or the relevant feature folder to keep single responsibility.

---

### Accessibility, performance and testing notes (per-screen summary)

- General (all screens)
  - Use semantic modifiers (contentDescription, role).
  - Respect font scaling (sp), large touch targets (min 48dp), and contrast.
  - Add TalkBack-friendly summaries (e.g., "Document 123, client ACME, total planned 100").
  - Use stable keys on LazyColumn/LazyRow: key = item.id.
  - Avoid heavy synchronous work on composition; use coroutines and show LoadingPlaceholder.
  - Add unit tests for ViewModel logic and small snapshot/compose tests for critical Composables.
  - Provide instrumentation tests for navigation and core flows.

- Documents list
  - Performance: use pagination (if dataset is large) or Room Paging 3 integration (consider later). For now, LazyColumn with paging or incremental loading.
  - Accessibility: each item composite should have a combined contentDescription summarizing state.
  - Tests: unit test for filter logic; instrumented UI test for scroll and open document.

- Document detail
  - Performance: batch DB writes (debounce 300–500ms) when user types quantity.
  - Accessibility: each line's buttons labeled; stepper has description "Increase quantity for line X".
  - Tests: unit test for quantity update/save; instrumented test for edit flow and persistence.

- Login / Splash / Home / Settings / Profile
  - Keep them light; test navigation and state changes.
  - Ensure network/offline indicators are announced for accessibility.

---

### Estimated relative effort & implementation order (T-shirt sizes + order)
1. Splash — S (order 1)
2. Login (polish) — S (order 2)
3. Home (extend) — M (order 3)
4. Documents list — L (order 4)
5. Document detail — L (order 5)
6. Settings — M (order 6)
7. Profile — S (order 7)
8. Shared components — S (implement incrementally as needed during steps 2–6)

Implement Splash→Login→Home first to verify navigation and ViewModel patterns. Then Documents and DocumentDetail (largest features). Finish Settings/Profile.

---

## Compact implementation checklist (developer steps)

Preparation
- Open the project in Android Studio and select target device/emulator.

Step-by-step tasks (concrete)

1. Project bootstrap & nav start
   - Update `Screen.kt` if missing routes (already present).
   - Set `startDestination` in `NavGraph.kt` to `Screen.Splash.route`. Add new composable entry for Splash.
     - Files to create: `presentation/splash/SplashScreen.kt`, `SplashViewModel.kt`, `SplashUiState.kt`.

2. Verify and polish Login & Home flows
   - Update `LoginScreen.kt` UI to use `OfflineBanner`, `ErrorSnackbar`. Ensure `LoginViewModel.login(...)` remains the single source of truth.
   - Update `HomeScreen.kt` to expose navigation callbacks to Documents/Settings/Profile and a manual sync button.

3. Add Documents feature
   - Create `presentation/documents/DocumentsScreen.kt`, `DocumentsViewModel.kt`, `DocumentsUiState.kt`.
   - Create `DocumentListItem.kt` component.
   - Wire navigation: add `composable(route = Screen.Documents.route)` in `NavGraph.kt`.
   - Inject `DocumentRepository` into ViewModel; implement `loadDocuments()` to collect from Room.

4. Add DocumentDetail feature
   - Create `presentation/document/DocumentDetailScreen.kt`, `DocumentDetailViewModel.kt`, `DocumentDetailUiState.kt`.
   - Create `DocumentLineRow.kt`, `QuantityStepper.kt`.
   - Wire nav route `Screen.DocumentDetail` with `documentId` arg. In NavGraph, call `hiltViewModel<DocumentDetailViewModel>()` and either pass `documentId` to `load()` or rely on `SavedStateHandle`.

5. Add Settings & Profile screens
   - Create `presentation/settings/*` and `presentation/profile/*` files and wire composables to NavGraph.

6. Shared components
   - Create `presentation/common/*` components as used by screens (TopBar, OfflineBanner, ErrorSnackbar, EmptyState, SyncStatusChip, SearchBar).

7. DI changes
   - Add any small presentation-level module `presentation/di/UiModule.kt` if needed.
   - Annotate new ViewModels with `@HiltViewModel`. Inject existing repositories and `NetworkMonitor` as required.

8. Tests
   - Add unit tests for each new ViewModel in `app/src/test/...`.
   - Add 1–2 instrumented tests under `app/src/androidTest/...`: navigation login->home->documents and detail-edit-persist.

9. Final verification
   - Ensure build passes: compile & run app, exercise flows.

Commands to run locally (PowerShell on Windows)
- Run all unit tests:
  .\gradlew.bat test
- Build and assemble debug APK:
  .\gradlew.bat :app:assembleDebug
- Run instrumented tests (device required):
  .\gradlew.bat connectedAndroidTest
- Clean:
  .\gradlew.bat clean

Manual QA verification checklist (quick)
- Splash: app opens to splash, then navigates automatically to Login (or Home if logged in).
- Login: empty credentials show validation; valid credentials navigate to Home.
- Home: shows user name, online/offline indicator, manual sync button toggles loading.
- Documents: open Documents, list populates, pull-to-refresh triggers refresh, click item opens DocumentDetail.
- DocumentDetail: edit line quantity, toggle complete, press back → changes persisted locally (verify in DB or by reopening).
- Settings/Profile: save changes persist across restart.
- Offline behavior: with network disabled, offline banner shown and operations that require network show informative errors.
- Accessibility spot-check: TalkBack reads top-level summaries for Document items and Quantity controls.

---

## Minimal test matrix (unit + instrumented)

Unit tests (fast, run in `test`):
- LoginViewModel:
  - Validate `login()` success path: sets isLoading false and isLoggedIn true when UserRepository returns success.
  - Validate `login()` error path: sets errorMessage appropriately.
- HomeViewModel:
  - NetworkMonitor change updates `isOnline`.
  - `syncData()` toggles `isLoading` state.
- DocumentsViewModel:
  - `loadDocuments()` returns DB data; `onSearch()` filters in-memory results.
- DocumentDetailViewModel:
  - `updateLineQuantity()` updates state and marks `isDirty`.
  - Debounce/save behavior persists changes via `DocumentRepository` mock.

Instrumented tests (run on device/emulator; `connectedAndroidTest`):
1. Login → Home → Documents navigation test
   - Launch app, ensure Login shown, perform login (use test credentials or stubbed repo), verify Home is shown, navigate to Documents, scroll list, open first document, verify DocumentDetail displayed.
2. Document edit persistence test
   - Open a known test Document, edit a line quantity, press back, reopen document and verify persisted quantity (validates Room integration).

What to validate in tests:
- State transitions (isLoading, errorMessage).
- Navigation movements (route changes).
- Persistence to Room (document and line updates are saved).
- Offline UI (isOfflineMode flags cause offline banner to show).

---

Requirements coverage
- Prioritized screens: Done (list + order)
- Per-screen files/responsibilities: Done (file names and responsibilities)
- Inputs/outputs & acceptance criteria: Done per-screen
- ViewModel contracts: Done (public methods + state shapes)
- DI & navigation changes: Done (summary + specifics)
- Small/shared components: Done (list)
- Accessibility/performance/testing notes: Done per-screen summary
- Relative effort & order: Done (T-shirt sizes + order)
- Implementation checklist + commands + verification checklist: Done
- Minimal test matrix: Done

If you want, I can now convert any single screen spec above into a more granular task list with exact TODOs per file (Kotlin signatures and sample UiState fields) for immediate implementation. Which screen should I expand next?
