<p align="center">
  <img src="docs/icon.svg" width="112" alt="LitePPTX icon">
</p>

<h1 align="center">LitePPTX</h1>

<p align="center">
  <b>A featherweight PowerPoint viewer, universal document viewer, PDF viewer and tools, document &amp; ID-card scanner and photo-to-PDF maker for Android.</b><br>
  Still under ~250&nbsp;KB (to verify from CI). Zero dependencies. No ads, no accounts, no tracking.
</p>

<p align="center">
  <a href="https://github.com/Evilenor/LitePPTXViewer/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/Evilenor/LitePPTXViewer?style=flat-square&color=4C8DF6"></a>
  <a href="https://github.com/Evilenor/LitePPTXViewer/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/Evilenor/LitePPTXViewer/total?style=flat-square&color=2F6FD8"></a>
  <img alt="Android 5.0+" src="https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=flat-square">
  <img alt="Dependencies: 0" src="https://img.shields.io/badge/dependencies-0-success?style=flat-square">
  <a href="LICENSE"><img alt="MIT license" src="https://img.shields.io/badge/license-MIT-lightgrey?style=flat-square"></a>
  <a href="https://the-evilenor.vercel.app/support"><img alt="Donate" src="https://img.shields.io/badge/%E2%99%A5%20donate-keep%20it%20free-ff4d6d?style=flat-square"></a>
</p>

<p align="center">
  <a href="https://github.com/Evilenor/LitePPTXViewer/releases/latest"><b>Download the APK</b></a> ·
  <a href="CHANGELOG.md">Changelog</a> ·
  <a href="https://the-evilenor.vercel.app/support">♥ Support</a> ·
  <a href="README.fa.md">فارسی</a>
</p>

<p align="center">
  <img src="docs/images/showcase.png" alt="LitePPTX screenshots: slide viewer, Images to PDF editor, finished PDF, in-app update" width="100%">
</p>

---

## Why LitePPTX

Opening a `.pptx` someone sent you in a chat shouldn't need a 300&nbsp;MB office suite. Scanning a receipt or turning five photos into one clean PDF shouldn't need an account, a subscription or a watermark. LitePPTX does all of it, in a package smaller than a single phone photo, and updates itself.

| | |
|---|---|
| **Tiny** | The release APK is still under ~250&nbsp;KB (to verify from CI). Only Android platform APIs are used. There is not a single third-party library. |
| **Fast** | Slides are parsed only when they scroll into view, rendered lazily and cached, with a bitmap pool and 16-bit colour for flat slides — so a big deck opens near-instantly and uses far less memory. Global search caches each file's text once; the home screen appears without waiting for the update check or file cleanup. |
| **Private** | No analytics, no ads, no permissions to read your files. Your documents never leave the phone. |
| **Self-updating** | The app notices new releases by itself and keeps a dot on the **⋯** button until you update. Download once: it is remembered, verified, and offered for install even after you close the app. |
| **Bilingual** | English and Persian UI, with proper right-to-left text rendering in slides. |

## Features

### PowerPoint viewer
- Opens `.pptx` from file managers, Telegram, WhatsApp, Gmail, browsers, or the *Share* menu.
- Vertical, card-style slide list with a page counter, and pinch-to-zoom.
- Understands slide **masters and layouts** (placeholders inherit position, size, fonts and colours), shapes (rectangles, rounded rectangles, ellipses, lines, freeform paths, groups, **15+ preset shapes** like arrows, stars, hexagons, chevrons), gradient and solid fills, borders, theme colours with tints, **tables**, bullets and numbering, text anchoring and autofit, and pictures with cropping.
- **Right-to-left text** (Persian, Arabic) with correct alignment and bullets.
- Everything is parsed and drawn by the app itself, straight from the file's XML.
- **Speaker notes**: a badge on the slide counter, tap to read notes from any slide.
- **Slide search** (**⋯ > Search slides**): full-text search across shapes, tables and notes.
- **Go to slide** (**⋯ > Go to slide**): slider and number input to jump instantly.
- **Share current slide** (**⋯ > Share current slide**): renders the visible slide as a PNG.
- **Keep screen on** toggle in the overflow menu.
- **Export as PDF** (**⋯ > Export as PDF**): every slide is rendered to a page, then you can save, open or share the file.
- **Recent files** on the start screen with relative timestamps, slide count and remembered position. Long-press one for its details (type, count, size, last opened); sort them by recency, name or type from the menu.
- **Global search** (**⋯ > Search all files**): searches the text of every recent file — presentations, documents and sheets — with a snippet per hit; tap to jump to that slide, block or row. PDFs are excluded (Android cannot extract their text).
- **Bookmarks** (**⋯ > Bookmark**): save the current slide; the home screen lists them, tap to jump back, long-press to delete.
- **Night mode** (**⋯ > Night mode**): dims the slides for reading in the dark; remembered across sessions.

