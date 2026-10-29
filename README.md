# super-duper-octo-fishstick / VoiceDock AI

VoiceDock AI — a floating voice assistant for Android. Continuous Bangla/English dictation with draft recovery, local
punctuation, AI tidy-up without invented facts, a personal knowledge base, read-only connectors (Google Drive/Gmail,
GitHub), its own phonetic Bengali keyboard, a built-in offline Whisper (whisper.cpp via JNI), an on-screen agent, and AI
accounts (ChatGPT / OpenRouter sign-in). Native Kotlin + Jetpack Compose.

**Current version: 0.6.2** (debug/test build; real-device verification pending).

## Where the app lives

The Android app is a Gradle project under `project/v061/` (an older preserved copy is under `project/v060/`).

| Path | What |
|------|------|
| `project/v061/` | VoiceDock AI v0.6.2 — active version (Kotlin, Compose, whisper.cpp JNI) |
| `project/v061/app/src/main/java/com/voicedock/ai/` | app source (voice, ai, agent, accounts, knowledge, connectors, overlay, ime, ui, …) |
| `project/v061/app/src/test/` | JVM/Robolectric unit tests (157 passing at v0.6.0) |
| `project/v061/docs/` | PROGRESS, LIMITATIONS (+manual checklist), PRIVACY, OAUTH-SETUP, LOCAL-AI, DB-SCHEMA, … |
| `project/v061/.github/workflows/android.yml` | CI: tests + debug/release APK builds on every push |

## Quick start (build machine)

Requires JDK 17+, Android SDK 35 + build-tools, NDK 27.0.12077973, CMake 3.22.1:

```bash
cd project/v061
echo "sdk.dir=/path/to/android-sdk" > local.properties
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.0.12077973" "cmake;3.22.1"
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (arm64-v8a only)
```

CI runs the same plus `:app:assembleRelease` on GitHub Actions (see `project/v061/.github/workflows/android.yml`).

## Docs

- `project/v061/README.md` — features, install, first run, security
- `project/v061/CONTRIBUTING.md` — how to contribute
- `project/v061/docs/PROGRESS.md` — version history & continuation notes
- `project/v061/docs/LIMITATIONS.md` — known limits + manual device test checklist
- `project/v061/docs/PRIVACY.md` — privacy & security model

## License

MIT — see `LICENSE`.
