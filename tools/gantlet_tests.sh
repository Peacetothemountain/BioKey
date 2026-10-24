#!/bin/sh
# The lock screen's gantlet: the unit tests that must pass before anything reaches main. CI runs
# this (the "Lock-screen gantlet" job) and so does the pre-push hook (.githooks/pre-push), from the
# repository's root. Gradle fails a filter that matches no test, so a renamed class can't quietly
# drop out of it.
set -e
cd "$(dirname "$0")/.."
./gradlew :app:testDebugUnitTest "$@" \
  --tests 'com.android.BioKey.Gantlet*' \
  --tests 'com.android.BioKey.LockStateTrackerTest' \
  --tests 'com.android.BioKey.CompatibilityEngineTest' \
  --tests 'com.android.BioKey.AccessibilityOwnTripTest' \
  --tests 'com.android.BioKey.ForegroundRulesTest' \
  --tests 'com.android.BioKey.LockLauncherTest' \
  --tests 'com.android.BioKey.SessionRulesTest' \
  --tests 'com.android.BioKey.RecentsGestureTest' \
  --tests 'com.android.BioKey.OwnSessionTest'
