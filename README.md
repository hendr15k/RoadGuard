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

## License

MIT
