package io.github.nimbice.fanos.core.model

/**
 * The search boxes that share a history of recent searches: [Novels], every box that looks for novels (Browse's, the
 * results of searching all sources, a site's own, moving a novel, the library's); [Chapters], a novel's chapter search.
 */
enum class SearchHistory {
    Novels,
    Chapters,
    ;

    companion object {
        /** How many searches a history keeps, the newest. */
        const val KEPT = 5
    }
}
