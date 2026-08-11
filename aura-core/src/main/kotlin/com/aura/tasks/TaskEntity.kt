package com.aura.tasks

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(
    tableName = "tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["status", "dueAt"]),
        Index(value = ["status", "salience"]),
    ],
)
@Serializable
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String = "",
    val createdAt: Long,
    val dueAt: Long? = null,
    val completedAt: Long? = null,
    val status: String = "pending", // pending | done | cancelled
    val recurrence: String? = null,
    val priority: Int = 0, // 0..3
    val tags: String = "",

    /**
     * How much this task currently deserves your attention, 0..1.
     *
     * Distinct from [priority], which is what you said it was worth when you
     * wrote it down and which never changes by itself. Salience is what the
     * evidence says it is worth *now*: it decays with neglect, drops when you
     * defer, drops hard when a deadline passes and nothing breaks, and rises
     * when you touch or mention the thing.
     *
     * Below [com.aura.tasks.TaskSalience.QUIET_THRESHOLD] the task leaves the
     * default list. It is never deleted and search always sees it.
     */
    val salience: Double = 1.0,

    /** Last time you did anything to this task. 0 means "never, use createdAt". */
    val lastTouchedAt: Long = 0L,

    /**
     * How many times you have actively pushed this away.
     *
     * Counted rather than merely timestamped because repetition is the signal:
     * deferring once is scheduling, deferring nine times is a decision you have
     * not admitted to yet.
     */
    val deferCount: Int = 0,

    /** When this dropped below the threshold. 0 while it is still in the main list. */
    val quietSince: Long = 0L,

    /** Why it came back, in words, for the one time it matters. Blank otherwise. */
    val revivedReason: String = "",
)
