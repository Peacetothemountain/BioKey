# BioKey privacy policy

_Last updated: 20 October 2026_

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
| **Accessibility service** (optional, one of two ways BioKey can work) | Which app is on screen; the label of what you tap on the home screen, in its search or in Recents; the app names on Recents cards while Recents is open and an app you've locked is unlocked; the text of system Settings pages about BioKey itself (App info, Accessibility, notification access, uninstall). | Covering apps you've locked until you authenticate, even before they open; ending a locked app's session when you close it from Recents; optionally asking for your authentication before BioKey can be switched off or uninstalled. |
| **Usage access** and **Display over other apps** (compatibility mode, used when the accessibility service can't run, for example under Android's Advanced Protection) | Which app was just opened (app usage events on this phone). | Covering apps you've locked until you authenticate. |
| **Notification access** (optional) | Notifications from apps you've locked. | Hiding their content until you unlock the app, then putting them back. |
| **Biometric / screen lock** | Nothing: Android's system prompt checks your face, fingerprint or PIN and only tells BioKey "success" or "failure". | Unlocking apps and BioKey itself. |
| **Installed apps** (launchable apps, home screens, keyboards, apps that pick or capture photos and files, and a few system apps) | The names and icons of launchable apps. Which home-screen apps, keyboards, photo and file pickers, galleries and cameras are installed. Whether Google Play services and Credential Manager are installed, and the Settings app's own labels. | Showing the apps you can lock. Telling going home (which ends a locked app's session) apart from a trip to a keyboard, a photo or file picker or a camera (which doesn't). Recognising Google Password Manager. Recognising BioKey's own Settings pages, for self-protection. All of it only on the phone. |

Before BioKey asks for accessibility, usage access, display over other apps or notification
access, it shows an in-app disclosure explaining the above and continues only if you tap **Agree**.

BioKey never reads what you type, your messages, or the content of the apps you use.

## What BioKey stores, and where

On your phone only, in BioKey's private storage (encrypted by Android with your screen lock):
which apps you've locked, your profiles and their re-lock delays, and your BioKey settings.
Unlock sessions live only in memory and are wiped whenever the phone locks.

**Diagnostic log** (on by default; you can turn it off in Settings > Diagnostics): a short record
of BioKey's own lock decisions about the apps you've locked, such as which locked app it locked or
unlocked and why, naming them by package. It never names apps you haven't locked: going from a
locked app to one of them is logged only as going to "a helper app" or "another app". At most 500
entries, from the last 3 days. It stays on your phone and leaves it only if you share it from
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
