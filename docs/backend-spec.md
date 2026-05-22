# PickApp Backend Specification

This document defines the backend contract for the PickApp Android application.

## Overview

The backend is an intermediate server between the Android TSD devices and an
external ERP system. It is **WebSocket-first**: the app authenticates,
synchronizes data, and runs all document, stage and box operations over a single
WebSocket connection.

- **WebSocket** — authentication, delta/full synchronization, real-time updates,
  document/stage/box operations, diagnostics. This is the only data path used in
  production.
- **Offline-first sync** — delta updates with server-managed cursors and
  conflict resolution.

The detailed wire protocol lives in [websocket-protocol.md](websocket-protocol.md).
This document covers the entity contract, the document state machine, the sync
model, security and the ERP integration boundary.

---

## 1. Authentication

Authentication is performed entirely over the WebSocket connection — there is no
REST authentication path in the production flow.

- **Stage 1 — Device connection:** the device connects with an `app_token`
  (hardcoded in the app build) and a `device_id`. New devices are auto-registered
  PENDING and must be approved via the admin panel.
- **Stage 2 — User login:** after the socket is open, the device sends a
  `USER_LOGIN` message with `login` + `password`. The server replies with
  `USER_LOGIN_RESULT` carrying the user identity, role, tenant, an
  `offline_hash` for offline re-authentication, the per-type capability flags,
  the per-device `debug_journal_enabled` flag, and any `held_stage_locks`.

See [websocket-protocol.md](websocket-protocol.md) §"USER_LOGIN" for payloads.

> **Legacy note.** The codebase still contains a REST auth scaffold
> (`AuthApi`: `POST /auth/login`, `/auth/refresh`, `/auth/logout`, with JWT
> access/refresh tokens). It is **not used** by the production flow and is not
> part of this contract. New work should not depend on it.

---

## 2. Synchronization

All synchronization runs over the WebSocket. See
[websocket-protocol.md](websocket-protocol.md) for message envelopes.

- `SYNC_REQUEST` — delta sync since the client's per-entity cursors.
- `FULL_SYNC_REQUEST` — full resync (after a local failure or inconsistency).
- `SYNC_DATA` — one batch per entity type; carries `full_set`, `data`, and
  `deleted_ids`.
- `SYNC_COMPLETE` — round complete, returns the new cursors.
- `ACK` — client confirms receipt; the server persists the device cursors only
  after the ACK.
- `DOCUMENT_LIST_REFRESH` / `DOCUMENT_PRODUCTS` — targeted refreshes.

Entity types: `users`, `products`, `clients`, `warehouses`, `documents`,
`boxes`.

---

## 3. Entity Data Structures

All reference entities carry an optional `external_id` — the ERP canonical
identifier used in cross-references (e.g. `DocumentLine.product_id`,
`Document.assigned_user_id`) on the v2 wire format.

### User

```json
{
  "id": "string (server _id)",
  "external_id": "string (ERP id, optional)",
  "login": "string (unique per tenant)",
  "name": "string",
  "role": "COLLECTOR | COURIER | ADMINISTRATOR",
  "is_active": true,
  "warehouse_id": "string (optional)",
  "last_updated": 1234567890000
}
```

### Product

```json
{
  "id": "string",
  "external_id": "string (ERP id, optional)",
  "code": "string (unique)",
  "name": "string",
  "description": "string (optional)",
  "unit": "string (e.g., kg, pcs)",
  "supports_batches": false,
  "is_active": true,
  "barcodes": [
    {
      "id": "string (optional)",
      "barcode": "string",
      "type": "EAN13 | EAN8 | CODE128 | CODE39 | QR | DATAMATRIX | GS1_DATAMATRIX | UNKNOWN",
      "is_primary": false
    }
  ],
  "image_url": "string (optional)"
}
```

### Client

```json
{
  "id": "string",
  "external_id": "string (ERP id, optional)",
  "code": "string (unique)",
  "name": "string",
  "address": "string (optional)",
  "phone": "string (optional)",
  "is_active": true
}
```

### Warehouse

```json
{
  "id": "string",
  "external_id": "string (ERP id, optional)",
  "code": "string (unique)",
  "name": "string",
  "is_addressed": false,
  "is_active": true,
  "locations": [
    {
      "id": "string",
      "row": "string",
      "shelf": "string",
      "barcode": "string (optional)",
      "is_active": true
    }
  ]
}
```

### Box (master catalog)

A box type from the ERP catalog. `is_parcel` distinguishes a delivery "place"
(a parcel — weight required when added to a document during PACK) from a
package (which nests inside a parcel).

```json
{
  "id": "string",
  "external_id": "string (ERP id, optional)",
  "barcode": "string",
  "name": "string",
  "length": 0,
  "width": 0,
  "height": 0,
  "is_parcel": false,
  "is_active": true
}
```

### Document

