# Terminal-first Android development

## Installed environment

- SDK: `~/Android/Sdk`
- Shell configuration: `~/.config/android/env.zsh`, loaded by `~/.zshrc`
- Build runtime: `/usr/lib/jvm/java-21-openjdk` (JDK 21)
- Stable SDK platform: Android 16 / API 36
- Build tools: 36.0.0
- Virtual device: `Android_API_36` (Pixel 7, x86_64, Google APIs, 4 CPU cores, 4 GB RAM)
- Android Studio remains available at `/mnt/1T/android-studio/bin/studio`, but is not required.

Open a new terminal, or activate the configuration in an existing Zsh shell:

```zsh
source ~/.config/android/env.zsh
```

This changes Java for that shell and its children, not the system Java installation.

## Create your first Kotlin / Jetpack Compose app

Choose a new, empty destination directory:

```zsh
android create empty-activity \
  --name MyApp \
  --namespace com.example.myapp \
  --application-id com.example.myapp \
  --output ~/Projects/AndroidApps/MyApp

cd ~/Projects/AndroidApps/MyApp
./gradlew assembleDebug
```

Edit the project with your preferred editor. Kotlin and Compose dependencies are
managed by Gradle; no separate Kotlin compiler or global Gradle installation is
needed. Use each project's `./gradlew`, which selects its compatible Gradle
version. Google's template includes a resolver that can download its Java 17
compilation toolchain independently of the Java 21 build runtime.

First builds need internet access to download Gradle and dependencies.

## Start the emulator and run an app

In a separate terminal:

```zsh
emulator -avd Android_API_36
```

Wait for the Android home screen, then from the project directory:

```zsh
adb devices
./gradlew installDebug
adb shell am start -n com.example.myapp/.MainActivity
```

Close the emulator window to stop it, or use `adb -s emulator-5554 emu kill`
when it is the emulator running on that port. If you have multiple devices,
use `adb -s <serial>` to choose one explicitly.

## Build and test

```zsh
./gradlew assembleDebug test lint
# With an emulator running:
./gradlew connectedDebugAndroidTest
```

Debug APKs normally appear under `app/build/outputs/apk/debug/`.

## Manage SDK packages

```zsh
android sdk list
android sdk list --all 'platforms/*'
android sdk install platforms/android-36
android sdk install build-tools/36.0.0
emulator -list-avds
avdmanager list avd
emulator -accel-check
```

Google's current command-line package supplies `android sdk` as the modern
package manager. `sdkmanager` is also available as a deprecated compatibility
command. Run `android --help` to discover the other terminal tools. No Google
account login is required for local app builds and this local emulator.

## Restore the previous shell setup

Remove the Android source line at the end of `~/.zshrc`, then open a new
terminal. A timestamped `~/.zshrc.before-android-setup-*` backup contains the
pre-setup shell configuration. Removing the source line does not delete SDK
packages or the emulator.
