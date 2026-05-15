# RoadGuard - Driving Safety Assistant

## Project Overview
- **Project Name**: RoadGuard
- **Type**: Android Native Application
- **Core Functionality**: Real-time road monitoring via camera to detect lane departures and insufficient following distance, providing audio/visual warnings to the driver.

## Technology Stack & Choices
- **Framework**: Android Native with Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **UI Framework**: Jetpack Compose with Material 3
- **Camera**: CameraX
- **ML/Detection**: ML Kit for lane detection and object detection (Firebase ML Kit)
- **Architecture**: MVVM with Clean Architecture layers
- **DI**: Hilt
- **Async**: Kotlin Coroutines + Flow
- **Permissions**: Accompanist Permissions

## Feature List
1. **Real-time Camera Preview** - Display live camera feed with overlay
2. **Lane Departure Warning** - Detect when vehicle drifts from lane and alert
3. **Forward Collision Warning** - Monitor distance to vehicle ahead and warn if too close
4. **Audio Alerts** - Sound notifications for warnings
5. **Visual Alerts** - On-screen warning indicators
6. **Settings Screen** - Configure warning sensitivities

## UI/UX Design Direction
- **Visual Style**: Material Design 3, dark theme optimized for driving
- **Color Scheme**: Dark background with high-contrast warning colors (red for danger, yellow for caution, green for safe)
- **Layout**: Single-screen main view with camera preview and overlay alerts, bottom sheet for settings
