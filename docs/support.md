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
      <p>Get it from <a href="https://play.google.com/store/apps/details?id=com.nbdesigns.biokey" rel="noopener noreferrer">Google Play</a>. It needs Android 12 or later.</p>
    </li>
    <li>
      <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="10" rx="5"/><circle cx="16" cy="12" r="3"/></svg></span>
      <h3>Turn on protection</h3>
      <p>In BioKey’s setup, tap <strong>Open Accessibility settings</strong>. In the list Android opens, tap <strong>BioKey</strong> under <strong>Downloaded apps</strong> and turn it on (where Android opens BioKey’s own page instead, turn on the switch at the top); BioKey then comes back by itself. Under Advanced Protection, it asks for usage access and display over other apps instead (see <a href="#q-advanced-protection">compatibility mode</a>).</p>
    </li>
    <li>
      <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/></svg></span>
      <h3>Allow notifications</h3>
      <p>Optional, but without them BioKey can’t tell you if protection stops. Setup has a step for them under <strong>Optional</strong>, after the steps it needs (tap it and Android asks); you can also allow them in Android Settings &gt; Apps &gt; BioKey &gt; Notifications.</p>
    </li>
    <li>
      <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><path d="M12 15v2"/></svg></span>
      <h3>Choose what to lock</h3>
      <p>When setup finishes, auto-sort locks the apps it recognises and sorts them into the Personal, Finance, Social and Work profiles (see <a href="#q-new-app">which apps</a>); setup says so before you finish, and BioKey’s main page then says how many it locked. Flip the switch beside any other app to lock it, and make profiles of your own if you like: each has its own re-lock delay and switch.</p>
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

1. Try to turn on **BioKey** once in Android Settings > Accessibility, and close the “Restricted setting” message.
2. Open Android Settings > Apps > **BioKey**, tap ⋮ (top right), then **Allow restricted settings**, and confirm with your screen lock. The ⋮ item only appears after step 1.
3. Go back to Android Settings > Accessibility and turn the service on.

