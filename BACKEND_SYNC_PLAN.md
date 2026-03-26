# Backend v2 — Android App Sync Plan

This document describes the backend changes introduced in Pick v2 that require corresponding updates in the Android app. The backend code is already implemented — this plan is for planning the Android-side work.

## Summary of Backend Changes

The backend now implements a **two-stage shipment pipeline** with queue management, box tracking, and new user roles. The WebSocket protocol has new message types, entity sync has new data types, and the document state machine has expanded.

---

## 1. Role Rename

### What changed
- `WAREHOUSE_WORKER` → `COLLECTOR` (assembles orders from warehouse shelves, packs into boxes)
- `PICKER` → `COURIER` (picks up collected boxes and delivers to shipment service)
- `ADMINISTRATOR` stays the same

### Backend compatibility
The backend accepts old role names as aliases during a transition period. The `USER_LOGIN_RESULT` will return the new role names (`COLLECTOR`, `COURIER`).

### Android action required
- Update role constants/enums
- Update any UI text referencing roles
- Update Room entity if role is stored locally

---

## 2. Document State Machine

### What changed
Old: `LOADED → IN_PROGRESS → COMPLETED → SENT → ERROR`

New: `LOADED → COLLECTING → COLLECTED → DELIVERING → DELIVERED → SENT → ERROR`

| Old State | New State | Meaning |
|-----------|-----------|---------|
| `LOADED` | `LOADED` | In queue, waiting for collector |
| `IN_PROGRESS` | `COLLECTING` | Collector is assembling the order |
| `COMPLETED` | `COLLECTED` | Collection done, in courier queue |
| *(new)* | `DELIVERING` | Courier picked up all boxes, in transit |
| *(new)* | `DELIVERED` | Courier confirmed delivery |
| `SENT` | `SENT` | ERP acknowledged |

### Backend compatibility
`IN_PROGRESS` and `COMPLETED` are accepted as aliases for `COLLECTING` and `COLLECTED`. Sync responses will use the new state names.

### Android action required
- Update `DocumentState` enum with new states
- Update state-dependent UI (colors, labels, icons)
- Update any state filtering logic
- Room migration for state column

---

## 3. New User Field: `warehouse_id`

### What changed
Users now have an optional `warehouse_id` field that determines which document queue they receive work from. Set by admin/tenant operator via the web UI or ERP API.

### Sync impact
The `users` entity in `SYNC_DATA` now includes `warehouse_id` (nullable string).

### Android action required
- Add `warehouse_id` to User Room entity
- Room migration
- Possibly display assigned warehouse in user profile

---

## 4. Queue-Based Document Assignment (COLLECTOR role)

### What changed
Instead of seeing all documents, a **COLLECTOR** receives **one document at a time** from a per-warehouse queue ordered by document date.

### New WebSocket messages

#### NEXT_DOCUMENT_REQUEST (Client → Server)
```json
{
  "type": "NEXT_DOCUMENT_REQUEST",
  "payload": {}
}
```
No parameters needed — server resolves the worker's warehouse and finds the next eligible document.

#### NEXT_DOCUMENT_RESULT (Server → Client)
```json
{
  "type": "NEXT_DOCUMENT_RESULT",
  "payload": {
    "success": true,
    "document": { /* full DocumentSyncDto */ },
    "error": "queue is empty"
  }
}
```
Returns the next document already locked for this worker, or an error.

#### COLLECTION_COMPLETE (Client → Server)
```json
{
  "type": "COLLECTION_COMPLETE",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1"
  }
}
```
Transitions document from `COLLECTING` → `COLLECTED`. Response uses existing `DOCUMENT_COMPLETE_RESULT` format.

### Android action required
- **Collector flow**: Replace "see all documents + lock one" with "request next document" pattern
- New screen or modified document list for collector mode
- Send `NEXT_DOCUMENT_REQUEST` when ready for work
- Send `COLLECTION_COMPLETE` instead of `DOCUMENT_COMPLETE`
- Handle "queue is empty" state in UI

---

## 5. Box Scanning (COLLECTOR role)

### What changed
During order collection, the collector scans physical box barcodes to link boxes to the document. Each box gets a weight entry.

### New entity: Box (master data)
Synced via `SYNC_DATA` with entity_type `"boxes"`:
```json
{
  "id": "string",
  "external_id": "string",
  "barcode": "string",
  "name": "string",
  "length": 40,
  "width": 30,
  "height": 20,
  "is_active": true
}
```

### New entity: DocumentBox (instance)
Synced via `SYNC_DATA` with entity_type `"document_boxes"`:
```json
{
  "id": "string",
  "document_id": "string",
  "box_id": "string",
  "barcode": "string",
  "weight": 2500,
  "status": "COLLECTED",
  "collected_by": "string",
  "collected_at": 1705323600000,
  "picked_up_by": "string",
  "picked_up_at": 1705327200000,
  "delivered_by": "string",
  "delivered_at": 1705330800000,
  "last_modified": 1705323600000,
  "version": 1
}
```

