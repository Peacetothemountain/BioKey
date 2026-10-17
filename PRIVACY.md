# BioKey privacy policy

_Last updated: 15 October 2026_

BioKey is an app lock. It keeps the apps you choose behind your face, fingerprint or screen lock.
This policy explains what BioKey can see on your phone and what it does with it.

## The short version

- **BioKey collects no data.** Nothing it sees is stored on a server, shared, sold or sent anywhere.
- **BioKey has no internet access.** It does not request Android's `INTERNET` permission, so it
  cannot send anything off your phone.
- **Cloud backups are off.** BioKey's settings are excluded from cloud backup and device-to-device
  transfer. You can save them yourself, to a file protected by a password (see below).

## What BioKey can see, and why

| Access | What BioKey sees | What it's used for |
|---|---|---|
| **Accessibility service** (optional, one of two ways BioKey can work) | Which app is on screen; the name of an app you tap on the home screen, in search or in Recents; the text of system Settings pages about BioKey itself (App info, Accessibility, uninstall). | Covering apps you've locked until you authenticate; optionally asking for your authentication before BioKey can be switched off or uninstalled. |
| **Usage access** and **Display over other apps** (compatibility mode, used when the accessibility service can't run, for example under Android's Advanced Protection) | Which app was just opened (app usage events on this phone). | Covering apps you've locked until you authenticate. |
| **Notification access** (optional) | Notifications from apps you've locked. | Hiding their content until you unlock the app, then putting them back. |
| **Biometric / screen lock** | Nothing: Android's system prompt checks your face, fingerprint or PIN and only tells BioKey "success" or "failure". | Unlocking apps and BioKey itself. |
| **Installed apps** (launchable apps only) | App names and icons. | Showing the list of apps you can lock. |

Before BioKey asks for accessibility, usage access, display over other apps or notification
access, it shows an in-app disclosure explaining the above and continues only if you tap **Agree**.

BioKey never reads what you type, your messages, or what's on screen in other apps.

## What BioKey stores, and where

On your phone only, in BioKey's private storage (encrypted by Android with your screen lock):
which apps you've locked, your profiles and their re-lock delays, and your BioKey settings.
Unlock sessions live only in memory and are wiped whenever the phone locks.

**Diagnostic log** (on by default; Settings > Diagnostics): BioKey's own recent lock decisions,
such as which app it covered and why, with the package names of your locked apps. At most 500
entries from the last 3 days. It stays on your phone and leaves it only if you share it from
Settings, through Android's share sheet, to wherever you choose. Turning it off deletes it.

**Backup files** (only if you make one): your locked apps, profiles and BioKey settings, sealed
with a password you choose (AES-256-GCM, key derived with PBKDF2). You choose where the file is
saved; without the password it reveals nothing, not even which apps are locked.

Uninstalling BioKey deletes all of it.

## Children

BioKey isn't directed at children and collects no personal information from anyone.

## Changes and contact

Changes to this policy will be published in this file in BioKey's repository. Questions:
open an issue at https://github.com/Peacetothemountain/BioKey.