### Universal document viewer
Open a file someone sent you without installing an office suite: **Open document** on the start screen, in the menu, or from any app's *Share* menu.

- **Word (`.docx`):** styles and headings, bullets and numbering, tables, right-to-left text, inline images.
- **Sheets (`.xlsx`, `.csv`):** sheet tabs, A/B/C column headers, column widths, cell colours, merged cells, Excel-style date and percent formatting, go-to-row. CSV files open as a single sheet with a bold header row.
- **Plain text:** `.txt`, `.rtf` (bold/italic/underline, Unicode escapes) and Markdown (headings, bullets, `**bold**`, `*italic*`, `` `code` ``).
- **Legacy Office, text-only:** old `.doc`, `.ppt` and `.xls` files open as recovered plain text, with a clear note that formatting, tables and images are lost.
- **Everywhere:** full-text search, text size 0.8–1.6×, pinch-to-zoom, share the original file, open externally, keep screen on, night mode, bookmarks, and recent files with block/row counts.
- Limits, stated up front in the app: XLSX is capped at 5,000 rows / 100 columns, DOCX at 2,000 blocks.

### Document & ID-card scanner
A CamScanner-style scanner with no account, no ads and no upload: **Scan document** on the start screen, in the menu, or as a launcher shortcut.

- **Take photos or pick them** from the gallery, several pages in a row. The photo is taken by your phone's own camera app, so LitePPTX needs **no camera permission**.
- **Automatic page detection** proposes the outline of the paper, and you can drag the four corners to fix it (or tap *Full photo* / *Auto*). The page is straightened (perspective corrected) on the device.
- **Filters:** *Original*, *Clean* (removes shadows and evens out the paper), *B&W*, *Gray* and *Magic* (stronger levels, saturation boost and light sharpening for dull pages), with a live preview. Rotate any page.
- **Undo**: remove the last captured page without starting over.
- **Save images**: every captured page can also be saved as a JPEG in Downloads.
- **PDF quality choice**: pick *Original*, *Smart* or *Small* before building the scan PDF (remembered for next time).
- **ID-card mode**: capture front and back of an ID card, then both sides are composited onto one A4 page at 200 DPI — ready for forms, applications and photocopies.
- **Create PDF** from all the pages, then save to Downloads, open or share. Each page is stored as a JPEG with a long edge of up to 2600&nbsp;px, which keeps text sharp and files small.
- All the image processing is our own code, with no ML library and no Google Play Services, so it works on any phone.

### Images to PDF
Select photos in your gallery, tap **Share > Create PDF**, and get a clean, professional PDF in seconds.

- **Reorder, rotate, remove** pages before creating.
- **Page size:** Fit (page matches each image), A4 or Letter. Portrait and landscape are chosen per image.
- **Margins:** none, small or normal.
- **Honours EXIF orientation**, so phone photos come out the right way up. Transparent PNGs are flattened onto white.
- **Share, save to Downloads, or open** the finished file, with the size and the saving versus your originals shown.

#### Compression that respects your pictures

| Preset | What it does | Best for |
|---|---|---|
| **Original** | No resampling. JPEGs are embedded **byte-for-byte**, PNGs stay lossless. | Archiving, maximum fidelity |
| **High** | Up to 300 dpi / 3600 px, JPEG q90. PNG screenshots stay lossless. | Printing |
| **Smart** *(default)* | Up to 200 dpi / 2600 px, JPEG q82. Looks identical on screen and in print, far smaller. | Everyday use |
| **Small** | Up to 130 dpi / 1600 px, JPEG q68. | Chat and email |

Under the hood there is no PDF library. LitePPTX writes the PDF itself and embeds JPEG data directly, so a photo that is already small enough is **never re-encoded**. Images are only downscaled when they carry more pixels than the page can show at the chosen resolution.

