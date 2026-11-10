---
layout: default
title: BioKey
slug: home
description: An app lock for Android. The apps you choose open only after your face, fingerprint or screen lock. No internet access, no data collected.
---

<section class="hero">
  <div class="wrap hero__inner">
    <svg class="hero__mark" viewBox="31 22 44 49" role="img" aria-label="BioKey’s mark: a fingerprint whose middle ridge becomes a key">
      <g fill="none" stroke="url(#bk-g)" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">
        <path d="M41 46.875 V37.65 A12.875 12.875 0 0 1 66.75 37.65 V45.625"/>
        <path d="M43.5 50.625 V37.65 A10.375 10.375 0 0 1 64.25 37.65 V53.125"/>
        <path d="M46.25 54.375 V37.65 A7.625 7.625 0 0 1 61.5 37.65 V48.125"/>
        <path d="M48.75 51.875 V37.65 A5.125 5.125 0 0 1 59 37.65 V50.625"/>
        <path d="M51.25 49.375 V37.65 A2.625 2.625 0 0 1 56.5 37.65 V46.875"/>
        <path d="M53.875 40.375 V68.625 M53.875 60.75 H58.375 M53.875 66 H56.625"/>
      </g>
      <path class="sparkle sparkle--1" fill="#7A58EC" d="M36 31.3 Q36 34.5 39.2 34.5 Q36 34.5 36 37.7 Q36 34.5 32.8 34.5 Q36 34.5 36 31.3 Z"/>
      <path class="sparkle sparkle--2" fill="#F0803A" d="M71.1 38.75 Q71.1 40.9 73.25 40.9 Q71.1 40.9 71.1 43.05 Q71.1 40.9 68.95 40.9 Q71.1 40.9 71.1 38.75 Z"/>
      <path class="sparkle sparkle--3" fill="#5C8CF6" d="M38.9 57.8 Q38.9 59 40.1 59 Q38.9 59 38.9 60.2 Q38.9 59 37.7 59 Q38.9 59 38.9 57.8 Z"/>
    </svg>
    <h1>Lock any app behind your face or fingerprint.</h1>
    <p class="lead">BioKey is an app lock for Android. The apps you choose open only after your face, fingerprint or screen lock, and stay unlocked while you’re using them. Nothing leaves your phone.</p>
    <div class="btn-row">
      <a class="btn btn--primary" href="https://play.google.com/store/apps/details?id=com.nbdesigns.biokey">
        <svg viewBox="0 0 24 24" aria-hidden="true" fill="currentColor"><path d="M4 3.5v17a1 1 0 0 0 1.5.87l14.6-8.5a1 1 0 0 0 0-1.74L5.5 2.63A1 1 0 0 0 4 3.5z"/></svg>
        Get it on Google Play
      </a>
      <a class="btn btn--secondary" href="{{ '/support/' | relative_url }}">Help and support</a>
    </div>
    <p class="hero__note">Requires Android 12 or later. Open source under the Apache 2.0 license.</p>
  </div>
</section>

<section class="section" aria-labelledby="features-title">
  <div class="wrap">
    <div class="section__head">
      <h2 id="features-title">Locks the way you’d want it to.</h2>
      <p>Set it up once. BioKey covers a locked app the moment it opens, and gets out of the way once it’s you.</p>
    </div>
    <ul class="card-grid">
      <li class="card feature">
        <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M5 13a7 7 0 0 1 14 0v3"/><path d="M8 13a4 4 0 0 1 8 0v6"/><path d="M11 13a1 1 0 0 1 2 0v8"/><path d="M12 3.5v2"/></svg></span>
        <h3>Face, fingerprint or screen lock</h3>
        <p>Lock the apps you choose behind face unlock, fingerprint, or your PIN, pattern or password. Android’s own prompt does the checking: BioKey never sees your face, fingerprint or PIN.</p>
      </li>
      <li class="card feature">
        <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2"/><path d="M9.5 3h5"/></svg></span>
        <h3>Locks again when you leave</h3>
        <p>Unlocked apps stay unlocked while you use them, and lock again when you leave: at once, or after a delay you choose. Whenever the phone locks, every locked app locks with it.</p>
      </li>
      <li class="card feature">
        <span class="icon icon--magenta" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M3 11h18"/></svg></span>
        <h3>Profiles</h3>
        <p>Group apps into profiles (Finance, Social, Work, or your own), each with its own re-lock delay and its own switch.</p>
      </li>
      <li class="card feature">
        <span class="icon icon--blue" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M12 3l7 3v5.5c0 4.4-3 8-7 9.5-4-1.5-7-5.1-7-9.5V6z"/><path d="M9 12l2 2 4-4"/></svg></span>
        <h3>Works with Advanced Protection</h3>
        <p>When Android’s Advanced Protection reserves the accessibility service for accessibility tools, BioKey switches to compatibility mode on its own, using usage access and display over other apps instead.</p>
      </li>
      <li class="card feature">
        <span class="icon icon--orange" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15z"/><path d="M10 20a2 2 0 0 0 4 0"/><path d="M12 9.5v3"/><path d="M12 15h.01"/></svg></span>
        <h3>Failed unlock alerts</h3>
        <p>Turn them on, and BioKey keeps a note of each face or fingerprint that didn’t match on a locked app, and tells you the next time you unlock your phone.</p>
      </li>
      <li class="card feature">
        <span class="icon icon--violet" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M2.5 8.5a15 15 0 0 1 19 0"/><path d="M6 12a10 10 0 0 1 12 0"/><path d="M9.5 15.5a5 5 0 0 1 5 0"/><path d="M12 19h.01"/><path d="M3 3l18 18"/></svg></span>
        <h3>No internet, no data</h3>
        <p>BioKey has no internet permission and collects nothing. What it keeps stays on your phone, in storage other apps can’t read.</p>
      </li>
    </ul>
  </div>
</section>

<section class="section" aria-labelledby="shots-title">
  <div class="wrap">
    <div class="section__head">
      <h2 id="shots-title">A look inside</h2>
      <p>Light or dark, in your wallpaper’s colours.</p>
    </div>
    <ul class="shots">
      <li><img src="{{ '/assets/screen-home.png' | relative_url }}" width="540" height="960" alt="BioKey’s home screen: a list of locked apps, each with its own switch, under the heading Lock any app" loading="lazy"></li>
      <li><img src="{{ '/assets/screen-cover.png' | relative_url }}" width="540" height="960" alt="A locked app, Chrome, covered the moment it opens: only its icon and name show until you unlock" loading="lazy"></li>
      <li><img src="{{ '/assets/screen-profile.png' | relative_url }}" width="540" height="960" alt="A Personal profile with three apps, its own re-lock delay and its own switch" loading="lazy"></li>
      <li><img src="{{ '/assets/screen-dark.png' | relative_url }}" width="540" height="960" alt="BioKey’s home screen in dark theme" loading="lazy"></li>
    </ul>
  </div>
</section>

<section class="section" aria-labelledby="open-title">
  <div class="wrap band">
    <div>
      <h2 id="open-title">Open source, so you can check for yourself.</h2>
      <p>BioKey’s code is public under the Apache 2.0 license. Read it, build it, or file an issue.</p>
    </div>
    <div class="btn-row">
      <a class="btn btn--secondary" href="https://github.com/Peacetothemountain/BioKey">Source on GitHub</a>
      <a class="btn btn--secondary" href="{{ '/privacy/' | relative_url }}">Privacy policy</a>
    </div>
  </div>
</section>
