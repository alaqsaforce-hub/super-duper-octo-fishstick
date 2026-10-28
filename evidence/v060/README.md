# VoiceDock AI v0.6.0 — preserved build/test evidence

Archive created before clearing generated workspace caches.

- `junit-xml/`: original Gradle JVM/Robolectric XML test results (29 suites, 157 tests, 0 failures/errors/skips).
- `html-report/`: generated Gradle unit-test HTML report.
- `apk/output-metadata.json`: APK build output metadata; the APK itself is stored separately in Drive.
- `docs/`: build information and test-summary documents from the authoritative source.
- `environment/setup.sh`: the session-specific bootstrap script used to download build tools; those regenerable SDK/dependency caches are not included. The script requires network access and uses current JDK 17 patch versions.

This is prior automated build evidence, not a new test run or a substitute for real-device QA. No device test, emulator test, logcat, or 16 KB-device runtime evidence is included.
