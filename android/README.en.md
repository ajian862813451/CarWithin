# CarWithin for Android

**English** · [简体中文](README.md) · [CarWithin project home](../README.en.md)

CarWithin receives the system CarLife interface on the same Android phone and provides local preview, touch input, and Wi-Fi Direct projection to compatible dashboards.

## Download and install

Choose a version with an Android APK from [GitHub Releases](https://github.com/xmz28/CarWithin/releases), then open the APK and follow the Android installation prompts. The minimum installation target is Android 8.0 (API 26). Same-phone CarLife also requires a compatible system car connectivity component. The first version was tested with OPPO Car+. Compatibility with other phones, OS versions, and Car+ versions requires device testing.

## Quick start

1. Enable Wi-Fi on the phone and put the dashboard into its projection or connected navigation mode.
2. From CarWithin's home screen, open Current output, select the dashboard profile, scan, and connect to your dashboard. Grant Android's nearby-device or location permission when prompted.
3. Return home and tap Start local CarLife. After opening Car+, return to CarWithin to view the interface or enter fullscreen. The app sends video to the connected dashboard and tries to reconnect to the last verified dashboard on a later launch.
4. If the dashboard stays blank, try the moving test pattern in Current output. Stop CarLife from the home screen when finished.

Settings offers 20, 25, and 30 FPS for local CarLife and the Ninebot 5-inch profile. Actual rates depend on the phone and dashboard. Other profiles use their own presets.

## Compatibility

| Device or feature | Verification |
|---|---|
| OPPO PHB110, Android 16 | Local CarLife display and touch input verified |
| Ninebot KZ110 5-inch | CarLife display verified through the OPPO phone |
| Ninebot 7-inch and 4.3-inch | Experimental profiles, hardware verification pending |
| Tailing 4.3-inch and ZEEHO 6.2-inch | Protocol presets included, CarWithin hardware verification pending |

## Build from source

Open this directory in Android Studio with JDK 21 and Android SDK 37. On the first build, the Gradle Wrapper downloads its configured Gradle version and dependencies.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Use gradlew.bat on Windows. The Debug APK is under app/build/outputs/apk/debug/. Sign distributable builds with your own release key. Version name and build number are defined in app/build.gradle.kts.

About → Check for updates reads update.json from the repository's Latest release. A standalone Android release that isn't marked Latest is available through the GitHub Releases page.

The project is distributed under the repository's [AGPL-3.0](../LICENSE). See the [Android third-party notices](THIRD_PARTY_NOTICES.md).
