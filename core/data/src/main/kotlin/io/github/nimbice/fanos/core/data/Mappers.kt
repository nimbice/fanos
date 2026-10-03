package io.github.nimbice.fanos.core.data

import io.github.nimbice.fanos.core.database.entity.ChapterEntity
import io.github.nimbice.fanos.core.database.entity.LibraryNovelRow
import io.github.nimbice.fanos.core.database.entity.NovelEntity
import io.github.nimbice.fanos.core.model.CatalogPage
import io.github.nimbice.fanos.core.model.Chapter
import io.github.nimbice.fanos.core.model.LibraryNovel
import io.github.nimbice.fanos.core.model.LibrarySection
import io.github.nimbice.fanos.core.model.Novel
import io.github.nimbice.fanos.core.model.NovelStatus
import io.github.nimbice.fanos.core.model.NovelSummary
import io.github.nimbice.fanos.source.api.NovelDetails
import io.github.nimbice.fanos.source.api.NovelsPage

internal fun NovelEntity.toModel() =
    Novel(
        id = id,
        sourceId = sourceId,
        url = url,
        title = customTitle ?: title,
        author = author,
        coverUrl = customCoverUrl ?: coverUrl,
        description = description,
        genres = genres,
        status = status,
        inLibrary = inLibrary,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        detailsFetchedAt = detailsFetchedAt,
        chaptersFetchedAt = chaptersFetchedAt,
        siteTitle = title,
        siteCoverUrl = coverUrl,
        chapterGroup = chapterGroup,
        chapterGroupChosen = chapterGroupChosen,
        chaptersNewestFirst = chaptersNewestFirst,
    )

internal fun ChapterEntity.toModel() =
    Chapter(
        id = id,
        novelId = novelId,
        url = url,
        title = title,
        index = sourceIndex,
        publishedAt = publishedAt,
        readAt = readAt,
        removedAt = removedAt,
        locked = locked,
        group = group,
        addedAt = addedAt,
    )

internal fun LibraryNovelRow.toModel() =
    LibraryNovel(
        novel.toModel(),
        chapterCount,
        unreadCount,
        sectionIds?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet()?.ifEmpty { null } ?: setOf(LibrarySection.DEFAULT_ID),
        savedChapters = savedCount,
        latestChapterAt = latestChapterAt,
    )

internal fun NovelsPage.toCatalogPage(sourceId: String) =
    CatalogPage(
        novels = novels.map { NovelSummary(sourceId, it.url, it.title, it.coverUrl, it.author, it.description) },
        hasNextPage = hasNextPage,
    )

internal fun NovelDetails.Status.toModel(): NovelStatus =
    when (this) {
        NovelDetails.Status.Unknown -> NovelStatus.Unknown
        NovelDetails.Status.Ongoing -> NovelStatus.Ongoing
        NovelDetails.Status.Completed -> NovelStatus.Completed
        NovelDetails.Status.Hiatus -> NovelStatus.Hiatus
        NovelDetails.Status.Cancelled -> NovelStatus.Cancelled
    }
