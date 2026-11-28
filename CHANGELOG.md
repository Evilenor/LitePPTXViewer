# Changelog

All notable changes to LitePPTX. Versions follow [semantic versioning](https://semver.org/).
Release notes for each version live in [`release-notes/`](release-notes/).

## [1.8.0] - 2026-11-28

The speed release: same features, same rendering — just faster to open and lighter on memory. No new permissions, no new libraries, still zero dependencies.

### Changed
- **Lazy slide parsing**: opening a presentation now reads only `presentation.xml` and the slide list. Each slide is parsed when it is first shown, searched or exported, and the last few stay cached — so a 50-slide deck opens near-instantly instead of parsing every slide up front.
- **Streaming XML parsing**: OOXML parts (slides, `document.xml`, sheets) are now parsed straight from the zip entry stream with `XmlPullParser` instead of being read fully into a `ByteArray` first.
- **Bitmap pool + 16-bit colour**: rendered slides reuse bitmaps from a small pool instead of allocating a fresh frame on every scroll step. Flat slides (no photos, gradients or translucent colours) render in RGB_565 — half the memory of ARGB_8888, with identical pixels. Photos and gradients still get full colour.
- **Search cache**: global search extracts each recent file's text once and reuses it for later queries, keyed by file size + last-modified so an edited file is re-parsed. A cache hit skips the file copy and the re-parse entirely.
- **Cold start**: the update check now waits ~1.2 s until the first screen is up instead of running during startup, and the update cleanup and stale cache-file sweep moved off the start path. Startup timing is logged to logcat (`adb logcat -s LitePPTX`: `onCreate total`, `first deck shown`, `recents rendered`).
- **In-deck search** (⋯ → Search slides) now runs on a background thread with the progress bar, since slides parse on demand.

### Known limitations
- Time-to-first-slide and peak memory are measured on a real device via the new logcat lines; no measured numbers yet.
- Global search hits on PPTX files still jump one slide too far and label one slide too high (pre-existing v1.7.0 off-by-one, to be fixed separately).

## [1.7.0] - 2026-11-27

### Added
- **Global search** (home menu): full-text search across all recent files — PPTX slides, DOCX/XLSX/TXT/CSV/RTF/MD and legacy Office text — on a background thread with debounced input (~300 ms) and a progress state. Results are grouped by file with a snippet, capped at 50 hits; tapping one opens the file at that slide, block or sheet row.
- **Bookmarks**: a "Bookmark" menu item (checkable) in the PPTX, office and PDF viewers saves the current slide, document block, sheet row or PDF page. The home screen shows a **Bookmarks** section with an empty-state text; tap jumps to the file and position, long-press or the × deletes.
- **Night mode**: persisted toggle in the home overflow menu and in each viewer menu, applied immediately. PPTX/PDF viewers dim rendered pages with a translucent black overlay (bitmaps are never inverted); the office viewer switches to a black page with lightened text and darkened table/sheet fills.
- **Batch PDF compress**: Compress in PDF Tools now allows multiple selection. Each file is compressed with the single-file logic (lossless first, rendered rebuild at the current preset when it saves <5%, no per-file dialog). A results dialog lists name, before → after size and method per file, each with its own Save button (Downloads on API 29+, create-document flow below).
- **Recent details + sorting**: long-press a recent item for a details dialog (type, slide/block/row count, size when resolvable, last-opened relative time). Home menu sorts recents by recency (default), name or type; the choice persists.

### Fixed
- **Shared PDFs failed to open**: `PdfViewerActivity` had no SEND filter for `application/pdf` and only read `intent.data`. It now accepts ACTION_SEND via `EXTRA_STREAM` too.
- **Shared legacy `.ppt` failed**: `OfficeViewerActivity`'s SEND filter was missing `application/vnd.ms-powerpoint`.
- **`.markdown` files did not open from file managers**: the VIEW fallback filter only listed `.md`/`.MD`.
- **DOCX default text rendered black-on-black**: the office viewer drew black text on its dark page. The page is now white in day mode (black with light text at night), and table cells without a fill get readable text in both modes.

### Known limitations
- Global search covers recent files only; PDFs are excluded (PdfRenderer cannot extract text).
- Bookmarks and search hits open PPTX files through the normal load path, so a file that was deleted or moved shows the usual load error instead of jumping.

## [1.6.0] - 2026-11-27

### Added
- **Universal document viewer** (`OfficeViewerActivity`): one viewer for `.docx`, `.xlsx`, `.txt`, `.csv`, `.rtf` and `.md`, opened from the file picker, the home tile, the overflow menu, or *Share* / *View* intents.
  - Word: styles and headings, numbering and bullets, tables, right-to-left text, inline images decoded lazily from the file.
  - Sheets: sheet tabs, A/B/C column headers, column widths, cell fills and bold text, merged cells, date/percent/number formatting, go-to-row.
  - Plain text: `.txt` paragraphs, RFC 4180 `.csv`, `.rtf` (bold/italic/underline, Unicode escapes), Markdown headings, bullets and `**bold**`/`*italic*`/`` `code` ``.
  - Text search across the document or sheet, text size 0.8–1.6×, share the original file, open externally, keep screen on.
- **Legacy Office text fallback**: `.doc`, `.ppt` and `.xls` files are shown as plain text recovered from the OLE2 streams, with a clear "text only, formatting lost" note.
- **PDF tools** (`PdfToolsActivity`, home tile and menu): **Compress** (lossless first — re-deflates Flate streams in classic xref-table PDFs and only keeps the result when it saves ≥5%; otherwise offers a rendered rebuild at Keep/Balanced/Small), **Merge** (in pick order) and **Split** (page range) — all offline, results shown with share/save/open.
- **Magic scan filter**: a fifth filter with stronger levels, a saturation boost and an unsharp mask, for dull or faded pages.
- **Scan PDF quality choice** (Original / Smart / Small, remembered) before building the scan PDF.
- **Save scanned pages as images** to Downloads from the scanner start screen.
- Recent files now remember documents and sheets too, with block/row counts.

### Changed
- The home screen gains a third tools row: **PDF Tools** and **Open document**.
- APK size: still under ~250 KB (to verify from CI). Still zero third-party dependencies.

### Fixed
- Removed a stale unused import in `PdfViewerActivity`.

### Known limitations
- Legacy `.doc`/`.ppt`/`.xls` are text-only; charts/SmartArt are still not rendered.
- Lossless PDF compression only works on classic xref-table PDFs; anything else falls back to a rendered rebuild, which turns pages into images.
- XLSX is capped at 5,000 rows / 100 columns, DOCX at 2,000 blocks; the viewer says so when the cap is hit.

## [1.5.0] - 2026-11-27

### Added
- **Built-in PDF viewer** using Android's `PdfRenderer` (API 21+). Pinch-to-zoom, page jump, share, save, open externally, keep screen on. Registered as a handler for `application/pdf` files.
- **Speaker notes**: badge on the slide counter, dialog via overflow menu, notes parsed from PPTX `notesSlide` XML.
- **Slide search** (**⋯ → Search slides**): full-text search across shapes, tables and notes with jump-to-slide.
- **Share current slide** as a PNG via the system share sheet.
- **Go to slide** dialog with slider and number input.
- **Keep screen on** toggle in the overflow menu.
- **ID card scanning**: 2-sided scan composited onto one A4 page at 200 DPI, with mode selector (Document / ID Card) and undo button.
- **Recent files** on the start screen with relative timestamps, slide count, remembered scroll position, and clear button.
- **15+ preset vector shapes** in `SlideRenderer`: right/left/up/down arrows, star5, hexagon, pentagon, chevron, home plate, parallelogram, trapezoid, plus, improved diamond.
- **Open PDF** tile on the home screen's 2×2 tool grid.
- Shared `UiHelper.kt` for insets, keep-awake, display names and go-to dialogs.
- `PdfViewerActivity` with dedicated layout and menu.
- `RecentStore.kt` for recent-file persistence.
- 35+ new English and Persian string resources.

### Changed
- Home screen redesigned: 2×2 tool grid (Scan, ID Card, Images to PDF, Open PDF) replaces the 1×2 row.
- Back button returns to home instead of exiting when a deck is open.
- `PdfShareProvider` handles `image/png` for slide sharing.
- `PptxModels.Slide` gains `notes: String?` and `textContent()`.
- APK size: ~156 KB (up from ~131 KB). Still zero third-party dependencies.

### Fixed
- Kept updater download progress compatible with Android 5.0–6.0 (API 21–23).
- Scoped the display-cutout theme setting to Android 8.1+ (API 27+).

## [1.4.1] - 2026-11-26

### Added
- A red dot on the **⋯** button while an update is waiting, so you notice it on any screen state.
- The menu entry turns into **Update to v…** / **Install v…** while an update is pending.

### Changed
- A downloaded update is remembered. If you close the app, go offline or cancel Android's installer, the bar comes back as "ready to install" and the APK is not downloaded again. Downloads are stored where cache clean-ups cannot delete them.
- If the saved APK has disappeared, the button goes back to **Update** and downloads it again.
- After you allow installs from LitePPTX in Android settings, coming back to the app continues the install.

## [1.4.0] - 2026-11-26

### Added
- **Document scanner** (*Scan document* on the start screen, in the menu and as a launcher shortcut). Take photos with the phone's camera app (no camera permission needed) or pick them from the gallery, several pages in a row. Automatic page detection proposes the outline, the four corners can be dragged, and the page is straightened on the device. Filters: *Original*, *Clean*, *B&W*, *Gray*. Rotate pages, then create a PDF.
- **Export as PDF** for presentations (**⋯ > Export as PDF**): every slide is rendered to a PDF page.

### Changed
- The "Your PDF is ready" dialog no longer closes when you tap *Save*, so you can save and then share.
- The release APK grows from about 97 KB to about 131 KB. Still no third-party libraries.

### Known limitations
- No OCR and no ID-card mode in the scanner yet. Automatic edge detection is weaker on busy backgrounds; the corners can be set by hand.

## [1.3.2] - 2026-11-26

### Added
- **Donate** card on the start screen (heart with a soft heartbeat) and a **Donate ♥** menu entry. Both open the support page in the browser; the app sends nothing.
- Support link in the English and Persian READMEs and in the release notes.

### Changed
- *What's new* dialog shows Markdown links as plain text and uses the localized title.

## [1.3.1] - 2026-11-26

### Fixed
- Restored the viewer screen: v1.3.0 was published with an empty `MainActivity` and could not start.

### Changed
- The in-app updater now matches its documentation: update bar opens *What's new*, the download can be cancelled, and silent launch checks run at most every 6 hours while up to date.

## [1.3.0] - 2026-11-26

### Added
- **In-app updates.** The app checks GitHub Releases (at most every 6 hours, and on demand from the menu), shows an update bar, and downloads and installs the new build with one tap. The download is verified (size, SHA-256 and package identity) before Android's installer is opened.
- "What's new" dialog: tap the update bar to read the release notes before updating.
- Tagged releases: pushing a `v*` tag now builds, signs and publishes a GitHub Release with the APK and its SHA-256.

### Changed
- Releases are now versioned (`vX.Y.Z`) instead of a rolling `latest` build.
- CI uses `actions/setup-java@v5` and builds pull requests as well.

## [1.2.0] - 2026-11-26

### Added
- **Images to PDF**: pick photos in the gallery, tap *Share > Create PDF* (or use the in-app button), reorder / rotate / remove pages, then share, save to Downloads or open the result.
- Page size (Fit / A4 / Letter), margins (None / Small / Normal) and four quality presets (Original, High, Smart, Small).
- Lossless pipeline: JPEGs are embedded byte-for-byte whenever possible, PNG screenshots are stored losslessly, EXIF orientation is honoured.
- New launcher icon (adaptive) and an app shortcut for Images to PDF.

## [1.1.0] - 2026-11-25

### Fixed
- Pictures and picture backgrounds were never drawn (relationship attribute lookup).
- Errors are logged instead of silently swallowed.

### Changed
- Release APKs are signed with a fixed key, so updates install over older versions.

## [1.0.0] - 2026-11-25

### Added
- First release: a dependency-free PowerPoint (`.pptx`) viewer for Android 5.0+ with master/layout inheritance, shapes, tables, gradients, bullets, right-to-left text, pinch-to-zoom and a page counter.

[1.7.0]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.7.0
[1.6.0]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.6.0
[1.5.0]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.5.0
[1.4.1]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.4.1
[1.4.0]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.4.0
[1.3.2]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.3.2
[1.3.1]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.3.1
[1.3.0]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.3.0
[1.2.0]: https://github.com/Evilenor/LitePPTXViewer/commits/main
[1.1.0]: https://github.com/Evilenor/LitePPTXViewer/commits/main
[1.0.0]: https://github.com/Evilenor/LitePPTXViewer/commits/main
