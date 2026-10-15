# BioKey

An Android app lock modelled on Android 17's own App Lock: the apps you choose open only after your face, fingerprint or screen lock, and stay unlocked while you're using them.

[![Platform](https://img.shields.io/badge/Android-12%2B%20(API%2031%E2%80%9337.2)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## How it works

1. **Covering.** An accessibility service watches the screen. When a locked app is about to open (a tap on its icon, a Recents card or a search result) or appears (any other way, including split screen and picture-in-picture), BioKey covers it at once with an opaque, screenshot-proof overlay.
2. **Authenticating.** A lightweight lock screen takes over from the cover and shows the system `BiometricPrompt`: Class 3 face unlock first, fingerprint at the same time, then your PIN, pattern or password. Weak (Class 2) face unlock is never accepted.
3. **Sessions.** An unlocked app stays unlocked for as long as it is on screen, whatever happens inside it (refreshes, dialogs, sign-in sheets, keyboards, chat heads, assistants). It counts as left only once its windows have gone. Then it locks again after its re-lock delay: immediately, after 30 s, 1, 5 or 15 min, or when the screen turns off. Going straight to a helper app (authenticator, email, file picker, camera) and back doesn't end the session. Turning the screen off always locks everything.

## Features

| Feature | What it does |
|---|---|
| **Profiles** | Group apps (Finance, Social, Work, or your own) with their own re-lock delay. Each profile is edited from its own page. An app lives in at most one profile. Optional auto-sort puts recognised apps into the built-in profiles. |
| **Notification shield** | Optional. A locked app's notifications are snoozed and replaced with "N hidden notifications" until you unlock it; tapping the stand-in unlocks and opens the original. Calls, alarms, media and navigation always show. |
| **BioKey locks itself** | BioKey's own screens need authentication and lock again after 3 minutes of inactivity. |
| **Self-protection** | BioKey's App info page (force stop, uninstall, clear storage), its accessibility switch, notification access and the uninstall dialog require authentication. Pages you open from inside BioKey are pre-authorised. |
| **Protection alert** | A notification fires if the accessibility service stops. |
| **Pause** | Pause for 15 minutes, 1 hour, or until turned back on, from the app or the Quick Settings tile (behind authentication). |
| **Window hardening** | `FLAG_SECURE`, no Recents thumbnails of BioKey, other apps' overlays hidden, and touches through obscuring windows rejected (tapjacking). |
| **No network access** | No `INTERNET` permission. Backup and device-to-device transfer are disabled. |

## Android 17 App Lock

Android 17 QPR2 (API 37.2) adds a native App Lock (`PackageManager.getEnableAppLockIntentForPackage`, `ApplicationInfo.isAppLockEnabled`). Turning it on for an app needs `LOCK_APPS`, which only the default launcher can hold, so BioKey can't use it directly; this was verified on a device. Where it's available, BioKey's settings link to the system App Lock page.

## Limits

An app lock running as a regular app can't defend against everything:

- **Root, ADB, or a modified OS** can bypass any app lock.
- **Safe mode** disables third-party accessibility services, so locked apps open without a check until you reboot normally. The protection alert tells you the service stopped.
- **Recents previews** of a locked app can't be blanked; only the system App Lock can do that.
- **Launches without a tap** (links from other apps, the quick-switch gesture) are covered when the app's window appears, which can be a frame later than a tap-started launch.
- **Self-protection is heuristic.** There's no Android API for "this screen can disable app X", so BioKey recognises those screens by package and content. It's tested against Pixel layouts; other manufacturers' Settings may differ.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Sees which app is on screen, reads the name of the app you tap on the home screen or in Recents, and recognises BioKey's own settings pages. Doesn't read what you type. |
| `USE_BIOMETRIC` | Face and fingerprint authentication. |
| `HIDE_OVERLAY_WINDOWS` | Hides other apps' overlays while BioKey's screens are showing. |
| `POST_NOTIFICATIONS` | The protection alert and hidden-notification stand-ins. |
| `RECEIVE_BOOT_COMPLETED` | Warns after a reboot if the accessibility service is off. |
| Notification access (optional) | The notification shield. |

Package visibility is limited to launchable apps, launchers, keyboards and a few system settings pages (`<queries>`); BioKey doesn't request `QUERY_ALL_PACKAGES`.

## Architecture

```
core/data          LockTimeoutMode, SecurityProfile, AppClassifier
core/security      SecurityVault (settings, locked apps, profiles), LockStateTracker (sessions),
                   SecurityEngine (BiometricPrompt), WindowProtection, ProtectionAlerts
core/receiver      Boot receiver
feature/lock       BioKeyAccessibilityService, InstantShieldOverlay, LockScreenActivity,
                   LockCoverView, SelfProtectionDetector, AppInfoCache, ScreenReceiver
feature/main       MainActivity (BioKey's own lock), main screen, profile sheets, settings
feature/setup      First-run setup
feature/notification  Notification shield
feature/tile       Quick Settings tile
ui/theme           BioKeyTheme, system haptics, press animation
```

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 37.2; Gradle downloads it if missing).

```bash
./gradlew :app:assembleDebug
```

### Release builds

Release builds use R8 (minify and resource shrinking). `Log.d/i/v` calls are stripped.

To sign a release, copy the example config and point it at your keystore (keep the keystore out of the repo and backed up offline: losing it means you can never update the installed app):

```bash
cp key.properties.example key.properties   # git-ignored
./gradlew :app:assembleRelease
```

Without `key.properties`, the release APK is left **unsigned**. It never silently falls back to the public debug key. For local testing only, you can opt in explicitly:

```bash
./gradlew :app:assembleRelease -Pbiokey.allowDebugSignedRelease=true
```

## License

Apache 2.0. See [LICENSE](LICENSE).
