# Privacy (v0.6.0)

> **v0.6 change (read this first).** The floating assistant bubble now performs ONE read of visible
> screen text **when you tap it**, if and only if you turned on the separate
> *"স্ক্রিন প্রসঙ্গ পড়ার অনুমতি / Screen-context consent"* switch. This changes two older statements
> below ("Overlay … never reads other apps" and "No background screen reading") — those lines have
> been rewritten for v0.6. Nothing else in the privacy model was relaxed.

### v0.6 assistant bubble — exact behaviour

- The bubble itself needs **no microphone permission**. It runs as a `specialUse` (Android 14+) /
  `dataSync` (Android 10-13) foreground service with a persistent notification.
- On tap, `ScreenProbe` walks `rootInActiveWindow` once, collecting: number of visible characters,
  number of elements, whether a non-password editable/focused field exists, whether a password field
  exists, and a *count* of strings that look like a PIN/OTP/card. **Screen text is never written to
  disk**; the snapshot lives only for the current activation and is not persisted or sent anywhere.
- Known blocked/financial packages and VoiceDock's own window are rejected before walking their
  child nodes. Password text is not read. If a password field or a recognizable PIN/OTP/card string
  is encountered, the result is redacted; labels and app identity are not retained. The detection
  rules are heuristic and can miss secrets. An incomplete scan fails closed.
- **Important limitation:** Android does not expose another window's `FLAG_SECURE` to this local
  accessibility probe. The probe cannot reliably identify every protected or sensitive screen.
  Turn screen-context consent off before opening a sensitive app; do not rely on these heuristics
  as a security boundary.
- **No passive background screen monitoring.** `onAccessibilityEvent()` is empty. The bubble probes on
  a tap; the separate agent can read screens while a task you explicitly started is running.
- **Reading != acting.** Nothing is typed into another app until you tap *"এখানে বসাও / Insert here"*
  on a draft you have seen. Sending messages, paying, deleting or changing records is still not implemented.
- The local probe never takes a screenshot. *Explain screen* requires Android 14+, screen-context
  consent, screenshot consent, Gemini/cloud consent and a key. It checks the current window, then
  uses Android's per-window screenshot API. If the capture fails or the window changes, no text is
  sent as a fallback. The separate user-started agent can also capture screenshots during its task.
- `VoiceDockNotificationListener` is declared in the manifest for honest permission status, but is a
  **no-op** until v0.8 and stores nothing from other apps' notifications.
- All new switches are off by default except cosmetic ones (auto-scan, self-typing greeting, dim/blur).
  Turn any of them off in Settings → ভাসমান সহকারী (v0.6). Remote AI remains default-off.



What this version does — and does not — do:

- **Microphone**: used only after the user taps Start/Resume. A red indicator (bubble/panel/app) and a persistent
  notification are shown while the mic may be in use. Pause/Finish/Cancel/Close turn it off.
- **Speech processing**: done by the phone's `SpeechRecognizer` (usually the Google app). In *Online/Auto* mode the
  audio may be sent to Google's servers by that app; in *On-device* mode it stays on the phone when an offline model
  exists. The optional Gemini and Whisper engines record speech clips; Gemini uploads them with consent, while
  Whisper processes them locally. Failed clips can be kept in app-private storage for retry.
- **Text storage**: drafts are stored only in the app-private Room database. `allowBackup=false`; data extraction
  rules exclude all data from cloud backup and device-to-device transfer.
- **Network** (Gemini part; see AI providers/connectors below): to Google Gemini (`generativelanguage.googleapis.com`, HTTPS) only when the user has entered their own
  key AND enabled "Allow sending audio and screenshots to Gemini" AND Local-only mode is off. Then:
  - Gemini engine: microphone audio clips (speech segments) are sent for transcription;
  - Explain screen: ONE screenshot + the visible text of the current screen + the question, only on the user's tap.
  On free API keys Google may use submitted content to improve its services — the Settings screen says so.
- **API key**: stored encrypted (AES-256-GCM, key in Android Keystore), sent only in the `x-goog-api-key` header, never logged, shown masked.
- **AI providers (0.3)**: text is sent only to providers the user added a key for, only for an action the user tapped
  (Mode B, Ask knowledge, OCR), never in Local-only mode. Paid providers are used only after "Allow paid usage".
- **Knowledge base (0.3)**: stored in the app-private database only. Imported files/mails/web text are treated as untrusted
  (instructions inside them are ignored by the prompt). Ask sends only the matching chunks (not the whole base) to the chosen AI.
- **Connectors (0.3)**: read-only. Google: `drive.readonly`, `gmail.readonly` via Google sign-in; the access token is kept in
  memory only. Gmail IMAP app password and GitHub token are stored encrypted. Nothing is sent, written or deleted in those accounts.
- **Offline Whisper (0.4)**: audio is processed on the phone; no network except the one-time model download from Hugging Face
  (only when the user taps Download). Models are stored in app storage.
- **VoiceDock keyboard (0.4)**: typed text goes only to the app you type in. VoiceDock does not store, log or send keystrokes.
  Android shows its standard warning for every third-party keyboard. Voice is disabled in password fields. Text from the voice
  strip is saved as a draft like any dictation (destination `ime:<package>`).
- **Agent (0.5)**: runs only after the user starts a task and stops on Stop / finish / limits. During the task, at each step the
  visible element list (text, labels, button names; password fields are marked, never read) and — when needed — one screenshot
  (VoiceDock's own bubble hidden) are sent to the AI provider chosen by the provider order. Nothing is stored except the step log
  in memory for the current task. The agent can tap/type in other apps; send/delete/pay/call-like actions require the user's Allow;
  finance apps, Play Store and VoiceDock itself are blocked; PIN/OTP/card-like text is never typed.
- **Accounts (0.5)**: ChatGPT sign-in tokens (access/refresh/ID token) and OpenRouter keys are stored encrypted (Keystore AES-GCM),
  never logged or put in URLs. The browser returns to `127.0.0.1` on the phone (a one-shot local listener that only accepts the
  callback path). Sign-out revokes the ChatGPT refresh token at OpenAI and deletes the tokens; the account row (e-mail, issued
  client id) remains until the user deletes it. A random per-phone id (`ext_agent_host_id`) is sent to OpenAI at sign-in.
  ChatGPT plan usage counts against the user's ChatGPT limits; no billing switch happens silently.
- **No analytics, no ads, no crash reporting.** (The only "accounts" are the AI accounts the user adds.)
- **Failed audio clips** (network/limit) are kept in app-private storage until retried, so speech is never silently lost.
- **Clipboard**: written only when the user taps Copy/Insert; never read in the background.
- **Overlay (v0.6)**: draws the mic bubble, a compact context card and a focusable assistant chat
  window. It never reads other apps *in the background*; a single on-tap probe is described above.
  The context card is non-focusable so the underlying app keeps its cursor.
- **Accessibility (optional)**: ignores all accessibility events. *Insert*: looks up the focused input field (never password
  fields), checks the target and writes the approved text or copies it instead. *Explain screen*: with the separate
  consents enabled on Android 14+, captures the current window and its visible text (password fields skipped).
  *Agent (0.5)*: reads the screen and performs gestures only
  during a task the user started (see above). *Assistant bubble (v0.6)*: one on-tap probe as described
  above. No background screen reading; nothing stored.
  Screen content is treated as untrusted (prompt-injection guard in the system prompt).
- **Logs**: only exception class names; never transcript text.
- **Deletion**: Drafts screen → open a draft → Delete (confirmation). Uninstalling removes everything.
