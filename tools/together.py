"""The all-mods run: every 26.1 mod's GameTests in one server, with the user's mods loaded together on THIS engine, as his game has them.

    python tools/together.py prepare                      the deployed jars, from the ship records (block bench/ships, on the PC)
    python tools/together.py prepare --release khazuk=laptop-48999eac:khazuk-0.14.2.jar:<sha256> ...
                                                          the same jars from the mods' GitHub releases (the laptop; prepare on the PC
                                                          prints this line)
    python tools/together.py prepare --jar path/to/x.jar  given jars (repeat --jar); --pack DIR adds the rest of a mods folder (the
                                                          instance's content mods, as -Pcompat did on 1.21.1)
    python tools/together.py run [--only a,b]             writes runs/together/run.properties and prints the laptop's command
    python tools/together.py run --here                   runs it here: gradle (refused while Warhammer 3 runs; the PC's rule is the
                                                          laptop), then the report
    python tools/together.py report                       the last run: passes and fails per mod, the log's WARN/ERROR lines per mod

Settlement Core's dev run already carries the engine, its test race and GeckoLib, so a jar holding any of those mod ids is never copied
(two copies of a mod id stop FML). The races' jars were built against the engine they pinned; here they run on this one, which is the
point: an engine change that breaks a deployed race shows here before it ships. FML loads <game dir>/mods in a dev run as in a game
(FancyModLoader's ModsFolderLocator reads FMLPaths.MODSDIR), so runs/together/mods is the run's mods folder, and every mod's GameTests
register from its jar's scan data as they do in the user's game.

What the run checks beyond each mod's own tests (test/TogetherGameTests): every jar loaded as its version, every mod with tests registered
some, no two mods shipping the same data file or one replacing another's tag; and this tool's report scans the log for WARN and ERROR lines
naming any of the mods. A server that never reaches its tests (a registry clash, a missing method between a race and this engine) fails the
report with the log's first errors.

Nothing here touches the user's instance or any server: jars are read (and copied out) only.
"""
import argparse
import fnmatch
import glob
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile
from xml.etree import ElementTree

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN = os.path.join(REPO, "runs", "together")
MODS = os.path.join(RUN, "mods")
LOCK = os.path.join(RUN, "together.lock.json")
PROPS = os.path.join(RUN, "run.properties")
REPORT = os.path.join(RUN, "report.xml")
LOG = os.path.join(RUN, "logs", "latest.log")
# a together run's own copies: savecheck --together runs in the same game dir and rewrites report.xml and latest.log after it
# (the laptop, 2026-11-13: a re-report read the save check's 2 tests as the all-mods run's verdict)
LAST = os.path.join(RUN, "last-together")
# what the dev run already loads from its classpath: a jar with any of these mod ids is never copied
DEV = {"minecraft", "neoforge", "settlementcore", "testrace", "geckolib"}
OURS_DEV = ["settlementcore", "testrace"]
INPUTS = ["src", "build.gradle", "settings.gradle", "gradle.properties"]
TIMEOUT = 40 * 60

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ------------------------------------------------------------------ what a jar is

