# Changelog

Product changes are listed newest first. For current installation steps, see the [installation guide](SETUP_GUIDE.md).

## 2.8.1 — 2026-10-26

### Fixed

- Preserve password masking when applying shared field styling.
- Keep input behavior separate from visual styling.

## 2.8 — 2026-10-26

### Changed

- Introduce a graphite and blue theme with a shared resource palette.
- Add vector navigation icons and an explicit selected-tab state.
- Group settings and backup information into cards.
- Distinguish primary actions, disabled controls, and destructive actions.
- Align viewer, dialogs, date indicator, launcher icon, and notifications with the theme.
- Associate connection-field labels with their inputs for accessibility.

## 2.7.1 — 2026-10-26

### Changed

- Remove an unused settings accessor.
- Use the active coroutine context explicitly for cancellation checks.
- Correct stale descriptions of pagination and Windows startup behavior.

## 2.7 — 2026-10-26

### Fixed

- Resolve background backup failures caused by foreground-service start restrictions.
- Retain a standard progress notification without requiring a foreground service.
- Apply an execution budget across inventory, hashing, and transfers.
- Migrate previously stalled periodic work once, then retain the schedule.
- Recognize connected power when battery protection pauses charging.
- Record automatic and manual success timestamps separately.
- Distinguish general backup failures from local-network failures.

## 2.6.1 — 2026-10-25

### Fixed

- Keep the displayed photo until the next media item is ready, removing the fade-to-black transition.
- Retain video posters through preparation and media changes.
- Synchronize media labels and actions with the image currently displayed.

## 2.6 — 2026-10-24

### Added

- Horizontal swipe navigation between photos and videos.
- Adjacent-media API queries independent of gallery page offsets.
- Boundary handling without wrapping to the beginning or end.
- Keyboard and accessibility navigation while preserving video controls.

## 2.5 — 2026-10-24

### Added

- A temporary month/year landmark while scrolling.
- Year-only display during fast scrolling.

## 2.4 — 2026-10-24

### Improved

- Prioritize visible thumbnails, cancel obsolete requests, and deduplicate image work.
- Separate image traffic from metadata requests.
- Prepare thumbnails in the background on the server.
- Reduce JPEG decoding costs and coordinate preview generation with purge.

## 2.3 — 2026-10-24

### Changed

- Replace manual load-more controls with automatic pagination.

## 2.2 — 2026-10-24

### Changed

- Replace refresh buttons with pull-to-refresh and an animated indicator.

## 2.1 — 2026-10-24

### Added

- In-app backup progress, an active navigation indicator, and a silent notification.
- Shared progress counters derived from WorkManager state.

## 2.0 — 2026-10-24

### Added

- Permanent PC photo/video archives independent of phone deletion.
- Native Android gallery, image previews, and video playback.
- Verified SHA-256 transfers, content deduplication, and folder exclusions.
- Manual archive deletion with 30-day trash and restoration.
- Re-upload prevention for deliberately deleted content.
- Local authenticated API and PC-only administration.
- Repeatable imports that preserve source files.
- Windows lifecycle scripts and optional sign-in startup.

### Changed

- Rename the application to FCS Mobile.
- Replace inventory-based mirroring with an independent archive.
- Consolidate runtime data under a project-local directory.
- Remove unused UI assets and build configuration.
- Restore the Gradle wrapper and align Java/Kotlin build targets.
