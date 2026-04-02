# PickApp Backend Specification

This document defines the backend API structure required to connect with the PickApp Android application.

## Overview

The backend serves as an intermediate server between the Android TSD devices and an external ERP system. It must support:

- **REST API** for authentication and batch synchronization
- **WebSocket** for real-time updates and document operations
- **Offline-first sync** with delta updates and conflict resolution

---

## 1. Authentication API

### POST `/auth/login`

Authenticate user and obtain tokens.

**Request:**
```json
{
  "login": "string",
  "password": "string",
  "device_id": "string (optional)"
}
```

**Response:**
```json
{
  "token": "string (JWT access token)",
  "refresh_token": "string",
  "expires_at": 1234567890000,
  "user": {
    "id": "string (UUID)",
    "login": "string",
    "name": "string",
    "role": "string (WAREHOUSE_WORKER | PICKER | ADMINISTRATOR)",
    "is_active": true,
    "last_updated": 1234567890000
  }
}
```

### POST `/auth/refresh`

Refresh expired access token.

**Request:**
```json
{
  "refresh_token": "string"
}
```

**Response:** Same as login response.

### POST `/auth/logout`

Invalidate current session.

**Headers:** `Authorization: Bearer <token>`

**Response:** `204 No Content`

---

## 2. Sync API

All sync endpoints require `Authorization: Bearer <token>` header.

### GET `/sync/full`

Get complete dataset for an entity type. Used on first sync.

**Query Parameters:**
- `entity` - Entity type: `users`, `products`, `clients`, `warehouses`, `documents`

**Response:**
```json
{
  "sync_id": "string (UUID)",
  "entity_type": "string",
  "timestamp": 1234567890000,
  "is_full_sync": true,
  "data": [...],
  "deleted_ids": []
}
```

### GET `/sync/delta`

Get incremental changes since last sync.

**Query Parameters:**
- `entity` - Entity type
- `since` - Last sync timestamp (milliseconds)

**Response:** Same structure as `/sync/full` with `is_full_sync: false`.

### POST `/sync/ack`

Acknowledge successful sync. Server can clean up pending changes.

**Request:**
```json
{
  "entity_type": "string",
  "sync_id": "string",
  "timestamp": 1234567890000
}
```

**Response:** `204 No Content`

---

## 3. Entity Data Structures

### User

```json
{
  "id": "string (UUID)",
  "login": "string (unique)",
  "name": "string",
  "role": "WAREHOUSE_WORKER | PICKER | ADMINISTRATOR",
  "is_active": true,
  "last_updated": 1234567890000
}
```

### Product

```json
{
  "id": "string (UUID)",
  "code": "string (unique)",
  "name": "string",
  "description": "string (optional)",
  "unit": "string (e.g., kg, pcs)",
  "supports_batches": false,
  "is_active": true,
  "barcodes": [
    {
      "id": "string (UUID)",
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
  "id": "string (UUID)",
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
  "id": "string (UUID)",
  "code": "string (unique)",
  "name": "string",
  "is_addressed": false,
  "is_active": true,
  "locations": [
    {
      "id": "string (UUID)",
      "row": "string",
      "shelf": "string",
      "barcode": "string (optional)",
      "is_active": true
    }
  ]
}
```

### Document

```json
{
  "id": "string (UUID)",
  "external_id": "string (optional, ERP reference)",
  "type": "INCOMING_RECEIPT | OUTGOING_SHIPMENT | INVENTORY",
  "number": "string",
  "date": 1234567890000,
  "state": "LOADED | IN_PROGRESS | COMPLETED | SENT | ERROR",
  "client_id": "string (optional)",
  "client_name": "string (optional)",
  "warehouse_id": "string (optional)",
  "warehouse_name": "string (optional)",
  "notes": "string (optional)",
  "total_planned": 100.0,
  "total_actual": 0.0,
  "assigned_user_id": "string (optional)",
  "taken_at": 1234567890000,
  "completed_at": null,
  "last_modified": 1234567890000,
  "version": 1,
  "lines": [
    {
      "id": "string (UUID)",
      "document_id": "string",
      "line_number": 1,
      "product_id": "string",
      "product_code": "string",
      "product_name": "string",
      "unit": "string",
      "planned_quantity": 10.0,
      "actual_quantity": 0.0,
      "batch_number": "string (optional)",
      "expiration_date": 1234567890000,
      "location_id": "string (optional)",
      "location_path": "A/1/2 (optional)",
      "notes": "string (optional)",
      "is_completed": false
    }
  ]
}
```

