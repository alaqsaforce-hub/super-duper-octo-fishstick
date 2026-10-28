# Progress

## v0.5.0 — agent core + AI accounts (2026-10-24)
- **Agent** (`agent/`): `ScreenModel` turns the Accessibility tree into ≤160 numbered elements (buttons, fields, lists,
  toggles, text; rows keep their inner text; password fields flagged) with a stable screen signature. `AgentProtocol` parses one
  JSON action per step (tolerates fences/nesting). `AgentSafety` is a code-level gate: Confirm for send/delete/pay/call/install-like
  short labels (English + Bengali), toggles, type+enter and model-flagged risky steps; Block for password fields, PIN/OTP/card-like
  text, finance apps (package segments + app labels, e.g. bKash/Nagad/বিকাশ/"Bank"), Play Store and VoiceDock itself.
  `AgentRunner` loop: snapshot → (screenshot when <3 labelled elements or after `look`) → brain → gate → act → settle; 30 steps /
  10 min, loop guard, 3 bad replies or 2 provider errors end the task, partial notes kept, Stop/Deny/ask-user handled.
  `A11yDevice`: node actions first (click/long-click/set-text/scroll), gesture fallback (`canPerformGestures`), screenshots with
  VoiceDock's overlay hidden, launcher-app matching with Bengali aliases. `AgentController`: blockers, free-first vision routing with
  text-only fallback, moves VoiceDock to the back when started from the app.
- **UI**: bubble panel 🤖 section (presets, "run this text", 🎙 voice goal), top status strip while running (touch-through except
  when asking Allow/Deny or a question; voice answer), purple bubble = tap to stop, result card (listen/copy). App: Agent screen
  (goal, presets, blockers, live step log). Accessibility XML: gestures, view ids, interactive windows; `<queries>` for launcher apps.
- **Accounts** (`accounts/`): multi-account store (non-secret JSON in prefs, secrets in SecretStore), free-first `AccountOrder`.
  `OAuthUtil`: PKCE (RFC 7636), one-shot 127.0.0.1 loopback receiver, JWT RS256/JWKS + claim checks. `ChatGptAuth`/`ChatGptSession`:
  Sign in with ChatGPT (dynamic registration → issued client id, reauth with id_token_hint, discovery, token exchange, serialised
  rotating refresh, revoke). `ChatGptClient`: `/v1/models`, `/v1/responses` streaming (`store:false`, `instructions`, image input),
  SIWC error codes → router error kinds. `OpenRouterLogin` (PKCE → key). `LoginManager`: browser flows, return page with
  `voicedock://auth`, sign-out keeps the registration. Provider screen: Continue with ChatGPT, plan notice, Manage usage, sign
  out/in, OpenRouter sign-in, extra keys with paid flag, enable/disable, delete.
- **AI layer**: CHATGPT provider; vision interface (`supportsImages`, `completeWithImages`) for OpenAI-compatible, Anthropic, Gemini
  and ChatGPT; `AiRouter.completeVision`; candidates = all free credentials first, then paid (only when allowed); answers carry the
  account label.
- Version 0.5.0 (code 5). Tests: **133** (new V5Test 24: screen model, protocol, safety gate, app matching, runner with a fake phone — notes, Allow/Deny, finance block, bad replies, stop, look; PKCE RFC 7636 vector, JWT RS256 with a generated key, loopback receiver, ChatGPT authorize URL + callback checks, token exchange/refresh/session sign-out, discovery + JWKS + revoke, OpenRouter PKCE, account order, ChatGPT SSE streaming + errors, vision routing, account store).

## v0.4.0 — offline Whisper + VoiceDock keyboard (2026-10-24)
- **Offline Whisper**: whisper.cpp v1.9.5 vendored (CPU backend only, 6.5 MB source), built with NDK 27 / CMake 3.22 for arm64-v8a
  (`libvdwhisper.so` 1.7 MB, always -O3, generic armv8-a so it runs on every arm64 phone, 4 exported JNI functions).
  `WhisperSpeechEngine` records the mic, cuts clips at 1.6 s pauses (max 28 s), transcribes on the CPU (2–4 threads), removes
  noise tags/known hallucinations (real words such as "ধন্যবাদ" are never dropped), converts Devanagari output to Bengali,
  warns when the model wrote another script. Model manager: Tiny/Base/Small/Turbo from Hugging Face, Range-resume, exact size +
  SHA-256 check (values read from Hugging Face headers), free-space check, delete.
