# Changelog

BioKey’s releases, newest first. The version is the `versionName` in `app/build.gradle.kts`.

## 2.0.1 (14 November 2026)

2.0.0 was built and installed for testing but never released on Google Play, so 2.0.1 is the
first 2.0 on Play, and this entry and 2.0.0’s below are read together. Version code 22. It
changes when a match the phone’s hardware can’t prove is let through, records and holds changes
to the passphrase, closes a few ways BioKey could stay unlocked or forget a removed app early, and
brings a round of wording and layout fixes across BioKey’s screens.

How unlocking is decided:

- **Proof of a match is never left off where your passphrase stands in** (“Face or fingerprint
  only” on, and a passphrase set). 2.0.0 left the check off after two failed signatures in a row,
  until BioKey next started; with a passphrase there, a match the hardware can’t prove now opens
  nothing however often it happens. A locked app’s lock screen says “Couldn’t confirm that was
  you. Try again.” and “Use passphrase” is there as before; a check before a change in BioKey asks
  for the passphrase in its place. And where there is no proof to ask the hardware for at all
  (the key can’t be made or used), the face or fingerprint prompt isn’t shown: the passphrase is
  asked for instead, on lock screens and inside BioKey. Without a passphrase standing in, the
  check is still left off after two failed signatures, but now for an hour, then it’s asked for
  again (2.0.0 left it off until BioKey next started, which can be weeks); and prompts run without
  it where the key can’t be made or used, so faulty hardware never locks you out.
- **Told, not only logged.** When matches are taken on Android’s word on a phone with a screen
  lock (the check left off after two failed signatures, or the key can’t be made or used), that’s
  now written into Security activity (“A match couldn’t be proved by the phone’s hardware, so
  Android’s word for it was taken”) and alerted like other records (“BioKey couldn’t prove a match
  with the phone’s hardware, so it took Android’s word for it.”). Once, until a match is proved
  again. To know it has already said so, BioKey keeps one yes or no in a small file of its own.
  Where the key can’t be made or used at all, prompts still run without it, passphrase or not.
- **The first prompt after BioKey starts** waits up to 0.3 seconds for the key to load, rather
  than running without the check; making a new key can take longer, and that prompt then runs
  without it, which the diagnostic log now says. It says so too when the key was destroyed (the
  screen lock removed since) and that prompt runs without it while a new one is made.
- **Face and fingerprint switched off for apps** in Android’s settings (Android 15 and later),
  with “Face or fingerprint only” on, is now recorded as what it is (“Face and fingerprint were
  switched off for apps in Android’s settings, so your screen lock was allowed”), with an alert of
  its own (“If it wasn’t you, someone knows your screen lock.”). Before, it read as a face or
  fingerprint that couldn’t be used, as if the sensor had a fault, though it takes someone to
  switch it off.
- **Passphrase changes are held and recorded.** Setting, changing or removing your passphrase now
  waits for a day after a face or fingerprint is added or removed, or after BioKey is reset, like
  the other held changes (“Not until” and the time), and is listed under “What waits a
  day”. Each time, Security activity records it (“Passphrase set”, “Passphrase changed”,
  “Passphrase removed”), with an alert, whatever BioKey was opened with. Before, someone who
  knows the screen lock could add their own fingerprint, set a passphrase only they know, then
  remove the fingerprint, and keep a way in that nothing showed.
- **Leaving BioKey for a page it sent you to** (a page of Android’s settings, the file picker)
  keeps it unlocked for up to two minutes, as before. That now ends as soon as you reach Home by
  any route: a Home gesture that Android reported only as a change of windows used to be missed.
  And it isn’t kept at all where nothing is running that would notice you going elsewhere (no
  engine running, or compatibility mode resting because protection is paused or nothing is
  locked): there, coming back to BioKey asks who it is.
- **Helper apps:** of the apps Android says pick or capture photos and files, only those that came
  with the phone now count as helpers you can go to and come back from without a locked app locking.
  An app you install could say it picks files just to be counted. The helpers BioKey knows by name
  (authenticators, password managers, messages and email, and a browser’s sign-in tab) now count
  only if they came with the phone or were installed by an app store: one that itself came with the
  phone (Google Play, Galaxy Store or another phone maker’s store), or F-Droid, told by the
  certificate its app is signed with. An app installed from a file could take the name of one that
  isn’t on the phone, and so could a file calling itself a store. An install made from a computer in
  a store’s name doesn’t count, and an app that is only in a work profile or Private Space counts
  only if it is part of the phone.
- **Left from the phone’s PIN screen:** where a check inside BioKey hands over to the phone’s own
  PIN screen and you leave from there (Home, another app), BioKey now locks itself. Its session used
  to stay open for up to 3 minutes.
- **An answer that comes too late opens nothing:** if everything was locked while BioKey’s own
  unlock prompt was up (the screen turned off, “Re-lock now”), a match reported after that no longer
  opens BioKey, as it already didn’t open a locked app.
- **Recorded every time, not once:** a face or fingerprint that couldn’t be used under “Face or
  fingerprint only”, or face and fingerprint switched off for apps, was recorded once for as long as
  BioKey kept running, which can be weeks. It is now recorded again each time it comes back after
  they were usable.
- **A record that couldn’t be made isn’t lost:** if a match can’t be proved before setup is
  finished, when Security activity takes no records, it is recorded at the next prompt that runs
  without proof.
