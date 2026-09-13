# RoadGuard

Driving Safety Assistant for Android

## Features

- **Real-time Camera Preview** - Live camera feed with visual overlay
- **Lane Departure Warning** - Detects when vehicle drifts from lane
- **Forward Collision Warning** - Monitors distance to vehicle ahead
- **Audio & Vibration Alerts** - Immediate feedback for dangerous situations
- **Customizable Settings** - Adjust warning sensitivity

## Screenshots

*(Add screenshots here)*

## Requirements

- Android 8.0 (API 26) or higher
- Camera permission required

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- CameraX
- ML Kit (Lane & Object Detection)
- Hilt (Dependency Injection)
- MVVM + Clean Architecture

## Build

```bash
./gradlew assembleDebug
```

## Project Structure

```
app/src/main/java/com/roadguard/app/
├── data/repository/       # Data layer
├── domain/
│   ├── model/            # Domain models
│   └── usecase/          # Business logic
└── ui/
    ├── components/       # Reusable UI components
    ├── screens/          # Screen composables
    └── theme/            # App theming
```

## Permissions

- `CAMERA` - Required for road monitoring
- `VIBRATE` - For haptic feedback alerts

## Release builds

`assembleDebug` is what gets attached to a release. The signer is the app's
identity — Android refuses to install a package whose signer changed, so every
variant (debug and release, local and CI) must be signed with the SAME key.

RoadGuard therefore has its own keystore, separate from the shared
`~/.android/debug.keystore`:

- Host location: `/root/roadguard-signing/roadguard-release.jks`
  (alias `roadguard`, passwords in `credentials.env`, mode 600 — never in git).
- Local builds load it through the wrapper, which is the supported entry point:

  ```bash
  tools/roadguard-gradle.sh :app:testDebugUnitTest :app:assembleDebug --offline
  tools/roadguard-gradle.sh :app:assembleRelease --offline
  ```

  A plain `./gradlew assembleRelease` without the four
  `ROADGUARD_*` environment variables fails on purpose rather than shipping a
  third signer. The guard covers every packaging task (`packageDebug`,
  `packageRelease`, `package*Bundle`, `package*UniversalApk`, `assemble*`,
  `bundle*` — anything that turns into an APK depends on one of them), so the
  debug build cannot silently revert to the host's debug keystore either. Unit
  tests need no key.
- CI restores the same keystore from the `ROADGUARD_KEYSTORE_BASE64` secret and
  asserts the resulting certificate against the expected fingerprint, so a
  mis-set secret fails the run instead of publishing.
- **CI does not publish the release** — it gates (tests, signed build, signer
  assertion) and uploads the APK as a workflow artifact. Publishing happens on
  the build host, which also compares the uploaded asset's digest, re-verifies
  the signer on the DOWNLOADED file and installs it. Two publishers conflicted:
  the workflow token cannot update a release created by the host, and that
  failure turned CI red on infrastructure instead of on the artifact.

**Known, unchanged:** the released artifact is `app-debug.apk`, i.e. the debug
variant and therefore `android:debuggable`. That is deliberate — the debug build
is the one the lane pipeline is tested on device — but it is not a production
hardening state; moving the release asset to `assembleRelease` is still open.

**One-time migration:** everything up to and including `v1.0.64` was signed with
a debug key (locally `eb3b6b03…`, on CI a per-run key such as `c0ce2a3f…`), so
installing the first key-signed build needs one `adb uninstall` — app data is
lost that once. From then on updates install in place.

## License

MIT
