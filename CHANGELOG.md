# Changelog

All notable changes to LitePPTX. Versions follow [semantic versioning](https://semver.org/).
Release notes for each version live in [`release-notes/`](release-notes/).

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

[1.3.0]: https://github.com/Evilenor/LitePPTXViewer/releases/tag/v1.3.0
[1.2.0]: https://github.com/Evilenor/LitePPTXViewer/commits/main
[1.1.0]: https://github.com/Evilenor/LitePPTXViewer/commits/main
[1.0.0]: https://github.com/Evilenor/LitePPTXViewer/commits/main
