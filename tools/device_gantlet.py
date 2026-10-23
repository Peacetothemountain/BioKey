#!/usr/bin/env python3
"""
Device gantlet: drives a phone with BioKey installed over adb and checks that a locked app, opened
every way people open apps, comes up under BioKey's lock screen and stays there, without being
sent Home, on every screen size a foldable switches between.

It never authenticates and never changes BioKey's settings: the apps passed with --locked must
already be locked in BioKey.

Foldables (--emulate-foldables, off by default): folding is emulated by resizing the screen
(wm size / wm density) to a foldable's cover and main screens; the phone's own size and density
are restored at the end, whatever happens. ONLY ON A TEST PHONE OR AN EMULATOR: a main-screen
size makes Pixel Launcher (and other Launcher3 home screens) lay the home screen out on its
tablet grid, and it deletes whatever doesn't fit there (a 5x6 phone grid's bottom row, on a 6x5
tablet one) from the phone's own layout. Restoring the size doesn't bring those back.

    python tools/device_gantlet.py --locked com.twitter.android=X --locked com.google.android.apps.messaging=Messages

Exit status 0 when every scenario passes.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

BIOKEY = "com.android.BioKey"
LOCK_SCREEN = "com.android.BioKey.feature.lock.LockScreenActivity"
LOCK_SCREEN_SHORT = "com.android.BioKey/.feature.lock.LockScreenActivity"

# Screens to emulate: (name, size, density). A Galaxy Z Fold7's cover and main screens, turned,
# and a Galaxy Z Flip7's cover screen.
FOLD7_COVER = ("Galaxy Z Fold7 cover screen", "1080x2520", 420)
FOLD7_INNER = ("Galaxy Z Fold7 main screen", "1968x2184", 374)
FOLD7_INNER_LAND = ("Galaxy Z Fold7 main screen, landscape", "2184x1968", 374)
FLIP7_COVER = ("Galaxy Z Flip7 cover screen", "948x1048", 420)

ADB = None
SERIAL = None
# Apps that reopen themselves by design (the gantlet's "loop" app): they may end up Home, given up on.
MAY_GO_HOME = set()
# The emulator's fingerprint (--fingerprint), to unlock the phone when something locked it.
FINGERPRINT = None


def adb(*args, check=True, timeout=30):
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    out = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")
    if check and out.returncode != 0:
        raise RuntimeError(f"{' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def sh(command, check=True, timeout=30):
    return adb("shell", command, check=check, timeout=timeout)


def find_adb():
    for candidate in [shutil.which("adb"),
                      os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
                      os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")]:
        if candidate and os.path.exists(candidate):
            return candidate
    sys.exit("adb not found: put it on PATH or set ANDROID_HOME")


class Display:
    """The phone's own size and density overrides, put back at the end."""

    def __init__(self):
        out = sh("wm size") + sh("wm density")
        self.size = re.search(r"Override size: (\S+)", out)
        self.density = re.search(r"Override density: (\d+)", out)
        self.physical_size = re.search(r"Physical size: (\S+)", out).group(1)
        self.physical_density = int(re.search(r"Physical density: (\d+)", out).group(1))

    def emulate(self, screen):
        name, size, density = screen
        sh(f"wm size {size}")
        sh(f"wm density {density}")
        time.sleep(2.5)  # Everything on screen is laid out again.

    def restore(self):
        sh(f"wm size {self.size.group(1)}" if self.size else "wm size reset")
        sh(f"wm density {self.density.group(1)}" if self.density else "wm density reset")


class Hinge:
    """An emulator's hinge: folds and unfolds a foldable AVD for real (a posture change, not a resize)."""

    def fold(self):
        adb("emu", "fold")
        time.sleep(3)

    def unfold(self):
        adb("emu", "unfold")
        time.sleep(3)


class Fingerprint:
    """An emulator's fingerprint sensor, with finger 1 enrolled: authenticates whatever prompt is up."""

    def touch(self):
        adb("emu", "finger", "touch", "1")
        time.sleep(0.8)
        adb("emu", "finger", "remove", "1")


