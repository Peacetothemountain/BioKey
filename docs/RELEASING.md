# Releasing BioKey

For whoever publishes BioKey to Google Play. Users don't need any of this.

## The app's identity

| | |
|---|---|
| App ID on Play (`applicationId`) | `com.nbdesigns.biokey`. It can never change after the first upload. |
| Kotlin package (`namespace`) | `com.android.BioKey`. Internal only; it isn't the installed ID. |
| Version | `versionCode` and `versionName` in `app/build.gradle.kts`. Every upload to Play needs a higher `versionCode`. |
| SDK | minSdk 31 (Android 12), targetSdk 37, compileSdk 37.2 |
| Developer | NB Designs, support.nbdesigns@gmail.com |
| Privacy policy | https://peacetothemountain.github.io/BioKey/privacy/ |
| Play listing | https://play.google.com/store/apps/details?id=com.nbdesigns.biokey |

## Before every release

1. CI is green on `main`, and the lock-screen gantlet passes locally: `sh tools/gantlet_tests.sh`.
2. The device gantlet passes on a test phone with the debug build (`tools/device_gantlet.py`, see the README). Never use `--emulate-foldables` on a phone you rely on: it can delete home-screen icons that don't come back.
3. The emulator gantlet passes on the foldable profiles: `python tools/emulator_gantlet.py`.
4. `versionCode` and `versionName` are bumped in `app/build.gradle.kts`.
5. `CHANGELOG.md` has an entry for the version.
6. On the test phone, with **Face or fingerprint only** on, lock **Android Settings** (home suggests it): otherwise anyone with the PIN can add a fingerprint there, and the gantlets don't cover that. For Play review: an owner who locks Android's Settings puts BioKey's own accessibility switch and App info behind their face or fingerprint too. That is the owner locking an app of their choice, not BioKey protecting itself (it never blocks those screens on its own), and a dead sensor falls back to the screen lock; say so in the Accessibility declaration if asked.
7. If anything BioKey reads, keeps or shares changed: `PRIVACY.md` and `docs/privacy.md` are updated together (with a new effective date), and so are the in-app disclosures (`ui/disclosure/Disclosures.kt`) and the Play Console Data safety form.

## Building the bundle

```bash
cp key.properties.example key.properties   # once; git-ignored
./gradlew :app:bundleRelease
```

The bundle is `app/build/outputs/bundle/release/app-release.aab`. Without `key.properties` it is unsigned, and Play will refuse it.

Keep `app/build/outputs/mapping/release/mapping.txt` from every build you upload, with the version it belongs to. It's the only way to read that build's stack traces.

## Signing

- **Play App Signing.** Google holds the key that signs what users install. The key in `key.properties` is your *upload key*: it signs what you upload.
- **Back the upload keystore up offline,** with its passwords. If it's lost, Google can reset the upload key from Play Console, but only after a request. Never commit it: `*.jks`, `*.keystore` and `key.properties` are git-ignored.
- **Never upload a `-debugsigned` build.** `-Pbiokey.allowDebugSignedRelease=true` is for local testing only, and marks the version name so it can't pass for a release.
- **Sideloaded APKs** (outside Play) are signed with whatever key you sign them with, not Play's app signing key, so they can't update a Play install, or the other way round.

## Play Console

Done once, and checked again whenever the app's behaviour changes:

