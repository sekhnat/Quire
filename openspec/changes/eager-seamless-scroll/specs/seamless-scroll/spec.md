# Spec Delta

## Purpose

Provide a fully prepared, continuous reading surface for reflowable EPUBs in scroll mode without exposing chapter resource transitions, while retaining chapter navigation and existing reading data.

## ADDED Requirements

### Requirement: Continuous book-wide scrolling

In scroll mode for a reflowable EPUB, the reader SHALL present every reading-order document in publication order in one continuous vertical scroll coordinate space. The reader MUST NOT introduce chapter-sized blank regions, viewport-height chapter minimums, separators, repeated reader chrome or insets, pagination breaks, scroll snapping, or chapter-local overscroll at resource boundaries. Authored headings, content, and ordinary publisher spacing SHALL remain intact; pagination-only page breaks SHALL NOT create chapter-boundary gaps in scroll mode.

#### Scenario: Scroll through adjacent chapters
- **WHEN** the reader drags or flings from the end of one reading-order document into the next
- **THEN** the next document follows continuously without a loading placeholder, renderer-added blank region, scroll reset, or chapter-local edge effect
- **AND** the same scroll gesture continues across the boundary

#### Scenario: A chapter is shorter than the viewport
- **WHEN** a short document is followed by another document
- **THEN** the next document starts after the short document's actual content and authored spacing rather than after an imposed viewport-sized chapter frame

#### Scenario: Chapter headings remain navigable content
- **WHEN** the reader reaches an authored chapter heading
- **THEN** the heading remains visible and addressable even though the renderer no longer presents a chapter boundary

### Requirement: Eager whole-book preparation

The reader SHALL prepare every reading-order document's text, styles, fonts, and static-image layout before declaring the scroll surface ready or applying its initial target. Prepared documents SHALL remain available throughout that scroll session without chapter mounting, eviction, or fetching triggered by the reader's proximity to a boundary. Eager preparation does not require pre-rasterizing every offscreen pixel or pre-buffering audio/video playback.

#### Scenario: A distant chapter is not yet prepared
- **WHEN** the first chapter is prepared but a later reading-order document is still loading
- **THEN** the reader remains in its loading state rather than exposing a partially prepared book as ready

#### Scenario: Traverse the complete prepared book
- **WHEN** the reader scrolls from the start to the end of a ready book and back
- **THEN** no chapter text, stylesheet, font, or static image is loaded or evicted because scrolling crossed a chapter boundary

#### Scenario: Delayed image and font affect layout
- **WHEN** an image or font in a chapter completes preparation after that chapter's text
- **THEN** its resulting geometry is included before readiness and before the initial chapter or saved-location jump

### Requirement: Honest loading failure and cancellation

The reader SHALL expose a loading state until the entire book is prepared and SHALL expose a failure if a required chapter document or reader runtime cannot be prepared. It MUST NOT silently omit a chapter, claim that an incomplete book is ready, or fall back to lazy or paginated reading. A failed optional image SHALL settle using the existing missing-image behavior rather than keep the book loading indefinitely. Closing the reader or replacing its book SHALL cancel preparation and prevent obsolete callbacks from updating the new session.

#### Scenario: A required chapter fails
- **WHEN** a reading-order document cannot be prepared
- **THEN** the reader reports a loading failure and does not expose a book with that chapter silently missing

#### Scenario: An optional image is unavailable
- **WHEN** a chapter document loads but an optional image fails to load
- **THEN** readiness can complete with the missing-image result accounted for in layout

#### Scenario: Close or replace a loading book
- **WHEN** the reader closes the book or opens another book during preparation
- **THEN** pending work for the old book is cancelled and its late readiness, geometry, navigation, and selection callbacks cannot affect the active session

### Requirement: Chapter and internal-link navigation without surface replacement

The reader SHALL retain TOC navigation and internal links to original resource hrefs and fragments. A jump SHALL move within the prepared book-wide surface, land on the requested chapter or fragment, and permit immediate continuous scrolling into adjacent chapters without replacing or lazily preparing that chapter. Multiple targets in the same chapter SHALL remain distinct.

#### Scenario: Jump to a distant chapter
- **WHEN** the reader selects a TOC entry in a nonadjacent chapter
- **THEN** the existing continuous surface scrolls to that target and adjacent chapters remain immediately readable

#### Scenario: Two TOC targets share a resource
- **WHEN** the reader selects each of two TOC entries with different fragments in the same resource
- **THEN** each jump lands at its own authored target rather than at the start of the resource

#### Scenario: Duplicate IDs in different resources
- **WHEN** two chapter documents contain the same element ID and navigation specifies one resource and that ID
- **THEN** the jump resolves the element in the specified resource only

### Requirement: Compatible original-resource locators

