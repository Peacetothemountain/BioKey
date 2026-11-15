# BioKey privacy policy

_Effective 14 November 2026_

BioKey is an app lock for Android, made by NB Designs. It keeps the apps you choose behind your
face, fingerprint or screen lock. This policy explains what BioKey can see on your phone, what it
does with it, and what it keeps.

It describes BioKey 2.0.1 and later, as published on Google Play (`com.nbdesigns.biokey`) and built
from its source code at <https://github.com/Peacetothemountain/BioKey>. BioKey 1.0 to 1.0.4 keep
less: they have no passphrase and no key that proves a match, nor the records and yes-or-no notes
that come with them. The policy as it stood for each earlier version is in the repository’s history.

## The short version

- **BioKey collects no data.** NB Designs receives nothing from the app: no personal information, no
  usage data, no crash reports, no device identifiers.
- **BioKey has no internet access.** It doesn’t request Android’s `INTERNET` permission, so it can’t
  send anything off your phone.
- **No analytics, no ads, no third parties.** BioKey contains no analytics, advertising or tracking
  code, and no third-party services.
- **Everything stays on your phone.** What BioKey keeps stays in its private storage, unless you
  choose to save a backup file or share the diagnostic log yourself. It keeps:
  - your locked apps, profiles and settings;
  - the diagnostic log: a short log of its own lock decisions, pauses, Security activity, waits
    after wrong passphrases and backups;
  - Security activity: times protection was off, faces or fingerprints added or removed, times the
    phone had no screen lock, a face or fingerprint that couldn’t be used under “Face or fingerprint
    only”, face and fingerprint switched off for apps in Android’s settings under it, matches the
    phone’s hardware couldn’t prove, your passphrase being set, changed or removed, changes made
    after BioKey was opened with your screen lock or within a day of a face or fingerprint change or
    a reset (with reminders stopped and the log opened for sharing then) and, if you turn them on,
    failed unlocks;
  - which of your locked apps Android’s own App lock was seen locking too;
  - a code worked out from each locked app’s signing certificates (SHA-256), and for 30 days after
    an uninstall its package name (the ID Android knows an app by, such as `com.example.bank`) and
    profile, to lock it again if it’s reinstalled (longer only while the phone’s date can’t be
    checked, as described below);
  - which apps “What locking does” has already been shown for;
  - a check value made from your passphrase, if you set one, never the passphrase;
  - a few yes-or-no notes it needs to work (each is described below).

## What BioKey can see, and why

BioKey asks for each of the special permissions below only after showing you, inside the app, what
it reads and what it is used for. It continues only if you tap **Agree**. Pressing Back or
dismissing the explanation is not consent. You can turn each of them off at any time in Android’s
settings.

### Accessibility service

BioKey works in one of two ways. The default uses Android’s Accessibility API, through a service
called “BioKey”. BioKey is not an accessibility tool, and says so to Android.

**What it reads:**

- Which app is on screen.
- The label of what you tap on the home screen, in its search or in Recents (an app’s name, for
  example), so a locked app can be covered as you tap it, before it opens.
- The app names on Recents cards, only while Recents is open and an app you’ve locked is unlocked,
  so closing that app from Recents locks it again.
- The title Android gives each window, to tell Google Password Manager’s own screens apart from
  sign-in sheets that appear over other apps, and to tell which app is on screen when an app hides
  its screens from accessibility services (BioKey compares the title with the names of the apps on
  the phone). The titles aren’t kept.
- On Android 17, while Android’s own App lock is asking, the words on its prompt (only that window,
  never the notification shade or a notification), to find the app it names, so an app you’ve locked
  in both BioKey and Android asks you only once. To be sure which app that is, BioKey compares that
  name with the names of the apps on the phone, as it reads them from Android. The words aren’t
  kept, only which of your locked apps they named (see “Android’s own App lock” below).
