# Changelog

BioKey's releases, newest first. The version is the `versionName` in `app/build.gradle.kts`.

## Unreleased

- Self-protection is gone from the Google Play version: Play's Accessibility API policy doesn't
  allow an app to stop you switching it off or uninstalling it, outside parental-control and
  enterprise apps. If you had it on, it's off. Builds from source can still include it (see the
  README).
- The setup screens now say everything the Accessibility service reads (each new window's title)
  and does (pressing Home or Back for you), and that notification access sees which app posted each
  notification, not only locked apps' ones, as the privacy policy already did.
- Settings pages BioKey opens go straight to the Settings app, so another app can't offer to open
  them instead and pose as Settings.

## 1.0.2 (26 October 2026)

- An app in a profile that's switched off now shows its lock switch off (and greyed out), and is
  listed under "Not locked". Before, its switch still showed on although the app wasn't locked.
  Turning the profile back on brings back each app's own choice, as before.
- Deleting a profile switches its apps off, with an Undo that brings the profile back. Before, they
  kept their switches, so deleting a profile that was off locked all its apps.

## 1.0.1 (24 October 2026)

- The default re-lock delay in Settings now applies to apps in profiles too. Profiles follow it
  unless you give one its own delay (its re-lock sheet has "Same as Settings"). Before, built-in
  profiles kept their own delays (immediately, or 5 minutes for Work), and as auto-sort files most
  apps into them, changing the default did almost nothing.
- The search bar no longer slides under the status bar when you scroll the app list.

## 1.0 (24 October 2026)

The first release on Google Play, as `com.nbdesigns.biokey`. Android 12 and later.

- Locks the apps you choose behind Class 3 face unlock, fingerprint, or your PIN, pattern or
  password, through Android's own prompt.
- Two ways of working: the accessibility service (the default, which covers a locked app at the
  tap) and compatibility mode (usage access and display over other apps), used when Android's
  Advanced Protection reserves accessibility for accessibility tools. BioKey switches between them
  by itself.
- Sessions: an unlocked app stays unlocked while it's on screen, and through short trips to helper
  apps (authenticators, pickers, the camera, Settings) and interruptions. It locks again after its
  re-lock delay (immediately, 30 s, 1, 5 or 15 min, or at screen off). Locking the phone locks
  everything.
- Profiles with their own re-lock delays and on/off switches, and optional auto-sort into Finance,
  Social and Work.
- Optional notification shield: a locked app's notifications are hidden until it's unlocked.
- BioKey locks itself, and optional self-protection asks for authentication before BioKey can be
  switched off or uninstalled.
- Pause from the app or a Quick Settings tile; an alert if protection stops.
- Password-protected backup and restore (AES-256-GCM, PBKDF2-SHA256).
- An on-phone diagnostic log of BioKey's lock decisions, kept for 3 days, which can be turned off.
- Split screen, picture-in-picture, desktop windows, other displays and foldables' cover and main
  screens.
- No internet access, no analytics, no data collected.