```json
{
  "id": "string",
  "external_id": "string (ERP id, optional)",
  "type": "string (ERP-defined type code)",
  "number": "string",
  "date": 1234567890000,
  "state": "LOADED | COLLECTING | COLLECTED | PACK | PACKING | PACKED | DELIVERY | DELIVERING | DELIVERED | SENT | ERROR",
  "client_id": "string (optional)",
  "client_name": "string (optional)",
  "warehouse_id": "string (optional)",
  "warehouse_name": "string (optional)",
  "notes": "string (optional)",
  "total_planned": 100.0,
  "total_actual": 0.0,
  "assigned_user_id": "string (optional)",
  "assigned_worker_id": "string (optional)",
  "courier_user_id": "string (optional)",
  "taken_at": 1234567890000,
  "completed_at": null,
  "delivered_at": null,
  "last_modified": 1234567890000,
  "version": 1,
  "lines": [
    {
      "id": "string",
      "document_id": "string",
      "line_number": 1,
      "product_id": "string (ERP external_id on v2)",
      "product_code": "string (optional)",
      "product_name": "string (optional)",
      "unit": "string (optional)",
      "planned_quantity": 10.0,
      "actual_quantity": 0.0,
      "batch_number": "string (optional)",
      "expiration_date": 1234567890000,
      "location_id": "string (optional)",
      "location_path": "A/1/2 (optional)",
      "notes": "string (optional)",
      "is_completed": false
    }
  ],
  "boxes": [
    {
      "box_number": 1,
      "box_id": "string (master Box id)",
      "barcode": "string",
      "is_parcel": false,
      "weight": 0,
      "status": "PACKED | PICKED_UP | DELIVERED",
      "packed_by": "string (optional)",
      "packed_at": 1234567890000,
      "picked_up_by": "string (optional)",
      "picked_up_at": 1234567890000,
      "delivered_by": "string (optional)",
      "delivered_at": 1234567890000
    }
  ]
}
```

- `boxes[]` are populated during the PACK / DELIVERY stages. `box_number` is the
  in-document primary key, server-assigned on `BOX_ADD`. `is_parcel` is
  denormalized from the master `Box` at add-time.
- The document `type` is an ERP-defined code. The server reports the set of
  types available to a user, with capability flags, in
  `USER_LOGIN_RESULT.available_document_types` (`allows_over_plan`,
  `allows_extra_lines`, `requires_plan`).

---

## 4. Document State Machine

A document moves through three stages — **collect → pack → deliver** — each with
a start state, an in-process state and a done state, plus two terminal states.

| Stage | Start | In-process | Done |
|-------|-------|------------|------|
| collect | `LOADED` | `COLLECTING` | `COLLECTED` |
| pack | `PACK` | `PACKING` | `PACKED` |
| deliver | `DELIVERY` | `DELIVERING` | `DELIVERED` |

Terminal states: `SENT` (confirmed by ERP), `ERROR` (requires attention).

```
LOADED ──lock──▶ COLLECTING ──complete──▶ COLLECTED ──▶ PACK
                     │ unlock
                     ▼
                  LOADED
PACK ──lock──▶ PACKING ──complete──▶ PACKED ──▶ DELIVERY
DELIVERY ──lock──▶ DELIVERING ──complete──▶ DELIVERED ──▶ SENT
                                            (any stage) ──▶ ERROR
```

- A worker acquires a stage lock (`STAGE_LOCK`) on a start state; the document
  enters the in-process state and the TSD becomes the source of truth.
- `STAGE_COMPLETE` advances to the done state; `STAGE_UNLOCK` reverts to the
  start state; `STAGE_PAUSE` releases the lock while keeping the in-process
  state.
- Transitions between one stage's done state and the next stage's start state
  are server / ERP driven.

See [ownership-model-plan.md](ownership-model-plan.md) for the full ownership
policy (who may write document data in each regime).

---

## 5. Sync Mechanism

### Sync Flow

1. Client sends `SYNC_REQUEST` (with cursors) or `FULL_SYNC_REQUEST`, or a
   targeted `DOCUMENT_LIST_REFRESH`.
2. Server returns one `SYNC_DATA` per entity type, then `SYNC_COMPLETE` with new
   cursors.
3. `SYNC_DATA.full_set` decides purge behavior:
   - `false` (delta) — merge only; local rows outside the payload are kept.
   - `true` (full set) — authoritative; local rows of that entity outside the
     payload are deleted. Worker-owned / locally-dirty documents are exempt.
4. `SYNC_DATA.deleted_ids` carries explicit server-side removals.
5. Client applies changes and sends `ACK`.
6. Server persists the device cursors only after the `ACK`.

### Conflict Resolution

Documents use optimistic locking via the `version` field:

1. The server bumps `version` on every write and uses it for an atomic
   compare-and-set.
2. While a stage lock is held the server sets `erp_sync_blocked = true` and
   refuses ERP writes to that document.
