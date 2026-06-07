# Android Plan — Document Notes & Per-Line Worker Notes/Photos

Client-side plan for three backend features now live in `~/projects/pick` (see that
repo's `docs/erp-api.md` and `docs/websocket-protocol.md`). This document is the
implementation blueprint for PickApp; every section lists exact files + line
anchors and mirrors an existing pattern in the codebase.

## 1. Scope

| # | Feature | Direction | App work |
|---|---------|-----------|----------|
| A | **Document-level note** | ERP → device, **read-only** | Display only. Data already syncs into `DocumentEntity.notes`. |
| B | **Per-line worker note** | Device → server (and back) | Display + edit + **send** in `DOCUMENT_UPDATE`. Storage already exists; outbound + UI missing. |
| C | **Per-line photo** (one per line) | Device → server (HTTP) | Capture, local cache, upload via signed URL, show `has_photo`. Net-new. |

**Backend contract recap (already shipped):**
- `DocumentDto.notes` — document-level note, ERP-owned, read-only. Already ingested.
- `DocumentLineDto.notes` — now **worker-owned** (server preserves it across ERP re-imports; ERP can no longer push it). Sent back via `DOCUMENT_UPDATE` as `lines[].notes`, **full-state** (echo the current note on every line update or the server clears it).
- `DocumentLineDto.has_photo` (**new field**) — server-set marker that a photo exists for the line.
- New WS action `LINE_PHOTO_UPLOAD_URL` → `LINE_PHOTO_UPLOAD_URL_RESULT` returns `{ upload_url, expires_at }`.
- Upload: `POST <upload_url>` with **raw image bytes**, `Content-Type: image/jpeg`, ≤ **2 MB**. The signed token in the URL query is the only auth (no bearer). `200 OK` on success; the server stores the photo and flips `has_photo`.

---

## 2. Feature A — Document-level note (read-only)

`DocumentEntity.notes` (`DocumentEntity.kt:49`), `Document.notes` (`Document.kt:14`),
DTO (`DocumentDto.kt:38`) and mapper (`DocumentMapper.kt:28,80`) already carry it.
**Only the UI is missing.**

1. `presentation/document/DocumentDetailScreen.kt` — `DocumentHeaderCard` (def `:588`, params `:589-599`, body renders client/warehouse `:623-650`):
   - Add a `notes: String?` parameter and render it below the warehouse block when non-blank (a labeled, muted text block; reuse the existing header text styles).
   - Pass it at the call site (`:430-441`): `notes = uiState.document?.notes`.
2. Localized label in `res/values/strings.xml` + `values-uk/strings.xml` (e.g. `document_note_label`).

No data, DTO, or sync changes. Estimated: ~1 file.

---

## 3. Feature B — Per-line worker note (display + edit + send)

Storage, DTO, mapper, domain, and the inbound sync-merge **already preserve `notes`**
(`DocumentLineEntity.notes:72`, `DocumentLineDao.updateLineNotes:101` already sets
`is_dirty=1`, `mergeDocumentLines` preserves it `SyncOrchestrator.kt:2508,2519`,
`DocumentRepositoryImpl.updateLine` already accepts a `notes` arg `:134-149`). Two gaps:
**(1) the UI never edits it, (2) the outbound `DOCUMENT_UPDATE` never sends it.**

### 3.1 Outbound — send `notes` in `DOCUMENT_UPDATE`

The line-update wire model omits notes. Add it in three places:

1. `data/remote/websocket/SyncMessage.kt:691` — add `val notes: String? = null` to `DocumentLineUpdate`.
2. `data/remote/websocket/MessageParser.kt:227-241` — in the line-serialize loop add `line.notes?.let { addProperty(FIELD_NOTES, it) }` (add `private const val FIELD_NOTES = "notes"` alongside the field consts at `:48-54`).
   - **Decision:** send notes as `null`-omitted vs always-present. Because the field is **full-state** server-side, prefer always emitting it (even empty string) so clearing a note propagates. If a line ever legitimately needs "leave note untouched", that's not supported by the protocol — always send the line's current note.
3. `data/sync/SyncOrchestrator.kt` — map `line.notes` into `DocumentLineUpdate` at **both** build sites: `performDocumentSync` (`:1190-1197`) and `resyncDirtyDocuments` (`:2836-2843`).

> **Full-state caveat:** every `DOCUMENT_UPDATE` for a line now overwrites the server's
> note. The app already sends the full current quantity/batch/completed state per line,
> so include the current `notes` the same way. A line update that omits `notes` will
> blank the note on the server.

### 3.2 UI — edit the note

1. `presentation/document/DocumentLineRow.kt` — in `LineCardContent` (`:147-309`), after the quantity row (`:306`) add a single-line `OutlinedTextField` (note), gated by `canEdit`. Debounce/commit on focus-loss or a confirm action; emit a new callback `onNoteChange(text)`.
2. `presentation/document/DocumentLineRow.kt:62-143` — thread the `onNoteChange` callback through `DocumentLineRow`.
3. `presentation/document/DocumentDetailScreen.kt:459-479` — wire `onNoteChange = { viewModel.updateLineNote(line.id, it) }` next to the existing `onQuantityChange`/`onToggleCompleted`.
4. `presentation/document/DocumentDetailViewModel.kt` — add `updateLineNote(lineId, text)` modeled on `updateLineQuantity` (`:461-507`): optimistic state update → `documentRepository.updateLine(lineId, …, notes = text)` (the `notes` param already exists at `DocumentRepositoryImpl.kt:143`, currently always `null`). Saving sets `is_dirty=1`, and the debounced `scheduleDocumentSync` path picks it up.
5. Gating: edit only when `DocumentDetailUiState.canEditLines` (`:104-105`).

### 3.3 Make a note-only edit re-arm dirty (sync correctness)

`mergeDocumentLines` `serverBehind` comparison (`SyncOrchestrator.kt:2512-2514`) does
**not** currently include `notes`. For the non-dirty in-process branch this is fine
because edits go through `updateLineNotes` which sets `is_dirty=1` directly. **No
change required**, but verify a note edit followed by an inbound sync (no version bump)
does not get reverted — the `local.isDirty` branch (`:2508`) already protects it.

Estimated: ~6 files, no schema change.

---

## 4. Feature C — Per-line photo (capture → upload → marker)

Net-new. No photo capture, no `has_photo` column, no HTTP upload, no CAMERA permission
exist today. Break into four parts.

### 4.1 `has_photo` field (inbound marker + display)

`has_photo` tells the UI a photo exists on the server (set by our own upload, or by
another device). The **image bytes are not synced to the device** — the server has no
device-facing download endpoint — so the device shows its own local cache when present,
otherwise just a "photo attached" indicator.

1. `data/remote/dto/DocumentLineDto.kt` — add `@SerializedName("has_photo") val hasPhoto: Boolean = false`.
2. `data/local/database/entity/DocumentLineEntity.kt` — add `val hasPhoto: Boolean = false` **and** a **local-only** `val photoPath: String? = null` (device cache path; never sent to/from server, never overwritten by merge).
3. Room migration — **bump version 15 → 16** (`AppDatabase.kt:51`), add `MIGRATION_15_16` (template: `MIGRATION_14_15` at `:253-258`) with `ALTER TABLE document_lines ADD COLUMN has_photo INTEGER NOT NULL DEFAULT 0` and `... ADD COLUMN photo_path TEXT`, register it in `DatabaseModule.kt:39-49`, and let the schema export (`app/schemas/…/16.json`) regenerate.
4. Domain `DocumentLine.kt` — add `hasPhoto: Boolean`, `photoPath: String?`.
5. `data/mapper/DocumentMapper.kt` — thread `hasPhoto` through the three line blocks (`toLineEntity:43-64`, `DocumentLineEntity.toDomain:122-143`, `DocumentLine.toEntity:145-166`). `photoPath` is local-only: carry it entity↔domain but **never** populate it from the DTO.
6. `data/sync/SyncOrchestrator.kt:2500-2525` — in `mergeDocumentLines`, take `hasPhoto` from the server (it's server-authoritative) but **preserve the local `photoPath`** in both the `isDirty` and `workerInProcess` branches (`photoPath = local.photoPath`). Do not let a server line wipe the local cached image path.

### 4.2 Capture

**Decision (recommended): `ActivityResultContracts.TakePicture` + FileProvider**, not a
custom CameraX `ImageCapture`. It reuses the system camera (no CAMERA permission needed
when the system camera app handles capture), needs far less code, and the existing
FileProvider is already declared (`AndroidManifest.xml:31-39`, authority
`${applicationId}.fileprovider`). The in-app CameraX path (`core/scanner/CameraScannerManager.kt`)
is barcode-only (`ImageAnalysis`, no `ImageCapture`) — extending it is the heavier option.

1. Create the capture target file in `cacheDir` (or `filesDir`) keyed by `documentId_lineNumber.jpg`; get a content URI via FileProvider.
2. From `DocumentLineRow` add a "take photo" `IconButton` (gated by `canEditLines`) → emits `onTakePhoto(line)`.
3. In the screen/VM, launch `rememberLauncherForActivityResult(TakePicture())` with the FileProvider URI. On success: **downscale + JPEG-compress to ≤ 2 MB** (target longest edge ~1600px, quality ~80) — add a small `core/util/ImageCompressor` (BitmapFactory `inSampleSize` + `Bitmap.compress`). Write the compressed bytes back to the cache file.
4. Persist `photoPath` on the line (`is_dirty` **not** required for photoPath alone — it isn't synced) and enqueue an upload (4.3). Show a thumbnail via the existing `ProductImageView`/`GlideImage` infra (`DocumentLineRow.kt:456-493`) and `FullScreenImagePreview` (`:358-452`) for tap-to-zoom.
5. If you instead choose in-app CameraX capture: add `<uses-permission android:name="android.permission.CAMERA"/>` + `<uses-feature .../>` to the manifest (currently only INTERNET/ACCESS_NETWORK_STATE `:6-7`) and a runtime-permission flow (none exists today).

### 4.3 Upload (WS-issued signed URL → HTTP POST)

There is **no existing "request URL over WS then act on it" pattern** in the app — model
the new request/result pair on `BoxLookup`/`BoxLookupResult` (`SyncMessage.kt:492-508`)
and the `sendAndAwait` correlation flow (`WebSocketManager.kt:467-502`).

**WS message plumbing:**
1. `SyncMessage.kt:8-72` — add `LINE_PHOTO_UPLOAD_URL`, `LINE_PHOTO_UPLOAD_URL_RESULT` to `MessageType`.
2. `SyncMessage.kt` — add request + result data classes:
   ```
   data class LinePhotoUploadUrl(override id, override timestamp,
       val documentId: String, val lineNumber: Int) : type = LINE_PHOTO_UPLOAD_URL
   data class LinePhotoUploadUrlResult(override id, override timestamp,
       val success: Boolean, val documentId: String?, val lineNumber: Int?,
       val uploadUrl: String?, val expiresAt: Long?, val error: String?) : type = …_RESULT
   ```
3. `MessageParser.kt` — add a `parseLinePhotoUploadUrlResult` and wire it into the `parseMessage` dispatch (`:125-147`); add a `buildPayload` branch for the request (`:174-320`) emitting `document_id` + `line_number`; field const `FIELD_LINE_NUMBER` already exists (`:51`).
4. `WebSocketManager.kt` — add correlation for the new pair in `extractOutgoingCorrelationId` (`:507-518`) and `extractIncomingCorrelationId` (`:733-744`) so `sendAndAwait` resolves it (key on document_id + line_number, since multiple lines may be in flight).
5. `SyncOrchestrator.kt` — add `requestLinePhotoUploadUrl(documentId, lineNumber): Result<String>` using `sendAndAwait(...)` (model on the box-lookup send helpers around `:885`), returning the `uploadUrl`.

**HTTP upload helper:**
6. New `data/remote/LinePhotoUploader.kt` (Hilt-injected). Use a **dedicated bare `OkHttpClient`** (no `AuthInterceptor` — the URL token is the auth; do not attach a bearer). `POST` the absolute `uploadUrl` with `RequestBody.create("image/jpeg".toMediaType(), jpegBytes)`. The URL is absolute (server builds it from its public base) — do **not** concat with `Constants.Network.BASE_URL`.
   - Provide the client in `core/di/NetworkModule.kt` (mirror `:43-56`, but without interceptors) or build it inline in the uploader.
7. Map results: `200` → success; `401` (expired/invalid token → request a fresh URL and retry once); `413` (too large → re-compress smaller); `415` (wrong content-type); `400` (empty). Surface failures via a `DocumentDetailUiEvent.ToastMessage` (`:21-44`).

**On success:** mark the line `has_photo = true` locally (`DocumentLineDao` — add `updateLineHasPhoto(lineId, true)` next to `updateLineNotes:101`), keep `photoPath` as the local cache. The server's next sync will also report `has_photo=true`.

### 4.4 Offline + sequencing

- **Offline capture:** capture and compression work offline. The upload needs the WS
  online (to mint the signed URL) + HTTP. Persist captured-but-unuploaded photos
  (`photoPath` set, a local `photoUploaded=false` flag or reuse `OutgoingOperationEntity`)
  and have `SyncOrchestrator` drain them on reconnect — **request a fresh
  `LINE_PHOTO_UPLOAD_URL` per attempt** (tokens expire in ~5 min, so never cache the URL).
- **Sequencing (avoid the server marker race):** the backend's `has_photo` marker is
  best-effort — a `DOCUMENT_UPDATE` whose `ReplaceOne` overlaps the upload can transiently
  revert it (documented in the backend `CLAUDE.md`). To minimize this, **don't fire a
  `DOCUMENT_UPDATE` for the same line while its photo upload is in flight**; let the upload
  complete (200) first, then allow line edits to flush. The photo blob is never lost
  server-side, and `has_photo` self-heals on the line's next update.

Estimated: ~12-15 files + migration.

---

## 5. File checklist

**Feature A (doc note):** `DocumentDetailScreen.kt`, `strings.xml` (+uk).

**Feature B (line note):**
- `SyncMessage.kt:691`, `MessageParser.kt:48-54,227-241`, `SyncOrchestrator.kt:1190-1197,2836-2843` (send)
- `DocumentLineRow.kt`, `DocumentDetailScreen.kt:459-479`, `DocumentDetailViewModel.kt`, `strings.xml` (+uk) (edit UI)

**Feature C (photo):**
- DTO/entity/domain/mapper: `DocumentLineDto.kt`, `DocumentLineEntity.kt`, `DocumentLine.kt`, `DocumentMapper.kt`
- Room: `AppDatabase.kt:51` (+`MIGRATION_15_16`), `DatabaseModule.kt:39-49`, `app/schemas/.../16.json`
- DAO: `DocumentLineDao.kt` (`updateLineHasPhoto`)
- Sync merge: `SyncOrchestrator.kt:2500-2525`
- WS: `SyncMessage.kt`, `MessageParser.kt`, `WebSocketManager.kt:507-518,733-744`, `SyncOrchestrator.kt`
- HTTP: new `data/remote/LinePhotoUploader.kt`, `core/di/NetworkModule.kt`, new `core/util/ImageCompressor.kt`
- UI: `DocumentLineRow.kt`, `DocumentDetailScreen.kt`, `DocumentDetailViewModel.kt`, `DocumentDetailUiState.kt`, `DocumentDetailUiEvent.kt`
- Manifest (only if in-app CameraX capture is chosen): `AndroidManifest.xml`

---

## 6. Testing

- **Unit (`MessageParser`):** `DOCUMENT_UPDATE` serialization includes `lines[].notes`; round-trips empty string. New `LINE_PHOTO_UPLOAD_URL` serializes correctly and `..._RESULT` parses `upload_url`/`expires_at`/`error`.
- **Unit (`SyncOrchestrator.mergeDocumentLines`):** a server line with `has_photo=true` and no local `photoPath` keeps `hasPhoto=true`; a local dirty line preserves `photoPath` and `notes` against a stale server line; a server `has_photo=false` does not wipe a freshly-set local `photoPath`.
- **Unit (ViewModel):** `updateLineNote` sets the note + dirties the line; photo capture success sets `photoPath` and enqueues upload; upload 401 triggers one URL refresh + retry.
- **Instrumented:** Room migration 15 → 16 (existing migration tests pattern); FileProvider URI grant for TakePicture.
- **Manual:** offline capture → reconnect → auto-upload; 2 MB cap rejection path; document note renders for an ERP-set note.

---

## 7. Open decisions (confirm before building Feature C)

1. **Capture mechanism:** system camera via `TakePicture` (recommended, less code, may skip CAMERA permission) vs in-app CameraX `ImageCapture` (consistent in-app UX, needs permission + runtime flow). 
2. **Local photo retention:** keep the compressed JPEG in `cacheDir` (evictable) vs `filesDir` (durable). Recommended: `filesDir/line_photos/` so the worker can re-view after navigating away; prune on document delete/purge (hook the document-purge path in `SyncOrchestrator`).
3. **Offline upload queue:** dedicated `photoUploaded` flag on the line vs a row in `OutgoingOperationEntity`. Recommended: reuse the existing outgoing-operation queue if it generalizes; otherwise a simple flag + reconnect scan.
4. **Multiple photos:** backend is **one photo per line** (re-upload replaces). Keep the UI single-slot (replace on re-capture).

---

## 8. Compatibility notes

- Protocol is already `v2` (`WebSocketManager.buildWebSocketUrl:566-574`) and the
  server accepts `external_id` everywhere — no handshake changes.
- `supports_update_ack` is already negotiated (`USER_LOGIN_RESULT`) and used by
  `resyncDirtyDocuments` — line `notes` rides the same confirmed-update path, so a note
  edit is cleared from `is_dirty` only on a confirmed `DOCUMENT_UPDATE_RESULT`.
- Server tolerates older clients that don't send `notes`/photos; this is additive.
