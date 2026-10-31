---
layout: default
title: Help and support
slug: support
permalink: /support/
description: Set up BioKey in a few steps, find answers to common questions, and get in touch with NB Designs.
---

<header class="page-head">
  <div class="wrap">
    <p class="eyebrow">Help and support</p>
    <h1>How can we help?</h1>
    <p class="lead">Set up BioKey in a few minutes, find answers to common questions, or <a href="#contact">get in touch</a>. If BioKey locked something it shouldn’t have, or didn’t lock something it should, its diagnostic log is the quickest way to tell us what happened.</p>
  </div>
</header>

<div class="wrap">
<section aria-labelledby="start-title">
  <h2 id="start-title">Get started</h2>
  <ol class="steps">
    <li>
      <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M4 20h16"/></svg></span>
      <h3>Install BioKey</h3>
      <p>Get it from <a href="https://play.google.com/store/apps/details?id=com.nbdesigns.biokey">Google Play</a>. It needs Android 12 or later.</p>
    </li>
    <li>
      <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>
      <h3>Turn on protection</h3>
      <p>BioKey’s setup opens Settings &gt; Accessibility for you: turn on <strong>BioKey App Lock Service</strong>. Under Advanced Protection, it asks for usage access and display over other apps instead (see <a href="#q-advanced-protection">compatibility mode</a>).</p>
    </li>
    <li>
      <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/></svg></span>
      <h3>Allow notifications</h3>
      <p>Optional, but without them BioKey can’t tell you if app locking stops. Setup asks; you can also allow them in Settings &gt; Apps &gt; BioKey &gt; Notifications.</p>
    </li>
    <li>
      <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><path d="M12 15v2"/></svg></span>
      <h3>Choose what to lock</h3>
      <p>Flip the switch beside an app. Group apps into profiles (Finance, Social, Work, or your own), each with its own re-lock delay and switch.</p>
    </li>
  </ol>
</section>
</div>

<section class="wrap faq-group" aria-labelledby="faq-setup" markdown="block">
  <h2 id="faq-setup"><span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>Setting up</h2>

<details class="faq" id="q-restricted-setting" markdown="block">
<summary>Android says “Restricted setting” when I turn on the accessibility service</summary>
<div class="faq__body" markdown="block">

This only happens when BioKey was installed from a file instead of Google Play. Android 13 and later block accessibility services for apps installed that way until you allow it:

1. Try to turn on **BioKey App Lock Service** once in Settings > Accessibility, and close the "Restricted setting" message.
2. Open Settings > Apps > **BioKey**, tap ⋮ (top right), then **Allow restricted settings**, and confirm with your screen lock. The ⋮ item only appears after step 1.
3. Go back to Settings > Accessibility and turn the service on.

