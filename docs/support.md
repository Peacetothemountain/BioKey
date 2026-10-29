---
layout: default
title: Support
permalink: /support/
---

# BioKey support

Email <support.nbdesigns@gmail.com>. Please include your phone's model, your Android version, your
BioKey version (in Android's App info for BioKey), and whether BioKey is using the accessibility
service or compatibility mode (BioKey's main screen says "Compatibility mode" when it's using
that).

If BioKey locked something it shouldn't have, or didn't lock something it should, the diagnostic
log helps: in BioKey's settings, open **View diagnostic log**, tap **Share** and confirm with your
face or fingerprint (your screen lock if it can't be used). Shared from BioKey opened with your
screen lock, it's recorded in its security activity. It lists the apps
you've locked by package name, and nothing about apps you haven't. Read it before you send it.

Bugs and feature requests can also go to
[GitHub issues](https://github.com/Peacetothemountain/BioKey/issues). Security problems: please
report them privately by email, not in a public issue
([security policy](https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md)).

In Android's Settings, BioKey's accessibility service is **BioKey App Lock Service**, and its
notification access **BioKey notification shield**.

## Setting up

### Android says "Restricted setting" when I turn on the accessibility service

This only happens when BioKey was installed from a file instead of Google Play. Android 13 and
later block accessibility and notification access for apps installed that way until you allow it:

1. Try to turn on **BioKey App Lock Service** once in Settings > Accessibility, and close the
   "Restricted setting" message.
2. Open Settings > Apps > **BioKey**, tap ⋮ (top right), then **Allow restricted settings**, and
   confirm with your screen lock. The ⋮ item only appears after step 1.
3. Go back to Settings > Accessibility and turn the service on.

BioKey's setup shows this step when it applies. Installing from
[Google Play](https://play.google.com/store/apps/details?id=com.nbdesigns.biokey) avoids it.

### Where is the accessibility switch?

Settings > Accessibility, then **BioKey App Lock Service**. On some phones it's under
**Downloaded apps** or **Installed apps**. BioKey's setup and its main screen open the right page
for you.

Before Android's page opens, BioKey shows what the service reads and what it's for, and continues
only if you tap **Agree**. The [privacy policy]({{ '/privacy/' | relative_url }}) says the same in
more detail.

### Advanced Protection is on, and BioKey asks for usage access instead

That's compatibility mode. On Android 17, Advanced Protection reserves the accessibility service
for accessibility tools (screen readers and the like), and switches BioKey's off. BioKey then
works through two other permissions:

- **Usage access**, which tells it which app was just opened.
- **Display over other apps**, which lets it cover a locked app as it opens.

BioKey switches between the two ways by itself, including when you turn Advanced Protection on or
off. In compatibility mode:

- A locked app is covered as it opens rather than at the tap, so an app that was still in memory
  can show for about half a second first.
- Android requires a low-priority notification while it runs.

Both permissions are needed: if either is turned off, BioKey stops locking and sends you an alert.

### Do I need to allow notifications?

They're optional, but without them BioKey can't tell you if app locking stops, and the
notification shield can't show its "hidden notifications" entries. BioKey's setup asks for
them; you can also allow them in Settings > Apps > **BioKey** > Notifications.

## Notifications

### How do I hide a locked app's notifications?

Turn on **Hide notification content** in BioKey's settings, and allow notification access for
**BioKey notification shield** when Android asks. A locked app's notifications are then replaced
by one "N hidden notifications" entry until you unlock the app. Tapping it asks you to unlock, then
opens the notification. Calls, alarms, media and navigation always show.

A notification can still flash briefly as a heads-up, or on a watch, before BioKey hides it.

If notification access is switched off while this is on, locked apps' notifications show in full
again: BioKey records it, tells you at your next unlock, and home shows **Notifications aren't
hidden** with **Allow access** until it's back.

### My hidden notifications didn't come back

Unlocking the app brings them back. To bring them all back, switch off **Hide notification
content** in BioKey's settings. Don't revoke notification access instead: that can leave them
hidden for up to a day. If Notification history is on, Settings > Notifications > Notification
history lists them in the meantime.

## Quick actions and alerts

### Someone knows my PIN. Can they open my locked apps?

Turn on **Face or fingerprint only** in BioKey's settings (it appears once a face or fingerprint is
set up on your phone): locked apps and BioKey then don't offer the PIN at all. Lock **Settings**
too (BioKey offers): with your PIN, someone could otherwise add their own fingerprint there.
Android can't be stopped from allowing that, but BioKey notices and tells you ("a face or
fingerprint was added or removed"). Home suggests **Lock Android Settings too** while the option
is on; **Not now** puts the suggestion away until you next turn the option on, and BioKey's
Settings keeps it under the option. If Settings is in a profile that's switched off, locking it
takes it out of that profile (Undo puts it back). After too many failed tries, Android pauses face and
fingerprint for a while; if it locks them out for good, lock your phone and unlock it with your PIN
to bring them back. With the option off, **Failed unlock alerts** and **Also note screen lock
unlocks** at least show you when it happened.

### How do I lock everything at once?

Before handing your phone to someone: long-press BioKey's icon and tap **Lock all now**, or add the
**Lock all** tile to Quick Settings (swipe down twice, tap the pencil). Every locked app asks
again the next time it opens. It needs no authentication, since it only locks. If protection was
paused, it resumes it.

### What are failed unlock alerts?

When you turn them on (BioKey asks once on its home screen, or Settings > Failed unlock alerts),
BioKey notes each face or fingerprint that didn't match on a locked app. If yours then matches on
the same unlock prompt, those misses were yours and the note goes. Misses followed by a later
unlock stay on the list, marked with how the app was opened, since someone may have tried and
handed the phone back. Misses no face or fingerprint followed (unanswered, or followed by the PIN)
are also notified, the next time you unlock your phone and at least 2 minutes later, without
naming the app; the notification stays until you open **Review** in BioKey opened with your face
or fingerprint (opened with your screen lock, tap **Mark reviewed** there and use your face or
fingerprint).
A face or fingerprint added or removed stays new for a day either way, and its alert comes back
at each unlock until then: whoever made the change can't hide it from you. Review says until when;
meanwhile nothing proves it's you in BioKey: changes are recorded, nothing is marked seen, and
nothing that clears or silences its records can be done (Clear, turning the alerts, their screen
lock option or the log off, restoring a backup). After the day, open Review again. Only faces and fingerprints count: Android doesn't tell apps about a wrong PIN, pattern
or password. If you never use your PIN yourself, turn on **Also note screen lock unlocks** to see
(listed on the home screen, not notified) whenever someone opens a locked app with it. Notes are kept until you've seen them, then 7 days;
clear them from **Review** on the home screen (it asks for your face or fingerprint).

### BioKey says "App locking was off"

Someone (maybe you) switched BioKey off in Android's settings, revoked one of its permissions, or
force-stopped it, and locked apps could open without a check meanwhile. The card shows when.
Android stopping BioKey on its own (low memory, a crash) or a restart of the phone doesn't count.
Some phone makers' battery savers force-stop apps the way a person would, and those do show:
see "Locking stopped" below to keep BioKey running. Clear it from **Review**.

If BioKey's storage was cleared in Android's settings (Apps > BioKey > Storage), every lock and
record went with it. Android also switches BioKey's accessibility service off and, from Android
13, takes back its notification permission, so nothing is locked and BioKey can't tell you until
you open it: its setup screen then says when (on Android 12, an alert also comes after the phone's
next restart). Once set up again, it's recorded. For a day after, as after a face or fingerprint change, records can't be cleared and
the alerts can't be switched off: whoever cleared it may have added their own fingerprint first.
(Android must still remember BioKey running since its last update; an uninstall removes that, but
then BioKey's icon is gone too.)

If you switched it off on purpose, **Stop reminding** on the "App lock is off" card (it asks for
your face or fingerprint) ends the reminders until app locking is back on; the card then says
so, and **Remind me** brings them back. It stays recorded. If app locking fails some other way
afterwards (switched on, but not running), the reminders come back on their own.

### BioKey offered to lock an app I just installed

Auto-sort locks the banking, social and work apps it recognises (an app you locked yourself isn't
moved into a profile that's switched off, which would unlock it). Anything else you install gets a
notification with a **Lock** button. Turn it off in Settings > Offer to lock new apps.

## Backup and restore

### How do I keep my setup when I reinstall BioKey or change phones?

BioKey's settings aren't included in Android's own backup or phone-to-phone transfer, so save a
backup file first:

1. In BioKey's settings, tap **Back up settings**.
2. Choose a password of at least 8 characters. You'll need it to restore; it can't be recovered.
3. Choose where to save the file. Pick somewhere that survives an uninstall or a new phone: your
   Downloads folder, or a cloud drive.

After installing BioKey again (or on the new phone) and finishing setup, tap **Restore from a
backup** in its settings, pick the file and enter the password, and confirm with your face or
fingerprint (your screen lock if none is set up). Within a day of a face or fingerprint being added
or removed, BioKey refuses to restore, and says until when. It replaces your locked apps, profiles and settings with the backup's. The
notification shield stays off on a new phone, and BioKey says so: turn on **Hide notification
content** in its settings (and agree to the explanation) to hide notifications there too.

The file holds your locked apps, profiles and BioKey settings, sealed with your password. Without
the password it reveals nothing, not even which apps are locked.

### I forgot my backup password

It can't be recovered, by you or by us: the password is the only key to the file. Make a new
backup with a new password.

## When something goes wrong

### Locking stopped

Open BioKey; its main screen says what's wrong, and BioKey sends an alert when protection stops
(if notifications are allowed). Common causes:

- **"App lock is off":** the accessibility service is switched off. Turn it back on in Settings >
  Accessibility > **BioKey App Lock Service**.
- **"App lock isn't running":** the accessibility service is switched on, but Android has stopped
  it. Turn it off and on again in Settings > Accessibility.
- **"Advanced Protection is on", or an alert that compatibility mode lost a permission:** usage
  access or display over other apps is missing. Open BioKey and tap **Set up compatibility mode**,
  or grant it again.
- **"Protection paused":** tap **Resume protection** in BioKey, or use its Quick Settings tile.
- **The app isn't locked:** check that its own switch is on, and that its profile is on.
- **Safe mode:** Android turns off every downloaded app in safe mode, BioKey included. Restart the
  phone normally.
- **Your phone maker's battery saver stopped it.** Some phones stop apps in the background on
  their own. Let BioKey run freely:
  - **Samsung:** Settings > Battery > Background usage limits: take BioKey out of "Sleeping apps"
    and "Deep sleeping apps"; in BioKey's App info > Battery, choose **Unrestricted**.
  - **Xiaomi, Redmi, POCO (HyperOS, MIUI):** in BioKey's App info, turn on **Autostart**, and set
    Battery saver to **No restrictions**.
  - **OPPO, OnePlus, realme, vivo:** in BioKey's App info > Battery, allow **background activity**
    (and "Auto launch" where offered).
  - **Any phone:** in BioKey's App info > App battery usage, choose **Unrestricted**.

If none of these fits, please email us with the diagnostic log (see the top of this page).

### A locked app showed for a moment before it was covered

Some ways of opening an app can't be covered at the tap: links from other apps, notifications,
the quick-switch gesture, and in compatibility mode any app still in memory. Some banking apps
also hide their windows from BioKey. The full list is in the
[Limits](https://github.com/Peacetothemountain/BioKey#limits) section of the README. The app still
can't be used until you unlock it.

### I can't lock my home screen

Home screens can't be locked, so you can always get back Home.

### Face or fingerprint doesn't work

BioKey uses Android's own prompt, and accepts only strong (Class 3) face unlock, fingerprint, or
your PIN, pattern or password. If your phone's face unlock is the weaker kind, BioKey asks for
your fingerprint or screen lock instead. After too many failed attempts, Android locks
biometrics for a while; use your PIN, pattern or password. With **Face or fingerprint only** on,
the PIN isn't offered: wait, and if Android locks face and fingerprint out for good, lock your
phone and unlock it with your PIN to bring them back. If the sensor itself stops working (a fault,
an update pending), BioKey allows your screen lock again for locked apps and records that it did;
clearing records, turning alerts off and restoring a backup wait until the sensor works again.
If it's broken for good, remove your faces and fingerprints in Android's settings: BioKey then
asks for your screen lock instead (and records the removal). On Android 15 and later, the same
applies if **Use for apps** is switched off for face or fingerprint in Android's settings.

### How do I uninstall BioKey?

Switch off **Hide notification content** in BioKey's settings first, so no notifications stay
hidden. Uninstalling deletes everything BioKey kept on the phone; backup files you saved stay where
you saved them.
