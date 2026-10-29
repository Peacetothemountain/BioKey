# BioKey privacy policy

_Effective 28 October 2026_

BioKey is an app lock for Android, made by NB Designs. It keeps the apps you choose behind your
face, fingerprint or screen lock. This policy explains what BioKey can see on your phone, what it
does with it, and what it keeps.

It applies to BioKey 1.0 and later, as published on Google Play
(`com.nbdesigns.biokey`) and built from its source code at
<https://github.com/Peacetothemountain/BioKey>.

## The short version

- **BioKey collects no data.** NB Designs receives nothing from the app: no personal
  information, no usage data, no crash reports, no device identifiers.
- **BioKey has no internet access.** It doesn't request Android's `INTERNET` permission, so it
  can't send anything off your phone.
- **No analytics, no ads, no third parties.** BioKey contains no analytics, advertising or
  tracking code, and no third-party services.
- **Everything stays on your phone.** What BioKey keeps (your locked apps, profiles and
  settings, and a short log of its own lock decisions) stays in its private storage, unless you
  choose to save a backup file or share the log yourself.

## What BioKey can see, and why

BioKey asks for each of the special permissions below only after showing you, inside the app,
what it reads and what it is used for. It continues only if you tap **Agree**. Pressing Back or
dismissing the explanation is not consent. You can turn each of them off at any time in Android
settings.

### Accessibility service

BioKey works in one of two ways. The default uses Android's Accessibility API, through a service
called "Bio Key App Lock Service". BioKey is not an accessibility tool, and says so to Android.

**What it reads:**

- Which app is on screen.
- The label of what you tap on the home screen, in its search or in Recents (an app's name, for
  example), so a locked app can be covered as you tap it, before it opens.
- The app names on Recents cards, only while Recents is open and an app you've locked is
  unlocked, so closing that app from Recents locks it again.
- The title Android gives each new window. BioKey uses it only to tell Google Password Manager's
  own screens apart from sign-in sheets that appear over other apps.
- Not in the Google Play version: builds made from BioKey's source code can include
  self-protection (off by default). Turned on, it reads the text of Android's Settings pages about
  BioKey itself (its App info page, its accessibility switch, notification access and the
  uninstall dialog), so BioKey can ask for your authentication before it is switched off or
  uninstalled.

**What it does:** it draws BioKey's cover over a locked app until you authenticate. It can press
Home or Back for you (to leave a locked app you didn't unlock, or a guarded Settings page), lock
the phone's screen (the "Lock screen" button after too many failed attempts), and expand a locked
app's picture-in-picture window so it can be covered.

**What it never does:** it doesn't read what you type, your messages, or the content of the apps
you use, and it doesn't record the screen. What it reads is used only at that moment, on your
phone, to decide whether to cover an app and to protect BioKey's own settings. It is never
collected, never sent anywhere and never shared. The only trace it leaves is the diagnostic log
described below, which names apps you've locked, never what was on screen.

### Usage access and display over other apps (compatibility mode)

When the Accessibility service can't run (for example under Android's Advanced Protection, which
reserves it for accessibility tools), BioKey uses compatibility mode instead.

