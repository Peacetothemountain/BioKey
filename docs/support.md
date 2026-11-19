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
    <p class="lead">Set up BioKey in a few minutes, find answers to common questions, or <a href="#contact">get in touch</a>.</p>
  </div>
</header>

<div class="wrap">
<section aria-labelledby="start-title">
  <h2 id="start-title">Get started</h2>
  <ol class="steps">
    <li>
      <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3v12"/><path d="M7 10l5 5 5-5"/><path d="M4 20h16"/></svg></span>
      <h3>Install BioKey</h3>
      <p>Get it from <a href="https://play.google.com/store/apps/details?id=com.nbdesigns.biokey" rel="noopener noreferrer">Google Play</a>. It needs Android 12 or later.</p>
    </li>
    <li>
      <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>
      <h3>Turn on protection</h3>
      <p>In BioKey’s setup, tap <strong>Open Accessibility settings</strong>, then turn on <strong>BioKey</strong> (usually under <strong>Downloaded apps</strong>). BioKey comes back by itself. Under Advanced Protection it asks for usage access and display over other apps instead (see <a href="#q-advanced-protection">compatibility mode</a>).</p>
    </li>
    <li>
      <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/></svg></span>
      <h3>Allow notifications</h3>
      <p>Optional, but without them BioKey can’t tell you if protection stops. Setup offers it under <strong>Optional</strong>, or allow them in Android Settings &gt; Apps &gt; BioKey &gt; Notifications.</p>
    </li>
    <li>
      <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><path d="M12 15v2"/></svg></span>
      <h3>Choose what to lock</h3>
      <p>Auto-sort locks the apps it recognises and sorts them into the Personal, Finance, Social and Work profiles (see <a href="#q-new-app">which apps</a>). Flip the switch beside any other app to lock it, and make profiles of your own if you like: each has its own re-lock delay and switch.</p>
    </li>
  </ol>
</section>
</div>

<section class="wrap faq-group" aria-labelledby="faq-setup" markdown="block">
  <h2 id="faq-setup"><span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>Setting up</h2>

<details class="faq" id="q-restricted-setting" markdown="block">
<summary>Android says “Restricted setting” when I turn on the accessibility service</summary>
<div class="faq__body" markdown="block">

This only happens when BioKey was installed from a file instead of Google Play. Android 13 and later block accessibility for apps installed that way until you allow it:

1. Try to turn on **BioKey** once in Android Settings > Accessibility, and close the “Restricted setting” message.
2. Open Android Settings > Apps > **BioKey**, tap ⋮ (top right), then **Allow restricted settings**. The ⋮ item only appears after step 1.
3. Go back to Android Settings > Accessibility and turn the service on.

