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
  them instead and pose as Settings. Pages that set your screen lock never open anywhere else.
- **Lock all now:** lock every app at once, without waiting for the screen to turn off, from the
  home screen, the status sheet, a new "Lock all" Quick Settings tile, or by long-pressing BioKey's
  icon. While protection is paused it resumes it; while nothing protects it says so.
- **Unlocked now:** home shows which locked apps are unlocked right now and when each locks again
  ("Locks again in 4 min"), with Lock and Lock all.
- **Failed unlock alerts** (optional; BioKey asks once): faces or fingerprints that didn't match on
  a locked app are listed on home, unless yours then matched on the same prompt. Misses nobody
  followed with an unlock are notified the next time you unlock your phone, without naming the
  app. Optionally, also note locked apps opened with the screen lock. Kept 7 days.
- **App locking was off:** if protection is switched off, or BioKey is force-stopped, home shows
  when, from and to, and you're told at your next unlock. Android stopping BioKey on its own
  doesn't count.
- **New apps:** an app you install that auto-sort doesn't lock gets a "Lock it?" notification;
  several at once gather under one.
- BioKey's lock screen and the lock cover now share one Unlock button: the same width, the same
  fingerprint glyph.
- Settings pages open only in the phone's own Settings app; if it doesn't have one, BioKey says
  so instead of opening whatever else answers.
- Clearer app list: no more "App" under every app, empty profiles don't say "· 0", and the default re-lock delay is stated once,
  in the "Locked" header; a row shows a delay only when it differs. On tablets the header and the
  profile chips run edge to edge. Header labels shrink to fit with large text instead of breaking
  mid-word.
- The Play version's Accessibility service no longer asks Android for screen elements' view IDs:
  only self-protection used them.

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
