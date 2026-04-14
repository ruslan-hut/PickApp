# Debug Journal — Server-Side Implementation Plan

## Context

The Android app now emits a per-device **Debug Journal**: a structured, per-document event log (line edits, debounce schedule/fire/flush, WS send/ack/fail, stage transitions, local deletes, resync attempts). The journal is the diagnostic tool used to investigate the reported bug where documents arrive on the server in `COMPLETED` state with incomplete collected quantities.

The client is already wired end-to-end:

- Events are written to a local Room table `debug_journal_events`, scoped by `tenant_id` + `device_id`.
- Events are uploaded in batches via a new WebSocket message `DEBUG_EVENT_BATCH`.
- Enablement is per-device, driven by a new `debug_journal_enabled: boolean` field in the `USER_LOGIN_RESULT` payload. The client already reads and persists this flag; when false, `DebugJournal.log()` is a no-op.
- The admin in-app screen (visible only to `ADMINISTRATOR` role) reads events from local Room and shows them grouped by document.

What is **missing** is everything on the server:

1. Per-device enablement storage and admin controls.
2. Ingestion and persistence of `DEBUG_EVENT_BATCH` messages.
3. Reply with `DEBUG_EVENT_BATCH_RESULT` so the client marks events uploaded.
4. A way for a tenant admin to browse events across devices (the in-app screen only shows the admin's own device; cross-device visibility requires a server-side view).
5. Retention and privacy controls.

This document defines the server contract and the work items needed to ship.

---

## 1. Data model

One new table, tenant-scoped, append-only in steady state.

### Table `debug_journal_events`

| column            | type          | notes |
|-------------------|---------------|-------|
| `id`              | uuid PK       | equal to `events[].id` from the client batch |
| `tenant_id`       | text NOT NULL | from batch envelope; index |
| `device_id`       | text NOT NULL | from batch envelope; index |
| `user_id`         | text NULL     | from event payload (may be null for pre-auth events) |
| `document_id`     | text NULL     | nullable for session-scoped events (WS disconnect) |
| `stage`           | text NULL     | `collect` / `pack` / `deliver` / null |
| `event_type`      | text NOT NULL | enum — see §2 |
| `severity`        | text NOT NULL | `INFO` / `WARN` / `ERROR` |
| `message`         | text NOT NULL | human-readable one-liner |
| `payload_json`    | jsonb NULL    | structured blob, see §2 |
| `device_ts`       | bigint NOT NULL | client `created_at` (epoch ms) |
| `server_ts`       | bigint NOT NULL | server receive time (epoch ms) |
| `ingested_at`     | timestamptz   | generated `now()` |

**Indices:**
- `(tenant_id, device_ts DESC)` — primary admin list view
- `(tenant_id, document_id, device_ts)` — per-document grouping
- `(tenant_id, device_id, device_ts)` — per-device drill-down
- `(ingested_at)` — retention sweep

**Conflict policy:** primary key on `id` is `UUID` generated client-side. Use `INSERT ... ON CONFLICT (id) DO NOTHING` — re-deliveries from a client with a lost ack are idempotent.

**Partitioning (recommended):** range-partition by `ingested_at` monthly. Drop old partitions on retention — much cheaper than row-level delete for a write-heavy log table.

---

## 2. Event catalog (canonical `event_type` values)

These match the client's `DebugEventType` constants. The server must accept them as opaque strings but should validate against this allowlist to catch client bugs.

| event_type | emitted when | payload_json fields |
|---|---|---|
| `LINE_EDIT` | User changed a line qty (manual or scan) | `line_id`, `new_qty`, `delta?`, `barcode?` |
| `LINE_CREATE` | New line created from a barcode scan | `line_id`, `qty`, `barcode` |
| `SYNC_SCHEDULED` | Debounced sync scheduled after a line change | — |
| `SYNC_FIRED` | Debounced sync ran | `line_count`, `total_actual_qty`, `state` |
| `SYNC_FLUSHED` | Pre-complete flush invoked | — |
| `DOC_UPDATE_SENT` | `DocumentUpdate` dispatched to WS | `line_count`, `total_actual_qty`, `state`, `message_id` |
| `DOC_UPDATE_QUEUED` | WS offline or send failed, queued locally | `line_count` |
| `STAGE_LOCK_SENT` | `StageLock` dispatched | — |
| `STAGE_LOCK_RESULT` | Server ack for `StageLock` | `success`, `locked_by`, `error` |
| `STAGE_UNLOCK_SENT` | `StageUnlock` dispatched | — |
| `STAGE_COMPLETE_SENT` | `StageComplete` dispatched | `message_id` |
| `STAGE_COMPLETE_RESULT` | Server ack for `StageComplete` | `success`, `state`, `error` |
| `DOC_DELETED_LOCAL` | Client purged the document after completion | — |
| `RESYNC_DIRTY` | `resyncDirtyDocuments` re-sent a dirty doc | `line_count`, `state` |
| `WS_SEND_FAIL` | `ws.send()` returned false or threw | — |
| `WS_ACK_TIMEOUT` | `sendAndAwait` timed out with no response | — |
| `WS_DISCONNECT` | Socket closed or failed | `code`, `reason` / `http_code`, `error` |
| `JOURNAL_CONFIG_CHANGED` | Server toggled enablement for this device | — |

Unknown `event_type` values **must be accepted** (stored as-is) so new client versions do not break the server.

---

## 3. Enablement

### 3a. Per-device flag storage

New table `debug_journal_config`:

| column            | type          | notes |
|-------------------|---------------|-------|
| `tenant_id`       | text NOT NULL | |
| `device_id`       | text NOT NULL | |
| `enabled`         | bool NOT NULL DEFAULT false | |
| `enabled_by`      | text NULL     | admin user id |
| `enabled_at`      | timestamptz   | |
| `expires_at`      | timestamptz NULL | optional auto-off; see §3c |
| PRIMARY KEY       | (tenant_id, device_id) | |

### 3b. Login response

Extend `USER_LOGIN_RESULT.payload` with:

```json
{
  "debug_journal_enabled": true
}
```

Populated from `debug_journal_config` by `(tenant_id, device_id)`. If no row exists, return `false` (not `null`). The field is optional on the wire — the client tolerates absence.

The `device_id` is already sent during the device-connection stage (Stage 1) of the WebSocket handshake. Make sure it is available on the session when the user login is processed.

### 3c. Live toggle (future, optional)

For support to flip the flag without forcing the user to re-log in, reuse the existing `PUSH` message:

```json
{
  "type": "PUSH",
  "payload": {
    "event": "debug_journal_config",
    "entity_type": "device",
    "entity_id": "<device_id>",
    "data": { "enabled": true }
  }
}
```

This is listed as **future work** — the app does not yet handle it. It will need a small orchestrator change to update the preference and emit a `JOURNAL_CONFIG_CHANGED` audit event.

### 3d. Auto-expiry (recommended)

Support specifies `expires_at` when enabling (e.g. 7 days). A cron job flips `enabled = false` when `expires_at < now()`. This prevents forgotten-on devices from streaming events forever.

---

## 4. WebSocket protocol

### 4a. New inbound message — `DEBUG_EVENT_BATCH`

Client → server. Best-effort; never blocks business flow.

```json
{
  "id": "<nanosec>",
  "type": "DEBUG_EVENT_BATCH",
  "timestamp": "2026-04-14T12:34:56Z",
  "payload": {
    "tenant_id": "tenant-123",
    "device_id": "device-uuid",
    "events": [
      {
        "id": "uuid",
        "user_id": "user-456 or null",
        "document_id": "doc-789 or null",
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

Notes:
- `payload_json` arrives as a **string**, not a nested object. Parse it on the server if you want indexable fields, but store the raw string too for forward-compat.
- Batch size is capped at **200** events by the client (`Constants.DebugJournal.UPLOAD_BATCH`). Reject batches larger than ~500 as a defensive measure.
- Validate `tenant_id` matches the authenticated session's tenant. Refuse (`success=false`, `error="tenant_mismatch"`) if not — a bug in the client would otherwise let it write into another tenant's table.

### 4b. New outbound message — `DEBUG_EVENT_BATCH_RESULT`

Server → client, echoing the request `id`.

```json
{
  "id": "<same-as-request>",
  "type": "DEBUG_EVENT_BATCH_RESULT",
  "timestamp": "2026-04-14T12:34:56Z",
  "payload": {
    "success": true,
    "accepted_ids": ["uuid1", "uuid2", "..."],
    "error": null
  }
}
```

- `success=true` and `accepted_ids` contains the full set → client marks all uploaded.
- `success=true` and `accepted_ids` is a strict subset → client marks only those uploaded and bumps attempt counter on the rest. Use this if you want to skip malformed events without failing the batch.
- `success=false` → client increments `upload_attempts` for every event in the batch and retries on the next cycle.

Correlation: `WebSocketManager` matches the response by **message type alone** (no correlation id extractor for this type), so there must not be two in-flight batches at the same time. The client's `DebugJournalUploader` uses a `Mutex` to guarantee this.

If you want to be strict, add `DebugEventBatchResult` → `batch_id` (= request id) to `extractIncomingCorrelationId()` in a follow-up.

### 4c. Auth gating

`DEBUG_EVENT_BATCH` must require a fully authenticated session (Stage 2 complete). Treat pre-auth batches as `success=false, error="not_authenticated"`. The client won't send them (uploader checks `isUserAuthenticated()`) but the server should defend anyway.

---

## 5. Ingestion pipeline

Inside the WebSocket handler for `DEBUG_EVENT_BATCH`:

1. Validate envelope (id, type, timestamp, payload present).
2. Validate `payload.tenant_id == session.tenant_id`. Reject if mismatch.
3. Validate `payload.device_id == session.device_id`. Reject if mismatch.
4. For each event:
   - Coerce `event_type` to string; allow unknown values but log a warning metric.
   - Coerce `severity` to one of INFO/WARN/ERROR, defaulting to INFO.
   - Parse `payload_json` if present; on parse failure, keep raw string and add `parse_error=true` to a sidecar field.
   - Clamp `message` length (e.g. 4 KB) to prevent memory blowup.
5. `INSERT ... ON CONFLICT (id) DO NOTHING` — bulk, one round-trip.
6. Count `inserted` and `skipped` (already present).
7. Reply with `DEBUG_EVENT_BATCH_RESULT` listing all ids as `accepted_ids` (both inserted and skipped — the client's job is done either way).

Put the insert on a **non-blocking writer pool** with a bounded queue, not the main WS message handler. A slow Postgres shouldn't stall stage lock messages on the same socket. Drop with a metric if the queue is full (still reply with `success=false, error="overloaded"` so the client retries on the next cycle).

---

## 6. Admin-facing API

The Android admin screen only shows events from the admin's own device. To help support triage **across** devices, expose a read API on the backend — used by the ERP admin UI, not the Android app.

### REST `GET /admin/debug_journal`

Auth: tenant admin (existing ERP admin auth; not a device session).

Query params:
- `tenant_id` (required, or inferred from auth)
- `device_id` (optional filter)
- `document_id` (optional filter)
- `event_type` (optional, multi-value)
- `severity` (optional)
- `since` (epoch ms, inclusive)
- `until` (epoch ms, exclusive)
- `limit` (default 500, max 5000)
- `cursor` (opaque, for pagination)

Response: paginated list of events, newest first, grouped server-side by `document_id` if requested.

### REST `GET /admin/debug_journal/documents/{document_id}`

Convenience endpoint: returns the full timeline for a single document across all devices and users that touched it. This is the primary investigation view for the completion bug — one call shows every event across every contributing device.

### REST `GET /admin/debug_journal/devices`

List devices with journal activity in a time window, with event counts and last-seen.

### REST `PUT /admin/debug_journal/config`

Enable/disable journaling for a `(tenant_id, device_id)` pair.

Body:
```json
{
  "device_id": "...",
  "enabled": true,
  "expires_at": 1714000000000
}
```

The server writes to `debug_journal_config` and, if the live-toggle push (§3c) is shipped, emits it to that device's active WebSocket session.

---

## 7. Retention & privacy

- **Retention:** default 30 days. Drop monthly partitions older than that. This matches the client-side 7-day local cap — server keeps longer so cross-device history survives a device wipe.
- **Tenant deletion:** add a hook in the tenant-deletion path to drop rows with the matching `tenant_id`.
- **PII:** the `message` and `payload_json` fields may contain product codes and quantities but no personal data by design. Review the client-side log sites (they are listed in §2) and confirm; if any future site emits customer names/addresses, mask before store.
- **Access log:** every `GET /admin/debug_journal*` call should produce an audit log entry (who viewed what). Debug logs are a support tool; their access should be traceable.

---

## 8. Observability

Metrics to export:
- `debug_journal_events_ingested_total{tenant, event_type, severity}`
- `debug_journal_events_rejected_total{reason}` — tenant_mismatch, not_authenticated, parse_error, overloaded
- `debug_journal_batch_size` histogram
- `debug_journal_config_enabled_devices{tenant}` gauge
- `debug_journal_writer_queue_depth` gauge

Alerts:
- Rejected rate > 1% sustained → client/server schema drift.
- Overloaded rejections > 0 → writer pool needs scaling.
- `ERROR`-severity events for a `document_id` followed by a successful `STAGE_COMPLETE_RESULT` → candidate for the completion bug; feed into a saved investigation query.

---

## 9. Sequence for the bug investigation (the immediate motivation)

Once ingestion is live:

1. Enable journaling for 5–10 real user devices where the bug was reported (via `PUT /admin/debug_journal/config`, 7-day expiry).
2. Wait for the next report of "document completed but incomplete".
3. Query `GET /admin/debug_journal/documents/{document_id}`.
4. Look for this pattern (one of the suspects from the original investigation memo):
   - **Race**: `STAGE_COMPLETE_RESULT success=true` arrives with no preceding `DOC_UPDATE_SENT` in the same window → the pre-complete flush didn't fire or didn't capture the latest lines.
   - **WS loss**: `DOC_UPDATE_SENT` present but server has no record of receiving it → fire-and-forget drop. Confirms we need per-message acks for `DocumentUpdate`.
   - **Resync stomp**: `RESYNC_DIRTY` fires after `STAGE_COMPLETE_RESULT` → `resyncDirtyDocuments` is racing with completion. Confirms we need to gate resync by completed state.
5. Pick the right fix based on which pattern dominates.

---

## 10. Work breakdown (server team)

| # | Item | Effort |
|---|---|---|
| 1 | Create `debug_journal_events` table + partitioning + indices | S |
| 2 | Create `debug_journal_config` table | S |
| 3 | Extend `USER_LOGIN_RESULT` payload builder to include `debug_journal_enabled` | S |
| 4 | Implement `DEBUG_EVENT_BATCH` WS handler with validation, async writer, and `DEBUG_EVENT_BATCH_RESULT` reply | M |
| 5 | Bounded writer pool + metrics | S |
| 6 | `GET /admin/debug_journal` with filters and pagination | M |
| 7 | `GET /admin/debug_journal/documents/{id}` | S |
| 8 | `GET /admin/debug_journal/devices` | S |
| 9 | `PUT /admin/debug_journal/config` | S |
| 10 | Retention cron (drop old partitions) | S |
| 11 | Tenant-delete hook | S |
| 12 | Audit log on admin reads | S |
| 13 | Metrics + alerts | S |
| 14 | ERP admin UI screen for the above (or whichever admin tool you use) | M |
| 15 | **Follow-up:** live-toggle via `PUSH` | S (deferred) |
| 16 | **Follow-up:** client-side `DebugEventBatchResult` correlation by `batch_id` | XS (client-side) |

---

## 11. Verification

1. **Unit/contract tests** on server:
   - Batch with mismatched `tenant_id` → rejected with `tenant_mismatch`.
   - Batch from unauthenticated session → `not_authenticated`.
   - Duplicate ids across two batches → idempotent, second inserts 0 rows, reply `success=true` with all ids in `accepted_ids`.
   - Oversized `message` → truncated, not rejected.
   - Unknown `event_type` → stored as-is, counter increments.

2. **End-to-end manual:**
   - Enable journaling for one test device.
   - Log in on that device, confirm `debug_journal_enabled=true` in login response.
   - Perform a document pick, tap Complete.
   - Within a minute, `GET /admin/debug_journal/documents/{doc_id}` returns the full event timeline, ordered by `device_ts`.
   - Confirm the sequence matches the expected happy path: `LINE_EDIT…SYNC_SCHEDULED…SYNC_FLUSHED…DOC_UPDATE_SENT…STAGE_COMPLETE_SENT…STAGE_COMPLETE_RESULT…DOC_DELETED_LOCAL`.
   - Kill the device mid-upload, relaunch → confirm no duplicates in the table.
   - Flip the flag off → new events stop arriving.

3. **Load test:**
   - Synthetic client sending 50 batches/sec of 200 events each → server p95 insert latency < 100ms, no queue overflow.
