# Vandi Launch

A minimal Android app that tells you when rockets launch from Vandenberg Space Force Base, and nothing else.

- **Next launch**: name, provider, date and time, live countdown, link to the webcast, link to details.
- **Reminders**: 24 hours, 1 hour and 10 minutes before launch (each can be switched off), plus a notice when a launch within the next two days changes time.
- **1x1 widget**: `FRI-12/2` / `9:04pm` / countdown. The countdown shows days until launch day, hours on launch day, and a ticking `H:MM:SS` for the last five hours.

Launches without a confirmed time show `TBD` and never trigger a reminder.

## Data

Schedule data comes from [Launch Library 2](https://thespacedevs.com/llapi) by The Space Devs: free, no key, limited to 15 requests an hour. The app asks only for Vandenberg (location id 11), refreshes about every two hours, and every 20 minutes in the last six hours before a launch.

## Build

Needs the Android SDK (platform 36) and JDK 17 or newer. No third-party libraries.

```
./gradlew testDebugUnitTest assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

Release builds are signed with the key named in `keystore.properties` (not in the repo):

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the build is signed with the local debug key instead. Android only installs an update over an existing app when both are signed with the same key.

## Install

Download the APK from the [latest release](https://github.com/NinjaKristo/VandiLaunch/releases/latest) and open it on the phone.

## Layout

| File | Role |
| --- | --- |
| `Times.kt`, `Reminders.kt`, `LaunchJson.kt` | Pure logic: formatting, countdown rules, reminder rules, parsing. Unit tested. |
| `Api.kt`, `Store.kt`, `Sync.kt` | Fetch, cache, and scheduling of alarms and the refresh job. |
| `LaunchWidget.kt`, `Notifier.kt`, `MainActivity.kt`, `Background.kt` | Widget, notifications, the one screen, and the receivers. |
