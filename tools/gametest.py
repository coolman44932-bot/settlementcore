"""Run Settlement Core's GameTests and print what failed: the one short command a permission rule can allow.

    python settlementcore/tools/gametest.py all                  (from block bench; any cwd works)
    python settlementcore/tools/gametest.py settlement,raise     (just those batches, or tests by name: -Ponly)
    python settlementcore/tools/gametest.py all --ns settlementcore,testrace   (the test race's tests too: -Ptestns)

The 26.1 port of tools/gametest.py in the Under-Empire's repo (2026-10-31). It refuses while Warhammer 3 is running (gradle
and the game fight over the PC). Exit code 0 when every required test passed, 1 otherwise; a run that passed 0 tests (a
batch name matching nothing) is a failure too. Every run starts with "tested commit: <git rev-parse HEAD>" and "tree:
clean|dirty" (git status --porcelain over src/ and the build files), so a result is tied to the code that ran.

The JDK is Java 25 (Minecraft 26.1 needs it): SETTLEMENTCORE_JAVA_HOME names another (the laptop test runner's), else
Temurin 25 where winget puts it.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INPUTS = ["src", "build.gradle", "settings.gradle", "gradle.properties"]


def java_home():
    if os.environ.get("SETTLEMENTCORE_JAVA_HOME"):
        return os.environ["SETTLEMENTCORE_JAVA_HOME"]
    found = sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-25*"))
    if not found:
        sys.exit("no JDK 25: install it (winget install EclipseAdoptium.Temurin.25.JDK) or set SETTLEMENTCORE_JAVA_HOME")
    return found[-1]


def tested_lines():
    head = subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"], capture_output=True, text=True)
    status = subprocess.run(["git", "-C", REPO, "status", "--porcelain", "--", *INPUTS], capture_output=True, text=True)
    if head.returncode or status.returncode or not re.fullmatch(r"[0-9a-f]{40}", head.stdout.strip()):
        return ["tested commit: unknown", "tree: unknown"]
    return [f"tested commit: {head.stdout.strip()}", f"tree: {'dirty' if status.stdout.strip() else 'clean'}"]


def warhammer_running():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Warhammer3.exe"], capture_output=True, text=True).stdout
    return "Warhammer3.exe" in out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("batches", help="comma-separated GameTest batches or test names (-Ponly), or 'all'")
    ap.add_argument("--ns", help="the GameTest namespaces to run (-Ptestns), e.g. settlementcore,testrace")
    a = ap.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_,.\-]+", a.batches):
        sys.exit("batches: letters, digits, _ , . - only")
    if a.ns and not re.fullmatch(r"[a-z0-9_,]+", a.ns):
        sys.exit("--ns: mod ids, comma-separated")
    if warhammer_running():
        sys.exit("Warhammer3.exe is running: no gradle until the user says so")
    print("\n".join(tested_lines()), flush=True)
    log = os.path.join(tempfile.gettempdir(), f"gametest_settlementcore_{a.batches.replace(',', '+')[:60]}.log")
    only = "" if a.batches == "all" else f" -Ponly={a.batches}"
    only += f" -Ptestns={a.ns}" if a.ns else ""
    gradle = f"gradlew.bat runGameTestServer{only} --no-daemon --max-workers=2 -q"
    with open(log, "w", encoding="utf-8", errors="replace") as f:
        subprocess.run(f'cmd /c "start /belownormal /wait /b {gradle}"', cwd=REPO, stdout=f, stderr=subprocess.STDOUT,
                       stdin=subprocess.DEVNULL, env={**os.environ, "JAVA_HOME": java_home()})
    text = open(log, encoding="utf-8", errors="replace").read()
    # 26.1's summary: "All N required tests passed :)", or "N required tests failed :(" and a "   - <id>: <why>" line each
    keep, failing = [], False
    for l in text.splitlines():
        if re.search(r"required tests (passed|failed)|optional tests failed|GAME TESTS COMPLETE|BUILD FAILED|error:|Exception in", l):
            keep.append(l)
            failing = "failed" in l
        elif failing and re.match(r".*\]:?\s+- ", l):
            keep.append(l)
        else:
            failing = failing and bool(re.match(r".*\]:?\s+- ", l))
    print("\n".join(keep) if keep else "(no test summary in the log: it may not have compiled)")
    print("log:", log)
    m = re.search(r"All (\d+) required tests passed", text)
    ok = m is not None
    if m and int(m.group(1)) == 0:
        print(f"FAILED: 0 tests ran for '{a.batches}': no test has that batch or name (-Ponly), or nothing was found to run")
        ok = False
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
