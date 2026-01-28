# TSD WebSocket Protocol

## Overview

This document describes the WebSocket protocol used for communication between Android TSD devices and the intermediate server. The protocol supports real-time synchronization, document operations, and offline-first patterns.

## Connection

### Endpoint

```
ws://{host}:{port}/ws/connect?app_token={app_token}&device_id={device_id}
```

Or with header:
```
ws://{host}:{port}/ws/connect?device_id={device_id}
X-App-Token: {app_token}
```

### Authentication Flow

Authentication happens in two stages:

1. **Device Connection** - App token validates the Android app, device_id identifies the device
2. **User Login** - After WebSocket is established, user authenticates via `USER_LOGIN` message

#### Stage 1: Device Connection

The device connects with:
- `app_token` - Hardcoded in the Android app, validates against server config
- `device_id` - Unique hardware identifier

**Connection responses:**
- `401 Unauthorized` - Invalid or missing app token
- `403 Forbidden` - Device is PENDING approval or REJECTED
- `403 Forbidden` - Device has no tenant assigned
- `101 Switching Protocols` - Success, WebSocket established

**New devices** are auto-registered with PENDING status and must be approved via admin panel.

#### Stage 2: User Login

After connection, send `USER_LOGIN` to authenticate the user:

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

Response:
```json
{
  "id": "124",
  "type": "USER_LOGIN_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": true,
    "user_id": "65a1b2c3d4e5f6a7b8c9d0e5",
    "user_name": "Worker One",
    "role": "WAREHOUSE_WORKER",
    "offline_hash": "abc123..."
  }
}
```

The `offline_hash` can be stored locally for offline authentication.

**Operations requiring user authentication:**
- `SYNC_REQUEST`
- `DOCUMENT_LOCK`
- `DOCUMENT_UNLOCK`
- `DOCUMENT_UPDATE`
- `DOCUMENT_COMPLETE`
- `PRODUCT_LOOKUP`

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
| timestamp | string | ISO 8601 timestamp |
| payload | object | Type-specific payload |

---

## Message Types

### Client → Server

| Type | Description | Requires User Auth |
|------|-------------|-------------------|
| `PING` | Keep-alive ping | No |
| `USER_LOGIN` | Authenticate user after connection | No |
| `ERROR_REPORT` | Report client-side error | No |
| `SYNC_REQUEST` | Request delta synchronization | **Yes** |
| `FULL_SYNC_REQUEST` | Request full data resync | **Yes** |
| `ACK` | Acknowledge received sync data | **Yes** |
| `DOCUMENT_LOCK` | Lock document for editing | **Yes** |
| `DOCUMENT_UNLOCK` | Release document lock | **Yes** |
| `DOCUMENT_UPDATE` | Update document lines | **Yes** |
| `DOCUMENT_COMPLETE` | Complete document processing | **Yes** |
| `PRODUCT_LOOKUP` | Search product by barcode | **Yes** |

### Server → Client

| Type | Description |
|------|-------------|
| `PONG` | Keep-alive pong response |
| `USER_LOGIN_RESULT` | User login result |
| `SYNC_DATA` | Synchronization data batch |
| `SYNC_COMPLETE` | Synchronization complete with cursors |
| `DOCUMENT_LOCK_RESULT` | Lock operation result |
| `DOCUMENT_COMPLETE_RESULT` | Document completion result |
| `PRODUCT_LOOKUP_RESULT` | Product barcode lookup result |
| `SERVER_ERROR` | Error response |
| `PUSH` | Real-time push notification |

---

## Protocol Messages

### USER_LOGIN

Authenticate user after WebSocket connection is established.

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

| Field | Type | Description |
|-------|------|-------------|
| login | string | User login (per-tenant) |
| password | string | User password |

**Server responds (success):**
```json
{
  "id": "124",
  "type": "USER_LOGIN_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": true,
    "user_id": "65a1b2c3d4e5f6a7b8c9d0e5",
    "user_name": "Worker One",
    "role": "WAREHOUSE_WORKER",
    "offline_hash": "abc123def456..."
  }
}
```

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

Keep-alive mechanism. Client sends PING, server responds with PONG.

**Client sends:**
```json
{
  "id": "123",
  "type": "PING",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": null
}
```

**Server responds:**
```json
{
  "id": "124",
  "type": "PONG",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": null
}
```

---

### SYNC_REQUEST

Request delta updates since last synchronization.

