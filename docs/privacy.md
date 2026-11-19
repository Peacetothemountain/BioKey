---
layout: default
title: Privacy policy
slug: privacy
permalink: /privacy/
description: BioKey collects no data and has no internet access. What it can see on your phone, what it keeps, and why.
---

<header class="page-head">
  <div class="wrap">
    <p class="eyebrow">Privacy policy</p>
    <h1>Nothing leaves your phone unless you send it</h1>
    <p class="lead">BioKey is an app lock for Android, made by NB Designs. It keeps the apps you choose behind your face, fingerprint or screen lock. This policy explains what BioKey can see on your phone, what it does with it, and what it keeps.</p>
    <p class="page-head__meta">
      <span>Effective <time datetime="2026-11-19">19 November 2026</time></span>
      <span>Describes BioKey 2.0.1 and later</span>
    </p>
  </div>
</header>

<div class="wrap">
<h2 class="glance-title" id="at-a-glance">At a glance</h2>
<ul class="glance">
  <li>
    <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M5.6 5.6l12.8 12.8"/></svg></span>
    <div>
      <h3>BioKey collects no data</h3>
      <p>NB Designs receives nothing from the app: no personal information, no usage data, no crash reports, no device identifiers.</p>
    </div>
  </li>
  <li>
    <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M2.5 8.5a15 15 0 0 1 19 0"/><path d="M6 12a10 10 0 0 1 12 0"/><path d="M9.5 15.5a5 5 0 0 1 5 0"/><path d="M12 19h.01"/><path d="M3 3l18 18"/></svg></span>
    <div>
      <h3>BioKey has no internet access</h3>
      <p>It doesn’t request Android’s <code>INTERNET</code> permission, so it can’t send anything off your phone.</p>
    </div>
  </li>
  <li>
    <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M3 3l18 18"/><path d="M10.6 10.6a2 2 0 0 0 2.8 2.8"/><path d="M9.9 5.1A10 10 0 0 1 12 5c5 0 9 4 10 7a11 11 0 0 1-3 4"/><path d="M6.6 6.6A11 11 0 0 0 2 12c1 3 5 7 10 7a10 10 0 0 0 4-.8"/></svg></span>
    <div>
      <h3>No analytics, no ads, no third parties</h3>
      <p>BioKey contains no analytics, advertising or tracking code, and no third-party services.</p>
    </div>
  </li>
  <li>
    <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M11 18h2"/><path d="M9.5 10.5l1.75 1.75L14.5 9"/></svg></span>
    <div>
      <h3>Everything stays on your phone</h3>
      <p>What BioKey keeps (your locked apps and settings, its Security activity records and a short diagnostic log) stays in its private storage. Only a backup file you save or a log you share yourself can leave it.</p>
    </div>
  </li>
</ul>
</div>

<div class="wrap policy" markdown="block">

<nav class="toc" aria-label="On this page">
<p class="toc__title">On this page</p>
<ol>
<li><a href="#what-biokey-can-see">What BioKey can see, and why</a>
<ol>
<li><a href="#accessibility-service">Accessibility service</a></li>
<li><a href="#compatibility-mode">Compatibility mode</a></li>
<li><a href="#face-fingerprint-and-screen-lock">Face, fingerprint and screen lock</a></li>
<li><a href="#installed-apps">Installed apps</a></li>
<li><a href="#other-permissions">Other permissions</a></li>
</ol>
</li>
<li><a href="#what-biokey-stores">What BioKey stores, and where</a>
<ol>
<li><a href="#your-settings">Your settings</a></li>
<li><a href="#unlock-sessions">Which apps are unlocked</a></li>
<li><a href="#uninstalled-apps">Locked apps that were uninstalled</a></li>
<li><a href="#your-passphrase">Your passphrase</a></li>
<li><a href="#what-locking-does">“What locking does”</a></li>
<li><a href="#androids-app-lock">Android’s own App lock</a></li>
<li><a href="#failed-unlock-alerts">Failed unlock alerts</a></li>
<li><a href="#times-off-and-changes">Times protection was off, and other changes</a></li>
<li><a href="#diagnostic-log">Diagnostic log</a></li>
<li><a href="#system-log">System log</a></li>
</ol>
</li>
<li><a href="#backups">Backups</a></li>
<li><a href="#sharing">Sharing</a></li>
<li><a href="#deleting-your-data">Deleting your data</a></li>
<li><a href="#children">Children</a></li>
<li><a href="#security">Security</a></li>
<li><a href="#changes">Changes to this policy</a></li>
<li><a href="#contact">Contact</a></li>
</ol>
</nav>

