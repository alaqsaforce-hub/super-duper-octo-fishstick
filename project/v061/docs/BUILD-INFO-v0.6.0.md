# VoiceDock AI v0.6.0 — build information

**Status:** Debug APK assembled and JVM tests passed on 2026-10-28. Installation and device behaviour have not been tested.

## Artifact

- File: `VoiceDock-AI-v0.6.0-debug.apk` (24,275,299 bytes)
- SHA-256: `04a478c8f74ef8c9a84f0a9314f521ccc3a45cd2f880813aeddb85cac5970b1f`
- Application ID: `com.voicedock.ai`; version 0.6.0, version code 6.
- Android 8.0+ (API 26+), **arm64-v8a** only. The Explain screen screenshot feature requires Android 14+ (API 34+).
- Signed with the supplied **debug** key, not a production release key. APK Signature Scheme v2 verification passed.
- Signing-certificate SHA-256: `B581CBB837E3016A1C11B9F00A595903E22CE9916D0976826786577369A443D1`.
- The provided v0.5.0 APK has the same application ID and signing certificate; this supports an in-place upgrade, but an actual install/upgrade was not tested. Do not uninstall the old app if its local data matters; uninstalling can delete it.
- The APK ZIP and both packaged arm64 ELF libraries passed 16 KB alignment checks. This is a structural check, not a substitute for testing on a 16 KB-page device.

## Build and tests

The source came from the latest versioned Drive archive `VoiceDock-AI-v0.6.0-source.zip` (SHA-256 `c83cc4dc545f454ca5644cdf2c321ce8e1175a3ed42d376363b9f863058e3273`) and was continued and fixed here. The supplemental v0.6 patch's project files matched that archive.

Built with JDK 17, Gradle wrapper 8.11.1, Android SDK 35 / build-tools 35.0.0, NDK 27.0.12077973 and CMake 3.22.1. Native CMake opts in to flexible page sizes.

```bash
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug
```

Result: **157 tests passed; 0 failed, 0 errors, 0 skipped** across 29 JVM/Robolectric suites. See `TEST-RESULTS-v0.6.0.md`. No physical Android device or emulator was available. Run `MANUAL-TESTS-v0.6.md`, especially upgrade, microphone, overlays, accessibility, screen capture, local/remote provider configuration, and app-specific/OEM cases.

## Important privacy limitations

- Screen sensitivity detection is heuristic. Android accessibility metadata cannot reliably identify every secure window or secret. Disable screen-context consent in sensitive apps and keep sensitive apps on the blocklist.
- Explain screen requires both screen-context and screenshot consent, Android 14+ per-window capture, a Gemini key, and cloud consent. When capture fails it does not fall back to sending text.
- The optional, user-started agent can read screens and send permitted content to its selected AI provider; its sensitive-screen checks are not a complete guarantee.
- A debug key is public and unsuitable for a Play Store or production release. A separately secured release signing process and real-device QA are required.
