package io.github.nimbice.fanos.core.model

/** A place the reader marked: a paragraph ([block], a leaf of the chapter's content) of a chapter, and its text's start. */
data class Bookmark(
    val id: Long,
    val chapterId: Long,
    val chapterTitle: String,
    val block: Int,
    val snippet: String,
    val createdAt: Long,
)
