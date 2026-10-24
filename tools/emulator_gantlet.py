#!/usr/bin/env python3
"""
Emulator gantlet: runs tools/device_gantlet.py on foldable emulators, on more than one Android
version, with both of BioKey's engines (accessibility, compatibility mode). Nothing here touches a
real phone: every adb command names its emulator.

For each foldable profile and Android version it creates an AVD with that foldable's screens and
hinge, boots it clean, sets a PIN and enrolls fingerprint 1, installs BioKey's debug build and the
gantlet's target apps (tools/gantlet-targets), sets BioKey up and locks the targets, then runs the
gantlet under each engine and writes a report.

The profiles are Google's Android images with each phone's screen sizes and hinge: not One UI,
ColorOS or OriginOS, which have no emulator images. Behaviour their own software adds (Samsung
opening a start with launch bounds in a pop-up window, say) is covered by the unit tests
(app/src/test/.../Gantlet*.kt), not here.

    ./gradlew :app:assembleDebug
    ./gradlew -p tools/gantlet-targets assembleDebug
    python tools/emulator_gantlet.py                      # every profile, Android 16 and 17
    python tools/emulator_gantlet.py --profiles fold7 --android 17 --parallel 1
"""
import argparse
import concurrent.futures
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
EMULATOR = os.path.join(SDK, "emulator", "emulator.exe" if os.name == "nt" else "emulator")
AVD_HOME = os.environ.get("ANDROID_AVD_HOME") or os.path.join(os.path.expanduser("~"), ".android", "avd")
PIN = "1234"
TARGET_COLOR = "220,0,120"

# name, inner (unfolded) screen, cover (folded) screen, density, hinge: "book" (folds left to right)
# or "flip" (folds top to bottom). Sizes in pixels, as each phone's spec sheet gives them.
PROFILES = {
    "fold7": ("Galaxy Z Fold7", (1968, 2184), (1080, 2520), 420, "book"),
    "flip7": ("Galaxy Z Flip7", (1080, 2520), (948, 1048), 420, "flip"),
    "findn5": ("OPPO Find N5", (2248, 2480), (1140, 2616), 420, "book"),
    "xfold5": ("vivo X Fold5", (2352, 2480), (1172, 2748), 440, "book"),
    "pixel9fold": ("Pixel 9 Pro Fold", (2076, 2152), (1080, 2424), 390, "book"),
    "pixel10fold": ("Pixel 10 Pro Fold", (2076, 2152), (1080, 2364), 390, "book"),
}
ANDROID = {
    "16": ("android-36", os.path.join("system-images", "android-36", "google_apis", "x86_64")),
    "17": ("android-37.0", os.path.join("system-images", "android-37.0", "google_apis", "x86_64")),
}
TARGETS = ["plain", "splash", "late", "chain", "newtask", "permission", "loop"]
# Their names (tools/gantlet-targets/app/build.gradle.kts): the gantlet taps them in the app drawer.
TARGET_NAMES = {"plain": "Gantlet Plain", "splash": "Gantlet Splash", "late": "Gantlet Late",
                "chain": "Gantlet Chain", "newtask": "Gantlet NewTask", "permission": "Gantlet Permission",
                "loop": "Gantlet Loop"}

# A foldable AVD's settings, those that differ per profile filled in by make_avd.
AVD_CONFIG = """\
AvdId={name}
PlayStore.enabled=no
abi.type=x86_64
avd.ini.displayname={display}
avd.ini.encoding=UTF-8
disk.dataPartition.size=6G
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=no
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.device.manufacturer=Google
hw.device.name=pixel_9_pro_fold
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.initialOrientation=portrait
hw.keyboard=yes
hw.lcd.density={density}
hw.lcd.height={inner_h}
hw.lcd.width={inner_w}
hw.displayRegion.0.1.height={cover_h}
hw.displayRegion.0.1.width={cover_w}
hw.displayRegion.0.1.xOffset=0
hw.displayRegion.0.1.yOffset=0
hw.mainKeys=no
hw.ramSize=3072
hw.sdCard=no
hw.sensor.hinge=yes
hw.sensor.hinge.areas={hinge_areas}
hw.sensor.hinge.count=1
hw.sensor.hinge.defaults=180
hw.sensor.hinge.fold_to_displayRegion.0.1_at_posture=1
hw.sensor.hinge.ranges=0-180
hw.sensor.hinge.resizable.config=1
hw.sensor.hinge.sub_type=1
hw.sensor.hinge.type={hinge_type}
hw.sensor.hinge_angles_posture_definitions=0-30, 30-150, 150-180
hw.sensor.posture_list=1, 2, 3
hw.sensors.orientation=yes
hw.sensors.proximity=yes
image.sysdir.1={sysdir}
showDeviceFrame=no
skin.dynamic=yes
skin.name={inner_w}x{inner_h}
skin.path=_no_skin
tag.display=Google APIs
tag.id=google_apis
vm.heapSize=256
"""