- **Builds with self-protection:** when BioKey opens one of Android’s pages itself (or Android’s
  question about notifications), “Ask before BioKey is stopped or removed” stands down for that
  trip. That now ends the moment BioKey locks itself, not up to 2 minutes later.
- **Locked again after a reinstall:** the 30 days a removed locked app is remembered for are now
  counted as Security activity’s ages are, on the clock since the phone started or, across a
  restart, the network’s time. One timed by the phone’s own clock (Android 12, or no network
  time) isn’t aged after a restart, though the oldest still go once more than 200 are
  remembered. Before, setting the phone’s date ahead and restarting BioKey while the phone had no
  network time could make it forget an app early, and not lock it again.
- **Patches stay up while a prompt is up.** With the accessibility engine, a locked app’s window in
  picture-in-picture, beside the lock screen or on another display is covered by a patch. While
  an unlock prompt was up, every patch on its display used to be hidden so none could cover it,
  which left those windows showing, dimmed, for as long as the prompt waited. Now only a patch
  that would cover the prompt’s own sheet is hidden. Where BioKey can’t tell where the sheet is,
  every patch on that display is hidden, as before.

Your passphrase:

- **Typed twice** when you set or change it (“Type it again”), so a typo can’t become a
  passphrase you don’t know. Its dialogs (set, change, remove, confirm) follow one layout.
- **The wait after wrong tries is said in full**: “Try again in 29 seconds”, not “29 s”. The lock
  screens keep room for that line, so nothing on them moves as a message comes and goes. During a
  wait, or while a passphrase is being checked, the field keeps the cursor and the keyboard stays
  up, but nothing typed goes in; once the wait is over you can type straight away.
- **Under a held cover.** In compatibility mode, when a locked app stays on screen in a small
  window it won’t leave, BioKey’s cover is held over the whole screen, and the passphrase can’t be
  typed under it. “Use passphrase” there now says “To use your passphrase, close this app’s
  picture-in-picture window, then open the app again”, instead of opening an entry that couldn’t
  be reached.
- **A wrong passphrase is named.** Where it stands in, the failed unlock alert reads “A face,
  fingerprint or passphrase didn’t match on a locked app, and it wasn’t opened.”; the note under
  failed unlocks in Review says the tries that didn’t match can be a passphrase.
- **The diagnostic log** says when a locked app was unlocked with your passphrase.

What BioKey says a locked app asks for now follows your settings: “your face, fingerprint or
screen lock”, “your face or fingerprint” under “Face or fingerprint only”, with “or your
passphrase” when one is set, or “your passphrase” alone when no face or fingerprint can be used.
That goes for “What locking does”, the “Lock app” row in an app’s options, the offer to lock a new
app, and setup. None of them names a way in that would be refused.

What you see:

- **“Protection is paused”**, a quiet notification with a **Resume** button, stays up while
  you’ve paused protection, saying until when (“Locked apps open without a check until 7:15 PM, or
  until you resume”). Hidden on the lock screen. In compatibility mode its own notification already
  says so, so this one isn’t shown. It has its own notification channel, “Protection paused”.
- **One word for protection:** BioKey locking your apps is called protection everywhere (BioKey’s
  main page, BioKey’s settings, the Quick Settings tiles, notifications and their channels, Security
  activity), and it’s on, off, paused or not running: “Protection is off”, “Protection isn’t
  running”, “Protection is paused”, “Pause protection”, “Resume protection”. The main page’s header
  says “On” where it said “Protected”, and Security activity says “Protection was off” where it said
  “App locking was off”. Its alerts are “Protection alerts” throughout (“Protection alerts are off”,
  “Some protection alerts are off” on the main page). A reset is “BioKey was reset” everywhere. The
  first group in BioKey’s settings is “Locking”, so nothing in BioKey is called “App lock” except
  Android’s own.
- **Names:** the accessibility service is now called “BioKey” (was “BioKey App Lock Service”), and
  its page in Android’s Accessibility settings links back into BioKey. The pause tile in Quick
  Settings is “Protection”, and says “Paused until 7:15 PM · tap to resume” during a timed pause.
  The action that locks every unlocked app again at once is “Re-lock now” everywhere (the tile,
  BioKey’s main page, the launcher’s menu), where it was “Lock all” or “Lock all apps now”; the
  shortcut pinned to the home screen is “Re-lock”, and it ends with “Locked apps will ask again”.
  Its tile and shortcut have a new icon, a grid of apps with a padlock in place of one, and the
  “Lock this app” tile’s is a single app with a padlock, so the two can’t be mistaken for each
  other. The line under the prompt’s title reads “Confirm it’s you”.
- **BioKey’s settings:** each row’s subtitle is short. Turning on “Let the camera open over the lock
  screen” asks first, and says in that dialog what it lets through. “Protect BioKey’s settings”
  (builds with self-protection only) is “Ask before BioKey is stopped or removed”, so it isn’t
  read as the row above it, and moved to the Locking group. The version has its own line at
  the foot of the page. Turning off failed unlock alerts or the diagnostic log says it deletes
  them, as it always did. The Protection row gives only its state (“On”), leaving what opens a
  locked app to the rows under it. “Lock Android Settings too” is a switch, on or off, with Undo
  either way (it used to be a row you tapped once, which then went); on, it says what opening
  Android Settings asks for. During the day’s hold after a face or fingerprint change or a reset,
  the Restore row says “Not until” and the time, and tapping it says why before any file is
  picked. Auto-sort’s subtitle says what it does (“Locks the apps it recognises and sorts them
  into Personal, Finance, Social and Work”): it used to name only banking, social and work apps.