---

## 4. WebSocket Protocol

**Endpoint:** `wss://<host>/ws/sync`

**Connection:** Include `Authorization: Bearer <token>` in upgrade headers.

### Server → Client Messages

#### CONNECTED
```json
{
  "type": "CONNECTED",
  "message_id": "uuid",
  "server_time": 1234567890000,
  "session_id": "uuid"
}
```

#### DELTA_UPDATE
Real-time entity changes pushed to client.
```json
{
  "type": "DELTA_UPDATE",
  "message_id": "uuid",
  "entity_type": "documents",
  "timestamp": 1234567890000,
  "is_full_sync": false,
  "data": [...],
  "deleted_ids": ["id1", "id2"]
}
```

#### DOCUMENT_LOCK
Notify when another user takes a document.
```json
{
  "type": "DOCUMENT_LOCK",
  "message_id": "uuid",
  "document_id": "uuid",
  "locked_by": "user-id",
  "locked_by_name": "User Name",
  "locked_at": 1234567890000
}
```

#### ACK
Acknowledge client operation.
```json
{
  "type": "ACK",
  "message_id": "uuid",
  "original_message_id": "client-msg-id",
  "success": true,
  "new_version": 2,
  "error": null
}
```

#### ERROR
```json
{
  "type": "ERROR",
  "message_id": "uuid",
  "code": "CONFLICT | NOT_FOUND | UNAUTHORIZED | INTERNAL",
  "message": "Human readable error",
  "related_message_id": "uuid (optional)"
}
```

### Client → Server Messages

#### SUBSCRIBE
```json
{
  "type": "SUBSCRIBE",
  "message_id": "uuid",
  "entity_types": ["documents", "products"]
}
```

#### TAKE_INTO_WORK
Claim document for processing.
```json
{
  "type": "TAKE_INTO_WORK",
  "message_id": "uuid",
  "document_id": "uuid",
  "user_id": "uuid",
  "timestamp": 1234567890000
}
```

#### DOCUMENT_UPDATE
Update document header.
```json
{
  "type": "DOCUMENT_UPDATE",
  "message_id": "uuid",
  "document_id": "uuid",
  "state": "IN_PROGRESS",
  "notes": "string (optional)",
  "total_actual": 50.0,
  "version": 1,
  "timestamp": 1234567890000
}
```

#### LINE_UPDATE
Update single document line.
```json
{
  "type": "LINE_UPDATE",
  "message_id": "uuid",
  "document_id": "uuid",
  "line_id": "uuid",
  "actual_quantity": 5.0,
  "batch_number": "BATCH123 (optional)",
  "location_id": "uuid (optional)",
  "notes": "string (optional)",
  "is_completed": false,
  "timestamp": 1234567890000
}
```

#### COMPLETE_DOCUMENT
Mark document as completed.
```json
{
  "type": "COMPLETE_DOCUMENT",
  "message_id": "uuid",
  "document_id": "uuid",
  "user_id": "uuid",
  "completed_at": 1234567890000,
  "version": 1
}
```

---

## 5. Document State Machine

```
┌─────────┐
│ LOADED  │  Initial state from ERP
└────┬────┘
     │ User takes into work
     ▼
┌────────────┐
│ IN_PROGRESS│  User is working on document
└─────┬──────┘
      │ User completes
      ▼
┌───────────┐
│ COMPLETED │  Ready for ERP sync
└─────┬─────┘
      │ Synced to ERP
      ▼
┌──────┐
│ SENT │  Confirmed by ERP
└──────┘

      │ Error during sync
      ▼
┌───────┐
│ ERROR │  Requires attention
└───────┘
```

---

## 6. Sync Mechanism

### Sync Flow

The server always returns the **complete document set** for the current user/filter. The client treats every `SYNC_DATA` response for documents as the full set and purges any local documents not present in the response (except dirty/locally-modified documents).

1. Client sends `SYNC_REQUEST` or `DOCUMENT_LIST_REFRESH` via WebSocket
2. Server returns `SYNC_DATA` messages per entity type (always includes documents, even if empty)
3. Server may include `deleted_ids` for explicitly removed documents
4. Client applies changes locally and purges stale documents
5. Client sends `ACK` to confirm
6. Client updates local sync cursors

### Conflict Resolution

