package com.aura.memory.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One corpus row.
 *
 * Ages are RELATIVE DAYS, never absolute epoch millis. An absolute timestamp
 * would make the corpus age between runs, drifting the recency and decay
 * signals until a suite that passed in August fails in December for reasons
 * having nothing to do with retrieval.
 */
@Serializable
data class EvalMemory(
    val id: String,
    val content: String,
    val category: String = "fact",
    val scope: String = "general",
    val importance: Float = 0.5f,
    @SerialName("created_days_ago") val createdDaysAgo: Long = 0,
    @SerialName("accessed_days_ago") val accessedDaysAgo: Long = 0,
    @SerialName("access_count") val accessCount: Int = 0,
    @SerialName("decay_score") val decayScore: Float = 1.0f,
    val tags: String = "",
)

/**
 * One judged query.
 *
 * [judgments] are GRADED: 0 irrelevant, 1 related, 2 relevant, 3 ideal. A query
 * whose judgments contain no grade >= 1 is a should-return-nothing case, which
 * is a first-class part of the set rather than an omission — recall inventing
 * context for a question it has no answer to is worse than silence.
 */
@Serializable
data class EvalQuery(
    val qid: String,
    /**
     * Which weakness this query probes. Drives the per-class breakdown, which
     * is where the interesting movement shows up — an aggregate hides that one
     * class doubled while the rest stood still.
     */
    @SerialName("class") val queryClass: String,
    val query: String,
    /** Prior turns, for the deictic class. Blank disables query rewriting. */
    @SerialName("recent_context") val recentContext: String = "",
    val scope: String = "general",
    val judgments: Map<String, Int> = emptyMap(),
)

/** A committed scorecard, for no-regression gating. */
@Serializable
data class EvalBaseline(
    val label: String,
    @SerialName("ndcg10") val ndcg10: Double,
    @SerialName("ndcg5") val ndcg5: Double,
    @SerialName("recall5") val recall5: Double,
    val mrr: Double,
    @SerialName("zero_result_rate") val zeroResultRate: Double,
    @SerialName("correctly_empty_rate") val correctlyEmptyRate: Double,
    /**
     * Absolute tolerance. Not zero: BM25 is float arithmetic and tie order can
     * legitimately flip across JDK versions, which would make a zero-tolerance
     * gate fail for reasons unrelated to retrieval.
     */
    val tolerance: Double = 0.005,
    val note: String = "",
)

/**
 * Loads the eval fixtures from the test classpath.
 *
 * `src/test/resources` rather than `assets/`: the assets path exists for
 * Robolectric's Android-resource emulation, and these are plain data files that
 * do not need it.
 */
object EvalFixtures {

    private const val DIR = "/retrieval-eval"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun corpus(): List<EvalMemory> = readJsonl(DIR + "/corpus.jsonl") { json.decodeFromString(it) }

    fun queries(): List<EvalQuery> = readJsonl(DIR + "/queries.jsonl") { json.decodeFromString(it) }

    fun baseline(): EvalBaseline? = readTextOrNull(DIR + "/baseline.json")?.let { json.decodeFromString(it) }

    /**
     * Whether the fixtures are still the shipped scaffold rather than a real
     * corpus.
     *
     * The scaffold is synthetic, and synthetic corpora have uniform style and
     * no natural vocabulary-overlap structure — every change to retrieval looks
     * like an improvement against them. Absolute scores from it mean nothing.
     * It is here so the harness is exercised and its own tests are not vacuous,
     * not so anyone can point at the numbers.
     */
    fun isScaffold(): Boolean = corpus().any { it.tags.contains("scaffold") }

    private fun <T> readJsonl(path: String, parse: (String) -> T): List<T> {
        val text = readTextOrNull(path)
            ?: error(
                "Missing eval fixture $path. This must FAIL rather than skip: a harness that " +
                    "reports OK over an empty file list is exactly the defect it exists to catch.",
            )
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .map(parse)
            .toList()
    }

    private fun readTextOrNull(path: String): String? =
        EvalFixtures::class.java.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}