def lock_and_unlock_phone(fingerprint):
    """Locks the phone (which ends every session in BioKey) and unlocks it again with the fingerprint."""
    sh("input keyevent KEYCODE_SLEEP")
    time.sleep(2)
    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1.5)
    for _ in range(3):
        fingerprint.touch()
        time.sleep(1.5)
        if "isKeyguardShowing=true" not in sh("dumpsys window | grep isKeyguardShowing"):
            return
    raise RuntimeError("Couldn't unlock the phone with the fingerprint")


class ExposureWatcher:
    """
    Watches the screen, on the device itself, for a locked app shown uncovered: the gantlet's target
    apps fill the screen with one loud colour, and BioKey's covers read as black (they are secure).
    Each frame samples a 7x7 grid of pixels; a frame where a large part of them have the colour is
    one where the app showed. Only for apps painted that colour (tools/gantlet-targets).
    """

    FLAG = "/data/local/tmp/biokey_gantlet.watch"
    SCRIPT = (
        "f=/data/local/tmp/biokey_gantlet.raw; "
        "while [ -f {flag} ]; do "
        "screencap {display} $f 2>/dev/null || continue; "
        "set -- $(od -A n -t u4 -N 8 $f); w=$1; h=$2; line=\"F $(date +%s.%N)\"; "
        "for gy in 1 2 3 4 5 6 7; do for gx in 1 2 3 4 5 6 7; do "
        "o=$(( 16 + ((h*gy/8)*w + w*gx/8)*4 )); "
        "line=\"$line $(od -A n -t u1 -j $o -N 3 $f | tr -s ' ' ',')\"; "
        "done; done; echo \"$line\"; done"
    )

    def __init__(self, rgb):
        self.rgb = rgb
        ids = re.findall(r"^Display (\d+)", sh("dumpsys SurfaceFlinger --display-id"), re.M)
        self.display = f"-d {ids[0]}" if len(ids) > 1 else ""
        self.proc = None

    def start(self):
        sh(f"touch {self.FLAG}")
        cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + [
            "shell", self.SCRIPT.format(flag=self.FLAG, display=self.display)]
        self.proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)

    def stop(self):
        """Stops watching. Returns (frames, frames where the app showed)."""
        sh(f"rm -f {self.FLAG}", check=False)
        try:
            out, _ = self.proc.communicate(timeout=15)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            out, _ = self.proc.communicate()
        frames = shown = 0
        for line in out.splitlines():
            if not line.startswith("F "):
                continue
            pixels = [list(map(int, p.strip(",").split(","))) for p in line.split()[2:] if p.strip(",").count(",") == 2]
            if not pixels:
                continue
            frames += 1
            if sum(self._is_app(p) for p in pixels) >= 0.35 * len(pixels):
                shown += 1
        return frames, shown

    def _is_app(self, pixel):
        # The colour, or the colour dimmed (under a dialog's scrim).
        r, g, b = pixel
        tr, tg, tb = self.rgb
        if r < 60:
            return False
        scale = r / tr
        return abs(g - tg * scale) <= 40 and abs(b - tb * scale) <= 40 * max(scale, 0.5)


class Events:
    """The window manager's event log, from a mark on."""

    LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+(wm_\w+)\s*:\s*\[(.*)\]")

    def mark(self):
        sh("logcat -b events -c")

    def read(self):
        events = []
        for line in sh("logcat -b events -d -v epoch").splitlines():
            m = self.LINE.match(line)
            if m:
                events.append((float(m.group(1)), m.group(2), m.group(3)))
        return events


def device_now():
    """The phone's clock: event times are its, not this computer's."""
    return float(sh("date +%s.%N").strip())


