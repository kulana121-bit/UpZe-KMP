# UpZe — Kotlin Multiplatform

UpZe music player rebuilt with **Kotlin Multiplatform + Compose Multiplatform**: one shared codebase (`composeApp/src/commonMain`) running on Android and iOS.

## Structure

- `composeApp/` — shared KMP module
  - `commonMain` — shared UI, sources, playback logic, lyrics
  - `androidMain` — Android playback (Media3), widgets, platform services
  - `iosMain` — iOS playback (AVFoundation), platform services
- `iosApp/` — the iOS host app (SwiftUI shell around the shared Compose UI)

## Identity (permanent)

- Android applicationId and iOS bundle ID are both **`com.ravi.musicspot`** and never change, so every release installs **over** the previous one — on Android via the permanent release signing key, and on iOS by re-signing the same bundle ID with the same method/Apple ID. User data and settings carry over on update.

## iOS build

The iOS app is built on a macOS runner by the `Build iOS IPA (unsigned)` GitHub Actions workflow: it links the `ComposeApp` framework (`:composeApp:linkReleaseFrameworkIosArm64`), builds the Xcode project without code signing, and packages `UpZe.ipa` as a workflow artifact. The IPA is unsigned — install it with a sideloading tool (AltStore / SideStore / Sideloadly) using your own Apple ID; see the install guide in the main repo: https://github.com/kulana121-bit/UpZe-music

## Android build

Open the project in Android Studio, or run `./gradlew :composeApp:assembleRelease`.
