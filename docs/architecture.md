# Architecture

Fanos is a ground-up rebuild of NovelLibrary. The September 2026 review of that codebase found
five root causes behind most of its bugs, and each has an owner here:

| Old problem | Owner in this app |
|---|---|
| Nothing owned the data: 83 helper functions over raw SQLite, called from screens; whole rows written back from stale copies | Room behind repositories in `:core:data`; screens observe Flows; every write changes only the columns it means to |
| No content model: a chapter was an HTML page edited in place by 7 copies of a cleaning pipeline | `:core:content` extracts a chapter once into `ChapterContent` blocks; everything else reads those |
| The extension API was the app's internals | `:source:api` is a small, versioned contract; every source is an extension, built in a repository of its own |
| No rules for network traffic: one global lock, string-matched errors | `:core:network`: per-host limits, typed errors, one challenge flow |
| Nobody owned background work | WorkManager jobs in `:core:data`; a Media3 service for listening |

## Modules

```
:app                     single activity, navigation graph, Application (Hilt, WorkManager, Coil)
:feature:library         library grid, pull-to-refresh update
:feature:browse          sources, source catalogue and search
:feature:novel           novel details and chapter list
:feature:reader          reader screen: text engine, controls, reader settings
:feature:settings        app settings
:core:data               repositories, workers, source registry           (Android)
:core:database           Room: entities, DAOs, migrations                 (Android)
:core:datastore          DataStore: app and reader settings               (Android)
:core:network            OkHttp, cookies shared with WebView, SourceHttp  (Android)
:core:designsystem       theme and shared Compose components              (Android)
:core:content            ChapterContent model and extraction              (JVM)
:core:model              plain data classes shared by every layer          (JVM)
:core:common             dispatchers, small helpers                       (JVM)
:source:api              the source contract, published for extensions    (JVM)
```

Rules:

- Features depend on `:core:*`, never on each other; `:app` wires them together.
- JVM modules never depend on Android modules, so extraction and models are tested with plain
  JUnit, as the extensions' parsers are in theirs.
- Routes carry IDs (`novelId`, `chapterId`), never objects. Screens load what they show from the
  repositories, so nothing works on a stale copy.

## Sources

`NovelSource` (in `:source:api`) is a small suspend API: `search`, `popular`/`latest` when the site
has them, `novelDetails`, `chapterList`, `chapterPage`. Sources fetch through `SourceHttp`, which
the app implements with OkHttp. They return absolute URLs; the app stores novels and chapters by
`(sourceId, path relative to baseUrl)`, so a site's domain change only needs a new `baseUrl`.

`chapterPage` returns the whole page plus, when the source knows it, the element holding the
chapter. Page-wide clean-up (hidden planted text, ads) needs the rest of the page.

### Extensions

The app ships with no sources: every site is an extension, a small APK built against `:source:api`
in a repository of its own (fanos-extensions) and installed from a file in Browse > Extensions.
An extension is never installed on the phone as an app, as in Mihon's private installs:

- `ExtensionManager` (`core/data` `extension/`) copies the file into the app's own storage
  (read-only, as Android requires of loaded code), reads it with Android's package parser, which
  verifies its signature, and loads its classes with the app's own as their parent. So extensions
  share the app's source API, Kotlin, kotlinx.coroutines, kotlinx.serialization and jsoup instead
  of bundling them, and a release build keeps those whole (`app/proguard-rules.pro`).
- The manifest names the extension's `SourceFactory` and the API level it was built against.
  `Extensions.API_LEVEL` goes up with every addition to the API and an extension needs an app at
  its level or later; `MIN_API_LEVEL` goes up only when a change breaks older extensions, which are
  then refused rather than failing as they run.
- Only extensions signed with a key the reader trusts are loaded. The first extension signed with
  a new key asks, showing the key's SHA-256; trusted keys stay on the phone and aren't backed up.