- Not in the Google Play version: builds made from BioKey’s source code can include self-protection
  (off by default). Turned on, it reads the text of the pages in Android Settings about BioKey
  itself (its App info page, its accessibility switch and the uninstall dialog), so BioKey can ask
  you to confirm it’s you before it is switched off or uninstalled.

**What it does:** it draws BioKey’s cover over a locked app until you unlock it. It can press Home
or Back for you (to leave a locked app you didn’t unlock, or, in builds with self-protection, a
guarded Settings page), lock the phone (the “Lock phone” button after too many tries), and expand a
locked app’s picture-in-picture window so it can be covered.

**What it never does:** it doesn’t read what you type, your messages or anything in other apps
beyond what’s listed above, and it doesn’t record the screen. What it reads is used only at that
moment, on your phone, to decide whether to cover an app and to protect BioKey’s own settings. It is
never collected, never sent anywhere and never shared. The only trace it leaves is the diagnostic
log described below, which names apps you’ve locked, never what was on screen.

### Usage access and display over other apps (compatibility mode)

When the accessibility service can’t run (for example under Android’s Advanced Protection, which
reserves it for accessibility tools), BioKey uses compatibility mode instead.

- **Usage access** tells BioKey which app was just opened and whether a locked app is still on
  screen (Android’s app usage events on this phone). BioKey reads them while the screen is on, to
  cover apps you’ve locked. While usage access is granted, BioKey’s app list also asks Android which
  apps were used in the last 24 hours, to put them first; that’s read as the list is shown, held
  only in memory and never kept. And while compatibility mode has nothing to watch (protection
  paused, or no app locked yet), the “Lock this app” tile asks Android which app was opened last
  (looking back up to 6 hours, never before the phone last started) as it is shown or tapped; that
  too is held only in memory. It doesn’t keep a usage history.
- **Display over other apps** lets BioKey draw its cover over a locked app as it opens. It reads
  nothing.
- While compatibility mode runs, Android requires BioKey to show a low-priority notification.

### Face, fingerprint and screen lock

Checking your face, fingerprint or screen lock is done entirely by Android’s own system prompt
(`BiometricPrompt`, the sheet Android shows over an app to check it’s you). BioKey never sees,
receives or stores your face, your fingerprint or your PIN, pattern or password: Android tells it
only whether the check passed, whether it was a face or fingerprint or the screen lock, and when a
face or fingerprint didn’t match (not whose it was). BioKey accepts your screen lock, or a face or
fingerprint that Android counts as strong (the less secure face unlock some phones have isn’t
accepted); with “Face or fingerprint only” on, a face or fingerprint alone (if they can’t be used,
your passphrase if you’ve set one, otherwise your screen lock, which is recorded). Failed unlock
alerts, if you turn them on, use those mismatches (see below). Android doesn’t tell apps about a
wrong PIN, pattern or password.

Your passphrase, if you set one (see “Your passphrase” below), is the one exception: it’s typed into
BioKey itself, never into Android’s prompt. BioKey checks it on the phone and never keeps it.

BioKey keeps two keys in Android’s Keystore (the phone’s store for keys, held in its secure hardware
where the phone has it), and a third if you set a passphrase. None of them encrypts or stores
anything of yours, and none holds anything about your face or fingerprint:

- To notice a face or fingerprint being added or removed in Android’s settings, one key that Android
  itself destroys when that happens. BioKey only looks at whether it’s still there.
- To be sure a match really happened, one key (`biokey_unlock_proof`) that signs a fresh random
  number for each unlock prompt. The hardware lets it sign only straight after it has checked you,
  so, where the phone can make and use such a key, BioKey believes a match only with that signature.
  Android destroys it if the screen lock is removed, and BioKey makes a new one once there is a
  screen lock again. If the key can’t be made or used (a prompt that comes while it’s still being
  made, or after Android destroyed it, included), or its signatures fail twice in a row, BioKey
  takes Android’s word for a match instead (after two failed signatures, for an hour, then it asks
  for the signature again), and records that in Security activity (see below). It records this only
  once until a match is proved again, and keeps one yes or no in a file of its own to know it
  already has. None of this happens while your passphrase stands in (“Face or fingerprint only” on,
  and a passphrase set): then a match without the signature never opens anything, and you can use
  your passphrase instead; where the key can’t be made or used at all, BioKey asks for the
  passphrase straight away.
