# Technically Here

A native Android time tracker for work and other activities. Start and stop manually, or automate tracking using a Wi-Fi network, a GPS area, or either signal. Edit recorded hours in a table and export reports as PDF or Markdown.

Everything runs locally: no account, server, WebView, or internet permission. The interface and built-in reports are in English. A fresh installation has no activities or installed addons.

## Features

- One form for activity name, daily break, 15-minute rounding, and trigger settings.
- Manual, Wi-Fi SSID, GPS, and **Wi-Fi or GPS** trigger modes.
- A default GPS radius of 200 metres and a one-hour departure delay.
- Returning during the delay keeps one continuous entry. Confirmed departures are saved at the first detected departure, without adding the waiting hour.
- Departure countdowns persist across service restarts and sleep. In combined mode, a known Wi-Fi disconnection can confirm departure after the delay even when GPS is unavailable; a current GPS fix inside the area keeps tracking active.
- An editable monthly table with activity, dates, times, breaks, hours, and notes.
- PDF and Markdown exports with selectable fields and activities.
- Custom weekly report templates, optional weekly shifts, and a drawn signature through declarative addons.
- Activity deletion that preserves historical hours and their calculation settings.
- JSON backup and restore.
- Background tracking, restart recovery, and possible-interruption indicators.

This repository contains the base app and synthetic test fixtures. It does not include private addons, employer forms, or user data. Addons are data files and cannot execute code.

## Quick start

1. Add an activity from **Today** or **Activities**.
2. Set its break, rounding, and trigger in the same form, then save.
3. For automation, open **Settings → Phone automation → Start background tracking**.
4. Review permissions and battery settings under **Reliable tracking**.
5. Check or edit entries under **Hours**, then choose **Export**.

See [MANUAL.md](MANUAL.md) for the short user guide and [ADDONS.md](app/src/main/appassets/ADDONS.md) for the addon format.

## Background operation

Tracking uses a location foreground service with a silent system notification. Allow precise location, background location, and unrestricted battery use. On Samsung phones, add the app to **Never sleeping apps** and disable automatic permission removal for unused apps.

When tracking was enabled, it can resume after a restart and first unlock, and after an app update, subject to Android permissions and restrictions. A force stop interrupts tracking until the app is opened again. Weeks of uninterrupted operation cannot be guaranteed: device policies, battery modes, and signal availability matter. The app flags possible longer interruptions for review instead of inventing missing arrival or departure times.

## Development

- Android 8.0+ (minSdk 26), compileSdk/targetSdk 35.
- Java 17, Android Views, Gradle Wrapper 8.11.1.
- Android SDK Platform 35 and Build Tools 35.0.0.

Open the project in Android Studio, or set `ANDROID_HOME` and use:

```sh
# Unit tests only; does not build an installable APK
./gradlew :app:testDebugUnitTest

# Static checks
./gradlew :app:lintDebug

# Build an APK only when needed
./gradlew :app:assembleDebug
```

Debug builds use a local development signing key. Configure your own release signing for distribution; never commit private signing keys. An alternative SDK-only build script is available at `tools/build-local.sh`.

Tests cover time calculations, trigger combinations, overlap protection, exports and pagination, native forms, settings persistence, deletion with retained history, and tracking recovery. The instrumented PDF test requires an emulator or device. See [VERIFICATION.md](VERIFICATION.md) for the current verification status.

## License

[MIT](LICENSE), copyright © 2026 slavomrkva. Dependencies and the Gradle Wrapper retain their own licenses.
