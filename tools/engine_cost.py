#!/usr/bin/env python3
"""
Engine cost: how much CPU time BioKey's process spends while nothing is being locked, the main
thing it costs a battery. Read from /proc/<pid>/stat (user + system time) over a fixed spell in
each of the states a phone spends most of its day in, under each engine:

    home        the home screen in front, screen on
    other app   an app that isn't locked in front (Settings), screen on
    screen off  the screen off (an emulator never deep-sleeps: an upper bound)

Prints milliseconds of CPU per minute for each, and with --json writes them to a file to compare
later runs against (a change to either engine's polling should move these, and only down).

ON AN EMULATOR OR A TEST PHONE: it switches BioKey's accessibility service off and on (that's how
compatibility mode is chosen), which the owner's phone mustn't have done to it.

    python tools/engine_cost.py --serial emulator-5554
    python tools/engine_cost.py --serial emulator-5554 --seconds 120 --json tools/out/engine-cost.json
"""
import argparse
import json
import subprocess
import sys
import time

PKG = "com.nbdesigns.biokey"
SERVICE = f"{PKG}/com.android.BioKey.feature.lock.BioKeyAccessibilityService"
SERIAL = None


def sh(command, check=True):
    out = subprocess.run(["adb", "-s", SERIAL, "shell", command], capture_output=True, encoding="utf-8",
                         errors="replace", timeout=60)
    if check and out.returncode != 0:
        raise RuntimeError(f"adb shell {command}: {out.stderr.strip()}")
    return out.stdout.strip()


def cpu_ticks():
    """BioKey's process: (pid, user + system ticks), or None if it isn't running."""
    pid = sh(f"pidof {PKG}", check=False).split()
    if not pid:
        return None
    fields = sh(f"cat /proc/{pid[0]}/stat").rsplit(")", 1)[1].split()
    # After the name: state is field 3, utime 14, stime 15 (1-based), so 11 and 12 here.
    return pid[0], int(fields[11]) + int(fields[12])


def engine(accessibility):
    if accessibility:
        sh(f"settings put secure enabled_accessibility_services {SERVICE}; settings put secure accessibility_enabled 1")
    else:
        sh("settings put secure enabled_accessibility_services null; settings put secure accessibility_enabled 0")
    # Compatibility mode's watcher starts once the accessibility service is gone (the handover).
    time.sleep(8)


def screen_on():
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    if "isKeyguardShowing=true" not in sh("dumpsys window | grep isKeyguardShowing", check=False):
        return
    # Locked by the screen-off spell: an emulator's enrolled fingerprint 1 opens it (as the gantlet does).
    if SERIAL.startswith("emulator-"):
        subprocess.run(["adb", "-s", SERIAL, "emu", "finger", "touch", "1"], capture_output=True, timeout=20)
        time.sleep(0.8)
        subprocess.run(["adb", "-s", SERIAL, "emu", "finger", "remove", "1"], capture_output=True, timeout=20)
        time.sleep(1.5)
    if "isKeyguardShowing=true" in sh("dumpsys window | grep isKeyguardShowing", check=False):
        raise RuntimeError("the phone is locked: unlock it, or use an emulator with fingerprint 1 enrolled")


def measure(name, seconds, hz):
    start = cpu_ticks()
    if start is None:
        raise RuntimeError("BioKey isn't running")
    time.sleep(seconds)
    end = cpu_ticks()
    if end is None or end[0] != start[0]:
        raise RuntimeError(f"{name}: BioKey's process restarted during the measurement")
    ms_per_min = (end[1] - start[1]) * 1000.0 / hz * 60.0 / seconds
    print(f"  {name:<11} {ms_per_min:8.1f} ms of CPU per minute")
    return round(ms_per_min, 1)


def main():
    global SERIAL
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="an emulator or test phone with BioKey set up")
    parser.add_argument("--seconds", type=int, default=90, help="each measurement's length (default 90)")
    parser.add_argument("--json", help="write the results here")
    args = parser.parse_args()
    SERIAL = args.serial

    hz = int(sh("getconf CLK_TCK", check=False) or 100)
    before = sh("settings get secure enabled_accessibility_services", check=False)
    results = {}
    try:
        for name, accessibility in (("accessibility", True), ("compatibility", False)):
            print(f"{name}:")
            engine(accessibility)
            screen_on()
            sh("input keyevent KEYCODE_HOME")
            time.sleep(3)
            r = {"home": measure("home", args.seconds, hz)}
            sh("am start -a android.settings.SETTINGS")
            time.sleep(3)
            r["other app"] = measure("other app", args.seconds, hz)
            sh("input keyevent KEYCODE_HOME")
            sh("input keyevent KEYCODE_SLEEP")
            time.sleep(3)
            r["screen off"] = measure("screen off", args.seconds, hz)
            screen_on()
            results[name] = r
    finally:
        # As it was: the accessibility service on or off.
        if before and before != "null":
            sh(f"settings put secure enabled_accessibility_services {before}; settings put secure accessibility_enabled 1")
        else:
            engine(False)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"device": sh("getprop ro.product.model"), "seconds": args.seconds, "ms_cpu_per_minute": results}, f, indent=2)
        print(f"Written to {args.json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