BioKey’s setup shows this step when it applies. Installing from [Google Play](https://play.google.com/store/apps/details?id=com.nbdesigns.biokey) avoids it.

</div>
</details>

<details class="faq" id="q-accessibility-switch" markdown="block">
<summary>Where is the accessibility switch?</summary>
<div class="faq__body" markdown="block">

Settings > Accessibility, then **BioKey App Lock Service**. On some phones it’s under **Downloaded apps** or **Installed apps**. BioKey’s setup and its main screen open the right page for you.

Before Android’s page opens, BioKey shows what the service reads and what it’s for, and continues only if you tap **Agree**. The [privacy policy]({{ '/privacy/' | relative_url }}) says the same in more detail.

</div>
</details>

<details class="faq" id="q-advanced-protection" markdown="block">
<summary>Advanced Protection is on, and BioKey asks for usage access instead</summary>
<div class="faq__body" markdown="block">

That’s compatibility mode. On Android 17, Advanced Protection reserves the accessibility service for accessibility tools (screen readers and the like), and switches BioKey’s off. BioKey then works through two other permissions:

- **Usage access**, which tells it which app was just opened.
- **Display over other apps**, which lets it cover a locked app as it opens.

BioKey switches between the two ways by itself, including when you turn Advanced Protection on or off. In compatibility mode:

- A locked app is covered as it opens rather than at the tap, so an app that was still in memory can show for about half a second first.
- Android requires a low-priority notification while it runs.

Both permissions are needed: if either is turned off, BioKey stops locking and sends you an alert.

</div>
</details>

<details class="faq" id="q-notifications" markdown="block">
<summary>Do I need to allow notifications?</summary>
<div class="faq__body" markdown="block">

They’re optional, but without them BioKey can’t tell you if app locking stops. BioKey’s setup asks for them; you can also allow them in Settings > Apps > **BioKey** > Notifications.

</div>
</details>

<details class="faq" id="q-android-app-lock" markdown="block">
<summary>I also use Android 17's own App lock</summary>
<div class="faq__body" markdown="block">

BioKey and Android 17's own App lock can both lock an app. With the accessibility service, BioKey reads Android's prompt as it asks and takes that unlock as its own, so an app locked in both asks once; in compatibility mode, or when BioKey can't be sure which app Android asked for, it asks twice. BioKey's home marks the apps Android locks too ("Also locked by Android"). Lock an app in one or the other, as you prefer.

</div>
</details>
</section>

<section class="wrap faq-group" aria-labelledby="faq-locking" markdown="block">
  <h2 id="faq-locking"><span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><path d="M12 15v2"/></svg></span>Locking and alerts</h2>

<details class="faq" id="q-relock" markdown="block">
<summary>When does a locked app lock again?</summary>
<div class="faq__body" markdown="block">

When you leave it: at once, or after the re-lock delay you chose for it or its profile. And whenever the phone locks: every locked app locks with it. While Android keeps the phone unlocked after the screen turns off, locked apps stay as they were.

</div>
</details>

<details class="faq" id="q-pin" markdown="block">
<summary>Someone knows my PIN. Can they open my locked apps?</summary>
<div class="faq__body" markdown="block">

Turn on **Face or fingerprint only** in BioKey’s settings (it appears once a face or fingerprint is set up on your phone): locked apps and BioKey then don’t offer the PIN at all.

Lock **Settings** too (BioKey offers): with your PIN, someone could otherwise add their own fingerprint there. Android can’t be stopped from allowing that, but BioKey notices and tells you ("a face or fingerprint was added or removed"). Home suggests **Lock Android Settings too** while the option is on; **Not now** puts the suggestion away until you next turn the option on, and BioKey’s Settings keeps it under the option. If Settings is in a profile that’s switched off, locking it takes it out of that profile (Undo puts it back).

After too many failed tries, Android pauses face and fingerprint for a while; if it locks them out for good, lock your phone and unlock it with your PIN to bring them back. With the option off, **Failed unlock alerts** and **Also note screen lock unlocks** at least show you when it happened.

</div>
</details>

<details class="faq" id="q-lock-all" markdown="block">
<summary>How do I lock everything at once?</summary>
<div class="faq__body" markdown="block">

Before handing your phone to someone: long-press BioKey’s icon and tap **Lock all now**, or add the **Lock all** tile to Quick Settings (swipe down twice, tap the pencil). Every locked app asks again the next time it opens. It needs no authentication, since it only locks. If protection was paused, it resumes it.

</div>
</details>

<details class="faq" id="q-failed-unlock-alerts" markdown="block">
<summary>What are failed unlock alerts?</summary>
<div class="faq__body" markdown="block">

When you turn them on (BioKey asks once on its home screen, or Settings > Failed unlock alerts), BioKey notes each face or fingerprint that didn’t match on a locked app. If yours then matches on the same unlock prompt, those misses were yours and the note goes. Misses followed by a later unlock stay on the list, marked with how the app was opened, since someone may have tried and handed the phone back.

Misses no face or fingerprint followed (unanswered, or followed by the PIN) are also notified, the next time you unlock your phone and at least 2 minutes later, without naming the app; the notification stays until you open **Review** in BioKey opened with your face or fingerprint (opened with your screen lock, tap **Mark reviewed** there and use your face or fingerprint).

Android tells apps when a face or fingerprint is added, or when the last one is removed; not when one of several is removed, which lets nobody in. A face or fingerprint added or removed stays new for a day either way, and its alert comes back at each unlock until then: whoever made the change can’t hide it from you. Review says until when; meanwhile nothing proves it’s you in BioKey: changes are recorded, nothing is marked seen, and nothing that clears or silences its records can be done (Clear, turning the alerts, their screen lock option or the log off, restoring a backup). After the day, open Review again.

Only faces and fingerprints count: Android doesn’t tell apps about a wrong PIN, pattern or password. If you never use your PIN yourself, turn on **Also note screen lock unlocks** to see (listed on the home screen, not notified) whenever someone opens a locked app with it. Notes are kept until you’ve seen them, then 7 days; clear them from **Review** on the home screen (it asks for your face or fingerprint).

</div>
</details>

<details class="faq" id="q-locking-was-off" markdown="block">
<summary>BioKey says “App locking was off”</summary>
<div class="faq__body" markdown="block">

Someone (maybe you) switched BioKey off in Android’s settings, revoked one of its permissions, or force-stopped it, and locked apps could open without a check meanwhile. The card shows when. Android stopping BioKey on its own (low memory, a crash) or a restart of the phone doesn’t count. Some phone makers' battery savers force-stop apps the way a person would, and those do show: see [Locking stopped](#q-locking-stopped) to keep BioKey running. Clear it from **Review**.

If BioKey’s storage was cleared in Android’s settings (Apps > BioKey > Storage), every lock and record went with it. Android also switches BioKey’s accessibility service off and, from Android 13, takes back its notification permission, so nothing is locked and BioKey can’t tell you until you open it: its setup screen then says when (on Android 12, an alert also comes once BioKey next runs). Once set up again, it’s recorded. For a day after, as after a face or fingerprint change, records can’t be cleared and the alerts can’t be switched off: whoever cleared it may have added their own fingerprint first. (BioKey tells from Android’s record of its last run, which a clearing keeps; an uninstall removes it, but then BioKey’s icon is gone too.)

If you switched it off on purpose, **Stop reminding** on the "App lock is off" card (it asks for your face or fingerprint) ends the reminders until app locking is back on; the card then says so, and **Remind me** brings them back. It stays recorded. If app locking fails some other way afterwards (switched on, but not running), the reminders come back on their own.

</div>
</details>

<details class="faq" id="q-new-app" markdown="block">
<summary>BioKey offered to lock an app I just installed</summary>
<div class="faq__body" markdown="block">

Auto-sort locks the banking, social and work apps it recognises (an app you locked yourself isn’t moved into a profile that’s switched off, which would unlock it). Anything else you install gets a notification with a **Lock** button. Turn it off in Settings > Offer to lock new apps.

</div>
</details>
</section>

<section class="wrap faq-group" aria-labelledby="faq-backup" markdown="block">
  <h2 id="faq-backup"><span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/><path d="M12 4v11"/><path d="M8 11l4 4 4-4"/></svg></span>Backup and restore</h2>

<details class="faq" id="q-backup" markdown="block">
<summary>How do I keep my setup when I reinstall BioKey or change phones?</summary>
<div class="faq__body" markdown="block">

BioKey’s settings aren’t included in Android’s own backup or phone-to-phone transfer, so save a backup file first:

1. In BioKey’s settings, tap **Back up settings**.
2. Choose a password of at least 8 characters. You’ll need it to restore; it can’t be recovered.
3. Choose where to save the file. Pick somewhere that survives an uninstall or a new phone: your Downloads folder, or a cloud drive.

After installing BioKey again (or on the new phone) and finishing setup, tap **Restore from a backup** in its settings, pick the file and enter the password, and confirm with your face or fingerprint (your screen lock if none is set up). Within a day of a face or fingerprint being added or removed, BioKey refuses to restore, and says until when. It replaces your locked apps, profiles and settings with the backup’s.

The file holds your locked apps, profiles and BioKey settings, sealed with your password. Without the password it reveals nothing, not even which apps are locked.

</div>
</details>

<details class="faq" id="q-forgot-password" markdown="block">
<summary>I forgot my backup password</summary>
<div class="faq__body" markdown="block">

It can’t be recovered, by you or by us: the password is the only key to the file. Make a new backup with a new password.

</div>
</details>
</section>

<section class="wrap faq-group" aria-labelledby="faq-trouble" markdown="block">
  <h2 id="faq-trouble"><span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 8v5"/><path d="M12 16h.01"/></svg></span>When something goes wrong</h2>

<details class="faq" id="q-locking-stopped" markdown="block">
<summary>Locking stopped</summary>
<div class="faq__body" markdown="block">

Open BioKey; its main screen says what’s wrong, and BioKey sends an alert when protection stops (if notifications are allowed). Common causes:

- **"App lock is off":** the accessibility service is switched off. Turn it back on in Settings > Accessibility > **BioKey App Lock Service**.
- **"App lock isn’t running":** the accessibility service is switched on, but Android has stopped it. Turn it off and on again in Settings > Accessibility.
- **"Advanced Protection is on", or an alert that compatibility mode lost a permission:** usage access or display over other apps is missing. Open BioKey and tap **Set up compatibility mode**, or grant it again.
- **"Protection paused":** tap **Resume protection** in BioKey, or use its Quick Settings tile.
- **The app isn’t locked:** check that its own switch is on, and that its profile is on.
- **Safe mode:** Android turns off every downloaded app in safe mode, BioKey included. Restart the phone normally.
- **Your phone maker’s battery saver stopped it.** Some phones stop apps in the background on their own. Let BioKey run freely:
    - **Samsung:** Settings > Battery > Background usage limits: take BioKey out of "Sleeping apps" and "Deep sleeping apps"; in BioKey’s App info > Battery, choose **Unrestricted**.
    - **Xiaomi, Redmi, POCO (HyperOS, MIUI):** in BioKey’s App info, turn on **Autostart**, and set Battery saver to **No restrictions**.
    - **OPPO, OnePlus, realme, vivo:** in BioKey’s App info > Battery, allow **background activity** (and "Auto launch" where offered).
    - **Any phone:** in BioKey’s App info > App battery usage, choose **Unrestricted**.

If none of these fits, please [email us](#contact) with the diagnostic log.

</div>
</details>

<details class="faq" id="q-showed-for-a-moment" markdown="block">
<summary>A locked app showed for a moment before it was covered</summary>
<div class="faq__body" markdown="block">

Some ways of opening an app can’t be covered at the tap: links from other apps, notifications, the quick-switch gesture, and in compatibility mode any app still in memory. Some banking apps also hide their windows from BioKey. The full list is in the [Limits](https://github.com/Peacetothemountain/BioKey#limits) section of the README. The app still can’t be used until you unlock it.

</div>
</details>

<details class="faq" id="q-home-screen" markdown="block">
<summary>I can’t lock my home screen</summary>
<div class="faq__body" markdown="block">

Home screens can’t be locked, so you can always get back Home.

</div>
</details>

<details class="faq" id="q-biometrics" markdown="block">
<summary>Face or fingerprint doesn’t work</summary>
<div class="faq__body" markdown="block">

BioKey uses Android’s own prompt, and accepts only strong (Class 3) face unlock, fingerprint, or your PIN, pattern or password. If your phone’s face unlock is the weaker kind, BioKey asks for your fingerprint or screen lock instead.

After too many failed attempts, Android locks biometrics for a while; use your PIN, pattern or password. With **Face or fingerprint only** on, the PIN isn’t offered: wait, and if Android locks face and fingerprint out for good, lock your phone and unlock it with your PIN to bring them back.

If the sensor itself stops working (a fault, an update pending), BioKey allows your screen lock again for locked apps and records that it did; clearing records, turning alerts off and restoring a backup wait until the sensor works again. If it’s broken for good, remove your faces and fingerprints in Android’s settings: BioKey then asks for your screen lock instead (and records the removal). On Android 15 and later, the same applies if **Use for apps** is switched off for face or fingerprint in Android’s settings.

</div>
</details>

<details class="faq" id="q-uninstall" markdown="block">
<summary>How do I uninstall BioKey?</summary>
<div class="faq__body" markdown="block">

Uninstall it as you would any app. Uninstalling deletes everything BioKey kept on the phone; backup files you saved stay where you saved them.

</div>
</details>
</section>

<section class="wrap contact" id="contact" aria-labelledby="contact-title">
  <div class="contact__intro">
    <h2 id="contact-title">Get in touch</h2>
    <p class="contact__lead">Email <a href="mailto:support.nbdesigns@gmail.com">support.nbdesigns@gmail.com</a>. Please include your phone’s model, your Android version, your BioKey version (in Android’s App info for BioKey), and whether BioKey is using the accessibility service or compatibility mode (BioKey’s main screen says “Compatibility mode” when it’s using that).</p>
  </div>
  <ul class="contact__list">
    <li>
      <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-7l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"/><path d="M8 9h8"/><path d="M8 12.5h5"/></svg></span>
      <div>
        <h3>Send the diagnostic log</h3>
        <p>In BioKey’s settings, open <strong>View diagnostic log</strong>, tap <strong>Share</strong> and confirm with your face or fingerprint (your screen lock if it can’t be used). Shared from BioKey opened with your screen lock, it’s recorded in its security activity. It lists the apps you’ve locked by package name, and nothing about apps you haven’t. Read it before you send it.</p>
      </div>
    </li>
    <li>
      <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M8 7l-5 5 5 5"/><path d="M16 7l5 5-5 5"/><path d="M14 4l-4 16"/></svg></span>
      <div>
        <h3>Bugs and feature requests</h3>
        <p>These can also go to <a href="https://github.com/Peacetothemountain/BioKey/issues">GitHub issues</a>. BioKey’s source code is <a href="https://github.com/Peacetothemountain/BioKey">on GitHub</a>.</p>
      </div>
    </li>
    <li>
      <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"/><path d="M12 8v4"/><path d="M12 15h.01"/></svg></span>
      <div>
        <h3>Security problems</h3>
        <p>Please report them privately by email, not in a public issue. See the <a href="https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md">security policy</a>.</p>
      </div>
    </li>
    <li>
      <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>
      <div>
        <h3>In Android’s Settings</h3>
        <p>BioKey’s accessibility service is listed as <strong>BioKey App Lock Service</strong>.</p>
      </div>
    </li>
  </ul>
</section>