Setup offers this as help under its steps: from the start where BioKey can tell that Android is blocking the switch (“Allow restricted settings”), otherwise once you come back from Android’s settings with BioKey still off (“If Android wouldn’t let you turn it on”). It isn’t one of the steps setup counts. Installing from [Google Play](https://play.google.com/store/apps/details?id=com.nbdesigns.biokey) avoids it.

</div>
</details>

<details class="faq" id="q-accessibility-switch" markdown="block">
<summary>Where is the accessibility switch?</summary>
<div class="faq__body" markdown="block">

Android Settings > Accessibility, then **BioKey**. On most phones it’s under **Downloaded apps** (or **Installed apps**). BioKey’s setup and its main page open Android’s Accessibility settings for you, and setup says what to tap there. Once you turn it on there, BioKey comes back by itself. BioKey’s page in that list has a link back into BioKey.

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

They’re optional, but without them BioKey can’t tell you if protection stops. BioKey’s setup has a step for them under **Optional** (tap it and Android asks); you can also allow them in Android Settings > Apps > **BioKey** > Notifications.

There, BioKey’s notifications are in five categories you can turn off one by one: **Protection alerts** (protection stopped), **Security activity** (failed unlocks, times protection was off, changes and the other records Review lists), **New apps** (the offer to lock an app you installed), **Protection paused** and **Compatibility mode** (the notification Android requires while compatibility mode runs).

</div>
</details>

<details class="faq" id="q-android-app-lock" markdown="block">
<summary>I also use Android 17’s own App lock</summary>
<div class="faq__body" markdown="block">

BioKey and Android 17’s own App lock can both lock an app. With the accessibility service, BioKey reads Android’s prompt as it asks and takes that unlock as its own, so an app locked in both asks once; in compatibility mode, or when BioKey can’t be sure which app Android asked for, it asks twice. BioKey’s main page marks the apps Android locks too (“Also locked by Android”). Lock an app in one or the other, as you prefer.

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

Turn on **Face or fingerprint only** in BioKey’s settings (it appears once a face or fingerprint is set up on your phone): locked apps and BioKey then don’t offer the PIN at all. For the days your finger won’t work, set a [passphrase](#q-passphrase) of your own.

Lock **Android Settings** too (BioKey offers): with your PIN, someone could otherwise add their own fingerprint there. Android can’t be stopped from allowing that, but BioKey notices and tells you (“A face or fingerprint was added or removed on this phone”). BioKey’s main page suggests **Lock Android Settings too** while the option is on; **Not now** puts the suggestion away until you next turn the option on. BioKey’s settings have it as a switch under the option, **Lock Android Settings too**, with Undo. If Android Settings is in a profile that’s switched off, locking it takes it out of that profile (Undo puts it back).

After too many failed tries, Android pauses face and fingerprint for a while; if it locks them out for good, lock your phone and unlock it with your PIN to bring them back. With the option off, **Failed unlock alerts** and **Also record screen lock unlocks** at least show you when it happened.

</div>
</details>

<details class="faq" id="q-passphrase" markdown="block">
<summary>What is the passphrase?</summary>
<div class="faq__body" markdown="block">

A second way into your locked apps and BioKey, for when **Face or fingerprint only** is on and your face or fingerprint won’t work (a wet finger, a sensor locked out). It’s BioKey’s alone: knowing your phone’s PIN doesn’t help.

- **Set it:** in BioKey’s settings, turn on **Face or fingerprint only**; the **Passphrase** row then shows under it. At least 8 characters. Make it different from your screen lock. BioKey asks for your face or fingerprint first, and records it in Security activity (see [below](#q-passphrase-recorded)).
- **Use it:** tap **Use passphrase** on the unlock prompt or on the lock cover. If no face or fingerprint can be used at all, BioKey asks for it straight away.
- **Only with Face or fingerprint only on.** With that off, your screen lock works as usual and the passphrase is never asked for.
- **Never stored.** BioKey keeps only a check value made from it, on your phone, never in a backup. See the [privacy policy]({{ '/privacy/#your-passphrase' | relative_url }}).

</div>
</details>

<details class="faq" id="q-passphrase-forgot" markdown="block">
<summary>I forgot my passphrase</summary>
<div class="faq__body" markdown="block">

Nothing is lost. Open BioKey with your face or fingerprint, then, in its settings, tap **Passphrase** under **Face or fingerprint only** (the row shows whenever a passphrase is set): change it or remove it.

If your face or fingerprint can’t be used either, set one up again in Android’s settings (your screen lock lets you), then open BioKey with it. BioKey records the new face or fingerprint in Security activity, as it does any, and you can change or remove the passphrase a day later (see [below](#q-passphrase-not-until)).

</div>
</details>

<details class="faq" id="q-passphrase-wait" markdown="block">
<summary>Why do I have to wait after wrong passphrases?</summary>
<div class="faq__body" markdown="block">

So nobody can try guess after guess. After wrong tries in a row:

- 4 or fewer: no wait.
- 5th, 6th or 7th: 30 seconds each.
- 8th or 9th: 5 minutes each.
- 10th and after: 30 minutes each.

Restarting the phone or changing its date doesn’t shorten the wait. The right passphrase clears the count. Your face or fingerprint still works while you wait.

</div>
</details>

<details class="faq" id="q-passphrase-twice" markdown="block">
<summary>Why does BioKey ask me to type my passphrase twice?</summary>
<div class="faq__body" markdown="block">

Only when you set it or change it: the second field, **Type it again**, catches a typo before it becomes a passphrase you don’t know. If the two differ, it says “Doesn’t match what you typed above” and saves nothing. When you unlock with it, you type it once. A new backup password is asked for twice for the same reason.

</div>
</details>

<details class="faq" id="q-passphrase-not-until" markdown="block">
<summary>BioKey says “Not until …” when I change my passphrase</summary>
<div class="faq__body" markdown="block">

A face or fingerprint was added or removed on the phone, or BioKey was reset, less than a day ago. Until the day is up, BioKey can’t be sure it’s you holding the phone, so setting, changing or removing the passphrase waits, as restoring a backup and clearing records do. The message says when you can. If you didn’t make that change, check your faces and fingerprints in Android’s settings.

</div>
</details>

<details class="faq" id="q-passphrase-recorded" markdown="block">
<summary>Security activity says “Passphrase set”</summary>
<div class="faq__body" markdown="block">

Every time the passphrase is set, changed or removed, Security activity records it (“Passphrase set”, “Passphrase changed” or “Passphrase removed”) and alerts you, whoever did it and however BioKey was opened. If it was you, open **Review** on BioKey’s main page to clear the alert. If it wasn’t, someone who could open BioKey has made a way into your locked apps: change or remove the passphrase in BioKey’s settings, and check your faces and fingerprints in Android’s settings.

</div>
</details>

<details class="faq" id="q-lock-all" markdown="block">
<summary>How do I lock everything at once?</summary>
<div class="faq__body" markdown="block">

Before handing your phone to someone: long-press BioKey’s icon and tap **Re-lock now** (pinned to your home screen, it’s **Re-lock**), or add the **Re-lock now** tile to Quick Settings (swipe down twice, tap the pencil). BioKey’s main page has **Re-lock now** too. Every locked app asks again the next time it opens. It asks for nothing, since it only locks. If protection was paused, it resumes it. Over the phone’s lock screen, once BioKey is set up, its tiles don’t show whether protection is on, paused or off, and **Lock this app** doesn’t name the app.

</div>
</details>

<details class="faq" id="q-paused-notification" markdown="block">
<summary>What is the “Protection is paused” notification?</summary>
<div class="faq__body" markdown="block">

It stays up for as long as you’ve paused protection, so a pause can’t be forgotten: it says until when locked apps open without a check, and its **Resume** button puts protection back at once (it asks for nothing, since it only adds protection). It isn’t shown on the lock screen. In compatibility mode BioKey’s usual notification says “Protection is paused” instead. To silence it, turn off its category, “Protection paused”, in Android’s settings for BioKey’s notifications.

</div>
</details>

<details class="faq" id="q-failed-unlock-alerts" markdown="block">
<summary>What are failed unlock alerts?</summary>
<div class="faq__body" markdown="block">

When you turn them on (BioKey asks once on its main page, or turn on **Failed unlock alerts** in BioKey’s settings), BioKey records each face or fingerprint that didn’t match on a locked app. If yours then matches on the same unlock prompt, those misses were yours and the record goes. Misses followed by a later unlock stay on the list, marked with how the app was opened, since someone may have tried and handed the phone back.

Misses no face or fingerprint followed (unanswered, or followed by the PIN) are also notified, the next time you unlock your phone and at least 2 minutes later, without naming the app; the notification stays until you open **Review** after opening BioKey with your face or fingerprint (if you opened it with your screen lock, tap **Mark as reviewed** there and use your face or fingerprint).

A face or fingerprint added or removed on the phone is recorded too, whether or not the alerts are on: see [below](#q-biometrics-changed).

Only faces and fingerprints count, and your passphrase where you’ve set one (the alert then says “A face, fingerprint or passphrase didn’t match”): Android doesn’t tell apps about a wrong PIN, pattern or password. If you never use your PIN yourself, turn on **Also record screen lock unlocks** to see (listed on BioKey’s main page, not notified) whenever someone opens a locked app with it. These records are kept until you’ve seen them, and for 7 days after they happened; clear them from **Review** on the main page (it asks for your face or fingerprint).

</div>
</details>

<details class="faq" id="q-biometrics-changed" markdown="block">
<summary>Security activity says “A face or fingerprint was added or removed”</summary>
<div class="faq__body" markdown="block">

Android tells apps when a face or fingerprint is added, or when the last one is removed; not when one of several is removed, which lets nobody in. If you didn’t make the change, check your faces and fingerprints in Android’s settings.

The record stays new for a day, and its alert comes back at each unlock until then: whoever made the change can’t hide it from you. Review says until when, and lists what has to wait under **What waits a day**.

For that day BioKey can’t tell whether the new face or fingerprint is yours, so changes are recorded, nothing counts as seen or can be marked as reviewed, and nothing that clears or silences its records can be done: **Clear**, turning off **Failed unlock alerts**, **Also record screen lock unlocks** or **Diagnostic log**, deleting the log and restoring a backup all wait, and so does setting, changing or removing your passphrase. After the day, open **Review** again.

</div>
</details>

<details class="faq" id="q-locking-was-off" markdown="block">
<summary>BioKey says “Protection was off”</summary>
<div class="faq__body" markdown="block">

Someone (maybe you) switched BioKey off in Android’s settings, revoked one of its permissions, or force-stopped it, and locked apps could open without a check meanwhile. The card shows when. Android stopping BioKey on its own (low memory, a crash) or a restart of the phone doesn’t count. Some phone makers’ battery savers force-stop apps the way a person would, and those do show: see [Protection stopped](#q-locking-stopped) to keep BioKey running. Clear it from **Review**.

If BioKey’s storage was cleared in Android’s settings (Android Settings > Apps > BioKey > Storage), every lock and record went with it. Android also switches BioKey’s accessibility service off and, from Android 13, takes back its notification permission, so nothing is locked and BioKey can’t tell you until you open it: its setup screen then says when (on Android 12, an alert also comes once BioKey next runs). Once set up again, it’s recorded. For a day after, as after a face or fingerprint change, records can’t be cleared, the alerts can’t be switched off, and a backup can’t be restored or a passphrase set (setup says from when): whoever cleared it may have added their own fingerprint first. (BioKey tells from Android’s record of its last run, which a clearing keeps; an uninstall removes it, but then BioKey’s icon is gone too.)

If you switched it off on purpose, **Stop reminding** on the “Protection is off” card (it asks for your face or fingerprint) ends the reminders until protection is back on; the card then says so, and **Remind me** brings them back. It stays recorded. If protection stops some other way afterwards (switched on, but not running), the reminders come back on their own.

</div>
</details>

<details class="faq" id="q-new-app" markdown="block">
<summary>BioKey offered to lock an app I just installed</summary>
<div class="faq__body" markdown="block">

When setup finishes, and each time you install an app, auto-sort locks the apps it recognises and sorts them into profiles: Personal (camera, photos, files, notes, health, web browsers and VPNs), Finance (banking, payment, crypto and investing apps), Social (social, chat, messaging and dating apps) and Work (email, calendar, documents, work tools, sign-in codes and password managers). It goes by the names it knows first; an app it doesn’t know by name goes by the category it gives itself on Android. It never moves an app that’s already in a profile, or puts an app you locked yourself into a profile that’s switched off, which would unlock it. Turn auto-sort off in BioKey’s settings, **Auto-sort into profiles**.

Any other app you install gets a notification with a **Lock** button. Turn that off in BioKey’s settings, **Offer to lock new apps**.

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
2. Choose a password of at least 8 characters, and type it again to catch a typo. You’ll need it to restore; it can’t be recovered.
3. Choose where to save the file. Pick somewhere that survives an uninstall or a new phone: your Downloads folder, or a cloud drive. If BioKey has locked itself by the time you’ve chosen (the two minutes ran out, or you went elsewhere), nothing is saved, and the password you typed was wiped as BioKey locked (“Backup cancelled. Try again.”).

After installing BioKey again (or on the new phone) and finishing setup, tap **Restore from a backup** in its settings, pick the file and enter the password, and confirm with your face or fingerprint (your screen lock if none is set up). Within a day of a face or fingerprint being added or removed, or of BioKey being reset, BioKey refuses to restore before you pick a file, and says until when (the **Restore from a backup** row says “Not until” and the time). It replaces your locked apps, profiles and settings with the backup’s.

Your [passphrase](#q-passphrase) is never in a backup: set it again on the new phone. If the backup turns on **Face or fingerprint only** and no passphrase is set, BioKey offers to set one straight after the restore.

The file holds your locked apps, profiles and BioKey’s settings, sealed with your password. Without the password it reveals nothing, not even which apps are locked.

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

Open BioKey; its main page says what’s wrong, and BioKey sends an alert when protection stops (if notifications are allowed). Common causes:

- **“Protection is off”:** the accessibility service is switched off. Turn it back on in Android Settings > Accessibility > **BioKey**.
- **“Protection isn’t running”:** the accessibility service is switched on, but Android has stopped it. Turn it off and on again in Android Settings > Accessibility.
- **“Advanced Protection is on”, or an alert that compatibility mode lost a permission:** usage access or display over other apps is missing. Open BioKey and tap **Set up compatibility mode**, or grant it again.
- **“Protection is paused” or “Paused until …”:** tap **Resume protection** in BioKey, **Resume** on the “Protection is paused” notification, or BioKey’s **Protection** tile in Quick Settings.
- **The app isn’t locked:** check that its own switch is on, and that its profile is on.
- **Safe mode:** Android turns off every downloaded app in safe mode, BioKey included. Restart the phone normally.
- **Your phone maker’s battery saver stopped it.** Some phones stop apps in the background on their own. Let BioKey run freely:
    - **Samsung:** Android Settings > Battery > Background usage limits: take BioKey out of “Sleeping apps” and “Deep sleeping apps”; in BioKey’s App info > Battery, choose **Unrestricted**.
    - **Xiaomi, Redmi, POCO (HyperOS, MIUI):** in BioKey’s App info, turn on **Autostart**, and set Battery saver to **No restrictions**.
    - **OPPO, OnePlus, realme, vivo:** in BioKey’s App info > Battery, allow **background activity** (and “Auto launch” where offered).
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

<details class="faq" id="q-asks-after-settings" markdown="block">
<summary>BioKey asked who I am when I came back from Android’s settings</summary>
<div class="faq__body" markdown="block">

When BioKey sends you to a page of Android’s settings or to the file picker, it stays unlocked for up to two minutes so you can come straight back. It asks again if you went Home or to another app on the way, if the two minutes are up, or if nothing was running that would notice you going elsewhere: with protection not running, or in compatibility mode while protection is paused or nothing is locked. That’s so someone can’t pick up the phone after you’ve left BioKey and walk back into it.

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

BioKey uses Android’s own prompt. It accepts your face or fingerprint, or your PIN, pattern or password, but only a face or fingerprint that Android counts as strong: the less secure face unlock some phones have isn’t accepted. On those phones, BioKey asks for your fingerprint or screen lock instead.

After too many failed tries, Android locks face and fingerprint for a while; use your PIN, pattern or password. With **Face or fingerprint only** on, the PIN isn’t offered: wait, or use your [passphrase](#q-passphrase) if you set one; if Android locks face and fingerprint out for good, lock your phone and unlock it with your PIN to bring them back.

If the sensor itself stops working (a fault, an update pending), BioKey asks for your passphrase if you set one; otherwise it allows your screen lock again for locked apps and records that it did; clearing records, turning alerts off and restoring a backup wait until the sensor works again. If it’s broken for good, remove your faces and fingerprints in Android’s settings: BioKey then asks for your screen lock instead, or your passphrase if you set one (and records the removal). On Android 15 and later, the same applies if **Use for apps** is switched off for face or fingerprint in Android’s settings, but Security activity records it as that (“Face and fingerprint were switched off for apps in Android’s settings, so your screen lock was allowed”) and alerts you: switching it off takes someone, so if it wasn’t you, someone knows your screen lock.

</div>
</details>

<details class="faq" id="q-match-not-proved" markdown="block">
<summary>Security activity says “A match couldn’t be proved by the phone’s hardware”</summary>
<div class="faq__body" markdown="block">

Each time Android says your face, fingerprint or screen lock matched, BioKey also asks the phone’s secure hardware to sign for it, so Android’s word alone isn’t enough. On some phones that can’t be done: the hardware’s key can’t be made or used, isn’t ready yet (a new one is made after your screen lock is removed and set again), or its signatures keep failing. So that you’re never locked out of your own apps, BioKey then takes Android’s word for a match, and says so here once, until a match is proved again. It doesn’t mean someone got in.

If you’ve turned on **Face or fingerprint only** and set a [passphrase](#q-passphrase), failing signatures never make BioKey take Android’s word: a match that can’t be proved opens nothing, and you can use your passphrase instead. Where the key can’t be made or used at all, BioKey doesn’t ask for your face or fingerprint then: it asks for your passphrase.

BioKey asks for the signature again an hour after it stopped, and each time it starts. If the record keeps coming back, please [email us](#contact) with your phone’s model and the diagnostic log.

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
    <p class="contact__lead">Email <a href="mailto:support.nbdesigns@gmail.com">support.nbdesigns@gmail.com</a>. Please include your phone’s model, your Android version, your BioKey version (at the foot of BioKey’s settings, or in Android’s App info for BioKey), and whether BioKey is using the accessibility service or compatibility mode (in compatibility mode, BioKey’s settings show “On · compatibility mode” under <strong>Protection</strong>).</p>
  </div>
  <ul class="contact__list">
    <li>
      <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-7l-5 4v-4H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z"/><path d="M8 9h8"/><path d="M8 12.5h5"/></svg></span>
      <div>
        <h3>Send the diagnostic log</h3>
        <p>In BioKey’s settings, open <strong>View diagnostic log</strong>, tap the share icon (<strong>Share the diagnostic log</strong>) and confirm with your face or fingerprint (your screen lock if it can’t be used). Shared after BioKey was opened with your screen lock, it’s recorded in Security activity. It lists the apps you’ve locked by package name (the ID Android knows each app by, such as com.example.bank), and nothing about apps you haven’t. Read it before you send it.</p>
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
