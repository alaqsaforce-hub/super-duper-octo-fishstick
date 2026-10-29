# VoiceDock AI

Floating voice assistant for Android — continuous Bangla/English dictation with draft recovery, local punctuation,
AI tidy-up without invented facts, a personal knowledge base, read-only connectors, its own keyboard and a built-in
offline Whisper, an on-screen **agent** and AI **accounts** (ChatGPT / OpenRouter sign-in). Native Kotlin + Jetpack Compose
(+ whisper.cpp via JNI). **Current version: 0.6.1** (debug/test build; real-device verification pending).

## v0.6 contextual assistant
- A mic-free floating bubble can perform an on-tap accessibility probe with separate consent, and offers
  a context card or a focusable assistant chat. Its intent router and catch-up placeholder run locally.
- Insert requires an explicit tap and rechecks the original app and focused field; otherwise it copies the draft.
- Sensitive-screen detection is heuristic: the local probe cannot reliably detect every `FLAG_SECURE` screen or
  secret. Disable screen-context consent before sensitive apps. Explain screen needs Android 14+, screenshot and
  cloud consent, and refuses to send text if per-window capture fails.
- Background event collection, permanent personal memory, biometric protection and local text generation are not
  implemented in v0.6. See `docs/LIMITATIONS.md` and `docs/MANUAL-TESTS-v0.6.md`.

## What works
**Dictation (0.1–0.2)**
- Continuous dictation (bn-BD, en-US, en-IN, "mixed" = bn-BD) with an explicit state machine; pauses/coughs never lose text;
  no duplicate words; Pause/Resume/Finish/Cancel/Clear (Cancel and Clear keep a copy in Drafts).
- Engines: phone speech services (Google first, vendor last), **Gemini** (own key), **offline Whisper** (0.4, on the phone).
  AUTO: phone services → Gemini (if set up) → offline Whisper (if a model is downloaded).
- Every sentence saved immediately (Room); unfinished draft restored after a restart.
- Local Mode A punctuation (। . ? ,), spoken commands, stutter removal. No words invented.
- Floating bubble + panel and FGS notification. Explain-screen uses a consented window screenshot on Android 14+ → Gemini → Bengali steps.
- Insertion: Accessibility on Insert tap (never password fields) → clipboard/share fallback; **VoiceDock keyboard** (0.4).

**AI (0.3)**
- Providers: Gemini, Groq, OpenRouter, Mistral, Custom (OpenAI-compatible), OpenAI, Anthropic, DeepSeek. User's own keys,
  Keystore-encrypted. Free-first fallback order (editable); paid providers OFF by default; OpenRouter limited to `:free`
  models while paid is off. No quota numbers are invented.
- **Mode B** "structure my text": keeps the original, shows the AI version, FactGuard warns about new/missing numbers,
  links, e-mails, names and length changes (warnings only — the user decides).

**Knowledge base (0.3)**
- Save notes, shared text/files (share sheet, "Process text"), drafts ("নলেজে সেভ"), Drive/Gmail/GitHub items.
- Import: txt/md/csv/html/docx/xlsx/pptx/pdf; images & scanned PDFs via Gemini OCR (ML Kit has no Bengali).
- Search: SQLite FTS4 keyword search (Bengali-safe normaliser, prefix match), duplicate detection by SHA-256.
- Ask: answers only from saved documents with [n] citations; says so when the answer is not there; no AI call without hits.

**Connectors (0.3, all read-only)**
- Google Drive & Gmail via Google sign-in (scopes `drive.readonly`, `gmail.readonly`) — needs a one-time OAuth client
  setup by the developer (`docs/OAUTH-SETUP.md`). Gmail also via IMAP + app password (READ_ONLY folder open).
- GitHub with a personal access token (read). No send/write/delete endpoints exist in the code.

**Keyboard (0.4)**
- Own IME: QWERTY, Avro-style phonetic Bengali (`ami` → আমি, `kintu` → কিন্তু) or English, ?123 page with Bengali digits,
  ৳, ্ ; long-press 🌐 to switch keyboards.
- Voice strip: 🎤 starts the same dictation engine; **বসাও** finishes and types the text into the current field (no
  Accessibility needed). Voice disabled in password fields.

**Offline Whisper (0.4)**
- whisper.cpp v1.9.5 (MIT) built for arm64-v8a; models downloaded on request from Hugging Face with resume + SHA-256 check:
  Tiny 32 MB, Base 60 MB (English only), Small 190 MB, Large-v3 Turbo 574 MB (best offline Bengali).
- Honest quality note (tested on real Bengali news/audiobook clips): Tiny/Base cannot write Bengali; Small is weak;
  Turbo is usable but misspells; Gemini is much better. Devanagari output is converted to Bengali automatically.

**Agent (0.5)**
- Started only by the user (bubble presets, typed or spoken goal, or the Agent screen). Loop: read the screen (Accessibility node
  tree → numbered elements; screenshot when the tree has little text or the model asks to *look*) → AI decides ONE JSON action →
  code-level safety gate → act (tap, long-press, type, scroll, back, home, open app, wait, note, ask user, finish).
