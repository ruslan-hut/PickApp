# PickApp Backend Specification

This document defines the backend contract for the PickApp Android application.

## Overview

The backend is an intermediate server between the Android TSD devices and an
external ERP system. The app is a **REST client** of the backend's `/device`
surface (Retrofit 3 + OkHttp 5): it authenticates, synchronizes data, and runs
all document, stage and box operations over plain REST request/response. There
is no persistent connection — the app is no longer WebSocket-first.

Every `/device` response is wrapped in an `ApiEnvelope`:
`{"status":"ok","data":…}` on success, `{"status":"error","error":{code,message}}`
on failure. The protocol envelopes (payload schemas) are unchanged from the
previous wire path — only the transport changed from a persistent WebSocket to
REST.

- **REST/HTTPS** — authentication, delta/full synchronization, document/stage/box
  operations, lookups, debug-journal upload. This is the only data path.
- **Offline-first sync** — delta updates with server-managed cursors and
  conflict resolution.

The detailed wire protocol (per-message payload schemas) lives in
[sync-protocol.md](sync-protocol.md). This document covers the entity contract,
the document state machine, the sync model, security and the ERP integration
boundary.

---

## 1. Authentication

The device authenticates over REST and is issued a JWT pair.

- **`POST device/login`** — a single request that carries both device and user
  credentials: `app_token` (hardcoded in the app build), `device_id`, the user
  `login` + `password`, and `app_version`. New devices are auto-registered
  PENDING and must be approved via the admin panel. On success the response
  carries an **access + refresh JWT pair** plus the `USER_LOGIN_RESULT` payload:
  user identity, role, tenant, an `offline_hash` for offline re-authentication,
  the per-type capability flags (`available_document_types`), the per-device
  `debug_journal_enabled` flag, and any `held_stage_locks`. Where the WMS
  addressing module is on, it also carries `mode: "guided"` on the guided
  entries of `available_document_types`, and an `open_tasks` array — the
  worker's unfinished guided tasks, for the continue / cancel offer. Both are
  absent everywhere else. See [sync-protocol.md](sync-protocol.md)
  §"Guided Tasks".
- **`POST device/refresh`** — exchanges the stored refresh token for a fresh
  access/refresh pair (token rotation).
- **Access-token attachment & retry:** the auth interceptor attaches
  `Authorization: Bearer <access JWT>` to every call except the public
  login/refresh endpoints. A `401` triggers one transparent `device/refresh` +
  retry; if the refresh also fails the failure surfaces to the caller.

The user (worker) auth is the `USER_LOGIN` payload carried over
`POST device/login` — there is no separate user-login endpoint; one request
authenticates both the device and the worker.

See [sync-protocol.md](sync-protocol.md) §"USER_LOGIN" for payloads.

---

## 2. Synchronization

All synchronization runs over REST. See [sync-protocol.md](sync-protocol.md) for
message envelopes.

- **`POST device/sync`** — delta pull since the client's per-entity cursors
  (`SYNC_REQUEST`). `full=1` query resets the cursors first (`FULL_SYNC_REQUEST`
  / full resync after a local failure or inconsistency). The response carries one
  entity batch per type (each with `full_set`, `data`, `deleted_ids`), the new
  `next_cursors`, and `has_more`; the client loops while `has_more` is true.
- **`GET device/documents`** — the role-filtered authoritative complete document
  set (the `DOCUMENT_LIST_REFRESH` equivalent), with an optional `document_type`
  filter.
- **`GET device/documents/{id}/products`** — targeted product refresh for one
  document (`DOCUMENT_PRODUCTS`).

The server persists the device cursors as part of the sync response; there is no
separate client ACK round-trip.

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

`collect_mode` is `"guided"` on a LOADED / COLLECTING document whose warehouse
works it as a guided WMS task; the device then opens it as a task instead of
locking it classically. It is recomputed on every list load, so the tenant's
emergency switch takes effect on the next refresh. `line_key` is the ERP's own
stable line id when it supplies one — opaque to the device, used by guided
`line_updates` to address a line; classic flows key on `line_number`. Both are
absent outside the addressing module.

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
  "collect_mode": "guided (optional)",
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
      "line_key": "string (ERP's stable line id, optional)",
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