def log(avd, text):
    print(f"[{time.strftime('%H:%M:%S')}] {avd}: {text}", flush=True)


class Emulator:
    def __init__(self, avd, port):
        self.avd = avd
        self.port = port
        self.serial = f"emulator-{port}"
        self.proc = None
        self.display = ""

    def adb(self, *args, check=True, timeout=60):
        out = subprocess.run([ADB, "-s", self.serial] + list(args), capture_output=True, text=True,
                             encoding="utf-8", errors="replace", timeout=timeout)
        if check and out.returncode != 0:
            raise RuntimeError(f"{' '.join(args)}: {out.stderr.strip() or out.stdout.strip()}")
        return out.stdout

    def sh(self, command, check=True, timeout=60):
        return self.adb("shell", command, check=check, timeout=timeout)

    def boot(self):
        logfile = open(os.path.join(ROOT, "tools", "out", f"{self.avd}.emulator.log"), "w")
        self.proc = subprocess.Popen([EMULATOR, "-avd", self.avd, "-port", str(self.port), "-no-window", "-no-audio",
                                      "-no-boot-anim", "-no-snapshot", "-wipe-data", "-gpu", "swiftshader_indirect"],
                                     stdout=logfile, stderr=subprocess.STDOUT)
        deadline = time.time() + 600
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError("the emulator quit while booting (see its log in tools/out)")
            try:
                if self.sh("getprop sys.boot_completed", check=False, timeout=10).strip() == "1":
                    break
            except subprocess.TimeoutExpired:
                pass
            time.sleep(3)
        else:
            raise RuntimeError("didn't boot in 10 minutes")
        time.sleep(15)  # The home screen and the system's own first-boot work.
        self.sh("settings put system screen_off_timeout 1800000; svc power stayon true; input keyevent KEYCODE_WAKEUP; "
                "wm dismiss-keyguard")
        ids = re.findall(r"^Display (\d+)", self.sh("dumpsys SurfaceFlinger --display-id"), re.M)
        self.display = f"-d {ids[0]}" if len(ids) > 1 else ""

    def kill(self):
        try:
            self.adb("emu", "kill", check=False, timeout=20)
        except Exception:
            pass
        if self.proc:
            try:
                self.proc.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    # --- The screen, read through uiautomator. Never while BioKey's accessibility engine is on:
    # UiAutomation suspends every other accessibility service, which BioKey reads as protection
    # stopping, and ends every session.
    def nodes(self):
        for _ in range(5):
            self.sh("rm -f /sdcard/biokey_gantlet_ui.xml", check=False)
            if "dumped to" in self.sh("uiautomator dump /sdcard/biokey_gantlet_ui.xml", check=False):
                root = ET.fromstring(self.sh("cat /sdcard/biokey_gantlet_ui.xml"))
                return list(root.iter("node"))
            time.sleep(1)
        raise RuntimeError("uiautomator couldn't read the screen")

    @staticmethod
    def label(node):
        return node.get("content-desc") or node.get("text") or ""

    @staticmethod
    def center(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        return (x1 + x2) // 2, (y1 + y2) // 2

    def find(self, pattern, nodes=None):
        for node in nodes if nodes is not None else self.nodes():
            if re.fullmatch(pattern, self.label(node), re.I):
                return node
        return None

    def tap(self, node):
        x, y = self.center(node)
        self.sh(f"input tap {x} {y}")

    def tap_text(self, pattern, wait=1.0):
        node = self.find(pattern)
        if node is None:
            return False
        self.tap(node)
        time.sleep(wait)
        return True

    def finger(self):
        self.adb("emu", "finger", "touch", "1")
        time.sleep(0.8)
        self.adb("emu", "finger", "remove", "1")

    def type_pin(self):
        area = self.find(r"PIN area")
        if area is not None:
            self.tap(area)
        for digit in PIN:
            self.sh(f"input keyevent KEYCODE_{digit}")
        self.sh("input keyevent KEYCODE_ENTER")


def make_avd(key, android):
    name, (inner_w, inner_h), (cover_w, cover_h), density, hinge = PROFILES[key]
    target, sysdir = ANDROID[android]
    if not os.path.isdir(os.path.join(SDK, sysdir)):
        raise RuntimeError(f"no Android {android} image at {os.path.join(SDK, sysdir)}")
    avd = f"BioKeyGantlet_{key}_A{android}"
    path = os.path.join(AVD_HOME, avd + ".avd")
    os.makedirs(path, exist_ok=True)
    areas = f"{inner_w // 2}-0-0-{inner_h}" if hinge == "book" else f"0-{inner_h // 2}-{inner_w}-1"
    with open(os.path.join(path, "config.ini"), "w", newline="\n") as f:
        f.write(AVD_CONFIG.format(name=avd, display=f"{name} (Android {android}, gantlet)", density=density,
                                  inner_w=inner_w, inner_h=inner_h, cover_w=cover_w, cover_h=cover_h,
                                  hinge_areas=areas, hinge_type=1 if hinge == "book" else 0,
                                  sysdir=sysdir + os.sep))
    with open(os.path.join(AVD_HOME, avd + ".ini"), "w", newline="\n") as f:
        f.write(f"avd.ini.encoding=UTF-8\npath={path}\npath.rel=avd{os.sep}{avd}.avd\ntarget={target}\n")
    return avd


def set_up(emu):
    """PIN, fingerprint, BioKey and the target apps installed, BioKey set up, the targets locked."""
    emu.sh(f"locksettings set-pin {PIN}")
    log(emu.avd, "enrolling a fingerprint")
    emu.sh("am start -a android.settings.FINGERPRINT_ENROLL")
    time.sleep(3)
    emu.type_pin()
    time.sleep(3)
    for _ in range(6):
        if not (emu.tap_text(r"more") or emu.tap_text(r"i agree|agree")):
            break
    for _ in range(16):
        emu.finger()
        time.sleep(0.6)
        if '"count":1' in emu.sh("dumpsys fingerprint"):
            break
    if '"count":1' not in emu.sh("dumpsys fingerprint"):
        raise RuntimeError("couldn't enroll a fingerprint")
    emu.sh("input keyevent KEYCODE_HOME")

    log(emu.avd, "installing BioKey and the target apps")
    emu.adb("install", "-r", os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk"), timeout=300)
    for target in TARGETS:
        apk = os.path.join(ROOT, "tools", "gantlet-targets", "app", "build", "outputs", "apk", target, "debug",
                           f"app-{target}-debug.apk")
        emu.adb("install", "-r", "-g", apk, timeout=300)

    log(emu.avd, "setting BioKey up")
    pkg = "com.android.BioKey"
    emu.sh(f"appops set {pkg} ACCESS_RESTRICTED_SETTINGS allow; pm grant {pkg} android.permission.POST_NOTIFICATIONS; "
           f"appops set {pkg} GET_USAGE_STATS allow; appops set {pkg} SYSTEM_ALERT_WINDOW allow")
    accessibility(emu, True)
    emu.sh(f"am start -n {pkg}/.feature.main.MainActivity")
    time.sleep(4)
    emu.finger()  # "Confirm it's you" finishes setup.
    time.sleep(3)

    # Locking the targets: the screen is read with uiautomator, so with the accessibility engine
    # off (BioKey runs in compatibility mode meanwhile).
    accessibility(emu, False)
    emu.sh("input keyevent KEYCODE_HOME")
    emu.sh(f"am start -n {pkg}/.feature.main.MainActivity")
    time.sleep(3)
    # BioKey locked itself when its accessibility engine stopped: its prompt is up already (it asks
    # by itself as it opens), or its Unlock button brings it up. Either way, the fingerprint.
    for _ in range(4):
        nodes = emu.nodes()
        if emu.find(r"search apps", nodes) is not None:
            break
        if emu.find(r"verify it's you|touch the fingerprint sensor", nodes) is not None:
            emu.finger()
        elif emu.tap_text(r"unlock", wait=2.5):
            emu.finger()
        time.sleep(3)
    search = emu.find(r"search apps")
    if search is None:
        raise RuntimeError("BioKey's app list didn't open")
    emu.tap(search)
    time.sleep(1)
    emu.sh("input text Gantlet")
    time.sleep(1)
    emu.sh("input keyevent KEYCODE_BACK")  # The keyboard goes; taps reach the list.
    time.sleep(1.5)
    # Which targets are locked is read from BioKey's own settings (the debug build lets run-as read
    # them): a switch's state doesn't always reach uiautomator (Android 17 reports BioKey's as not
    # checkable), and tapping by what it says would switch the same lock on and off.
    def locked_now():
        prefs = emu.sh(f"run-as {pkg} cat shared_prefs/biokey_vault.xml", check=False)
        return {t for t in TARGETS if f"com.biokey.gantlet.{t}<" in prefs}

    locked = locked_now()
    for _ in range(20):
        if len(locked) >= len(TARGETS):
            break
        switches = [n for n in emu.nodes() if re.fullmatch(r"Lock Gantlet \w+", emu.label(n))]
        todo = [s for s in switches if emu.label(s).split()[-1].lower() not in locked]
        # A row just below the screen's edge is listed with no size: scrolled to, not tapped at 0,0.
        todo = [s for s in todo if len(set(re.findall(r"\d+", s.get("bounds", "")))) > 1 and emu.center(s) != (0, 0)]
        if todo:
            emu.tap(todo[0])
            time.sleep(1.5)
        else:
            emu.sh("input swipe 500 1600 500 900 400")
            time.sleep(1.5)
        locked = locked_now()
    if len(locked) < len(TARGETS):
        raise RuntimeError(f"locked only {sorted(locked)}")
    emu.sh("input keyevent KEYCODE_HOME")


def accessibility(emu, on):
    service = "com.android.BioKey/com.android.BioKey.feature.lock.BioKeyAccessibilityService"
    emu.sh(f"settings put secure enabled_accessibility_services {service if on else 'null'}; "
           f"settings put secure accessibility_enabled {1 if on else 0}")
    time.sleep(4)


def run_gantlet(emu, engine, out_dir):
    args = [sys.executable, "-u", os.path.join(ROOT, "tools", "device_gantlet.py"), "--serial", emu.serial,
            "--hinge", "--fingerprint", "--drawer", "--watch-exposure", TARGET_COLOR,
            "--may-go-home", "com.biokey.gantlet.loop"]
    for target in TARGETS:
        args += ["--locked", f"com.biokey.gantlet.{target}={TARGET_NAMES[target]}"]
    path = os.path.join(out_dir, f"{emu.avd}.{engine}.log")
    with open(path, "w", encoding="utf-8") as f:
        code = subprocess.call(args, stdout=f, stderr=subprocess.STDOUT)
    text = open(path, encoding="utf-8").read()
    summary = re.search(r"(\d+) of (\d+) scenarios passed, (\d+) failed, (\d+) skipped", text)
    failures = re.findall(r"^\[FAIL\] (.*)\n((?:\s+! .*\n)*)", text, re.M)
    return {"engine": engine, "code": code, "summary": summary.groups() if summary else None,
            "failures": failures, "log": path}


def run_profile(key, android, port, out_dir):
    avd = make_avd(key, android)
    emu = Emulator(avd, port)
    result = {"avd": avd, "profile": PROFILES[key][0], "android": android, "runs": [], "error": None}
    try:
        log(avd, "booting")
        emu.boot()
        set_up(emu)
        for engine in ("compatibility", "accessibility"):
            accessibility(emu, engine == "accessibility")
            emu.sh("input keyevent KEYCODE_HOME")
            log(avd, f"gantlet, {engine} engine")
            run = run_gantlet(emu, engine, out_dir)
            result["runs"].append(run)
            log(avd, f"{engine}: " + (", ".join(run["summary"]) if run["summary"] else "no summary"))
    except Exception as e:
        result["error"] = str(e)
        log(avd, f"ERROR {e}")
    finally:
        emu.kill()
    return result


def report(results, out_dir):
    lines = ["# BioKey emulator gantlet", "",
             "Google's Android images with each foldable's screens and hinge (not the makers' own software).", "",
             "| Foldable | Android | Engine | Passed | Failed | Skipped |", "|---|---|---|---|---|---|"]
    for r in results:
        if r["error"] and not r["runs"]:
            lines.append(f"| {r['profile']} | {r['android']} | (didn't run) | | | {r['error']} |")
        for run in r["runs"]:
            p, total, f, s = run["summary"] or ("?", "?", "?", "?")
            lines.append(f"| {r['profile']} | {r['android']} | {run['engine']} | {p} of {total} | {f} | {s} |")
    lines.append("")
    for r in results:
        for run in r["runs"]:
            for name, problems in run["failures"]:
                lines.append(f"- **{r['profile']}, Android {r['android']}, {run['engine']}**: {name}")
                lines += [f"  - {p.strip().lstrip('! ')}" for p in problems.splitlines() if p.strip()]
        if r["error"]:
            lines.append(f"- **{r['profile']}, Android {r['android']}**: setup stopped: {r['error']}")
    path = os.path.join(out_dir, "report.md")
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--profiles", default=",".join(PROFILES), help=f"comma-separated: {', '.join(PROFILES)}")
    parser.add_argument("--android", default="16,17", help="comma-separated Android versions: 16, 17")
    parser.add_argument("--parallel", type=int, default=2, help="emulators at once (each takes about 3 GB)")
    args = parser.parse_args()
    out_dir = os.path.join(ROOT, "tools", "out")
    os.makedirs(out_dir, exist_ok=True)
    jobs = [(k, a) for a in args.android.split(",") for k in args.profiles.split(",")]
    results = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.parallel) as pool:
        futures = [pool.submit(run_profile, k, a, 5600 + 2 * i, out_dir) for i, (k, a) in enumerate(jobs)]
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    results.sort(key=lambda r: (r["android"], r["profile"]))
    print("\nReport: " + report(results, out_dir))
    failed = any(r["error"] or any(run["code"] for run in r["runs"]) for r in results)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