Installing from [Google Play](https://play.google.com/store/apps/details?id=com.nbdesigns.biokey) avoids this.

</div>
</details>

<details class="faq" id="q-accessibility-switch" markdown="block">
<summary>Where is the accessibility switch?</summary>
<div class="faq__body" markdown="block">

Android Settings > Accessibility > **BioKey**, usually under **Downloaded apps**. BioKey’s setup and main page open that page for you. Before they do, BioKey shows what the service reads and what it’s for, and goes on only if you tap **Agree**; the [privacy policy]({{ '/privacy/' | relative_url }}) has the details.

</div>
</details>

<details class="faq" id="q-advanced-protection" markdown="block">
<summary>Advanced Protection is on, and BioKey asks for usage access instead</summary>
<div class="faq__body" markdown="block">

That’s compatibility mode. Advanced Protection keeps the accessibility service for real accessibility tools, so BioKey uses two other permissions instead: **usage access**, to know which app was just opened, and **display over other apps**, to cover it. BioKey switches by itself when you turn Advanced Protection on or off.

In compatibility mode a locked app is covered as it opens rather than at the tap, so an app still in memory can show for about half a second first, and Android requires a low-priority notification while it runs. Both permissions are needed: if either is turned off, BioKey stops locking and alerts you.

</div>
</details>

<details class="faq" id="q-notifications" markdown="block">
<summary>Do I need to allow notifications?</summary>
<div class="faq__body" markdown="block">

They’re optional, but without them BioKey can’t tell you if protection stops. Allow them in setup, or in Android Settings > Apps > **BioKey** > Notifications, where each kind can be turned off on its own: **Protection alerts**, **Security activity**, **New apps**, **Protection paused** and **Compatibility mode**.

</div>
</details>

<details class="faq" id="q-android-app-lock" markdown="block">
<summary>I also use Android 17’s own App lock</summary>
<div class="faq__body" markdown="block">

Both can lock the same app. With the accessibility service, BioKey recognises Android’s prompt and counts that unlock, so the app asks once; in compatibility mode, or when BioKey can’t be sure which app Android asked for, it asks twice. BioKey marks apps Android locks too (“Also locked by Android”).

</div>
</details>
</section>

<section class="wrap faq-group" aria-labelledby="faq-locking" markdown="block">
  <h2 id="faq-locking"><span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><path d="M12 15v2"/></svg></span>Locking and alerts</h2>

<details class="faq" id="q-relock" markdown="block">
<summary>When does a locked app lock again?</summary>
<div class="faq__body" markdown="block">

When you leave it: at once, or after the re-lock delay set for it or its profile. And every locked app locks again when the phone locks.

</div>
</details>

<details class="faq" id="q-pin" markdown="block">
<summary>Someone knows my PIN. Can they open my locked apps?</summary>
<div class="faq__body" markdown="block">

Turn on **Face or fingerprint only** in BioKey’s settings (it shows once a face or fingerprint is set up): locked apps and BioKey then won’t accept the PIN. For days your finger won’t work, set a [passphrase](#q-passphrase).

Lock **Android Settings** too (BioKey suggests it, and its settings have **Lock Android Settings too**): with your PIN, someone could otherwise add their own fingerprint there. BioKey can’t stop that, but it notices and tells you.

</div>
</details>

<details class="faq" id="q-passphrase" markdown="block">
<summary>What is the passphrase?</summary>
<div class="faq__body" markdown="block">

A second way into your locked apps and BioKey, for when **Face or fingerprint only** is on and your face or fingerprint won’t work (a wet finger, a sensor locked out). It belongs to BioKey alone: knowing your phone’s PIN doesn’t help.

- **Set it** in BioKey’s settings: turn on **Face or fingerprint only**, then use the **Passphrase** row under it. At least 8 characters, different from your screen lock.
- **Use it** with **Use passphrase** on the unlock prompt or lock screen. If no face or fingerprint can be used at all, BioKey asks for it straight away.
- **Only with Face or fingerprint only on.** Otherwise your screen lock works as usual.
- **Never stored.** BioKey keeps only a check value made from it, on your phone, never in a backup. See the [privacy policy]({{ '/privacy/#your-passphrase' | relative_url }}).

</div>
</details>

<details class="faq" id="q-passphrase-forgot" markdown="block">
<summary>I forgot my passphrase</summary>
<div class="faq__body" markdown="block">

Nothing is lost. Open BioKey with your face or fingerprint, then change or remove it from the **Passphrase** row in BioKey’s settings.

If your face or fingerprint can’t be used either, set one up again in Android’s settings and open BioKey with it. You can change the passphrase a day after that (see [below](#q-passphrase-not-until)).

</div>
</details>

<details class="faq" id="q-passphrase-wait" markdown="block">
<summary>Why do I have to wait after wrong passphrases?</summary>
<div class="faq__body" markdown="block">

So nobody can keep guessing. After 4 wrong tries in a row, each one means a wait: 30 seconds for the 5th to 7th, 5 minutes for the 8th and 9th, then 30 minutes. Restarting the phone or changing its date doesn’t shorten it, and the right passphrase clears the count. Your face or fingerprint still works while you wait.

</div>
</details>

<details class="faq" id="q-passphrase-twice" markdown="block">
<summary>Why does BioKey ask me to type my passphrase twice?</summary>
<div class="faq__body" markdown="block">

Only when you set or change it: the second field, **Type it again**, catches a typo before it becomes a passphrase you don’t know. You type it once to unlock.

</div>
</details>

<details class="faq" id="q-passphrase-not-until" markdown="block">
<summary>BioKey says “Not until …” when I change my passphrase</summary>
<div class="faq__body" markdown="block">

A face or fingerprint was added or removed, or BioKey was reset, less than a day ago. Until then BioKey can’t be sure it’s you, so changes to the passphrase wait, as restoring a backup and clearing records do. The message says when. If you didn’t make that change, check your faces and fingerprints in Android’s settings.

</div>
</details>

<details class="faq" id="q-passphrase-recorded" markdown="block">
<summary>Security activity says “Passphrase set”</summary>
<div class="faq__body" markdown="block">

Every time the passphrase is set, changed or removed, Security activity records it and alerts you. If it was you, open **Review** on BioKey’s main page. If it wasn’t, someone who could open BioKey made a way into your locked apps: change or remove the passphrase, and check your faces and fingerprints in Android’s settings.

</div>
</details>

<details class="faq" id="q-lock-all" markdown="block">
<summary>How do I lock everything at once?</summary>
<div class="faq__body" markdown="block">

Long-press BioKey’s icon and tap **Re-lock now**, use the **Re-lock now** Quick Settings tile, or tap **Re-lock now** on BioKey’s main page. Every locked app asks again the next time it opens. It asks for nothing, since it only locks, and it resumes protection if it was paused.

</div>
</details>

<details class="faq" id="q-paused-notification" markdown="block">
<summary>What is the “Protection is paused” notification?</summary>
<div class="faq__body" markdown="block">

It stays up while protection is paused, so a pause can’t be forgotten. It says until when, and **Resume** puts protection back at once. It isn’t shown on the lock screen. To silence it, turn off its category, “Protection paused”, in Android’s settings for BioKey’s notifications.

</div>
</details>

<details class="faq" id="q-failed-unlock-alerts" markdown="block">
<summary>What are failed unlock alerts?</summary>
<div class="faq__body" markdown="block">

Turn them on in BioKey’s settings (or when BioKey asks), and BioKey records each face or fingerprint that doesn’t match on a locked app. If yours then matches on the same prompt, the misses were yours and the record goes. Otherwise you’re told the next time you unlock your phone, without the app being named, and BioKey’s main page lists them until you review and clear them.

Android doesn’t tell apps about a wrong PIN, pattern or password, so only faces, fingerprints and your passphrase count. If you never use your PIN yourself, turn on **Also record screen lock unlocks** to see whenever someone opens a locked app with it. Records are kept until you’ve seen them, and for 7 days after they happened.

</div>
</details>

<details class="faq" id="q-biometrics-changed" markdown="block">
<summary>Security activity says “A face or fingerprint was added or removed”</summary>
<div class="faq__body" markdown="block">

If you didn’t add or remove one, check your faces and fingerprints in Android’s settings. (Android tells apps when one is added, or when the last one is removed, but not when one of several is removed.)

For a day, BioKey can’t tell whether the new face or fingerprint is yours. The alert comes back at each unlock, changes are recorded, and nothing that clears or silences BioKey’s records can be done: clearing them, turning off alerts or the diagnostic log, restoring a backup, or changing your passphrase. Review says until when. After the day, open **Review** again.

</div>
</details>

<details class="faq" id="q-locking-was-off" markdown="block">
<summary>BioKey says “Protection was off”</summary>
<div class="faq__body" markdown="block">

BioKey was switched off in Android’s settings, lost a permission or was force-stopped, so locked apps could open without a check. The card shows when. A crash, low memory or a restart doesn’t count, but some phone makers’ battery savers stop apps the way a person would: see [Protection stopped](#q-locking-stopped). Clear it from **Review**.

If BioKey’s storage was cleared, every lock and record went with it and protection stopped until BioKey was set up again. Its setup screen says when, and for a day after, as after a face or fingerprint change, records can’t be cleared and a backup can’t be restored.

If you switched protection off on purpose, **Stop reminding** on the card ends the reminders; **Remind me** brings them back.

</div>
</details>

<details class="faq" id="q-new-app" markdown="block">
<summary>BioKey offered to lock an app I just installed</summary>
<div class="faq__body" markdown="block">

Auto-sort locks the apps it recognises, when setup finishes and each time you install one, and puts them in a profile: Personal (camera, photos, files, notes, health, browsers, VPNs), Finance (banking, payments, crypto, investing), Social (social, chat, messaging, dating) or Work (email, calendar, documents, work tools, sign-in codes, password managers). It never moves an app that’s already in a profile. Turn it off in BioKey’s settings, **Auto-sort into profiles**.

Any other app you install gets a notification with a **Lock** button. Turn that off with **Offer to lock new apps**.

</div>
</details>
</section>

<section class="wrap faq-group" aria-labelledby="faq-backup" markdown="block">
  <h2 id="faq-backup"><span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M4 16v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/><path d="M12 4v11"/><path d="M8 11l4 4 4-4"/></svg></span>Backup and restore</h2>

<details class="faq" id="q-backup" markdown="block">
<summary>How do I keep my setup when I reinstall BioKey or change phones?</summary>
<div class="faq__body" markdown="block">

BioKey’s settings aren’t in Android’s own backup or phone-to-phone transfer, so save a backup file first:

1. In BioKey’s settings, tap **Back up settings**.
2. Choose a password of at least 8 characters and type it again. You’ll need it to restore, and it can’t be recovered.
3. Save the file somewhere that survives a reinstall or a new phone, such as Downloads or a cloud drive.

On the new install, finish setup, then tap **Restore from a backup**, pick the file, enter the password and confirm it’s you. It replaces your locked apps, profiles and settings with the backup’s. Your [passphrase](#q-passphrase) is never in a backup: set it again (BioKey offers to).

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
<summary>Protection stopped</summary>
<div class="faq__body" markdown="block">

Open BioKey: its main page says what’s wrong.

- **“Protection is off”:** turn BioKey back on in Android Settings > Accessibility.
- **“Protection isn’t running”:** it’s on but Android stopped it. Turn it off and on again in Android Settings > Accessibility.
- **Compatibility mode lost a permission:** open BioKey and tap **Set up compatibility mode**.
- **“Protection is paused”:** tap **Resume protection** in BioKey, or **Resume** on the notification.
- **One app isn’t locked:** check that its switch is on, and that its profile is on.
- **Safe mode** turns off every downloaded app. Restart the phone normally.
- **A battery saver stopped it.** In BioKey’s App info > Battery, choose **Unrestricted**. On Samsung, also take BioKey out of “Sleeping apps” and “Deep sleeping apps”; on Xiaomi, Redmi and POCO, turn on **Autostart**; on OPPO, OnePlus, realme and vivo, allow background activity and auto launch.

If none of these fits, please [email us](#contact) with the diagnostic log.

</div>
</details>

<details class="faq" id="q-showed-for-a-moment" markdown="block">
<summary>A locked app showed for a moment before it was covered</summary>
<div class="faq__body" markdown="block">

Some ways of opening an app can’t be covered at the tap: links from other apps, notifications, the quick-switch gesture, and in compatibility mode any app still in memory. Some banking apps also hide their windows from BioKey. The app still can’t be used until you unlock it. The full list is under [Limits](https://github.com/Peacetothemountain/BioKey#limits) in the README.

</div>
</details>

<details class="faq" id="q-asks-after-settings" markdown="block">
<summary>BioKey asked who I am when I came back from Android’s settings</summary>
<div class="faq__body" markdown="block">

When BioKey sends you to Android’s settings or the file picker, it stays unlocked for up to two minutes so you can come straight back. It asks again if you went Home or to another app on the way, or the two minutes ran out, so nobody can pick up the phone and walk back into BioKey.

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

BioKey uses Android’s own prompt, and accepts only a face or fingerprint Android rates as strong; the less secure face unlock some phones have isn’t accepted. After too many failed tries, Android locks face and fingerprint for a while: use your PIN, or your [passphrase](#q-passphrase) if **Face or fingerprint only** is on.

If the sensor stops working, BioKey asks for your passphrase if you set one, and otherwise accepts your screen lock again (and records it), so you’re never locked out. If face and fingerprint are switched off for apps in Android’s settings, Security activity records that too: if it wasn’t you, someone knows your screen lock.

</div>
</details>

<details class="faq" id="q-match-not-proved" markdown="block">
<summary>Security activity says “A match couldn’t be proved by the phone’s hardware”</summary>
<div class="faq__body" markdown="block">

Each time Android says you matched, BioKey also asks the phone’s secure hardware to sign for it. On some phones that can’t be done, so BioKey takes Android’s word instead of locking you out, and says so here once. It doesn’t mean someone got in.

With **Face or fingerprint only** on and a [passphrase](#q-passphrase) set, BioKey never takes Android’s word: you use your passphrase instead. If this keeps coming back, please [email us](#contact) with your phone’s model and the diagnostic log.

</div>
</details>

<details class="faq" id="q-uninstall" markdown="block">
<summary>How do I uninstall BioKey?</summary>
<div class="faq__body" markdown="block">

Uninstall it as you would any app. That deletes everything BioKey kept on the phone; backup files stay where you saved them.

</div>
</details>
</section>

<section class="wrap contact" id="contact" aria-labelledby="contact-title">
  <div class="contact__intro">
    <h2 id="contact-title">Get in touch</h2>
    <p class="contact__lead">Email <a href="mailto:support.nbdesigns@gmail.com">support.nbdesigns@gmail.com</a>. Please include your phone’s model, your Android version and your BioKey version (at the foot of BioKey’s settings).</p>
  </div>
  <ul class="contact__list">
    <li>
      <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-7l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"/><path d="M8 9h8"/><path d="M8 12.5h5"/></svg></span>
      <div>
        <h3>Send the diagnostic log</h3>
        <p>In BioKey’s settings, open <strong>View diagnostic log</strong>, tap the share icon and confirm it’s you. The log names the apps you’ve locked (as package names, such as com.example.bank) and nothing about apps you haven’t. Read it before you send it.</p>
      </div>
    </li>
    <li>
      <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M8 7l-5 5 5 5"/><path d="M16 7l5 5-5 5"/><path d="M14 4l-4 16"/></svg></span>
      <div>
        <h3>Bugs and feature requests</h3>
        <p>These can also go to <a href="https://github.com/Peacetothemountain/BioKey/issues" rel="noopener noreferrer">GitHub issues</a>. BioKey’s source code is <a href="https://github.com/Peacetothemountain/BioKey" rel="noopener noreferrer">on GitHub</a>.</p>
      </div>
    </li>
    <li>
      <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"/><path d="M12 8v4"/><path d="M12 15h.01"/></svg></span>
      <div>
        <h3>Security problems</h3>
        <p>Please report them privately by email, not in a public issue. See the <a href="https://github.com/Peacetothemountain/BioKey/blob/main/SECURITY.md" rel="noopener noreferrer">security policy</a>.</p>
      </div>
    </li>
    <li>
      <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>
      <div>
        <h3>In Android’s settings</h3>
        <p>BioKey’s accessibility service is listed as <strong>BioKey</strong>.</p>
      </div>
    </li>
  </ul>
</section>
