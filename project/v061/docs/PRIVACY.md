# Privacy and data handling — VoiceDock AI v0.6.1

## Screen context and the assistant bubble

- The floating assistant does not require microphone access. It runs with a persistent foreground-service notification.
- Screen context is read only after the user has enabled the separate screen-context consent and taps the bubble/action. The first probe holds only counts, field flags, package/app metadata and a secret-hit count in memory; it does not persist screen text.
- Known finance/blocked packages and VoiceDock's own window are rejected before traversing child nodes. Password text and password-field children are not read. Incomplete scans fail closed. Android does not expose every other app's `FLAG_SECURE` state to this probe, so this is a heuristic—not a security boundary. Turn screen-context consent off before sensitive apps.
- The same platform-free `SecretDetector` is used by screen probing, agent pre-flight/type checks, safety-oracle checks and accessibility insertion. It normalizes Unicode decimal digits (including Bengali), detects OTP/PIN wording with nearby 4–8 digit runs, treats unexplained 6–8 digit runs conservatively, and only treats card-like text as a card when it passes Luhn. Years, prices, order references and postcodes are contextual exceptions. False negatives and false positives remain possible.
- Free-form bubble questions may be sent to the configured AI provider. The provider/model is shown with the answer. Existing consent, local-only, paid-provider and free-first policies are applied by the same provider router used elsewhere in the app. The user should not type confidential material into the bubble unless they intend to send it to that provider.
- Draft reply is offered only from a detected editable app screen. VoiceDock hides its windows, waits briefly, verifies that the same package is still active, then reads visible text once (up to 4,000 characters). It scans the captured text before making a provider call; if a likely code is found, no conversation text is sent. Otherwise the conversation is supplied as untrusted text, and the AI is instructed not to follow instructions contained in messages. Screen text is transient and is not saved by the app. Detection and prompt-injection defenses are heuristic and cannot guarantee a secret will never be missed.
- A draft is only a suggestion. VoiceDock never sends a message. Insertion still needs the user's explicit tap and re-checks the target package and focused field; if verification fails, the text is copied instead.
- The bubble's empty event-memory placeholder was removed. v0.6.1 does not collect background notifications or have persistent event memory.

## Other data handling

- **Microphone and speech:** used only after Start/Resume. Android's speech service may send audio to its own service in online mode. Gemini speech uses the user's key only with Gemini/cloud consent and when local-only mode is off. Offline Whisper runs on-device; model download occurs only after the user taps Download.
- **Drafts and knowledge:** kept in the app-private Room database. Android backup is disabled and data-extraction rules exclude application data from cloud backup/device transfer.
- **Keys and tokens:** user API keys, IMAP app passwords, GitHub tokens and ChatGPT/OpenRouter credentials are stored encrypted with Android Keystore-backed AES-GCM. They are not intentionally written to diagnostic reports.
- **Connectors:** Google Drive and Gmail OAuth use read-only scopes. IMAP opens Gmail's Inbox read-only. No connector send/write/delete operations are included.
- **Agent:** runs only after the user starts a task. It may send a visible element list and, when allowed/needed, a screenshot to the configured provider. The independent safety gate blocks known financial screens and secret-like text before provider calls and refuses PIN/OTP/card-like typing; this is heuristic. Send/delete/pay/call-like actions require a separate Allow tap.
- **Clipboard:** VoiceDock writes text only after the user taps Copy/Insert; it does not read clipboard contents in the background. Automatic clipboard clearing is not implemented in this phase.
- **No analytics or ads.** The notification listener remains a no-op; v0.6.1 does not collect notifications in the background.

## Local crash diagnostics (new in v0.6.1)

- Reports are stored only in the app-private `files/crash-reports` directory. Nothing is uploaded automatically.
- Crash logging is enabled by default in debug builds and disabled by default in release builds; the user can change the Diagnostics switch. The newest 10 reports are retained.
- Before saving, the scrubber attempts to remove e-mail addresses, common API keys, JWTs, credential assignments and long digit runs. Scrubbing is not perfect; inspect a report before sharing it. Sharing happens only when the user taps Share and chooses another app. View and delete/delete-all controls are available in Settings → Diagnostics.
- The debug-only “Create test crash log” action writes a synthetic sample; it does not terminate the app.

## Storage and deletion

- App-private local files and databases are deleted when the user clears app storage or uninstalls VoiceDock. Drafts and knowledge also have their in-app deletion controls. Crash reports can be deleted individually or all at once in Diagnostics.
- The source archive may exclude the public debug signing keystore; the supplied debug APK remains signed with the v0.6.0 debug certificate for an in-place update. A release key is always user-owned and must be backed up separately.