- **BioKey’s main page:** an app’s second line says only what its group or page doesn’t already: its
  profile (and that it’s off, if it is), a re-lock delay of its own, “Unlocked now”, “Work copy”.
  With nothing to add, the row has one line. Security activity takes a row’s height when something
  more urgent is above it. In a short window (landscape, split screen) the header is a row high, and
  the search bar and profile chips scroll away with the list instead of staying at the top. The
  chosen profile’s chip shows a tick and scrolls into view. On an unfolded foldable or a tablet, the
  main page and BioKey’s settings each keep to a column narrow enough to read easily, and Settings
  opens beside the main page when there’s room for both.
- **One offer at a time:** cards that need you (a record to review, protection off) come first,
  then at most one calm offer, in this order: what auto-sort did, once, after setup (“BioKey
  locked 12 apps for you”, with “Got it”); adding a face or fingerprint; locking Android
  Settings; failed unlock alerts. An offer stays until it’s answered or put away, then the next
  one comes. Before, they could all show at once, and what auto-sort did went as soon as you left
  the page; now it stays until you tap “Got it”.
- **Auto-sort** goes by the names it knows before the category an app gives itself on Android, so
  a web browser goes to Personal (Android files browsers under productivity, which put them in
  Work).
- **A prompt without proof is always recorded:** where Android destroyed the key that proves a
  match, or it was still being made, the prompt that ran without that proof is written into
  Security activity, where it was only in the diagnostic log. (Not where your passphrase stands
  in: there the passphrase is asked for and no such prompt runs.)
- **Quick Settings over the lock screen:** once BioKey is set up, the Protection tile looks the same
  whether protection is on, paused or off (lit, with no line under its name), and “Re-lock now” has
  no line under its name either. “Lock this app” never names the app there, as before.
- **Review** puts the same record several times running on one line (“3 times”), says each note
  once per section, and lists a switched-off lock as “Lock switched off: Notes”; its button to
  Android’s security page reads “Check faces, fingerprints and screen lock”. During the day’s
  hold after a face or fingerprint change or a reset, it says so in one sentence (“some changes
  wait until 9:40 PM in case it wasn’t you”), then shows the records, with what waits in a list
  under them that opens from “What waits a day”. The button is “Mark as reviewed”.
- **Security activity keeps what matters.** A face or fingerprint added or removed, and a reset,
  no longer count toward the 20 seen records kept: once seen, each stays its full 7 days whatever
  is recorded after it. And the same record made again and again before you’ve seen it (“Lock
  switched off: Notes”) is kept at most 5 times; after that the latest one moves on to the newest
  time instead of another being added. So a run of small changes can’t push out the records that
  matter.
- **During the day’s hold** the alert for a change, or for the diagnostic log being shared, no
  longer says BioKey was opened with your screen lock (“BioKey’s protection was changed. Open
  BioKey to see what.”): in that day changes are recorded whatever opened it.
- **Dialogs you type into** (passphrase, backup password, a profile’s name) take only the room
  above the keyboard, so their buttons stay in view; before, a tall one could leave Save under
  the keyboard on Android 15 and later. Their icon, title, text and fields scroll as one, with
  the field you’re typing in kept in view; only with very little room (landscape, large text)
  do the buttons scroll too. Cancel after “Remove passphrase” goes back to the Change dialog as
  you left it. Sheets’ drag handles light up as a circle, not a slab.
- **Lock screens** in landscape or a short window shrink the app’s icon (or BioKey’s logo) to
  fit, so the name and the buttons always show; shorter still, an app’s icon goes and its name
  stays.
- **Profiles:** the delay is called “Re-lock delay” throughout, and the default “Default re-lock
  delay”. An app leaves its profile by choosing “No profile” in its options, the one control for
  it. A name near the length limit shows a counter, and a pasted name is cut to fit. Deleting a
  profile with no apps says “It has no apps, so nothing else changes”, not that its apps will be
  unlocked. A new profile opens with the cursor in its name. The first icon to choose from is a
  person (Personal’s, and a new profile’s to start with), where it was a shield.
- **Setup:** nothing opens without a tap. Back from Android’s settings, the step shows as done
  (“Allowed, so BioKey can cover a locked app”) and the button moves on to the next one, but
  Android’s notification question and the final check wait for you, where before they came up on
  their own. The steps setup needs are numbered, and a line over them counts them (“1 of 2 steps
  done”); allowing notifications sits under its own heading, “Optional”, and isn’t counted. Before
  you finish, a note says that auto-sort will then lock the apps it recognises and sort them into
  profiles. Agreeing to a disclosure holds for the rest of that setup, so opening Accessibility
  settings again doesn’t ask again. Once you turn BioKey on in Accessibility settings that BioKey
  opened (from setup, its main page or its settings, within 5 minutes), BioKey comes back to the
  front by itself, where before it took Back twice. The check that finishes setup is titled “Finish
  setting up BioKey”. Help with Android blocking the switch is a card under the steps, not a step:
  “Allow restricted settings” from the start where BioKey can tell Android is blocking it; otherwise
  “If Android wouldn’t let you turn it on”, once you come back from Android’s settings with the
  service still off. The Accessibility step says what to tap (BioKey under “Downloaded apps”, then
  the switch, where Android opens its list), and its button reads “Open Accessibility settings”. The
  “What it keeps” part of each disclosure is set out in short lines and names the new records (a
  match that couldn’t be proved, and the passphrase being set, changed or removed); the
  accessibility description in Android’s settings is split into paragraphs, and says a backup you
  save leaves BioKey too. The line on window titles now says they’re also used to tell which app is
  on screen when an app hides its screens from accessibility services, as they already were. Neither
  reads anything new.
