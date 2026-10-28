# VoiceDock AI — Phase 1: inspection & plan

Date: 2026-10-24 · Target: Android 8.0+ (minSdk 26), target/compile SDK 35

## 1. Inspection results
- No existing VoiceDock code — new project in `~/voicedock-ai` (package `com.voicedock.ai`).
- Build toolchain available in the sandbox: JDK 21, Android SDK (platform 35, build-tools 34), Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28.
- No emulator (no /dev/kvm) → automated tests are JVM unit tests + Robolectric. Real speech, overlay and
  accessibility behaviour MUST be verified on a phone (checklist in `LIMITATIONS.md`).
- Library versions chosen from a proven build in the same environment (Compose BOM 2024.12.01, Room 2.6.1 on KSP1).

## 2. Architecture (Phase 2 scope)
```
ui/ (Compose: Home/setup, Dictation+Review, Drafts, Settings, Permissions&Privacy)
overlay/ OverlayService (FGS type=microphone) ─ bubble (View) + panel (ComposeView, NOT_FOCUSABLE)
voice/  SpeechEngine ← AndroidSpeechEngine (SpeechRecognizer, on-device on API 31+)
        DictationSession (pure state machine) ← DictationController (effects, ticker, persistence)
        TranscriptMerger (overlap/duplicate removal, partial→final replacement)
text/   LocalFormatter (Mode A: faithful local punctuation, bn/en)
insertion/ TextInserter → InsertAccessibilityService (focused field only, on Insert tap) → clipboard fallback
data/   Room AppDatabase v1 (dictation_drafts) + DraftStore
settings/ AppSettings + SettingsStore (SharedPreferences JSON), tr() bilingual labels
```
Dependency rule: `voice/`, `text/`, `insertion/InsertionMath` are pure Kotlin and unit-tested; Android glue is thin.

## 3. Dictation state machine
`IDLE → LISTENING ⇄ TEMP_PAUSE → PAUSED | WAITING | INTERRUPTED → LISTENING … → PROCESSING → REVIEW → INSERTED | SAVED`
- Android recognisers stop after each utterance/silence. Continuity = automatic restart after every final
  result or "no match"/"timeout" (SILENCE) error; BUSY/CLIENT errors restart after 400 ms with a fresh recogniser.
- Network/server/rate-limit errors: bounded retries with 1 s / 2 s / 4 s backoff, then INTERRUPTED with an honest message.
- Rapid-failure guard: 6 failures within 4 s → INTERRUPTED (prevents a restart loop draining battery).
- Long inactivity (default 5 min, configurable, can be disabled) → WAITING: mic off, text kept, one tap resumes.
- An unfinished partial is committed as a *provisional* segment when the recogniser ends without a final; a late
  final for the same utterance replaces it. Overlaps of ≥2 words at restart boundaries are removed; single-word
  repetitions ("very very", "ধীরে ধীরে") are kept.
- Every committed change is persisted immediately (Room) → crash/kill/restart restores the draft as PAUSED.
- Cancel never destroys text (kept as CANCELLED history). Clear first saves a CLEARED snapshot.

## 4. Phase plan
| Phase | Content | Status |
|---|---|---|
| 1 | Inspect, plan, risks | done (this doc) |
| 2 | App shell, permissions, floating bubble/panel, continuous dictation, persistent drafts, review/edit/copy/share, Accessibility insertion + clipboard fallback, debug APK | done — see PROGRESS.md |
| 2.5 | Engine detection (all phone speech services, Google first), Gemini speech engine, on-demand screen help (Gemini vision), Keystore key storage | done in v0.2.0 |
| 3 | More AI providers, Mode B structured writing, free-first fallback, usage tracking | next |
| 4 | Capture (share sheet, PROCESS_TEXT, documents) + Room FTS knowledge base + RAG | |
| 5 | Gmail / Drive / GitHub connectors (OAuth, read-only scopes) | |
| 6 | VoiceDock IME, OCR, advanced insertion | |
| 7 | Release hardening, signing, R8 | |

## 5. Risks
| Risk | Impact | Mitigation |
|---|---|---|
| Recogniser restarts may play a beep on some devices | Annoying during long dictation | Cannot be legitimately muted; documented; Phase 6 may add a streaming engine |
| On-device Bengali model missing | ON_DEVICE mode fails | AUTO falls back to online; ON_DEVICE shows honest error |
| Mixed Bangla+English | One recogniser language at a time | MIXED uses bn-BD; documented |
| Android 14 while-in-use mic rule | FGS can't start from background | Service always started from the visible Activity, then kept running while the bubble exists |
| OEM battery killers (Xiaomi, Oppo, …) | Service killed | Draft persisted per sentence; restored on next start |
| Accessibility insertion varies by app | Some apps reject SET_TEXT / PASTE | Verified after action; clipboard fallback + honest message |
| Recogniser runs in Google app process | Audio leaves our control in online mode | Disclosed in Privacy screen |
