# Known limitations (v0.5.0) & manual device tests

## Limitations
1. **Restarts**: Android's recogniser ends after each utterance; VoiceDock restarts it automatically. Some devices play a
   short beep on each restart. There is no legitimate API to silence it.
2. **Mixed Bangla/English**: phone recognisers take one language at a time ("Mixed" uses bn-BD). The Gemini engine handles mixed speech.
3. **Phone speech services**: VoiceDock lists every installed RecognitionService and tries them in order (Google first, phone-vendor
   services such as Xiaomi's last). Bengali support can be queried only on Android 13+; on older phones each service is tried.
   If none accepts Bengali and Gemini is not set up, an honest message is shown with install links.
3a. **Gemini engine**: needs internet and the user's own API key + consent. Text arrives per phrase (after a ~1 s pause), not word by word.
   Free-tier limits are Google's and may change; on HTTP 429 the clip is retried and then kept on the phone for "Retry".
   Silence/noise detection is energy-based; in a very noisy place clips may be cut late.
3b. **Models downloaded by other apps** (e.g. Whisper/OCR files from "Hermes") cannot be read by VoiceDock because of the Android app
   sandbox. Such apps are used automatically only if they provide a system speech service. VoiceDock now has its own offline Whisper.
3c. **Explain screen**: needs Android 14+, Accessibility, Gemini, screen-context consent, screenshot consent and cloud consent.
   It uses a per-window screenshot; if capture fails, no screen text is sent as a fallback. After updating, turn VoiceDock
   Accessibility OFF and ON once if needed so Android grants screenshot capability. The helper only explains; it never taps.
4. **Insertion**: works only via Accessibility (user-enabled) and only in fields that accept `ACTION_SET_TEXT` or `ACTION_PASTE`.
   Some apps (custom editors, games, banking apps, secure fields) refuse → text is copied to the clipboard and the user is told.
   No universal compatibility is claimed. Alternative: the VoiceDock keyboard types the text itself (no Accessibility).
5. **Starting from background**: the microphone service must be started from the open app (Android 14 rule). Once the bubble
   is running, dictation can be paused/resumed from the bubble in any app.
6. **OEM battery savers** may kill the service; text already recognised is saved per sentence and restored.
7. **Panel is not focusable** (so the target app keeps the cursor); editing is done in the app (Open app / edit).
8. **Error/status messages from the dictation engine are Bengali** even when the UI language is English (labels are bilingual).
9. **Still not built**: overlay-panel Mode B (Mode B is in the app's Dictation screen), embeddings/semantic search, OCR without
   Gemini, Drive/Gmail write actions (deliberately absent), AI tidy button on the keyboard.
11. **AI providers** (0.3): free tiers are the providers' own and change without notice; VoiceDock shows no quota numbers it cannot
    verify. A key with billing enabled may be charged even when the model is labelled "free". Paid providers stay off until the
    user turns on "Allow paid usage".
12. **Mode B**: AI may still change meaning; FactGuard only flags numbers/links/e-mails/Latin names/length — it cannot detect every
    change. The original is always kept; the user chooses.
13. **Knowledge search** is keyword-based (FTS4 + normaliser, prefix match), not semantic: synonyms and spelling variants
    (e.g. বেতন vs. মাইনে) are not matched. Very large files are cut at the import limit; scanned PDFs/images need Gemini OCR.
14. **Google Drive/Gmail sign-in** works only after an OAuth client (package `com.voicedock.ai` + SHA-1) is registered in Google
    Cloud Console and the user is added as a test user (`docs/OAUTH-SETUP.md`). Without it Google returns error 10 and the app says
    "not set up". Gmail IMAP with an app password works without that (needs 2-step verification on the Google account).
15. **Offline Whisper** (0.4): arm64-v8a phones only (the APK contains only arm64 native code; 32-bit-only phones cannot install
    0.4). Text appears after a ~1.6 s pause, not live; every clip costs several seconds (Whisper always encodes a 30 s window).
    Quality on real Bengali test clips (build machine): Tiny/Base wrote Urdu/Latin script (English only), Small mostly wrong,
    Large-v3 Turbo close but with spelling mistakes. Speed on a phone is untested; Turbo needs ~6 GB RAM. Models are large
    downloads (Wi-Fi recommended) and live in app storage (deleted on uninstall).
16. **VoiceDock keyboard** (0.4): phonetic rules are Avro-like but without a dictionary/suggestions, so ো needs a capital O
    (`tOmake` → তোমাকে) and rare conjuncts need `+`. No swipe typing, emoji or autocorrect. Whether a keyboard may use the
    microphone while another app is in front depends on the phone (Android 11+ "while-in-use" rules; MIUI may block the
    foreground-service start) — must be tested on the Xiaomi phone. If it fails, start the bubble once from the app first.
18. **Agent** (0.5): depends on what apps expose to Accessibility. Apps drawn as one canvas (games, some Flutter/React Native
    screens, video players) give few or no elements → the agent relies on screenshots and coordinate taps, which are less
    reliable. Apps with FLAG_SECURE (banking, some chats in incognito) give black screenshots. MIUI may kill background work or ask
    for "Display pop-up windows while running in background" for opening apps from the bubble. Each step costs one AI call
    (free-tier rate limits reach quickly on long tasks); steps take ~2–6 s. The model can misunderstand screens: it is an
    assistant, not guaranteed automation. After updating, Accessibility must be switched off/on once (new gesture capability).
    WhatsApp reply drafting/sending skills and hands-free voice tasks are planned for 0.6/0.7, not in 0.5.
19. **ChatGPT sign-in** (0.5) uses OpenAI's *preview* "Sign in with ChatGPT" for open-source / locally hosted apps. OpenAI decides
    eligibility: it needs a ChatGPT Go/Plus/Pro plan; the shared limit is the ChatGPT one (Plus has a 5-hour window shared with
    ChatGPT); OpenAI may refuse the app, change the API or end the preview. If OpenAI refuses, its sign-in page shows the error and
    nothing is saved. Not tested against the live OpenAI service (no account available in the build environment); the protocol is
    implemented from OpenAI's published documentation and tested with mock servers.
20. **OpenRouter sign-in** creates a key in the user's OpenRouter account; free (`:free`) models have OpenRouter's own daily limits.
21. **Not available**: Claude Pro/Max login (Anthropic forbids third-party apps → API key only), Gemini login (API key only),
    GitHub Copilot / GitHub Models, Arena.ai (no public API). Gemini Nano on-device is not supported on the Redmi Note 13.
22. Only automated JVM/Robolectric tests plus a host (x86) run of the whisper.cpp JNI bridge were done; no physical device or emulator was available.

## Manual device test checklist
| # | Test | Expected |
|---|---|---|
| 1 | Fresh install → Home | Setup checklist shows mic/notifications/overlay/accessibility status |
| 2 | Dictate here → Start, speak Bangla 3 sentences with 5 s pauses | Text keeps growing; TEMP_PAUSE shown during pause; no duplicated words |
| 3 | Cough / drink water mid-dictation | Session continues; nothing lost |
| 4 | Pause → Resume | Mic indicator off/on; text preserved |
| 5 | Finish | PROCESSING, then REVIEW with punctuation (।/?) ; edit text; Copy; Share; Save |
| 6 | Airplane mode while dictating (online engine) | Retries, then INTERRUPTED message; text kept; Resume after network returns |
| 7 | Silence for > configured minutes | WAITING, mic off, text kept |
| 8 | Swipe app away / reboot during dictation, reopen | "Unfinished draft" banner; text restored, mic off |
| 9 | Start floating bubble → open WhatsApp/Gmail, tap bubble | Panel appears; keyboard focus stays in the chat box |
| 10 | Start → speak → Finish → Insert (Accessibility ON) | Text inserted at cursor; toast "Text inserted" |
| 11 | Same with Accessibility OFF | Toast says copied; long-press → Paste works |
| 12 | Insert into a password field | Refused, copied with "secure field" message |
| 13 | Clear → Drafts | "Copy before clear" entry exists |
| 14 | Cancel → Drafts | "Cancelled (text kept)" entry exists |
| 15 | Notification buttons Pause/Resume/Finish/Close | Work; Close removes bubble and notification |
| 16 | Settings: English UI, dark theme, on-device engine | Applied immediately |
| 17 | Drag bubble, close, reopen | Bubble remembers position |
| 18 | Settings → Speech engine: list shows Google / Xiaomi services with status | Google ranked first; vendor service last |
| 19 | AUTO engine, Bengali, on a Xiaomi phone | Text appears (Google service), or switches to Gemini with a note |
| 20 | Gemini: paste key → Save & test | "Key works, N models"; model chips appear |
| 21 | Engine = Gemini, speak Bangla+English mixed in WhatsApp via bubble | Text appears after each pause; Insert works |
| 22 | Gemini with airplane mode | "not transcribed" card; Retry after network adds the text |
| 23 | Bubble → "Explain screen" on a settings page | Bengali step-by-step explanation; Listen reads it aloud (if a Bengali TTS voice exists) |
| 24 | Bubble → "Ask by voice" → speak question → Finish | Question is sent with the screenshot; answer shown |
| 25 | Settings → AI providers: add a free Groq/OpenRouter key → Test | "works"; provider appears in fallback order; paid ones disabled |
| 26 | Dictation screen → Mode B on a draft with numbers | AI version shown next to original; changed numbers flagged |
| 27 | Share a PDF/DOCX from Files → VoiceDock | Saved to knowledge; same file again → "Already saved" |
| 28 | Select text in Chrome → menu → VoiceDock | Saved to knowledge |
| 29 | Knowledge → ask a question answered in a saved doc | Answer with [1] citation; unrelated question → "not in your documents" |
| 30 | Drafts → open → নলেজে সেভ | "Added to knowledge" |
| 31 | Connectors → Gmail IMAP (address + app password) → list | Recent mails listed read-only; import one |
| 32 | Connectors → Google sign-in (after OAuth setup) | Drive/Gmail lists appear; without setup → clear "not set up" message |
| 33 | Connectors → GitHub token → list repos/files | Read-only list; import a README |
| 34 | Settings → Offline Whisper → download Small/Turbo; stop half-way; resume | Progress bar; resume continues; ✓ after verification |
| 35 | Engine = Offline Whisper, airplane mode, speak Bengali, pause 2 s | Text appears after each pause (note "Whisper লিখছে"); report speed and accuracy |
| 36 | Settings → VoiceDock keyboard → Enable → Choose; type `ami bangla likhi` | আমি বাংলা লিখি |
| 37 | Keyboard in WhatsApp: 🎤 → speak → বসাও | Text typed into the chat box; draft marked inserted |
| 38 | Keyboard 🎤 while another app is in front, screen stays on 1 min | Dictation continues (report if it stops — MIUI) |
| 39 | Keyboard in a password field | Voice strip shows 🔒; typing still works; no Bengali composing |
| 40 | Long-press 🌐 | System keyboard picker opens |
| 41 | Update to 0.5 → Settings → Accessibility → VoiceDock OFF then ON | Agent screen no longer shows the "turn off and on" warning |
| 42 | AI providers → ChatGPT → Continue with ChatGPT → sign in → allow | Browser returns to VoiceDock; "You're using your ChatGPT plan" → Got it; account shows "Using ChatGPT plan" |
| 43 | ChatGPT account → Test / models | Model list appears; pick one |
| 44 | ChatGPT account → Sign out, then Sign in again | Sign-out message; sign-in reuses the same account row |
| 45 | OpenRouter → Sign in with OpenRouter | Account "OpenRouter লগইন 1" appears; Test lists models (only :free shown while paid is off) |
| 46 | Gemini → + Add another key (billing on = paid) with paid OFF | Paid key is skipped (free key used); turning paid ON uses it only after free ones fail |
| 47 | Open any settings page → bubble → 🤖 → "explain settings" preset | Purple bubble, top strip shows steps; result card with explanation; nothing changed |
| 48 | Long article in Chrome → preset "scroll-read and summarise" | Agent scrolls to the end, then summarises |
| 49 | Preset "gallery photos" | Gallery opens, photos described one by one; nothing deleted/shared |
| 50 | Preset "WhatsApp top 5 chats" | Chats opened one by one, summary per chat; no message sent |
| 51 | Goal "send hi to <contact> on WhatsApp" | Agent types, then the strip asks Allow before Send; Deny → not sent |
| 52 | During a task tap the bubble | Task stops within a few seconds; partial notes shown |
| 53 | Goal "open bKash" | Blocked message; bKash not operated |


## v0.6 additions / still open

- The on-tap probe reads `rootInActiveWindow`. Apps that render everything into a Canvas/WebView or
  refuse accessibility nodes can look "empty"; the assistant then falls back to its own chat bubble.
- `FLAG_SECURE` cannot be read from `AccessibilityNodeInfo`, so the local on-tap probe cannot reliably
  detect all protected screens. Password/OTP/finance rules are heuristics, not a security boundary.
  Disable screen-context consent before sensitive apps. Explain screen separately uses API 34 per-window
  capture and refuses to send screen text if capture fails or the window changes.
- Android's Network Security Configuration cannot express CIDR ranges, so plain `http://` works only
  for the exact loopback/LAN hosts listed in `res/xml/network_security_config.xml`. See `LOCAL-AI.md`.
- Background blur uses `WindowManager.LayoutParams.blurBehindRadius` on Android 12+ via reflection;
  devices without GPU blur simply get the dim layer.
- `specialUse` FGS needs a Play Console declaration if the app is ever published on Play.
- Starting the legacy microphone service from the bubble while in the background may be restricted
  by Android 14+ "while-in-use" rules; device tests 58-60 cover this.
