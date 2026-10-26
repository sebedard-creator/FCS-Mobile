# Architecture

[Documentation](docs/README.md) · [Installation](SETUP_GUIDE.md) · [Contributing](CONTRIBUTING.md)

FCS Mobile consists of a Windows-hosted media archive and a native Android client. The supported workflow is one phone connecting to one local server over Wi-Fi.

## Components

| Component | Responsibility |
| --- | --- |
| `backup_service.py` | Server entry point, folder import, exclusive listener, housekeeping, and thumbnail warm-up |
| `fcs_mobile/server.py` | Flask API, authentication, local administration, and upload handling |
| `fcs_mobile/library.py` | SQLite catalog, verified file storage, deduplication, neighbors, trash, and imports |
| `fcs_mobile/previews.py` | Image and video derivatives |
| `fcs_mobile/paths.py` | Project-local data paths and storage validation |
| `MainActivity.kt` | Gallery, trash, backup status, settings, and folder selection |
| `ViewerActivity.kt`, `SwipeMediaFrame.kt` | Photo/video viewing and adjacent-media navigation |
| `ThumbnailLoader.kt`, `ScrollDateIndicator.kt` | Visible-image scheduling and date landmarks |
| `LocalApi.kt` | Authenticated, Wi-Fi-bound HTTP transport |
| `MediaScanner.kt`, `BackupWorker.kt` | Android media inventory, fingerprints, and background transfers |
| `AppSettings.kt` | Preferences and persistent scheduling |
| `BackupProgress.kt`, `BackupNotifications.kt` | Progress state and notifications |
| `AppStyle.kt` and Android resources | Shared palette and native component styling |

Android sources are under `app/src/main/java/com/fcsmobile/app/`. Server sources are under `fcs_mobile/`.

## Data flow

1. Android enumerates accessible MediaStore photos and videos and applies folder exclusions.
2. A local fingerprint cache avoids hashing unchanged files repeatedly.
3. The client checks batches of SHA-256 digests against the server.
4. Missing content is streamed to a temporary file, verified by size and digest, flushed, and moved into permanent storage before catalog insertion.
5. The gallery requests chronological metadata and derivatives. Video originals support HTTP range requests.

Phone paths are metadata, never server filesystem destinations. Identical content is deduplicated. A phone inventory does not delete archived content.

## Storage

Defaults are relative to the project:

| Path | Contents |
| --- | --- |
| `data/library/Originals/` | Content-addressed originals |
| `data/library/.fcs/` | Catalog, derivatives, and incoming files |
| `data/config/config.json` | Server configuration and generated connection key |
| `data/tmp/` | Process and upload temporary files |
| `data/logs/` | Standalone launcher output |

`backup_path` may be absolute or relative to `data/`. A complete library move must retain both `Originals` and `.fcs`. Current Windows storage validation rejects resolved paths on `C:`.

Archive deletion is logical for 30 days. Purge runs at startup and hourly while the server is running. It removes original bytes and derivatives while retaining deletion fingerprints to block automatic re-upload.

Mutations use a reentrant lock. Preview generation has per-media locks and two conversion slots; ready derivatives can be read without waiting for conversion. Purge coordinates with the media lock to prevent derivative recreation after deletion.

## HTTP API

Protocol version: **2**.

| Endpoint | Purpose |
| --- | --- |
| `GET /health` | Service identity and protocol, without credentials |
| `GET /api/status` | Authenticated library statistics |
| `POST /api/check` | Digest states without archive mutation |
| `POST /api/upload` | Multipart file and metadata, with verification |
| `GET /api/media?trash=0\|1&offset=0&limit=120` | Paginated gallery or trash |
| `GET /api/media/<id>/neighbors?trash=0\|1` | Immediate previous and next metadata |
| `GET /api/media/<id>/original` | Original bytes and range requests |
| `GET /api/media/<id>/thumbnail` or `/preview` | JPEG derivatives |
| `POST /api/media/<id>/trash` or `/restore` | Explicit archive actions |
| `GET /` and `POST /admin/config` | Loopback-only administration and CSRF-protected configuration |

API calls use `Authorization: Bearer …`. Source-network checks do not trust proxy headers. Responses use `Cache-Control: no-store`. The Android client accepts private IPv4 destinations, binds sockets to Wi-Fi, and disables proxies and redirects.

The transport is HTTP on the local network. HTTPS, public hosting, reverse-proxy deployment, and mobile-data access are outside the supported scope.

## Gallery and previews

Gallery ordering is `taken_at DESC, id ASC`; trash ordering is `deleted_at DESC, id ASC`. Neighbor queries anchor on the current item and date, independent of pagination offsets, with `null` at collection boundaries.

The Android grid loads 120 items per page and begins pagination before the end of the loaded collection. Pull-to-refresh resets the collection. Thumbnail requests prioritize visible cells, cancel work outside the viewing window, and use a separate dispatcher from metadata requests.

The memory image cache is capped at the smaller of 32 MiB and one eighth of the process budget. Screen stop or connection loss clears displayed media and memory caches. No persistent offline media cache is created.

Server derivatives are JPEG images up to 360 pixels for thumbnails and 2048 pixels for photo previews. Pillow handles images and EXIF orientation; FFmpeg extracts video frames. Background warm-up prepares missing thumbnails without holding the archive mutation lock.

The viewer retains the current image until its replacement is ready. Video posters cover preparation until the first rendered frame. One player is active at a time, and media changes cancel obsolete requests. Archive confirmation dialogs remain tied to the displayed item.

## Background work

Backups use a regular WorkManager `CoroutineWorker`, with a silent standard notification while running. The worker has a seven-minute execution budget, a non-waiting mutex, cancellation-aware loops, and centralized cleanup.

The unique periodic request runs at a requested 15-minute interval with charging and connected-network constraints. The worker additionally verifies local Wi-Fi and connected power. A battery charge limit can pause charging without removing eligibility when power remains connected.

A versioned schedule migration replaces an old request once, then subsequent scheduling calls retain the existing request. Manual requests use a separate unique work name.

Successful complete verification updates the common success timestamp and the timestamp for its automatic or manual origin in one preference edit. Progress is derived from WorkManager state rather than a persistent running flag.

## Windows lifecycle

Waitress serves the API through an exclusive Windows listener on port 5001. Only one instance may bind that listener.

`manage_service.ps1` manages setup, background launch, targeted stop, status, optional sign-in startup, and import. Batch files are entry points relative to their own folder. A process manager can alternatively run the project interpreter with the project as its working directory.

See [Contributing](CONTRIBUTING.md) for build and validation practices.
