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

## Ground rules

1. Raw Ground frames (screen/camera/mic) are never persisted unless the user explicitly saves an artifact from them.
2. Accessibility UI snapshots are redacted before reaching the model: password fields, sensitive nodes, and disallowed packages are stripped.
3. Conversation history and memory are local-only. No cloud sync.
4. Backup exports never include secrets, embeddings, or running job state.
5. Remote MCP/Bridge tools cannot read local secrets.
6. The user can inspect, edit, and delete any stored personal data at any time.
7. Taste/preference signals are local-only with explicit retention controls.