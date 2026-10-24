#!/bin/sh
# The lock screen's gantlet: the unit tests that must pass before anything reaches main. CI runs
# this first and so does the pre-push hook (.githooks/pre-push), from the repository's root. The
# list is in app/build.gradle.kts (testOptions), the one place the rest of the tests are told to
# leave these out; the build fails if an entry there matches no test, so a renamed class can't
# quietly drop out of it. Reports: app/build/reports/tests/gantlet.
set -e
cd "$(dirname "$0")/.."
./gradlew :app:testDebugUnitTest -Pbiokey.tests=gantlet "$@"
