# TSD Sync Protocol

## Overview

This document is the authoritative reference for the sync protocol between
Android TSD devices and the intermediate server. The app speaks **REST only**
(Retrofit / OkHttp) to the backend `/device` surface — authentication,
synchronization, document/stage operations, box operations and diagnostics all
run over plain HTTP requests (see [backend-spec.md](backend-spec.md) §1). There
is no WebSocket / push channel.

Every REST response is wrapped in an `ApiEnvelope`:

```json
{ "status": "ok", "data": { ... } }
```

The message-envelope schemas in this document are the **canonical payload
shapes**. `RestTransport` maps each REST call to and from these `SyncMessage`
types — emitting the correlated result on its `incomingMessages` flow exactly as
the legacy push path delivered server frames — so `SyncOrchestrator` stays
transport-agnostic. The schemas below therefore describe *payloads*; the
transport framing around each is "request → REST endpoint, result emitted as the
correlated message".

The protocol supports synchronization (poll-based), a three-stage document
workflow (collect → pack → deliver), and offline-first patterns.

### Authentication

Two independent layers, both over HTTP:

- **Device auth = JWT.** `POST device/login` returns an access + refresh JWT
  pair; `AuthInterceptor` attaches the access token as a bearer on every
  subsequent `/device` call. `POST device/refresh` exchanges the refresh token
  for a new pair when the access token expires. The old two-stage socket
  handshake (`app_token` + `device_id` query params) is gone.