- **Backup and restore:** a new backup password is typed twice, and a password manager can offer to
  save it. Backing up says your passphrase isn’t in the file; restoring says this phone keeps the
  passphrase it has. A restore that leaves “Face or fingerprint only” on with no passphrase set
  offers to set one (“Set a passphrase too?”), where a face or fingerprint is there to set it with,
  since until then the screen lock is what opens locked apps and BioKey when no face or fingerprint
  can be used. A backup is written only if BioKey is still unlocked once you’ve chosen where to save
  it: if it locked itself meanwhile, nothing is written, the empty file is deleted and BioKey says
  “Backup cancelled. Try again.” The password you typed is held only in memory, and wiped the moment
  BioKey locks itself, rather than waiting there for the file picker to answer.
- **The diagnostic log viewer** shows times as the phone does (12 or 24 hours), and apps by name
  with the package in brackets (“Calendar (com.google.android.calendar)”). The log itself, and
  what you share, still names apps by package only, and spells out lengths of time (“after 30
  seconds”, “paused for 5 minutes”).
- **Other wording:** compatibility mode’s notification reads “Compatibility mode · covering locked
  apps as they open”; after BioKey’s storage is cleared, setup says what waits a day, and that the
  storage was cleared some time after BioKey last ran (it read as if the time shown was when), and
  from when you can restore a backup or set a passphrase (“The wait is in case someone else reset
  BioKey to get past its locks”). The diagnostic log’s share button and Android’s share sheet both
  say “Share the diagnostic log”, and every description of the log says that it also logs pauses,
  Security activity, waits after wrong passphrases and backups. Wherever a sentence names a setting,
  it names it in quotes (“Failed unlock alerts”, “Diagnostic log”). Apostrophes are typographic (’)
  throughout, as the quotes already were.
- **Renamed:** the setting “Also note screen lock unlocks” is “Also record screen lock unlocks”; the
  pause option “Until you turn it back on” is “Until you resume”, as the action that ends it is
  “Resume”; the button that locks the phone after too many tries is “Lock phone”, where it
  was “Lock screen”; and a prompt that didn’t come up says “The unlock prompt didn’t open. Tap
  Unlock to try again.” (it said “The lock screen didn’t open”, though the phone’s lock screen
  wasn’t involved).
- **Security activity’s wording:** its notification category is described as what it carries:
  anything recorded in Security activity, such as failed unlocks, a face or fingerprint added or
  removed, BioKey being reset, your passphrase set, changed or removed and changes made after BioKey
  was opened with your screen lock. A
  record whose time is unknown says “at an unknown time”. The records that protection stopped, that
  there was no screen lock and that a face or fingerprint was added or removed read in lower case
  after their time, as the other lines there do. The records of “Hide notification content”, a
  setting BioKey no longer has, and of the old choice to keep apps unlocked through a screen-off
  until the phone locks, are worded without the old settings’ names. “What locking does” in an app’s
  options promises only what its page gives: what opening the app asks for and what a lock doesn’t
  cover.

## 2.0.0 (14 November 2026)

2.0 rather than 1.0.5, because how an unlock is decided has changed: a face, fingerprint or screen
lock now counts only with proof from the phone’s secure hardware, and “Face or fingerprint only”
can have a way in that isn’t the screen lock (your own passphrase). It also brings a round of
security hardening and the audit fixes made since 1.0.4. Version code 21: codes 7 to 20 were
never released.

- **Proof of a match.** Each unlock prompt now carries a fresh random number (a “challenge”) that
  a key in the phone’s secure hardware has to sign, and the hardware lets that key sign only
  straight after it has checked you (face, fingerprint or screen lock). A match Android reports
  without a correct signature opens nothing (“Couldn’t confirm that was you. Try again.”). The key
  holds nothing about your face or fingerprint. It is no defence on a rooted phone, where BioKey
  itself can be changed. Where the key can’t be made or used (no screen lock, a Keystore that
  fails), prompts run as before and the diagnostic log says so; after two failed signatures in a
  row the check is left off until BioKey next starts, so faulty hardware costs two extra tries,
  never your apps.
