package com.aura.backup

import androidx.room.withTransaction
import com.aura.agent.AgentDatabase
import com.aura.agent.ConversationDatabase
import com.aura.agent.StrategyBanditDatabase
import com.aura.agentrun.AgentRunDatabase
import com.aura.dream.DreamConsolidationDatabase
import com.aura.evolution.EvolutionDatabase
import com.aura.hands.HandDatabase
import com.aura.memory.MemoryDatabase
import com.aura.proactive.ProactiveEventDatabase
import com.aura.profile.UserProfileDatabase
import com.aura.tasks.TaskDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-database transaction envelopes for [BackupManager].
 *
 * `withTransaction` appeared nowhere in this project. `purgeAll` was ~65
 * unguarded DELETEs across eleven databases, so a failure partway through left
 * some tables emptied and others not, with nothing recording which was which.
 *
 * **This exists as one class rather than eleven constructor parameters.**
 * `BackupManager` already takes 75, and `BackupService` records what happened at
 * exactly that width: `mockk(relaxed = true)` quietly stopped intercepting and
 * six tests failed with NPEs that named nothing. Widening it to 86 to add
 * transactions would be trading one data-integrity problem for a testing one.
 *
 * **Eleven per-database transactions are not one atomic restore**, and nothing
 * here pretends otherwise. A failure in the seventh database still leaves the
 * first six committed. What it buys is that each database is all-or-nothing
 * instead of arbitrary — "some databases restored, others untouched" rather than
 * "every database in an unknown state". A true envelope needs ordered commit or
 * fewer databases, and both are larger changes than this one.
 *
 * Nothing that is not a database write belongs inside these blocks. WorkManager
 * enqueues and Keystore decrypts are hoisted out by their callers: a SQLite
 * rollback cannot cancel a scheduled job, and holding a transaction open across
 * a Keystore round trip is a lock held on something with its own failure modes.
 */
@Singleton
class RestoreTransactions @Inject constructor(
    private val memory: MemoryDatabase,
    private val conversations: ConversationDatabase,
    private val hands: HandDatabase,
    private val tasks: TaskDatabase,
    private val proactive: ProactiveEventDatabase,
    private val profile: UserProfileDatabase,
    private val evolution: EvolutionDatabase,
    private val agents: AgentDatabase,
    private val dreams: DreamConsolidationDatabase,
    private val agentRuns: AgentRunDatabase,
    private val strategyBandit: StrategyBanditDatabase,
) {
    suspend fun <T> memory(block: suspend () -> T): T = memory.withTransaction(block)
    suspend fun <T> conversations(block: suspend () -> T): T = conversations.withTransaction(block)
    suspend fun <T> hands(block: suspend () -> T): T = hands.withTransaction(block)
    suspend fun <T> tasks(block: suspend () -> T): T = tasks.withTransaction(block)
    suspend fun <T> proactive(block: suspend () -> T): T = proactive.withTransaction(block)
    suspend fun <T> profile(block: suspend () -> T): T = profile.withTransaction(block)
    suspend fun <T> evolution(block: suspend () -> T): T = evolution.withTransaction(block)
    suspend fun <T> agents(block: suspend () -> T): T = agents.withTransaction(block)
    suspend fun <T> dreams(block: suspend () -> T): T = dreams.withTransaction(block)
    suspend fun <T> agentRuns(block: suspend () -> T): T = agentRuns.withTransaction(block)
    suspend fun <T> strategyBandit(block: suspend () -> T): T = strategyBandit.withTransaction(block)
}