### New WebSocket message: BOX_SCAN (Client → Server)
```json
{
  "type": "BOX_SCAN",
  "payload": {
    "document_id": "string",
    "barcode": "string",
    "weight": 2500
  }
}
```
Response: `BOX_SCAN_RESULT`
```json
{
  "type": "BOX_SCAN_RESULT",
  "payload": {
    "success": true,
    "box_id": "string",
    "error": "box not found in catalog"
  }
}
```

### Android action required
- New Room entities: `BoxEntity`, `DocumentBoxEntity`
- Room migration
- Add `"boxes"` and `"document_boxes"` to sync entity_types in SYNC_REQUEST
- New UI for box scanning during collection:
  - Scan box barcode → look up in local box catalog
  - Enter weight
  - Send `BOX_SCAN` message
  - Show linked boxes list per document
- Barcode scanner integration for box codes

---

## 6. Courier Flow

### What changed
After collection, documents with boxes enter the courier queue. The courier has two modes:

1. **Pickup mode**: Scan each box barcode to confirm pickup. When all boxes for a document are scanned, document transitions `COLLECTED → DELIVERING`.
2. **Delivery mode**: At destination, scan each box barcode again to confirm delivery. When all boxes confirmed, document transitions `DELIVERING → DELIVERED`.

### New WebSocket messages

#### BOX_PICKUP_CONFIRM (Client → Server, offline-capable)
```json
{
  "type": "BOX_PICKUP_CONFIRM",
  "payload": {
    "barcode": "string",
    "offline_seq": 1,
    "client_ts": 1705323600000
  }
}
```

#### BOX_DELIVERY_CONFIRM (Client → Server, offline-capable)
```json
{
  "type": "BOX_DELIVERY_CONFIRM",
  "payload": {
    "barcode": "string",
    "offline_seq": 2,
    "client_ts": 1705327200000
  }
}
```

#### Response format (both):
```json
{
  "type": "BOX_PICKUP_CONFIRM_RESULT",
  "payload": {
    "success": true,
    "barcode": "string",
    "was_noop": false
  }
}
```
`was_noop: true` means this was a duplicate (already confirmed) — safe for offline replay.

### Offline support
- `offline_seq`: monotonically increasing sequence number assigned by the client
- `client_ts`: client-side timestamp in epoch milliseconds
- On reconnect, replay queued confirmations in `offline_seq` order
- Server handles duplicates idempotently — `was_noop: true` for already-confirmed boxes
- The courier can complete the entire delivery queue while offline

### Android action required
- **Courier pickup screen**: Show list of COLLECTED boxes, scan to confirm pickup
  - Track pickup progress (3/5 boxes picked up)
  - Warn if trying to leave with partial pickup
- **Courier delivery screen**: Show only picked-up boxes, scan to confirm delivery
  - Visual progress indicator
  - Document auto-completes when all boxes confirmed
- **Offline queue**: Store `BOX_PICKUP_CONFIRM` and `BOX_DELIVERY_CONFIRM` in Room when offline
  - Assign `offline_seq` locally
  - Replay on WebSocket reconnect
  - Handle `was_noop` responses gracefully

---

## 7. Sync Changes Summary

### New entity types in SYNC_REQUEST
Add to the `entity_types` array:
- `"boxes"` — box master data catalog (like products)
- `"document_boxes"` — box instances linked to documents

### Updated entity: Document
New fields in `DocumentSyncDto`:
- `assigned_worker_id` (nullable string) — ERP-assigned worker for queue routing
- `courier_user_id` (nullable string) — courier handling delivery
- `collected_at` (nullable int64) — when collection finished
- `delivered_at` (nullable int64) — when delivery confirmed

### Updated entity: User
New field:
- `warehouse_id` (nullable string) — assigned warehouse

---

## 8. Document Field: `assigned_worker_id`

### What changed
ERP can pre-assign a document to a specific worker by setting `assigned_worker_id`. If set, only that worker will receive the document from the queue.

### Android impact
- Display in document detail if assigned
- No action needed from the app — the server handles queue filtering

---

## 9. Implementation Priority

Recommended order for Android implementation:

1. **Role rename + state machine update** — minimal effort, high impact on compatibility
2. **User warehouse_id field** — Room migration, minor UI
3. **Queue-based document flow (COLLECTOR)** — major UX change for collectors
4. **Box scanning (COLLECTOR)** — new entity + UI for box management during collection
5. **Courier pickup/delivery flow** — new screens + offline queue
6. **Boxes/DocumentBoxes sync** — new Room entities and sync handling

Steps 1-2 can be done without changing the UX flow. Steps 3-6 are the new pipeline features.

---

## 10. Backward Compatibility Notes

- Old role names (`WAREHOUSE_WORKER`, `PICKER`) work as aliases — will be removed in a future backend release
- Old state names (`IN_PROGRESS`, `COMPLETED`) work as aliases — will be removed in a future backend release
- Old message types (`DOCUMENT_LOCK`, `DOCUMENT_COMPLETE`) still work — they map to the new pipeline internally
- New message types (`NEXT_DOCUMENT_REQUEST`, `BOX_SCAN`, etc.) are additive — old app versions that don't send them continue to work with the old flow
- The sync protocol is backward compatible — old entity_types still work, new ones (`boxes`, `document_boxes`) are ignored if not requested
