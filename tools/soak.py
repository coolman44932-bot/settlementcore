"""The soak: a test race settlement left running for an hour of game time, sampled every minute, must not keep growing anything or slow down.

    python tools/soak.py run [--ticks N]          prints the laptop's command (an hour of game time: 72000 ticks)
    python tools/soak.py run --here [--ticks N]   runs it here: gradle runGameTestServer -Ponly=soak -Psoak=N (refused while Warhammer 3
                                                  runs; the PC's rule is the laptop), then the report
    python tools/soak.py report [log]             the SOAK lines of the last run (runs/gametest/logs/latest.log): every count's first,
                                                  middle, last and highest value, the tick time and the heap, and the verdict

The test is testrace/test/TestRaceSoakGameTests, the sampler and its verdict test/Soak (docs/testing-beyond-gametests.md: what a sample
holds, how the verdict judges, and how a race runs a soak of its own). Without -Psoak the soak test passes at once, so a full run costs
nothing. The verdict is the test's own (SOAK <name> VERDICT pass|fail); this tool shows the numbers behind it.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOG = os.path.join(REPO, "runs", "gametest", "logs", "latest.log")
INPUTS = ["src", "build.gradle", "settings.gradle", "gradle.properties"]
TIMEOUT = 120 * 60
SAMPLE = re.compile(r"SOAK (?P<name>\S+) tick=(?P<tick>\d+) wall=(?P<wall>[\d.]+) mspt=(?P<mspt>[\d.]+) heap=(?P<heap>\d+)(?P<rest>.*)$")
VERDICT = re.compile(r"SOAK (?P<name>\S+) VERDICT (?P<v>pass|fail): (?P<why>.*)$")

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass


def tested_lines():
    head = subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"], capture_output=True, text=True)
    status = subprocess.run(["git", "-C", REPO, "status", "--porcelain", "--", *INPUTS], capture_output=True, text=True)
    if head.returncode or status.returncode or not re.fullmatch(r"[0-9a-f]{40}", head.stdout.strip()):
        return ["tested commit: unknown", "tree: unknown"]
    return [f"tested commit: {head.stdout.strip()}", f"tree: {'dirty' if status.stdout.strip() else 'clean'}"]


def java_home():
    if os.environ.get("SETTLEMENTCORE_JAVA_HOME"):
        return os.environ["SETTLEMENTCORE_JAVA_HOME"]
    found = sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-25*"), key=lambda d: [int(n) for n in re.findall(r"\d+", d)])
    if not found:
        sys.exit("no JDK 25: install it (winget install EclipseAdoptium.Temurin.25.JDK) or set SETTLEMENTCORE_JAVA_HOME")
    return found[-1]


def run(ticks, here):
    args = ["runGameTestServer", "-Ponly=soak", f"-Psoak={ticks}"]
    if not here:
        print("on the laptop, in its Settlement Core clone at the commit to test:")
        print(f"  python tools\\soak.py run --here --ticks {ticks}")
        print(f"(gradle: gradlew {' '.join(args)}; then python tools\\soak.py report)")
        return
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Warhammer3.exe"], capture_output=True, text=True).stdout
    if "Warhammer3.exe" in out:
        sys.exit("Warhammer3.exe is running: no gradle until the user says so")
    print("\n".join(tested_lines()), flush=True)
    log = os.path.join(tempfile.gettempdir(), "gametest_settlementcore_soak.log")
    with open(log, "w", encoding="utf-8", errors="replace") as f:
        p = subprocess.Popen(f'cmd /c "start /belownormal /wait /b gradlew.bat {" ".join(args)} --no-daemon --max-workers=2 -q"', cwd=REPO,
                             env={**os.environ, "JAVA_HOME": java_home()}, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            p.wait(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
            p.wait()
            print(f"STOPPED: the soak passed {TIMEOUT // 60} minutes of wall time and was ended")
    print(f"gradle log: {log}")
    sys.exit(report(LOG))


def report(path):
    if not os.path.isfile(path):
        print(f"no log at {path}")
        return 1
    runs = {}
    verdicts = {}
    for line in open(path, encoding="utf-8", errors="replace"):
        m = SAMPLE.search(line)
        if m:
            counts = dict((k, int(v)) for k, v in re.findall(r" (\S+?)=(-?\d+)(?= |$)", m.group("rest")))
            runs.setdefault(m.group("name"), []).append((int(m.group("tick")), float(m.group("wall")), float(m.group("mspt")),
                                                         int(m.group("heap")), counts))
            continue
        v = VERDICT.search(line)
        if v:
            verdicts[v.group("name")] = (v.group("v"), v.group("why"))
    if not runs and not verdicts:
        print(f"no SOAK lines in {path}: the soak did not run (no -Psoak, or -Ponly matched nothing)")
        return 1
    ok = True
    for name, samples in runs.items():
        n = len(samples)
        mid = samples[n // 2]
        print(f"== soak {name}: {n} samples, ticks {samples[0][0]}-{samples[-1][0]}")
        print(f"   {'':<44} {'first':>8} {'middle':>8} {'last':>8} {'highest':>8}")
        print(f"   {'tick wall ms':<44} {samples[0][1]:>8.2f} {mid[1]:>8.2f} {samples[-1][1]:>8.2f} {max(s[1] for s in samples):>8.2f}")
        print(f"   {'tick mspt (server, last 100)':<44} {samples[0][2]:>8.2f} {mid[2]:>8.2f} {samples[-1][2]:>8.2f} "
              f"{max(s[2] for s in samples):>8.2f}")
        print(f"   {'heap MB after a collection':<44} {samples[0][3]:>8} {mid[3]:>8} {samples[-1][3]:>8} {max(s[3] for s in samples):>8}")
        keys = sorted({k for s in samples for k in s[4]})
        growth = sorted(keys, key=lambda k: -(samples[-1][4].get(k, 0) - samples[0][4].get(k, 0)))
        for k in growth:
            vals = [s[4].get(k, 0) for s in samples]
            if max(vals) == 0:
                continue
            print(f"   {k:<44} {vals[0]:>8} {vals[n // 2]:>8} {vals[-1]:>8} {max(vals):>8}")
        if name not in verdicts:
            print("   no VERDICT line: the soak did not finish (timed out, or the server stopped)")
            ok = False
    for name, (v, why) in verdicts.items():
        print(f"VERDICT {name}: {v.upper()}: {why}")
        ok = ok and v == "pass"
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="action", required=True)
    r = sub.add_parser("run", help="print the laptop's command, or --here run the soak")
    r.add_argument("--ticks", type=int, default=72000, help="game ticks to run (72000: an hour; at most 431600)")
    r.add_argument("--here", action="store_true", help="run gradle here (the laptop; never on the PC between ships)")
    q = sub.add_parser("report", help="the SOAK lines of a run's log")
    q.add_argument("log", nargs="?", default=LOG)
    a = ap.parse_args()
    if a.action == "run":
        if not 1200 * 22 <= a.ticks <= 431600:
            sys.exit("--ticks: from 26400 (enough samples for the verdict: two windows of ten after a warm-up of two) to 431600")
        run(a.ticks, a.here)
    else:
        sys.exit(report(a.log))


if __name__ == "__main__":
    main()
