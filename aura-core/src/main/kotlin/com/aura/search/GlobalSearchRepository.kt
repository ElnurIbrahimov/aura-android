package com.aura.search

import com.aura.agent.ConversationDao
import com.aura.agent.ConversationEntity
import com.aura.hands.Hand
import com.aura.hands.HandDao
import com.aura.kg.KnowledgeGraphDao
import com.aura.kg.NodeEntity
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryEntity
import com.aura.memory.escapeLikeWildcards
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single unified search result from any data source.
 */
data class GlobalSearchResult(
    val id: kotlin.String,
    val title: kotlin.String,
    val subtitle: kotlin.String,
    val category: SearchCategory,
    /** Route to navigate to when this result is tapped. */
    val route: kotlin.String,
)

enum class SearchCategory(val label: kotlin.String) {
    CONVERSATION("Chat"),
    MEMORY("Memory"),
    TASK("Task"),
    HAND("Hand"),
    SKILL("Skill"),
    KNOWLEDGE("Knowledge"),
}

/**
 * Searches across all Aura data sources in parallel.
 * Returns a flat list of [GlobalSearchResult] ranked by relevance
 * (exact title matches first, then content matches).
 */
@Singleton
class GlobalSearchRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val memoryDao: MemoryDao,
    private val taskDao: TaskDao,
    private val handDao: HandDao,
    private val kgDao: KnowledgeGraphDao,
    private val skillsStore: SkillsStore? = null,
) {
    suspend fun search(query: kotlin.String, limit: Int = 20): List<GlobalSearchResult> {
        if (query.isBlank()) return emptyList()
        val escaped = escapeLikeWildcards(query.trim())
        val q = query.trim()

        return coroutineScope {
            // searchVisible filters soft-deleted rows (deletedAt IS NULL).
            // The un-filtered conversationDao.search() would let users
            // find soft-deleted conversations in the global search drawer
            // and tap them to resume — sidestepping the soft-delete/undo
            // workflow. Same fix as ConversationStore.search() at the
            // DAO level; this is the only caller of the un-filtered variant.
            val conversations = async { runCatching { conversationDao.searchVisible(escaped, limit) }.onFailure { android.util.Log.w("GlobalSearch", "conversation search failed: ${it.message}") }.getOrDefault(emptyList()) }
            val memories = async { runCatching { memoryDao.searchByText("%$escaped%", limit) }.onFailure { android.util.Log.w("GlobalSearch", "memory search failed: ${it.message}") }.getOrDefault(emptyList()) }
            val tasks = async { runCatching { taskDao.all() }.onFailure { android.util.Log.w("GlobalSearch", "task search failed: ${it.message}") }.getOrDefault(emptyList()) }
            val hands = async { runCatching { handDao.getAll() }.onFailure { android.util.Log.w("GlobalSearch", "hand search failed: ${it.message}") }.getOrDefault(emptyList()) }
            val skills = async { runCatching { skillsStore?.skills?.value ?: emptyList() }.onFailure { android.util.Log.w("GlobalSearch", "skills search failed: ${it.message}") }.getOrDefault(emptyList()) }
            val kgNodes = async { runCatching { kgDao.searchNodes(escaped, limit) }.onFailure { android.util.Log.w("GlobalSearch", "KG search failed: ${it.message}") }.getOrDefault(emptyList()) }

            val results = mutableListOf<GlobalSearchResult>()

            // Conversations
            conversations.await().forEach { c ->
                results.add(GlobalSearchResult(
                    id = c.id,
                    title = c.title.ifBlank { "Untitled chat" },
                    subtitle = "Conversation",
                    category = SearchCategory.CONVERSATION,
                    route = "chat?convId=${c.id}",
                ))
            }

            // Memories
            memories.await().forEach { m ->
                results.add(GlobalSearchResult(
                    id = m.id,
                    title = m.content.take(60),
                    subtitle = "[${m.category}] ${m.source}",
                    category = SearchCategory.MEMORY,
                    route = "memory",
                ))
            }

            // Tasks (in-memory filter since TaskDao has no search)
            tasks.await().filter { it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }.take(limit).forEach { t ->
                results.add(GlobalSearchResult(
                    id = t.id,
                    title = t.title,
                    subtitle = if (t.status == "done") "✓ Done" else "Pending",
                    category = SearchCategory.TASK,
                    route = "tasks",
                ))
            }

            // Hands (in-memory filter)
            hands.await().filter { it.name.contains(q, ignoreCase = true) }.take(limit).forEach { h ->
                results.add(GlobalSearchResult(
                    id = h.id,
                    title = h.name,
                    subtitle = if (h.enabled) "Active" else "Disabled",
                    category = SearchCategory.HAND,
                    route = "hands",
                ))
            }

            // Skills (in-memory filter)
            skills.await().filter { it.name.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }.take(limit).forEach { s ->
                results.add(GlobalSearchResult(
                    id = s.id,
                    title = s.name,
                    subtitle = s.description.take(60),
                    category = SearchCategory.SKILL,
                    route = "skills",
                ))
            }

            // Knowledge graph nodes
            kgNodes.await().take(limit).forEach { n ->
                results.add(GlobalSearchResult(
                    id = n.id,
                    title = n.label,
                    subtitle = n.type,
                    category = SearchCategory.KNOWLEDGE,
                    route = "knowledge_graph",
                ))
            }

            // Rank: exact title matches first, then by category priority
            results.sortedWith(
                compareByDescending<GlobalSearchResult> { it.title.startsWith(q, ignoreCase = true) }
                    .thenBy { it.category.ordinal }
            ).take(limit * 3)
        }
    }
}