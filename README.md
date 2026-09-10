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

`assembleDebug` is what gets attached to a release, which has two consequences
worth knowing before shipping:

- CI has no signing key, so it signs with the **runner's** temporary debug
  keystore. That certificate differs from the one the locally built APKs use,
  and Android refuses to install a package whose signer changed — so a
  CI-produced APK cannot update an already installed app. Release assets must
  therefore be built locally (`./gradlew assembleDebug`) with the keystore in
  `~/.android/debug.keystore`; the pre-`vv1.0.55` releases came from that path.
- The debug build is `android:debuggable`. Shipping a release/CI signing key is
  the actual fix for both points.

## License

MIT