- **Your own passphrase** (optional: in BioKey’s settings, the Passphrase row under “Face or
  fingerprint only”, which shows once that’s on).
  With face or fingerprint only, a wet finger or a locked-out sensor used to leave you stuck. A
  passphrase is a second way in that belongs to BioKey alone, so knowing the phone’s screen lock
  isn’t enough. It does nothing while “Face or fingerprint only” is off.
  - Where it’s asked for: the prompt’s button reads “Use passphrase” in place of Cancel (Back
    still cancels; from Android 16 QPR2 it uses the prompt’s own place for another way in); the
    lock cover has “Use passphrase” beside Cancel; and when no face or fingerprint can be used at
    all, BioKey asks for the passphrase straight away instead of allowing the screen lock. Without
    a passphrase, the screen lock is still allowed then, as before.
  - Setting, changing or removing it asks for your face or fingerprint first. With no face or
    fingerprint to use, it can only be removed, not set or changed, and removing it then asks for
    the passphrase itself. The same goes for everything else in BioKey that asks who you are
    (switching “Face or fingerprint only” off, clearing Security activity, restoring a backup):
    while the passphrase stands in, the screen lock alone never passes those checks.
  - It is never offered to a password manager, and its fields allow no autofill or copying.
  - At least 8 characters. Wrong tries are counted where a restart can’t reset them: the 5th, 6th
    and 7th wrong try in a row each mean a 30-second wait, the 8th and 9th 5 minutes, the 10th and
    every one after 30 minutes. Changing the phone’s date or restarting it doesn’t shorten the
    wait. The right passphrase clears the count.
  - Never stored. BioKey keeps a check value made from it (PBKDF2-HMAC-SHA512, 210,000 rounds,
    with a random salt, then mixed with a key held in the phone’s Keystore, so a copy of the file
    alone can’t be guessed at off the phone), the salt, the round count, the count of wrong tries
    and the wait, in its own storage: never in a backup, never sent anywhere, gone when BioKey’s
    storage is cleared or it’s uninstalled. With failed unlock alerts on, wrong tries are recorded
    like a face or fingerprint that didn’t match (and go, as those do, when the right passphrase
    follows on the same lock screen); an unlock with it reads “then opened with your passphrase”.
  - Forgotten? Nothing is lost: change or remove it in BioKey’s settings with your face or
    fingerprint.
- **Names from other apps are cleaned.** App names, window titles, profile names (typed or from a
  backup) and search text handed to BioKey lose invisible direction-changing characters and
  control characters, and are cut to a sensible length, before BioKey shows, records or logs them:
  an app can’t name itself so that the words around its name on a prompt or in Security activity
  read differently. Diagnostic log lines are cleaned the same way.
- **Backups:** a file over 1 megabyte is refused as not a backup before the password is tried, and one
  asking for far more key-derivation rounds than BioKey uses is refused too. Passwords are 8 to 256
  characters. The file is read and written through a file handle BioKey checks first: anything
  that isn’t a document from Android’s picker, or that leads into BioKey’s own storage, is refused.
  A backup with something malformed in it (an entry that isn’t an app, too many profiles) is
  refused rather than half restored, and the opened settings are wiped from memory once read.
- **Locked again after a reinstall:** a locked app reinstalled signed by someone else is still
  only offered, and no longer erases the signature BioKey remembered, so if the real app comes back
  within the 30 days it’s locked again. The 30 days are counted on a clock that changing the
  phone’s date doesn’t move.
- **Over the lock screen:** only the preinstalled camera and phone apps count as the lock-screen
  camera and the emergency dialer; a downloaded app set as the default camera is covered as usual.
  A window counts as a screensaver only while screensavers are on and it belongs to the one the
  phone is set to use. The “Lock this app” tile asks you to unlock the phone first when used from
  the lock screen, and BioKey’s tiles don’t show there whether protection is paused or off.
- **Touches and windows:** touches on BioKey’s covers and patches are dropped while another app’s
  window is over them, and BioKey’s own dialogs ignore touches once BioKey has locked itself. A
  window from another app with BioKey’s title isn’t taken for BioKey’s. A face or fingerprint check
  for a change in BioKey that ends after the phone or BioKey has locked doesn’t make the change
  (it says to try again), and the check is cancelled when BioKey leaves the screen.
- **One prompt names and unlocks at most three locked apps** on screen at once; any others stay
  locked. A locked app on another display whose lock screen can’t start there, and that has no
  patch over it, sends that display Home.
- **When something goes wrong, the stricter choice:** if BioKey’s stored profiles can’t be read,
  the default re-lock delay becomes Immediately rather than a longer one. A timed pause lasts at
  most 24 hours. A face or fingerprint change is saved at once and kept until it has been
  recorded, and finishing setup starts watching for changes straight away, even on a phone with
  only a screen lock. Google Password Manager’s own screens reached through Credential Manager
  (managing passwords, its settings, Password Checkup) count as Password Manager, locked if you
  locked it, not as sign-in sheets over other apps.
- **Builds with self-protection** (not the Play version): Android’s security, screen lock, face
  and fingerprint, and private space pages opened from BioKey are no longer let past it without
  asking. Setup skips the “Allow restricted settings” step only for an install from Google Play or
  from a store that came with the phone.
- **Behind the scenes:** two error messages in the system log now give only the kind of error,
  not its details; CI no longer keeps the R8 mapping with its test reports; Dependabot
  waits 7 days before proposing a new version of a dependency; the website loads nothing from
  other sites and sends no referrer.
- **A phone that had the unreleased “2.0.0-fortress” build** (installed on its developer’s own
  phone only, never released, and abandoned) has that build’s leftover files and its two Keystore
  keys deleted when BioKey starts. That build had already discarded the settings before it, so
  such a phone is set up again. Nobody else is affected.
- **A force stop the phone restarted after is recorded** (Android 15 and later). Android can lose
  its record of the stop with the restart, but still says BioKey was left stopped: BioKey’s
  main page then shows that protection was off, until when, though not from when. The note is kept
  until protection is next on, even if BioKey is closed again before that.
- **Compatibility mode covers the app left on screen before the phone is unlocked.** When the
  phone locked as the screen went off (the power button), the app in front was covered only once
  the phone was unlocked again, so it could show for a moment. Its lock screen now waits behind
  the keyguard, as it already did with the accessibility engine. A split screen under a
  screensaver is asked for in one prompt.
