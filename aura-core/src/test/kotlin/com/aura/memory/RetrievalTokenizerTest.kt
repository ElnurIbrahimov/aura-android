package com.aura.memory

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two tokenizers must agree, because `corpusDocFreq` is keyed by one and
 * read by the other.
 *
 * `MemoryStore` used to split the query on whitespace to build the FTS `MATCH`
 * expression and the document-frequency probes; `BM25` split on non-alphanumeric
 * to build its index. Any word they tokenized differently had its corpus
 * document frequency computed, stored under one key, and looked up under
 * another — so it silently fell back to candidate-set `df`, where every
 * candidate contains the term by construction and IDF collapses to the floor.
 *
 * That is the exact defect corpus statistics were introduced to fix, and it
 * survived for every token class the two splitters disagreed on.
 */
class RetrievalTokenizerTest {

    // ---- the invariant ---------------------------------------------------

    @Test
    fun `every query term appears among the index tokens`() {
        // The property that makes corpusDocFreq lookups land. If a query term
        // is not a token BM25 will look up, its corpus df is dead weight.
        val samples = listOf(
            "don't forget the milk",
            "kotlin/native cross-compilation",
            "read-write lock semantics",
            "what's the plan for tomorrow",
            "e-mail me the co-ordinates",
            "naïve café façade",
            "path/to/some/file.txt",
            "version 2.5.1 release notes",
        )
        samples.forEach { text ->
            val queryTerms = RetrievalTokenizer.queryTerms(text, maxTerms = 24)
            val indexTokens = RetrievalTokenizer.indexTokens(text).toSet()
            val orphaned = queryTerms.filterNot { it in indexTokens }
            assertTrue(
                orphaned.isEmpty(),
                "query terms with no matching index token in \"$text\": $orphaned — " +
                    "their corpus document frequency would be computed and never read",
            )
        }
    }

    @Test
    fun `an apostrophe is split the same way on both sides`() {
        // The motivating case. Pre-fix the FTS side produced "don't" and the
        // BM25 side produced "don" + "t", so the df computed for "don't" was
        // never looked up.
        assertEquals(listOf("don", "t", "forget"), RetrievalTokenizer.words("don't forget"))
        // "t" and "don" are both short or stopword-adjacent; what matters is
        // that whatever survives the query filter is also an index token.
        val terms = RetrievalTokenizer.queryTerms("don't forget", maxTerms = 24)
        val tokens = RetrievalTokenizer.indexTokens("don't forget").toSet()
        assertTrue(terms.all { it in tokens })
    }

    @Test
    fun `hyphens and slashes split identically on both sides`() {
        assertEquals(listOf("read", "write", "lock"), RetrievalTokenizer.words("read-write lock"))
        assertEquals(listOf("kotlin", "native"), RetrievalTokenizer.words("kotlin/native"))
    }

    // ---- query terms -----------------------------------------------------

    @Test
    fun `query terms drop stopwords and short tokens`() {
        val terms = RetrievalTokenizer.queryTerms("the plan for a big migration", maxTerms = 24)
        assertTrue("the" !in terms, "stopword survived: $terms")
        assertTrue("for" !in terms, "stopword survived: $terms")
        assertTrue("a" !in terms, "one-character token survived: $terms")
        assertTrue("migration" in terms)
    }

    @Test
    fun `query terms are deduplicated and capped`() {
        val terms = RetrievalTokenizer.queryTerms("kotlin kotlin kotlin android", maxTerms = 24)
        assertEquals(terms.distinct(), terms, "duplicates survived: $terms")

        val many = (1..50).joinToString(" ") { "token$it" }
        assertEquals(24, RetrievalTokenizer.queryTerms(many, maxTerms = 24).size)
    }

    @Test
    fun `index tokens keep stopwords`() {
        // Deliberate: BM25 handles common terms through IDF, which is more
        // principled than a hand-maintained list — and dropping them would also
        // shorten docLength in a way the length normalisation is not expecting.
        assertTrue("the" in RetrievalTokenizer.indexTokens("the plan"))
    }

    // ---- bigrams ---------------------------------------------------------

    @Test
    fun `index tokens append adjacent bigrams`() {
        val tokens = RetrievalTokenizer.indexTokens("kotlin coroutines are hard")
        assertTrue("kotlin_coroutines" in tokens)
        assertTrue("coroutines_are" in tokens)
        assertTrue("are_hard" in tokens)
        // Not a bigram of non-adjacent words.
        assertTrue("kotlin_hard" !in tokens)
    }

    @Test
    fun `bigrams can be turned off`() {
        val tokens = RetrievalTokenizer.indexTokens("kotlin coroutines", bigrams = false)
        assertEquals(listOf("kotlin", "coroutines"), tokens)
    }

    @Test
    fun `query bigrams match the index bigrams they will be looked up against`() {
        // The df probe has to produce the same string BM25 will look up, or the
        // probe is wasted in exactly the way the unigram case was.
        val text = "kotlin coroutines are genuinely hard"
        val probed = RetrievalTokenizer.queryBigrams(text, maxTerms = 24)
        val indexed = RetrievalTokenizer.indexTokens(text).toSet()
        val orphaned = probed.filterNot { it in indexed }
        assertTrue(orphaned.isEmpty(), "probed bigrams that BM25 will never look up: $orphaned")
    }

    @Test
    fun `query bigrams skip short words to match the query-term filter`() {
        // "are" is three characters and survives; "a" would not. The filter has
        // to be consistent or the probe list drifts from what is scored.
        val bigrams = RetrievalTokenizer.queryBigrams("a big migration", maxTerms = 24)
        assertTrue(bigrams.none { it.startsWith("a_") }, "one-character word entered a bigram: $bigrams")
        assertTrue("big_migration" in bigrams)
    }

    // ---- edge cases ------------------------------------------------------

    @Test
    fun `empty and punctuation-only input yields nothing`() {
        assertEquals(emptyList(), RetrievalTokenizer.words(""))
        assertEquals(emptyList(), RetrievalTokenizer.words("!!! ??? ..."))
        assertEquals(emptyList(), RetrievalTokenizer.queryBigrams("", maxTerms = 24))
    }

    @Test
    fun `a single word produces no bigrams`() {
        assertEquals(emptyList(), RetrievalTokenizer.queryBigrams("kotlin", maxTerms = 24))
        assertEquals(listOf("kotlin"), RetrievalTokenizer.indexTokens("kotlin"))
    }

    @Test
    fun `non-ascii letters survive`() {
        // The split regex keeps the À-￿ range on purpose; dropping
        // them would make every non-English memory unsearchable.
        assertEquals(listOf("naïve", "café"), RetrievalTokenizer.words("naïve café"))
        assertEquals(listOf("привет", "мир"), RetrievalTokenizer.words("привет мир"))
    }

    @Test
    fun `case is normalised`() {
        assertEquals(listOf("kotlin", "android"), RetrievalTokenizer.words("Kotlin ANDROID"))
    }
}
