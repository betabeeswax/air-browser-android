# AIR Browser for Android

Native Android browser built with Kotlin and Android WebView.

## First version

This is an early testing build; features may change.

- Multiple browser tabs
- Address and search bar
- Back, forward, reload and home
- Bookmarks and browsing history
- Android Download Manager integration
- Dark AIR-style mobile interface
- Session tab restoration
- Reading mode for cleaner article pages
- QuietChat shortcut
- Automatic update checks with an Update now / Later prompt
- Manual update checks and an automatic-update on/off control

## Build

Open this repository folder in Android Studio, or use a recent Gradle installation with JDK 17:

```sh
gradle :app:assembleDebug
```

The debug APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`

The GitHub Actions workflow builds an APK and publishes it in the GitHub Releases tab. Debug builds are for testing and are not signed for Play Store distribution. Android checks GitHub Releases for newer APKs when automatic updates are enabled; choosing Update now opens the APK download, and Android still asks the user to approve installation.

## Privacy

The app uses Android WebView and Android's Download Manager. Bookmarks, history and open-tab URLs are stored locally in app preferences. This first version does not upload browsing history, passwords or cookies to AIR servers. Passwords are not collected by a custom AIR password database in this initial build.