- **Compatibility mode coming back while the phone is locked** (starting, or a pause ending)
  covers a locked app that is showing over the lock screen (navigation, a call); it was left
  uncovered until it was next opened.
- **“Re-lock now” from the lock screen’s Quick Settings** (or a pause running out) leaves the
  lock-screen camera open over the lock screen alone, as Android’s own App lock does; it used to
  put the camera’s lock screen behind the keyguard, which closed the camera. In compatibility
  mode it also no longer sends a lock screen that is up over the lock screen, or on its way
  there (an alarm ringing in a locked app), behind it.
- **“Lock this app” works while protection is paused, or before any app is locked**, in
  compatibility mode: the tile asks Android which app is in front, rather than saying there is
  nothing to lock.
- **Compatibility mode comes back by itself** when Android restarts BioKey in the background and
  refuses it its place in the foreground (Android 15 and later): it tries once more under a
  1-pixel overlay, as a start from the background already did, before alerting you.
- **Picture-in-picture stays covered over the lock screen** in compatibility mode on Android 15
  and earlier, where hiding other apps’ overlays also hid BioKey’s own cover.
- **“Lock phone”** (“Lock screen” before 2.0.1), after too many misses, keeps the app covered until
  the phone has locked.
- **Too many wrong fingers is not “fingerprints removed”:** where Android reports the lockout,
  BioKey no longer records a removal (and a day’s hold), nor accepts the screen lock under “Face
  or fingerprint only”.
- **After a change of language,** an app locked with Android’s own App lock too still asks once,
  and “Clear all” in Recents still ends every session. An app installed in a work profile is
  recognised on Android’s prompt as well.
- **What is kept for 30 days is gone from the phone after them:** which apps Android’s own App
  lock was seen locking, and locked apps uninstalled, were only left unused once their 30 days
  were up; they are now deleted, as BioKey starts and each time the phone is unlocked.
- **Waking the phone doesn’t lock everything a second time.** A phone that locked as its screen
  went off was taken to lock again at BioKey’s first look as the screen came back. In
  compatibility mode that could send the lock-screen camera, or a lock screen already up over
  the keyguard (an alarm ringing in a locked app), behind the keyguard.
- **BioKey’s settings and main page:** the “Protection alerts” row says when one of BioKey’s
  alert channels is switched off in Android’s settings, and opens it; a pause sheet, or the
  “Unlocked now” list, left open no longer comes back by itself; long setting names wrap with
  large text instead of being cut short.
- **New-app offers** can no longer replace one another when several apps are installed at once.
- **What BioKey says about itself:** the in-app disclosure, the accessibility service’s
  description, the privacy policy (now dated 14 November 2026) and the README list what BioKey
  keeps and reads (times the phone had no screen lock, which locked apps Android’s App lock was
  seen locking, signing-certificate fingerprints, the camera-over-lock-screen setting in backups),
  and no longer mention notification access. BioKey no longer asks Android which apps handle the
  App lock and notification-access settings pages, which it stopped opening in 1.0.4.

## 1.0.4 (31 October 2026)

- **Re-lock delays hold through the Home gesture:** on a Pixel, swiping up to go Home could count
  as the app being swiped away in Recents, which ended its session at once whatever its re-lock
  delay (“closed from Recents” in the diagnostic log). A Recents card now counts as swiped away
  only if it keeps its width on the way up; one shrinking into its icon is the Home gesture.
- **Apps lock when the phone locks:** no longer a setting, simply how BioKey works. A screen-off
  that Android doesn’t follow with a lock (its own lock delay, Smart Lock keeping the phone
  unlocked) leaves unlocked apps as they were; they lock once the phone does. The “When screen
  turns off” re-lock delay is now called “When the phone locks”.
- **Settings, simplified:** the master switch is now “Protection” (“Lock apps” said too little).
  The “Android’s App lock” row is gone (home still marks the apps Android locks too). “Hide
  notification content” is gone, with BioKey’s notification access: to hide a locked app’s
  notifications, turn them off in Android’s settings, which “What locking does” now opens for
  you. Notifications hidden by the previous version come back on their own within a day.
- **Website:** the privacy policy and help pages are redesigned, and the policy no longer
  describes notification access.
- **Take several apps out of a profile at once:** on a profile’s page, tap “Select” (or long-press
  an app), pick the apps, then “Remove”. One Undo puts them all back; each keeps its own lock
  switch, and each is recorded as taking one out is.

## 1.0.3 (30 October 2026)

- **What locking does:** the first time you lock an app, a sheet says what the lock doesn’t cover
  (notifications without the shield, its widgets, assistants and apps with access) and, for a
  gallery, files or messaging app, which other apps can see the same photos, files or messages.
  Once per app, with “Don’t show again”; always in the app’s options.
- **App list order:** locked first, then recently used (with usage access), then A–Z, fixed while
  you flip switches.
- **Locked again after a reinstall:** a locked app uninstalled with its data and installed again
  within 30 days is locked again in its profile (same developer) or offered (anyone else).
- **“Lock this app” Quick Settings tile:** locks the app you’re in, without opening BioKey.
- **No screen lock:** locked apps open without a check rather than never, recorded and shown on
  home until a screen lock is set again.
- Home suggests adding a face or fingerprint where none is set up; setup’s last step names how
  your phone checks it’s you; Settings links to Android 17’s own App lock (and private space,
  where allowed).
