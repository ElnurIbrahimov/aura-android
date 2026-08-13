# Privacy Boundaries

## Data classification

| Class | Examples | Storage | Backup |
|-------|----------|---------|--------|
| Secret | API keys, OAuth tokens, passwords | SecureDataStore (Keystore) | Never |
| Personal | Memories, KG, profile, beliefs, taste signals | Room (local) | Plaintext JSON export, user-initiated — see note below |
| Artifact | Creative projects, media, canon | Room metadata + app-private files | `.aura` archive (no secrets) |
| Ephemeral | Ground frames, raw UI snapshots, streaming tokens | In-memory only | Never |
| Public | Tool definitions, provider lists, model catalogs | Room/cache | N/A |

The Personal export is **not encrypted**. `BackupManager.encodeToJson` is a
`kotlinx.serialization` `encodeToString` written to `aura-backup-<date>.json`, and
nothing on that path applies a cipher. What protects the user is omission, not
encryption: API keys, OAuth tokens and SMTP passwords stay in `SecureDataStore`
(AES-256-GCM under the Android Keystore) and are never written to the file. Every
memory, knowledge-graph node, belief and profile fact in it is readable by anything
that can read the file. This table said "Encrypted export" for several releases,
which is exactly the kind of claim a user would act on when deciding where to put
the backup.

The paragraph above describes the **manual** export only. The **automatic** one
(`BackupWorker`, weekly) *is* encrypted, and had to be: it writes unattended to a
user-chosen SAF folder which may well be one a cloud service syncs, so the file
leaves the device with nobody present to think about it. `BackupCrypto` seals it
with the same AES-256-GCM, under a key derived from a user passphrase via
PBKDF2-HMAC-SHA256 — deliberately **not** the Keystore key used everywhere else,
because that key dies with the device and an archive that cannot be opened after
the phone is gone is not a backup. Salt and iteration count travel in the envelope
so any device holding the passphrase can open it. There is no recovery path for a
forgotten passphrase, and there is not meant to be one.

## Ground rules

1. Raw Ground frames (screen/camera/mic) are never persisted unless the user explicitly saves an artifact from them.
2. Accessibility UI snapshots are redacted before reaching the model: password fields, sensitive nodes, and disallowed packages are stripped.
3. Text Aura **captured** rather than was **given** is scrubbed of incidental personal data before it can reach a provider — phone numbers, email addresses, long digit runs — via `com.aura.security.Redactor`. This runs at the capture sites (`UiTraversal`, `NotificationListTool`) and never at the network boundary: scrubbing everything on the way out would strip the number from "call mum on 0555 123 4567" and break the assistant to protect a number the user typed on purpose. The test is how Aura came to have the text, not what the text contains — which is why `ContactsSearchTool` is explicitly not scrubbed and `RedactorScopeTest` asserts it stays that way. Rule 2 covers what an app *declares* sensitive; this covers what merely happened to be on the screen.
4. Conversation history and memory are local-only. No cloud sync.
5. Backup exports never include secrets, embeddings, or running job state.
6. Remote MCP/Bridge tools cannot read local secrets.
7. The user can inspect, edit, and delete any stored personal data at any time.
8. Taste/preference signals are local-only with explicit retention controls.