3. A device write that is no longer valid is rejected with a `SERVER_ERROR`
   (`LOCK_LOST` or `WRONG_STATE`, carrying `document_id`); the app runs silent
   re-lock recovery.
4. For documents not locked by the device, server state wins; for worker-owned
   documents, per-line dirty preservation protects the worker's edits.

### Sync Intervals

- **Periodic:** every 15 minutes via a background worker.
- **On-demand:** when network becomes available, on app foreground, and after
  stage operations.
- **Real-time:** WebSocket `PUSH` for immediate updates.

---

## 6. Error Handling

### WebSocket errors

Errors are delivered as `SERVER_ERROR` messages. See
[websocket-protocol.md](websocket-protocol.md) §"SERVER_ERROR" for the full code
list (`NOT_AUTHENTICATED`, `INVALID_PAYLOAD`, `DOCUMENT_LOCKED`, `LOCK_LOST`,
`WRONG_STATE`, `FORBIDDEN`, `INTERNAL_ERROR`, …).

### Connection-upgrade errors

The initial WebSocket upgrade can fail with HTTP status codes:

| Code | Meaning |
|------|---------|
| 101  | Switching Protocols (success) |
| 401  | Unauthorized (invalid/missing app token) |
| 403  | Forbidden (device PENDING/REJECTED, or no tenant) |

After the upgrade, device-status close codes `4003` (PENDING) and `4004`
(REJECTED) stop the client from auto-reconnecting.

---

## 7. Security Requirements

### Authentication

- Two-stage auth: app-token device validation, then per-tenant user login.
- `offline_hash` returned on login enables offline re-authentication for
  previously authenticated users.
- Password storage: SHA-256 hash (for offline fallback on the client).

### Transport

- WSS required for WebSocket connections in production.
- Certificate pinning recommended for production.

### Authorization

- Role-based access control (`COLLECTOR`, `COURIER`, `ADMINISTRATOR`).
- Stage-lock ownership tracking (who is working on which document).
- Audit logging for document and force-release operations.

---

## 8. Database Schema (Reference)

### Core Tables

| Table | Purpose |
|-------|---------|
| `users` | User accounts and roles |
| `products` | Product master data |
| `product_barcodes` | Multiple barcodes per product |
| `product_images` | Product image URLs |
| `clients` | Customers and suppliers |
| `warehouses` | Warehouse definitions |
| `warehouse_locations` | Addressed storage locations |
| `boxes` | Box master catalog (parcels and packages) |
| `documents` | Document headers |
| `document_lines` | Document line items |
| `document_boxes` | Boxes linked to a document during pack/deliver |

### Sync Support Tables

| Table | Purpose |
|-------|---------|
| `sync_state` | Per-device sync cursors |
| `deleted_records` | Soft-delete tracking for delta sync |
| `outgoing_queue` | Pending ERP sync operations |
| `debug_journal_events` | Uploaded device debug-journal events (tenant-scoped) |

---

## 9. ERP Integration Points

The backend acts as middleware between mobile clients and the ERP.

### Inbound (ERP → Backend)

- New documents and document state advances.
- Master data updates (products, clients, warehouses, boxes).

### Outbound (Backend → ERP)

- Completed-stage documents with actual quantities and boxes.
- Document state changes and error notifications.

### Ownership boundary

While a document is worker-owned (`erp_sync_blocked = true`), the backend
refuses ERP writes to it. At stage completion the device's line actuals,
batches, `is_completed` flags and boxes become the new baseline and are
immutable from the ERP side.

```
ERP ◀──REST/SOAP──▶ Backend ◀──WebSocket──▶ Mobile App
         │                         │
    Batch sync               Real-time, offline-first sync
    (scheduled)              (delta + full)
```

---

## 10. Project Status

> [!NOTE]
> The PickApp middleware backend MVP is shipped and running in production. The
> three-stage (collect/pack/deliver) document workflow, box operations and the
> debug journal are implemented. Open ownership-model follow-ups are tracked in
> [ownership-model-plan.md](ownership-model-plan.md).

| Area | Status |
|------|--------|
| Two-stage WebSocket authentication | ✅ Shipped |
| Delta / full synchronization (`SYNC_REQUEST` / `FULL_SYNC_REQUEST`) | ✅ Shipped |
| Three-stage document workflow (`STAGE_LOCK` / `PAUSE` / `COMPLETE` / `UNLOCK`) | ✅ Shipped |
| Box operations (add / remove / lookup, courier pickup / delivery) | ✅ Shipped |
| Optimistic locking + `erp_sync_blocked` ownership guard | ✅ Shipped |
| Cooperative force-release (`FORCE_RELEASE_REQUEST`) | ✅ Shipped |
| Debug journal upload (`DEBUG_EVENT_BATCH`) | ✅ Shipped |
| ERP inbound / outbound integration | ✅ Shipped |
| Ownership-model hardening (PACK/DELIVERY reset, cross-device push, …) | ◻ In progress — see [ownership-model-plan.md](ownership-model-plan.md) |