- If you set a passphrase, one key mixed into its check value, so that value is no use off the
  phone.

BioKey also keeps whether a strong face or fingerprint was set up when it last looked.

### Installed apps

BioKey can see the names and icons of apps you can launch, so you can choose which to lock. It can
also see which home screens, keyboards, photo and file pickers, galleries and cameras are installed,
which apps answer the lock screen’s camera and emergency-call shortcuts, whether Google Play
services and Credential Manager are installed, and the Settings app’s own labels. This is used only
on the phone: to tell going Home (which locks an unlocked app again) apart from a short trip to a
keyboard, a picker or the camera (which doesn’t), to let the lock screen’s camera and emergency
dialer open over the lock screen, to recognise Google Password Manager, to open Settings pages in
the Settings app itself, and, for self-protection (not in the Google Play version), to recognise
BioKey’s own Settings pages. For the helper apps BioKey knows by name (authenticators, password
managers, messages and email, browsers), it can see whether each is installed, and the app stores it
knows by name too. It also asks Android whether each came with the phone, which app installed it and
which app asked for the install, to be sure it is the app of that name (one from an app store, not a
file); for F-Droid, which doesn’t come with a phone, it checks the certificate F-Droid’s app is
signed with; and for one that is only in Android’s work profile or Private Space, whether it is part
of the phone. That answer is held only in memory, for at most 5 minutes.

When you install an app that nothing locked, BioKey can offer to lock it (a notification naming it;
turn this off in BioKey’s settings, “Offer to lock new apps”). The first time you lock an app,
BioKey looks at whether it has home-screen widgets and, for a gallery, files or messaging app, which
other launchable apps hold permission to read photos, files or text messages, to tell you; that’s
read at that moment and not kept. For apps you’ve locked, BioKey reads a code worked out from their
signing certificates (SHA-256; public information about the app, not about you), to recognise the
same app if it’s reinstalled (see below). BioKey doesn’t request access to the full list of
installed packages (`QUERY_ALL_PACKAGES`).

### Other permissions

- **Notifications** (`POST_NOTIFICATIONS`): the alert if protection stops (shown again, quietly,
  each time you unlock your phone and about every 15 minutes while it’s still off, unless you stop
  the reminders in BioKey; on the lock screen it says only “BioKey”); Security activity (failed
  unlocks, times protection was off, changes and the other records below), told the next time you
  unlock your phone, never naming the app and never shown on the lock screen, staying until you’ve
  reviewed it; the offer to lock an app you just installed (its name hidden on the lock screen if
  your phone hides sensitive notification content there); and “Protection is paused”, while you’ve
  paused protection outside compatibility mode, saying until when, with a Resume button, never shown
  on the lock screen. In Android’s settings for BioKey’s notifications, its categories are
  “Protection alerts”, “Security activity”, “New apps”, “Protection paused” and “Compatibility mode”.
- **Start at boot** (`RECEIVE_BOOT_COMPLETED`): after a restart, BioKey starts compatibility mode if
  that is in use, or warns you if protection is off.
- **Advanced Protection status** (`QUERY_ADVANCED_PROTECTION_MODE`): so BioKey knows to switch to
  compatibility mode.
- **Hide other apps’ overlays** (`HIDE_OVERLAY_WINDOWS`): while BioKey’s own screens are showing, so
  another app can’t draw over them. One exception: in compatibility mode, while BioKey’s cover has
  to stay up over a lock screen (a locked app in picture-in-picture floats above it), that lock
  screen doesn’t hide overlays, because Android would hide BioKey’s own cover with them. Touches
  through anything drawn over it are still rejected.