**Client sends:**
```json
{
  "id": "123",
  "type": "SYNC_REQUEST",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "entity_types": ["products", "clients", "warehouses", "documents", "users"],
    "cursors": {
      "products": "2024-01-01T10:00:00Z",
      "documents": "2024-01-01T11:00:00Z"
    }
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| entity_types | string[] | Types to sync: products, clients, warehouses, documents, users |
| cursors | object | Optional. Last sync timestamps per entity type |

**Server responds with SYNC_COMPLETE:**
```json
{
  "id": "124",
  "type": "SYNC_COMPLETE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "sync_id": "sync-abc123",
    "cursors": {
      "products": "2024-01-01T12:00:00Z",
      "clients": "2024-01-01T12:00:00Z",
      "warehouses": "2024-01-01T12:00:00Z",
      "documents": "2024-01-01T12:00:00Z",
      "users": "2024-01-01T12:00:00Z"
    }
  }
}
```

---

### ACK

Acknowledge successful receipt and storage of sync data.

**Client sends:**
```json
{
  "id": "125",
  "type": "ACK",
  "timestamp": "2024-01-01T12:00:01Z",
  "payload": {
    "sync_id": "sync-abc123",
    "cursors": {
      "products": "2024-01-01T12:00:00Z",
      "documents": "2024-01-01T12:00:00Z"
    }
  }
}
```

Server updates device sync cursors only after receiving ACK.

---

### DOCUMENT_LOCK

Lock a document for editing ("Take into work").

**Client sends:**
```json
{
  "id": "126",
  "type": "DOCUMENT_LOCK",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1"
  }
}
```

**Server responds:**
```json
{
  "id": "127",
  "type": "DOCUMENT_LOCK_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1",
    "success": true,
    "locked_by": "65a1b2c3d4e5f6a7b8c9d0e2"
  }
}
```

**Lock failure response:**
```json
{
  "id": "127",
  "type": "DOCUMENT_LOCK_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1",
    "success": false,
    "locked_by": "65a1b2c3d4e5f6a7b8c9d0e3",
    "error": "document is already locked"
  }
}
```

---

### DOCUMENT_UNLOCK

Release document lock.

**Client sends:**
```json
{
  "id": "128",
  "type": "DOCUMENT_UNLOCK",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1"
  }
}
```

---

### DOCUMENT_UPDATE

Update document lines (while document is locked).

**Client sends:**
```json
{
  "id": "129",
  "type": "DOCUMENT_UPDATE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1",
    "state": "IN_PROGRESS",
    "lines": [
      {
        "line_number": 1,
        "actual_quantity": 10.5,
        "batch_number": "BATCH001",
        "is_completed": true
      },
      {
        "line_number": 2,
        "actual_quantity": 5,
        "is_completed": true
      }
    ]
  }
}
```

---

### DOCUMENT_COMPLETE

Complete document processing. Document must be locked by the current user and in IN_PROGRESS state.

**Client sends:**
```json
{
  "id": "130",
  "type": "DOCUMENT_COMPLETE",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1"
  }
}
```

**Server responds (success):**
```json
{
  "id": "131",
  "type": "DOCUMENT_COMPLETE_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1",
    "success": true,
    "state": "COMPLETED",
    "completed_at": "2024-01-01T12:00:00Z",
    "version": 5
  }
}
```

**Server responds (error):**
```json
{
  "id": "131",
  "type": "DOCUMENT_COMPLETE_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "document_id": "65a1b2c3d4e5f6a7b8c9d0e1",
    "success": false,
    "error": "document must be locked by the current user"
  }
}
```

---

### PRODUCT_LOOKUP

Search for a product by barcode.

**Client sends:**
```json
{
  "id": "132",
  "type": "PRODUCT_LOOKUP",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "barcode": "4600000000001"
  }
}
```

**Server responds (found):**
```json
{
  "id": "133",
  "type": "PRODUCT_LOOKUP_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": true,
    "product": {
      "id": "65a1b2c3d4e5f6a7b8c9d0e5",
      "external_id": "PROD001",
      "name": "Product Name",
      "code": "SKU-001",
      "description": "Product description",
      "image_url": "https://example.com/image.jpg",
      "barcodes": [
        {
          "barcode": "4600000000001",
          "type": "EAN13",
          "is_primary": true
        }
      ],
      "batch_support": true
    }
  }
}
```

**Server responds (not found):**
```json
{
  "id": "133",
  "type": "PRODUCT_LOOKUP_RESULT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "success": false,
    "error": "product not found"
  }
}
```

---

### ERROR_REPORT

Report client-side errors for diagnostics.

**Client sends:**
```json
{
  "id": "130",
  "type": "ERROR_REPORT",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "error_type": "SYNC_FAILURE",
    "message": "Failed to save products to local database",
    "stack_trace": "...",
    "metadata": {
      "products_count": "150",
      "db_error": "SQLITE_FULL"
    }
  }
}
```

---

### SERVER_ERROR

Server error response.

**Server sends:**
```json
{
  "id": "131",
  "type": "SERVER_ERROR",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "code": "DOCUMENT_LOCKED",
    "message": "Document is locked by another user",
    "details": "User ID: 65a1b2c3d4e5f6a7b8c9d0e3"
  }
}
```

Common error codes:
- `INVALID_MESSAGE` - Malformed message
- `UNKNOWN_MESSAGE_TYPE` - Unsupported message type
- `INVALID_PAYLOAD` - Invalid payload format
- `NOT_AUTHENTICATED` - User login required for this operation
- `SYNC_ERROR` - Synchronization failed
- `DOCUMENT_LOCKED` - Document is locked
- `FORBIDDEN` - Permission denied
- `INTERNAL_ERROR` - Server error

---

### PUSH

Real-time push notifications from server.

**Server sends:**
```json
{
  "id": "132",
  "type": "PUSH",
  "timestamp": "2024-01-01T12:00:00Z",
  "payload": {
    "event": "document_updated",
    "entity_type": "documents",
    "entity_id": "65a1b2c3d4e5f6a7b8c9d0e1",
    "data": { ... }
  }
}
```

Push events:
- `document_updated` - Document was modified
- `document_locked` - Document was locked by another user
- `document_unlocked` - Document was unlocked
- `reference_updated` - Reference data changed

---

## Synchronization Flow

### Initial Sync (First Connection)

```
Client                          Server
   |                               |
   |--[WS connect: app_token]----->|  (device must be APPROVED)
   |<-----[101 Switching]----------|
   |                               |
   |------- USER_LOGIN ----------->|  (login + password)
   |<----- USER_LOGIN_RESULT ------|  (success + user info)
   |                               |
   |------- SYNC_REQUEST --------->|  (empty cursors)
   |                               |
   |<------ SYNC_COMPLETE ---------|  (full data + cursors)
   |                               |
   |----------- ACK -------------->|  (confirm cursors)
   |                               |