- `SourceRegistry` follows the working extensions. Asking it for a source waits for them to load at
  start, so nothing takes an extension that hasn't loaded yet for a missing one. A library novel
  whose extension isn't installed keeps its chapters and downloads, names its site from its stored
  address, and is left out of library checks until the extension is back.

## Chapter content

`ChapterExtractor` (in `:core:content`) turns a page into `ChapterContent`: a list of `Block`s
(headings, paragraphs of styled `Span`s, translator and editor credit lines, images, scene breaks,
boxes for LitRPG status windows, quotes, lists, tables, preformatted text). The stages are
documented on the interface. Clean-up rules carried over from NovelLibrary (hidden text, ads, site
notices, look-alike watermark domains) run inside it, with their tests. Credit lines are kept where
the site put them and shown more quietly than the chapter, never removed.

The page as fetched is kept (downloads store it) and the extracted content is cached with the
extractor version, so improving extraction applies to chapters already saved.

Reading positions are `(leaf block index, character offset)`, which survive changes of font,
size, screen and mode.

## Reader

The app draws the text itself, in Compose (`feature/reader` `text/`). `RowLayout` flattens a
chapter's blocks into rows: one per leaf (paragraph, heading, credit line, image, scene break,
preformatted text), the title when the chapter doesn't open with its own, and a table as one row.
Quotes, boxes and list items get no rows of their own; they are drawn around the rows inside them,
and space between rows collapses as CSS margins do. `ReadingStyle` turns the reading settings into
text styles and sizes, and `RowGeometry` places rows, so what measures a row off screen agrees with
what draws it.

- Scroll mode: a pager with a page per chapter, each a list that scrolls through its chapter and
  stops at the end. A sideways swipe, or a chapter button, slides the next or previous chapter in.
- Page mode: `Paginator` cuts each chapter into pages a screen tall, between lines and never through
  one. A paragraph runs on to the next page with no line of it left alone either side of a break,
  and a heading goes over with the text it heads. One pager runs across the chapters, so the last
  page of a chapter turns to the first of the next like any other page. A change of font or size
  cuts the pages again and keeps the same words in view.
