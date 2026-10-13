# Myboox — Android ebook reader

A simple Android ebook reader modelled on Books on iPad mini. It supports EPUB, PDF, TXT, Markdown (.md) and HTML, and it works on phones, tablets and foldables.

แอปอ่าน ebook บน Android ที่ใช้งานง่ายแบบ Books บน iPad mini รองรับ EPUB, PDF, TXT, Markdown (.md) และ HTML ใช้ได้ทั้งมือถือ แท็บเล็ต และจอพับ

## Features / ความสามารถ

- **Edge-to-edge pages.** The page colour fills the whole screen, including the status-bar area and the camera notch, so there is no black strip at the top. While you read, the system bars are hidden. The text stays clear of the notch and the rounded corners.
  หน้ากระดาษเต็มจอจริง สีพื้นหลังลากไปถึงขอบบนและรอยบากกล้อง ไม่มีแถบดำด้านบน ส่วนตัวหนังสือจะเว้นไม่ให้โดนรอยบาก
- **Turning pages like iPad.** The page folds back like paper from the point where you grab it: a corner if you grab near the top or bottom, the whole edge if you grab the middle. Tap the right or left edge, swipe, or use the volume buttons. Tap the middle to show the controls. You can switch the animation to Slide or None.
  พลิกหน้าแบบกระดาษพับตรงจุดที่จับ แตะขอบขวา/ซ้าย ปัด หรือใช้ปุ่มเสียงเปลี่ยนหน้า แตะกลางจอเพื่อเปิดเมนู
- **Text close to the edge.** On phones with a camera cutout, the chapter title moves into the strip beside the camera, so the text starts just below it. "Full-screen text" hides the title and page number completely.
  ตัวหนังสือชิดขอบบนเกือบเท่า iPad ชื่อบทย้ายไปอยู่ข้างกล้องหน้า และมีโหมด "เต็มจอสุด"
- **The "Aa" panel.** It has text size, four themes (White, Paper, Gray, Night), font, line spacing, margins, justify, brightness, and keep screen on.
  ปรับขนาดตัวอักษร ธีม 4 แบบ แบบอักษร ระยะบรรทัด ระยะขอบ ความสว่าง
- **Every screen size.** The library grid adapts to the screen width. On tablets and unfolded foldables in landscape, books show two pages side by side. Rotation and resizing keep your place.
  รองรับทุกขนาดจอ จอแนวนอนขนาดใหญ่แสดงสองหน้าเหมือนหนังสือจริง หมุนจอแล้วไม่หลุดหน้าที่อ่าน
- **Library.** Covers, reading progress, a "Continue reading" card, sort by recent/title/author. You can also use "Open with" or "Share" from other apps.
  ชั้นหนังสือพร้อมปก เปอร์เซ็นต์ที่อ่าน และการ์ด "อ่านต่อ"
- **Contents and progress slider.** You can jump to any chapter. EPUB 2 (NCX) and EPUB 3 (nav) are both supported. In TXT files, lines like "บทที่ 1" or "Chapter 1" become chapters.
- **Markdown and HTML manuscripts.** A `.md` or `.html` file reads like an EPUB. It is split into chapters at `#` headings (or `##` when there is only one `#`, which is taken as the book title), and the headings fill the contents. Title and author come from YAML front matter (`title:`, `author:`) or the HTML `<title>`. Bold, italics, quotes, tables, `---` scene breaks (shown as ⁂) and in-book links all work. A single line break starts a new line, as in Obsidian and Typora. Images need to be in the book: embedded (`data:`) images work, and so do image files inside a zip (see below).
  ไฟล์ .md / .html อ่านได้เหมือน EPUB แบ่งบทตามหัวข้อ # และมีสารบัญให้อัตโนมัติ
- **Books written as one file per episode.** Pick several `.md` files together (`ep01.md`, `ep02.md`, …) and they become one book, one chapter per file, in natural file-name order (ep2 before ep10). The app then asks for the book's name. Later, add new episodes with **+**, by sharing them to the app (several files at once work too, e.g. from Google Drive), or by long-pressing the book and choosing **Add episodes**. When Markdown books are already on the shelf, the app asks which book the new files belong to. Adding a file with the same name replaces that episode, so an edited episode can just be added again.
  เขียนแยกไฟล์ทีละตอนได้ เลือกหลายไฟล์พร้อมกันจะรวมเป็นเล่มเดียว และกดค้างที่หนังสือเพื่อ "เพิ่มตอน" ภายหลังได้
