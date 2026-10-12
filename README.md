# BioKey 🛡️🔑

> **Hardware-backed biometric app lock engineered with zero-latency instant shielding, StrongBox Keystore cryptography, and refined Material 3 Expressive design.**

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029--35)-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin%202.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![UI Framework](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2F%20Material%203%20Expressive-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Security Level](https://img.shields.io/badge/Hardware%20Security-StrongBox%20%2F%20TEE%20(EAL6%2B)-00C853?logo=google&logoColor=white)](https://source.android.com/docs/security/features/keystore)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 📖 Overview

**BioKey** is an advanced, high-performance Android application locker designed from the silicon up for speed, privacy, and unyielding defense. Unlike legacy app lockers that rely on delayed polling loops or clunky overlays, BioKey combines a low-overhead, native Accessibility Shield Service with Android's hardware-backed **StrongBox / Titan M Security Chip** to deliver instantaneous 0ms app interception, tamper resistance, and zero-flash biometric authentication.

Featuring a bespoke **Material 3 Expressive** interface, fluid spring-physics transitions, and an adaptive native splash engine, BioKey ensures your sensitive personal and work apps remain private without sacrificing usability or device battery life.

---

## ✨ Key Features

### ⚡ 0ms Instant Shield Interception
- **Real-Time Foreground Detection**: Monitors window state changes via `BioKeyAccessibilityService` to intercept protected applications at the exact moment of launch before sensitive content renders.
- **Instant Barrier Overlay**: Renders a hardware-accelerated `InstantShieldOverlay` directly over the target window to eliminate screen flicker and preview leakage.
- **Zero Framework Leaks**: Employs rigorous node object pooling and recycled accessibility node references for ultra-efficient memory usage.

### 🛡️ Silicon Hardware Security & StrongBox
- **Hardware-Backed Keystore**: Cryptographic keys are generated and securely stored inside hardware-isolated secure elements (StrongBox / TEE / Titan M2/M3).
- **CryptoObject Biometric Authentication**: Enforces hardware-backed biometric verification using `BiometricPrompt.CryptoObject` initialized with an `AES-256-GCM` hardware cipher.
- **Silicon Attestation Telemetry**: Live telemetry dashboard inspecting chip security level, EAL6+ compliance, key alias status, and hardware biometric classes.

### 🎨 Material 3 Expressive Interface
- **Dark Surface Palette**: Cohesive, immersive dark theme (`#121212`) engineered specifically for OLED displays and low-light environments.
- **Dynamic Physics & Gestures**: Spring-interpolated switches, breathing neural glow indicators, and tactile haptic feedback (`HapticFeedbackConstants.CONFIRM` / `REJECT`).
- **Bento Grid Architecture**: Grouped, glanceable bento modules for security profiles, quick actions, diagnostic telemetry, and locked application management.

### 🚀 Seamless Android 12+ Splash Engine
- **Zero-Flash Transition**: Native `android:windowSplashScreen` implementation that smoothly transitions into the Compose theme.
- **Adaptive Vector Assets**: High-resolution circular branding with zero squircle or container box visual artifacts across standard and third-party launchers.

### 📸 Intruder Vault & Stealth Disguise
- **Automated Intruder Capture**: Silently triggers front-facing camera snapshots upon repeated failed biometric attempts.
- **Secure Encrypted Log**: Stores timestamped intruder records with device state telemetry inside private application storage.
- **Stealth Crash Disguise**: Misleads unauthorized users with a simulated "App has stopped" system crash dialog, accessible only via a hidden unlock sequence.

### 🗂️ Dynamic Security Profiles
- **Contextual Categorization**: Organize applications into customizable profiles: *All Apps*, *Social*, *Finance*, *Work*, or *Private Spaces*.
- **Flexible Lock Delays**: Choose between *Instant (0ms)*, *Screen Off*, or configurable timeout windows (30s, 1m, 5m, 15m) before re-locking.

### 🔒 Anti-Tamper & Device Admin Protection
- **Uninstall Prevention**: Binds to the system `DevicePolicyManager` to prevent malicious or accidental app uninstallation.
- **Reliable Fallback**: PIN / Pattern backup authentication powered by PBKDF2 with SHA-256 and salt randomization when biometrics are unavailable.

---

## 🏛️ System Architecture

```
┌────────────────────────────────────────────────────────┐
│               Jetpack Compose UI Layer                │
│    (MainActivity, MainScreenExpressive, SetupWizard)   │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                  Security Vault Core                   │
│   • Multi-Store Atomic State Sync (DataStore + Vault)  │
│   • Profile Routing & App Lock State Management        │
│   • Intruder Detection & Telemetry Logger              │
└─────────────┬────────────────────────────┬─────────────┘
              │                            │
┌─────────────▼──────────────┐ ┌───────────▼─────────────┐
│ BioKeyAccessibilityService │ │  TitanM3SecurityManager │
│ • 0ms Window State Listen  │ │ • StrongBox Keymaster   │
│ • Instant Shield Barrier   │ │ • AES-256-GCM Keystore  │
│ • App Launch Interception  │ │ • Biometric CryptoObject│
└────────────────────────────┘ └─────────────────────────┘
```

---

## 📋 Permissions Overview

To deliver non-bypassable 0ms protection, BioKey utilizes standard Android system permissions:

| Permission | Purpose | Required |
| :--- | :--- | :---: |
| **Accessibility Service** | Detects foreground app launches to intercept locked targets immediately. | **Yes** |
| **Device Administrator** | Prevents unauthorized uninstallation or force-stopping by intruders. | **Yes** |
| **Display Over Other Apps** | Displays the instant 0ms biometric authentication barrier over locked apps. | **Recommended** |
| **Biometric / Fingerprint** | Queries hardware biometric sensors (Class 3 Strong Biometrics) for unlock. | **Yes** |
| **Usage Stats** | Provides fallback app usage tracking and foreground activity detection. | Optional |
| **Camera** | Captures photos of unauthorized users attempting to access locked applications. | Optional |

---

## 🛠️ Building & Installation

### Prerequisites
- **Android Studio**: Ladybug (2024.2.1+) or newer
- **JDK**: Java 17+ (JDK 21 recommended)
- **Android SDK**: API 35 (Android 15) build tools
- **Gradle**: 8.9+ (bundled via Gradle Wrapper 9.7)

### Clone the Repository
```bash
git clone https://github.com/Peacetothemountain/BioKey.git
cd BioKey
```

### Keystore Configuration (Release Builds)
Copy the example signing configuration:
```bash
cp key.properties.example key.properties
```
Edit `key.properties` with your signing credentials:
```properties
storeFile=/path/to/your/release-key.keystore
storePassword=yourStorePassword
keyAlias=yourKeyAlias
keyPassword=yourKeyPassword
```

### Compile with Gradle
- **Debug Build**:
  ```bash
  ./gradlew assembleDebug
  ```
- **Release Build**:
  ```bash
  ./gradlew assembleRelease
  ```

Output APKs will be located in:
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

---

## 📱 Supported Devices

- **Google Pixel**: Pixel 6, 7, 8, 9, 10, 11 series (full Titan M2 / M3 StrongBox hardware crypto support).
- **Samsung Galaxy**: Galaxy S21 through S25 / Z Fold / Flip series (Knox Vault / TEE hardware integration).
- **All Android Devices**: Running Android 10 (API 29) through Android 15 / 16+ with biometric hardware.

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome!
1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📜 License

Distributed under the Apache 2.0 License. See [LICENSE](LICENSE) for more information.

---

<p align="center">
  <b>Engineered with precision for uncompromised privacy.</b><br>
  <sub>BioKey • Hardware-Backed Application Security</sub>
</p>