- **User auth = `USER_LOGIN` payload.** `POST device/login` also carries the
  user `login` / `password` and returns the `USER_LOGIN_RESULT` payload (user
  id, role, capability flags, held stage locks). See the
  [USER_LOGIN](#user_login) section.

`device/login` and `device/refresh` are the only public endpoints; every other
`/device` call requires the device access JWT.

---

## Message Format

All payloads share a common envelope structure (preserved verbatim by
`RestTransport` when it synthesizes result messages onto its message flow):

```json
{
  "id": "1704067200123456789",
  "type": "MESSAGE_TYPE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { ... }
}
```

| Field | Type | Description |
|-------|------|-------------|
| id | string | Unique message identifier (nanosecond timestamp) |
| type | string | Message type (see types below) |
| timestamp | string | ISO 8601 timestamp (UTC) |
| payload | object | Type-specific payload |

Each request maps to one REST endpoint; the result is emitted as the correlated
message. Correlation is by message `type` plus, for document-scoped operations,
the `document_id` in the payload. Only one in-flight request per correlation key
is assumed.

---

## Message Types

### Request → result

Each request `SyncMessage` is mapped by `RestTransport` to the REST endpoint
below; the response is parsed and emitted as the listed result message.

| Request | REST endpoint | Result message |
|---------|---------------|----------------|
| `USER_LOGIN` | `POST device/login` (also returns device JWT pair) | `USER_LOGIN_RESULT` |
| — token refresh | `POST device/refresh` | (JWT pair, no message) |
| `SYNC_REQUEST` | `POST device/sync` (loop while `has_more`) | `SYNC_DATA` × N + `SYNC_COMPLETE` |
| `FULL_SYNC_REQUEST` | `POST device/sync?full=1` (reset cursors, then loop) | `SYNC_DATA` × N + `SYNC_COMPLETE` |
| `ACK` | — (cursor commit rides `applied_cursors` on the next sync) | none |
| `DOCUMENT_LIST_REFRESH` | `GET device/documents?document_type=…` | `SYNC_DATA` × N + `SYNC_COMPLETE` |
| `DOCUMENT_PRODUCTS` | `GET device/documents/{id}/products` | `SYNC_DATA` + `SYNC_COMPLETE` |
| `DOCUMENT_UPDATE` | `PATCH device/documents/{id}` | `DOCUMENT_UPDATE_RESULT` (+ `SERVER_ERROR` on `LOCK_LOST`/`WRONG_STATE`) |
| `STAGE_LOCK` | `POST device/documents/{id}/lock` | `STAGE_LOCK_RESULT` |
| `STAGE_UNLOCK` | `POST device/documents/{id}/unlock` | `STAGE_LOCK_RESULT` |
| `STAGE_PAUSE` | `POST device/documents/{id}/pause` | `STAGE_LOCK_RESULT` |
| `STAGE_COMPLETE` | `POST device/documents/{id}/complete` | `STAGE_COMPLETE_RESULT` |
| `PRODUCT_LOOKUP` | `GET device/products/lookup?barcode=…` | `PRODUCT_LOOKUP_RESULT` |
| `BOX_ADD` | `POST device/documents/{id}/boxes` | `BOX_ADD_RESULT` |
| `BOX_REMOVE` | `DELETE device/documents/{id}/boxes/{boxNumber}` | `BOX_REMOVE_RESULT` |
| `BOX_LOOKUP` | `GET device/boxes/lookup?barcode=…` | `BOX_LOOKUP_RESULT` |
| `BOX_PICKUP_CONFIRM` | `POST device/boxes/pickup` | `BOX_PICKUP_CONFIRM_RESULT` |
| `BOX_DELIVERY_CONFIRM` | `POST device/boxes/delivery` | `BOX_DELIVERY_CONFIRM_RESULT` |
| `LINE_PHOTO_UPLOAD_URL` | `POST device/documents/{id}/lines/{lineNumber}/photo-url` | `LINE_PHOTO_UPLOAD_URL_RESULT` |
| (shipment label) | `GET device/documents/{id}/shipment/label` | shipment label result |
| (shipment track) | `POST device/documents/{id}/shipment/track` | shipment track result |
| `TASK_START` | `POST device/tasks` | `TASK_RESULT` |
| `TASK_GET` | `GET device/tasks/{id}` | `TASK_RESULT` |
| `TASK_OPEN` | `GET device/tasks/open` | `TASK_OPEN_RESULT` |
| `TASK_ACTION` | `POST device/tasks/{id}/actions` | `TASK_RESULT` |
| `TASK_CANCEL` | `POST device/tasks/{id}/cancel` | `TASK_RESULT` |
| `ERROR_REPORT` | `POST device/error-report` | none (fire-and-forget) |
| `DEBUG_EVENT_BATCH` | `POST device/debug-events` | `DEBUG_EVENT_BATCH_RESULT` |

> There is no `STAGE_UNLOCK_RESULT` or `STAGE_PAUSE_RESULT`. Both unlock and
> pause map to endpoints that return a `STAGE_LOCK_RESULT` payload.

### Server-initiated

`FORCE_RELEASE_REQUEST` is an admin-initiated cooperative force-release. With no
push channel it is surfaced by the server in a sync/poll response rather than
delivered live; the orchestrator handles it identically once observed.

---

## Protocol Messages

### USER_LOGIN

**Endpoint:** `POST device/login` — also returns the device access + refresh JWT
pair (consumed by `AuthInterceptor`); the body below is the `USER_LOGIN_RESULT`
payload.

Authenticate the user.

**Request payload:**
```json
{
  "id": "123",
  "type": "USER_LOGIN",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "login": "worker1",
    "password": "password123"
  }
}
```

**Result (success):**
```json
{
  "id": "124",
  "type": "USER_LOGIN_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": true,
    "user_id": "65a1b2c3d4e5f6a7b8c9d0e5",
    "user_external_id": "EMP-0042",
    "user_name": "Worker One",
    "role": "COLLECTOR",
    "offline_hash": "abc123def456...",
    "tenant_id": "tenant-abc",
    "available_document_types": [
      {
        "code": "INCOMING_RECEIPT",
        "description": "Goods receipt",
        "allows_over_plan": true,
        "allows_extra_lines": false,
        "requires_plan": true
      }
    ],
    "debug_journal_enabled": false,
    "held_stage_locks": ["DOC-1001", "DOC-1007"]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| success | bool | Authentication result |
| user_id | string | Server `_id` of the user |
| user_external_id | string? | ERP `external_id` of the worker; lets the app populate `UserEntity.externalId` before the users sync runs |
| user_name | string | Display name |
| role | string | `COLLECTOR` / `COURIER` / `ADMINISTRATOR` |
| offline_hash | string? | Stored locally for offline authentication |
| tenant_id | string? | Tenant the session belongs to |
| available_document_types | array? | Per-type capability flags (see below) |
| debug_journal_enabled | bool? | When `true`, the device records and uploads debug-journal events. Looked up server-side per `(tenant_id, device_id)`. Absent on older server builds. |
| held_stage_locks | string[]? | ERP `external_id`s of documents this `(user, device)` pair already holds an in-process stage lock for. The app rebuilds its `heldStageLocks` set from this on login, closing the post-restart race where inbound `SYNC_DATA` could overwrite worker-owned line data. Null/empty on a fresh login. |
| supports_update_ack | bool | When `true` the backend confirms each `DOCUMENT_UPDATE` with a `DOCUMENT_UPDATE_RESULT`, so the orchestrator clears `is_dirty` only on ack. `RestTransport` always sets this `true` — every REST write returns a 2xx body it surfaces as a result — so an updated client never waits on an ack that never arrives. |

`available_document_types[]` entries carry `code`, `description`, and three
optional capability flags. Each flag is nullable — `null` means the ERP did not
specify it and the client applies its own default:

| Flag | Default | Effect |
|------|---------|--------|
| `allows_over_plan` | `false` | Permit `actual_quantity > planned_quantity` |
| `allows_extra_lines` | `false` | Scanning an unknown product creates a new line |
| `requires_plan` | `true` | Lines carry a plan; UI shows plan labels and progress |

**Result (failure):**
```json
{
  "id": "124",
  "type": "USER_LOGIN_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": false,
    "error_message": "invalid credentials"
  }
}
```

---

### SYNC_REQUEST

**Endpoint:** `POST device/sync` — the device loops, re-issuing the call while
the response carries `has_more`, applying `next_cursors` each iteration.
`RestTransport` fans each page into synthetic `SYNC_DATA` batches and closes the
round with a `SYNC_COMPLETE`.

Request delta updates since the last synchronization.

**Request payload:**
```json
{
  "id": "123",
  "type": "SYNC_REQUEST",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "entity_types": ["users", "products", "clients", "warehouses", "documents", "boxes"],
    "cursors": {
      "products": "2024-01-01T10:00:00Z",
      "documents": "2024-01-01T11:00:00Z"
    }
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| entity_types | string[] | Any of: `users`, `products`, `clients`, `warehouses`, `documents`, `boxes` |
| cursors | object? | Last sync timestamp (ISO 8601) per entity type. Omit / empty for an initial sync. |

Each `device/sync` page yields one `SYNC_DATA` per entity type; the final page
(`has_more=false`) is followed by a synthetic `SYNC_COMPLETE`.

---

### FULL_SYNC_REQUEST

**Endpoint:** `POST device/sync?full=1` — `full=1` resets the server-side
cursors first, then the device loops over `device/sync` (without `full`) while
`has_more`, exactly as the delta path.

Request a complete resync (used after a local failure or data inconsistency).

**Request payload:**
```json
{
  "id": "123",
  "type": "FULL_SYNC_REQUEST",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "entity_types": ["users", "products", "clients", "warehouses", "documents", "boxes"] }
}
```

The server sends full datasets (`full_set: true`) instead of deltas.

---

### SYNC_DATA

A batch of entity records for one entity type. `RestTransport` emits one per
entity type carried by a `device/sync` / `device/documents` /
`device/documents/{id}/products` response, before `SYNC_COMPLETE`.

**Result payload:**
```json
{
  "id": "200",
  "type": "SYNC_DATA",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "entity_type": "documents",
    "full_set": true,
    "data": [ ... ],
    "deleted_ids": ["DOC-0900"]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| entity_type | string | The entity type this batch carries |
| data | array | Entity records (see [backend-spec.md](backend-spec.md) §3) |
| deleted_ids | string[]? | IDs explicitly removed server-side |
| full_set | bool | See below. Absent = `false`. |

`full_set` semantics:
- `false` (delta sync) — **merge only**. Local rows outside this payload must
  **not** be purged.
- `true` (full list refresh) — **authoritative set**. Local rows of this entity
  type outside the payload must be deleted so the UI reflects server-side
  removals.

For documents, locally-dirty / worker-owned rows are protected from purge even
when `full_set` is `true`.

---

### SYNC_COMPLETE

Marks a sync round finished, carrying the new cursors. Synthesized by
`RestTransport` once the poll loop drains (`has_more=false`).

**Result payload:**
```json
{
  "id": "124",
  "type": "SYNC_COMPLETE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "sync_id": "sync-abc123",
    "cursors": {
      "users": "2024-01-01T12:00:00Z",
      "products": "2024-01-01T12:00:00Z",
      "clients": "2024-01-01T12:00:00Z",
      "warehouses": "2024-01-01T12:00:00Z",
      "documents": "2024-01-01T12:00:00Z",
      "boxes": "2024-01-01T12:00:00Z"
    }
  }
}
```

---

### ACK

**Endpoint:** none. Over REST the cursor commit rides the `applied_cursors`
field of the *next* `device/sync` request, so `RestTransport` treats an `ACK`
message as a no-op. The schema is retained because `SyncOrchestrator` still
produces it transport-agnostically.

Acknowledge successful receipt of sync data.

**Payload:**
```json
{
  "id": "125",
  "type": "ACK",
  "timestamp": "2024-01-01T12:00:01Z",
  "payload": {
    "sync_id": "sync-abc123",
    "cursors": { "products": "2024-01-01T12:00:00Z", "documents": "2024-01-01T12:00:00Z" }
  }
}
```

---

### DOCUMENT_LIST_REFRESH

**Endpoint:** `GET device/documents?document_type=…` — returns the role-filtered
authoritative complete set of documents together with the warehouses and clients
they reference. `RestTransport` emits the entities as `SYNC_DATA` batches and a
`SYNC_COMPLETE`.

**Request payload:**
```json
{
  "id": "126",
  "type": "DOCUMENT_LIST_REFRESH",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_type": "INCOMING_RECEIPT" }
}
```

`document_type` is optional — omit it to refresh all types.

---

### DOCUMENT_PRODUCTS

**Endpoint:** `GET device/documents/{id}/products`

Request the products referenced by the lines of a single document (used when a
document is opened and some products are missing locally).

**Request payload:**
```json
{
  "id": "127",
  "type": "DOCUMENT_PRODUCTS",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001" }
}
```

The response yields a `SYNC_DATA` (`entity_type: "products"`) batch and a
`SYNC_COMPLETE`.

---

### STAGE_LOCK

**Endpoint:** `POST device/documents/{id}/lock`

Lock a document for a stage ("Take into work", or resume after a pause). A stage
lock can be acquired on a stage **start state** (`LOADED`, `PACK`, `DELIVERY`)
or on the matching in-process state when resuming.

**Request payload:**
```json
{
  "id": "128",
  "type": "STAGE_LOCK",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "stage": "collect" }
}
```

| Field | Type | Description |
|-------|------|-------------|
| document_id | string | ERP `external_id` of the document |
| stage | string | `collect` / `pack` / `deliver` |

**Result (success):**
```json
{
  "id": "129",
  "type": "STAGE_LOCK_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "DOC-1001",
    "stage": "collect",
    "success": true,
    "locked_by": "65a1b2c3d4e5f6a7b8c9d0e2"
  }
}
```

**Lock failure:**
```json
{
  "id": "129",
  "type": "STAGE_LOCK_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "DOC-1001",
    "stage": "collect",
    "success": false,
    "locked_by": "65a1b2c3d4e5f6a7b8c9d0e3",
    "error": "document is already locked"
  }
}
```

On a successful lock the server sets `erp_sync_blocked = true` on the document:
while the lock is held, ERP writes to the document are refused and the device is
the source of truth.

---

### STAGE_UNLOCK

**Endpoint:** `POST device/documents/{id}/unlock` — returns a `STAGE_LOCK_RESULT`
payload.

Release a stage lock and revert the document to its stage start state. Drops the
worker's in-process ownership.

**Request payload:**
```json
{
  "id": "130",
  "type": "STAGE_UNLOCK",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "stage": "collect" }
}
```

---

### STAGE_PAUSE

**Endpoint:** `POST device/documents/{id}/pause` — returns a `STAGE_LOCK_RESULT`
payload.

Pause work on a stage: release the lock **without** reverting the document
state. The document stays in its in-process state (`COLLECTING` / `PACKING` /
`DELIVERING`) in the worker's queue and remains worker-owned
(`erp_sync_blocked` stays `true`).

The active segment elapsed since the lock was taken is added to the document's
`active_work_ms` by the server, so the paused interval is excluded from the
worker's effective work time. Resume with a regular `STAGE_LOCK` on the same
document.

**Client sends:**
```json
{
  "id": "131",
  "type": "STAGE_PAUSE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "stage": "collect" }
}
```

The endpoint returns a `STAGE_LOCK_RESULT` payload.

---

### STAGE_COMPLETE

**Endpoint:** `POST device/documents/{id}/complete`

Complete the current stage. The document must be locked by the current user and
in the matching in-process state.

**Request payload:**
```json
{
  "id": "132",
  "type": "STAGE_COMPLETE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "stage": "collect" }
}
```

**Result (success):**
```json
{
  "id": "133",
  "type": "STAGE_COMPLETE_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "DOC-1001",
    "stage": "collect",
    "success": true,
    "state": "COLLECTED",
    "completed_at": "2024-01-01T12:00:00Z",
    "version": 5
  }
}
```

**Result (error):**
```json
{
  "id": "133",
  "type": "STAGE_COMPLETE_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "DOC-1001",
    "stage": "collect",
    "success": false,
    "error": "document must be locked by the current user"
  }
}
```

At stage completion the server treats the device's data as the new baseline:
ERP may keep editing header fields, but per-line `actual_quantity`,
`batch_number`, `is_completed` and `Document.Boxes` become immutable from the
ERP side.

---

### DOCUMENT_UPDATE

**Endpoint:** `PATCH device/documents/{id}` — returns a `DOCUMENT_UPDATE_RESULT`
payload (`success`, `document_id`, `request_id`, `version`, `error_code`). The
`request_id` echoes the originating update's `id` so concurrent updates on the
same document can be disambiguated.

Push document line updates while a stage lock is held. Sent debounced from the
device, and flushed before `STAGE_LOCK` / `STAGE_COMPLETE` / `STAGE_UNLOCK`
(not before `STAGE_PAUSE`).

**Request payload:**
```json
{
  "id": "134",
  "type": "DOCUMENT_UPDATE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "DOC-1001",
    "state": "COLLECTING",
    "lines": [
      { "line_number": 1, "actual_quantity": 10.5, "batch_number": "BATCH001", "is_completed": true },
      { "line_number": 2, "actual_quantity": 5, "is_completed": true }
    ]
  }
}
```

Each line may also carry `notes` (worker-owned line note, sent full-state: an
omitted/empty note clears the server's copy).

Each line may also carry `batches` — the part of `actual_quantity` collected by
scanning a **batch label**, per batch: `[{ "batch_id": "…", "qty": 4 }]`. The
ERP books that part to the scanned batch and assigns batches for the rest
(product scans) itself by expiry. It is full-state like the quantity; the app
sends it only when it holds a breakdown for the line (Room `batches` column not
null) and omits it otherwise, so the server keeps what a guided task wrote
there. Both sides fit it to the quantity by the same rule
(`LineBatch.normalizedTo` / `entity.NormalizeLineBatches`): repeats merged, the
latest entries trimmed when the quantity goes down. On sync ingest a line with
no local breakdown takes the server's.

`actual_quantity` is **absolute** (the post-increment total), not a delta — so
retries are idempotent. On success the endpoint returns a
`DOCUMENT_UPDATE_RESULT` (the orchestrator clears `is_dirty` for the confirmed
lines and adopts `version`); on a `LOCK_LOST` / `WRONG_STATE` rejection
`RestTransport` additionally emits a `SERVER_ERROR` (see below) carrying the
`document_id` so lock-loss recovery can run for that document, plus a failed
`DOCUMENT_UPDATE_RESULT` so the awaiter fails fast.

---

### PRODUCT_LOOKUP

**Endpoint:** `GET device/products/lookup?barcode=…`

Search for a product by barcode.

**Request payload:**
```json
{
  "id": "135",
  "type": "PRODUCT_LOOKUP",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "barcode": "4600000000001" }
}
```

**Result (found):**
```json
{
  "id": "136",
  "type": "PRODUCT_LOOKUP_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": true,
    "product": { ... ProductDto ... }
  }
}
```

A **batch label** (WMS module on, batch known from the ERP) resolves to the
batch's product plus the batch:

```json
{ "success": true, "product": { ... ProductDto ... },
  "batch": { "id": "76cf5c4d-…", "number": "ПН-000123", "expiry_date": 1830211200000 } }
