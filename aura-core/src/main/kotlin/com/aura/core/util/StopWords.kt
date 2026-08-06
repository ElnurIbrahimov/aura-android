package com.aura.core.util

/**
 * Shared English stopword list for keyword extraction and lexical search.
 *
 * Used by memory recall candidate generation (a query word like "the" or
 * "want" LIKE-matches nearly every row, flooding the candidate pool with
 * noise) and by [com.aura.agent.recentTopics]'s word-frequency heuristic.
 */
object StopWords {
    val ENGLISH: Set<String> = setOf(
        // Function words
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "must", "can", "shall", "to", "of", "in",
        "for", "on", "with", "at", "by", "from", "as", "into", "about",
        "but", "or", "and", "not", "no", "if", "then", "else", "when",
        "this", "that", "these", "those", "it", "its", "i", "you", "he",
        "she", "we", "they", "me", "him", "her", "us", "them", "my", "your",
        "their", "which", "what", "who", "how", "why", "where",
        // High-frequency fillers observed in compaction output
        "just", "like", "some", "more", "there", "also", "than", "other",
        "want", "need", "make", "using", "here", "only", "much", "very",
        "really", "still", "after", "before", "over", "back", "well",
        "even", "know", "think", "please", "tell",
        // URL noise
        "https", "http", "github", "docs", "www", "com",
    )
}
