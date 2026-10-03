package io.github.nimbice.fanos.core.model

data class SourceInfo(
    val id: String,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val supportsPopular: Boolean,
    val supportsLatest: Boolean,
    /** What to search by, when it isn't a title. */
    val searchHint: String? = null,
    /** Its search finds creators by their names, not novels by their titles. */
    val searchesCreators: Boolean = false,
    /** Its chapters' groups are versions of the same chapters (uploads of them over again), not translators. */
    val groupsAreVersions: Boolean = false,
)
