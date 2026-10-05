# Spec Delta

## Purpose

Keep the continuous, seamless scroll surface and its navigation and interaction guarantees, but bound renderer memory by the live window instead of the size of the book.

## MODIFIED Requirements

### Requirement: Eager whole-book preparation

Replaces "Eager whole-book preparation". The reader SHALL keep one complete geometry table for the whole book and SHALL hold a loaded document only for the chapters near the reading position (the live window), plus any chapter pinned by a jump, a script addressed to it, or a text selection. A document outside the window SHALL be an empty region of its measured height, or of an estimate until it has been measured. The scroll surface SHALL be ready once the documents around the starting position are loaded and measured. The number of loaded documents SHALL NOT grow with the number of chapters. Heights SHALL be corrected without moving the text being read.

#### Scenario: Large book opens
- **WHEN** a book with hundreds of chapters and tens of megabytes of images is opened in scroll mode
- **THEN** the reader becomes ready from the documents around the starting position, without loading the rest of the book
- **AND** the WebView renderer's memory stays bounded while the reader flings through the whole book

#### Scenario: Chapter leaves and re-enters the window
- **WHEN** the reader scrolls far from a chapter and back
- **THEN** its document is unloaded in between and loaded again with its whole text visible, and its saved decorations are restored

#### Scenario: Estimated heights are corrected above the reader
- **WHEN** unloaded chapters above the reading position are measured and their heights differ from the estimates
- **THEN** the text being read does not move

#### Scenario: Pinned chapter stays loaded
- **WHEN** a jump, a script addressed to a chapter by href, or a text selection involves a chapter outside the window
- **THEN** that chapter is loaded first and stays loaded until the jump has landed, the script has run, or the selection is cleared

### Requirement: Honest loading failure and cancellation

Modifies "Honest loading failure and cancellation". A required document failing before the book is ready SHALL still be a loading failure and SHALL NOT be silently omitted. After readiness a document that fails to load SHALL leave its slot an empty region and be retried later; it SHALL NOT end the reading session. If the WebView's renderer process is lost the reader SHALL rebuild the surface at the current position once; if it is lost again within thirty seconds the reader SHALL report that the book does not fit in scroll mode. The app MUST NOT be terminated by a renderer loss. Closing the reader or replacing its book SHALL still cancel pending work and reject late callbacks.

#### Scenario: Renderer is killed
- **WHEN** the system kills the WebView renderer while a book is open in scroll mode
- **THEN** the app keeps running and the book reopens at the same chapter

#### Scenario: Renderer is killed repeatedly
- **WHEN** the renderer is lost twice within thirty seconds
- **THEN** the reader tells the reader to switch to pages and does not rebuild again

### Requirement: Exact search navigation waits for whole-book readiness

Modifies "Exact search navigation waits for whole-book readiness". Search-result navigation SHALL wait for the surface's readiness, load the target's chapter if it is outside the live window, show the existing exact-match underline and bring the match into view. The latest requested target SHALL still win.

#### Scenario: Exact match in an unloaded chapter
- **WHEN** an exact search target points to a chapter outside the live window
- **THEN** the chapter is loaded, the match is underlined and scrolled into view

### Requirement: Resource-scoped reading interactions

Modifies "Resource-scoped reading interactions". A text selection SHALL keep its originating document loaded until the selection is cleared, even if the reader scrolls far away, so copy, highlight and note actions address that document.

#### Scenario: Select, scroll away, act
- **WHEN** the reader selects text and scrolls several chapters away before choosing Highlight
- **THEN** the highlight is created in the originating chapter

## ADDED Requirements

### Requirement: Reported position is the chapter being read

The reader SHALL report as the current location the chapter at the reading position, sampled one CSS pixel below the viewport top, from geometry that is current when the position changes. A jump to a chapter start SHALL report that chapter, not the end of the one before it. Jump targets and reflow anchors SHALL be resolved against the same current geometry.

#### Scenario: Jump to a chapter start
- **WHEN** the reader jumps to the first page of a chapter whose unloaded predecessors have not yet been measured
- **THEN** the reader reports that chapter and its first page, and the text stays put while the predecessors are measured

#### Scenario: Font size change deep in a chapter
- **WHEN** the reader changes the font size part-way through a long chapter
- **THEN** the reader stays on the same paragraph, not at the start of the chapter or in another chapter

### Requirement: Only publication resources are served

The reader SHALL serve a request only from the publication (by manifest, or by path in the container for resources not listed in it), the reader's own assets and the reserved shell route. A request for anything else, including the WebView's `/favicon.ico` and remote resources referenced by a book, SHALL be answered as not found and MUST NOT reach a network client.

#### Scenario: Book references a remote image
- **WHEN** a chapter references an `https://` image and the app has no network permission
- **THEN** the image is not loaded and the reader keeps running
