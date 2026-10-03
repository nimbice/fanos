package io.github.nimbice.fanos.core.model

data class Chapter(
    val id: Long,
    val novelId: Long,
    val url: String,
    val title: String,
    /** Position in the source's list, 0 for the first chapter. */
    val index: Int,
    val publishedAt: Long? = null,
    /** When it was marked read, epoch milliseconds; null while unread. */
    val readAt: Long? = null,
    /** When the source stopped listing it. Such chapters are kept if they were read or saved. */
    val removedAt: Long? = null,
    /** The site says the reader can't read it now (a Patreon post above their tier, say). */
    val locked: Boolean = false,
    /** The translation group that put it out, when the site says. */
    val group: String? = null,
    /** When this device first saw it listed, epoch milliseconds. */
    val addedAt: Long? = null,
) {
    val isRead: Boolean get() = readAt != null
}

/** A chapter that arrived in a library novel, as the Updates list shows it. */
data class RecentChapter(
    val chapterId: Long,
    val novelId: Long,
    val novelTitle: String,
    val coverUrl: String?,
    val chapterTitle: String,
    /** When the app first saw it, epoch milliseconds. */
    val arrivedAt: Long,
    val read: Boolean,
    /** Saved for reading offline. */
    val saved: Boolean,
    /** Waiting to be downloaded. */
    val waiting: Boolean,
)

/** A novel read lately, for the continue-reading widget: the chapter the reader was on, if any, and how many are unread. */
data class ContinueReading(
    val novelId: Long,
    val title: String,
    val coverUrl: String?,
    val chapterId: Long?,
    val chapterTitle: String?,
    val unread: Int,
)

/** A novel the reader read: when last, and the chapter they were on then, if it's still there. */
data class HistoryEntry(
    val novelId: Long,
    val novelTitle: String,
    val coverUrl: String?,
    val inLibrary: Boolean,
    val lastReadAt: Long,
    val chapterId: Long?,
    val chapterTitle: String?,
)

/** One of a novel's translation groups, and how many of the chapters listed are its. */
data class ChapterGroup(val name: String, val chapters: Int)

/**
 * Where the reader is in a chapter: the index of a block of its content (see ChapterContent) and a
 * character offset in that block. Unlike a scroll offset or page number, it survives a change of
 * font, size, screen or reading mode.
 */
data class ReadingPosition(val block: Int, val offset: Int = 0) {
    companion object {
        val Start = ReadingPosition(0, 0)
    }
}

data class ReadingProgress(
    val novelId: Long,
    val chapterId: Long,
    val position: ReadingPosition,
    val updatedAt: Long,
)
