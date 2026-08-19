package com.aura.backup

import java.io.File

/**
 * What the rest of the app needs from backup.
 *
 * Extracted from [BackupManager] because that class became impossible to
 * substitute in a test. Adding a 75th constructor parameter made
 * `mockk<BackupManager>(relaxed = true)` stop *intercepting* — the mock is still
 * created and the class still loads, but calls fall through to the real
 * implementation, which then throws a `NullPointerException` on whichever
 * injected collaborator it touches first. Six tests across both modules broke at
 * once, and none of them named backup in their failure: they read as null-pointer
 * bugs in production code.
 *
 * The mechanism was **not** identified, and two plausible explanations were
 * measured and refuted — the constant pool holds 3,634 entries of a possible
 * 65,535, and the largest method is 44,258 bytes of a possible 65,535.
 * Constructor arity alone is not it either: 75 parameters mock fine when the
 * added one has a type the class already referenced. What is certain is that
 * `BackupManager` — 345 KB of class file, a 75-parameter constructor, and one
 * `AuraBackup(...)` expression with 75 named arguments — sits at some threshold
 * where instrumentation degrades silently.
 *
 * An interface sidesteps all of it rather than guessing: mocking an interface
 * uses a JDK proxy and needs no bytecode rewriting of the implementation at all.
 * It is also the better dependency. `BackupWorker` and `BackupViewModel` use
 * eight methods; they had been depending on a class with seventy-five
 * collaborators to get them.
 *
 * **This is a seam, not the fix.** ENGINEERING_HISTORY §3 already names
 * `BackupManager` as the largest file in the project and the cost of eleven
 * separate databases. Splitting it is the real work; this makes the tests honest
 * in the meantime, and the next person to add a table to the backup will find
 * out the hard way if it is left much longer.
 *
 * [BackupManager.RestoreCounts], [BackupManager.RestoreMode] and
 * [BackupManager.InterruptedRestore] stay nested in the implementation
 * deliberately: hoisting them would touch every call site for no benefit today,
 * and they move naturally when the class is split.
 */
interface BackupService {

    /** Everything on the device, as one serialisable object. */
    suspend fun snapshot(appVersionName: String): AuraBackup

    fun encodeToJson(backup: AuraBackup): String

    fun decodeFromJson(bytes: String): AuraBackup

    /**
     * True when [text] is a sealed envelope from [com.aura.security.BackupCrypto]
     * rather than plain JSON — the shape [BackupWorker] writes every automatic
     * backup in.
     *
     * Content, never filename: the SAF provider decides the final display name, so
     * a `.json` suffix says nothing about what is inside.
     */
    fun isSealed(text: String): Boolean

    /**
     * Decrypt a sealed backup, returning the plaintext JSON for [decodeFromJson],
     * or null if it could not be opened.
     *
     * Null covers a wrong passphrase, a truncated file, an unrecognised header and
     * a corrupt payload alike, because the crypto refuses to distinguish them —
     * see `BackupCrypto.open`. Callers must not invent a distinction it declines
     * to make; say "wrong passphrase, or the file is damaged" and mean it.
     *
     * The counterpart of [BackupWorker]'s seal. It had none for the whole time
     * automatic backups existed: `BackupCrypto.open` was written, tested and never
     * called, so every weekly backup was an envelope no code in this project could
     * open. Restore read the sealed bytes straight into `decodeFromJson` and
     * reported "Unexpected JSON token at offset 0".
     */
    suspend fun unseal(text: String, passphrase: String): String?

    suspend fun restore(
        backup: AuraBackup,
        mode: BackupManager.RestoreMode = BackupManager.RestoreMode.MERGE,
    ): BackupManager.RestoreCounts

    /** A restore that started and never finished, if the last one died mid-write. */
    fun consumeInterruptedRestore(): BackupManager.InterruptedRestore?

    fun pruneCacheExports(now: Long = System.currentTimeMillis()): Int

    fun defaultExportFileName(now: Long = System.currentTimeMillis()): String

    fun exportFile(): File
}