In our emulator test (a 4000×3000 photo, a small JPEG and a text-heavy PNG screenshot, 1.5&nbsp;MB in total) the results were 1.50&nbsp;MB with *Original*, 758&nbsp;kB with *Smart* and 356&nbsp;kB with *Small*.

### PDF viewer
Open any PDF from a file manager, browser, or the *Open PDF* tile on the start screen. Built on Android's `PdfRenderer` (API 21+), no libraries.

- **Pinch-to-zoom** and **page jump** (slider + number input).
- **Share**, **save to Downloads**, or **open externally** in another app.
- **Keep screen on** toggle.
- **Night mode** (**⋯ > Night mode**) dims the pages for reading in the dark; **Bookmark** (**⋯ > Bookmark**) saves the current page to the home screen's Bookmarks section.
- LitePPTX registers itself as a PDF handler, so tapping any `.pdf` file can open it directly. It also accepts PDFs from any app's *Share* menu.

### PDF tools
**PDF Tools** on the start screen (or in the menu): compress, merge and split PDFs, all offline.

- **Compress:** tries a lossless rebuild first. It re-deflates the compressed streams inside classic xref-table PDFs and only keeps the result when it saves at least 5% — text stays text. If that cannot help, it offers a rendered rebuild at Keep (200 dpi), Balanced (170 dpi) or Small (130 dpi), and says plainly that every page becomes an image. Pick several PDFs at once and they are compressed in a row, each with its own Save button in the results list.
- **Merge:** pick two or more PDFs; they are joined in the order you picked them.
- **Split:** pick a page range and get just those pages as a new PDF.
- Every result can be shared, saved to Downloads or opened in the viewer.

### Updates
- Checks GitHub Releases at most every 6 hours (and on demand from **⋯ > Check for updates**).
- Tap the update bar to read *What's new*, then **Update**.
- Until you update, a red dot stays on the **⋯** button and the menu entry reads *Update to v…* or *Install v…*.
- A finished download is remembered: even after you close the app or go offline, the bar comes back as "ready to install" and you do not have to download again.
- The APK is verified (size, SHA-256, package identity) before Android's installer opens, and Android refuses anything not signed with the same key.

## Install

