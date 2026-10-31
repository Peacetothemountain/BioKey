# Changelog

BioKey's releases, newest first. The version is the `versionName` in `app/build.gradle.kts`.

## Unreleased

- **Take several apps out of a profile at once:** on a profile's page, tap "Select" (or long-press
  an app), pick the apps, then "Remove". One Undo puts them all back; each keeps its own lock
  switch, and each is recorded as taking one out is.

## 1.0.3 (30 October 2026)

- **What locking does:** the first time you lock an app, a sheet says what the lock doesn't cover
  (notifications without the shield, its widgets, assistants and apps with access) and, for a
  gallery, files or messaging app, which other apps can see the same photos, files or messages.
  Once per app, with "Don't show again"; always in the app's options.
- **App list order:** locked first, then recently used (with usage access), then A–Z, fixed while
  you flip switches.
- **Locked again after a reinstall:** a locked app uninstalled with its data and installed again
  within 30 days is locked again in its profile (same developer) or offered (anyone else).
- **"Lock this app" Quick Settings tile:** locks the app you're in, without opening BioKey.
- **No screen lock:** locked apps open without a check rather than never, recorded and shown on
  home until a screen lock is set again.
- Home suggests adding a face or fingerprint where none is set up; setup's last step names how
  your phone checks it's you; Settings links to Android 17's own App lock (and private space,
  where allowed).
- Self-protection is gone from the Google Play version: Play's Accessibility API policy doesn't
  allow an app to stop you switching it off or uninstalling it, outside parental-control and
  enterprise apps. If you had it on, it's off. Builds from source can still include it (see the
  README).
- The setup screens now say everything the Accessibility service reads (each new window's title)
  and does (pressing Home or Back for you), and that notification access sees which app posted each
  notification, not only locked apps' ones, as the privacy policy already did.
- Settings pages BioKey opens go straight to the Settings app, so another app can't offer to open
  them instead and pose as Settings. Pages that set your screen lock never open anywhere else.
- **Face or fingerprint only** (optional): locked apps and BioKey stop accepting your PIN. If
  someone adds their own fingerprint in Android's settings, BioKey notices and tells you.
- **Lock all now:** lock every app at once, without waiting for the screen to turn off, from the
  home screen, the status sheet, a new "Lock all" Quick Settings tile, or by long-pressing BioKey's
  icon. While protection is paused it resumes it; while nothing protects it says so.
- **Unlocked now:** home shows which locked apps are unlocked right now and when each locks again
  ("Re-locks in 4 min"), with Lock and Lock all.
- **Failed unlock alerts** (optional; BioKey asks once): faces or fingerprints that didn't match on
  a locked app are listed on home, unless yours then matched on the same prompt. Misses nobody
  followed with an unlock are notified the next time you unlock your phone, without naming the
  app; swiped away, it comes back until you've reviewed it. Optionally, also note locked apps
  opened with the screen lock. Kept 7 days after you've seen them.
- **App locking was off:** if protection is switched off, or BioKey is force-stopped, home shows
  when, from and to, and you're told at your next unlock. Android stopping BioKey on its own
  doesn't count. While it's off, the alert comes back at every unlock. BioKey's storage cleared in
  Android's settings (every lock and record with it; Android also switches its accessibility
  service off and, from Android 13, takes back its notification permission) is said on BioKey's
  setup screen when it's next opened, then recorded once it's set up. On Android 12 it's also told
  once BioKey next runs, and every so often until set up (from Android 13, once notifications are
  allowed again). Neither a changed clock nor an
  update since hides a clearing, and changes made in the day after say it was a reset. Like a face or fingerprint change,
  the reset then holds records, alerts, the log and backups for a day.
- Home's header says "Apps locked", not "Protected", while locked apps' notifications show in full
  (notification access off under "Hide notification content"), and only one card is ever urgent.
- Clearing security activity, switching failed unlock alerts or the diagnostic log off, deleting
  the log, or restoring any backup, needs your face or fingerprint: someone who only knows
  your PIN can't erase the record. Changing the phone's date can't hide or delete it either.
