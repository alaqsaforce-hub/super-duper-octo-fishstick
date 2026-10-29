# Google OAuth setup (Gmail + Drive, read-only) — optional (v0.6.1)

VoiceDock works without this: Drive files can be imported with the system file picker, and Gmail with
"select text → Save to VoiceDock" or IMAP + App password. OAuth adds **Drive search** and **Gmail search** inside the app.

Google only lets an app request Gmail/Drive access if the app is registered in a Google Cloud project.
No secret is put into the app: Android OAuth clients are identified by **package name + signing certificate SHA-1**.

## Values for this APK
| Field | Value |
|---|---|
| Package name | `com.voicedock.ai` |
| SHA-1 (debug key in `keystore/debug.keystore`) | `3A:0F:14:E7:3E:D4:F3:26:F1:D8:76:99:50:C3:E2:7E:1D:3C:EA:30` |
| Scopes | `https://www.googleapis.com/auth/gmail.readonly`, `https://www.googleapis.com/auth/drive.readonly` |

## Steps (about 10 minutes, free)
1. Open https://console.cloud.google.com/ → create a project (e.g. "VoiceDock").
2. **APIs & Services → Library**: enable **Gmail API** and **Google Drive API**.
3. **APIs & Services → OAuth consent screen** (Google Auth Platform):
   - User type **External**, app name "VoiceDock", your e-mail as support/developer contact.
   - **Data access / Scopes**: add `gmail.readonly` and `drive.readonly`.
   - **Audience → Test users**: add your own Gmail address. Leave the app in **Testing** (no Google verification needed
     for up to 100 test users; Google may ask you to re-consent every 7 days in Testing mode).
4. **Clients → Create client → Android**: package `com.voicedock.ai`, SHA-1 from the table above.
5. On the phone: VoiceDock → Settings → Connectors → **Connect Google (read-only)** → pick your account → allow.

If the app shows "OAuth not set up", the package/SHA-1 pair is not registered in the same project yet
(Google error code 10 = DEVELOPER_ERROR). A release build signed with a different key needs its own SHA-1 entry.

## What VoiceDock does with the token
- Kept in memory only (not written to disk); disappears when the app process ends.
- Used only for GET requests to `gmail.googleapis.com` and `www.googleapis.com/drive/v3`.
- No send, modify, delete or share calls exist in the code (`connectors/Connectors.kt`).
- Revoke any time: https://myaccount.google.com/permissions
