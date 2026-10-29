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
log helps: in BioKey's settings, open **View diagnostic log** and tap **Share**. It lists the apps
you've locked by package name, and nothing about apps you haven't. Read it before you send it.

Bugs and feature requests can also go to
[GitHub issues](https://github.com/Peacetothemountain/BioKey/issues). Security problems: please
report them privately by email, not in a public issue
([security policy](https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md)).

In Android's Settings, BioKey appears as **Bio Key**, its accessibility service as **Bio Key App
Lock Service**, and its notification access as **Bio Key notification shield**.

## Setting up

### Android says "Restricted setting" when I turn on the accessibility service

This only happens when BioKey was installed from a file instead of Google Play. Android 13 and
later block accessibility and notification access for apps installed that way until you allow it:

1. Try to turn on **Bio Key App Lock Service** once in Settings > Accessibility, and close the
   "Restricted setting" message.
2. Open Settings > Apps > **Bio Key**, tap ⋮ (top right), then **Allow restricted settings**, and
   confirm with your screen lock. The ⋮ item only appears after step 1.
3. Go back to Settings > Accessibility and turn the service on.

BioKey's setup shows this step when it applies. Installing from
[Google Play](https://play.google.com/store/apps/details?id=com.nbdesigns.biokey) avoids it.

### Where is the accessibility switch?

Settings > Accessibility, then **Bio Key App Lock Service**. On some phones it's under
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
them; you can also allow them in Settings > Apps > **Bio Key** > Notifications.

## Notifications

### How do I hide a locked app's notifications?

Turn on **Hide notification content** in BioKey's settings, and allow notification access for
**Bio Key notification shield** when Android asks. A locked app's notifications are then replaced
by one "N hidden notifications" entry until you unlock the app. Tapping it asks you to unlock, then
opens the notification. Calls, alarms, media and navigation always show.

A notification can still flash briefly as a heads-up, or on a watch, before BioKey hides it.

### My hidden notifications didn't come back

Unlocking the app brings them back. To bring them all back, switch off **Hide notification
content** in BioKey's settings. Don't revoke notification access instead: that can leave them
hidden for up to a day. If Notification history is on, Settings > Notifications > Notification
history lists them in the meantime.

## Backup and restore

### How do I keep my setup when I reinstall BioKey or change phones?

BioKey's settings aren't included in Android's own backup or phone-to-phone transfer, so save a
backup file first:

1. In BioKey's settings, tap **Back up settings**.
2. Choose a password of at least 8 characters. You'll need it to restore; it can't be recovered.
3. Choose where to save the file. Pick somewhere that survives an uninstall or a new phone: your
   Downloads folder, or a cloud drive.

After installing BioKey again (or on the new phone) and finishing setup, tap **Restore from a
backup** in its settings, pick the file and enter the password. It replaces your locked apps,
profiles and settings with the backup's.

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
  Accessibility > **Bio Key App Lock Service**.
- **"App lock isn't running":** the accessibility service is switched on, but Android has stopped
  it. Turn it off and on again in Settings > Accessibility.
- **"Advanced Protection is on", or an alert that compatibility mode lost a permission:** usage
  access or display over other apps is missing. Open BioKey and tap **Set up compatibility mode**,
  or grant it again.
- **"Protection paused":** tap **Resume protection** in BioKey, or use its Quick Settings tile.
- **The app isn't locked:** check that its own switch is on, and that its profile is on.
- **Safe mode:** Android turns off every downloaded app in safe mode, BioKey included. Restart the
  phone normally.

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
biometrics for a while; use your PIN, pattern or password.

### How do I uninstall BioKey?

Switch off **Hide notification content** in BioKey's settings first, so no notifications stay
hidden. Uninstalling deletes everything BioKey kept on the phone; backup files you saved stay where
you saved them.
