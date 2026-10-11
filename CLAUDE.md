# CLAUDE.md

Android ebook reader (EPUB, PDF, TXT) written in Kotlin + Jetpack Compose. The goal is a simple reading experience like Books on iPad mini, with true edge-to-edge pages and support for phones, tablets and foldables. See `README.md` for features and the source layout.

## Commands

```bash
./gradlew assembleRelease   # release APK (signed with the debug key) → app/build/outputs/apk/release/
./gradlew assembleDebug
./gradlew installDebug      # install on a connected device
```

Requires JDK 17 and the Android SDK (compileSdk 35, minSdk 26). CI (`.github/workflows/build-apk.yml`) builds the release APK on every push and uploads it as the `ebook-reader-apk` artifact.

## Notes

- EPUB/TXT pages are laid out by `app/src/main/assets/reader.js` inside a WebView; `ui/reader/WebBookController.kt` serves chapters through `WebViewAssetLoader` and injects the script. Changes to pagination usually touch both files.
- PDF uses Android's `PdfRenderer` (`ui/reader/PdfBook.kt`, `PdfReader.kt`).
- Text margins must include display-cutout insets; the page background must stay edge-to-edge (that was the original pain point on other Android readers).
- UI strings live in `res/values/strings.xml` and `res/values-th/strings.xml`; keep both in sync.
