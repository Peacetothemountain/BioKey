# BioKey

An Android app lock modelled on Android 17's own App Lock: the apps you choose open only after your face, fingerprint or screen lock, and stay unlocked while you're using them.

[![Platform](https://img.shields.io/badge/Android-12%2B%20(API%2031%E2%80%9337.2)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## How it works

1. **Covering.** BioKey watches which app is opening, with one of two engines (see [Two engines](#two-engines)). With the accessibility engine, when a locked app is about to open (a tap on its icon, a Recents card or a search result) or appears (any other way, including split screen and picture-in-picture), BioKey covers it at once with an opaque, screenshot-proof overlay.
2. **Authenticating.** A lightweight lock screen takes over from the cover and shows the system `BiometricPrompt`: Class 3 face unlock first, fingerprint at the same time, then your PIN, pattern or password. Weak (Class 2) face unlock is never accepted.
3. **Sessions.** An unlocked app stays unlocked for as long as it is on screen, whatever happens inside it (refreshes, dialogs, sign-in sheets, keyboards, chat heads, assistants). It counts as left only once its windows have gone. Then it locks again after its re-lock delay: immediately, after 30 s, 1, 5 or 15 min, or when the screen turns off. Going straight to a helper app (authenticator, email, the photo picker or a gallery, a file picker, the camera, Settings, a Play Store purchase, review or update page), a screen the app opened (permission request, share sheet), or an interruption (a call, an alarm) and back within 5 minutes doesn't end the session, even through several of them in a row; BioKey asks Android which apps on the phone pick or capture photos and files. Locking the phone always locks everything: the screen turning off, a screensaver starting, or Lockdown from the power menu.

## Features

| Feature | What it does |
|---|---|
| **Profiles** | Group apps (Finance, Social, Work, or your own) with their own re-lock delay and their own on/off switch. An app is locked when its own switch is on and its profile is on; switching either off never takes it out of the profile. Each profile is edited from its own page. An app lives in at most one profile. Optional auto-sort puts recognised apps into the built-in profiles. |
| **Notification shield** | Optional. A locked app's notifications are snoozed and replaced with "N hidden notifications" until you unlock it; tapping the stand-in unlocks and opens the original. Tapped on the phone's lock screen, a stand-in asks you to unlock the phone first. Calls, alarms, media and navigation always show. |
| **BioKey locks itself** | BioKey's own screens need authentication, and lock again as soon as you leave them (except for the system settings pages and the backup file picker BioKey opens itself) or after 3 minutes of inactivity. |
| **Self-protection** | Optional (off by default), accessibility engine only. BioKey's App info page (force stop, uninstall, clear storage), its accessibility switch, notification access and the uninstall dialog require authentication. Pages you open from inside BioKey are pre-authorised. |
| **Protection alert** | A notification fires if protection stops (the accessibility service is switched off, or compatibility mode loses a permission or can't start). |
| **Pause** | Pause for 15 minutes, 1 hour, or until turned back on, from the app or the Quick Settings tile (behind authentication). |
| **Window hardening** | `FLAG_SECURE`, no Recents thumbnails of BioKey, other apps' overlays hidden, touches through obscuring windows rejected (tapjacking), and BioKey's screens marked accessibility-data-sensitive (Android 14 and later), so only genuine accessibility tools (e.g. TalkBack) can read or operate them there: accessibility malware can't see your locked apps or tap "Pause". |
| **No network access** | No `INTERNET` permission. Backup and device-to-device transfer are disabled. |

## Two engines

| | Accessibility engine (default) | Compatibility mode |
|---|---|---|
| **Used when** | The accessibility service is on | Accessibility is unavailable, e.g. Android 17's Advanced Protection, which reserves accessibility for genuine accessibility tools |
| **Sees** | Which app is on screen; the label of what you tap on the home screen, in its search or in Recents; app names on Recents cards (while Recents is open and a locked app is unlocked); with self-protection on, BioKey's own Settings pages | Which app was just opened, and whether a locked app is still on screen (usage access) |
| **Covers locked apps** | From the tap, before the app has a window | As the app opens, with an overlay: it checks every 0.15 s, so a cold launch is covered as it opens, while a warm one can show for about half a second first (see [Limits](#limits)) |
| **Self-protection** | Yes (optional) | No |
| **Extra** | | A low-priority notification while running (required by Android) |

BioKey switches between them by itself, including the moment Advanced Protection is turned on or off.
It never claims to be an accessibility tool: Google Play rejects that and Play Protect blocks it.

## Google Play and Play Protect

BioKey is built to stay within Google's rules, so Play Protect and Advanced Protection have nothing to block:

- **Honest accessibility declaration** (`isAccessibilityTool="false"`), and compatibility mode for when Android reserves accessibility for real accessibility tools.
- **Prominent disclosure and consent** in the app before every sensitive permission (accessibility, usage access + overlay, notification access). Only **Agree** continues; Back or dismissing is not consent.
- **No blocking of uninstall or disable unless you turn it on**: self-protection is opt-in.
- **Minimum permissions**: no `INTERNET`, no `QUERY_ALL_PACKAGES`, no battery-optimisation exemption, no `VIBRATE`.
- **[Privacy policy](PRIVACY.md)**: BioKey collects no data.

What only the developer account can do:

1. **Distribute through Google Play.** Play Protect's fraud protection blocks *sideloaded* apps that request accessibility or notification access in several countries (Singapore, India, Malaysia and others), and Advanced Protection blocks sideloading altogether. Installs from Play are exempt from both.
2. **In Play Console:** complete the *Accessibility API* declaration (with a short screen recording of the disclosure and app lock), the *special use foreground service* declaration (text matches the manifest), the *Data safety* form (no data collected or shared) and the privacy-policy URL (this repo's `PRIVACY.md`).
3. **Android developer verification:** required for sideloaded and third-party-store installs in Brazil, Indonesia, Singapore and Thailand since 30 September 2026, and worldwide in 2027. Register the package name `com.android.BioKey` and the release signing certificate in the Android Developer Console.

## Android 17 App Lock

Android 17 QPR2 (API 37.2) adds a native App Lock (`PackageManager.getEnableAppLockIntentForPackage`, `ApplicationInfo.isAppLockEnabled`). Turning it on for an app needs `LOCK_APPS`, which only the default launcher can hold, so BioKey can't use it directly; this was verified on a device.

## Limits

An app lock running as a regular app can't defend against everything:

- **Root, ADB, or a modified OS** can bypass any app lock.
- **Compatibility mode** (used under Advanced Protection) covers a locked app a little later than the accessibility engine (no tap prediction; it checks every 0.15 s) and can't provide self-protection. It shows a low-priority notification, which Android requires for this kind of background work.
- **Compatibility mode needs both its permissions.** Turning off usage access or display over other apps stops it: BioKey ends every session and alerts you, but the app on screen at that moment can't be covered.
- **Safe mode** turns off every downloaded app, BioKey included, until you restart normally: preinstalled locked apps (Photos, Gmail or Chrome, for example) open without a check, and no alert can be shown.
- **Recents previews** of a locked app can't be blanked; only the system App Lock can do that.
- **Home screens** can't be locked: BioKey never covers the home screen in use or the phone's own (which also shows Recents), so you can always get Home. Their lock switches can't be turned on.
- **Closing an app from Recents** ends its session only with the accessibility engine (on launchers built on Android's own, like the Pixel's). Compatibility mode can't see it: Android doesn't tell apps when another app is closed, so the session ends by time.
- **Compatibility mode on warm launches**: an app that was still in memory can show its last screen for about half a second before the cover. Android holds back app overlays while an app's opening animation runs; the accessibility engine's cover isn't held.
- **Apps that hide other apps' overlays** (some banking and payment apps): in compatibility mode Android hides BioKey's cover over them, so their screen can show for a moment on each launch until the lock screen appears; the lock itself still applies.
- **Launches without a tap** (links from other apps, the quick-switch gesture) are covered when the app's window appears, which can be a frame later than a tap-started launch.
- **Apps that hide their windows from accessibility services** (Android 14 and later; many banking apps do): the accessibility engine covers them when they're opened by a tap on their icon or Recents card, or when a window of theirs it has already seen comes back. Opened another way (a link, a notification), they can open uncovered.
- **Picture-in-picture**: with the accessibility engine, a locked app's picture-in-picture window is covered by a patch of its own. The window can show for a moment right after the phone is unlocked or while it's being moved, and an app that won't leave picture-in-picture shows dimmed under the unlock prompt. Compatibility mode can't see where that window is, or close it: a locked app left in picture-in-picture when the phone locks (or when every session ends, protection resumes or compatibility mode starts) is covered about a second after the phone is unlocked, by BioKey opening it full screen where it can, and otherwise by a cover over the whole screen, which stays until you unlock the app. Over another locked app, or while another app's unlock prompt is up, BioKey doesn't open it (that would count as leaving the app in front): the cover goes up and its prompt follows, once any other prompt is done, and the app in front stays unlocked.
- **Split screen, desktop windows and other displays** (DeX, an external screen): a locked app's lock screen takes the whole display (desktop windows, Android 14 and later) or sends you Home, so the app is never usable beside the prompt. Windows of locked apps beside the lock screen, or on another display, are covered by patches.
- **Work profile and other profiles**: compatibility mode can't see apps in a work profile or another profile, including the work copy of an app installed in both, because Android only shares app-launch information within one profile. They stay locked with the accessibility engine, and the notification shield still hides their notifications. While you're in a work app, compatibility mode counts you as still in the last personal app; locking the phone still locks everything.
- **Notifications** of a locked app can flash as a heads-up or on a watch before BioKey hides them. With Notification history on, Settings > Notifications > Notification history lists the snoozed (hidden) notifications.
- **Bringing hidden notifications back**: switch the notification shield off in BioKey. Revoking notification access instead can leave them hidden for up to a day.
- **Android 12 and 13** have no way to hide an app's screens from other accessibility services: one you have turned on can read and operate BioKey's screens.
- **Accessibility shortcuts**: a shortcut (volume keys or the accessibility button) assigned to Bio Key App Lock Service switches it off without authentication. Remove it if you rely on self-protection; BioKey's settings warn you while one is assigned.
- **Self-protection is heuristic.** There's no Android API for "this screen can disable app X", so BioKey recognises those screens by package and content. It's tested against Pixel layouts; other manufacturers' Settings may differ.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Sees which app is on screen; reads the label of what you tap on the home screen, in its search or in Recents, and, while Recents is open and a locked app is unlocked, the app names on its cards; with self-protection on, recognises BioKey's own settings pages. Doesn't read what you type. |
| `USE_BIOMETRIC` | Face and fingerprint authentication. |
| `HIDE_OVERLAY_WINDOWS` | Hides other apps' overlays while BioKey's screens are showing. |
| `POST_NOTIFICATIONS` | The protection alert and hidden-notification stand-ins. |
| `RECEIVE_BOOT_COMPLETED` | After a reboot, starts compatibility mode if it's in use, or warns if protection is off. |
| `QUERY_ADVANCED_PROTECTION_MODE` | Detects Android's Advanced Protection, so BioKey switches to compatibility mode. |
| Usage access (`PACKAGE_USAGE_STATS`), display over other apps (`SYSTEM_ALERT_WINDOW`), `FOREGROUND_SERVICE_SPECIAL_USE` | Compatibility mode only: which app was opened and whether a locked app is still on screen, covering it, and running while the screen is on. |
| Notification access (optional) | The notification shield. |

Package visibility is limited to launchable apps, launchers, keyboards, apps that pick or capture photos and files (galleries, file managers, cameras), Google Play services and Credential Manager, the Settings app and the settings pages BioKey opens (`<queries>`); BioKey doesn't request `QUERY_ALL_PACKAGES`.

## Architecture

```
core/data          LockTimeoutMode, SecurityProfile, AppClassifier, PasswordManager
core/security      SecurityVault (settings, locked apps, profiles), LockStateTracker (sessions),
                   SecurityEngine (BiometricPrompt), ProtectionEngine (which engine runs),
                   AdvancedProtection, WindowProtection, ProtectionAlerts, VaultBackup
core/diagnostics   DiagnosticLog (on-phone log of lock decisions)
core/receiver      Boot receiver
feature/lock       BioKeyAccessibilityService, UsageWatchService (compatibility mode),
                   ForegroundRules, SessionRules, LockLauncher, InstantShieldOverlay, WindowMask,
                   LockScreenActivity, LockCoverView, SelfProtectionDetector, RecentsWatcher,
                   AppInfoCache, AppLabels, ScreenReceiver
feature/main       MainActivity (BioKey's own lock), main screen, profile sheets, settings,
                   backup and diagnostic log, disclosures
feature/setup      First-run setup
feature/notification  Notification shield
feature/tile       Quick Settings tile
ui                 AppIcons
ui/brand           BioKeyLogo, LogoArt/LogoLayer, VineSlither, WordmarkView, header constellation
ui/theme           BioKeyTheme, components, messages, system haptics, press animation
```

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 37.2; Gradle downloads it if missing).

```bash
./gradlew :app:assembleDebug
```

### Release builds

Release builds use R8 (minify and resource shrinking). `Log.v/d/i/w` calls are stripped, since their messages can name locked apps; `Log.e` stays for diagnosis, so its messages must not name an app. Keep `app/build/outputs/mapping/release/mapping.txt` from every release you publish: it's the only way to read that build's stack traces.

Lint errors fail `:app:lintDebug`, which CI runs; warnings don't. The quick check inside `assembleRelease` fails the release build on the most serious ones: API calls newer than Android 12 without a version check, and components or intents other apps could reach.

To sign a release, copy the example config and point it at your keystore (keep the keystore out of the repo and backed up offline: losing it means you can never update the installed app):

```bash
cp key.properties.example key.properties   # git-ignored
./gradlew :app:assembleRelease
```

A relative `storeFile` path is resolved from the project root. Without `key.properties`, the release APK is left **unsigned**. It never silently falls back to the public debug key. For local testing only, you can opt in explicitly:

```bash
./gradlew :app:assembleRelease -Pbiokey.allowDebugSignedRelease=true
```

Its version name ends in `-debugsigned`, so it can't be mistaken for a real release.

## License

Apache 2.0. See [LICENSE](LICENSE).
