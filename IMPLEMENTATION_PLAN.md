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

**Goal:** All screens for document workflows

### Files to Create

**Navigation:**
| File | Purpose |
|------|---------|
| `presentation/navigation/Screen.kt` | Update with all routes |

**Home:**
| File | Purpose |
|------|---------|
| `presentation/home/HomeScreen.kt` | Enhanced with document type cards |
| `presentation/home/HomeViewModel.kt` | Document counts, role-based access |
| `presentation/home/HomeUiState.kt` | Updated state |

**Document List:**
| File | Purpose |
|------|---------|
| `presentation/document/list/DocumentListScreen.kt` | Document list with status |
| `presentation/document/list/DocumentListViewModel.kt` | List logic |
| `presentation/document/list/DocumentListUiState.kt` | List state |

**Document Detail:**
| File | Purpose |
|------|---------|
| `presentation/document/detail/DocumentDetailScreen.kt` | Header + lines |
| `presentation/document/detail/DocumentDetailViewModel.kt` | Detail + actions |
| `presentation/document/detail/DocumentDetailUiState.kt` | Detail state |

**Line Edit:**
| File | Purpose |
|------|---------|
| `presentation/document/line/LineEditScreen.kt` | Quantity editor (large TSD buttons) |
| `presentation/document/line/LineEditViewModel.kt` | Line editing logic |
| `presentation/document/line/LineEditUiState.kt` | Edit state |

**Scanner:**
| File | Purpose |
|------|---------|
| `presentation/scanner/ScannerScreen.kt` | Camera preview + hardware handling |
| `presentation/scanner/ScannerViewModel.kt` | Scan processing |
| `presentation/scanner/ScannerUiState.kt` | Scanner state |

**Settings:**
| File | Purpose |
|------|---------|
| `presentation/settings/SettingsScreen.kt` | Sync status, connection config |
| `presentation/settings/SettingsViewModel.kt` | Settings logic |

**Common Components:**
| File | Purpose |
|------|---------|
| `presentation/common/DocumentStateChip.kt` | State indicator chip |
| `presentation/common/ConnectionStatusBar.kt` | Online/offline banner |
| `presentation/common/EmptyState.kt` | Empty list placeholder |

### Files to Modify

| File | Changes |
|------|---------|
| `presentation/navigation/NavGraph.kt` | Add all new routes |

### Screen Routes

```
home -> documents/{type} -> document/{id} -> line/{lineId}
                        -> scanner/{mode}
home -> settings -> sync
```

---

## Phase 6: Testing & Polish

**Goal:** Production readiness

### Test Files to Create

| File | Purpose |
|------|---------|
| `test/.../mapper/DocumentMapperTest.kt` | Mapper unit tests |
| `test/.../repository/DocumentRepositoryTest.kt` | Repository logic tests |
| `test/.../scanner/GS1ParserTest.kt` | GS1 parsing tests |
| `androidTest/.../dao/DocumentDaoTest.kt` | DAO integration tests |
| `androidTest/.../DocumentFlowTest.kt` | UI flow tests |

### Final Tasks

- [ ] ProGuard rules for WebSocket and ML Kit
- [ ] Performance profiling (document lists, barcode scanning)
- [ ] Error reporting integration
- [ ] Release build testing

---

## Implementation Order Summary

```
Phase 1: Domain models + DB entities + DAOs
    |
    v
Phase 2: Repository interfaces + implementations
    |
    v
Phase 3: WebSocket + SyncOrchestrator + Workers  <-->  Phase 4: Barcode scanning
    |
    v
Phase 5: UI screens (Home -> List -> Detail -> Line -> Scanner -> Settings)
    |
    v
Phase 6: Tests + polish
```

---

## Verification Plan

### Phase 1 Verification
```bash
./gradlew test --tests "*MapperTest"
./gradlew connectedAndroidTest --tests "*DaoTest"
```

### Phase 2 Verification
```bash
./gradlew test --tests "*RepositoryTest"
```

### Phase 3-4 Verification
- Connect to test server, verify delta sync
- Test hardware scanner with TSD device
- Test camera scanning with ML Kit

### Phase 5 Verification
- Manual testing of complete flow:
  1. Login (online/offline)
  2. View document list
  3. Take document into work
  4. Scan products, edit quantities
  5. Complete document
  6. Verify sync upload

### Full Build
```bash
./gradlew clean assembleDebug
./gradlew test
./gradlew connectedAndroidTest
```

---

## Critical Files Summary

| Priority | File | Reason |
|----------|------|--------|
| 1 | `domain/model/Document.kt` | Core business entity |
| 2 | `data/local/database/AppDatabase.kt` | DB schema changes |
| 3 | `data/repository/DocumentRepositoryImpl.kt` | Business logic |
| 4 | `data/sync/SyncOrchestrator.kt` | Sync coordination |
| 5 | `presentation/navigation/NavGraph.kt` | Navigation hub |
| 6 | `core/scanner/BarcodeService.kt` | Scanning integration |
