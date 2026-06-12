# Android TSD Application – Development Plan (MVP)

## 1. Overview

This document describes a complete and actionable development plan for an Android application for TSD (handheld terminals).  
The application is designed as an **offline-first client**, synchronized via WebSocket with an intermediate server, which acts as a master data source for TSD and is synchronized with an external ERP system.

The plan is based on fixed architectural, synchronization, and business rules agreed upfront to minimize rework during development.

---

## 2. System Architecture

### 2.1 Components

- **ERP System**
  - Primary business system
  - Provides documents and reference data
  - Receives document statuses and execution results

- **Intermediate Server**
  - Maintains a dedicated database for TSD
  - Synchronizes with ERP
  - Acts as a master copy for TSD devices
  - Manages synchronization cursors and WebSocket sessions

- **Android TSD Application**
  - Offline-first client
  - Local database (SQLite)
  - WebSocket connection to server
  - Background synchronization and outgoing operation queue

---

## 3. Data Synchronization Model

### 3.1 General Principles

- Synchronization is **delta-based**
- All timestamps are generated and managed **on the server**
- TSD never generates authoritative timestamps
- WebSocket delivery is **at-least-once**, without guaranteed re-delivery

### 3.2 Delta Synchronization

- Each entity has `last_updated` (server time)
- Server tracks `last_sync_time` per TSD device
- `last_sync_time` is persisted **only after ACK is received**
- ACK confirms **network delivery only**, not local DB persistence

### 3.3 Full Synchronization

- TSD can explicitly request a **full resync**
- Used in case of local failures or data inconsistency
- Server sends full datasets instead of deltas

---

## 4. Local Data Model (TSD)

### 4.1 Reference Data

- **Products**
  - ~5,000 items
  - Multiple barcodes per product
  - Batch / lot support
  - GS1 DataMatrix / QR codes

- **Clients**
  - ~1,000 records

- **Warehouses**
  - Mixed model:
    - Non-addressed warehouses
    - Addressed warehouses: `warehouse → row → shelf`

---

### 4.2 Documents

- Incoming Receipt
- Outgoing Shipment
- Inventory (Stocktaking)

Expected volume:
- ~500 documents per type
- Up to 100 lines per document

---

### 4.3 Service Tables

- Users
- Roles and permissions
- Document states
- Outgoing operation queue
- Synchronization state
- Diagnostic error logs

---

## 5. Document Lifecycle (TSD)

### 5.1 Document Stages and States

A document moves through three stages — **collect → pack → deliver** — each with
a start state (idle, ERP-owned), an in-process state (locked by a worker), and a
done state, plus two terminal states.

| Stage | Start | In-process | Done |
|-------|-------|------------|------|
| collect | Loaded | Collecting | Collected |
| pack | Pack | Packing | Packed |
| deliver | Delivery | Delivering | Delivered |

Terminal states: **Sent** (confirmed by ERP) and **Error / Conflict**.

> The original MVP scope used a single in-process stage (Loaded → In progress →
> Completed → Sent). It was superseded by the three-stage model above. See
> [sync-protocol.md](sync-protocol.md) and
> [backend-spec.md](backend-spec.md) for the implemented state machine.

### 5.2 Stage Locking

- Explicit user action: **“Take into work”** acquires a stage lock.
- After this action:
  - TSD becomes the source of truth for the document
  - Server blocks incoming ERP updates (`erp_sync_blocked`)
  - The document enters the stage's in-process state
- A worker can pause a stage (release the lock, keep the in-process state) or
  unlock it (revert to the start state). Admins can force-release a lock; see
  [ownership-model-plan.md](ownership-model-plan.md).

---

## 6. Users and Roles

### 6.1 Authentication

- Login + password (server-side validation)
- Offline login allowed for previously authenticated users

### 6.2 Roles (MVP)

#### Collector
- Works with inventory and shipment documents from ERP
- Inputs actual stock levels via barcode scanning
- Can view reference data
- Cannot create or edit documents outside assigned work

#### Courier
- Picks up and delivers prepared boxes
- Confirms pickup and delivery offline-capable
- Cannot edit document lines

#### Administrator
- Full access
- Can view the in-app Debug Journal (see [CLAUDE.md](../CLAUDE.md))

---

## 7. Business Scenarios

### 7.1 Inventory (Stocktaking)

- Document received from ERP
- Scan-driven data input
- Batch handling
- Addressed storage support
- Completion and result submission

### 7.2 Order Picking

- Work with shipment documents
- Barcode scanning
- Quantity adjustment
- Offline execution allowed
- Automatic sync on reconnect

### 7.3 Goods Receipt

- Based on ERP documents
- Batch registration
- Placement into shelves

---

## 8. Offline Mode and Reliability

- Full offline operation supported
- Local persistence of all actions
- Automatic synchronization on reconnect
- WebSocket connection is optional for active work
- UI indicators:
  - Online / Offline status
  - Timestamp of last successful sync

---

## 9. Scanning and UX Principles

- Scan-driven workflow
- Automatic barcode resolution
- User selection when ambiguity cannot be resolved
- Large controls, minimal navigation steps
- Hardware scanner button support

---

## 10. Data Flow: TSD → Server

TSD sends:
- Document changes (lines, quantities, batches, locations)
- Document lifecycle events
- Newly created documents
- Diagnostic application errors

(Not included in MVP: user activity audit logs)

---

## 11. Application Updates

- Distribution via Google Play
- Server accepts all versions in MVP
- No forced update logic in initial release

---

## 12. MVP Scope Exclusions

Explicitly excluded from MVP:
- Label printing
- Camera / photo capture
- PIN or electronic signatures
- Advanced analytics and reports

---

## 13. Technical Stack (Recommended)

- Kotlin
- MVVM architecture
- Room (SQLite)
- WorkManager
- Retrofit + OkHttp
- WebSocket (OkHttp)
- Dependency Injection: Hilt
- UI: Jetpack Compose

---

## 14. Status

MVP shipped. This document is kept as a frozen product-spec reference for
scope, business scenarios, roles, and offline-first requirements. Current
implementation details (layer structure, message catalog, debug journal) live
in [CLAUDE.md](../CLAUDE.md), [sync-protocol.md](sync-protocol.md), and [backend-spec.md](backend-spec.md).