Documents use optimistic locking via `version` field:

1. Client includes current `version` in update requests
2. Server compares with stored version
3. If mismatch: reject with `409 Conflict` (REST) or `ERROR` with code `CONFLICT` (WebSocket)
4. Client must refresh document and retry

### Sync Intervals

- **Periodic:** Every 15 minutes via background worker
- **On-demand:** When network becomes available
- **Real-time:** WebSocket push for immediate updates

---

## 7. Error Handling

### HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200  | Success |
| 204  | No Content (success, no body) |
| 400  | Bad Request (validation error) |
| 401  | Unauthorized (invalid/expired token) |
| 403  | Forbidden (insufficient permissions) |
| 404  | Not Found |
| 409  | Conflict (version mismatch) |
| 500  | Internal Server Error |

### Error Response Format

```json
{
  "error": "ERROR_CODE",
  "message": "Human readable message",
  "details": {}
}
```

---

## 8. Security Requirements

### Authentication

- JWT tokens with configurable expiration
- Refresh tokens for seamless re-authentication
- Password storage: SHA-256 hash (for offline fallback on client)

### Transport

- HTTPS required for all REST endpoints
- WSS required for WebSocket connections
- Certificate pinning recommended for production

### Authorization

- Role-based access control
- Document assignment tracking (who is working on what)
- Audit logging for all document operations

---

## 9. Database Schema (Reference)

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
| `documents` | Document headers |
| `document_lines` | Document line items |

### Sync Support Tables

| Table | Purpose |
|-------|---------|
| `sync_state` | Per-client sync timestamps |
| `deleted_records` | Soft-delete tracking for delta sync |
| `outgoing_queue` | Pending ERP sync operations |

---

## 10. ERP Integration Points

The backend acts as middleware between mobile clients and ERP:

### Inbound (ERP → Backend)

- New documents (receipts, shipments, inventory tasks)
- Master data updates (products, clients, warehouses)
- Document state confirmations

### Outbound (Backend → ERP)

- Completed documents with actual quantities
- Document state changes
- Error notifications

### Recommended Integration Pattern

```
ERP ←──REST/SOAP──→ Backend ←──REST/WS──→ Mobile App
         │                        │
    Batch sync              Real-time sync
    (scheduled)             (on-demand)
```

---

## 11. Recommended Tech Stack

### Backend Options

| Component | Options |
|-----------|---------|
| Runtime | Node.js, Kotlin/Spring, Go, .NET |
| REST Framework | Express, Spring Boot, Gin, ASP.NET |
| WebSocket | ws (Node), Spring WebSocket, Gorilla |
| Database | PostgreSQL (recommended), MySQL |
| Cache | Redis (for sessions, rate limiting) |
| Queue | RabbitMQ, Redis Streams (for ERP sync) |

### Deployment

- Docker containers
- Kubernetes for scaling
- Load balancer for WebSocket sticky sessions

---

## 12. Development Checklist

### Phase 1: Core API
- [ ] User authentication (login/refresh/logout)
- [ ] Sync endpoints (full/delta/ack)
- [ ] Basic CRUD for all entities

### Phase 2: Real-time
- [ ] WebSocket connection handling
- [ ] Message routing (subscribe, updates)
- [ ] Document locking mechanism

### Phase 3: Document Operations
- [ ] Take into work flow
- [ ] Line updates with validation
- [ ] Document completion
- [ ] Version conflict handling

### Phase 4: ERP Integration
- [ ] Inbound sync from ERP
- [ ] Outbound document sync
- [ ] Error handling and retry

### Phase 5: Production
- [ ] Security hardening
- [ ] Performance optimization
- [ ] Monitoring and logging
- [ ] Documentation

---

## Appendix: Sample API Calls

### Login
```bash
curl -X POST https://api.example.com/auth/login \
  -H "Content-Type: application/json" \
  -d '{"login":"user1","password":"secret"}'
```

### Get Products (Delta)
```bash
curl -X GET "https://api.example.com/sync/delta?entity=products&since=1234567890000" \
  -H "Authorization: Bearer <token>"
```

### WebSocket Connection
```javascript
const ws = new WebSocket('wss://api.example.com/ws/sync', {
  headers: { 'Authorization': 'Bearer <token>' }
});

ws.send(JSON.stringify({
  type: 'SUBSCRIBE',
  message_id: crypto.randomUUID(),
  entity_types: ['documents']
}));
```