1. Download `LitePPTXViewer.apk` from the [latest release](https://github.com/Evilenor/LitePPTXViewer/releases/latest).
2. Open it. If Android asks, allow installs from your browser or file manager.
3. Done. Future versions install from inside the app.

Requires **Android 5.0 (API 21)** or newer. Integrity: each release ships a `.sha256` file (`sha256sum -c LitePPTXViewer.apk.sha256`).

## Using it

- **View a presentation:** open any `.pptx` file, or launch the app and tap *Open PowerPoint*. Use the overflow menu for speaker notes, slide search, go-to, share, and export.
- **View a PDF:** open any `.pdf` file, or tap *Open PDF* on the start screen.
- **View a document:** open any `.docx`, `.xlsx`, `.txt`, `.csv`, `.rtf` or `.md` file, or tap *Open document* on the start screen. Old `.doc`/`.ppt`/`.xls` files open as plain text.
- **Shrink, join or cut a PDF:** tap *PDF Tools*, pick *Compress*, *Merge* or *Split*, and choose a quality preset for rebuilds.
- **Scan a document:** tap *Scan document*, take or pick photos, adjust the corners, choose a filter, then *Create PDF*. For ID cards, tap *ID Card* for a 2-sided scan onto one page. Long-press the app icon for a shortcut.
- **Make a PDF from photos:** select images in Gallery / Photos / Files, tap *Share*, choose **Create PDF**. Or long-press the app icon and pick *Images to PDF*.

## Limitations

LitePPTX is deliberately minimal:

- Charts and SmartArt are not rendered; some less common preset shapes may fall back to rectangles.
- Animations, transitions and embedded fonts are ignored (system fonts are used).
- Only `.pptx` is supported, not legacy `.ppt` — but legacy `.doc`/`.ppt`/`.xls` open as plain text in the document viewer.
- Lossless PDF compression only works on classic xref-table PDFs; other PDFs fall back to a rendered rebuild, which turns pages into images (text can no longer be selected).
- Global search covers recent files only and never PDFs (Android's `PdfRenderer` cannot extract text).
- XLSX is capped at 5,000 rows / 100 columns and DOCX at 2,000 blocks; the viewer says so when the cap is hit.
- Page reordering uses the ◀ ▶ buttons rather than drag and drop.
- The scanner has **no OCR** (no searchable text). Automatic edge detection works best on a plain background; on a busy one, drag the corners by hand.
- On very low-memory phones the scanner reduces the page resolution automatically; this has only been tested on an emulator so far.

Rendering fidelity has been checked on an Android 15 emulator with test decks; real-world files vary, so please [open an issue](https://github.com/Evilenor/LitePPTXViewer/issues) with a sample if something looks wrong.

## Privacy

LitePPTX has no analytics and no ads. Its only network traffic is the update check (`GET api.github.com/.../releases/latest`) and, when you tap Update, the APK download from GitHub. Files you open, scan or convert are processed on the device. Scanning uses your phone's camera app to take the photo, so LitePPTX never asks for the camera permission. The **Donate** button only opens the support page in your browser; the app itself sends nothing. Declared permissions: `INTERNET` (updates) and `REQUEST_INSTALL_PACKAGES` (to hand the verified update to Android's installer).

## Build from source

```bash
git clone https://github.com/Evilenor/LitePPTXViewer.git
cd LitePPTXViewer
./gradlew assembleRelease      # needs JDK 17 and the Android SDK (platform 35)
```

The APK lands in `app/build/outputs/apk/release/`. Without signing secrets it is signed with the debug key.
Run `./gradlew assembleRelease lintRelease` to build the release APK and run Android Lint. The project currently has no automated unit-test cases.

### Project layout

```
app/src/main/java/com/litepptx/viewer/
  MainActivity.kt        viewer screen, update bar
  OfficeViewerActivity.kt  Word / Sheets / plain-text viewer
  PdfToolsActivity.kt    compress, merge and split PDFs
  PdfMakerActivity.kt    Images to PDF editor and share target
  ScanActivity.kt        document scanner: capture, crop, filter, PDF
  scan/                  page detection, perspective warp, filters, crop view
  pptx/                  PPTX parser and slide renderer (pure Android APIs)
  office/                OOXML package, DOCX/XLSX parsers, legacy text fallback, plain-text formats
  pdf/                   streaming PDF writer, image pipeline, slide-to-PDF export, file provider, PDF tools
  update/Updater.kt      GitHub Releases updater
```

### Releasing

GitHub Actions builds and runs Android Lint for pushes to `main`, pull requests, and version tags. Tag builds also check that the tag matches `versionName`, verify the APK package and signature, compare the signing certificate with the latest release, and generate a SHA-256 checksum. The release job has write access only when publishing a version tag.

For a new repository, or if the workflow secrets are missing, add these **repository Actions secrets** under **Settings → Secrets and variables → Actions**. Keep the values private; never commit the keystore or passwords:

| Secret | Value |
|---|---|
| `LITE_KEYSTORE_B64` | Base64-encoded release keystore (`.jks`) |
| `LITE_KEYSTORE_PASSWORD` | Keystore password |
| `LITE_KEY_ALIAS` | Signing key alias |
| `LITE_KEY_PASSWORD` | Signing key password |

Each release must use the same keystore as earlier releases so Android can install it as an update. A tagged build fails if any signing secret is missing or invalid; it never publishes a debug-signed APK. Build on `main` and wait for CI before tagging:

```bash
# 1. bump versionCode and versionName in app/build.gradle.kts
# 2. update README files, CHANGELOG.md, and release-notes/vX.Y.Z.md
# 3. commit and push the changes to main; wait for the build workflow
git tag vX.Y.Z
git push origin vX.Y.Z
```

The tag workflow builds the APK with the stored release key, checks the signature and package version, computes SHA-256, then publishes `LitePPTXViewer.apk` and `LitePPTXViewer.apk.sha256` as the latest GitHub Release. The in-app updater reads that release.

## Support LitePPTX ♥

LitePPTX is free, open source and ad-free, and it will stay that way. If it saved you time, a donation keeps it maintained and lets me keep building small, honest tools.

<p align="center">
  <a href="https://the-evilenor.vercel.app/support"><img src="https://img.shields.io/badge/%E2%99%A5%20Support%20LitePPTX-the--evilenor.vercel.app%2Fsupport-ff4d6d?style=for-the-badge" alt="Support LitePPTX: the-evilenor.vercel.app/support"></a>
</p>

It is also one tap away inside the app: the **Donate** card on the start screen and **⋯ → Donate ♥**. Can't donate? Starring the repo, sharing it with a friend or reporting a bug helps just as much.

## License

[MIT](LICENSE) © Evilenor