- Security activity also records a face or fingerprint added or removed on the phone (not
  clearable), and anything that weakens protection done from BioKey opened with the screen lock
  (a pause, an app's lock off, a profile off or deleted). Home warns if BioKey's alerts are muted,
  and Settings reminds you to lock Settings while face or fingerprint only is on.
- If a face or fingerprint is set up but the sensor can't be used, clearing records, turning
  alerts off and restoring a backup wait for it, rather than taking the PIN. Reviewing records
  only counts as seen when BioKey was opened with a face or fingerprint.
- **Stop reminding** (when app locking is switched off on purpose) stays stopped across restarts,
  the card says so, and **Remind me** undoes it; a different failure later is reminded about
  again. **Lock Android Settings too** shows only with face or fingerprint only on, can be put
  away, locks Settings even from a profile that's off, and has Undo. The notification shield
  always shows its explanation before it's switched on, and a restored backup doesn't switch it on
  by itself (BioKey says so).
- Opened with the PIN, Review offers **Mark reviewed** (with a face or fingerprint) instead of
  silently counting as seen. Records age on the clock since the phone started, so they age on
  Android 12 too, and a date set ahead can't age them early.
- Settings: face or fingerprint only and new-app offers moved to the App lock group; Hide
  notification content says what it does until it's on.
- Card buttons wrap at large text instead of squeezing. Settings rows that open a page show a
  chevron, and the ones that leave BioKey (your browser, Android's settings) an open-in-new mark. The snackbar spans the list,
  and the home header's constellation keeps clear of the tiles' labels.
- Notification access switched off while Hide notification content is on is recorded and told,
  and home says notifications aren't hidden until it's back.
- BioKey's text, its notifications and the records of changes are worded from Android's string
  resources, ready for translation (records store what changed, not a sentence).
- A face or fingerprint added stays new for a day, whatever Review says. A force stop whose start
  can't be told says so. On Android 14 and later a force stop right after an update is recorded.
  Notification access switched off under the shield is recorded from and to.
- Settings: Hide notification content can always be switched off; Protection alerts opens its
  own settings; Lock Android Settings has a Lock button. The lock screen scrolls instead of running
  under the status bar in landscape. Setup's constellation keeps clear of its title.
- Auto-sort never files an app you locked into a profile that's off.
- For a day after a face or fingerprint is added or removed, nothing proves it's you in BioKey:
  changes are recorded, nothing is marked seen, and nothing that clears or silences its records
  (or restores a backup) can be done. Clear only ever removes what Review showed. Switching hiding
  off, from home or Settings, is recorded from a screen-lock session and ends the revoked-access
  record then and there; Undo switches it back on and reopens that record (the switch-off stays
  recorded: notifications may have been read meanwhile). Stopping the reminders is recorded too,
  and a flood of records can't push a face or fingerprint change out. Review offers to open
  Android's security settings. On tablets, Settings opens beside home (its tile shows it's open;
  the pane closes with ×). Setup lists the steps in the order they're done and highlights the one
  the main button does. The accessibility-shortcut warning shows in every build.
- BioKey is called BioKey everywhere, Android's Settings included (it was "Bio Key" there).
- The accessibility service ignores what it sees until setup is done; sharing the diagnostic log
  asks for your face or fingerprint (your screen lock if it can't be used), and is recorded from a
  screen-lock session. Setup finishes by itself once a screen lock it needed is set. BioKey's main
  screen keeps its state through rotation, folding and dark-mode switches.
- Reviewed security activity is shown calmly; only something new makes the card urgent. Review
  says until when a face or fingerprint change stays new. Moving or adding a locked app into a
  profile that re-locks later, or taking it out of one, is recorded.
- The permission explanations link to the privacy policy, and say what BioKey keeps.
- When nothing is protecting your apps, home says so at the top, with the one thing to do.
- Settings has the privacy policy and help (About).
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
- **Android 17's own App lock:** an app locked in both asks once where BioKey is sure. BioKey
  lowers its cover so Android's prompt shows; once Android lets the app through it doesn't ask
  again, if Android's prompt named the app (one app on the phone of that name) and the app came
  as the prompt went. It still asks with face or fingerprint only, or with failed unlock alerts,
  on. Apps seen locked by Android are remembered for 30 days. An app only Android locks is left
  alone.
- **Let the camera open over the lock screen** (on for new setups, as in Android's own App lock;
  a phone set up before keeps it off until you switch it on): the lock-screen camera and the
  emergency dialer aren't covered while the phone is locked, and are covered once it's unlocked
  with them in front. Off, they're covered as before.
- **One prompt for every locked app on screen:** two locked apps in split screen (or one on
  another display) are asked for together ("Notes and Diary") and unlocked together.
- **Lock apps when the phone locks** (optional): apps stay unlocked through a screen-off that
  Android doesn't follow with a lock (its lock delay, Smart Lock). The default is unchanged: they
  lock when the screen turns off.
- The cover is light or dark as the locked app's own launch screen is.
- If the cover can't go up and Android refuses the lock screen without a word, the app is sent
  Home, and the diagnostic log says so.

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