```

### Delta Sync (Reconnect)

```
Client                          Server
   |                               |
   |--[WS connect: app_token]----->|
   |<-----[101 Switching]----------|
   |                               |
   |------- USER_LOGIN ----------->|  (re-authenticate)
   |<----- USER_LOGIN_RESULT ------|
   |                               |
   |------- SYNC_REQUEST --------->|  (with last cursors)
   |                               |
   |<------ SYNC_COMPLETE ---------|  (delta only + new cursors)
   |                               |
   |----------- ACK -------------->|  (update cursors)
   |                               |
```

### Document Workflow

```
Client                          Server                    ERP
   |                               |                        |
   |------ DOCUMENT_LOCK --------->|                        |
   |                               |--- Block ERP sync ---->|
   |<---- LOCK_RESULT (ok) --------|                        |
   |                               |                        |
   |---- DOCUMENT_UPDATE --------->|                        |
   |---- DOCUMENT_UPDATE --------->|  (multiple updates)    |
   |                               |                        |
   |--- DOCUMENT_COMPLETE -------->|                        |
   |<-- COMPLETE_RESULT (ok) ------|                        |
   |                               |--- Notify completed -->|
   |                               |                        |
```

### Product Lookup Flow

```
Client                          Server
   |                               |
   |------ PRODUCT_LOOKUP -------->|
   |   {barcode: "4600000001"}     |
   |                               | Query by barcode
   |<---- PRODUCT_LOOKUP_RESULT ---|
   |   {success: true, product:{}} |
```

---

## Connection Lifecycle

1. **Connect** - Establish WebSocket with app_token + device_id
2. **User Login** - Authenticate user via USER_LOGIN message
3. **Initial Sync** - Request full or delta sync
4. **Work** - Lock documents, update, unlock
5. **Keep-Alive** - PING/PONG every 30 seconds
6. **Reconnect** - On disconnect, reconnect with same device_id (user must re-login)

### Full Connection Flow

```
Android App                          Server
    |                                   |
    |--[WS connect: app_token + device_id]-->|
    |                                   |
    |                        [Validate app_token]
    |                        [Check device status]
    |                                   |
    |<--------[403 Forbidden]-----------|  (if PENDING/REJECTED)
    |<--------[WebSocket established]---|  (if APPROVED)
    |                                   |
    |--[USER_LOGIN: login, password]--->|
    |                                   |
    |                        [Validate credentials]
    |                        [per-tenant user lookup]
    |                                   |
    |<--[USER_LOGIN_RESULT: user info]--|
    |                                   |
    |--[SYNC_REQUEST, etc.]------------>|  (now allowed)
```

### Timeouts

| Parameter | Default | Description |
|-----------|---------|-------------|
| Ping Interval | 30s | Client should send PING |
| Pong Wait | 60s | Max time to wait for PONG |
| Write Wait | 10s | Max time to write message |
| Max Message Size | 512KB | Maximum message size |

---

## Offline Handling

1. Store from device:
   - `app_token` - Hardcoded in app build
   - `device_id` - Unique hardware ID
   - `user_id`, `role`, `offline_hash` - After successful USER_LOGIN
2. Queue operations locally when disconnected
3. On reconnect:
   - Connect with app_token + device_id
   - Send USER_LOGIN to re-authenticate user
   - Request sync
4. Apply queued operations after sync
5. Handle conflicts (server wins for documents not locked by this device)