- A table whose cells are prose (a skill's description, say) is drawn as a card per row rather than
  a grid too narrow to read. Page mode cuts a long grid between its rows.

The chapters either side of the one being read are loaded ahead, so reading on never waits; those
further off are let go. The view reports the chapter on screen, the reading position and a
chapter's end, and switching modes opens the other at the same place.

## Data

Room is the single source of truth. Repositories expose Flows for screens and suspend functions
for commands; a command writes only the columns it changes. A chapter-list refresh goes through
one `ChapterReconciler`: new chapters are inserted, known ones keep their read state, and chapters
the source no longer lists are marked removed rather than deleted.

Settings live in DataStore.

## Background work

WorkManager runs library updates (periodic, plus an expedited one-off for pull-to-refresh),
downloads, and maintenance, each as unique work. Screens observe the database, not the workers.

The periodic update follows its setting (every 6 hours by default, or 12, daily, off) and is
rescheduled from app start. Only it posts notifications, through `NewChapterNotifier`: one per novel
with the new chapters' titles, under a summary. Pull-to-refresh stays silent, as the reader is watching.

## Your own titles and covers

A novel's title and cover can be the reader's own (`custom_title`, `custom_cover_url` beside the
site's `title` and `cover_url`): every screen shows them, while refreshes keep only the site's up to
date. A cover comes from Open Library (`CoverSearch`: no key; a book not out yet falls back to the
other books of its series) or a picture from the phone, kept as a private copy (`CoverFiles`).
Backups carry the edits, a cover only when it's on the web.

## Backup and restore

`core/data` `backup/`. A backup is the library in its order (novels with every chapter, read state
and reading positions) plus the settings, as gzipped JSON in a `.fanosbackup` file; chapter text is
left out, so a large library is a few hundred kilobytes. The file carries a format version that goes
up only for changes an older app would misread; fields can be added without it, since readers skip
unknown fields and default missing ones. An app refuses a backup from a newer version.

- Automatic backups run daily by default, with nothing to set up: into Documents/Fanos through
  MediaStore (Android 10 and later let an app keep its own files there without a permission, and they
  outlive an uninstall), or into a folder the reader picks instead (a persisted SAF tree grant). The
  newest five are kept; a library with nothing in it isn't backed up. Each file is read back after
  writing; a failure is kept for the settings screen and posted as a notification, since nobody
  watches a scheduled backup.
- Restore merges and never removes: novels are matched by (source, key) and chapters by key; a
  chapter read in either stays read; the latest reading position wins; novels that join the library
  go on top in the backup's order. Each novel is restored in its own transaction, so one bad novel
  doesn't take the rest with it, and restoring twice changes nothing.
- Settings travel as named values through an explicit list in `SettingsDataSource`; a key never
  changes type, and settings tied to the phone (the backup folder) stay behind.

## Downloads

`core/data` `download/`. A downloaded chapter is its stored content marked saved
(`chapter_content.saved`), plus its images under `files/downloads/<chapter id>/<SHA-1 of the URL>`;
the reader shows those from the files and any others from their sites. Content cached from reading
counts: downloading it only marks it saved.

- The queue is a table (`download_queue`), so it survives the app closing. One `DownloadWorker` works
  through it, with a network constraint (unmetered when "only on Wi-Fi"), so downloads start and
  resume by themselves when there is a connection. Queuing appends work rather than keeping it, so a
  run that is just ending can't strand new chapters.
- Sites are spared: one chapter at a time per site, 2–3.5 s apart, at most two sites at once, on
  top of the network layer's per-host limit. Passing trouble (dropped connections, 429, 5xx) is
  waited out and the site left for a later run after three tries; a refusal (404, locked) sets the
  chapter aside with the reason; a browser check sets the site's waiting chapters aside.
- New chapters a check finds in library novels are queued when "Download new chapters" is on
  (`NovelRepository.refreshChapters`, so every kind of check does it). The novel screen queues the
  next ten or all unread chapters, asking first above fifty.
- While a library novel is read, the chapters after the one on screen are kept saved, as many as
  "Download ahead while reading" says (2 by default; `DownloadRepository.downloadAhead`).
- Chapters a site lists as locked (`ChapterInfo.isLocked`, source API level 3: a Patreon post above
  the reader's tier) are marked in chapter lists, left out of "new chapters" and of downloads made
  for the reader, and still open when tapped, in case the reader's membership changed.

## App updates

`core/updater`. Builds are releases of the private repository nimbice/fanos-builds: each has the
APK and an update.json (versionCode, versionName, apk, size, sha256, notes). The app reads them
through GitHub's API with a read-only fine-grained token that the reader pastes into Settings >
Updates, once; it's kept on the phone only (not in the APK, not in backups). A check runs once a day:
the daily worker's, or one as the app opens when the last is a day old (`UpdateScheduler`, off with "Check for
updates automatically"),
or when asked, and announces a build once. Nothing is fetched until the reader taps Install: the
APK is downloaded, checked against its SHA-256, the package name, the version and the installed
app's signing key, and handed to Android's package installer, which asks the first time and after
that installs the app's own updates without asking (Android 12+).

## Errors

Sources and the network layer throw typed exceptions (`HttpStatusException`,
`ChallengeRequiredException`, `ParseException`); screens map them to messages and actions such as
"Open in browser to verify". `suspendRunCatching` never swallows cancellation.

## Milestones

1. Read a chapter: browse a source, open a novel, add it to the library, read in scroll and page
   mode with themes, position saved. Royal Road to start.
2. The rest of the sources and NovelUpdates; downloads; library updates with notifications;
   import from a NovelLibrary backup.
3. Listening: one Media3 service with system TTS and the on-device voices as engines.
4. Per-novel reader settings, history, categories. (Backup and restore came early.)
5. Release pipeline. (The native reader and extensions came early.)