- **A zip of episodes.** A `.zip` holding the `.md` files (folders are fine) becomes one book named after the zip file. Images the episodes link to (`![](images/map.png)`) are shown, and a `cover.jpg`/`cover.png` becomes the cover. Zipping everything again later and adding it to the same book updates edited episodes and adds new ones. Zips made on Windows with Thai file names work too.
  zip ทุกตอนมาไฟล์เดียวได้ ชื่อไฟล์ zip เป็นชื่อหนังสือ รูปในเรื่องแสดงได้ และ cover.jpg เป็นปก
- **Share your book with others.** Long-press a book → **Share / export**. **EPUB** (validated with EPUBCheck) opens in Apple Books, Google Play Books, Kindle (via Send to Kindle) and other ebook apps, with cover, contents, title and author. You can share it straight to LINE, Gmail or Drive, or save it to the device. **PDF** goes through Android's print screen: choose "Save as PDF" and a paper size (A5 by default). Each chapter starts on a new page, and the text stays real, sharp text.
  แชร์หนังสือให้คนอื่นอ่าน ส่งออกเป็น EPUB หรือ PDF ได้
- **Thai TXT files.** Legacy TIS-620 / Windows-874 files are detected automatically.
  ไฟล์ TXT ภาษาไทยแบบเก่า (TIS-620) อ่านได้ ไม่เป็นภาษาต่างดาว
- **PDF.** White page margins are trimmed automatically. On phones, PDFs scroll continuously at full width, because a printed page is much shorter than a tall phone screen. On tablets they turn page by page with the curl animation, two pages side by side in landscape. Pinch or double-tap to zoom. The Paper and Night themes recolour the pages too.
  PDF ตัดขอบขาวอัตโนมัติ มือถือเลื่อนลงต่อเนื่องเต็มความกว้าง แท็บเล็ตพลิกทีละหน้า

The app is fully offline. It has no internet permission, no account and no tracking.

## Install / ติดตั้ง

1. On the Android device, open **https://github.com/ykasorns/myboox/releases/latest** in the browser (Chrome) and sign in to GitHub if asked.
   เปิดลิงก์ด้านบนในเบราว์เซอร์บนมือถือ (ล็อกอิน GitHub ถ้าระบบถาม)
2. Under **Assets**, tap `myboox-<number>.apk` to download it.
   ใต้หัวข้อ **Assets** กดไฟล์ `myboox-<เลข>.apk` เพื่อดาวน์โหลด
3. Open the downloaded file and allow "Install unknown apps" when Android asks. Installing over an older build keeps your books.
   เปิดไฟล์ที่โหลดมาแล้วกดติดตั้ง ติดตั้งทับเวอร์ชันเก่าได้เลย หนังสือไม่หาย

Every push to `main` publishes a new release. The GitHub mobile app cannot download build artifacts, so use the release link instead.

Every build is signed with the same key (`signing/myboox-release.jks`), so new builds install over old ones. That key is in the repository, which is fine for a personal app; use a private key before you publish to Google Play.

## Build locally

Open this folder in Android Studio, or run:

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug        # install on a connected device
```

You need JDK 17 and the Android SDK (compileSdk 35). The minimum Android version is 8.0 (API 26).

## How it works

| Part | Where |
|---|---|
| Import (copy, hash, unpack, cover) | `importer/` — `EpubImporter`, `PdfImporter`, `TxtImporter`, `HtmlBookImporter` (Markdown + HTML) |
| Library storage | `data/LibraryRepository.kt` (`files/library.json`, one folder per book) |
| EPUB/TXT rendering | `ui/reader/WebBookController.kt` + `assets/reader.js` |
| PDF rendering | `ui/reader/PdfBook.kt` (Android `PdfRenderer`) + `PdfReader.kt` |
| Page-turn animation | `ui/reader/PageCurl.kt` (fold renderer + state), `ReaderFrame` in `WebBookController.kt` |
| Margins beside the camera cutout | `ui/reader/PageFrame.kt` |
| Export (EPUB, PDF via printing) | `export/EpubWriter.kt`, `export/BookExporter.kt` |
| Crayon bookshelf | `ui/library/Crayon.kt`, `LibraryScreen.kt` |
| Controls and settings | `ui/reader/ReaderChrome.kt`, `ReaderSheets.kt` |
| Full-screen handling | `MainActivity` (edge-to-edge, cutout mode), `util/Window.kt` (immersive bars) |

EPUB chapters are served to a WebView from app storage through `WebViewAssetLoader`. Each chapter gets `reader.js` injected. The script lays the chapter out in CSS columns exactly one screen wide, and turning a page scrolls by one screen. Margins come from the app and include the display-cutout insets. That is how the page background can fill the screen while the text never sits under the camera.

Fixed-layout EPUBs (comics, picture books) are shown as reflowable pages. DRM-protected books cannot be opened.