- Self-protection is gone from the Google Play version: Play’s Accessibility API policy doesn’t
  allow an app to stop you switching it off or uninstalling it, outside parental-control and
  enterprise apps. If you had it on, it’s off. Builds from source can still include it (see the
  README).
- The setup screens now say everything the Accessibility service reads (each new window’s title)
  and does (pressing Home or Back for you), and that notification access sees which app posted each
  notification, not only locked apps’ ones, as the privacy policy already did.
- Settings pages BioKey opens go straight to the Settings app, so another app can’t offer to open
  them instead and pose as Settings. Pages that set your screen lock never open anywhere else.
- **Face or fingerprint only** (optional): locked apps and BioKey stop accepting your PIN. If
  someone adds their own fingerprint in Android’s settings, BioKey notices and tells you.
- **Lock all now:** lock every app at once, without waiting for the screen to turn off, from the
  home screen, the status sheet, a new “Lock all” Quick Settings tile, or by long-pressing BioKey’s
  icon. While protection is paused it resumes it; while nothing protects it says so.
- **Unlocked now:** home shows which locked apps are unlocked right now and when each locks again
  (“Re-locks in 4 min”), with Lock and Lock all.
- **Failed unlock alerts** (optional; BioKey asks once): faces or fingerprints that didn’t match on
  a locked app are listed on home, unless yours then matched on the same prompt. Misses nobody
  followed with an unlock are notified the next time you unlock your phone, without naming the
  app; swiped away, it comes back until you’ve reviewed it. Optionally, also note locked apps
  opened with the screen lock. Kept 7 days after you’ve seen them.
- **App locking was off:** if protection is switched off, or BioKey is force-stopped, home shows
  when, from and to, and you’re told at your next unlock. Android stopping BioKey on its own
  doesn’t count. While it’s off, the alert comes back at every unlock. BioKey’s storage cleared in
  Android’s settings (every lock and record with it; Android also switches its accessibility
  service off and, from Android 13, takes back its notification permission) is said on BioKey’s
  setup screen when it’s next opened, then recorded once it’s set up. On Android 12 it’s also told
  once BioKey next runs, and every so often until set up (from Android 13, once notifications are
  allowed again). Neither a changed clock nor an
  update since hides a clearing, and changes made in the day after say it was a reset. Like a face or fingerprint change,
  the reset then holds records, alerts, the log and backups for a day.
- Home’s header says “Apps locked”, not “Protected”, while locked apps’ notifications show in full
  (notification access off under “Hide notification content”), and only one card is ever urgent.
- Clearing security activity, switching failed unlock alerts or the diagnostic log off, deleting
  the log, or restoring any backup, needs your face or fingerprint: someone who only knows
  your PIN can’t erase the record. Changing the phone’s date can’t hide or delete it either.
- Security activity also records a face or fingerprint added or removed on the phone (not
  clearable), and anything that weakens protection done from BioKey opened with the screen lock
  (a pause, an app’s lock off, a profile off or deleted). Home warns if BioKey’s alerts are muted,
  and Settings reminds you to lock Settings while face or fingerprint only is on.
- If a face or fingerprint is set up but the sensor can’t be used, clearing records, turning
  alerts off and restoring a backup wait for it, rather than taking the PIN. Reviewing records
  only counts as seen when BioKey was opened with a face or fingerprint.
- **Stop reminding** (when app locking is switched off on purpose) stays stopped across restarts,
  the card says so, and **Remind me** undoes it; a different failure later is reminded about
  again. **Lock Android Settings too** shows only with face or fingerprint only on, can be put
  away, locks Settings even from a profile that’s off, and has Undo. The notification shield
  always shows its explanation before it’s switched on, and a restored backup doesn’t switch it on
  by itself (BioKey says so).
- Opened with the PIN, Review offers **Mark reviewed** (with a face or fingerprint) instead of
  silently counting as seen. Records age on the clock since the phone started, so they age on
  Android 12 too, and a date set ahead can’t age them early.
- Settings: face or fingerprint only and new-app offers moved to the App lock group; Hide
  notification content says what it does until it’s on.
- Card buttons wrap at large text instead of squeezing. Settings rows that open a page show a
  chevron, and the ones that leave BioKey (your browser, Android’s settings) an open-in-new mark. The snackbar spans the list,
  and the home header’s constellation keeps clear of the tiles’ labels.
- Notification access switched off while Hide notification content is on is recorded and told,
  and home says notifications aren’t hidden until it’s back.
- BioKey’s text, its notifications and the records of changes are worded from Android’s string
  resources, ready for translation (records store what changed, not a sentence).
- A face or fingerprint added stays new for a day, whatever Review says. A force stop whose start
  can’t be told says so. On Android 14 and later a force stop right after an update is recorded.
  Notification access switched off under the shield is recorded from and to.
- Settings: Hide notification content can always be switched off; Protection alerts opens its
  own settings; Lock Android Settings has a Lock button. The lock screen scrolls instead of running
  under the status bar in landscape. Setup’s constellation keeps clear of its title.
- Auto-sort never files an app you locked into a profile that’s off.
- For a day after a face or fingerprint is added or removed, nothing proves it’s you in BioKey:
  changes are recorded, nothing is marked seen, and nothing that clears or silences its records
  (or restores a backup) can be done. Clear only ever removes what Review showed. Switching hiding
  off, from home or Settings, is recorded from a screen-lock session and ends the revoked-access
  record then and there; Undo switches it back on and reopens that record (the switch-off stays
  recorded: notifications may have been read meanwhile). Stopping the reminders is recorded too,
  and a flood of records can’t push a face or fingerprint change out. Review offers to open
  Android’s security settings. On tablets, Settings opens beside home (its tile shows it’s open;
  the pane closes with ×). Setup lists the steps in the order they’re done and highlights the one
  the main button does. The accessibility-shortcut warning shows in every build.