```

`SyncOrchestrator.lookupProduct` stores the product (never the label as its
barcode — a later local hit would lose the batch), caches the label → batch hit
for the session, and the document screen adds the unit to the product's line
and to its `batches` entry.

**Result (not found):**
```json
{
  "id": "136",
  "type": "PRODUCT_LOOKUP_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "success": false, "error": "product not found" }
}
```

---

### BOX_ADD

**Endpoint:** `POST device/documents/{id}/boxes`

Worker scans a box barcode during the PACK stage to link it to a document.
Parcel boxes require `weight > 0`; for packages the server forces `weight = 0`.

**Request payload:**
```json
{
  "id": "137",
  "type": "BOX_ADD",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "barcode": "BX-7788", "weight": 1200 }
}
```

**Result:**
```json
{
  "id": "138",
  "type": "BOX_ADD_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": true,
    "box": { ... DocumentBox DTO ... }
  }
}
```

On success `box` carries the full `DocumentBox` DTO (including the
server-assigned `box_number`) so the client can render it without a sync
round-trip. On failure `error` is set.

---

### BOX_REMOVE

**Endpoint:** `DELETE device/documents/{id}/boxes/{boxNumber}`

Remove a previously-added box while the document is still in `PACKING`.
`box_number` is the in-document sequence assigned by the server on `BOX_ADD`,
sent as the `{boxNumber}` path segment.

**Request payload:**
```json
{
  "id": "139",
  "type": "BOX_REMOVE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "box_number": 3 }
}
```

**Result:**
```json
{
  "id": "140",
  "type": "BOX_REMOVE_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "success": true, "box_number": 3 }
}
```

---

### BOX_LOOKUP

**Endpoint:** `GET device/boxes/lookup?barcode=…`

Client fallback when a scanned box barcode misses the local box catalog. The
server resolves it against the master catalog and, on a hit, returns the full
`Box` DTO so the client can cache it and continue the add flow.

**Request payload:**
```json
{
  "id": "141",
  "type": "BOX_LOOKUP",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "barcode": "BX-7788" }
}
```

**Result:**
```json
{
  "id": "142",
  "type": "BOX_LOOKUP_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "success": true, "box": { ... Box DTO ... } }
}
```

---

### BOX_PICKUP_CONFIRM / BOX_DELIVERY_CONFIRM

**Endpoints:** `POST device/boxes/pickup` / `POST device/boxes/delivery`

Courier confirms box pickup / delivery. Both are offline-capable: the device
assigns an `offline_seq` and a `client_ts`, queues the call when the REST request
fails, and replays queued confirmations once connectivity returns.

**Request payload:**
```json
{
  "id": "143",
  "type": "BOX_PICKUP_CONFIRM",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "barcode": "BX-7788", "offline_seq": 12, "client_ts": 1712000000000 }
}
```

`BOX_DELIVERY_CONFIRM` has the identical payload shape.

**Result:**
```json
{
  "id": "144",
  "type": "BOX_PICKUP_CONFIRM_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "success": true, "barcode": "BX-7788", "was_noop": false }
}
```

`was_noop: true` means the confirmation had already been applied (idempotent
replay) — not an error.

---

### FORCE_RELEASE_REQUEST

Server-initiated request to release the worker's stage lock cooperatively.
Triggered by an admin clicking "Force release" in the tenant UI. With no push
channel, the server surfaces this in a sync/poll response (the device polls
`device/sync` while a document is held); the orchestrator handles it the same
way once observed.

**Payload shape:**
```json
{
  "id": "145",
  "type": "FORCE_RELEASE_REQUEST",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001" }
}
```

On observing it the device runs the equivalent of "exit without saving":
1. Cancels pending debounced sync for the document.
2. Drops locally-dirty edits (zeroes actuals / `is_completed` / batch).
3. Sends `STAGE_UNLOCK` so the server completes the cooperative flow.
4. Surfaces a "lock lost" banner and navigates back from any open detail screen.

The admin's HTTP request completes when the device's `STAGE_UNLOCK` lands
within ~10s; otherwise the admin side falls back to a hard release.

---

### ERROR_REPORT

**Endpoint:** `POST device/error-report` — fire-and-forget, no result message.

Report client-side errors for diagnostics.

**Request payload:**
```json
{
  "id": "146",
  "type": "ERROR_REPORT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "error_type": "SYNC_FAILURE",
    "message": "Failed to save products to local database",
    "stack_trace": "...",
    "metadata": { "products_count": "150", "db_error": "SQLITE_FULL" }
  }
}
```

---

### SERVER_ERROR

Server error result. A failing REST call (non-`ok` `ApiEnvelope` / non-2xx) is
surfaced by `RestTransport` as this message; for write-path rejections
(`LOCK_LOST` / `WRONG_STATE`) the error code is carried through from the response
body.

**Payload shape:**
```json
{
  "id": "147",
  "type": "SERVER_ERROR",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "code": "LOCK_LOST",
    "message": "Document lock no longer held",
    "details": "User ID: 65a1b2c3d4e5f6a7b8c9d0e3",
    "document_id": "DOC-1001"
  }
}
```

`document_id` is populated for write-path rejections (`LOCK_LOST`,
`WRONG_STATE`) so the orchestrator can drive lock-loss recovery for the specific
document without parsing the human-readable message. It is empty for
connection-level errors.

Common error codes:

| Code | Meaning |
|------|---------|
| `INVALID_MESSAGE` | Malformed message |
| `UNKNOWN_MESSAGE_TYPE` | Unsupported message type |
| `INVALID_PAYLOAD` | Invalid payload format |
| `NOT_AUTHENTICATED` | User login required for this operation |
| `SYNC_ERROR` | Synchronization failed |
| `DOCUMENT_LOCKED` | Document is locked by another user |
| `LOCK_LOST` | The write was rejected — this device no longer holds the stage lock |
| `WRONG_STATE` | The write was rejected — the document moved out of its in-process state |
| `FORBIDDEN` | Permission denied |
| `INTERNAL_ERROR` | Server error |

`LOCK_LOST` / `WRONG_STATE` on a `DOCUMENT_UPDATE` trigger the app's silent
re-lock recovery: the orchestrator re-issues `STAGE_LOCK` up to 3× over 10s; on
success it drains the dirty edits, on give-up it drops them, journals at
`ERROR`, refreshes from the server and shows a banner.

---

### Server-initiated updates (no push)

REST has **no server push**. There is no live `PUSH` message. Server-initiated
updates — a document changed, locked, or unlocked on another device or via ERP
sync — reach the client only by **active polling**: while a document is being
worked, `SyncOrchestrator` repeatedly pulls `device/sync` and
`device/documents` (`SyncTransport.requiresPolling = true`). Each poll response
is the authoritative complete set and carries `deleted_ids`, so the client
converges on the server state without any pushed frame.

---

### DEBUG_EVENT_BATCH

**Endpoint:** `POST device/debug-events`

Upload a batch of per-document debug-journal events from the device.
Best-effort, fire-and-retry. Enabled per device via `debug_journal_enabled`
(see [USER_LOGIN_RESULT](#user_login) and the Debug Journal section).

**Request payload:**
```json
{
  "id": "<nanosec>",
  "type": "DEBUG_EVENT_BATCH",
  "timestamp": "2026-04-14T12:34:56Z",
  "payload": {
    "tenant_id": "tenant-abc",
    "device_id": "device-uuid",
    "events": [
      {
        "id": "uuid",
        "user_id": "user-123",
        "document_id": "DOC-456",
        "stage": "collect",
        "event_type": "DOC_UPDATE_SENT",
        "severity": "INFO",
        "message": "document update sent",
        "payload_json": "{\"line_count\":5,\"total_actual_qty\":12.0}",
        "created_at": 1712000000000
      }
    ]
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| tenant_id | string | Must match the authenticated session tenant |
| device_id | string | Must match the connected device |
| events[] | array | Up to 200 events per batch |
| events[].id | uuid | Client-generated; idempotent on server |
| events[].event_type | string | See the Debug Journal section for the catalog |
| events[].severity | string | `INFO` / `WARN` / `ERROR` |
| events[].payload_json | string? | Opaque structured blob (stringified JSON) |
| events[].created_at | long | Device epoch ms |

**Result:**
```json
{
  "id": "<same-as-request>",
  "type": "DEBUG_EVENT_BATCH_RESULT",
  "timestamp": "2026-04-14T12:34:56Z",
  "payload": { "success": true, "accepted_ids": ["uuid1", "uuid2"], "error": null }
}
```

- `success=true`, full `accepted_ids` → client marks all events uploaded.
- `success=true`, subset → client marks only those; bumps attempts on the rest.
- `success=false` → client bumps attempts on the whole batch and retries later.

Only one `DEBUG_EVENT_BATCH` is ever in flight from a given client; the uploader
is mutex-guarded, so correlation by message type alone is safe.

---

## Document Stage & State Machine

A document moves through three stages — **collect → pack → deliver** — each with
a start state, an in-process state, and a done state, plus two terminal states.

| Stage | Start state | In-process state | Done state |
|-------|-------------|------------------|------------|
| collect | `LOADED` | `COLLECTING` | `COLLECTED` |
| pack | `PACK` | `PACKING` | `PACKED` |
| deliver | `DELIVERY` | `DELIVERING` | `DELIVERED` |
| — | terminal: `SENT` | terminal: `ERROR` | |

```
LOADED ──STAGE_LOCK(collect)──▶ COLLECTING ──STAGE_COMPLETE──▶ COLLECTED
                                    │ STAGE_UNLOCK
                                    ▼
                                 LOADED
   COLLECTED ──(server/ERP)──▶ PACK
PACK ──STAGE_LOCK(pack)──▶ PACKING ──STAGE_COMPLETE──▶ PACKED
   PACKED ──(server/ERP)──▶ DELIVERY
DELIVERY ──STAGE_LOCK(deliver)──▶ DELIVERING ──STAGE_COMPLETE──▶ DELIVERED
   DELIVERED ──▶ SENT
                          (any stage) ──▶ ERROR
```

- `STAGE_LOCK` on a start state → in-process state.
- `STAGE_COMPLETE` on an in-process state → done state.
- `STAGE_UNLOCK` reverts an in-process state back to its start state.
- `STAGE_PAUSE` releases the lock but keeps the in-process state.
- Transitions from one stage's done state to the next stage's start state are
  server / ERP driven.

Ownership regimes:

| Regime | States | Source of truth | ERP writes |
|--------|--------|-----------------|------------|
| ERP-owned (idle) | LOADED, PACK, DELIVERY | ERP | allowed |
| Worker-owned (in-process) | COLLECTING, PACKING, DELIVERING | device holding the lock | **blocked** (`erp_sync_blocked=true`) |
| Worker-frozen (post-stage) | COLLECTED, PACKED, DELIVERED, SENT, ERROR | device's last snapshot | header only; line actuals / boxes preserved |

See [ownership-model-plan.md](ownership-model-plan.md) for the full ownership
policy and the mechanisms that enforce it.

---

## Synchronization Flow

### Initial Sync (first run after login)

```
Client                                    Server
   |--- POST device/login -------------->|  (login + password)
   |<-- USER_LOGIN_RESULT + JWT pair -----|  (user info + held_stage_locks)
   |--- POST device/sync?full=1 -------->|  (reset cursors, then loop)
   |<-- SyncResponse (entities,has_more)-|
   |--- POST device/sync ---------------->|  (applied_cursors, while has_more)
   |<-- SyncResponse (has_more=false) ---|
   |   (RestTransport emits SYNC_DATA × N + SYNC_COMPLETE)
```

### Delta Sync (subsequent pulls)

```
Client                                    Server
   |--- POST device/sync ---------------->|  (applied_cursors = last next_cursors)
   |<-- SyncResponse (delta + deleted_ids)|
   |   (loops while has_more; emits SYNC_DATA × N + SYNC_COMPLETE)
```

Delta pulls omit `full`; only `full=1` resets the server-side cursors.

### Document Stage Workflow

```
Client                                         Server                ERP
   |--- POST device/documents/{id}/lock ----->|                      |
   |                                           |--- erp_sync_blocked ->|
   |<-- STAGE_LOCK_RESULT --------------------|                      |
   |--- PATCH device/documents/{id} --------->|  (debounced, repeated)|
   |<-- DOCUMENT_UPDATE_RESULT ---------------|                      |
   |--- POST device/documents/{id}/complete ->|                      |
   |<-- STAGE_COMPLETE_RESULT ----------------|                      |
   |                                           |--- stage done ------->|
```

---

## Offline Handling

1. Stored on the device:
   - Device access + refresh JWT — issued by `device/login`, refreshed via
     `device/refresh`.
   - `user_id`, `role`, `offline_hash` — after a successful `USER_LOGIN`.
2. Offline = REST calls fail; queue operations locally:
   - Stage-lifecycle ops (`STAGE_LOCK` / `STAGE_COMPLETE` / `STAGE_UNLOCK` /
     `STAGE_PAUSE`) go through the `OutgoingOperationEntity` queue.
   - Line edits are carried as per-line `is_dirty` rows and reconciled by the
     resync worker.
   - Courier box confirmations carry an `offline_seq` for idempotent replay.
3. On the next successful request: drain the `OutgoingOperationEntity` queue and
   resync.
4. Conflicts: server wins for documents not locked by this device; for
   worker-owned documents, dirty line preservation protects the worker's edits.

---

## Guided Tasks (WMS addressing module)

Present only where the tenant has the `wms_addressing` module and the worker's
warehouse has it switched on; elsewhere the endpoints answer
`403 FEATURE_DISABLED` and the guided types never reach the login response.
Authoritative contract: server repo `docs/device-api.md` § "Guided tasks".

The device is a **renderer**: the server owns the step machine, the route and
every text the worker reads (already in the tenant's locale). The app shows
`step` verbatim, sends one action back, and shows the next `step`. It never
branches on the step id or the task type.

`GuidedTaskRepository` is the only caller of the five messages above; it owns
the `operation_id` and the retry that reuses it. Nothing about a task is
persisted on the device.

### Envelope

Every task endpoint answers the same shape, carried by `TASK_RESULT`
(`TASK_OPEN_RESULT` carries an array of them):

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

| Field | Meaning for the app |
|-------|---------------------|
| `step.expect` | `cell` / `product` / `batch` / `qty` / `document` / `none`. An unknown value is treated as `none`; scans are still forwarded. |
| `step.actions[].code` | `scan` / `confirm` / `empty` / `skip` / `manual_cell` / `no_stock` / `cancel` / `done`. Only the offered ones are listed, and `scan` never is — it is what the scanner sends. An unknown code renders as a plain button and is sent as-is. `manual_cell` is the one exception: its button opens the *enter address* dialog and the typed code goes out as `manual_cell` with `value` — the bare button is never posted (an empty value answers "cell not found"). |
| `step.rows` | Context lines rendered as they come. `highlight` marks rows still needing attention. |
| `task.state` | `OPEN` / `DONE` / `CANCELLED`. After the last two the step is a final screen with a single `done`. |
| `message` | One-shot notice about the last action; not part of the step. `error` also fires a haptic. |
| `line_updates` | Document-bound tasks only — applied to the cached document with no sync round-trip. |

### Login fields

| Field | Meaning |
|-------|---------|
| `available_document_types[].mode` | `"guided"` marks a task type. The server omits guided types where the module is off, so a plain client never sees one. |
| `available_document_types[].wms_flow` | `"collect"` / `"receive"`: the guided flow documents of a classic type run. Sent only where the worker's warehouse has the module on. The app keys *Join receiving* and the guided bar's wording on `wms_flow == "receive"` — never on the type code (1C sends its own, e.g. `ПриходнаяНакладная`). |
| `open_tasks[]` | `{id, type, document_id?, step_title, started_at}` — the worker's unfinished tasks, for the continue / cancel offer. |
| `held_stage_locks[]` | Includes the Collect lock the task engine took for a guided document. The orchestrator **excludes** every id present in `open_tasks[].document_id`. |

### Idempotency, stale screens, locks

* `operation_id` is a fresh UUID per action and is **reused verbatim on retry**
  (3 attempts, 1/2/4 s). A repeat answers the original response with
  `replayed: true` and never applies twice. A response carrying a server error
  code is a verdict, not a hiccup, and is not retried.
* A `step_id` the server has moved past is ignored; the current step comes back
  with a warning `message` and the app simply re-renders.
* Cell / line locks are extended by the ordinary `POST /device/sync` and by
  every task action. Since a guided document holds no client stage lock, the
  task screen drives that poll itself on the orchestrator's 8 s cadence while
  it is resumed. Backgrounding the app lets the lock expire after the
  warehouse's `lock_ttl_sec`; the next action may then answer `LOCKED` with the
  holder named in the server message.

### Document fields

| Field | Meaning |
|-------|---------|
| `collect_mode` | `"guided"` on a LOADED / COLLECTING document whose warehouse works it as a task. Recomputed on every list load, so the tenant's emergency switch flips the detail screen on the next refresh. Absent = classic screen. |
| `lines[].line_key` | The ERP's stable line id, when supplied. `line_updates` address a line by it; classic flows keep using `line_number`. |

### Error codes

| Code | HTTP | App behaviour |
|------|------|---------------|
| `FEATURE_DISABLED` / `WMS_WAREHOUSE_DISABLED` | 403 / 409 | toast, leave the task screen |
| `GUIDED_OFF` | 409 | toast, go back; the detail screen shows the classic bar on the next load |
| `NO_WAREHOUSE` | 409 | toast, leave |
| `LOCKED` | 409 | show the server message, stay on the step |
| `WRONG_STATE` / `DOCUMENT_LOCKED` / `DOCUMENT_WAREHOUSE` | 409 | toast, go back |
| `CONFLICT` | 409 | on a `{document_id}` start: the document is not in the Collect stage (generic refusal of the lock path) — the *document not available* toast, go back. Elsewhere: generic toast |
| `NOT_FOUND` | 404 | toast, leave; the task is dropped from the unfinished list |
| `FORBIDDEN` | 403 | on a `{document_id}` start: the document is assigned to another worker — *document not available*. On a type start: the task type is not assigned to the worker. Toast, leave |
| `BAD_REQUEST` | 400 | logged at ERROR; the step is re-fetched |

---

## Debug Journal

The app records per-document debug events into a local Room table
(`debug_journal_events`), tenant-scoped, and uploads them in batches via
`DEBUG_EVENT_BATCH`. Events are visible in-app on a "Debug Journal" screen
reachable from Profile for users with the `ADMINISTRATOR` role.

Enablement is per-device, driven by `debug_journal_enabled` in
`USER_LOGIN_RESULT` (re-read on each login). When disabled,
`DebugJournal.log()` is a no-op. Retention: a rolling cap of 7 days or 5000
rows, pruned by a background worker every 15 minutes; batches are capped at 200
events.

Event-type catalog (the `event_type` field of `DEBUG_EVENT_BATCH` events):

| Group | Event types |
|-------|-------------|
| Line edits | `LINE_EDIT`, `LINE_EDIT_FAILED`, `LINE_CREATE` |
| Boxes | `BOX_ADD`, `BOX_REMOVE`, `BOX_LOOKUP` |
| Sync scheduling | `SYNC_SCHEDULED`, `SYNC_FIRED`, `SYNC_FLUSHED` |
| Document update | `DOC_UPDATE_SENT`, `DOC_UPDATE_QUEUED` |
| Stage ops | `STAGE_LOCK_SENT`, `STAGE_LOCK_RESULT`, `STAGE_UNLOCK_SENT`, `STAGE_PAUSE_SENT`, `STAGE_COMPLETE_SENT`, `STAGE_COMPLETE_RESULT` |
| Sync apply | `DOC_SYNC_SUPPRESSED`, `DOC_SYNC_APPLIED`, `PHANTOM_LINE_PURGED`, `LOADED_ACTUAL_REJECTED` |
| Lock-loss recovery | `LOCK_LOST_RECOVERED` (INFO), `LOCK_LOST_EDIT_DROPPED` (ERROR) |
| Local / transport | `DOC_DELETED_LOCAL`, `RESYNC_DIRTY`, `WS_SEND_FAIL`, `WS_ACK_TIMEOUT`, `WS_DISCONNECT`, `WS_RECONNECT` (legacy `WS_`-prefixed constants, retained as event-type identifiers) |
| Config | `JOURNAL_CONFIG_CHANGED` |

Server-side implementation is tracked in the server repository.
