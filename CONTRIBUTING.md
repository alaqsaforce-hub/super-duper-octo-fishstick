# Contributing to VoiceDock AI

VoiceDock AI is a floating voice assistant for Android — continuous Bangla/English dictation with draft recovery,
local punctuation, AI tidy-up, a personal knowledge base, read-only connectors, its own keyboard, a built-in offline
Whisper (whisper.cpp via JNI), an on-screen agent, and AI accounts (ChatGPT / OpenRouter sign-in).

Current version: 0.6.2 (debug/test build; real-device verification pending).

## How to contribute

1. **Fork the repo** and create a feature branch from `main`.
2. **Build & test** (requires the build machine's toolchain — see `project/v061/README.md` → "Build"):
   - JDK 17+, Android SDK 35 + build-tools, NDK 27.0.12077973, CMake 3.22.1
   - `./gradlew :app:testDebugUnitTest :app:assembleDebug`
3. **Make your change**, keeping:
   - Pure Kotlin modules (`voice/`, `text/`, `context/`, …) unit-tested (JVM/Robolectric).
   - Android glue thin; no new hardcoded credentials or API keys.
   - Room schema changes only via committed migrations (`app/schemas/`), never `fallbackToDestructiveMigration`.
4. **Run the full check** on the build machine: `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`.
5. **Open a pull request**. CI (GitHub Actions `android.yml`) runs tests + debug/release builds on every push/PR.

## Build environment

This repo cannot be fully compiled in a plain Java-11 sandbox. Verify on a machine with:
- JDK 17 or newer
- Android SDK platform 35, build-tools 35.0.0
- NDK 27.0.12077973 + CMake 3.22.1
- `local.properties` with `sdk.dir=<path>`

## Security

- Never commit API keys, OAuth client secrets, or a release keystore.
- User keys/tokens are stored encrypted (Android Keystore AES-GCM); the committed `keystore/debug.keystore` is
  debug-only and must never be used for a store release.
- Network calls go only to user-configured services, with consent.

## Releases

- Version code/name live in `project/v061/app/build.gradle.kts` (`defaultConfig`).
- The app is debug-signed only. For a Play Store release, generate your own keystore and configure
  `keystore.properties` / CI secrets (`VOICEDOCK_KEYSTORE_BASE64`, …).
- See `docs/PRIVACY.md`, `docs/LIMITATIONS.md`, and `docs/MANUAL-TESTS-v0.6.md` before releasing.
