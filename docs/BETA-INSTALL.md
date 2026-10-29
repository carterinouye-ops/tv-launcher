# Glass Launcher beta

Experimental Android TV / Fire TV launcher. Developed on a Hisense Fire TV; other models and OS versions need testing. Requires Android 9 (API 28) or newer and OpenGL ES 3.0. A TV running an older Android base cannot install this beta even if it has a recent Fire OS update.

## Install

Download the signed `glass-launcher-1.0.0-beta.2.apk` from the GitHub release assets. GitHub's automatically generated source ZIP is not an installable app.

With Android Platform Tools installed on your computer, enable developer options and ADB debugging on the TV. Use the TV's own address and accept its debugging prompt:

```sh
adb connect TV_ADDRESS:5555
adb install -r glass-launcher-1.0.0-beta.2.apk
adb shell am start -n com.carter.glasslauncher/.ui.MainActivity
```

Some devices use wireless debugging with pairing and a different port; follow the address and pairing details shown on that device.

If installation reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an older copy was signed with a different key. Uninstall it first, then install this beta. Uninstalling clears the launcher's settings, app order, and watch history:

```sh
adb uninstall com.carter.glasslauncher
```

## Home button and optional features

On Android TV, choose Glass Launcher as the Home app if the device offers that setting.

On Fire TV, open the device's Accessibility settings and enable **Glass Launcher Home Redirect** if available. Fire OS restrictions vary; installing the APK alone does not guarantee Home-button replacement. The stock launcher stays installed.

For optional automatic repair if Fire OS clears the Home Redirect service, grant:

```sh
adb shell pm grant com.carter.glasslauncher android.permission.WRITE_SECURE_SETTINGS
```

This allows the launcher to re-enable its accessibility service. To stop using Home Redirect, revoke this permission before disabling the service in Accessibility settings:

```sh
adb shell pm revoke com.carter.glasslauncher android.permission.WRITE_SECURE_SETTINGS
```

Enable **Glass Launcher Watch History** in notification access settings for Continue Watching. Supported apps must expose media metadata; some apps will provide no history or artwork. This beta contains no TMDB key, so TMDB artwork enrichment and episode-news lookups are unavailable. HDMI discovery, labels, and switching also depend on the TV.

## Privacy and permissions

The launcher lists installed apps to populate the dock. Notification access reads media metadata for local watch history. Home Redirect uses accessibility events and window content to recognize Fire TV Home and TV input labels. The optional secure-settings permission restores its own accessibility service. Network permissions support optional TMDB lookups; builds configured with a TMDB key send title queries to TMDB. Wi-Fi/location permissions support network information where permitted by the OS. No TMDB key is included in this beta.

## Return to your previous launcher

Revoke the repair permission above if granted, then disable Glass Launcher Home Redirect. On Android TV, select your previous Home app. You can remove Glass Launcher through device settings or `adb uninstall com.carter.glasslauncher`.

## Report a problem

Open an issue at https://github.com/carterinouye-ops/tv-launcher/issues with your TV model, Android/Fire OS version, beta version, the steps that caused the problem, and whether Home Redirect or notification access was enabled. Remove personal information before attaching logs.