- **Privacy policy:** https://peacetothemountain.github.io/BioKey/privacy/ (needs GitHub Pages, below).
- **Accessibility API declaration:** BioKey uses the Accessibility API and isn't an accessibility tool (`isAccessibilityTool="false"`). The Play build has no self-protection (Play doesn't allow blocking disable or uninstall outside parental-control and enterprise apps): never upload a build made with `-Pbiokey.selfProtection=true`, whose version name ends in `-selfprotection`. Describe the core feature (locking chosen apps until the owner authenticates), and say up front that the owner may choose to lock Android's Settings like any app (BioKey suggests it with "Face or fingerprint only"), which also puts BioKey's own switch behind their authentication; BioKey never blocks those screens itself, a dead sensor falls back to the screen lock, and the disclosure says so. Attach a short screen recording showing the in-app disclosure, **Agree**, turning on the service, a locked app being covered and unlocked, and the "Lock Android Settings too" suggestion.
- **Foreground service declaration:** `specialUse`, for compatibility mode. Use the same text as `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` in `app/src/main/AndroidManifest.xml`, and a short video of compatibility mode covering a locked app.
- **Data safety:** BioKey has no `INTERNET` permission, and NB Designs receives nothing. Data processed only on the device isn't "collected" in Play's terms, and the two things that can leave the phone (the backup file and the diagnostic log) go only where the user sends them, from Android's file picker and share sheet. Security activity (failed unlocks, times app locking was off, face or fingerprint changes, notification access revoked under the shield, changes from a screen-lock session or within a day of a face or fingerprint change, reminders stopped meanwhile, the diagnostic log opened for sharing from such a session) stays on the phone too, like the diagnostic log (which now names them); the log leaves only when the user shares it, to a place they pick, which Play doesn't count as collection by the app. The answer is no data collected and no data shared; re-read Google's current guidance before submitting, since it's your declaration.
- **App access:** nothing is behind a sign-in. Reviewers need a phone with a screen lock set up; tell them setup asks for accessibility (or, under Advanced Protection, usage access and display over other apps) after an in-app disclosure.
- **Ads:** none.
- **Target audience and content:** not designed for children. Fill in the content rating questionnaire (no user-generated content, no sharing between users, no location).
- **Store listing:** contact email support.nbdesigns@gmail.com, website https://peacetothemountain.github.io/BioKey/.

## Why Google Play, and developer verification

- Play Protect's fraud protection blocks *sideloaded* apps that request accessibility or notification access in several countries (Singapore, India, Malaysia and others), and Advanced Protection blocks sideloading altogether. Installs from Play are exempt from both, and from Android's restricted settings.
- **Android developer verification** is required for sideloaded and third-party-store installs in Brazil, Indonesia, Singapore and Thailand since 30 September 2026, and worldwide in 2027. If BioKey is ever distributed outside Play, register the package name `com.nbdesigns.biokey` and the certificate those APKs are signed with in the Android Developer Console.

## Store pictures and video

Release builds always set `FLAG_SECURE`, so BioKey's screens can't be captured. A debug build leaves it off while `files/store-capture` exists in its storage:

```bash
./gradlew :app:installDebug
adb shell run-as com.nbdesigns.biokey touch files/store-capture
adb shell am force-stop com.nbdesigns.biokey     # reopen BioKey, then capture
adb shell run-as com.nbdesigns.biokey rm files/store-capture   # when done
```

Force-stopping BioKey also turns its accessibility service off; turn it back on before capturing a lock.

## The GitHub Pages site

The privacy policy, support page and landing page are served from the `docs/` folder on `main`:

- Repository Settings > Pages > Build and deployment: **Deploy from a branch**, branch `main`, folder `/docs`.
- The site is at https://peacetothemountain.github.io/BioKey/, the policy at `/privacy/`, support at `/support/`.
- `docs/privacy.md` is `PRIVACY.md` with Jekyll front matter on top. Keep them identical; this prints nothing when they are:

  ```bash
  tail -n +6 docs/privacy.md | diff - PRIVACY.md
  ```

This file (`docs/RELEASING.md`) is excluded in `docs/_config.yml`, so it isn't part of the site.

## After the release

1. Tag the commit that was uploaded: `git tag -a v1.0 -m "BioKey 1.0"`, then push the tag.
2. Create a GitHub release from the tag with the `CHANGELOG.md` entry. Attach `mapping.txt` only if you're happy for it to be public; otherwise keep it privately.