- **Foreground service** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`): lets
  compatibility mode keep running while the screen is on.

## What BioKey stores, and where

Everything below is kept in BioKey’s private app storage on your phone, which other apps can’t read.
Android encrypts that storage as part of the phone’s own storage encryption, tied to your screen
lock; BioKey doesn’t add encryption of its own on top. It is excluded from Google’s cloud backup and
from device-to-device transfer, so it never leaves the phone that way.

### Your settings

Which apps you’ve locked, your profiles and their re-lock delays, whether protection is paused, and
BioKey’s other settings. Also which of the suggestions on BioKey’s main page you’ve answered or put
away, and, just after setup and until you tap “Got it” there, how many apps auto-sort locked.

### Which apps are unlocked

Which apps are unlocked right now is held only in memory, and every one of them locks again whenever
the phone locks. The app last on screen, for the “Lock this app” tile, is kept in memory too: one
package name, replaced as you switch apps.

### Locked apps that were uninstalled

For each app you’ve locked, a code worked out from its signing certificates (SHA-256). When one is
uninstalled with its data, its package name, that code, its profile and the time are kept for 30
days, so that installing the same app again locks it again (or, if someone else signed it, offers
to).

The 30 days are measured like failed unlocks’ ages (below), on clocks that changing the phone’s date
doesn’t move. One timed by the phone’s own clock (Android 12, or no network time) isn’t forgotten
for its age after a restart, so it can be kept longer than 30 days; at most 200 are kept, the oldest
going first. Not in backups; forgotten when a backup is restored.

### Your passphrase

Only if you set one, and never the passphrase itself. You set it in BioKey’s settings, in the
Passphrase row under “Face or fingerprint only”, which shows once that’s on. BioKey keeps a check
value made from it (with PBKDF2-HMAC-SHA512: 210,000 rounds and a random salt, which makes every
guess slow; then mixed with the Keystore key above, so a copy of the file alone can’t be tested
against guesses off the phone), the salt, the number of rounds, how many wrong tries in a row there
have been, and until when the next try has to wait. Not in backups, never sent anywhere. Removing
the passphrase in BioKey’s settings deletes all of it, and so does clearing BioKey’s storage or
uninstalling it.

While it stands in (“Face or fingerprint only” on, and a passphrase set), wherever this policy says
BioKey asks for your screen lock because no face or fingerprint can be used, it asks for your
passphrase instead: the screen lock alone never passes those checks.

Setting, changing or removing it is recorded in Security activity (below) whatever BioKey was opened
with, and can’t be done within a day of a face or fingerprint being added or removed, or of BioKey
being reset. Wrong tries are also recorded with failed unlocks, if you turn on “Failed unlock
alerts” (below).

### “What locking does”

Which apps you’ve been told about when locking them (their package names, at most 300) and whether
you chose “Don’t show again”, kept until BioKey’s storage is cleared, so the same app isn’t
explained twice.

### Android’s own App lock

On Android 17: which of the apps you’ve locked Android’s own App lock was seen locking too, and when
it last was, so BioKey can say so. An app not seen locked by it for 30 days is forgotten.

### Failed unlock alerts

Off until you turn them on; BioKey asks once. For each failed unlock on a locked app (or on BioKey’s
own prompts that confirm a change, listed as BioKey): which app (its package name and name), when,
how many faces or fingerprints (or passphrases) didn’t match, whether face and fingerprint were
locked out, and how the app was then opened, if it was. When your face or fingerprint matches on the
same prompt, or your passphrase on the same lock screen, its misses were yours and aren’t kept. If
you also turn on “Also record screen lock unlocks”, each locked app opened with your PIN, pattern or
password is listed too (not notified).

BioKey’s main page lists them until you clear them. Once you’ve seen them (Review), each is deleted
7 days after it happened, and at most 50 seen ones are kept; until then none is deleted for its age,
and none to make room for newer ones short of 500. Past 500, the oldest 250 and the newest 250 are
kept, so a flood of new ones can’t push out what happened first.

Ages are measured on clocks that changing the phone’s date doesn’t move: the time since the phone
last started, and across a restart the network’s time where the phone has it (Android 13 and later).
A record taken on the phone’s own clock (Android 12, or no network time) isn’t deleted for its age
after a restart, only to stay within those limits.

Opening Review counts as seeing them only when BioKey was opened with your face or fingerprint (or
none is set up); otherwise Review offers “Mark as reviewed”, which asks for one.

A face or fingerprint added or removed stays new for a day whatever Review says (if its age can’t be
told after a restart, until the phone has been on for a day), so whoever made the change can’t use
it to quiet the alert. Meanwhile BioKey can’t tell whether the new face or fingerprint is yours, so
changes are recorded (marked as made within a day of a face or fingerprint change, or of BioKey
being reset), and nothing is counted as seen. Nothing that clears or silences BioKey’s records can
be done: clearing them, turning off “Failed unlock alerts”, “Also record screen lock unlocks” or
“Diagnostic log”, deleting the log and restoring a backup all wait, and so does setting, changing or
removing your passphrase. After the day, review them again (if Review is still open on BioKey’s
screen then, with a face or fingerprint that now counts, they count as seen).

Turning the alerts off deletes them; a restored backup that turns them off leaves them in place.
Clearing them, or turning the alerts off, asks for your face or fingerprint where one is set up.

### Times protection was off, and other changes

BioKey records:

- when BioKey’s protection was switched off or BioKey was force-stopped, and when it came back;
- when the phone’s screen lock was removed (so locked apps opened without a check), from when BioKey
  noticed until one was set again;
- when BioKey’s storage was cleared (worked out on the phone from Android’s record of how BioKey’s
  process last ended: some time after BioKey last ran), until it was set up again; like a face or
  fingerprint change, this holds records for a day;
- when a face or fingerprint was added or removed on the phone;
- when a face or fingerprint couldn’t be used while “Face or fingerprint only” was on and no
  passphrase was set, so your screen lock was allowed;
- when face and fingerprint were switched off for apps in Android’s settings (Android 15 and later)
  while “Face or fingerprint only” was on and no passphrase was set, so your screen lock was allowed
  (recorded apart from a face or fingerprint that couldn’t be used);
- when a match couldn’t be proved by the phone’s hardware, so Android’s word for it was taken (on a
  phone with a screen lock, once until a match is proved again);
- each time your passphrase was set, changed or removed, whatever BioKey was opened with.

It also records changes that weaken protection: on a phone with a face or fingerprint, those made
after BioKey was opened with your screen lock and not confirmed with a face or fingerprint; and
those made within a day of a face or fingerprint being added or removed, or of BioKey being reset,
however BioKey was opened. They are:

- protection paused;
- an app’s lock switched off;
- a locked app moved or added into a profile that’s off or that re-locks later, or taken out of its
  profile so it’s locked less;
- a profile switched off or deleted;
- a longer re-lock delay, for a profile or the default;
- the camera let open over the lock screen;
- in builds with self-protection, its switch turned off;
- within that day, “Face or fingerprint only” switched off and the reminders stopped.

Those that concern an app or profile name it. Opening the diagnostic log for sharing at those times
is recorded too, though it changes nothing.

On a phone with a face or fingerprint, some changes can’t be made with the screen lock at all,
because they ask for a face or fingerprint: restoring a backup, stopping the reminders, and
switching off “Face or fingerprint only”, “Failed unlock alerts” or “Diagnostic log”. That’s why
“Face or fingerprint only” switched off and the reminders stopped are recorded only within a day of
a face or fingerprint change or a reset: that’s when a face or fingerprint that has just been added
could make them. Within that day, clearing records, switching off “Failed unlock alerts”, “Also
record screen lock unlocks” or “Diagnostic log”, deleting the log, restoring a backup, and setting,
changing or removing the passphrase can’t be done at all.

These records are shown on BioKey’s main page until you clear them, and kept like failed unlocks:
until you’ve seen them, and for 7 days after they happened (at most 20 seen ones). A face or
fingerprint added or removed, and BioKey being reset, don’t count toward the 20: once seen, each is
kept its full 7 days whatever is recorded after it. The same record made again and again before
you’ve seen it is kept at most 5 times; after that the latest of them moves on to the newest time
instead of another being added. So a run of small changes can’t push out the records that matter. A
face or fingerprint added or removed can’t be cleared, only aged out, so whoever added one can’t
erase the record of it. A number orders these records and failed unlocks, whatever the phone’s clock
says. These records are kept for everyone; only the changes name an app or profile.

To measure them, BioKey keeps, while protection is off, when it stopped (on the phone’s clock and on
its clock since the last restart) and the phone’s restart count. To tell a force stop from Android
stopping BioKey on its own, it reads Android’s record of why its own process last ended (the reason
and the time; which of Android’s last few it has looked at), and when BioKey itself was last updated
(an update stops it too). From Android 15 it also reads whether its process started with BioKey left
force-stopped, and keeps that (a yes, nothing more) until protection is next on, since Android can
lose its record of the stop when the phone restarts.

### Diagnostic log

On by default; turn off “Diagnostic log” in BioKey’s settings to stop it. A short record of BioKey’s
own lock decisions about the apps you’ve locked, such as which locked app it covered or unlocked and
why, naming them by package name (for example `com.example.bank`). It never names apps you haven’t
locked: going from a locked app to one of them is logged only as going to “a helper app” or “another
app”.

It also logs:

- each record in Security activity as it’s made (how long protection was off, a face or fingerprint
  added or removed, a change made after BioKey was opened with your screen lock);
- pauses, and how long they last;
- while “Failed unlock alerts” is on, failed unlocks of locked apps and locked apps opened with the
  screen lock (which app, not whose face or finger);
- your passphrase being set, changed or removed, a wait starting after wrong tries, and a locked app
  opened with it;
- the proof of a match: a match the phone’s hardware couldn’t prove, and a prompt run without that
  proof because the key behind it wasn’t ready yet or had been destroyed (the screen lock removed);
- backups saved and restored;
- each of BioKey’s two ways of working (the accessibility service, compatibility mode) starting and
  stopping.

It keeps at most 500 entries from the last 3 days, measured like failed unlocks: on the time since
the phone started, and across a restart on the network’s time. After a restart with no network time,
older entries aren’t deleted for their age, only to stay within 500. You can view and delete it in
BioKey’s settings (the viewer shows each locked app’s name beside its package name, read from the
phone as you look; the log itself keeps package names only). Deleting it or turning it off asks for
your face or fingerprint (your screen lock where none is set up).

The log leaves your phone only if you open it in BioKey’s settings (View diagnostic log), tap the
share icon (**Share the diagnostic log**) and confirm with your face or fingerprint (your screen
lock if they can’t be used; shared after BioKey was opened with your screen lock, it’s recorded).
That opens Android’s share sheet, so you choose where it goes (an email to support, for example). A
shared log also includes BioKey’s version, your Android version, your phone’s maker and model, and
which way BioKey is working (accessibility or compatibility mode).

### System log

BioKey’s released app writes only short error messages to Android’s system log (logcat), which stays
on the phone. Its more detailed messages, which could name locked apps, are removed from the
released app.

Builds of BioKey made by developers for testing (debug builds) can also keep a more detailed trace
of what BioKey saw and decided, and log timing information, both on the phone. The app on Google
Play is a release build and does neither.

## Backups

BioKey can save your settings to a backup file, and restore them, from its settings (Back up
settings, Restore from a backup). This happens only when you ask for it.

- **What’s in it:** which apps you’ve locked, your profiles and their re-lock delays, and BioKey’s
  settings (default re-lock delay, “Face or fingerprint only”, the camera over the lock screen,
  auto-sort and the apps you’ve told it to leave alone, failed unlock alerts and their screen lock
  option, new-app offers, whether the diagnostic log is on, and the self-protection setting, which
  is always off in the Play version). Nothing else: no diagnostic log, no failed unlocks or times
  protection was off, no passphrase, nothing about your face, fingerprint or screen lock. Restoring
  a backup never deletes the failed unlocks kept on the phone, never changes the passphrase this
  phone has, and always asks for your face or fingerprint first (your screen lock where none is set
  up). Within a day of a face or fingerprint added or removed, or of BioKey being reset, it can’t be
  done at all. A restore that turns on “Face or fingerprint only” with no passphrase set here offers
  to set one.
- **How it’s protected:** the file is sealed with a password you choose (at least 8 characters,
  typed twice), using AES-256-GCM with a key derived from your password by PBKDF2-SHA256 (600,000
  rounds). The password is held only in memory until the file is written, and is wiped the moment
  BioKey locks itself, so a backup asked for before BioKey locked is never written after it. Without
  the password the file reveals nothing, not even which apps are locked, and any change to the file
  is detected. If you forget the password, the backup can’t be opened, by you or by NB Designs.
- **Where it goes:** you choose where the file is saved using Android’s file picker (your phone’s
  storage, or a cloud drive app you use). It’s a file you control: BioKey doesn’t upload it, and NB
  Designs never receives it. If you save it to a cloud drive, that service’s own privacy policy
  applies to the file. Your password manager may offer to save the backup password; that is between
  you and your password manager.

## Sharing

BioKey doesn’t share any data with NB Designs or anyone else. The only things that can leave your
phone are the backup file and the diagnostic log, and only when you choose to save or share them, to
the place you choose.

## Deleting your data

- **Uninstall BioKey,** or use **Clear storage** on its App info page: this deletes everything
  BioKey keeps on the phone (your settings, your passphrase’s check value, the diagnostic log,
  failed unlocks and times protection was off).
- **The diagnostic log** can be deleted on its own in BioKey’s settings, or by turning it off.
- **Your passphrase** can be removed in BioKey’s settings, in the Passphrase row under “Face or
  fingerprint only” (shown whenever a passphrase is set; BioKey checks it’s you first), which
  deletes everything kept about it.
- **Failed unlocks, times protection was off and changes** can be cleared from BioKey’s main page
  (Review > Clear), except a face or fingerprint added or removed, which ages out on its own.
  Turning off “Failed unlock alerts” also deletes the failed unlocks. Both ask for your face or
  fingerprint (your screen lock where none is set up), so someone who only knows your screen lock
  can’t erase them; within a day of a face or fingerprint added or removed, or of BioKey being
  reset, neither can be done.
- **Backup files** are yours: delete them wherever you saved them.

NB Designs holds no data about you, so there is nothing to request from us or delete on our side.

## Children

BioKey isn’t directed at children under 13, and it collects no personal information from anyone,
including children.

## Security

BioKey’s own screens can’t be captured in screenshots or recordings, are hidden from Recents
previews, and reject touches through windows other apps draw over them. On Android 14 and later,
they are hidden from accessibility services that aren’t genuine accessibility tools. No app lock
running as a regular app can defend against everything (for example a rooted phone, or a computer
connected with developer access); BioKey’s limits are listed at
<https://github.com/Peacetothemountain/BioKey#limits>.

If you find a security problem, please report it privately to <support.nbdesigns@gmail.com> (see
<https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md>).

This policy is also linked from BioKey’s settings (About > Privacy policy), and from each
explanation BioKey shows before asking for a permission.

## Changes to this policy

If this policy changes, the new version will be published at
<https://peacetothemountain.github.io/BioKey/privacy/> and in the `PRIVACY.md` file of BioKey’s
repository, with a new effective date. Earlier versions stay in the repository’s history. If a
change ever meant BioKey handling your data in a new way, the app would tell you before it happened.

## Contact

Questions about this policy or about BioKey: NB Designs, <support.nbdesigns@gmail.com>.

If you email us, we receive your email address and whatever you choose to send (a shared diagnostic
log, for example). We use it only to answer you, keep it no longer than that takes, and delete it on
request.