- Safety (in code, independent of the model): send/delete/pay/call/install/toggle-like buttons and "type + enter" need **Allow**
  in the bubble; password/PIN fields and PIN/OTP/card-like text are refused; banking/bKash/Nagad/wallet apps, Play Store and
  VoiceDock itself are blocked; step (30) and time (10 min) limits; loop guard; Stop = tap the bubble.
- Read-only presets: summarise this screen, scroll-read a page, describe gallery photos, summarise top WhatsApp chats, explain
  settings. Partial notes are returned when a task stops early.
- Any configured provider can drive it (image-capable ones get screenshots; others get the element list only).

**Accounts (0.5)**
- **ChatGPT sign-in** ("Continue with ChatGPT", OpenAI's Sign in with ChatGPT for open-source/local apps, preview): browser →
  127.0.0.1 loopback redirect, PKCE + state + nonce, dynamic registration (issued `client_id` saved per account), ID token
  RS256/JWKS verification, rotating refresh (2 min early, serialised), revoke on sign-out, Responses API streaming with
  `store:false`; uses the user's ChatGPT Go/Plus/Pro plan limits ("Using ChatGPT plan", "Manage usage").
- **OpenRouter sign-in** (official PKCE → user key) and **several API keys per provider** with labels, enable/disable, "billing on
  (paid)" flag, delete. Order: all free credentials of all providers first (in the provider order), paid ones only after them and
  only when "Allow paid" is on.
- Not possible (documented): Claude subscription login (forbidden for third-party apps → API key only), Gemini login (key),
  GitHub Copilot/Models, Arena.ai (no public API).

## Build
Requirements: JDK 17+ (v0.6.0 was built with 17), Android SDK 35 + build-tools, **NDK 27.0.12077973 + CMake 3.22.1** (for whisper.cpp).
```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
sdkmanager "ndk;27.0.12077973" "cmake;3.22.1"
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (arm64-v8a only)
```
Debug builds are signed with the committed `keystore/debug.keystore` (debug only, password `android`).
The certificate matches the supplied v0.5 APK, supporting an in-place upgrade without changing the
OAuth SHA-1, but installation has not been tested on a phone. Never use this key for a store release.

## Install & first run
1. Install the APK (allow "install unknown apps"). The included debug key matches the supplied v0.5 APK, so an in-place update
   should be possible without uninstalling; it has not been verified on a device. Room stays at schema v2. If needed, turn
   VoiceDock Accessibility OFF and ON once after updating.
2. Home → allow Microphone and Notifications; "Display over other apps" for the bubble.
3. Bengali speech: Settings → Speech engine → Gemini key (best) or download an offline Whisper model (Turbo for Bengali).
4. Keyboard: Settings → VoiceDock keyboard → 1. Enable → 2. Choose.
5. AI providers / Mode B: Settings → AI providers → add a free key (Gemini, Groq, OpenRouter …). Connectors: Settings → Connectors.
6. Knowledge tab → add note / import file / ask.
7. Agent: Settings → AI providers (add Gemini key and/or Continue with ChatGPT) → Home → 🤖 Agent, or bubble → 🤖 section.

## Project layout
```
app/src/main/java/com/voicedock/ai/
  VoiceDockApp.kt        Application + VoiceDock service locator
  voice/                 engines (Android, Gemini, Whisper), RoutingSpeechEngine, AudioChunker, DictationSession/Controller
  ime/                   VoiceDockIme (keyboard), BanglaPhonetic (pure converter)
  ai/                    GeminiClient, AiProviders (router, vision, OpenAI-compatible + Anthropic), ChatGptClient, AiSetup
  agent/                 ScreenModel, AgentProtocol, AgentSafety, AgentBrain, AgentRunner, A11yDevice, AgentController
  accounts/              Accounts store, OAuthUtil (PKCE, loopback, JWT), ChatGptAuth/Session, OpenRouterLogin, LoginManager
  text/                  LocalFormatter (Mode A), AiFormatter (Mode B + FactGuard)
  knowledge/             KnowledgeRepo (add/search/ask), KbText (chunker, search), TextExtract (file import)
  connectors/            Drive/Gmail REST, IMAP, Google authorisation, GitHub
  data/database/         Room v2: dictation_drafts, kb_documents, kb_chunks, kb_chunks_fts
  security/ settings/ permissions/ vision/ overlay/ insertion/ ui/
app/src/main/cpp/        CMakeLists.txt, whisper_jni.c, vendored whisper.cpp (CPU backend only)
app/src/test/            JVM/Robolectric tests (see PROGRESS for the count)
app/schemas/             exported Room schemas (1, 2)
docs/                    PROGRESS, LIMITATIONS (+ manual checklist), PRIVACY, DB-SCHEMA, OAUTH-SETUP, PLAN-PHASE1
```

## Security
No API keys or OAuth secrets in the source (ChatGPT/OpenRouter sign-in are public PKCE clients). User keys and tokens are stored encrypted (Android Keystore AES-GCM). Network calls only go to
services the user configured, with consent; Local-only mode blocks cloud AI but permits a configured private/LAN AI endpoint. Imported content is treated as untrusted
(prompt-injection guard). Backup disabled. See `docs/PRIVACY.md`.