<div class="prose" markdown="block">

This policy describes BioKey 2.0.1 and later, as published on Google Play (`com.nbdesigns.biokey`) and built from its source code at [github.com/Peacetothemountain/BioKey](https://github.com/Peacetothemountain/BioKey). Earlier versions keep less (they have no passphrase and no unlock-proof key); the policy for each is in the repository’s history.

<h2 id="what-biokey-can-see">What BioKey can see, and why</h2>

Before asking for any of the special permissions below, BioKey explains inside the app what it reads and why, and goes on only if you tap **Agree**. You can turn each of them off at any time in Android’s settings.

<h3 id="accessibility-service">Accessibility service</h3>

By default BioKey uses Android’s Accessibility API, through a service called “BioKey”. BioKey isn’t an accessibility tool, and tells Android so.

**What it reads:**

- Which app is on screen.
- The label of what you tap on the home screen, in its search or in Recents (an app’s name, for example), so a locked app can be covered before it opens.
- The app names on Recents cards, only while an app you’ve locked is unlocked, so closing it from Recents locks it again.
- Window titles, to tell which app is on screen when an app hides its screens from accessibility services, and to tell Google Password Manager’s own screens from sign-in sheets over other apps.
- On Android 17, the words on Android’s own App lock prompt (that window only), to find which app it names, so an app locked in both BioKey and Android asks you only once.

**What it does:** it covers a locked app until you unlock it. It can press Home or Back to leave a locked app you didn’t unlock, lock the phone when you tap “Lock phone” after too many tries, and expand a locked app’s picture-in-picture window so it can be covered.

**What it never does:** it doesn’t read what you type, your messages or anything else in your apps, and it doesn’t record the screen. What it reads is used at that moment, on your phone, and isn’t kept. The only trace is the diagnostic log (below), which names the locked apps BioKey acted on, never what was on screen.

Builds made from the source code (not the Google Play version) can include self-protection, off by default. Turned on, it also reads the text of Android’s Settings pages about BioKey (its App info page, its accessibility switch and the uninstall dialog), so BioKey can ask you to confirm it’s you before it is switched off or uninstalled.

<h3 id="compatibility-mode">Usage access and display over other apps (compatibility mode)</h3>

When the accessibility service can’t run (under Android’s Advanced Protection, for example), BioKey uses compatibility mode instead.

- **Usage access** tells BioKey which app was just opened while the screen is on, so it can cover locked apps. BioKey also uses it to put the apps you used in the last 24 hours first in its list, and to find the app opened last for the “Lock this app” tile. All of this is read when needed, held only in memory and never kept.
- **Display over other apps** lets BioKey draw its cover over a locked app. It reads nothing.
- While compatibility mode runs, Android requires BioKey to show a low-priority notification.

<h3 id="face-fingerprint-and-screen-lock">Face, fingerprint and screen lock</h3>

Your face, fingerprint and screen lock are checked by Android’s own prompt (`BiometricPrompt`). BioKey never sees or stores your face, your fingerprint or your PIN, pattern or password. Android tells it only whether the check passed, whether it was a face or fingerprint or the screen lock, and when a face or fingerprint didn’t match (not whose it was). BioKey accepts your screen lock, or a face or fingerprint that Android rates as strong.

Your passphrase, if you set one, is the exception: you type it into BioKey itself. BioKey checks it on the phone and never keeps it (see [Your passphrase](#your-passphrase)).

BioKey keeps up to three keys in Android’s Keystore, in the phone’s secure hardware where it has some. None of them holds anything of yours, or anything about your face or fingerprint:

- One that Android destroys when a face or fingerprint is added or removed, so BioKey can tell that happened.
- One that signs a fresh challenge at each unlock prompt, which the hardware allows only straight after it has checked you, so BioKey can be sure a match really happened. If the phone can’t make or use this key, BioKey takes Android’s word for the match and records that in Security activity. While your passphrase stands in for the screen lock, it never does: you use your passphrase instead.
- If you set a passphrase, one mixed into its check value, so that value is useless off the phone.

BioKey also keeps whether a strong face or fingerprint was set up when it last looked.

<h3 id="installed-apps">Installed apps</h3>

BioKey can see the names and icons of the apps you can open, so you can choose which to lock. It also checks which home screens, keyboards, file and photo pickers, galleries and cameras are installed, which apps answer the lock screen’s camera and emergency shortcuts, and whether certain known apps are installed and where they came from (authenticators, password managers, messaging and email apps, browsers, app stores, Google Play services and Credential Manager). This tells going Home (which locks an app again) from a quick trip to a keyboard, picker or camera (which doesn’t), and lets the lock screen’s camera and emergency dialer open. It is used on the phone and held only in memory.

For each app you lock, BioKey keeps a SHA-256 code of its signing certificate (public information about the app, not about you), to recognise the same app if it’s reinstalled. The first time you lock an app, BioKey may check whether it has home-screen widgets, or which other apps can read the same photos, files or messages, to tell you; that isn’t kept. When you install an app, BioKey can offer to lock it (turn this off with “Offer to lock new apps” in its settings). BioKey doesn’t request access to the full list of installed packages (`QUERY_ALL_PACKAGES`).

<h3 id="other-permissions">Other permissions</h3>

- **Notifications** (`POST_NOTIFICATIONS`): an alert if protection stops (repeated quietly while it stays off, unless you stop the reminders); Security activity, shown the next time you unlock your phone, never naming an app and never on the lock screen; offers to lock apps you’ve just installed; and “Protection is paused”, with a Resume button.
- **Start at boot** (`RECEIVE_BOOT_COMPLETED`): to start compatibility mode after a restart, or warn you if protection is off.
- **Advanced Protection status** (`QUERY_ADVANCED_PROTECTION_MODE`): to know when to switch to compatibility mode.
- **Hide other apps’ overlays** (`HIDE_OVERLAY_WINDOWS`): so other apps can’t draw over BioKey’s own screens.
- **Foreground service** (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`): keeps compatibility mode running while the screen is on.

<h2 id="what-biokey-stores">What BioKey stores, and where</h2>

Everything below is kept in BioKey’s private storage on your phone. Other apps can’t read it, and Android encrypts it with the rest of the phone’s storage. It is excluded from Google’s cloud backup and from device-to-device transfer.

<h3 id="your-settings">Your settings</h3>

Which apps you’ve locked, your profiles and their re-lock delays, whether protection is paused, your other settings, and which suggestions on BioKey’s main page you’ve answered.

<h3 id="unlock-sessions">Which apps are unlocked</h3>

Held only in memory. Every unlocked app locks again when the phone locks. For the “Lock this app” tile, BioKey also holds the last app on screen in memory.

<h3 id="uninstalled-apps">Locked apps that were uninstalled</h3>

When a locked app is uninstalled, its package name, certificate code, profile and the time are kept for 30 days, so installing it again locks it again (or, if someone else signed it, BioKey offers to). If the phone’s date can’t be checked after a restart, one can be kept longer; at most 200 are kept. They aren’t included in backups.

<h3 id="your-passphrase">Your passphrase</h3>

Only if you set one, in BioKey’s settings under “Face or fingerprint only”. BioKey never keeps the passphrase itself. It keeps a check value made from it (PBKDF2-HMAC-SHA512 with 210,000 rounds and a random salt, mixed with the Keystore key above), the salt, how many wrong tries there have been in a row, and when the next try is allowed. None of it is in backups, and removing the passphrase deletes all of it.

While “Face or fingerprint only” is on and a passphrase is set, the passphrase replaces your screen lock wherever BioKey would otherwise accept it.

<h3 id="what-locking-does">“What locking does”</h3>

Which apps you’ve already been shown “What locking does” for (up to 300), and whether you chose “Don’t show again”.

<h3 id="androids-app-lock">Android’s own App lock</h3>

On Android 17: which of your locked apps Android’s App lock was also seen locking, and when. An app not seen for 30 days is forgotten.

<h3 id="failed-unlock-alerts">Failed unlock alerts</h3>

Off until you turn them on. For each failed unlock on a locked app (or on BioKey’s own prompts that confirm a change): which app, when, how many face, fingerprint or passphrase tries failed, whether face and fingerprint were locked out, and how the app was opened afterwards, if it was. Misses followed by a match on the same prompt were yours, and aren’t kept. With “Also record screen lock unlocks” on, apps opened with your PIN, pattern or password are listed too.

They are listed on BioKey’s main page. Once you’ve reviewed them, each is deleted 7 days after it happened, and at most 50 reviewed ones are kept. Until you review them, up to 500 are kept. Turning the alerts off deletes them.

<h3 id="times-off-and-changes">Times protection was off, and other changes</h3>

For Security activity, BioKey records:

- when protection was off (switched off, or BioKey force-stopped) and when it came back;
- when the phone had no screen lock;
- when BioKey’s storage was cleared;
- when a face or fingerprint was added or removed;
- when “Face or fingerprint only” had to accept the screen lock, because no face or fingerprint could be used (or Android’s settings turned them off for apps) and no passphrase was set;
- when a match couldn’t be proved by the phone’s hardware;
- when your passphrase was set, changed or removed;
- changes that weaken protection, made after BioKey was opened with the screen lock on a phone with a face or fingerprint, or within a day of a face or fingerprint change or of BioKey being reset: pausing protection, switching an app’s lock off, moving a locked app into a profile that locks it less, switching a profile off or deleting it, a longer re-lock delay, letting the camera open over the lock screen, and opening the diagnostic log to share. These name the app or profile involved.

Reviewed records are kept for 7 days (at most 20). A face or fingerprint added or removed can’t be cleared, only aged out. To tell how long protection was off, BioKey keeps when it stopped and the phone’s restart count, and reads Android’s record of why its own process last ended.

For a day after a face or fingerprint is added or removed, or after BioKey is reset, nothing that clears or silences these records can be done: clearing them, turning off the alerts or the diagnostic log, deleting the log, restoring a backup, or setting, changing or removing your passphrase. So someone who has just added their own face or fingerprint can’t erase the record of it.

<h3 id="diagnostic-log">Diagnostic log</h3>

On by default; turn off “Diagnostic log” in BioKey’s settings to stop it. It is a short record of BioKey’s own decisions about the apps you’ve locked (which locked app it covered or unlocked, and why), naming them by package name, such as `com.example.bank`. Apps you haven’t locked are never named. It also notes Security activity as it’s recorded, pauses, failed unlocks (while the alerts are on), passphrase changes and waits, matches that couldn’t be proved, backups, and BioKey’s protection starting and stopping.

It keeps at most 500 entries from the last 3 days. You can view and delete it in BioKey’s settings.

It leaves your phone only if you tap **Share the diagnostic log** and confirm it’s you. Android’s share sheet then lets you choose where it goes (an email to support, for example). A shared log also includes BioKey’s version, your Android version, your phone’s maker and model, and which mode BioKey is working in.

<h3 id="system-log">System log</h3>

The released app writes only short error messages to Android’s system log, which stays on the phone. Test (debug) builds can log more detail, also on the phone; the Google Play version doesn’t.

<h2 id="backups">Backups</h2>

BioKey can save your settings to a backup file and restore them, from its settings, only when you ask.

- **What’s in it:** your locked apps, profiles, re-lock delays and settings. Not the diagnostic log, not Security activity, not your passphrase, and nothing about your face, fingerprint or screen lock. Restoring asks you to confirm it’s you, and never deletes records or changes the passphrase on the phone.
- **How it’s protected:** the file is sealed with a password you choose (at least 8 characters), using AES-256-GCM with a key derived by PBKDF2-SHA256 (600,000 rounds). Without the password the file reveals nothing, not even which apps are locked, and any change to it is detected. If you forget the password, nobody can open the backup, including NB Designs.
- **Where it goes:** wherever you choose with Android’s file picker (your phone’s storage, or a cloud drive app). BioKey doesn’t upload it and NB Designs never receives it. If you save it to a cloud drive, that service’s privacy policy applies to the file.

<h2 id="sharing">Sharing</h2>

BioKey doesn’t share any data with NB Designs or anyone else. The only things that can leave your phone are a backup file and the diagnostic log, and only when you choose to save or share them, to the place you choose.

<h2 id="deleting-your-data">Deleting your data</h2>

- **Uninstall BioKey,** or use **Clear storage** on its App info page: this deletes everything BioKey keeps on the phone.
- **The diagnostic log** can be deleted in BioKey’s settings, or by turning it off.
- **Your passphrase** can be removed in BioKey’s settings, which deletes everything kept about it.
- **Security activity** can be cleared from BioKey’s main page (Review, then Clear), except a face or fingerprint added or removed, which ages out on its own. Turning off “Failed unlock alerts” also deletes failed unlocks. Both ask for your face or fingerprint where one is set up, so someone who knows only your screen lock can’t erase them.
- **Backup files** are yours: delete them wherever you saved them.

NB Designs holds no data about you, so there is nothing to request from us or delete on our side.

<h2 id="children">Children</h2>

BioKey isn’t directed at children under 13, and it collects no personal information from anyone, including children.

<h2 id="security">Security</h2>

BioKey’s own screens can’t be captured in screenshots or recordings, are hidden from Recents previews, and ignore touches that come through windows other apps draw over them. On Android 14 and later, they are hidden from accessibility services that aren’t real accessibility tools. No app lock running as a regular app can defend against everything (a rooted phone, or a computer connected with developer access, for example); BioKey’s limits are listed at [github.com/Peacetothemountain/BioKey#limits](https://github.com/Peacetothemountain/BioKey#limits).

If you find a security problem, please report it privately to [support.nbdesigns@gmail.com](mailto:support.nbdesigns@gmail.com) (see [the security policy](https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md)).

This policy is linked from BioKey’s settings (About, then Privacy policy) and from each explanation BioKey shows before asking for a permission.

<h2 id="changes">Changes to this policy</h2>

If this policy changes, the new version will be published at [peacetothemountain.github.io/BioKey/privacy](https://peacetothemountain.github.io/BioKey/privacy/) and in the `PRIVACY.md` file of BioKey’s repository, with a new effective date. Earlier versions stay in the repository’s history. If a change ever meant BioKey handling your data in a new way, the app would tell you before it happened.

<h2 id="contact">Contact</h2>

Questions about this policy or about BioKey: NB Designs, [support.nbdesigns@gmail.com](mailto:support.nbdesigns@gmail.com).

If you email us, we receive your email address and whatever you choose to send (a shared diagnostic log, for example). We use it only to answer you, keep it no longer than that takes, and delete it on request.

</div>
</div>
