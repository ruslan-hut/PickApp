# TSD WebSocket Protocol

## Overview

This document is the authoritative reference for the WebSocket protocol between
Android TSD devices and the intermediate server. The app is **WebSocket-first**:
authentication, synchronization, document/stage operations, box operations and
diagnostics all run over a single WebSocket connection. There is no REST data
path in the production flow (see [backend-spec.md](backend-spec.md) §1).

The protocol supports real-time synchronization, a three-stage document
workflow (collect → pack → deliver), and offline-first patterns.

## Connection

### Endpoint

```
ws://{host}:{port}/ws/connect?app_token={app_token}&device_id={device_id}&protocol_version=v2
```

The `app_token` is also sent as an `X-App-Token` header (fallback).

| Query param | Description |
|-------------|-------------|
| `app_token` | Hardcoded in the Android app build; validates the app against server config |
| `device_id` | Unique hardware identifier |
| `protocol_version` | `v2` — marks the client as carrying the ERP `external_id` translation logic. The server defaults to `v1` when absent. |

> `protocol_version=v2` is a forward marker for the eventual cleanup of the
> server's dual-accept ID heuristic. The backend currently emits the same DTO
> format regardless of the value.

### Authentication Flow

Authentication happens in two stages:

1. **Device Connection** — `app_token` validates the Android app, `device_id`
   identifies the device.
2. **User Login** — after the WebSocket is established, the user authenticates
   via a `USER_LOGIN` message.

#### Stage 1: Device Connection

**Connection responses:**
- `401 Unauthorized` — invalid or missing app token
- `403 Forbidden` — device is PENDING approval, REJECTED, or has no tenant assigned
- `101 Switching Protocols` — success, WebSocket established

After a successful upgrade the server may still close the socket with a
device-status close code:

| Close code | Meaning |
|------------|---------|
| `4003` | Device is PENDING approval — client does not auto-reconnect |
| `4004` | Device is REJECTED — client does not auto-reconnect |
| `4000` | PONG timeout (client-initiated) — client reconnects |

**New devices** are auto-registered with PENDING status and must be approved
via the admin panel.

#### Stage 2: User Login

