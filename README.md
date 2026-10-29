# Glass Launcher

An experimental Android TV / Fire TV launcher with refractive glass, water-like transitions, a floating app dock, and a clock that morphs into HDMI controls or Continue Watching.

## Try the beta

See [Releases](https://github.com/carterinouye-ops/tv-launcher/releases) for published APKs and the [beta installation guide](docs/BETA-INSTALL.md) for setup, compatibility, optional permissions, and removal. This is experimental software; development has focused on a Hisense Fire TV.

## Features

- Native 1920 × 1080 glass rendering with OpenGL ES 3.0.
- Glass clock, top navigation pill, and app dock.
- Varied liquid transitions between the clock, HDMI bubbles, and content card.
- HDMI names read from the TV's input configuration.
- Continue Watching based on media-session and notification data available from supported apps.
- A 20-second idle timeout that returns to the clock.
- Remote-friendly navigation and app rearrangement.

## Build an APK

Use Android Studio with JDK 17 and Android SDK 34. The project includes its Gradle wrapper.

1. Clone or download the repository and open it in Android Studio.
2. Let Android Studio create `local.properties` with your Android SDK path. For a command-line setup, copy `local.properties.example` to `local.properties` and update `sdk.dir`.
3. Optionally set `tmdb.api.key` in `local.properties` to your own TMDB API key for title and artwork lookups. The app builds without this key; artwork enrichment will be unavailable.
4. Build from Android Studio or run:

```sh
./gradlew :app:assembleDebug
```

Windows:

```bat
gradlew.bat :app:assembleDebug
```

The installable APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The first build needs internet access to download the Gradle and Android dependencies. The debug build is suitable for development and testing.

## Install and try it

The minimum Android version is Android 9 (API 28). Enable developer options and ADB debugging on the TV, then use its own address:

```sh
adb connect TV_ADDRESS:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.carter.glasslauncher/.ui.MainActivity
```

Android TV can offer the app as a home-screen launcher. Fire TV uses the included Home redirect accessibility service; enable it in the device's accessibility settings if needed. The optional repair permission lets that service restore its enabled state if Fire OS clears it:

```sh
adb shell pm grant com.carter.glasslauncher android.permission.WRITE_SECURE_SETTINGS
```

Enable notification access for Glass Launcher if you want it to collect the media notifications used by Continue Watching. Data availability and HDMI switching vary by TV, streaming app, and OS version. This has been developed on a Hisense Fire TV; other devices may require adjustments.

## Where the code lives

- `app/src/main/java/com/carter/glasslauncher/gl/`: glass shaders and liquid motion.
- `app/src/main/java/com/carter/glasslauncher/ui/`: launcher UI, dock focus, and transition controllers.
- `app/src/main/java/com/carter/glasslauncher/data/`: installed apps, HDMI inputs, history, and artwork lookups.
- `app/src/main/java/com/carter/glasslauncher/service/`: media-notification collection and Home redirect.
- `app/src/main/res/`: layouts, icons, fonts, and bundled artwork.

## Local configuration

`local.properties`, build outputs, developer screenshots, and signing files are excluded from version control. Supply your own SDK path and API key locally. Android apps cannot keep an embedded API key secret from someone who inspects their APK.