- **Measured on the build machine** (x86, 2 vCPU; the same JNI C code compiled for the host) with real Bengali clips from the
  BanSpeech dataset: init ≈0.1–0.4 s; Base wrote Urdu script; Small wrote Devanagari (→ converted); Turbo mostly Bengali,
  phonetically close with misspellings. A Bengali initial prompt made decoding 4–5× slower and caused loops → removed.
  Reducing `audio_ctx` ruined accuracy → not used. Phone speed is unknown until tested on the device.
- **Routing**: new engine choice "Offline Whisper"; AUTO falls back phone → Gemini → Whisper.
- **VoiceDock keyboard (IME)**: Compose keyboard in an InputMethodService; QWERTY + Avro-style phonetic Bengali (pure
  `BanglaPhonetic`, whitelist conjuncts so colloquial words stay right; `+` forces / `` ` `` breaks a join), English mode,
  ?123 page (Bengali digits, ৳, ্, ঃ), shift/caps, repeat backspace, editor-action enter, 🌐 toggle / long-press picker.
  Voice strip: 🎤/⏸, live text, **বসাও** (finish → `commitText` → draft marked `ime:<package>`), ✕. Password fields: no voice,
  no composing. `DictationController.finishHandler` makes the keyboard insert instead of the bubble (no double insert).
- Settings: Offline Whisper card (download/progress/stop/resume/delete/choose) and Keyboard card (enable/choose, try-it box,
  rules). Home shows Whisper status. Drafts: "নলেজে সেভ". Drive search no longer sends an empty `orderBy`.
- Tests: **109** (new: BanglaPhonetic 5, ImeTyping 1 — real InputConnection composing, WhisperText 6, WhisperDownload 2 —
  MockWebServer resume + checksum, WhisperRouting 4).

## v0.3.0 — AI providers, Mode B, knowledge base, connectors (2026-10-24, local checkpoint, not released separately)
- AiRouter with free-first fallback over Gemini, Groq, OpenRouter, Mistral, Custom, OpenAI, Anthropic, DeepSeek; paid off by default.
- Mode B (AiFormatter + FactGuard), capture (share / PROCESS_TEXT), knowledge base (Room v2 + FTS4, file import, Gemini OCR, RAG
  with citations), connectors (Drive/Gmail OAuth read-only, Gmail IMAP, GitHub token), fixed debug signing key, OAuth setup doc.
- Tests: 91.

## v0.2.0 — Bengali engine fix + Gemini + screen help (2026-10-24)

**User report (Xiaomi phone):** "এই স্পিচ ইঞ্জিন নির্বাচিত ভাষা সমর্থন করে না" — no language worked in WhatsApp/Gmail.

**Root causes found in v0.1.0 code**
1. `SpeechRecognizer.createSpeechRecognizer(context)` uses the phone's *default* speech service. On Xiaomi (and Samsung, etc.)
   that is often the vendor's own service without Bengali → error 12/13 → "language not supported".
   AUTO mode also tried the on-device recogniser first, which usually has no Bengali model.
2. The bubble flow called `moveTaskToBack()` right after starting the microphone foreground service. On Android 14+ the
   service may reach the foreground after VoiceDock is already in the background and then gets no microphone access in other apps.

**Fixes / new**
- `RecognizerCatalog`: lists every installed RecognitionService, ranks Google first, offline Whisper-type apps next, vendor services
  last; on Android 13+ asks each service whether it supports the language (and can trigger the offline model download).
- `AndroidSpeechEngine`: tries the ordered services one by one; a service that rejects the language is skipped automatically.
- `GeminiSpeechEngine`: own microphone recording (AudioRecord 16 kHz), pause-based clipping (`AudioChunker`), verbatim
  Bengali/English/mixed transcription with Gemini; retries 1/2/4 s; failed clips kept on the phone with a Retry button.
- `RoutingSpeechEngine`: AUTO = phone services, switching to Gemini automatically if no phone service accepts the language.
- Gemini settings: key stored encrypted (Android Keystore), "Test key" lists the live model list (no guessed model names),
  explicit consent switch, Local-only mode, link to create a free key.
- Explain screen (vision): from the bubble, ONE screenshot + visible text on tap → Gemini → simple Bengali step-by-step answer,
  "Listen" (text-to-speech), follow-up questions, "Ask by voice". Prompt-injection guard; never taps anything itself.
- Engine status card on Home; speech-service list with status + install links (Google app, Speech Services by Google, whisperIME, Gemini key).
- Bubble start now waits until the microphone service is in the foreground.

**Build:** BUILD SUCCESSFUL. APK `VoiceDock-AI-v0.2.0-debug.apk` — 9,975,146 bytes, md5 `36e08e6ae1465691bc951d147d16dc65`, versionCode 2.

**Automated tests: 60 / 60 passed** (41 old + 19 new)
| New suite | Tests | Covers |
|---|---|---|
| AudioChunkerTest | 6 | clip at pause, two utterances, click dropped, 25 s cut keeps remainder, flush, WAV header |
| GeminiClientTest | 7 | audio request + key only in header, typed errors (key/429/5xx), model list filter, default model pick, no-speech cleanup, blocked prompt, injection guard text |
| RecognizerOrderTest | 3 | Google before Xiaomi default, user choice first / unsupported last, service kinds |
| RoutingTest | 3 | AUTO → Gemini on language error, honest error when Gemini not set up, Gemini choice |

**Not tested automatically (needs the phone):** real Gemini calls with a real key, microphone recording, screenshots, TTS, Xiaomi service behaviour.
Manual checklist: `LIMITATIONS.md` tests 18–24.

## v0.1.0 — Phase 1 + Phase 2 (2026-10-24)

**Build:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL.
APK `VoiceDock-AI-v0.1.0-debug.apk` — 9,329,811 bytes, md5 `0c8511582d737db7201884dc03fd1589`, package `com.voicedock.ai`, minSdk 26, targetSdk 35. Debug-signed (not for Play Store).

**Automated tests: 41 / 41 passed** (JVM + Robolectric, SDK 35)
| Suite | Tests | Covers |
|---|---|---|
| TranscriptMergerTest | 4 | restart overlap removal, real repetitions kept, partial→final replacement, duplicate finals |
| DictationSessionTest | 10 | silence/cough restart, partial rescue, pause/resume/finish, long inactivity → WAITING, backoff, rapid-failure guard, cancel keeps text, clear snapshot, restore never auto-listens |
| DictationControllerTest | 6 | full flow with fake engine incl. late final after Finish, network backoff → INTERRUPTED, CLEARED/CANCELLED history rows, WAITING, restore after "restart", on-device request |
| LocalFormatterTest | 12 | Bangla । and ?, subordinate clauses, English capitals/questions, continuation words, spoken commands bn/en, "period" as a word, stutters, no invented words |
| InsertionMathTest | 7 | cursor/selection insertion and spacing |
| RoomPersistenceTest | 1 | file database: write, close, reopen, update without duplicates |
| MainActivitySmokeTest | 1 | real activity launches and renders Home, Dictation, Drafts, Settings, Privacy |

**Not tested (needs a real phone):** actual speech recognition, overlay bubble/panel, foreground-service
notification, Accessibility insertion into real apps. See `LIMITATIONS.md` checklist (17 tests).

### Status by requirement
| Feature | Status |
|---|---|
| App shell, 5 screens, bilingual UI, theme | Implemented, smoke-tested |
| Permissions onboarding (mic, notifications, overlay, accessibility) | Implemented; device test needed |
| Continuous dictation state machine | Implemented, unit-tested |
| Android SpeechRecognizer engine (online / on-device API 31+) | Implemented; device test needed |
| Persistent drafts + restore | Implemented, tested (Room + controller) |
| Local punctuation (Mode A) | Implemented, unit-tested |
| Review / edit / copy / share / save | Implemented; smoke-tested |
| Floating bubble + panel + FGS notification | Implemented; device test needed |
| Accessibility insertion + clipboard fallback | Implemented (math unit-tested); device test needed |
| AI providers + Mode B (v0.3) | Implemented, unit-tested (fake clients); device test needed |
| Capture + knowledge base + RAG (v0.3) | Implemented, tested (Room FTS, migration 1→2) |
| Connectors Drive/Gmail/GitHub read-only (v0.3) | Implemented (REST/IMAP parsing tested); OAuth needs Cloud Console setup |
| Offline Whisper (v0.4) | Implemented; JNI tested on x86 host with real Bengali audio; phone test needed |
| VoiceDock keyboard (v0.4) | Implemented; typing logic tested (Robolectric); phone test needed |
| Overlay Mode B, semantic search, OCR without Gemini | Not built |

## Next step
1. User installs v0.4.0 and runs manual tests 18–40 (esp. 35 Whisper speed/accuracy, 37–38 keyboard voice on Xiaomi).
2. Depending on results: keyboard AI-tidy button, overlay Mode B, dictionary suggestions for the phonetic keyboard,
   optional semantic search, release build (own keystore) when the user wants Play-store style signing.

## Continuation notes
- Build environment: JDK 21, `GRADLE_USER_HOME=/opt/gradle-home`, SDK `/opt/android-sdk`; run long builds in the background.
- Robolectric tests must use `@Config(sdk = [35])` (cached runtime).
- Do not add `fallbackToDestructiveMigration`; add migrations to `Migrations.ALL` and bump the DB version.
- Native build: NDK `27.0.12077973`, CMake `3.22.1`; `app/.cxx/` is ignored. Two-job compile pool keeps RAM low.
- whisper.cpp update: replace `app/src/main/cpp/whisper.cpp` with a newer tag (keep `include/ src/ ggml/{include,cmake,src/*.c*,src/ggml-cpu}`),
  then re-check `whisper_full_params` fields used in `whisper_jni.c`.
- Robolectric cannot load the arm64 `.so`: `WhisperNative.available` is false in tests (asserted).


---

## v0.6.0 — Contextual Mic Bubble (debug build; device QA pending)

Goal: the default UI is only a small floating mic; one tap gives contextual help without a password,
without granting the microphone and without a cloud call.

Done in this working copy:

| # | Change | Verification |
|---|---|---|
| 1 | Pure, Android-free `context/` core: `ContextModels`, `SafetyOracle`, `ContextPolicy`, `ContextClassifier`, `CatchUpSummary`, `AssistantGreeting`, `AssistantPrefs`, `LocalIntentRouter` | `ContextCoreTest` (14 JVM tests, passing) |
| 2 | Bug fixes: `AiPolicy` local-only now allows a self-hosted LAN model and refuses public `http://`; `network_security_config.xml` narrow LAN allow-list; `setStripTouchable` bitwise flag fix; dead `FutureChips()` removed | `AiPolicyLocalTest` (9 JVM tests, passing) |
| 3 | `device/ScreenProbe.kt` + `device/AgentSafetyOracle.kt` (Android adapters) | compiled; **device test pending** |
| 4 | `overlay/AssistantOverlayService.kt` — mic-free FGS, drag bubble, on-tap probe, scrim, context card, focusable chat bubble, typewriter greeting, offline intent router, approval-gated insert | compiled; **device test pending** |
| 5 | Manifest: `dataSync|specialUse` FGS + `<property>`, no-op notification listener, new FGS permissions | compiled and packaged |
| 6 | UI: 3-tab navigation, just-in-time consent dialog, `AssistantSettingsSection`, Privacy rows, updated a11y description | compiled; activity smoke test passed |
| 7 | Docs: `LOCAL-AI.md`, `V0.6-CHANGES.md`, `MANUAL-TESTS-v0.6.md`, rewritten `PRIVACY.md` | — |

Still NOT done (by design, later versions): encrypted personal-memory tables (v0.7), background
event collector (v0.7-0.8), real catch-up numbers (v0.7), priority learning (v0.9), local text LLM
(v1.0), biometric gate (v0.7). Any number shown by the assistant today comes ONLY from local events;
with no events it says so instead of inventing one.

**Build and test status (2026-10-28):** The project compiled with JDK 17, Android SDK 35,
NDK 27.0.12077973 and CMake 3.22.1; `:app:assembleDebug` succeeded. `:app:testDebugUnitTest`
passed **157 tests, 0 failures, 0 errors, 0 skipped** (JVM/Robolectric, 29 suites). The APK is
signed with the supplied debug key, whose certificate matches the provided v0.5.0 APK.
Both packaged arm64 native libraries and the APK ZIP entries were verified for 16 KB alignment.
No emulator or physical phone was attached; installation, overlay, microphone, accessibility,
network-provider, and OEM behaviour require the manual tests in `MANUAL-TESTS-v0.6.md`.
This debug key is not a production/Play Store signing key.

**Continuation fixes:** Guard screen explanation behind the actual consent switches, re-check the
current screen, and do not send text when a per-window capture fails. Reject spoofed local AI hosts
and redirects; re-check approved insertion targets; gate sensitive agent screens before AI calls;
start dictation only after its microphone foreground service is active; fix probe cancellation and
add visible dictation controls. The local probe still cannot reliably detect every FLAG_SECURE window
or secret: disable screen-context consent in sensitive apps. Explain screen now requires Android 14+.
