# JARVIS Android Assistant — initial project source

This project is a real Android application foundation, not a movie-level autonomous system. It includes:
- Online AI chat through a backend endpoint (no provider secret embedded in the APK).
- Hindi speech recognition via Android's available speech-recognition provider.
- Hindi text-to-speech where the device has a suitable voice installed.
- Locally stored chat history.
- App launching, Android settings shortcuts, and optional system navigation actions.
- Optional notification listener, activated only after the user enables notification access.
- A GitHub Actions workflow to build a debug APK.

## Important limitations
- This is not yet a fully tested release. Build it in Android Studio or run the included GitHub Actions workflow first.
- The backend URL is inherited from the earlier FARIS prototype. Its availability and response schema must be verified; it expects `POST {"message":"..."}` and returns `{"reply":"..."}`.
- Always-on wake-word listening is not included. Android restricts persistent microphone use, especially from the background; a foreground service and device-specific testing are needed.
- Android does not allow an ordinary app unrestricted control of every app or every system setting. Accessibility and notification access are optional and must be enabled manually by the phone owner.
- Notification contents may be sensitive and are stored locally in app preferences. Avoid enabling notification access on shared devices.
- The app does not place API keys in client code. Keep AI provider credentials on the backend only.

## Build
Open the project in a recent Android Studio, sync Gradle, and build `app` > `assembleDebug`. Or push the project to a GitHub repository and use Actions > Build JARVIS Android APK.

## Security
Never paste API keys into Kotlin source or commit them. Add provider keys only to the backend's secret store.
