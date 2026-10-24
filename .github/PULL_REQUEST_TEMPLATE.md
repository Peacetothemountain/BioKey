## What and why

<!-- What this changes, and the problem it solves. Link an issue if there is one. -->

## Tests

<!-- Tick what you ran. The gantlet is required for anything that touches the app. -->

- [ ] Lock-screen gantlet: `sh tools/gantlet_tests.sh`
- [ ] All unit tests: `./gradlew :app:testDebugUnitTest`
- [ ] Device gantlet (`tools/device_gantlet.py`) on: <!-- phone or emulator, Android version, engine -->
- [ ] Emulator gantlet (`tools/emulator_gantlet.py`)
- [ ] Tried by hand on: <!-- phone, Android version, accessibility or compatibility mode -->
- [ ] Documentation only: nothing to run

## Checklist

- [ ] Nothing new is logged in release builds that names an app (`Log.e` included).
- [ ] If this changes what BioKey reads, keeps or shares: `PRIVACY.md`, `docs/privacy.md` and the in-app disclosures are updated.
- [ ] If this adds a gantlet test: it's in the list in `app/build.gradle.kts`.