def top_resumed():
    out = sh("dumpsys activity activities")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", out) or \
        re.search(r"ResumedActivity: ActivityRecord\{\S+ \S+ (\S+)", out)
    return m.group(1) if m else ""


def launcher_package():
    out = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    return out.strip().splitlines()[-1].split("/")[0]


def launch_component(pkg):
    out = sh(f"cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER {pkg}")
    return out.strip().splitlines()[-1]


def home():
    sh("input keyevent KEYCODE_HOME")
    time.sleep(0.8)


def go_home_fully():
    """Out of any lock screen or prompt: Home until the home screen is in front."""
    for _ in range(4):
        # Folding can lock the phone (the "continue apps when folded" setting): with a fingerprint
        # to unlock it, carry on; without one, the scenario stops here.
        if FINGERPRINT is not None and "isKeyguardShowing=true" in sh("dumpsys window | grep isKeyguardShowing"):
            lock_and_unlock_phone(FINGERPRINT)
        home()
        if launcher_package() in top_resumed():
            return
    raise RuntimeError("Couldn't get back to the home screen")


def open_app(pkg):
    sh(f"am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n {launch_component(pkg)}")


def find_app_on_home_screen(label):
    """Where [label]'s icon is on the home screen, opening the folder it's in if need be; None if nowhere."""
    def dump():
        # uiautomator sometimes finds no window to read ("null root node"), mid-animation: again.
        for _ in range(5):
            sh("rm -f /sdcard/biokey_gantlet_ui.xml", check=False)
            if "dumped to" in sh("uiautomator dump /sdcard/biokey_gantlet_ui.xml", check=False):
                return sh("cat /sdcard/biokey_gantlet_ui.xml")
            time.sleep(1)
        raise RuntimeError("uiautomator couldn't read the home screen")

    def center(bounds):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
        return (x1 + x2) // 2, (y1 + y2) // 2

    def find(xml, desc):
        # An icon's name can carry more after a comma ("Messages, 2 notifications").
        m = re.search(r'content-desc="' + re.escape(desc) + r'(?:,[^"]*)?"[^>]*?bounds="([^"]+)"', xml)
        return center(m.group(1)) if m else None

    xml = dump()
    spot = find(xml, label)
    if spot is None:
        for folder in re.findall(r'content-desc="(Folder: [^"]+)"', xml):
            go_home_fully()
            tap = find(dump(), folder)
            if tap is None:
                continue
            sh(f"input tap {tap[0]} {tap[1]}")
            time.sleep(1.0)
            spot = find(dump(), label)
            if spot:
                break
    return spot


def app_label(pkg):
    out = sh(f"dumpsys package {pkg}")
    m = re.search(r"nonLocalizedLabel=([^\s]+)", out)
    return m.group(1) if m and m.group(1) != "null" else None


class Result:
    def __init__(self, name):
        self.name = name
        self.problems = []
        self.notes = []
        self.skipped = False

    def skip(self, why):
        self.skipped = True
        self.notes = [f"skipped: {why}"]

    def fail(self, text):
        self.problems.append(text)

    def note(self, text):
        self.notes.append(text)


def check_locked(result, events, pkg, launcher, start, expect_lock=True):
    """The checks every scenario that opens a locked app makes."""
    # wm_set_resumed_activity: [display, package/activity, reason]: what the window manager put in front.
    resumed = [(t, body.split(",")[1]) for t, tag, body in events if tag == "wm_set_resumed_activity"]
    lock_resumes = [t for t, what in resumed if what == LOCK_SCREEN_SHORT]
    app_resumes = [t for t, what in resumed if what.startswith(pkg + "/")]
    lock_finishes = [t for t, tag, body in events if tag == "wm_finish_activity" and LOCK_SCREEN_SHORT in body]
    homes = [t for t, what in resumed if what.startswith(launcher + "/")]
    if not expect_lock:
        return
    if pkg in MAY_GO_HOME:
        # Reopens itself for ever: covered at first, given up on (Home) in the end, never shown.
        if not lock_resumes:
            result.fail("BioKey's lock screen never came up")
        else:
            result.note(f"lock screen up {lock_resumes[0] - start:.2f}s after the launch; "
                        f"in front at the end: {top_resumed() or 'nothing'}")
        return
    if not lock_resumes:
        result.fail("BioKey's lock screen never came up")
        return
    first_lock = lock_resumes[0]
    result.note(f"lock screen up {first_lock - start:.2f}s after the launch")
    if app_resumes:
        result.note(f"app in front {first_lock - app_resumes[0]:.2f}s before its lock screen (under the shield)")
    bounced = [t for t in homes if t > first_lock]
    if bounced:
        result.fail(f"sent Home {bounced[0] - first_lock:.2f}s after the lock screen came up")
    closed = [t for t in lock_finishes if t > first_lock]
    if closed:
        result.fail(f"the lock screen closed by itself {closed[0] - first_lock:.2f}s after coming up")
    top = top_resumed()
    if LOCK_SCREEN_SHORT not in top:
        result.fail(f"in front at the end: {top or 'nothing'}, not the lock screen")


def run_open(result, pkg, launcher, how, settle=4.0):
    events = Events()
    events.mark()
    start = device_now()
    how()
    time.sleep(settle)
    check_locked(result, events.read(), pkg, launcher, start)


def scenarios(pkg, launcher, display, label, hinge=None, fingerprint=None):
    def cold_start(r):
        go_home_fully()
        sh(f"am force-stop {pkg}")
        run_open(r, pkg, launcher, lambda: open_app(pkg))

    def warm_start(r):
        go_home_fully()
        open_app(pkg)  # Warms it up (and covers it).
        time.sleep(3)
        go_home_fully()
        run_open(r, pkg, launcher, lambda: open_app(pkg))

    def tapped_on_home_screen(r):
        if not label:
            r.skip("give its icon's name: --locked package=Name")
            return
        go_home_fully()
        sh(f"am force-stop {pkg}")
        spot = find_app_on_home_screen(label)
        if spot is None:
            r.skip(f"no '{label}' icon on the home screen or in its folders")
            return
        run_open(r, pkg, launcher, lambda: sh(f"input tap {spot[0]} {spot[1]}"))

    def tapped_three_times_in_a_row(r):
        # As in the report: open, back out, open again, three times.
        for attempt in range(3):
            go_home_fully()
            sub = Result(f"attempt {attempt + 1}")
            run_open(sub, pkg, launcher, lambda: open_app(pkg), settle=3.0)
            for p in sub.problems:
                r.fail(f"attempt {attempt + 1}: {p}")

    def back_at_once_after_home(r):
        # Home from the lock screen, then straight back (a quick switch's timing).
        go_home_fully()
        open_app(pkg)
        time.sleep(3)
        home()
        run_open(r, pkg, launcher, lambda: open_app(pkg))

    def opened_on(screen):
        def run(r):
            if not display.may_emulate:
                r.skip("screen sizes are only emulated with --emulate-foldables (see the warning)")
                return
            go_home_fully()
            display.emulate(screen)
            try:
                go_home_fully()
                sh(f"am force-stop {pkg}")
                run_open(r, pkg, launcher, lambda: open_app(pkg))
            finally:
                go_home_fully()
                display.restore()
                time.sleep(2)
        run.__name__ = f"cold start on the {screen[0]}"
        return run

    def folded_and_unfolded_under_the_lock_screen(r):
        if not display.may_emulate:
            r.skip("screen sizes are only emulated with --emulate-foldables (see the warning)")
            return
        go_home_fully()
        events = Events()
        events.mark()
        start = device_now()
        open_app(pkg)
        time.sleep(3)
        try:
            for screen in (FOLD7_COVER, FOLD7_INNER, FOLD7_INNER_LAND, FOLD7_INNER, FOLD7_COVER, FLIP7_COVER):
                display.emulate(screen)
                top = top_resumed()
                if LOCK_SCREEN_SHORT not in top:
                    r.fail(f"on the {screen[0]}: {top or 'nothing'} in front, not the lock screen")
        finally:
            display.restore()
            time.sleep(2.5)
        check_locked(r, events.read(), pkg, launcher, start)

    def hinge_cold_start(folded):
        def run(r):
            if hinge is None:
                r.skip("folds for real only on a foldable emulator (--hinge)")
                return
            go_home_fully()
            hinge.fold() if folded else hinge.unfold()
            try:
                go_home_fully()
                sh(f"am force-stop {pkg}")
                run_open(r, pkg, launcher, lambda: open_app(pkg))
            finally:
                go_home_fully()
                hinge.unfold()
        return run

    def hinge_under_the_lock_screen(r):
        if hinge is None:
            r.skip("folds for real only on a foldable emulator (--hinge)")
            return
        go_home_fully()
        hinge.unfold()
        events = Events()
        events.mark()
        start = device_now()
        open_app(pkg)
        time.sleep(3)
        try:
            for step, move in (("folded", hinge.fold), ("unfolded", hinge.unfold), ("folded again", hinge.fold),
                               ("unfolded again", hinge.unfold)):
                move()
                top = top_resumed()
                if pkg not in MAY_GO_HOME and LOCK_SCREEN_SHORT not in top:
                    r.fail(f"{step}: {top or 'nothing'} in front, not the lock screen")
        finally:
            hinge.unfold()
        check_locked(r, events.read(), pkg, launcher, start)

    def unlocked_then_folded_stays_open(r):
        """Unlocked with the fingerprint, then folded and unfolded: still open, not asked again."""
        if hinge is None or fingerprint is None:
            r.skip("needs a foldable emulator with a fingerprint (--hinge --fingerprint)")
            return
        if pkg in MAY_GO_HOME:
            r.skip("reopens itself by design")
            return
        go_home_fully()
        hinge.unfold()
        open_app(pkg)
        time.sleep(2.5)
        fingerprint.touch()
        time.sleep(3)
        if not top_resumed().startswith(pkg + "/"):
            r.fail(f"not open after the fingerprint: {top_resumed() or 'nothing'} in front")
            return
        events = Events()
        events.mark()
        try:
            for step, move in (("folded", hinge.fold), ("unfolded", hinge.unfold)):
                move()
                top = top_resumed()
                if not top.startswith(pkg + "/"):
                    r.fail(f"{step}: {top or 'nothing'} in front, not the unlocked app")
        finally:
            hinge.unfold()
        if any(tag == "wm_set_resumed_activity" and LOCK_SCREEN_SHORT in body for _, tag, body in events.read()):
            r.fail("asked to unlock again after folding")
        # Locking the phone ends the session: the next open is covered again.
        lock_and_unlock_phone(fingerprint)
        go_home_fully()
        sub = Result("after locking the phone")
        run_open(sub, pkg, launcher, lambda: open_app(pkg))
        for p in sub.problems:
            r.fail(f"after locking the phone: {p}")

    def home_from_the_lock_screen_stays_home(r):
        if pkg in MAY_GO_HOME:
            r.skip("reopens itself by design")
            return
        go_home_fully()
        open_app(pkg)
        time.sleep(3)
        events = Events()
        events.mark()
        go_home_fully()
        time.sleep(3)
        got = events.read()
        if launcher not in top_resumed():
            r.fail(f"not on the home screen after Home: {top_resumed()}")
        if any(tag == "wm_on_resume_called" and LOCK_SCREEN in body for _, tag, body in got):
            r.fail("the lock screen came back by itself after Home")

    def back_from_the_lock_screen(r):
        if pkg in MAY_GO_HOME:
            r.skip("reopens itself by design")
            return
        go_home_fully()
        open_app(pkg)
        time.sleep(3)
        for _ in range(3):
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1)
            if launcher in top_resumed():
                break
        if launcher not in top_resumed():
            r.fail(f"Back didn't leave the lock screen: {top_resumed()} in front")
        elif pkg in top_resumed():
            r.fail("Back left the app open")

    return [
        ("cold start", cold_start),
        ("warm start", warm_start),
        ("tapped on the home screen (in a folder)", tapped_on_home_screen),
        ("opened three times in a row", tapped_three_times_in_a_row),
        ("Home, then straight back", back_at_once_after_home),
        (f"cold start on the {FOLD7_COVER[0]}", opened_on(FOLD7_COVER)),
        (f"cold start on the {FOLD7_INNER[0]}", opened_on(FOLD7_INNER)),
        (f"cold start on the {FOLD7_INNER_LAND[0]}", opened_on(FOLD7_INNER_LAND)),
        (f"cold start on the {FLIP7_COVER[0]}", opened_on(FLIP7_COVER)),
        ("folded and unfolded under the lock screen", folded_and_unfolded_under_the_lock_screen),
        ("cold start folded (hinge)", hinge_cold_start(folded=True)),
        ("cold start unfolded (hinge)", hinge_cold_start(folded=False)),
        ("folded and unfolded under the lock screen (hinge)", hinge_under_the_lock_screen),
        ("Home from the lock screen stays Home", home_from_the_lock_screen_stays_home),
        ("Back from the lock screen", back_from_the_lock_screen),
        ("unlocked, folded and unfolded: stays open; locking the phone locks it", unlocked_then_folded_stays_open),
    ]