The reader SHALL report and persist the original EPUB href and resource-relative location for the visible content, using the existing position and total-progression model. Bookmarks, highlights, saved reading positions, and search targets created before this change SHALL remain valid without migration. Internal shell or container URLs MUST NOT appear in persisted reading data.

#### Scenario: Progress crosses a chapter seam
- **WHEN** the top reading position passes from one chapter into the next
- **THEN** the current locator changes to the next chapter's original href with its local progression and corresponding existing book-wide position

#### Scenario: Restore a saved position from the old reader
- **WHEN** a saved original-resource locator is reopened in scroll mode
- **THEN** the fully prepared book restores that location rather than the beginning of the book or an estimated chapter offset

#### Scenario: Existing bookmark or highlight is opened
- **WHEN** the reader navigates to a bookmark or highlight created before the change
- **THEN** its original resource and local target still identify the same book content

### Requirement: Exact search navigation waits for whole-book readiness

Search-result navigation SHALL wait for the active scroll session's whole-book readiness, resolve the target in its original resource, show the existing exact-match underline, and bring that match into view. Whole-book preparation taking longer than the former five-second navigator wait MUST NOT by itself produce an unresolved-target fallback. If the target genuinely cannot be resolved after readiness, the existing unresolved-target behavior SHALL remain. Among competing pending jumps, the latest requested target SHALL win.

#### Scenario: Exact match in a distant chapter
- **WHEN** an exact search target points to a chapter outside the old three-chapter window
- **THEN** the ready book shows the underline on that match in the original resource and positions it visibly

#### Scenario: Slow but successful whole-book startup
- **WHEN** preparation takes more than five seconds and eventually succeeds with a resolvable target
- **THEN** navigation waits for readiness and resolves the target without showing an unresolved-target warning solely because of the loading duration

#### Scenario: Missing match after readiness
- **WHEN** the fully prepared book does not contain the requested exact target
- **THEN** the reader uses the existing unresolved-target fallback rather than leaving the reader indefinitely waiting

#### Scenario: A newer jump supersedes an older jump
- **WHEN** two navigation requests arrive while preparation or layout is pending
- **THEN** only the latest request determines the final landing position and target decoration

### Requirement: Resource-scoped reading interactions

The reader SHALL preserve existing per-resource text selection, copy, highlight, note, decoration activation, and link behavior on the continuous surface. Selections and interactions SHALL identify their originating resource, and displayed interaction coordinates SHALL account for that resource's position in the book and the current scroll offset. Resource-local styles, relative assets, and repeated element IDs MUST NOT leak into or interfere with other chapter documents. This change does not introduce selection spanning multiple chapter documents.

#### Scenario: Select text in a later chapter
- **WHEN** the reader selects text in a chapter after scrolling or jumping past earlier chapters
- **THEN** copy, highlight, and note actions operate on that selected text with the later chapter's original href and correctly placed selection UI

#### Scenario: Same decoration identity in different resources
- **WHEN** resource-scoped decorations are applied or activated in chapters with overlapping local IDs
- **THEN** the action addresses only the intended resource's content

#### Scenario: Conflicting publisher styles and relative URLs
- **WHEN** adjacent chapters use conflicting styles, duplicate element IDs, and relative asset or link URLs
- **THEN** each chapter retains its own styling and resolves its URLs relative to its original resource

### Requirement: Stable reflow and ordinary scroll gestures

Typography, theme, and viewport changes SHALL reflow every prepared document without reverting to windowed loading. The reader SHALL retain the current original-resource text anchor and its viewport-relative position when possible, falling back to that resource's progression only if the anchor is unavailable. Native drag, fling, touch-to-stop, and whole-book start/end edge behavior SHALL remain available.

#### Scenario: Typography or orientation changes mid-book
- **WHEN** the reader changes font size or the viewport size while reading a later chapter
- **THEN** all documents are remeasured and the same reading anchor remains in view without a jump to a different chapter or lazy chapter preparation

#### Scenario: Touch stops a cross-chapter fling
- **WHEN** the reader touches the surface during a fling that traverses chapter boundaries
- **THEN** scrolling stops as an ordinary single-surface scroll rather than restarting after a chapter layout update

### Requirement: Reading-mode compatibility

The eager continuous surface SHALL apply only to reflowable scroll mode. Paginated navigation, fixed-layout rendering, reading-mode preferences, library scanning, and persisted reader-data formats SHALL retain their existing behavior. Switching modes SHALL retain the original-resource reading location.

#### Scenario: Switch between scroll and paginated reading
- **WHEN** the reader switches reading modes while positioned in a chapter
- **THEN** the selected mode is used at the same original-resource location and paginated mode does not use the continuous book surface

#### Scenario: Open a fixed-layout publication
- **WHEN** the reader opens a publication using the existing fixed-layout reader
- **THEN** the existing fixed-layout behavior is used rather than the eager reflowable scroll surface
