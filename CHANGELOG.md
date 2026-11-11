# Changelog

Every release of Quire, newest first. Each version installs over any earlier one; the
[GitHub releases](https://github.com/sekhnat/Quire/releases) have the signed APK and the R8 `mapping.txt`.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Filter the library by length. While word counts are shown, a *Words* chip after the status chips opens a sheet with
  Under 5K, 5–10K, 10–20K, 20–50K, 50–100K and 100K+ words, or a custom range: fill in a minimum, a maximum or both
  (a minimum alone means "that many and more", a maximum alone "up to that many"; `12k` and `12,000` both work). It
  combines with the status filter, an author, series or tag, and the search boxes, and narrows "Inside books" results
  too. Books not counted yet are left out of a range. The filter is not saved, and turning word counts off clears it.

## [1.12.2] - 2026-11-10

Titles and descriptions show their symbols and nothing of their HTML.

### Fixed

- Titles, series, authors and tags show `&` and other symbols instead of `&amp;` and similar codes, for books whose
  metadata was escaped twice.
- Book descriptions no longer show codes such as `&#8212;` or `&rsquo;` or leftover tags such as `<p>`, and start a new
  line at headings, quotes and rules. Books already in the library that are affected are read once more on the next scan.

### Upgrading

- No database migration and no new permissions.
- The first scan after upgrading reads the affected books again, so their word counts are counted again.

## [1.12.1] - 2026-11-09

### Fixed

- The library keeps its scroll position when you come back from a book, the reader, Settings or Stats, in every view and layout. A list opened from a book's author, series or tag still starts at the top.

## [1.12.0] - 2026-11-09

Select several books at once, and send, tag, copy, remove or delete them together.

### Added

- **Select several books.** Long-press a book in the library (grid, either list or shelves) to start selecting; taps then pick or unpick books, *Select all* takes every book shown, and Back ends it. The bar along the bottom acts on them all at once:
  - *Send* hands the book files to another app through the share sheet.
  - *Tag* adds one of your tags to every selected book, typed or picked from the library's tags.
  - *Copy* copies the files into a folder you pick; a copy into a watched folder joins the library.
  - *Info* shows their files: count, total size and formats, and each book's format, size, date added and location.
  - *Remove* takes the books out of the library and leaves the files on your device. Scans skip them until you bring them back from Settings → *Removed books*, with any reading history they had.
  - *Delete* deletes the book files from your device, with their reading history. Only the book file goes: a Calibre folder's `metadata.opf` and `cover.jpg` stay.

### Upgrading

- No database migration and no new permissions.
- Going back to 1.11.0 brings removed books back into the library at its next scan.

## [1.11.0] - 2026-11-09

Every book's word count, in the library, on its page and in the reader.

### Added

- **Word counts.** Settings → Library → *Show word count* shows how many words every book has: under each cover in the grid and on shelves, beside the status in the list layouts, on *Continue reading*, in *Inside books* results and in the reader's top bar ("92k words"), and in full on a book's page ("92,431 words", under File). The library can also be sorted by word count while it is on. Quire counts each book once, in the background, from the book's own text (EPUB, AZW3 and MOBI alike, without converting anything); a book is counted again only when its file changes. The setting is included in backups.

### Upgrading

- The library database moves to version 8, which an older build cannot open. Back up first if you may want to go back.
- Version 8 adds the word count to every book, empty until the setting is first turned on.

## [1.10.0] - 2026-11-08

Your folders become tags, and any tag can be hidden from the library.

### Added

- **Folder tags.** Every book is tagged with the folder it sits in. `Downloads/ABC/DEF/book.epub` is tagged `DEF`, and a book directly in a folder you added gets that folder's name. Books in a Calibre library (a `metadata.opf` beside them) get the library folder's name, such as `Calibre Library`, rather than one tag per book folder, and imported books are tagged `Imported`. Folder tags work like any other tag: in the Tags view, on shelves, as a library filter and as an *Inside books* search filter. On a book's page the folder tag comes first, marked with a folder icon.
- **Hidden tags.** Settings → Library → *Hidden tags* opens a searchable list of every tag, each with a switch. Books with a hidden tag leave the library views and counts, the shelves, *Continue reading*, the Tags view and *Inside books* search. Nothing is deleted: Stats still count those books, a book already open keeps reading, and switching the tag off brings them straight back. The hidden list is included in backups and exported reading data.

### Changed

- Every scan keeps the folder tags in step with the files, including files that have not changed, so a moved book's tag follows it without the book being read again.
- The empty library says when every book is hidden by a tag, instead of suggesting you add books.

### Upgrading

- No database migration: the first scan after updating tags your whole library in a single quick pass and reads no book again.
- Going back to 1.9.0 keeps the folder tags as ordinary tags that no longer follow moves, and ignores the hidden-tags setting.

## [1.9.0] - 2026-10-31

### Added

- **Read aloud.** A *Listen* button in the reader, and *Read from here* in the selection menu, read the book with the phone's own text-to-speech engine, starting at the top of what is on screen. The sentence being read is marked and the page follows it. You can play and pause, step a sentence back or forward, change the speed (0.75× to 2×) and pick a voice. Only voices that work offline are offered, so Quire never sends your text anywhere. Reading continues with the screen off, with a notification and lock-screen controls.
- **Reading stats.** The chart icon in the library shows the time you read today, this week and this month, your streak, the last 30 days, books finished this year, your pace in pages an hour, and the books you read most. Only time with a book open on screen counts, and listening counts too.
- **Highlight colours.** Five colours, picked from the bar that opens when you tap a highlight. New highlights use your last colour, and the Highlights list can be narrowed to one colour.
- **Look up and share.** Look up selected text in any dictionary or translator app, or share it (or a highlight) as a quote with the book's title and author.
- **Reading controls.** Turn pages with the volume keys, keep the screen on while a book is open, and lock the reader to portrait or landscape.

### Fixed

- The library title no longer collides with the buttons on a narrow screen with a long book count.

### Upgrading

- The library database moves to version 7, which an older build cannot open. Back up first if you may want to go back.
- New permission: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, so read aloud can continue with the screen off.

## [1.8.0] - 2026-10-31

### Changed

- **Books open faster.** Reading settings, brightness and the saved position are read while the book is parsed, so the reader lays out once. Page turns no longer rebuild position and chapter lookups.
- Importing says how many picked files could not be read.
- The APK is about 2 MB smaller: the UI fonts were bundled twice.
- **Licence:** Quire is released under Apache-2.0. The vendored Readium navigator keeps BSD 3-Clause, the icons MIT and the fonts their OFL.

### Added

- Settings → About shows the version and every open-source notice in the app.

### Fixed

- Closing a book while it is still loading no longer crashes the app.
- A stopped library scan no longer marks the book it was reading as unreadable, and a scan that keeps failing waits for its next run instead of retrying for ever.

## [1.7.0] - 2026-10-09

### Added

- **MOBI and AZW3.** DRM-free MOBI, AZW3 and joint MOBI/KF8 books can be read, searched, highlighted and annotated like EPUBs. Each is converted to an EPUB the first time it is opened, and up to 256 MB of copies are cached. Scanning reads their metadata and cover straight from the file. When Calibre keeps several formats of one book, Quire shows it once and opens the EPUB, then the AZW3, then the MOBI.
- Library indexing shows its progress in a notification.

## [1.6.0] - 2026-10-08

### Changed

- **Scroll mode keeps up with fast flings.** The reader predicts where a fling will stop and loads those chapters first, weighs chapters by their content within a memory budget, and shrinks when the system runs low on memory. In testing, blank screen time during flings fell from about 9 s to 0.25 s.
- **The library is lighter on large collections.** The book list, Authors, Series, Tags and shelves are built in the background and only when needed, so filter, sort and search no longer stall the screen. Saving a reading position no longer re-reads the whole library.

### Fixed

- Restoring a backup could delete an imported book that had the same name and size as a different one. Books are now compared byte for byte, and a restore never deletes a local book.

## [1.5.0] - 2026-10-07

### Added

- **Advanced reading controls** (opt-in in Settings): typography (the book's own or Quire's, text weight), paragraph presets, letter, word and line spacing, hyphenation, ligatures, vertical text and reading direction, image filters and two-column pages. Each can be set per book or as the default, and settings that cannot apply to a book are greyed out with the reason.

## [1.4.0] - 2026-10-06

### Added

- **Full backups.** Settings → *Back up now* writes your whole library to one `.zip`: folders, positions, highlights, notes, bookmarks, ratings, tags and settings, optionally with the search index, covers and imported books.
- **Restore** a backup by merging its reading data into your library, or by replacing everything. A new phone can be set up straight from a backup on the welcome screen.
- **Automatic backups**, daily or weekly, to a folder you choose, keeping the newest 3, 5 or 10.

### Changed

- A book whose file was copied but whose content is unchanged keeps its search index.

## [1.3.0] - 2026-10-06

### Added

- Swipe left or right to move between the library's Books, Authors, Series and Tags tabs.

## [1.2.2] - 2026-10-06

### Fixed

- The Tags tab opens and scrolls smoothly with hundreds of tags.

## [1.2.1] - 2026-10-06

### Fixed

- New books are indexed for text search on their own.
- The library remembers its sort after a restart.

## [1.2.0] - 2026-10-06

### Added

- A comfortable library layout with larger covers and the start of each synopsis.
- Onboarding shows its progress while looking for books.

### Changed

- The library remembers its layout after a restart.

### Fixed

- Changing the theme while reading in scroll mode no longer crashes the reader.

## [1.1.0] - 2026-10-06

### Changed

- **Search index v2.** A smaller full-text index in its own database (37% of the old size), with Chinese, Japanese and Korean search and a choice of ordering results by relevance or by number of passages. The library is indexed again once after updating.

### Added

- Reading data (positions, ratings, tags, bookmarks, highlights, notes) is kept safe in Android's backup and can be exported to one file and merged into any library. Notes can be exported as Markdown.

## [1.0.1] - 2026-10-05

### Fixed

- Release builds extract book covers again.

## [1.0.0] - 2026-10-05

First release.

### Added

- A library built from the folders you choose, with Calibre metadata and covers.
- Search inside every book, with a full-text index built in the background.
- Scroll mode reads the whole book as one continuous page, and a fling stops the moment your finger lands.

[Unreleased]: https://github.com/sekhnat/Quire/compare/v1.12.2...HEAD
[1.12.2]: https://github.com/sekhnat/Quire/compare/v1.12.1...v1.12.2
[1.12.1]: https://github.com/sekhnat/Quire/compare/v1.12.0...v1.12.1
[1.12.0]: https://github.com/sekhnat/Quire/compare/v1.11.0...v1.12.0
[1.11.0]: https://github.com/sekhnat/Quire/compare/v1.10.0...v1.11.0
[1.10.0]: https://github.com/sekhnat/Quire/compare/v1.9.0...v1.10.0
[1.9.0]: https://github.com/sekhnat/Quire/compare/v1.8.0...v1.9.0
[1.8.0]: https://github.com/sekhnat/Quire/compare/v1.7.0...v1.8.0
[1.7.0]: https://github.com/sekhnat/Quire/compare/v1.6.0...v1.7.0
[1.6.0]: https://github.com/sekhnat/Quire/compare/v1.5.0...v1.6.0
[1.5.0]: https://github.com/sekhnat/Quire/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/sekhnat/Quire/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/sekhnat/Quire/compare/v1.2.2...v1.3.0
[1.2.2]: https://github.com/sekhnat/Quire/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/sekhnat/Quire/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/sekhnat/Quire/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/sekhnat/Quire/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/sekhnat/Quire/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/sekhnat/Quire/releases/tag/v1.0.0