- BioKey is called BioKey everywhere, Android’s Settings included (it was “Bio Key” there).
- The accessibility service ignores what it sees until setup is done; sharing the diagnostic log
  asks for your face or fingerprint (your screen lock if it can’t be used), and is recorded from a
  screen-lock session. Setup finishes by itself once a screen lock it needed is set. BioKey’s main
  screen keeps its state through rotation, folding and dark-mode switches.
- Reviewed security activity is shown calmly; only something new makes the card urgent. Review
  says until when a face or fingerprint change stays new. Moving or adding a locked app into a
  profile that re-locks later, or taking it out of one, is recorded.
- The permission explanations link to the privacy policy, and say what BioKey keeps.
- When nothing is protecting your apps, home says so at the top, with the one thing to do.
- Settings has the privacy policy and help (About).
- **New apps:** an app you install that auto-sort doesn’t lock gets a “Lock it?” notification;
  several at once gather under one.
- BioKey’s lock screen and the lock cover now share one Unlock button: the same width, the same
  fingerprint glyph.
- Settings pages open only in the phone’s own Settings app; if it doesn’t have one, BioKey says
  so instead of opening whatever else answers.
- Clearer app list: no more “App” under every app, empty profiles don’t say “· 0”, and the default re-lock delay is stated once,
  in the “Locked” header; a row shows a delay only when it differs. On tablets the header and the
  profile chips run edge to edge. Header labels shrink to fit with large text instead of breaking
  mid-word.
- The Play version’s Accessibility service no longer asks Android for screen elements’ view IDs:
  only self-protection used them.
- **Android 17’s own App lock:** an app locked in both asks once where BioKey is sure. BioKey
  lowers its cover so Android’s prompt shows; once Android lets the app through it doesn’t ask
  again, if Android’s prompt named the app (one app on the phone of that name) and the app came
  as the prompt went. It still asks with face or fingerprint only, or with failed unlock alerts,
  on. Apps seen locked by Android are remembered for 30 days. An app only Android locks is left
  alone.
- **Let the camera open over the lock screen** (on for new setups, as in Android’s own App lock;
  a phone set up before keeps it off until you switch it on): the lock-screen camera and the
  emergency dialer aren’t covered while the phone is locked, and are covered once it’s unlocked
  with them in front. Off, they’re covered as before.
- **One prompt for every locked app on screen:** two locked apps in split screen (or one on
  another display) are asked for together (“Notes and Diary”) and unlocked together.
- **Lock apps when the phone locks** (optional): apps stay unlocked through a screen-off that
  Android doesn’t follow with a lock (its lock delay, Smart Lock). The default is unchanged: they
  lock when the screen turns off.
- The cover is light or dark as the locked app’s own launch screen is.
- If the cover can’t go up and Android refuses the lock screen without a word, the app is sent
  Home, and the diagnostic log says so.

## 1.0.2 (26 October 2026)

- An app in a profile that’s switched off now shows its lock switch off (and greyed out), and is
  listed under “Not locked”. Before, its switch still showed on although the app wasn’t locked.
  Turning the profile back on brings back each app’s own choice, as before.
- Deleting a profile switches its apps off, with an Undo that brings the profile back. Before, they
  kept their switches, so deleting a profile that was off locked all its apps.

## 1.0.1 (24 October 2026)

- The default re-lock delay in Settings now applies to apps in profiles too. Profiles follow it
  unless you give one its own delay (its re-lock sheet has “Same as Settings”). Before, built-in
  profiles kept their own delays (immediately, or 5 minutes for Work), and as auto-sort files most
  apps into them, changing the default did almost nothing.
- The search bar no longer slides under the status bar when you scroll the app list.

## 1.0 (24 October 2026)

The first release on Google Play, as `com.nbdesigns.biokey`. Android 12 and later.

- Locks the apps you choose behind a strong face unlock, fingerprint, or your PIN, pattern or
  password, through Android’s own prompt.
- Two ways of working: the accessibility service (the default, which covers a locked app at the
  tap) and compatibility mode (usage access and display over other apps), used when Android’s
  Advanced Protection reserves accessibility for accessibility tools. BioKey switches between them
  by itself.
- Sessions: an unlocked app stays unlocked while it’s on screen, and through short trips to helper
  apps (authenticators, pickers, the camera, Settings) and interruptions. It locks again after its
  re-lock delay (immediately, 30 s, 1, 5 or 15 min, or at screen off). Locking the phone locks
  everything.
- Profiles with their own re-lock delays and on/off switches, and optional auto-sort into Finance,
  Social and Work.
- Optional notification shield: a locked app’s notifications are hidden until it’s unlocked.
- BioKey locks itself, and optional self-protection asks you to confirm it’s you before BioKey can
  be switched off or uninstalled.
- Pause from the app or a Quick Settings tile; an alert if protection stops.
- Password-protected backup and restore (AES-256-GCM, PBKDF2-SHA256).
- An on-phone diagnostic log of BioKey’s lock decisions, kept for 3 days, which can be turned off.
- Split screen, picture-in-picture, desktop windows, other displays and foldables’ cover and main
  screens.
- No internet access, no analytics, no data collected.