def main():
    global ADB, SERIAL, FINGERPRINT
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--locked", action="append", required=True, help="a package locked in BioKey, optionally with its icon's name: com.twitter.android=X (repeatable)")
    parser.add_argument("--serial", help="adb serial, when more than one device is connected")
    parser.add_argument("--only", help="run only scenarios whose name contains this")
    parser.add_argument("--emulate-foldables", action="store_true",
                        help="resize the screen to foldables' screens. Test phones and emulators only: the home "
                             "screen's launcher can delete icons and folders that don't fit a tablet-sized grid")
    parser.add_argument("--hinge", action="store_true", help="a foldable emulator: fold and unfold it for real")
    parser.add_argument("--fingerprint", action="store_true",
                        help="an emulator with fingerprint 1 enrolled: also unlock apps, and lock the phone")
    parser.add_argument("--watch-exposure", metavar="R,G,B",
                        help="fail a scenario if the screen shows this colour uncovered (tools/gantlet-targets "
                             "apps: 220,0,120)")
    parser.add_argument("--may-go-home", action="append", default=[],
                        help="a package that reopens itself by design: covered, then given up on (Home)")
    args = parser.parse_args()
    ADB = find_adb()
    SERIAL = args.serial
    MAY_GO_HOME.update(args.may_go_home)
    hinge = Hinge() if args.hinge else None
    fingerprint = FINGERPRINT = Fingerprint() if args.fingerprint else None

    if "Awake" not in sh("dumpsys power | grep mWakefulness="):
        sys.exit("Wake and unlock the phone first")
    if "isKeyguardShowing=true" in sh("dumpsys window | grep isKeyguardShowing"):
        sys.exit("Unlock the phone first")
    version = re.search(r"versionName=(\S+)", sh(f"dumpsys package {BIOKEY}"))
    launcher = launcher_package()
    print(f"BioKey {version.group(1) if version else '?'} on {sh('getprop ro.product.model').strip()}, "
          f"Android {sh('getprop ro.build.version.release').strip()}, home screen {launcher}")

    display = Display()
    display.may_emulate = args.emulate_foldables
    if display.may_emulate:
        print("Emulating foldables' screens: the home screen's layout may lose icons that don't fit them.")
    watcher = ExposureWatcher(tuple(map(int, args.watch_exposure.split(",")))) if args.watch_exposure else None
    results = []
    try:
        for spec in args.locked:
            pkg, _, label = spec.partition("=")
            label = label or app_label(pkg)
            for name, fn in scenarios(pkg, launcher, display, label, hinge, fingerprint):
                if args.only and args.only.lower() not in name.lower():
                    continue
                result = Result(f"{pkg}: {name}")
                # The unlock scenario shows the app on purpose, once unlocked.
                watching = watcher is not None and not name.startswith("unlocked")
                if watching:
                    watcher.start()
                try:
                    fn(result)
                except Exception as e:  # A scenario that can't run is a failure, not a crash of the run.
                    result.fail(f"couldn't run: {e}")
                finally:
                    if watching:
                        frames, shown = watcher.stop()
                        if shown and not result.skipped:
                            result.fail(f"the app showed uncovered in {shown} of {frames} screenshots")
                        elif frames and not result.skipped:
                            result.note(f"never shown uncovered ({frames} screenshots)")
                results.append(result)
                status = "FAIL" if result.problems else "skip" if result.skipped else "pass"
                print(f"[{status}] {result.name}")
                for p in result.problems:
                    print(f"         ! {p}")
                for n in result.notes:
                    print(f"           {n}")
    finally:
        display.restore()
        try:
            go_home_fully()
        except Exception:
            pass
    failed = [r for r in results if r.problems]
    skipped = [r for r in results if r.skipped and not r.problems]
    print(f"\n{len(results) - len(failed) - len(skipped)} of {len(results)} scenarios passed, "
          f"{len(failed)} failed, {len(skipped)} skipped")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
