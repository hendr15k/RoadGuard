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
  third signer.
- CI restores the same keystore from the `ROADGUARD_KEYSTORE_BASE64` secret and
  verifies the resulting certificate in the build log.

**One-time migration:** everything up to and including `v1.0.64` was signed with
a debug key (locally `eb3b6b03…`, on CI a per-run key such as `c0ce2a3f…`), so
installing the first key-signed build needs one `adb uninstall` — app data is
lost that once. From then on updates install in place.

## License

MIT
