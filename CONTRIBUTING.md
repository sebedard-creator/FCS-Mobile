# Contributing

[Documentation](docs/README.md) · [Architecture](architecture.md) · [Installation](SETUP_GUIDE.md)

Use a source checkout and generate your own environment, configuration, and signing keys. Keep development data separate from any library you rely on.

## Development setup

Follow the [installation guide](SETUP_GUIDE.md) for Python, the Android SDK, JDK 17, and project-local build caches. Server and Android dependencies are declared in `requirements.txt` and the Gradle build files.

The server is supported on Windows. Current path validation requires storage outside `C:`. Android requires API 31 or later and compiles against SDK 34. Use the included Gradle wrapper.

## Validate changes

### Server

From the project folder after running `setup.bat`:

```powershell
.\.env\Scripts\python.exe -B -m unittest discover -s tests -v
```

The tests create isolated data under `data/tests/`. They cover verified uploads, duplicate content, independent archives, trash, retention, import, ordering, neighbor queries, real HTTP transfers, previews, concurrency, and storage paths.

For manual server tests, use a separate source checkout and library. The production entry point binds port 5001 exclusively; stop an instance before starting another on that port.

### Android

Configure the JDK, SDK, cache, and temporary paths as described in [Build the Android app](SETUP_GUIDE.md#4-build-the-android-app), then run:

```powershell
.\gradlew.bat --no-daemon --console=plain assembleDebug lintDebug
```

Build output is `app/build/outputs/apk/debug/app-debug.apk`. Keep the signing key for repeated updates to the same development installation. Never commit that key.

Device checks should cover gallery and trash loading, refresh, pagination, date navigation, image/video swipes, exclusions, permission handling, and offline behavior. For backup changes, verify a naturally scheduled run while the app is in the background, suspension when power or local Wi-Fi is lost, progress indicators, and separate automatic/manual success dates.

## Behavior to preserve

- Missing phone files never remove archived media.
- The app never deletes the phone’s originals.
- Transfers use connected power and local Wi-Fi without cellular fallback.
- Incomplete or corrupt uploads are not reported as verified archives.
- Folder exclusions do not remove existing archives.
- Manual archive deletion uses 30-day trash and preserves re-upload prevention.
- Gallery media is not persisted for offline access on the phone.
- Connection loss and activity stop clear displayed media and release resources.
- Password fields remain masked after styling.

## Documentation and assets

Write public documentation in English, using relative paths and reproducible commands. Describe supported behavior without machine-specific folders, credentials, device serials, or private deployment records.

The application interface is in French. Keep the README’s language notice and design credit at the bottom. Screenshots must exclude personal gallery content and visible connection keys; use the asset notes in [docs/assets](docs/assets/README.md).

Update [Architecture](architecture.md) when components or data handling change, [Installation](SETUP_GUIDE.md) when setup changes, and [Changelog](changelog.md) for user-visible fixes and features.

## Before publishing changes

Review the files selected by Git:

```powershell
git status --short
git diff --cached --stat
```

The `.gitignore` excludes runtime data, local configuration, environments, build outputs, credential files, and signing material. It does not untrack previously committed files or prevent forced additions. Review staged content before publishing, and keep private test captures and deployment notes under the ignored `data/` directory.

For a release, build from a clean checkout, complete relevant tests, and package only the intended artifacts. Runtime data, connection keys, and signing keys must not be bundled with an APK or source archive.