After connection, send `USER_LOGIN` to authenticate the user. See the
[USER_LOGIN](#user_login) section for payloads.

The app auto-logs-in on every (re)connect using credentials stored after the
first successful login.

**Operations requiring user authentication** — everything except the three
below.

**Operations allowed without user login:**
- `PING`
- `USER_LOGIN`
- `ERROR_REPORT`

---

## Message Format

All messages follow a common envelope structure:

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
| payload | object | Type-specific payload (`null` for `PING`/`PONG`) |

Request/response correlation is by message `type` plus, for document-scoped
operations, the `document_id` in the payload. Only one in-flight request per
correlation key is assumed.

---

## Message Types

### Client → Server

| Type | Description | Requires User Auth |
|------|-------------|-------------------|
| `PING` | Keep-alive ping | No |
| `USER_LOGIN` | Authenticate user after connection | No |
| `ERROR_REPORT` | Report client-side error | No |
| `SYNC_REQUEST` | Request delta synchronization | **Yes** |
| `FULL_SYNC_REQUEST` | Request a full data resync | **Yes** |
| `ACK` | Acknowledge received sync data | **Yes** |
| `DOCUMENT_LIST_REFRESH` | Refresh the document list (+ related warehouses/clients) | **Yes** |
| `DOCUMENT_PRODUCTS` | Request products referenced by one document | **Yes** |
| `DOCUMENT_UPDATE` | Push document line updates (debounced) | **Yes** |
| `STAGE_LOCK` | Lock a document for a stage ("Take into work" / resume) | **Yes** |
| `STAGE_UNLOCK` | Release a stage lock (revert to start state) | **Yes** |
| `STAGE_PAUSE` | Pause a stage: release lock, keep in-process state | **Yes** |
| `STAGE_COMPLETE` | Complete the current stage | **Yes** |
| `PRODUCT_LOOKUP` | Search a product by barcode | **Yes** |
| `BOX_ADD` | Link a scanned box to a document (PACK stage) | **Yes** |
| `BOX_REMOVE` | Remove a box from a document (PACKING stage) | **Yes** |
| `BOX_LOOKUP` | Resolve a box barcode missing from the local catalog | **Yes** |
| `BOX_PICKUP_CONFIRM` | Courier confirms box pickup (offline-capable) | **Yes** |
| `BOX_DELIVERY_CONFIRM` | Courier confirms box delivery (offline-capable) | **Yes** |
| `DEBUG_EVENT_BATCH` | Upload a batch of debug-journal events | **Yes** |

### Server → Client

| Type | Description |
|------|-------------|
| `PONG` | Keep-alive pong (carries `debug_journal_enabled`) |
| `USER_LOGIN_RESULT` | User login result |
| `SYNC_DATA` | Synchronization data batch (one per entity type) |
| `SYNC_COMPLETE` | Synchronization complete with cursors |
| `STAGE_LOCK_RESULT` | Stage lock / pause / resume result |
| `STAGE_COMPLETE_RESULT` | Stage completion result |
| `PRODUCT_LOOKUP_RESULT` | Product barcode lookup result |
| `BOX_ADD_RESULT` | Box add result |
| `BOX_REMOVE_RESULT` | Box remove result |
| `BOX_LOOKUP_RESULT` | Box barcode lookup result |
| `BOX_PICKUP_CONFIRM_RESULT` | Box pickup confirmation result |
| `BOX_DELIVERY_CONFIRM_RESULT` | Box delivery confirmation result |
| `SERVER_ERROR` | Error response |
| `PUSH` | Real-time push notification |
| `FORCE_RELEASE_REQUEST` | Admin-initiated cooperative force-release |
| `DEBUG_EVENT_BATCH_RESULT` | Ack for a debug-journal batch upload |

> There is no `STAGE_UNLOCK_RESULT` or `STAGE_PAUSE_RESULT`. `STAGE_UNLOCK` is
> fire-and-forget; `STAGE_PAUSE` is answered with a `STAGE_LOCK_RESULT`.

---

## Protocol Messages

### USER_LOGIN

Authenticate the user after the WebSocket connection is established.

**Client sends:**
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

**Server responds (success):**
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
| held_stage_locks | string[]? | ERP `external_id`s of documents this `(user, device)` pair already holds an in-process stage lock for. The app rebuilds its `heldStageLocks` set from this on connect, closing the post-restart race where inbound `SYNC_DATA` could overwrite worker-owned line data. Null/empty on a fresh login. |

`available_document_types[]` entries carry `code`, `description`, and three
optional capability flags. Each flag is nullable — `null` means the ERP did not
specify it and the client applies its own default:

| Flag | Default | Effect |
|------|---------|--------|
| `allows_over_plan` | `false` | Permit `actual_quantity > planned_quantity` |
| `allows_extra_lines` | `false` | Scanning an unknown product creates a new line |
| `requires_plan` | `true` | Lines carry a plan; UI shows plan labels and progress |

**Server responds (failure):**
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

### PING / PONG

Keep-alive mechanism. The client sends `PING` every 30s; the server responds
with `PONG`.

**Client sends:**
```json
{ "id": "123", "type": "PING", "timestamp": "2024-01-01T12:00:00Z", "payload": null }
```

**Server responds:**
```json
{
  "id": "124",
  "type": "PONG",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "debug_journal_enabled": true }
}
```

`PONG.payload` may be `null`. When present, `debug_journal_enabled` piggybacks
the per-device debug-journal toggle so an admin flip propagates within one ping
interval (~30s) without requiring re-login. The client only persists the value
when it actually changed. Absent field = older server build → leave the local
flag as-is.

---

### SYNC_REQUEST

Request delta updates since the last synchronization.

**Client sends:**
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

The server streams one `SYNC_DATA` message per entity type, then a
`SYNC_COMPLETE`.

---

### FULL_SYNC_REQUEST

Request a complete resync (used after a local failure or data inconsistency).

**Client sends:**
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

A batch of entity records for one entity type. The server sends one per entity
type before `SYNC_COMPLETE`.

**Server sends:**
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

Server notification that a sync round is finished, carrying the new cursors.

**Server sends:**
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

Acknowledge successful receipt of sync data. The server persists the device's
sync cursors **only** after receiving the ACK. ACK confirms network delivery,
not local DB persistence.

**Client sends:**
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

Request a refresh of the document list together with the warehouses and clients
they reference. The server answers with `SYNC_DATA` batches and a
`SYNC_COMPLETE`.

**Client sends:**
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

Request the products referenced by the lines of a single document (used when a
document is opened and some products are missing locally).

**Client sends:**
```json
{
  "id": "127",
  "type": "DOCUMENT_PRODUCTS",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001" }
}
```

The server answers with a `SYNC_DATA` (`entity_type: "products"`) batch.

---

### STAGE_LOCK

Lock a document for a stage ("Take into work", or resume after a pause). A stage
lock can be acquired on a stage **start state** (`LOADED`, `PACK`, `DELIVERY`)
or on the matching in-process state when resuming.

**Client sends:**
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

**Server responds (success):**
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

Release a stage lock and revert the document to its stage start state. Drops the
worker's in-process ownership. Fire-and-forget — no result message.

**Client sends:**
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

The server answers with a `STAGE_LOCK_RESULT`.

---

### STAGE_COMPLETE

Complete the current stage. The document must be locked by the current user and
in the matching in-process state.

**Client sends:**
```json
{
  "id": "132",
  "type": "STAGE_COMPLETE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "stage": "collect" }
}
```

**Server responds (success):**
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

**Server responds (error):**
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

Push document line updates while a stage lock is held. Sent debounced from the
device, and flushed before `STAGE_LOCK` / `STAGE_COMPLETE` / `STAGE_UNLOCK`
(not before `STAGE_PAUSE`).

**Client sends:**
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

`actual_quantity` is **absolute** (the post-increment total), not a delta — so
retries are idempotent. There is no dedicated success message; the server
applies the update and rejects with a `SERVER_ERROR` (`LOCK_LOST` /
`WRONG_STATE`, see below) if the write is no longer valid.

---

### PRODUCT_LOOKUP

Search for a product by barcode.

**Client sends:**
```json
{
  "id": "135",
  "type": "PRODUCT_LOOKUP",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "barcode": "4600000000001" }
}
```

**Server responds (found):**
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

**Server responds (not found):**
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

Worker scans a box barcode during the PACK stage to link it to a document.
Parcel boxes require `weight > 0`; for packages the server forces `weight = 0`.

**Client sends:**
```json
{
  "id": "137",
  "type": "BOX_ADD",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "barcode": "BX-7788", "weight": 1200 }
}
```

**Server responds:**
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

Remove a previously-added box while the document is still in `PACKING`.
`box_number` is the in-document sequence assigned by the server on `BOX_ADD`.

**Client sends:**
```json
{
  "id": "139",
  "type": "BOX_REMOVE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001", "box_number": 3 }
}
```

**Server responds:**
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

Client fallback when a scanned box barcode misses the local box catalog. The
server resolves it against the master catalog and, on a hit, returns the full
`Box` DTO so the client can cache it and continue the add flow.

**Client sends:**
```json
{
  "id": "141",
  "type": "BOX_LOOKUP",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "barcode": "BX-7788" }
}
```

**Server responds:**
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

Courier confirms box pickup / delivery. Both are offline-capable: the device
assigns an `offline_seq` and a `client_ts` and replays queued confirmations on
reconnect.

**Client sends:**
```json
{
  "id": "143",
  "type": "BOX_PICKUP_CONFIRM",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "barcode": "BX-7788", "offline_seq": 12, "client_ts": 1712000000000 }
}
```

`BOX_DELIVERY_CONFIRM` has the identical payload shape.

**Server responds:**
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
Triggered by an admin clicking "Force release" in the tenant UI while the device
is connected.

**Server sends:**
```json
{
  "id": "145",
  "type": "FORCE_RELEASE_REQUEST",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": { "document_id": "DOC-1001" }
}
```

On receipt the device runs the equivalent of "exit without saving":
1. Cancels pending debounced sync for the document.
2. Drops locally-dirty edits (zeroes actuals / `is_completed` / batch).
3. Sends `STAGE_UNLOCK` so the server completes the cooperative flow.
4. Surfaces a "lock lost" banner and navigates back from any open detail screen.

The admin's HTTP request completes when the device's `STAGE_UNLOCK` lands
within ~10s; otherwise the admin side falls back to a hard release.

---

### ERROR_REPORT

Report client-side errors for diagnostics. Allowed without user login.

**Client sends:**
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

Server error response.

**Server sends:**
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

### PUSH

Real-time push notification from the server.

**Server sends:**
```json
{
  "id": "148",
  "type": "PUSH",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "event": "document_updated",
    "entity_type": "documents",
    "entity_id": "DOC-1001",
    "data": { ... }
  }
}
```

Push events include `document_updated`, `document_locked`, `document_unlocked`,
`reference_updated`.

---

### DEBUG_EVENT_BATCH

Upload a batch of per-document debug-journal events from the device.
Best-effort, fire-and-retry. Enabled per device via `debug_journal_enabled`
(see [USER_LOGIN_RESULT](#user_login) and the Debug Journal section).

**Client sends:**
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

**Server responds:**
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

### Initial Sync (First Connection)

```
Client                          Server
   |--[WS connect: app_token]----->|  (device must be APPROVED)
   |<-----[101 Switching]----------|
   |------- USER_LOGIN ----------->|  (login + password)
   |<----- USER_LOGIN_RESULT ------|  (success + user info + held_stage_locks)
   |------ FULL_SYNC_REQUEST ----->|  (or SYNC_REQUEST with empty cursors)
   |<-------- SYNC_DATA -----------|  (one per entity type)
   |<-------- SYNC_DATA -----------|
   |<------- SYNC_COMPLETE --------|  (cursors)
   |----------- ACK -------------->|  (confirm cursors)
```

### Delta Sync (Reconnect)

```
Client                          Server
   |--[WS connect: app_token]----->|
   |<-----[101 Switching]----------|
   |------- USER_LOGIN ----------->|  (auto re-authenticate)
   |<----- USER_LOGIN_RESULT ------|
   |------- SYNC_REQUEST --------->|  (with last cursors)
   |<-------- SYNC_DATA -----------|  (delta only)
   |<------- SYNC_COMPLETE --------|  (new cursors)
   |----------- ACK -------------->|
```

### Document Stage Workflow

```
Client                          Server                    ERP
   |------- STAGE_LOCK ---------->|                        |
   |                              |--- erp_sync_blocked -->|
   |<---- STAGE_LOCK_RESULT ------|                        |
   |----- DOCUMENT_UPDATE ------->|  (debounced, repeated) |
   |----- DOCUMENT_UPDATE ------->|                        |
   |----- STAGE_COMPLETE -------->|                        |
   |<-- STAGE_COMPLETE_RESULT ----|                        |
   |                              |--- stage done -------->|
```

---

## Connection Lifecycle

1. **Connect** — establish the WebSocket with `app_token` + `device_id` + `protocol_version`.
2. **User Login** — authenticate via `USER_LOGIN` (auto-login from stored credentials).
3. **Initial Sync** — `FULL_SYNC_REQUEST` or `SYNC_REQUEST`.
4. **Work** — lock stages, push `DOCUMENT_UPDATE`, complete / unlock / pause.
5. **Keep-Alive** — `PING` / `PONG` every 30s.
6. **Reconnect** — on disconnect, reconnect with the same `device_id`; the user
   is re-authenticated automatically.

### Resume health check

After a long Doze sleep the PING timer can be frozen while the socket is
already dead server-side. On app foreground the client probes a `Connected`
socket with an immediate `PING` and forces a reconnect if no `PONG` arrives
within ~7s.

### Timeouts

| Parameter | Default | Description |
|-----------|---------|-------------|
| Ping Interval | 30s | Client sends `PING` |
| Pong Wait | 60s | Max time to wait for `PONG` before reconnecting |
| Write Wait | 10s | Max time to write a message |
| Request Timeout | 30s | Max wait for a correlated response |
| Max Message Size | 512 KB | Maximum message size |
| Max Reconnect Attempts | 10 | Exponential backoff: 1s → 60s |

---

## Offline Handling

1. Stored on the device:
   - `app_token` — hardcoded in the app build
   - `device_id` — unique hardware ID
   - `user_id`, `role`, `offline_hash` — after a successful `USER_LOGIN`
2. Queue operations locally when disconnected:
   - Stage-lifecycle ops (`STAGE_LOCK` / `STAGE_COMPLETE` / `STAGE_UNLOCK` /
     `STAGE_PAUSE`) go through the `OutgoingOperationEntity` queue.
   - Line edits are carried as per-line `is_dirty` rows and reconciled by the
     resync worker.
   - Courier box confirmations carry an `offline_seq` for idempotent replay.
3. On reconnect: connect → `USER_LOGIN` → sync → drain the queue.
4. Conflicts: server wins for documents not locked by this device; for
   worker-owned documents, dirty line preservation protects the worker's edits.

---

## Debug Journal

The app records per-document debug events into a local Room table
(`debug_journal_events`), tenant-scoped, and uploads them in batches via
`DEBUG_EVENT_BATCH`. Events are visible in-app on a "Debug Journal" screen
reachable from Profile for users with the `ADMINISTRATOR` role.

Enablement is per-device, driven by `debug_journal_enabled` in
`USER_LOGIN_RESULT` and re-confirmed on every `PONG`. When disabled,
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
| Local / WS | `DOC_DELETED_LOCAL`, `RESYNC_DIRTY`, `WS_SEND_FAIL`, `WS_ACK_TIMEOUT`, `WS_DISCONNECT`, `WS_RECONNECT` |
| Config | `JOURNAL_CONFIG_CHANGED` |

Server-side implementation is tracked in the server repository.
