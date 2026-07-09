# 📱 PickApp — Android Warehouse TSD Client

[![Kotlin](https://img.shields.io/badge/kotlin-1.9.20-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-green.svg?logo=android)](https://developer.android.com/jetpack/compose)
[![Hilt](https://img.shields.io/badge/DI-Hilt-orange.svg)](https://developer.android.com/training/dependency-injection/hilt-android)
[![Room](https://img.shields.io/badge/Database-Room--SQLite-cyan.svg)](https://developer.android.com/training/data-storage/room)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

PickApp is an advanced, enterprise-grade **Android Terminal Scanner Device (TSD)** application designed for modern warehouse environments. It facilitates seamless inventory management, order picking, box packing, and goods receipt. 

Operating as an **offline-first client**, PickApp maintains total productivity in low-connectivity areas by processing operations locally and synchronizing over a REST device API with an intermediate ERP-connected middleware server.

---

## 🚀 Key Features

*   **Offline-First Architecture**: All scanning, location mapping, and document edits are stored in a local Room database, ensuring zero downtime. Changes are safely queued via `OutgoingOperationEntity` and synced when online.
*   **Robust Concurrency & Locking (Ownership Model)**: Utilizes a sophisticated lock negotiation mechanism (`DOCUMENT_LOCK`, `cooperative force-release`, `silent re-lock` state-machine) preventing race conditions between ERP updates and active TSD collectors.
*   **Intelligent Barcode Resolution**: Supports high-throughput hardware barcode scanners and camera-based fallbacks (via CameraX & ML Kit) to automatically resolve barcodes, GS1 DataMatrix, and QR codes.
*   **Diagnostic Telemetry (Debug Journal)**: A rolling 7-day in-app journal tracks system events, transport status, and sync payloads, uploading them in batches via background WorkManager workers for tenant-wide administrative visibility.

---

## 🛠️ Technology Stack

*   **UI Framework**: Jetpack Compose (Material 3) with MVVM Architecture and reactive `StateFlow` bindings.
*   **Dependency Injection**: Hilt (Dagger) for modern, testable class structures.
*   **Local Storage**: Room Persistence Library (SQLite) and DataStore Preferences.
*   **Networking & Sync**: Retrofit 3 + OkHttp 5 for the REST device transport (full/delta sync, polling while a document is worked), and WorkManager for resilient background sync scheduling.
*   **Hardware Integration**: CameraX and Google ML Kit for automated barcode scanner resolution.

---

## 📂 Repository Structure

```
PickApp/
├── app/                           # Android Application Source Code
│   ├── src/main/java/ua/com/programmer/pick/
│   │   ├── core/                  # Dependency Injection (Hilt), Scanner integration, and Utilities
│   │   ├── data/                  # Repositories, Database (Room), REST transport, Sync Orchestrator
│   │   ├── domain/                # Pure Business Entities and Repository Interfaces
│   │   └── presentation/          # Jetpack Compose UI Screens (Auth, Docs, Profile, Settings, Debug)
│   └── src/test/ /androidTest/    # Unit Tests and Instrumented UI/Integration Tests
├── docs/                          # Project Documentation
│   ├── app-develop-plan.md        # MVP Product Specification & Frozen Business Scope
│   ├── backend-spec.md            # REST API Contracts & ERP Integration Guidelines
│   ├── ownership-model-plan.md    # Document Concurrency, Policy, & Bottleneck Analysis
│   └── sync-protocol.md           # REST Sync Message Envelope Schemas
├── CLAUDE.md                      # Developer Reference for Build, Test, and Formatting Commands
├── LICENSE                        # Project MIT License
└── README.md                      # Repository Entry Point (This Document)
```

---

## 📖 Documentation Index

For in-depth technical references and implementation details, check out the specialized guides in the [docs/](docs) directory:

1.  **[API Specification](docs/backend-spec.md)**  
    Defines the REST endpoints for user authentication, full/delta sync batching, database schemas, and external ERP integration contracts.
2.  **[Sync Protocol](docs/sync-protocol.md)**  
    The single source of truth for the message envelope schemas (`USER_LOGIN`, `STAGE_LOCK`, `DOCUMENT_UPDATE`, `SYNC_DATA`, `DEBUG_EVENT_BATCH`) and how each maps onto a REST `/device` endpoint.
3.  **[Document Ownership Model](docs/ownership-model-plan.md)**  
    A rolling analysis and formalization of who owns document data and when (ERP vs. Device), detailing CAS versioning, cooperative release negotiations, and edge-case mitigations.
4.  **[Product Development Plan](docs/app-develop-plan.md)**  
    The original development roadmap and frozen MVP spec representing business scenarios, offline principles, roles (Collector, Courier, Admin), and scope exclusions.
5.  **[Посібник інтегратора 1С](docs/integration/1c-integration.md)** (Ukrainian)  
    How an accounting system feeds the server: `external_id` contract, reference data, per-type capability flags, document fields and what each one changes on the terminal, e-excise (Е-Акциз) line barcodes, and result retrieval.
6.  **[Чекліст інтегратора](docs/integration/1c-checklist.md)** (Ukrainian)  
    Step-by-step launch checklist for a new 1C integration, including the e-excise acceptance tests.

---

## 💻 Developer Guide

For quick setup and local development, see also [CLAUDE.md](CLAUDE.md) for direct environment commands.

### Build Commands

To build the application, execute these commands in your terminal at the project root:

```bash
# Clean project build cache
./gradlew clean

# Build Debug APK
./gradlew :app:assembleDebug

# Build Release APK
./gradlew :app:assembleRelease
```

### Running Tests

Ensure high code quality and sync correctness by running the comprehensive test suite:

```bash
# Run all JVM unit tests
./gradlew test

# Run a specific unit test class
./gradlew test --tests "ua.com.programmer.pick.presentation.splash.SplashViewModelTest"

# Run instrumented UI/integration tests (requires an emulator/device)
./gradlew connectedAndroidTest
```

---

## 📄 License

This repository is licensed under the MIT License. See the [LICENSE](LICENSE) file for the full text.