- **Usage access** tells BioKey which app was just opened and whether a locked app is still on
  screen (Android's app usage events on this phone). BioKey reads them while the screen is on, to
  cover apps you've locked. It doesn't keep a usage history.
- **Display over other apps** lets BioKey draw its cover over a locked app as it opens. It reads
  nothing.
- While compatibility mode runs, Android requires BioKey to show a low-priority notification.

### Notification access (optional)

Used only by the notification shield, which you can switch on or off in BioKey's settings ("Hide
notification content").

Android passes every new notification to a service with notification access. BioKey looks at
which app posted it and what kind it is (calls, alarms, media and navigation are never hidden).
Notifications from apps you've locked are snoozed and replaced by one "N hidden notifications"
entry per app. When you unlock the app they come back. BioKey doesn't store, copy or read out the
content of any notification; what it needs to bring them back (their system keys, and what
tapping them opens) is held only in memory.

### Face, fingerprint and screen lock

Authentication is done entirely by Android's own system prompt (`BiometricPrompt`). BioKey never
sees, receives or stores your fingerprint, your face, or your PIN, pattern or password: Android
tells it only whether authentication succeeded. BioKey accepts only strong (Class 3) biometrics
or your screen lock.

### Installed apps

BioKey can see the names and icons of apps you can launch, so you can choose which to lock. It
can also see which home screens, keyboards, photo and file pickers, galleries and cameras are
installed, whether Google Play services and Credential Manager are installed, and the Settings
app's own labels. This is used only on the phone: to tell going Home (which ends a locked app's
session) apart from a short trip to a keyboard, a picker or the camera (which doesn't), to
recognise Google Password Manager, to open Settings pages in the Settings app itself, and, for
self-protection (not in the Google Play version), to recognise BioKey's own Settings pages. BioKey doesn't request access to the full list of installed packages
(`QUERY_ALL_PACKAGES`).

### Other permissions

- **Notifications** (`POST_NOTIFICATIONS`): the alert if protection stops, and the notification
  shield's stand-ins.
- **Start at boot** (`RECEIVE_BOOT_COMPLETED`): after a restart, BioKey starts compatibility mode
  if that is in use, or warns you if protection is off.
- **Advanced Protection status** (`QUERY_ADVANCED_PROTECTION_MODE`): so BioKey knows to switch to
  compatibility mode.
- **Hide other apps' overlays** (`HIDE_OVERLAY_WINDOWS`): while BioKey's own screens are showing,
  so another app can't draw over them.
- **Foreground service** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`): lets
  compatibility mode keep running while the screen is on.

## What BioKey stores, and where

Everything below is kept in BioKey's private app storage on your phone, which other apps can't
read. Android encrypts that storage as part of the phone's own storage encryption, tied to your
screen lock; BioKey doesn't add encryption of its own on top. It is excluded from Google's cloud
backup and from device-to-device transfer, so it never leaves the phone that way.

- **Your settings:** which apps you've locked, your profiles and their re-lock delays, whether
  protection is paused, and BioKey's other settings.
- **Unlock sessions** (which apps are unlocked right now) live only in memory, and end whenever
  the phone locks.
- **Diagnostic log** (on by default; turn it off in Settings > Diagnostic log): a short record of
  BioKey's own lock decisions about the apps you've locked, such as which locked app it covered or
  unlocked and why, naming them by package name (for example `com.example.bank`). It never names
  apps you haven't locked: going from a locked app to one of them is logged only as going to "a
  helper app" or "another app". It keeps at most 500 entries from the last 3 days. You can view
  and delete it in BioKey's settings; turning it off deletes it.

  The log leaves your phone only if you tap **Share** in BioKey's settings, which opens Android's
  share sheet so you choose where it goes (an email to support, for example). A shared log also
  includes BioKey's version, your Android version, your phone's maker and model, and which way
  BioKey is working (accessibility or compatibility mode).
- **System log:** BioKey's released app writes only short error messages to Android's system log
  (logcat), which stays on the phone. Its more detailed messages, which could name locked apps,
  are removed from the released app.

Builds of BioKey made by developers for testing (debug builds) can also keep a more detailed
trace of what BioKey saw and decided, and log timing information, both on the phone. The app on
Google Play is a release build and does neither.

## Backups

BioKey can save your settings to a backup file, and restore them, from its settings (Back up
settings, Restore from a backup). This happens only when you ask for it.

- **What's in it:** which apps you've locked, your profiles and their re-lock delays, and BioKey's
  settings (default re-lock delay, notification shield, auto-sort, and self-protection in builds
  that have it). Nothing else:
  no diagnostic log, no notifications, no authentication data.
- **How it's protected:** the file is sealed with a password you choose (at least 8 characters),
  using AES-256-GCM with a key derived from your password by PBKDF2-SHA256 (600,000 rounds).
  Without the password the file reveals nothing, not even which apps are locked, and any change
  to the file is detected. If you forget the password, the backup can't be opened, by you or by
  NB Designs.
- **Where it goes:** you choose where the file is saved using Android's file picker (your phone's
  storage, or a cloud drive app you use). It's a file you control: BioKey doesn't upload it,
  and NB Designs never receives it. If you save it to a cloud drive, that service's own privacy
  policy applies to the file. Your password manager may offer to save the backup password; that is
  between you and your password manager.

## Sharing

BioKey doesn't share any data with NB Designs or anyone else. The only things that can leave your
phone are the backup file and the diagnostic log, and only when you choose to save or share them,
to the place you choose.

## Deleting your data

- **Uninstall BioKey,** or use **Clear storage** on its App info page: this deletes everything
  BioKey keeps on the phone (your settings and the diagnostic log).
- **The diagnostic log** can be deleted on its own in BioKey's settings, or by turning it off.
- **Backup files** are yours: delete them wherever you saved them.

NB Designs holds no data about you, so there is nothing to request from us or delete on our side.

## Children

BioKey isn't directed at children under 13, and it collects no personal information from anyone,
including children.

## Security

BioKey's own screens can't be captured in screenshots or recordings, are hidden from Recents
previews, and reject touches through windows other apps draw over them. On Android 14 and later,
they are hidden from accessibility services that aren't genuine accessibility tools. No app lock
running as a regular app can defend against everything (for example a rooted phone, or a computer
connected with developer access); BioKey's limits are listed at
<https://github.com/Peacetothemountain/BioKey#limits>.

If you find a security problem, please report it privately to <support.nbdesigns@gmail.com> (see
<https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md>).

## Changes to this policy

If this policy changes, the new version will be published at
<https://peacetothemountain.github.io/BioKey/privacy/> and in the `PRIVACY.md` file of BioKey's
repository, with a new effective date. Earlier versions stay in the repository's history. If a
change ever meant BioKey handling your data in a new way, the app would tell you before it
happened.

## Contact

Questions about this policy or about BioKey: NB Designs, <support.nbdesigns@gmail.com>.
