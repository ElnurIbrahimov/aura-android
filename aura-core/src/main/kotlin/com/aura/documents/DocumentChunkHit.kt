package com.aura.documents

import androidx.room.Embedded

/**
 * A chunk plus the document name, which is the one thing a citation needs and
 * the chunk row does not carry.
 *
 * `@Embedded` rather than a second query per hit: a 200-candidate window would
 * otherwise be 200 lookups of a name that is the same for every chunk of the
 * same document.
 */
data class DocumentChunkHit(
    @Embedded val chunk: DocumentChunkEntity,
    val documentName: String,
)