def nested_ids(z):
    """The mod ids of the jars a jar carries inside it (META-INF/jarjar), which FML loads as mods of their own."""
    out = []
    for n in z.namelist():
        if re.fullmatch(r"META-INF/jarjar/[^/]+\.jar", n):
            try:
                with zipfile.ZipFile(io.BytesIO(z.read(n))) as inner:
                    names = inner.namelist()
                    t = next((x for x in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml") if x in names), None)
                    if t:
                        out += [m.get("modId", "") for m in tomllib.loads(inner.read(t).decode("utf-8", "replace")).get("mods", [])]
                    out += nested_ids(inner)
            except (zipfile.BadZipFile, tomllib.TOMLDecodeError, KeyError):
                pass
    return [i for i in out if i]


def jar_info(path):
    """{ids, provides, version, deps: [(modId, required, side)], client_only, tests, dirs} of a mod jar, read from its neoforge.mods.toml
    and its entries; provides adds the mods it carries inside (jar-in-jar). None for a jar with no mods.toml and nothing inside (a plain
    library, copied as it is)."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        toml_name = next((n for n in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml") if n in names), None)
        if toml_name is None:
            inner = nested_ids(z)
            return None if not inner else {"ids": [], "provides": inner, "version": "", "deps": [], "client_only": False, "tests": False,
                                           "dirs": []}
        text = z.read(toml_name).decode("utf-8", "replace")
        try:
            t = tomllib.loads(text)
        except tomllib.TOMLDecodeError as e:
            return {"ids": [], "provides": [], "version": "", "deps": [], "client_only": False, "tests": False, "dirs": [],
                    "error": f"mods.toml: {e}"}
        mods = t.get("mods", [])
        ids = [m.get("modId", "") for m in mods if m.get("modId")]
        version = str(mods[0].get("version", "")) if mods else ""
        if "${" in version:
            manifest = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace") if "META-INF/MANIFEST.MF" in names else ""
            m = re.search(r"^Implementation-Version:\s*(\S+)", manifest, re.M)
            version = m.group(1) if m else version
        deps = []
        for owner, lst in (t.get("dependencies") or {}).items():
            for d in lst if isinstance(lst, list) else []:
                required = d.get("type", "").lower() == "required" or d.get("mandatory") is True
                deps.append((d.get("modId", ""), required, str(d.get("side", "BOTH")).upper()))
        # client-only only when it says so: every one of its dependencies on the game applies on the client alone (Dynamic FPS). A
        # displayTest of IGNORE_ALL_VERSION says nothing: server-side mods use it too (Let Me Despawn)
        game_deps = [d for d in deps if d[0] in ("minecraft", "neoforge")]
        client_only = bool(game_deps) and all(d[2] == "CLIENT" for d in game_deps)
        tests = False
        dirs = set()
        for n in names:
            m = re.match(r"data/[^/]+/([^/]+)/", n)
            if m:
                dirs.add(m.group(1))
            if not tests and n.endswith(".class") and "/test/" in n:
                tests = b"/GameTestHolder;" in z.read(n)
        return {"ids": ids, "provides": ids + nested_ids(z), "version": version, "deps": deps, "client_only": client_only, "tests": tests,
                "dirs": sorted(dirs)}


# ------------------------------------------------------------------ where the deployed jars are

def histories(ships):
    """{mod: (file, sha8, full sha256 or None, release tag or None)} of every ship history's newest deployed set, but the engine's."""
    out = {}
    for p in sorted(glob.glob(os.path.join(ships, "deploys_*.json"))):
        mod = os.path.basename(p)[len("deploys_"):-len(".json")]
        try:
            h = json.load(open(p, encoding="utf-8"))
        except (OSError, ValueError) as e:
            print(f"  (skipped {p}: {e})")
            continue
        last = next((e for e in reversed(h) if isinstance(e, dict) and e.get("files") and e.get("jars")), None)
        if last is None:
            continue
        built = (last.get("tests") or {}).get("built") or ""
        release = re.search(r"\brelease (laptop-[0-9a-f]+)", built)
        for name, file in last["files"].items():
            s8 = last["jars"].get(name)
            full = re.search(re.escape(file) + r"=([0-9a-f]{64})", built)
            out.setdefault(mod, []).append((file, s8, full.group(1) if full else None, release.group(1) if release else None))
    return out


def archived(ships, sha8):
    """The archived jar whose name ends in -<sha8>.jar and whose bytes start with that hash, or None."""
    for p in glob.glob(os.path.join(ships, "jars", "*", f"*-{sha8}.jar")):
        if sha256(p).startswith(sha8):
            return p
    return None


def owner():
    """The GitHub owner of this repo's origin: the mods' repos are its siblings."""
    r = subprocess.run(["git", "-C", REPO, "remote", "get-url", "origin"], capture_output=True, text=True)
    m = re.search(r"github\.com[/:]([^/]+)/", r.stdout.strip())
    if r.returncode or not m:
        sys.exit(f"this repo's origin is not on GitHub ({r.stdout.strip() or r.stderr.strip()}): give --jar paths instead")
    return m.group(1)


def download(spec, into):
    """--release <repo>=<tag>:<file>:<sha256>: that asset of that release, checked against its hash."""
    m = re.fullmatch(r"([A-Za-z0-9_.-]+)=(laptop-[0-9a-f]+|[A-Za-z0-9_.-]+):([^:]+\.jar):([0-9a-f]{64})", spec)
    if not m:
        sys.exit(f"--release {spec!r}: expected <repo>=<tag>:<file>.jar:<sha256>")
    repo, tag, file, want = m.groups()
    r = subprocess.run(["gh", "release", "download", tag, "--repo", f"{owner()}/{repo}", "--pattern", file, "--dir", into, "--clobber"],
                       capture_output=True, text=True, timeout=600)
    path = os.path.join(into, file)
    if r.returncode or not os.path.isfile(path):
        sys.exit(f"gh could not download {file} from {repo} release {tag}: {(r.stderr or r.stdout).strip()[-300:]}")
    if sha256(path) != want:
        os.remove(path)
        sys.exit(f"{file} from {repo} {tag} is not the recorded jar (sha256 differs): nothing was prepared")
    return path, f"release {repo} {tag}"


# ------------------------------------------------------------------ prepare

def prepare(a):
    staged = tempfile.mkdtemp(prefix="together_")
    picked = []                                                   # (path, source, ours, the name it goes in as)
    for p in a.jar or []:
        if not os.path.isfile(p):
            sys.exit(f"--jar {p}: no such file")
        picked.append((os.path.abspath(p), "given", True, os.path.basename(p)))
    for spec in a.release or []:
        path, source = download(spec, staged)
        picked.append((path, source, True, os.path.basename(path)))
    if not a.jar and not a.release:
        ships = a.ships or os.path.join(os.path.dirname(REPO), "ships")
        if not os.path.isdir(ships):
            sys.exit(f"no ship records at {ships}: give --ships, --jar or --release")
        laptop = []
        for mod, sets in sorted(histories(ships).items()):
            for file, s8, full, release in sets:
                path = archived(ships, s8) if s8 else None
                if path is None:
                    print(f"  {mod}: no archived jar for {file} ({s8}) in {ships}/jars: left out")
                    continue
                if full and sha256(path) != full:
                    sys.exit(f"{path} does not match the full hash its ship recorded: nothing was prepared")
                info = jar_info(path)
                if info and DEV & set(info["ids"]):
                    continue                                      # the engine's own set: the dev run is this engine
                picked.append((path, f"ship record {mod} {s8}", True, file or os.path.basename(path)))   # named as deployed
                if full and release:
                    laptop.append(f"--release {mod}={release}:{file}:{full}")
                else:
                    laptop.append(f"(no laptop release for {mod} {file}: carry it, --jar)")
        if laptop:
            print("on the laptop, the same jars:\n  python tools/together.py prepare " + " ".join(l for l in laptop if l.startswith("--")))
            for l in laptop:
                if not l.startswith("--"):
                    print("  " + l)
    if a.pack:
        skip = [s.lower() for s in (a.skip or [])]
        ours_ids = set()
        for path, _, _, _ in picked:
            info = jar_info(path)
            ours_ids |= set(info["ids"]) if info else set()
        for p in sorted(glob.glob(os.path.join(a.pack, "*.jar"))):
            info = jar_info(p)
            name = os.path.basename(p).lower()
            if any(fnmatch.fnmatch(name, s) for s in skip):
                print(f"  pack: skipped {os.path.basename(p)} (--skip)")
            elif info and (DEV | ours_ids) & set(info["ids"]):
                continue                                          # the dev run's own, or one of ours from its record
            elif info and info["client_only"]:
                print(f"  pack: skipped {os.path.basename(p)} (client-only by its mods.toml)")
            else:
                picked.append((os.path.abspath(p), f"pack {a.pack}", False, os.path.basename(p)))

    # the folder is the set: nothing left over from another prepare
    os.makedirs(MODS, exist_ok=True)
    for old in glob.glob(os.path.join(MODS, "*.jar")):
        os.remove(old)
    entries, ids_seen, provided = [], {}, set()
    for path, source, ours, as_name in picked:
        info = jar_info(path) or {"ids": [], "provides": [], "version": "", "deps": [], "client_only": False, "tests": False, "dirs": []}
        if info.get("error"):
            sys.exit(f"{path}: {info['error']}")
        clash = DEV & set(info["ids"])
        if clash:
            sys.exit(f"{os.path.basename(path)} holds {sorted(clash)}, which the dev run already loads: two copies of a mod id stop FML")
        for i in info["ids"]:
            if i in ids_seen:
                sys.exit(f"mod id {i} is in both {ids_seen[i]} and {os.path.basename(path)}")
            ids_seen[i] = os.path.basename(path)
        dest = os.path.join(MODS, as_name)
        if os.path.exists(dest):
            sys.exit(f"two jars would both be {as_name} in the run's mods folder")
        shutil.copy2(path, dest)
        provided |= set(info["provides"])
        entries.append({"file": as_name, "sha256": sha256(dest), "ids": info["ids"], "version": info["version"],
                        "source": source, "ours": ours, "tests": info["tests"], "dirs": info["dirs"], "deps": info["deps"]})
    shutil.rmtree(staged, ignore_errors=True)
    have = DEV | set(ids_seen) | provided
    missing = sorted({f"{d} (for {e['file']})" for e in entries for d, req, side in e["deps"] if req and side != "CLIENT" and d not in have})
    if missing:
        print("MISSING required mods: " + ", ".join(missing) + "\n  add their jars (--jar, or --pack <the instance's mods folder>)")
    lock = {"mods": entries, "fingerprint": fingerprint(entries)}
    with open(LOCK, "w", encoding="utf-8", newline="\n") as f:
        json.dump(lock, f, indent=1)
    for e in entries:
        print(f"  {e['file']:<48} {','.join(e['ids']) or '(library)':<22} {e['version']:<12} {'tests ' if e['tests'] else ''}{e['source']}")
    print(f"together mods: {lock['fingerprint']} ({len(entries)} jars in {MODS})")
    sys.exit(1 if missing else 0)


def fingerprint(entries):
    return hashlib.sha256("\n".join(f"{e['file']} {e['sha256']}" for e in sorted(entries, key=lambda e: e["file"])).encode()).hexdigest()[:12]


def load_lock():
    if not os.path.isfile(LOCK):
        sys.exit("no prepared set: python tools/together.py prepare")
    lock = json.load(open(LOCK, encoding="utf-8"))
    for e in lock["mods"]:
        p = os.path.join(MODS, e["file"])
        if not os.path.isfile(p) or sha256(p) != e["sha256"]:
            sys.exit(f"{p} is missing or changed since prepare: prepare again")
    extra = {os.path.basename(p) for p in glob.glob(os.path.join(MODS, "*.jar"))} - {e["file"] for e in lock["mods"]}
    if extra:
        sys.exit(f"{MODS} holds jars prepare did not put there ({sorted(extra)}): prepare again")
    return lock


# ------------------------------------------------------------------ run

def tested_lines():
    head = subprocess.run(["git", "-C", REPO, "rev-parse", "HEAD"], capture_output=True, text=True)
    status = subprocess.run(["git", "-C", REPO, "status", "--porcelain", "--", *INPUTS], capture_output=True, text=True)
    if head.returncode or status.returncode or not re.fullmatch(r"[0-9a-f]{40}", head.stdout.strip()):
        return ["tested commit: unknown", "tree: unknown"]
    return [f"tested commit: {head.stdout.strip()}", f"tree: {'dirty' if status.stdout.strip() else 'clean'}"]


def write_props(lock, only):
    mods = [e for e in lock["mods"] if e["ids"]]
    lines = [
        "# written by tools/together.py run: what test/TogetherGameTests checks, and each mod's test filter",
        "settlementcore.together=" + ",".join(f"{i}@{e['version']}" for e in mods for i in e["ids"]),
        "settlementcore.together.tests=" + ",".join(i for e in mods if e["tests"] for i in e["ids"]),
        "settlementcore.together.dirs=" + ",".join(sorted({d for e in lock["mods"] for d in e["dirs"] if d != "tags"})),
        f"settlementcore.only={only}",
    ]
    # a mod with its own registrar reads <modid>.only (Bloodlines); the races on the engine read settlementcore.only
    lines += [f"{i}.only={only}" for e in mods if e["ours"] and e["tests"] for i in e["ids"]]
    with open(PROPS, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def java_home():
    if os.environ.get("SETTLEMENTCORE_JAVA_HOME"):
        return os.environ["SETTLEMENTCORE_JAVA_HOME"]
    found = sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-25*"), key=lambda d: [int(n) for n in re.findall(r"\d+", d)])
    if not found:
        sys.exit("no JDK 25: install it (winget install EclipseAdoptium.Temurin.25.JDK) or set SETTLEMENTCORE_JAVA_HOME")
    return found[-1]


def warhammer_running():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Warhammer3.exe"], capture_output=True, text=True).stdout
    return "Warhammer3.exe" in out


def run(a):
    if a.only and not re.fullmatch(r"[A-Za-z0-9_,.\-]+", a.only):
        sys.exit("--only: letters, digits, _ , . - only")
    lock = load_lock()
    write_props(lock, a.only or "")
    if not a.here:
        print("on the laptop (docs/testing-beyond-gametests.md), in its Settlement Core clone at the commit to test:")
        print("  python tools\\together.py prepare <the --release line prepare printed on the PC>")
        print(f"  python tools\\together.py run --here{' --only ' + a.only if a.only else ''}")
        print(f"together mods here: {lock['fingerprint']} (the laptop's prepare must print the same)")
        return
    if warhammer_running():
        sys.exit("Warhammer3.exe is running: no gradle until the user says so")
    print("\n".join(tested_lines()), flush=True)
    print(f"together mods: {lock['fingerprint']}", flush=True)
    for stale in (REPORT,):
        if os.path.exists(stale):
            os.remove(stale)                                       # a run that never writes one must not be read as this run's
    out = os.path.join(tempfile.gettempdir(), "gametest_settlementcore_together.log")
    env = {**os.environ, "JAVA_HOME": java_home()}
    with open(out, "w", encoding="utf-8", errors="replace") as f:
        p = subprocess.Popen(["cmd", "/c", "start", "/belownormal", "/wait", "/b", "gradlew.bat", "runGameTestServerTogether", "--no-daemon",
                              "--max-workers=2", "-q"], cwd=REPO, env=env, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            p.wait(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
            p.wait()
            print(f"STOPPED: the run passed {TIMEOUT // 60} minutes and was ended with everything it started (gradle log {out})")
    print(f"gradle log: {out}")
    if os.path.isdir(LAST):
        shutil.rmtree(LAST)
    os.makedirs(LAST)
    for f in (REPORT, LOG):
        if os.path.isfile(f):
            shutil.copy2(f, LAST)
    sys.exit(report(a, lock))


# ------------------------------------------------------------------ report

# Log lines a test sets out to cause, matched on the message: reported, never counted as the run's errors. Each names its test.
# Add one only with the test that provokes it; any other ERROR still fails the run.
PROVOKED = [
    (r"missing room template .*no_such_room_for_a_server_read\.nbt", "AuditFixGameTests asks for a room that doesn't exist"),
]

LINE = re.compile(r"^\[(?P<time>[^\]]+)\] \[(?P<thread>[^\]]+)/(?P<level>[A-Z]+)\] \[(?P<logger>[^\]/]*)/?[^\]]*\]: (?P<msg>.*)$")


def owner_of(logger, msg, ids):
    """The mod a log line names: its logger's package (claude.<id>., <id>.) or an <id>:thing id or the bare word in its message."""
    for i in ids:
        if re.search(rf"(^|\.){re.escape(i)}(\.|$)", logger) or re.search(rf"\b{re.escape(i)}(:|\b)", msg):
            return i
    return None


def report(a, lock=None):
    lock = lock or load_lock()
    REPORT, LOG = os.path.join(LAST, "report.xml"), os.path.join(LAST, "latest.log")   # this tool's last run, never savecheck's
    ours = OURS_DEV + [i for e in lock["mods"] if e["ours"] for i in e["ids"]]
    others = [i for e in lock["mods"] if not e["ours"] for i in e["ids"]]
    print(f"together mods: {lock['fingerprint']}")
    ok = True
    per = {}
    if os.path.isfile(REPORT):
        root = ElementTree.parse(REPORT).getroot()
        for tc in root.iter("testcase"):
            name = tc.get("name", "")
            ns = name.split(":", 1)[0]
            d = per.setdefault(ns, {"run": 0, "failed": [], "optional": []})
            d["run"] += 1
            f = tc.find("failure")
            if f is not None:
                d["failed"].append(f"{name}: {f.get('message', '')[:300]}")
            s = tc.find("skipped")
            if s is not None:
                d["optional"].append(f"{name}: {s.get('message', '')[:200]}")
    else:
        print(f"NO REPORT: {REPORT} was not written (the server never finished its tests)")
        ok = False
    text = open(LOG, encoding="utf-8", errors="replace").read() if os.path.isfile(LOG) else ""
    if not text:
        print(f"NO LOG at {LOG}")
        ok = False
    passed = re.search(r"All (\d+) required tests passed", text)
    failed = re.search(r"(\d+) required tests failed", text)
    if per:
        total = sum(d["run"] for d in per.values())
        nfail = sum(len(d["failed"]) for d in per.values())
        print(f"tests: {total} run, {total - nfail - sum(len(d['optional']) for d in per.values())} passed, {nfail} required failed")
        for ns in sorted(per, key=lambda n: (n not in ours, n)):
            d = per[ns]
            good = d["run"] - len(d["failed"]) - len(d["optional"])
            print(f"  {ns:<16} {good}/{d['run']}" + ("" if not d["failed"] else "  FAILED"))
            for f in d["failed"][:10]:
                print(f"      {f}")
            for f in d["optional"][:5]:
                print(f"      (optional) {f}")
        if nfail:
            ok = False
        expected = {i for e in lock["mods"] if e["tests"] for i in e["ids"]} | set(OURS_DEV)
        props = open(PROPS, encoding="utf-8").read() if os.path.isfile(PROPS) else ""
        filtered = bool(re.search(r"^settlementcore\.only=\S", props, re.M))
        for i in sorted(expected - set(per)):
            print(f"  {i:<16} 0 tests ran" + (" (-Ponly may have filtered them all)" if filtered else ": its tests never registered"))
            ok = ok and filtered
    if not passed and not failed and text:
        ok = False
        print("the server never reached its test summary; its first errors:")
        errs = [l for l in text.splitlines() if "/ERROR]" in l or re.search(r"(Exception|Error)(:|$)", l) or "Caused by" in l][:15]
        for l in errs:
            print("    " + l[:300])
    # the log: WARN and ERROR lines by the mod they name
    by = {}
    other_count = 0
    for line in text.splitlines():
        m = LINE.match(line)
        if not m or m.group("level") not in ("WARN", "ERROR"):
            continue
        who = owner_of(m.group("logger"), m.group("msg"), ours)
        if who is None:
            if owner_of(m.group("logger"), m.group("msg"), others):
                other_count += 1
            continue
        key = re.sub(r"\d+", "#", m.group("msg"))[:160]
        d = by.setdefault(who, {}).setdefault((m.group("level"), key), [0, m.group("msg")[:300]])
        d[0] += 1
    errors = 0
    if by:
        print("log lines naming our mods:")
    for who in sorted(by):
        for (level, _), (n, first) in sorted(by[who].items(), key=lambda kv: (kv[0][0] != "ERROR", -kv[1][0])):
            known = next((why for pat, why in PROVOKED if re.search(pat, first)), None)
            print(f"  {who:<16} {level:<5} x{n:<4} {first}" + (f"   [provoked by a test: {known}]" if known else ""))
            errors += n if level == "ERROR" and not known else 0
    if other_count:
        print(f"  ({other_count} WARN/ERROR lines name the pack's other mods: not ours, not counted)")
    if errors and not a.errors_ok:
        ok = False
        print(f"{errors} ERROR line(s) name our mods (--errors-ok to report them without failing)")
    print("VERDICT: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="action", required=True)
    p = sub.add_parser("prepare", help="fill runs/together/mods")
    p.add_argument("--jar", action="append", help="a jar to put in (repeat); with --jar or --release the ship records are not read")
    p.add_argument("--release", action="append", help="<repo>=<tag>:<file>.jar:<sha256>: a jar from a mod's GitHub release (repeat)")
    p.add_argument("--ships", help="the ship records' folder (default: ../ships beside this repo, block bench's)")
    p.add_argument("--pack", help="a mods folder whose other jars go in too (the user's instance: his content mods)")
    p.add_argument("--skip", action="append", help="with --pack: a file-name glob to leave out (repeat)")
    r = sub.add_parser("run", help="write the run's properties; print the laptop command, or --here run it")
    r.add_argument("--only", help="batches or tests by name, for every mod (their -Ponly)")
    r.add_argument("--here", action="store_true", help="run gradle here (the laptop; never on the PC between ships)")
    r.add_argument("--errors-ok", action="store_true", help="ERROR lines naming our mods are reported, not failed")
    q = sub.add_parser("report", help="read the last run's report.xml and log")
    q.add_argument("--errors-ok", action="store_true", help="ERROR lines naming our mods are reported, not failed")
    a = ap.parse_args()
    if a.action == "prepare":
        prepare(a)
    elif a.action == "run":
        run(a)
    else:
        sys.exit(report(a))


if __name__ == "__main__":
    main()
