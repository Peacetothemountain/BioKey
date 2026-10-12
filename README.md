# BioKey

An Android app lock that puts the apps you choose behind your fingerprint, face, or screen lock, backed by Android Keystore keys that only work after a real authentication.

[![Platform](https://img.shields.io/badge/Android-12%2B%20(API%2031%E2%80%9337)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## How it works

1. **Detection.** An accessibility service watches which app is in the foreground (including split-screen and picture-in-picture windows). When a locked app appears, BioKey covers it straight away with an opaque, screenshot-proof window, then shows its lock screen.
2. **Authentication.** Unlocking uses `BiometricPrompt` with a `CryptoObject`. The prompt is bound to an AES-GCM key in the Android Keystore that can only be used right after a successful authentication. BioKey only treats an unlock as successful once that key has actually encrypted a fresh random challenge. A spoofed or hooked "success" callback unlocks nothing.
3. **Sessions.** An unlocked app stays unlocked according to your re-lock setting (immediately, 30 s, 1 min, 5 min, or until the screen turns off). Turning the screen off always locks everything.

## Security features

| Feature | What it does |
|---|---|
| **Hardware-bound unlock** | Keystore keys (StrongBox when available, otherwise the TEE) gate every unlock. The real storage level is read from `KeyInfo`, not assumed. |
| **Biometric change detection** | If fingerprints or faces are added or removed, the biometric key is invalidated by the platform. BioKey then requires your device PIN/pattern/password before issuing a new key, and logs the event. |
| **Identity Check** | Optional strict mode: Class 3 biometrics only, with no PIN fallback. This protects against someone who has watched or forced your PIN. Adding or removing a fingerprint or face (which needs only the PIN) starts a 30-minute security delay with an alert, after which the PIN confirms the change. |
| **BioKey locks itself** | BioKey's own UI always requires authentication. Pausing protection or missing permissions doesn't skip it. |
| **Step-up authentication** | Anything that *weakens* protection needs an authentication from the last 60 seconds: unlocking an app, pausing, lengthening the re-lock delay, turning off a shield, deleting intruder photos. The settings store refuses these changes on its own, so a missed UI check fails closed. |
| **Self-protection** | BioKey's App info page (force stop, uninstall, clear storage), its accessibility toggle, device-admin page, and the uninstall dialog all require authentication. Pages you open from inside BioKey are pre-authorised. |
| **Uninstall protection** | A device admin with **no policies** makes uninstalling a two-step process (deactivate, then uninstall). Both steps are guarded by self-protection. |
| **Tamper-evident settings** | Settings are sealed with an HMAC under a non-exportable Keystore key. If they're changed outside BioKey, it re-arms protection, alerts you, and logs the event. |
| **Protection alerts** | A high-priority notification fires if the accessibility service stops, the device admin is removed, or tampering is detected. |
| **Timed pause** | Pause protection for 15 minutes or 1 hour (it resumes automatically), or until you turn it back on. Available from the Quick Settings tile, behind authentication. |
| **Intruder photos** | Optional. After two failed unlocks, BioKey takes a silent front-camera photo. Each photo is envelope-encrypted (a random AES-256 key per photo, wrapped by a Keystore key). File names are random, so the storage reveals nothing about which apps you lock. |
| **Notification shield** | Optional. Notifications from locked apps are replaced with "N hidden notifications" until you unlock. Even the app name is hidden on the lock screen. |
| **Security log** | An encrypted, on-device log of pauses, tampering, biometric changes, failed unlocks, and blocked attempts to switch BioKey off. |
| **Crash disguise** | Optional. Shows a fake "app keeps stopping" dialog instead of the lock screen. A double tap or long press on its title opens the real prompt. |
| **Window hardening** | `FLAG_SECURE`, no recents thumbnails, `setHideOverlayWindows`, and rejection of touches through obscuring overlays (tapjacking). |
| **No network access** | BioKey has no `INTERNET` permission. Backup and device-to-device transfer are disabled for all data. |

## Limits (read this)

An app lock running as a regular app can't defend against everything:

- **Root, ADB, or a modified OS** can bypass any app lock, BioKey included.
- **Safe mode** disables third-party accessibility services, so locked apps open without a check until you reboot normally. Protection alerts tell you the service stopped.
- **Self-protection is heuristic.** There's no Android API for "this screen can disable app X", so BioKey recognises those screens by package and content. It's tested against AOSP/Pixel layouts. OEM Settings apps may differ.
- **Notification shield** can't edit another app's notification, only dismiss it. The content is still in the app itself. Ongoing notifications (calls, media, foreground services) can't be dismissed.
- **Intruder photos** are best-effort. The camera may be busy (face unlock), or the OS may block camera access in some states.

## Permissions

| Permission | Why |
|---|---|
| Accessibility service | Detects when a locked app (or a BioKey settings page) is in the foreground. Doesn't read what you type. |
| Device admin (`watch-login` only) | Two-step uninstall. Optionally notices failed screen unlocks, for intruder photos. Can't lock, wipe, or change the device. |
| `USE_BIOMETRIC` | Fingerprint and face authentication. |
| `HIDE_OVERLAY_WINDOWS` | Hides other apps' overlays while BioKey's lock screen is showing. |
| `POST_NOTIFICATIONS` | Protection alerts and hidden-notification stand-ins. |
| `CAMERA` (optional) | Intruder photos. |
| Notification access (optional) | Notification shield. |
| `RECEIVE_BOOT_COMPLETED` | Prepares Keystore keys after a reboot. |

Package visibility is limited to launchable apps, launchers, and keyboards (`<queries>`); BioKey doesn't request `QUERY_ALL_PACKAGES`.

## Architecture

```
feature/main      MainActivity (own lock, step-up provider), main screen, settings, intruder photos
feature/setup     First-run setup and the repair screen for revoked permissions
feature/lock      Accessibility service, lock screen, instant cover, self-protection detector,
                  device admin, crash disguise
feature/notification  Notification shield (NotificationListenerService)
feature/tile      Quick Settings tile
core/security     HardwareKeyManager (Keystore keys, crypto-bound prompts, integrity MAC)
                  SecurityEngine (all authentication), LockStateTracker (sessions, step-up)
                  SecurityVault (sealed settings), SecurityEventLog, ProtectionAlerts
core/intruder     Intruder photo capture and envelope encryption
```

## Building

Requirements: JDK 17 and the Android SDK (compileSdk 37).

```bash
./gradlew :app:assembleDebug
```

### Release builds

Release builds use R8 (minify and resource shrinking). `Log.d/i/v` calls are stripped.

To sign a release, copy the example config and fill in your own keystore:

```bash
cp key.properties.example key.properties   # git-ignored
./gradlew :app:assembleRelease
```

Without `key.properties`, the release APK is left **unsigned**. It never silently falls back to the public debug key. For local testing only, you can opt in explicitly:

```bash
./gradlew :app:assembleRelease -Pbiokey.allowDebugSignedRelease=true
```

## License

Apache 2.0. See [LICENSE](LICENSE).