1. Client calls `POST device/sync` (with cursors), `POST device/sync?full=1`, or
   the targeted `GET device/documents`.
2. The response returns one entity batch per type plus the new `next_cursors`,
   and `has_more`. While `has_more` is true the client repeats `device/sync`
   with the returned cursors.
3. Each entity batch's `full_set` decides purge behavior:
   - `false` (delta) — merge only; local rows outside the payload are kept.
   - `true` (full set) — authoritative; local rows of that entity outside the
     payload are deleted. Worker-owned / locally-dirty documents are exempt.
4. The batch `deleted_ids` carries explicit server-side removals.
5. Client applies changes. The server persists the device cursors as part of
   serving the response.

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
- **While a document is being worked:** there is no server push. The device
  polls (`POST device/sync` + `GET device/documents`) to pick up server-side
  changes while a document is open.

---

## 6. Error Handling

### Application errors (in-envelope)

A failed request returns the error envelope shape
`{"status":"error","error":{code,message}}`. The `SERVER_ERROR` code semantics
are unchanged — see [sync-protocol.md](sync-protocol.md) §"SERVER_ERROR" for the
full code list (`NOT_AUTHENTICATED`, `INVALID_PAYLOAD`, `DOCUMENT_LOCKED`,
`LOCK_LOST`, `WRONG_STATE`, `FORBIDDEN`, `INTERNAL_ERROR`, …). The document
write-path codes `LOCK_LOST` / `WRONG_STATE` drive the app's lock-loss recovery.

### HTTP status codes

| Code | Meaning |
|------|---------|
| 401  | Unauthorized — triggers one transparent `device/refresh` + retry |
| 403  | Forbidden (device PENDING/REJECTED, no tenant, or RBAC denial) |
| 409  | Conflict — optimistic-lock / `version` compare-and-set failure |
| 5xx  | Server error |

On a non-2xx response the client parses the error envelope from the body to
recover the backend `code`; a 401 is retried once after a refresh.

---

## 7. Security Requirements

### Authentication

- Device-JWT auth: `POST device/login` validates the app token + device and the
  per-tenant user credentials in one request, issuing an access + refresh JWT
  pair; `POST device/refresh` rotates the pair.
- `offline_hash` returned on login enables offline re-authentication for
  previously authenticated users.
- Password storage: SHA-256 hash (for offline fallback on the client).

### Transport

- HTTPS required in production.
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
ERP ◀──REST/SOAP──▶ Backend ◀──REST──▶ Mobile App
         │                       │
    Batch sync             Polled, offline-first sync
    (scheduled)            (delta + full)
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
| REST device-JWT auth (`device/login` / `device/refresh`) + `USER_LOGIN` payload | ✅ Shipped |
| Delta / full synchronization (`SYNC_REQUEST` / `FULL_SYNC_REQUEST`) | ✅ Shipped |
| Three-stage document workflow (`STAGE_LOCK` / `PAUSE` / `COMPLETE` / `UNLOCK`) | ✅ Shipped |
| Box operations (add / remove / lookup, courier pickup / delivery) | ✅ Shipped |
| Optimistic locking + `erp_sync_blocked` ownership guard | ✅ Shipped |
| Cooperative force-release (`FORCE_RELEASE_REQUEST`) | ✅ Shipped |
| Debug journal upload (`DEBUG_EVENT_BATCH`) | ✅ Shipped |
| Guided WMS tasks (`device/tasks*`, `collect_mode`, `line_key`, `open_tasks`) | ✅ Shipped — see [wms-guided-tasks-plan.md](wms-guided-tasks-plan.md) |
| ERP inbound / outbound integration | ✅ Shipped |
| Ownership-model hardening (PACK/DELIVERY reset, cross-device push, …) | ◻ In progress — see [ownership-model-plan.md](ownership-model-plan.md) |
