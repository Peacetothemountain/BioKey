# BioKey

![BioKey](docs/assets/feature-graphic.png)

An app lock for Android, modelled on Android 17's own App Lock: the apps you choose open only after your face, fingerprint or screen lock, and stay unlocked while you're using them. No internet access, no data collected.

**[Get it on Google Play](https://play.google.com/store/apps/details?id=com.nbdesigns.biokey)** · [Privacy policy](https://peacetothemountain.github.io/BioKey/privacy/) · [Support](https://peacetothemountain.github.io/BioKey/support/)

[![Platform](https://img.shields.io/badge/Android-12%2B%20(API%2031%E2%80%9337.2)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

<p align="center">
  <img src="docs/assets/screen-home.png" width="200" alt="BioKey's list of locked apps">
  <img src="docs/assets/screen-cover.png" width="200" alt="A locked app covered as it opens">
  <img src="docs/assets/screen-profile.png" width="200" alt="A profile of locked apps">
  <img src="docs/assets/screen-dark.png" width="200" alt="BioKey in dark theme">
</p>

---

## Contents

- [Features](#features)
- [How it works](#how-it-works)
- [Permissions](#permissions)
- [Privacy](#privacy)
- [Limits](#limits)
- [Building from source](#building-from-source)
- [Testing](#testing)
- [Project layout](#project-layout)
- [Support and contact](#support-and-contact)
- [License](#license)

## Features

- Lock the apps you choose behind Class 3 face unlock, fingerprint, or your PIN, pattern or password.
- With the accessibility engine, a locked app is covered the moment you tap it, before it draws anything.
- An unlocked app stays unlocked while you use it, and locks again when you leave: at once, after a delay you choose, or when the screen turns off.
- Profiles (Finance, Social, Work, or your own), each with its own re-lock delay and on/off switch.
- Optional notification shield: a locked app's notifications are hidden until you unlock it.
- BioKey locks itself. Builds from source can also guard its own settings and uninstall (not the Google Play version).
- Works under Android's Advanced Protection, through compatibility mode.
- Pause for 15 minutes, an hour, or until you turn it back on, from the app or a Quick Settings tile.
- Optionally, face or fingerprint only: your PIN no longer opens your locked apps or BioKey. If someone adds their own fingerprint in Android's settings to get round it, BioKey tells you.
- Lock every app at once (before handing your phone over), from the app, a Quick Settings tile, or by long-pressing BioKey's icon.
- See which locked apps are unlocked right now, and when each locks again.
- Optional failed unlock alerts, and a record of any time app locking was switched off, so you know if someone tried.
- An offer to lock each app you install that auto-sort didn't.
- Password-protected backup and restore of your setup.
- No `INTERNET` permission. Nothing leaves the phone unless you save or share it yourself.

Requires Android 12 or later.

## How it works

1. **Covering.** BioKey watches which app is opening, with one of two engines (see [Two engines](#two-engines)). With the accessibility engine, when a locked app is about to open (a tap on its icon, a Recents card or a search result) or appears (any other way, including split screen and picture-in-picture), BioKey covers it at once with an opaque, screenshot-proof overlay.
2. **Authenticating.** A lightweight lock screen takes over from the cover and shows the system `BiometricPrompt`: Class 3 face unlock first, fingerprint at the same time, then your PIN, pattern or password. Weak (Class 2) face unlock is never accepted.
3. **Sessions.** An unlocked app stays unlocked for as long as it is on screen, whatever happens inside it (refreshes, dialogs, sign-in sheets, keyboards, chat heads, assistants). It counts as left only once its windows have gone. Then it locks again after its re-lock delay: immediately, after 30 s, 1, 5 or 15 min, or when the screen turns off. Going straight to a helper app (authenticator, email, the photo picker or a gallery, a file picker, the camera, Settings, a Play Store purchase, review or update page), a screen the app opened (permission request, share sheet), or an interruption (a call, an alarm) and back within 5 minutes doesn't end the session, even through several of them in a row; BioKey asks Android which apps on the phone pick or capture photos and files. Locking the phone always locks everything: the screen turning off, a screensaver starting, or Lockdown from the power menu.

### Features in detail

| Feature | What it does |
|---|---|
| **Profiles** | Group apps (Finance, Social, Work, or your own) with their own re-lock delay and their own on/off switch. An app is locked when its own switch is on and its profile is on; switching either off never takes it out of the profile. Each profile is edited from its own page. An app lives in at most one profile. Optional auto-sort puts recognised apps into the built-in profiles. |
| **Notification shield** | Optional. A locked app's notifications are snoozed and replaced with "N hidden notifications" until you unlock it; tapping the stand-in unlocks and opens the original. Tapped on the phone's lock screen, a stand-in asks you to unlock the phone first. Calls, alarms, media and navigation always show. |
| **BioKey locks itself** | BioKey's own screens need authentication, and lock again as soon as you leave them (except for the system settings pages and the backup file picker BioKey opens itself, and a locked app's lock screen BioKey brings up over them, until it closes) or after 3 minutes of inactivity. |
| **Self-protection** | Not in the Google Play version (see [Self-protection builds](#self-protection-builds)). Optional (off by default), accessibility engine only. BioKey's App info page (force stop, uninstall, clear storage), its accessibility switch, notification access and the uninstall dialog require authentication. Pages you open from inside BioKey are pre-authorised. |
| **Protection alert** | A notification fires if protection stops (the accessibility service is switched off, or compatibility mode loses a permission or can't start). |
| **Pause** | Pause for 15 minutes, 1 hour, or until turned back on, from the app or the Quick Settings tile (behind authentication). |
| **Face or fingerprint only** | Optional, where a strong (Class 3) face or fingerprint is set up. Locked apps and BioKey stop accepting the screen lock (Android's own App Lock always accepts it). Turning it on or off asks for your face or fingerprint, and home suggests locking Android's Settings too (put away with "Not now", it comes back the next time you turn the option on; Settings keeps the suggestion meanwhile). Android lets anyone with the PIN add a fingerprint in Settings; BioKey can't stop that, but notices it (a Keystore key Android destroys when biometrics change) and tells you. If no face or fingerprint is set up any more, or the sensor can't be used (a fault, an update pending), the screen lock opens locked apps again (and that's recorded), so you're never locked out; changes that need your face or fingerprint (clearing records, restoring a backup, switching protections off) wait until it works, or until you remove it in Android's settings. |
| **Lock all now** | Ends every locked app's session at once, without waiting for the screen to turn off: from the home screen and the status sheet, the "Lock all" Quick Settings tile, or the launcher shortcut (long-press BioKey's icon). It only ever locks, so it needs no authentication; while paused it resumes protection, and while nothing protects it says so instead. |
| **Unlocked now** | Home shows the locked apps unlocked right now, each with when it locks again ("Re-locks in 4 min"), to lock one or all of them. Rows state a re-lock delay only where it differs from the default, which the "Locked" header states once. |
| **Failed unlock alerts** | Off until you turn them on (BioKey asks once, where a face or fingerprint is set up). A face or fingerprint that didn't match on a locked app is noted as it happens. A match on the same prompt shows those misses were yours, and they go; misses followed by a later unlock stay, marked with how the app was then opened. Misses no face or fingerprint followed are notified the next time you unlock your phone, at least 2 minutes on, never naming the app and never on the lock screen; home lists them all until reviewed and cleared; a swiped notification comes back at the next unlock. Changing the phone's date can't hide or delete them. Clearing them, switching the alerts off, deleting the log, or restoring any backup, needs your face or fingerprint (the screen lock where none is set up). Opened with the PIN, Review doesn't count as seen: "Mark reviewed" asks for a face or fingerprint. Kept 7 days after you've seen them. If BioKey's notifications are muted in Android's settings, home says so. Optional: also note each locked app opened with the screen lock, for owners who never use their PIN. Android doesn't tell apps about a wrong PIN. |
| **App locking was off** | If protection is switched off, or BioKey is force-stopped (as Android itself records it), home shows when, from and to, and you're told the next time you unlock your phone (while it's still off, the alert comes back, quietly, at every unlock and about every 15 minutes; if you switched it off on purpose, "Stop reminding" on home ends that, and "Remind me" brings it back). Also recorded: a face or fingerprint added or removed on the phone (these can't be cleared, only aged out, and stay new for a day; meanwhile nothing proves it's you in BioKey, so changes are recorded and nothing can be marked seen, cleared, silenced or restored), notification access switched off while the notification shield was on, until it's back or hiding is switched off (home also says so meanwhile), a face or fingerprint that couldn't be used under "Face or fingerprint only", and, on a phone with one, each way protection is weakened from BioKey opened with the screen lock and not confirmed with a face or fingerprint: a pause, an app's lock switched off, a locked app moved or added into a profile that's off or re-locks later, or taken out of one so it's locked less, a profile switched off or deleted, a longer re-lock delay, the notification shield off, and (within a day of a face or fingerprint change) Face or fingerprint only off and the reminders stopped. The diagnostic log opened for sharing from such a session is recorded too, though it changes nothing. (Anything else that asks for a face or fingerprint, a restore included, can't be done with the screen lock at all.) BioKey's storage cleared in Android's settings, which takes every lock and record with it, is told at every unlock until BioKey is set up again, and then recorded (when Android still remembers BioKey running before). Android stopping BioKey on its own (low memory, a crash), a restart, an update of BioKey, or a moment's handover between engines isn't counted (some phone makers' battery savers force-stop apps the way a person would, and do show), and changing the phone's clock neither shortens nor hides it. The Play version can't prevent this (see [Self-protection builds](#self-protection-builds)); it makes sure you find out. |
| **New apps** | When you install an app auto-sort doesn't lock, a notification offers to lock it, with a Lock button; several at once (a new phone restoring) gather under one. Can be turned off in Settings. |
| **Backup and restore** | Your locked apps, profiles and settings, saved to a file you choose, sealed with a password (AES-256-GCM, key from PBKDF2-SHA256). Restore after a reinstall or on a new phone; a restore always asks for your face or fingerprint, and is refused within a day of one being added or removed. |
| **Diagnostic log** | On by default, can be turned off. BioKey's own lock decisions about your locked apps, on the phone, for 3 days and at most 500 entries (after a restart with no network time, the last 500), to help track down a problem. You can share it from Settings, with your face or fingerprint (your screen lock if it can't be used). |
| **Window hardening** | `FLAG_SECURE`, no Recents thumbnails of BioKey, other apps' overlays hidden, touches through obscuring windows rejected (tapjacking), and BioKey's screens marked accessibility-data-sensitive (Android 14 and later), so only genuine accessibility tools (e.g. TalkBack) can read or operate them there: accessibility malware can't see your locked apps or tap "Pause". |
| **No network access** | No `INTERNET` permission. Android's cloud backup and device-to-device transfer are disabled for BioKey's data. |

### Two engines

| | Accessibility engine (default) | Compatibility mode |
|---|---|---|
| **Used when** | The accessibility service is on | Accessibility is unavailable, e.g. Android 17's Advanced Protection, which reserves accessibility for genuine accessibility tools |
| **Sees** | Which app is on screen and its windows' titles; the label of what you tap on the home screen, in its search or in Recents; app names on Recents cards (while Recents is open and a locked app is unlocked); with self-protection on (not in the Play version), BioKey's own Settings pages | Which app was just opened, and whether a locked app is still on screen (usage access) |
| **Covers locked apps** | From the tap, before the app has a window | As the app opens, with an overlay: it checks every 0.15 s, so a cold launch is covered as it opens, while a warm one can show for about half a second first (see [Limits](#limits)) |
| **Self-protection** | Yes (optional; not in the Play version) | No |
| **Extra** | | A low-priority notification while running (required by Android) |

BioKey switches between them by itself, including the moment Advanced Protection is turned on or off.
It never claims to be an accessibility tool: Google Play rejects that and Play Protect blocks it.

### Android 17 App Lock

Android 17 QPR2 (API 37.2) adds a native App Lock (`PackageManager.getEnableAppLockIntentForPackage`, `ApplicationInfo.isAppLockEnabled`). Turning it on for an app needs `LOCK_APPS`, which only the default launcher can hold, so BioKey can't use it directly; this was verified on a device.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Sees which app is on screen; reads the label of what you tap on the home screen, in its search or in Recents, and, while Recents is open and a locked app is unlocked, the app names on its cards; the title of each new window (to recognise Google Password Manager's screens); with self-protection on (not in the Play version), recognises BioKey's own settings pages. Doesn't read what you type. |
| `USE_BIOMETRIC` | Face and fingerprint authentication, through the system prompt. |
| `HIDE_OVERLAY_WINDOWS` | Hides other apps' overlays while BioKey's screens are showing. |
| `POST_NOTIFICATIONS` | The protection alert, security activity (never naming the app, never on the lock screen), offers to lock new apps, and hidden-notification stand-ins. |
| `RECEIVE_BOOT_COMPLETED` | After a reboot, starts compatibility mode if it's in use, or warns if protection is off. |
| `QUERY_ADVANCED_PROTECTION_MODE` | Detects Android's Advanced Protection, so BioKey switches to compatibility mode. |
| Usage access (`PACKAGE_USAGE_STATS`), display over other apps (`SYSTEM_ALERT_WINDOW`), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Compatibility mode only: which app was opened and whether a locked app is still on screen, covering it, and running while the screen is on. |
| Notification access (optional) | The notification shield. |

Package visibility is limited to launchable apps, launchers, keyboards, apps that pick or capture photos and files (galleries, file managers, cameras), Google Play services and Credential Manager, the Settings app and the settings pages BioKey opens (`<queries>`); BioKey doesn't request `QUERY_ALL_PACKAGES`.

BioKey is built to stay within Google Play's rules, so Play Protect and Advanced Protection have nothing to block:

- **Honest accessibility declaration** (`isAccessibilityTool="false"`), and compatibility mode for when Android reserves accessibility for real accessibility tools.
- **Prominent disclosure and consent** in the app before every sensitive permission (accessibility, usage access + overlay, notification access). Only **Agree** continues; Back or dismissing is not consent.
- **No blocking of uninstall or disable**: the Google Play version has no self-protection, since Play's Accessibility API policy allows that only to parental-control and enterprise apps. Builds from source can include it, opt-in.
- **Minimum permissions**: no `INTERNET`, no `QUERY_ALL_PACKAGES`, no battery-optimisation exemption, no `VIBRATE`.

Installs from Google Play are exempt from Android's restricted settings and from Play Protect's fraud protection for sideloaded apps that request accessibility or notification access. A build installed from a file has to be allowed restricted settings first (see [Support](https://peacetothemountain.github.io/BioKey/support/)).

## Privacy

BioKey collects no data. It has no internet access, no analytics, no ads and no third-party services. What it keeps (your locked apps, profiles and settings, a short log of its own lock decisions, and security activity: the times app locking was off, faces or fingerprints added or removed, notification access switched off under the shield, a face or fingerprint that couldn't be used, changes made from BioKey opened with the screen lock or within a day of a face or fingerprint change, reminders stopped within that day, the diagnostic log opened for sharing from such a session, and, if you turn them on, failed unlocks; each kept until you've seen it (a face or fingerprint change at least a day) and then 7 days) stays in its private storage on the phone, and is excluded from cloud backup and device transfer. Nothing leaves the phone unless you save a backup file or share the diagnostic log yourself.

Full policy: [peacetothemountain.github.io/BioKey/privacy](https://peacetothemountain.github.io/BioKey/privacy/) (source: [PRIVACY.md](PRIVACY.md)).

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
- **Launches without a tap** (links from other apps, notifications, the quick-switch gesture) are covered when Android reports the app's window, not at the tap. BioKey covers within about 30 to 50 ms of hearing of it, but on a cold start Android can report it late: measured at up to about half a second on an emulator (the engine trace shows when the event was made and when it arrived). A tap on an app's icon, its Recents card or a search result is covered at the tap.
- **Apps that hide their windows from accessibility services** (Android 14 and later; many banking apps do): the accessibility engine covers them when they're opened by a tap on their icon or Recents card, or when a window of theirs it has already seen comes back. Opened another way (a link, a notification), they can open uncovered.
- **Picture-in-picture**: with the accessibility engine, a locked app's picture-in-picture window is covered by a patch of its own. The window can show for a moment right after the phone is unlocked or while it's being moved, and an app that won't leave picture-in-picture shows dimmed under the unlock prompt. Compatibility mode can't see where that window is, or close it: a locked app left in picture-in-picture when the phone locks (or when every session ends, protection resumes or compatibility mode starts) is covered about a second after the phone is unlocked, by BioKey opening it full screen where it can, and otherwise by a cover over the whole screen, which stays until you unlock the app. Over another locked app, BioKey itself or a screen the app in front opened (a sign-in tab, say), beside an unlocked app or BioKey in split screen, or while another app's unlock prompt is up, BioKey doesn't open it (that would count as leaving the apps on screen): the cover goes up and its prompt follows, once any other prompt is done, and the apps on screen (BioKey included) stay unlocked. An app's own lock screen coming up doesn't count as leaving it either, so a playing video doesn't go into picture-in-picture over it.
- **Split screen, desktop windows and other displays** (DeX, an external screen): a locked app's lock screen takes the whole display (desktop windows, Android 14 and later) or sends you Home, so the app is never usable beside the prompt. Windows of locked apps beside the lock screen, or on another display, are covered by patches.
- **Work profile and other profiles**: compatibility mode can't see apps in a work profile or another profile, including the work copy of an app installed in both, because Android only shares app-launch information within one profile. They stay locked with the accessibility engine, and the notification shield still hides their notifications. While you're in a work app, compatibility mode counts you as still in the last personal app; locking the phone still locks everything.
- **Notifications** of a locked app can flash as a heads-up or on a watch before BioKey hides them. With Notification history on, Settings > Notifications > Notification history lists the snoozed (hidden) notifications.
- **Bringing hidden notifications back**: switch the notification shield off in BioKey. Revoking notification access instead can leave them hidden for up to a day.
- **Android 12 and 13** have no way to hide an app's screens from other accessibility services: one you have turned on can read and operate BioKey's screens.
- **Accessibility shortcuts**: a shortcut (volume keys or the accessibility button) assigned to BioKey App Lock Service switches it off without authentication. Remove it if that matters to you, Android Settings locked or not; BioKey's settings warn you while one is assigned and the service is on. Either way, BioKey shows afterwards when app locking was off.
- **Self-protection is heuristic.** There's no Android API for "this screen can disable app X", so BioKey recognises those screens by package and content. It's tested against Pixel layouts; other manufacturers' Settings may differ.
- **Clearing BioKey's storage** (App info > Storage) deletes everything it keeps, the record of failed unlocks and of times app locking was off included. The Play version can't stop that, but it also undoes BioKey's setup and switches its service off, which the owner can't miss.
- **A restart into safe mode** turns off every downloaded app, BioKey included, so BioKey can't record it (see Safe mode above); a restart with BioKey switched off is recorded once it's back on.
- **Home-screen widgets** of a locked app (a mail or photo widget, say) show their content without a check: a widget isn't the app opening. Remove them from the home screen if that matters.
- **Someone who knows your screen lock** can add their own face or fingerprint in Android's settings, which "Face or fingerprint only" then accepts. BioKey can't prevent it (lock Android's Settings to make it harder; BioKey suggests it while face or fingerprint only is on) but records it, and the record can't be cleared.
- **After a force stop,** Android also stops BioKey's reminders and clears its notifications until BioKey runs again: you find out when you next open BioKey, which then shows when it was stopped.

### Deliberate choices

- **No intruder photos.** Some app locks take a picture of whoever fails to unlock. That needs the camera permission and photographs people who never agreed to it; BioKey records which app and when, and nothing about who.
- **English only.** BioKey isn't translated. What Android shows outside BioKey (notifications, tiles, the launcher shortcut, the accessibility description) is in `strings.xml`, with plurals, ready for translation.
- **No separate BioKey PIN.** Some app locks add their own PIN as a fallback. BioKey uses the phone's own face, fingerprint and screen lock, kept in Android's secure hardware: one more secret to remember would be one more to guess, shoulder-surf or forget. Face or fingerprint only plus locking Settings covers the person who knows your PIN.
- **The diagnostic log is on by default.** With no network access, a user's log is the only way to trace a missed or unexpected lock. It stays on the phone, keeps 3 days (see Diagnostic log above), names only locked apps, and leaves only when the user shares it.

## Building from source

Requirements: JDK 17 and the Android SDK (compileSdk 37.2; Gradle downloads it if missing). The unit tests run on Java 21, which Gradle downloads if this computer doesn't have it.

The installed app ID is `com.nbdesigns.biokey` (`applicationId`); the Kotlin code lives in the package `com.android.BioKey` (`namespace`). Debug and release builds use the same app ID.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Android 13 and later, Android can block an APK installed from a file from turning on accessibility and notification access until you allow restricted settings: try the accessibility switch once, then in BioKey's App info tap ⋮ > **Allow restricted settings**. BioKey's setup shows this step when it applies. Installs from Google Play aren't affected.

### Release builds

Release builds use R8 (minify and resource shrinking). `Log.v/d/i/w` calls are stripped, since their messages can name locked apps; `Log.e` stays for diagnosis, so its messages must not name an app. Keep `app/build/outputs/mapping/release/mapping.txt` from every release you publish: it's the only way to read that build's stack traces.

Lint errors fail `:app:lintDebug`, which CI runs; warnings don't. The quick check inside `assembleRelease` fails the release build on the most serious ones: API calls newer than Android 12 without a version check, and components or intents other apps could reach.

### Signing

To sign a release, copy the example config and point it at your keystore (keep the keystore out of the repo and backed up offline: losing it means you can never update the installed app):

```bash
cp key.properties.example key.properties   # git-ignored
./gradlew :app:assembleRelease             # APK
./gradlew :app:bundleRelease               # App Bundle, for Google Play
```

A relative `storeFile` path is resolved from the project root. Without `key.properties`, the release APK is left **unsigned**. It never silently falls back to the public debug key. For local testing only, you can opt in explicitly:

```bash
./gradlew :app:assembleRelease -Pbiokey.allowDebugSignedRelease=true
```

Its version name ends in `-debugsigned`, so it can't be mistaken for a real release.

### Self-protection builds

Release builds, including the one on Google Play, leave self-protection out: its switch isn't shown, and a saved or restored "on" reads as off. Google Play's Accessibility API policy doesn't allow an app to stop users disabling or uninstalling it, outside parental-control and enterprise apps. Debug builds always have it, so its tests run. For a release you install yourself:

```bash
./gradlew :app:assembleRelease -Pbiokey.selfProtection=true
```

Its version name ends in `-selfprotection`. Never upload one to Google Play.

Publishing to Google Play (Play Console declarations, signing, developer verification, the privacy-policy page) is covered in [docs/RELEASING.md](docs/RELEASING.md).

## Testing

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

The unit tests run on the JVM with Robolectric (mostly as Android 14; the lock-screen tests as Android 12 and 17 too). They cover the locking rules (sessions, helper-app hops, profiles, Recents gestures), both engines, the notification shield, self-protection, backup, and the diagnostic log.

- `-Pbiokey.tests=gantlet` runs only the lock-screen gantlet (below); `-Pbiokey.tests=others` runs everything else.
- The design screenshots (`DesignScreenshotTest`, Roborazzi) render every screen for review and are never compared. They're slow, so they run only with `-Pbiokey.screenshots=true`; the PNGs go to `app/build/screenshots/`.

### The lock-screen gantlet

The gantlet is the set of unit tests that must pass before anything reaches `main`: BioKey's lock screen, once up, stays up until you unlock or leave it, on every screen size (phones, foldables' cover and main screens, tablets), through every way of leaving or covering it, with both engines. The list lives in one place, `testOptions` in `app/build.gradle.kts`; the build fails if an entry there matches no test, so a renamed class can't quietly drop out.

```bash
sh tools/gantlet_tests.sh        # reports in app/build/reports/tests/gantlet
```

CI runs it first, then the rest. A pre-push hook runs it before any push to `main`, and refuses the push if it fails or if the checkout differs from what's being pushed. Turn the hook on once per clone:

```bash
git config core.hooksPath .githooks
```

### Device gantlet

`tools/device_gantlet.py` drives a phone or emulator with BioKey installed over adb, and checks that a locked app, opened every way people open apps, comes up under BioKey's lock screen and stays there, without being sent Home. It never authenticates and never changes BioKey's settings: the apps passed with `--locked` must already be locked in BioKey.

```bash
python tools/device_gantlet.py --locked com.twitter.android=X --locked com.google.android.apps.messaging=Messages
```

Useful options: `--only <name>` (run matching scenarios), `--serial` (pick a device), `--drawer` (also open apps from the app drawer), `--watch-exposure 220,0,120` (fail if the gantlet targets' colour shows uncovered), and on emulators `--fingerprint` and `--hinge`. Exit status 0 when every scenario passes. `--help` lists them all.

**`--emulate-foldables` is for test phones and emulators only.** It resizes the screen (`wm size`, `wm density`) to foldables' cover and main screens. A main-screen size makes Pixel Launcher (and other Launcher3 home screens) lay the home screen out on its tablet grid, and it deletes whatever doesn't fit from the phone's own layout. Restoring the size doesn't bring those back.

The gantlet's own target apps (`tools/gantlet-targets`: Gantlet Plain, Splash, Late, Chain, NewTask, Permission and Loop, each opening in a different way) build with:

```bash
./gradlew -p tools/gantlet-targets assembleDebug
```

### Emulator gantlet

`tools/emulator_gantlet.py` runs the device gantlet on foldable emulators (Galaxy Z Fold7 and Flip7, OPPO Find N5, vivo X Fold5, Pixel 9 and 10 Pro Fold screen sizes and hinges), on Android 16 and 17, with both engines. Nothing touches a real phone: every adb command names its emulator. For each profile it creates an AVD, boots it clean, sets a PIN and enrolls a fingerprint, installs BioKey's debug build and the target apps, sets BioKey up and locks the targets, then runs the gantlet under each engine. Logs and a report go to `tools/out/`.

```bash
./gradlew :app:assembleDebug
./gradlew -p tools/gantlet-targets assembleDebug
python tools/emulator_gantlet.py                                    # every profile, Android 16 and 17
python tools/emulator_gantlet.py --profiles fold7 --android 17 --parallel 1
```

The profiles are Google's Android images with each phone's screen sizes and hinge, not One UI, ColorOS or OriginOS, which have no emulator images. Behaviour their own software adds is covered by the unit tests (`app/src/test/.../Gantlet*.kt`). Each emulator takes about 3 GB of memory.

### Latency

The device gantlet also reports how fast each cover goes up: per engine and scenario, the milliseconds from the launch (the tap on its icon, or its window opening; in compatibility mode, its resume) until the cover is drawn and until the prompt is asked for, as count, median, 90th percentile and slowest. The debug build logs those moments to logcat under `BioKeyLatency` (`core/diagnostics/LatencyMarks.kt`), on the phone's uptime clock; release builds never do, since the lines name locked apps.

`tools/gantlet-latency-baseline.json` holds a baseline from a Pixel 11 Pro XL on Android 17. Numbers only mean something on the phone they were measured on. To record a new one, run the gantlet with `--record-latency-baseline` on that phone (every scenario must pass) and commit the file. `--latency-budget` then fails a run whose median for any engine, scenario and mark is more than 20% and at least 16 ms slower than the baseline's (`--latency-budget 30,25` for another budget).

### Engine traces

The debug build records what each protection engine is told and what it decides (`core/diagnostics/EngineTrace.kt`): window events and window lists, usage events, the screen and keyguard, the lock screen and its prompt, unlocks and cancels; then every cover, lock screen started and trip Home. A problem seen on a phone can then be replayed on the JVM, without the phone. Release builds record nothing, since the trace names locked apps.

1. Reproduce the problem on the phone with the debug build.
2. Copy the trace off it: `adb shell run-as com.nbdesigns.biokey cat files/engine-trace.txt > app/src/test/resources/traces/<name>.trace`.
3. `EngineReplayTest` replays every trace there into the real engine and checks it decides what the trace says it did. To turn a bug into a test, edit the trace's decisions (its `o.*` lines) to what should have happened: the test fails until the engine does that.

A replay sets up the phone from the trace (locked apps, their re-lock delays and names, home screens, helper apps, keyboards) and plays every input at the time it came. It doesn't replay BioKey's own lock screen (only what the engines were told of it) or the notification shield.

### Baseline profile

The baseline profile (code Android compiles ahead of time, so the first cover after an install isn't drawn by interpreted code) is generated by `baselineprofile/` (`BaselineProfileGenerator`): BioKey starting to its main screen, and the lock path, a locked app opened, covered, its lock screen up and cancelled. It needs a device or emulator on Android 13 or later prepared as the emulator gantlet prepares one (BioKey's debug build installed and set up, its accessibility engine on, `com.biokey.gantlet.plain` locked; the steps are in the generator's KDoc), so it isn't part of CI:

```bash
./gradlew :app:generateBaselineProfile
```

It writes `app/src/release/generated/baselineProfiles/` (the baseline and startup profiles), which AGP merges with the hand-written `app/src/main/baseline-prof.txt`; check the generated files in.

### CI

`.github/workflows/ci.yml` runs on every pull request and every push to `main`: the lock-screen gantlet first, a check that the gantlet tools still run and their target apps build, then the rest of the unit tests, the design screenshots, lint, a debug build and a release build shrunk by R8 like a real one (signed with the runner's throwaway debug key). Test reports, screenshots and the R8 mapping are kept for 14 days. Changes to Markdown files and `LICENSE` alone run nothing.

## Project layout

```
app/src/main/java/com/android/BioKey/
core/data          LockTimeoutMode, SecurityProfile, AppClassifier, PasswordManager
core/security      SecurityVault (settings, locked apps, profiles), LockStateTracker (sessions),
                   SecurityEngine (BiometricPrompt), ProtectionEngine (which engine runs),
                   AdvancedProtection, WindowProtection, ProtectionAlerts, VaultBackup
core/diagnostics   DiagnosticLog (on-phone log of lock decisions), EngineTrace (debug builds:
                   what the engines saw and did), LatencyMarks (debug builds: the gantlet's
                   latency marks)
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
ui/brand           KeyMark (the launcher icon's key, in vector), BioKeyLogo and WordmarkView (it, still),
                   BioKeyMarkSideways (the home header's, swirling), header constellation
ui/theme           BioKeyTheme, components, messages, system haptics, press animation

app/src/test/      Unit tests, the gantlet (Gantlet*.kt and the list in app/build.gradle.kts),
                   engine replays (resources/traces/)
baselineprofile/   Baseline profile generator
tools/             gantlet_tests.sh, device_gantlet.py, emulator_gantlet.py, gantlet-targets/,
                   gantlet-latency-baseline.json
docs/              The GitHub Pages site (privacy policy, support), RELEASING.md
.githooks/         pre-push: the gantlet before any push to main
```

## Support and contact

- Help with setup and common problems: [Support](https://peacetothemountain.github.io/BioKey/support/).
- Bugs and feature requests: [GitHub issues](https://github.com/Peacetothemountain/BioKey/issues).
- Security problems: report them privately, as described in [SECURITY.md](SECURITY.md). Please don't open a public issue.
- Email: support.nbdesigns@gmail.com

Pull requests are welcome. Run the unit tests (at least `sh tools/gantlet_tests.sh`) before opening one. Changes are listed in [CHANGELOG.md](CHANGELOG.md).

## License

Apache 2.0. See [LICENSE](LICENSE).

BioKey is developed by NB Designs.
