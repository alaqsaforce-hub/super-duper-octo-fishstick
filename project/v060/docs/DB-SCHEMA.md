# Database schema (Room `voicedock.db`)

Version **2** (since 0.3.0). Exported JSON schemas: `app/schemas/com.voicedock.ai.data.database.AppDatabase/{1,2}.json`.
No destructive migration fallback is configured: a version without a migration fails loudly instead of deleting drafts.
Migrations live in `Migrations.ALL`: `MIGRATION_1_2` creates the knowledge tables and keeps every draft (tested from a real v1 file).
0.4.0 adds no tables (Whisper models are files in `filesDir/whisper/`; keyboard state is in settings).
0.5.0 adds no tables either. AI accounts are a JSON list in SharedPreferences `voicedock_accounts` / key `list_v1`
(`Account`: id, provider, label, kind = key | openrouter_login | chatgpt, paid, enabled, createdAt, email, clientId, subject,
loggedIn, planUsage) plus `chatgpt_host_id`. Secrets are in the encrypted SecretStore under `acct_<id>` (API key, or the
ChatGPT credential JSON: clientId, hostId, subject, email, idToken, accessToken, refreshToken, expiresAt, scopes).

## dictation_drafts
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| createdAt | INTEGER | epoch ms |
| updatedAt | INTEGER | epoch ms, indexed |
| rawText | TEXT | recognised text exactly as spoken (segments joined) |
| formattedText | TEXT | cleaned / user-edited text |
| segmentsJson | TEXT | JSON `[{"text":..,"provisional":bool}]` — lets a restored session merge correctly |
| status | TEXT | ACTIVE, REVIEW, INSERTED, SAVED, CANCELLED, CLEARED — indexed |
| language | TEXT | BCP-47 tag used for recognition |
| engine | TEXT | engine label |
| destination | TEXT | e.g. `accessibility:com.whatsapp`, `clipboard` |

"Unfinished" = ACTIVE or REVIEW → restored on app start (microphone stays off).
Storage is app-private; `allowBackup=false` and data-extraction rules exclude everything from cloud backup/device transfer.

## kb_documents (v2)
| Column | Type | Notes |
|---|---|---|
| id | TEXT PK | UUID |
| title | TEXT | file name / first line |
| source | TEXT | NOTE, SHARE, PROCESS_TEXT, FILE, DRIVE, GMAIL, GITHUB, DRAFT |
| sourceRef | TEXT | e.g. Drive file id, `draft:<id>`, repo path |
| mime | TEXT | original type |
| createdAt / updatedAt | INTEGER | epoch ms (`updatedAt` indexed) |
| charCount | INTEGER | normalised text length |
| contentHash | TEXT | SHA-256 of the normalised text (indexed) → duplicate detection |

## kb_chunks (v2)
| Column | Type | Notes |
|---|---|---|
| id | INTEGER PK autoincrement | also the rowid of the FTS row |
| docId | TEXT | → kb_documents.id (indexed) |
| ord | INTEGER | chunk order |
| text | TEXT | ~900-character sentence-aligned chunk, 120-character overlap |

## kb_chunks_fts (v2)
Standalone `FTS4(text, tokenize=simple)`; `rowid` = `kb_chunks.id`. The text is stored through `indexText()` (lower-case, zero-width
characters removed, punctuation → space) and queries use the same normaliser with prefix terms (`বেত*`).
`unicode61` was rejected because it tokenised Bengali inconsistently. Rows are written/deleted by the DAO (no triggers).

Not stored in the database: API keys/tokens (encrypted in SecretStore), OAuth access tokens (memory only), audio (only failed
Gemini clips in `filesDir/pending_audio/` until retried).
