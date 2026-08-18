package com.aura.documents

import com.aura.memory.BM25
import com.aura.memory.FtsQuery
import com.aura.memory.RetrievalConfig
import com.aura.memory.RetrievalTokenizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One retrieved passage, with enough of its address to be quoted honestly.
 *
 * [documentName] and [ordinal] are what a person needs to find it again;
 * [charStart]/[charEnd] are what a program needs. Both are carried because a
 * citation that cannot be checked is decoration.
 */
data class DocumentPassage(
    val documentId: String,
    val documentName: String,
    val chunkId: String,
    val ordinal: Int,
    val charStart: Int,
    val charEnd: Int,
    val text: String,
    val score: Float,
) {
    /** "world-bible.pdf · part 4" — the form a citation takes in an answer. */
    val citation: String get() = "$documentName · part ${ordinal + 1}"
}

/**
 * Lexical search over imported documents, with corpus statistics of its own.
 *
 * ## Why this is separate from `MemoryStore.query`
 *
 * Not a style preference. Every consumer of recall — RRF fusion, the reranker,
 * scoped correction demotion, `touch`, the evolution hooks, the retrieval-label
 * harvest — is typed on `MemoryEntity`, and a chunk has no `scope`,
 * `decayScore`, `accessCount`, `importance` or `retiredAt`. Threading chunks
 * through as synthetic memories would put ids that do not exist in `memories`
 * into `touch`, into evolution evidence, and into the retrieval labels the eval
 * corpus is built from — the last of which would corrupt the measurement that
 * has to settle whether any of this is an improvement.
 *
 * So documents are searched as documents. Recall is untouched, which also means
 * this cannot make recall worse — a property worth having while the eval
 * harness is still waiting on a corpus.
 *
 * ## Ranking
 *
 * FTS4 selects a candidate window, BM25 ranks it, and both `N` and `df` come
 * from the chunk corpus rather than from the candidate set. That last point is
 * the one `MemoryFtsEntity`'s KDoc records the hard way: IDF computed over
 * candidates that all contain a query term by construction goes negative for
 * exactly the terms that should discriminate, and clamps to the floor.
 *
 * No vector arm. `RetrievalConfig.vectorPoolSize` is measured off at 0.4837
 * against 0.7976 because a 384-dimension hash sketch injects near-noise, and
 * document chunks are not embedded on the import path anyway. When there is a
 * real embedder and `allWithoutEmbeddings` has a caller, this is where the arm
 * goes — behind a measurement, not ahead of one.
 */
@Singleton
class DocumentChunkRetrieval @Inject constructor(
    private val chunkDao: DocumentChunkDao,
    private val config: RetrievalConfig = RetrievalConfig.DEFAULT,
) {

    /**
     * The passages best matching [text], best first.
     *
     * Empty when nothing is imported, when the query is all stopwords, or when
     * no chunk shares a term with it. Empty is a real answer here: the caller
     * is a tool that should say "nothing in your documents matches" rather than
     * return the least-bad passage, which is what a floorless ranker does.
     */
    suspend fun search(text: String, limit: Int = DEFAULT_LIMIT): List<DocumentPassage> {
        if (limit <= 0) return emptyList()
        val terms = RetrievalTokenizer.queryTerms(text, config.maxQueryTerms)
        if (terms.isEmpty()) return emptyList()
        val ftsQuery = FtsQuery.build(terms) ?: return emptyList()

        // Per document, not one window across all of them. A single query
        // ending `ORDER BY documentId, ordinal LIMIT n` takes a *prefix by
        // document*, so with two books the second was invisible for any query
        // the first also matched — arbitrarily and permanently, since document
        // ids are content hashes.
        //
        // Over-fetched within each document for the same reason the memory
        // path over-fetches: ordinal order is not a relevance signal, so at
        // the plain limit BM25 would be re-ranking "the first few chunks that
        // share a word".
        val documentIds = chunkDao.matchingDocumentIds(ftsQuery)
        if (documentIds.isEmpty()) return emptyList()
        val perDocument = (limit * config.ftsOverfetch * CANDIDATE_WIDTH)
            .coerceAtMost(MAX_CANDIDATES_PER_DOCUMENT)
        val candidates = documentIds
            .take(MAX_DOCUMENTS_SCANNED)
            .flatMap { chunkDao.searchFtsInDocument(ftsQuery, it, perDocument) }
        if (candidates.isEmpty()) return emptyList()

        val corpusSize = chunkDao.countChunks()
        val docFreq = mutableMapOf<String, Int>()
        for (term in terms) {
            val single = FtsQuery.build(listOf(term)) ?: continue
            docFreq[term] = runCatching { chunkDao.docFreq(single) }.getOrDefault(0)
        }

        val bm25 = BM25(
            documents = candidates.map { it.chunk.text },
            corpusSize = corpusSize,
            corpusDocFreq = docFreq,
            k1 = config.bm25K1,
            b = config.bm25B,
            idfFloor = config.bm25IdfFloor,
            bigrams = config.bm25Bigrams,
        )

        return candidates.indices
            .map { index -> index to bm25.normalizedScore(text, index) }
            .filter { (_, score) -> score > 0f }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .map { (index, score) ->
                val hit = candidates[index]
                DocumentPassage(
                    documentId = hit.chunk.documentId,
                    documentName = hit.documentName,
                    chunkId = hit.chunk.id,
                    ordinal = hit.chunk.ordinal,
                    charStart = hit.chunk.charStart,
                    charEnd = hit.chunk.charEnd,
                    text = hit.chunk.text,
                    score = score,
                )
            }
    }

    private companion object {
        const val DEFAULT_LIMIT = 5

        /**
         * Ceiling per document, so one enormous import cannot turn a search
         * into a full-table read now that the fetch is per document rather
         * than one bounded window.
         */
        const val MAX_CANDIDATES_PER_DOCUMENT = 200

        /**
         * Ceiling on documents scanned in one search. A personal library is
         * tens of files, not thousands; this bounds the query count rather
         * than expressing a belief about how many documents are useful.
         */
        const val MAX_DOCUMENTS_SCANNED = 50

        /**
         * Extra width on top of [RetrievalConfig.ftsOverfetch].
         *
         * The memory path's over-fetch is tuned against a store of a few
         * hundred rows. One imported book is on the order of a thousand chunks,
         * so the same multiplier covers proportionally far less of it.
         */
        const val CANDIDATE_WIDTH = 5
    }
}
