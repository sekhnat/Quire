# Terminal-first Android development

## Environment

- SDK: `~/Android/Sdk`; Android 16 / API 36; build tools 36.0.0.
- Build JDK: `/usr/lib/jvm/java-21-openjdk` (21). System Java is 27; do not use it for these builds.
- Emulator: `Android_API_36` (Pixel 7, x86_64, KVM, 4 cores, 4 GB RAM).
- Full guide: `~/.config/android/README.md`; local overview: `ANDROID.md`.

Activate in Zsh (new interactive terminals already do this):

```zsh
source ~/.config/android/env.zsh
```

**Bash-based runners must use Zsh**, not source this Zsh-specific file directly:

```bash
zsh -fc 'source ~/.config/android/env.zsh; ./gradlew assembleDebug test lint'
```

## Build and test

Run from the Android project root. Always use its Gradle wrapper; no global
Gradle or standalone Kotlin compiler is needed. Templates can automatically
provision a Java 17 compilation toolchain while Gradle runs on Java 21.

```zsh
./gradlew assembleDebug test lint
./gradlew connectedDebugAndroidTest  # Requires a running device/emulator.
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Run on the emulator

```zsh
emulator -avd Android_API_36         # Separate terminal; wait for Android to boot.
adb devices
./gradlew installDebug
adb shell am start -n 'APPLICATION_ID/ACTIVITY_CLASS'
```

Replace the launch component with the project's actual application ID and
launcher activity. With multiple devices, select one using `adb -s SERIAL`.
Stop the default emulator with `adb -s emulator-5554 emu kill`.

## SDK management

Prefer `android sdk list` and `android sdk install PACKAGE`;
`sdkmanager` is available but deprecated. Use `emulator -accel-check` to check KVM.
Wait for SDK installations to finish before other Android tooling: they share an SDK lock.
First builds need internet access; subsequent builds reuse cached Gradle, toolchains, and dependencies.

## The app (Quire, an EPUB reader)

- Application ID `com.quire.reader`, minSdk 30 (all-files access starts at Android 11), Kotlin + Jetpack Compose. Design source: the "Quire Reader" Claude Design project (Nocturne design system; tokens in `theme/Tokens.kt`).
- `data/` is the library: Room (`data/db`), the folder scanner and Calibre `metadata.opf` parser (`data/scan`), `LibraryRepository`, `SettingsStore` (DataStore). `reader/` wraps Readium (`ReaderSession`, `EpubHost`, `PrefsMapper`). `ui/` has the Compose screens and `QuireViewModel`.
- **Readium is pinned to 3.3.0.** 3.4.0 needs compileSdk 37, which AGP 9.0.1 does not support. Coil is pinned to 3.5.0 for the same reason (3.6.x pulls Compose 1.12 / compileSdk 37). Check `checkDebugAarMetadata` errors before bumping either.
- The app reads books straight from shared storage and needs "All files access". On the emulator: `adb shell appops set com.quire.reader MANAGE_EXTERNAL_STORAGE allow`.
- Testing the library on the emulator: `adb push` EPUBs into `/sdcard/Books` and Calibre-style trees (`Author/Title (id)/book.epub` + `metadata.opf` + `cover.jpg`) into `/sdcard/Calibre Library`, then relaunch; the app rescans on start. Scan results are logged under the `LibraryScanner` tag.
- Fonts: `res/font` (Compose) and `assets/fonts` (served to Readium's WebView) hold copies of the same files; keep them in step.
