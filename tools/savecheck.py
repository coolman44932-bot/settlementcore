"""The old-save check: does this build load and save a world that older jars saved, keeping every settlement's people, stores and plans?

    python tools/savecheck.py add <world folder or .zip> [--name N] [--mods DIR]
                                         keep a world saved by an older set of jars as a baseline: runs/savecheck/worlds/<N>.zip,
                                         and <N>.json naming the jars that saved it (a world in an instance's saves: that instance's mods)
    python tools/savecheck.py list       the baselines, the prepared copies and their kept outputs
    python tools/savecheck.py scan <world folder, .zip or baseline name>
                                         what a world holds of each mod, read here in Python (no game): block entities and entities
                                         by id, per namespace, to pick a baseline with settlements in it
    python tools/savecheck.py prepare [N ...|all]
                                         unzip baselines into runs/savecheck/<N>/world (all of them by default)
    python tools/savecheck.py run [N ...|all] [--here] [--together] [--allow path,...]
                                         load and save the prepared copies with this build (the GameTest batch "savecheck",
                                         test/SaveCheckGameTests): prints the laptop's command, or --here runs gradle; then each summary.
                                         --together: in the all-mods run (tools/together.py prepare first), which a race's world needs:
                                         only the mods a server has loaded can be read
    python tools/savecheck.py keep <label> [N ...]
                                         keep this build's out/ of each copy as <label>, to compare a later build with
    python tools/savecheck.py diff <label> [N ...]
                                         this build's out/ against <label>: exit 0 when identical

The test reads every block entity and entity of the engine and the mods on it from the copy's region files, loads each with this build
and saves it again, as sorted SNBT: in/ (as the old jars saved it), out/ (after this build's load and save), summary.txt (last). It
fails on anything that does not load or save, a key or list entry a block entity's save drops (unless --allow names it), and a
settlement whose people, stores and plans read back different; summary.txt lists them. keep/diff then compare builds with each other.

Where things live (runs/ is never in git): runs/savecheck/worlds/<N>.zip and <N>.json are the baselines, the only part to carry to the
laptop (copy the folder); runs/savecheck/<N>/ is a prepared copy with world/, in/, out/, summary.txt and kept/<label>/. A result is only
trusted when its run finished: run deletes in/, out/ and summary.txt first, the test writes summary.txt last, and keep and diff refuse
without it or when it disagrees with the files. A world is only ever read (its folder, or a zip of it); never point this at a world
that is open in a game (its region files may be half written: close the game first).
"""
import argparse
import difflib
import glob
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.join(REPO, "runs", "savecheck")
WORLDS = os.path.join(ROOT, "worlds")
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


def longpath(p):
    """p as Windows reads a path past 260 characters (a world's deepest data files under a long checkout path); p elsewhere."""
    p = os.path.abspath(p)
    return "\\\\?\\" + p if os.name == "nt" and not p.startswith("\\\\?\\") else p


def last_played(world_dir):
    """When the world was last saved (level.dat's LastPlayed, in seconds), or None."""
    try:
        import gzip
        data = nbt(gzip.decompress(open(os.path.join(world_dir, "level.dat"), "rb").read()))
        return data.get("Data", {}).get("LastPlayed", 0) / 1000 or None
    except (OSError, ValueError, struct.error):
        return None


def safe_name(n):
    n = re.sub(r"[^A-Za-z0-9_.-]+", "_", n).strip("._")
    if not n:
        sys.exit("a baseline needs a name of letters, digits, _ . -")
    return n


def baselines():
    return sorted(os.path.splitext(os.path.basename(p))[0] for p in glob.glob(os.path.join(WORLDS, "*.zip")))


def names(given):
    """The baselines a command means: those named, or every one ('all', or none named)."""
    have = baselines()
    if not given or given == ["all"]:
        if not have:
            sys.exit(f"no baselines in {WORLDS}: python tools/savecheck.py add <world>")
        return have
    for n in given:
        if n not in have:
            sys.exit(f"no baseline {n} (have: {', '.join(have) or 'none'})")
    return given


# ------------------------------------------------------------------ add, list, prepare

def add(src, name, mods):
    src = os.path.abspath(src)
    os.makedirs(WORLDS, exist_ok=True)
    if os.path.isfile(src) and src.lower().endswith(".zip"):
        with zipfile.ZipFile(src) as z:
            top = {n.split("/")[0] for n in z.namelist()}
            if "level.dat" not in z.namelist() and not any(f"{t}/level.dat" in z.namelist() for t in top):
                sys.exit(f"{src} holds no level.dat: not a world")
        name = safe_name(name or os.path.splitext(os.path.basename(src))[0])
        dest = os.path.join(WORLDS, name + ".zip")
        shutil.copy2(src, dest)
        world_dir = None
    elif os.path.isdir(src):
        if not os.path.isfile(os.path.join(src, "level.dat")):
            sys.exit(f"{src} holds no level.dat: not a world")
        name = safe_name(name or os.path.basename(src))
        dest = os.path.join(WORLDS, name + ".zip")
        tmp = dest + ".part"
        with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
            for dp, dns, fns in os.walk(src):
                for fn in fns:
                    if fn == "session.lock":
                        continue
                    p = os.path.join(dp, fn)
                    z.write(p, os.path.relpath(p, src).replace("\\", "/"))
        os.replace(tmp, dest)
        world_dir = src
    else:
        sys.exit(f"{src}: neither a world folder nor a .zip of one")
    # the jars that saved it: --mods, else the instance it sits in (<instance>/minecraft/saves/<world> -> <instance>/minecraft/mods)
    if not mods and world_dir and os.path.basename(os.path.dirname(world_dir)) == "saves":
        guess = os.path.join(os.path.dirname(os.path.dirname(world_dir)), "mods")
        mods = guess if os.path.isdir(guess) else None
    jars, on_engine = {}, []
    played = last_played(world_dir) if world_dir else None
    if mods:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        from together import jar_info                              # noqa: E402 (reads a jar's mods.toml)
        for p in sorted(glob.glob(os.path.join(mods, "*.jar"))):
            jars[os.path.basename(p)] = sha256(p)
            info = jar_info(p)
            if info and ("settlementcore" in info["ids"] or any(d[0] == "settlementcore" for d in info["deps"])):
                newer = played is not None and os.path.getmtime(p) > played
                on_engine.append(f"{os.path.basename(p)} {jars[os.path.basename(p)][:8]}" + (" (put in after the world's last save:"
                                 " an older jar saved it)" if newer else ""))
    # the jars in that mods folder now: the world was last saved by these or by older ones (a jar newer than its last save is marked)
    note = {"name": name, "from": src, "added": time.strftime("%Y-%m-%d %H:%M:%S"), "zip_sha256": sha256(dest),
            "last_saved": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(played)) if played else "unknown",
            "on_engine": on_engine, "mods_folder_jars": jars or "unknown (no --mods given)"}
    with open(os.path.join(WORLDS, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(note, f, indent=1)
    print(f"baseline {name}: {dest} ({os.path.getsize(dest) >> 20} MB)" + (f", saved by {len(jars)} jars from {mods}" if jars else ""))
    print(f"next: python tools/savecheck.py prepare {name}")


def list_all():
    for n in baselines():
        note = os.path.join(WORLDS, n + ".json")
        info = json.load(open(note, encoding="utf-8")) if os.path.isfile(note) else {}
        ours = info.get("on_engine") or []
        d = os.path.join(ROOT, n)
        state = "not prepared"
        if os.path.isdir(os.path.join(d, "world")):
            state = "prepared" + (", has a result" if os.path.isfile(os.path.join(d, "summary.txt")) else "")
        kept = sorted(os.listdir(os.path.join(d, "kept"))) if os.path.isdir(os.path.join(d, "kept")) else []
        print(f"{n:<28} last saved {info.get('last_saved', '?'):<20} {state:<26} kept: {', '.join(kept) or '-'}")
        for j in ours:
            print(f"    {j}")


def clear_results(d):
    """A copy's in/, out/ and summary.txt: the results of a run, never the world or what was kept."""
    for sub in ("in", "out"):
        if os.path.isdir(os.path.join(d, sub)):
            shutil.rmtree(longpath(os.path.join(d, sub)))
    if os.path.exists(os.path.join(d, "summary.txt")):
        os.remove(os.path.join(d, "summary.txt"))


def prepare(given):
    for n in names(given):
        d = os.path.join(ROOT, n)
        world = os.path.join(d, "world")
        if os.path.isdir(world):
            shutil.rmtree(longpath(world))
        os.makedirs(world)
        clear_results(d)
        with zipfile.ZipFile(os.path.join(WORLDS, n + ".zip")) as z:
            z.extractall(longpath(world))
        # a zip of the world's parent folder holds the world one level down: lift it up
        if not os.path.isfile(os.path.join(world, "level.dat")):
            inner = [p for p in glob.glob(os.path.join(world, "*", "level.dat"))]
            if len(inner) != 1:
                sys.exit(f"{n}.zip holds no level.dat at its top or one folder down")
            top = os.path.dirname(inner[0])
            for e in os.listdir(top):
                shutil.move(os.path.join(top, e), os.path.join(world, e))
            os.rmdir(top)
        for marker in ("session.lock",):
            if os.path.exists(os.path.join(world, marker)):
                os.remove(os.path.join(world, marker))
        print(f"{n}: {world}")
    print("next: python tools/savecheck.py run")


# ------------------------------------------------------------------ scan: a world read in Python

def nbt(data):
    """The root compound of an uncompressed NBT blob, as Python values (compounds dicts, lists lists)."""
    pos = 0

    def take(n):
        nonlocal pos
        b = data[pos:pos + n]
        pos += n
        return b

    def string():
        (n,) = struct.unpack(">H", take(2))
        return take(n).decode("utf-8", "replace")

    def value(t):
        if t == 1:
            return struct.unpack(">b", take(1))[0]
        if t == 2:
            return struct.unpack(">h", take(2))[0]
        if t == 3:
            return struct.unpack(">i", take(4))[0]
        if t == 4:
            return struct.unpack(">q", take(8))[0]
        if t == 5:
            return struct.unpack(">f", take(4))[0]
        if t == 6:
            return struct.unpack(">d", take(8))[0]
        if t == 7:
            (n,) = struct.unpack(">i", take(4))
            return take(n)
        if t == 8:
            return string()
        if t == 9:
            et, n = struct.unpack(">bi", take(5))
            return [value(et) for _ in range(max(0, n))]
        if t == 10:
            out = {}
            while True:
                (ct,) = struct.unpack(">b", take(1))
                if ct == 0:
                    return out
                k = string()
                out[k] = value(ct)
        if t == 11:
            (n,) = struct.unpack(">i", take(4))
            return list(struct.unpack(f">{n}i", take(4 * n)))
        if t == 12:
            (n,) = struct.unpack(">i", take(4))
            return list(struct.unpack(f">{n}q", take(8 * n)))
        raise ValueError(f"tag type {t}")

    (t,) = struct.unpack(">b", take(1))
    if t != 10:
        raise ValueError("not a compound at the root")
    string()
    return value(10)


def chunks(mca):
    """Every chunk tag of a region file (zlib or gzip or none; chunks stored outside the region in .mcc files are skipped)."""
    data = open(mca, "rb").read()
    for i in range(1024):
        off = int.from_bytes(data[i * 4:i * 4 + 3], "big") * 4096
        if not off or off + 5 > len(data):
            continue
        (length,) = struct.unpack(">i", data[off:off + 4])
        kind = data[off + 4]
        body = data[off + 5:off + 4 + length]
        if kind & 0x80:
            continue                                               # its own .mcc file
        try:
            raw = zlib.decompress(body) if kind == 2 else (zlib.decompress(body, 16 + zlib.MAX_WBITS) if kind == 1 else body)
            yield nbt(raw)
        except (zlib.error, ValueError, struct.error):
            continue


def scan(target):
    """Block entities and entities of every namespace in a world, read here in Python."""
    tmp = None
    if os.path.isdir(target):
        world = target
    else:
        z = target if target.lower().endswith(".zip") else os.path.join(WORLDS, target + ".zip")
        if not os.path.isfile(z):
            sys.exit(f"{target}: not a world folder, a .zip or a baseline name")
        tmp = tempfile.mkdtemp(prefix="savecheck_scan_")
        with zipfile.ZipFile(z) as zf:
            zf.extractall(longpath(tmp))
        lv = glob.glob(os.path.join(tmp, "level.dat")) + glob.glob(os.path.join(tmp, "*", "level.dat"))
        world = os.path.dirname(lv[0]) if lv else tmp
    counts = {}
    try:
        for region in glob.glob(os.path.join(world, "**", "region", "r.*.mca"), recursive=True):
            for c in chunks(region):
                for be in c.get("block_entities", []):
                    counts[("block_entity", be.get("id", "?"))] = counts.get(("block_entity", be.get("id", "?")), 0) + 1
        for region in glob.glob(os.path.join(world, "**", "entities", "r.*.mca"), recursive=True):
            for c in chunks(region):
                stack = list(c.get("Entities", []))
                while stack:
                    e = stack.pop()
                    stack += e.get("Passengers", [])
                    counts[("entity", e.get("id", "?"))] = counts.get(("entity", e.get("id", "?")), 0) + 1
    finally:
        if tmp:
            shutil.rmtree(tmp, ignore_errors=True)
    by_ns = {}
    for (kind, i), n in counts.items():
        by_ns.setdefault(i.split(":")[0], []).append((kind, i, n))
    for ns in sorted(by_ns, key=lambda k: (k == "minecraft", k)):
        if ns == "minecraft":
            print(f"minecraft: {sum(n for _, _, n in by_ns[ns])} block entities and entities")
            continue
        for kind, i, n in sorted(by_ns[ns]):
            print(f"{kind:<13} {i:<40} {n}")


# ------------------------------------------------------------------ run

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


def warhammer_running():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Warhammer3.exe"], capture_output=True, text=True).stdout
    return "Warhammer3.exe" in out


def run(given, here, allow, together=False):
    ns = names(given)
    for n in ns:
        if not os.path.isfile(os.path.join(ROOT, n, "world", "level.dat")):
            sys.exit(f"{n} is not prepared: python tools/savecheck.py prepare {n}")
    if allow and not re.fullmatch(r"[A-Za-z0-9_.\[\],:-]+", allow):
        sys.exit("--allow: save-key paths (settlement.old_key, settlement.works.digs[].cells), comma-separated")
    dirs = ";".join(f"runs/savecheck/{n}" for n in ns)
    if together:
        # a race's world needs the race loaded: the all-mods run with the jars together.py prepared, filtered to this one batch
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import together as tg                                      # noqa: E402
        tg.write_props(tg.load_lock(), "savecheck")
        args = ["runGameTestServerTogether", f"-Psavecheck={dirs}"]
    else:
        args = ["runGameTestServer", "-Ponly=savecheck", f"-Psavecheck={dirs}"]
    args += [f"-Psavecheckallow={allow}"] if allow else []
    if not here:
        print("on the laptop, in its Settlement Core clone at the commit to test (runs/savecheck/worlds carried over, then prepare):")
        if together:
            print("  python tools\\together.py prepare <the --release line together.py prepare printed on the PC>")
        print(f"  python tools\\savecheck.py prepare {' '.join(ns)}")
        print(f"  python tools\\savecheck.py run {' '.join(ns)} --here" + (" --together" if together else "")
              + (f" --allow {allow}" if allow else ""))
        print(f"(gradle: gradlew {' '.join(args)})")
        return
    if warhammer_running():
        sys.exit("Warhammer3.exe is running: no gradle until the user says so")
    print("\n".join(tested_lines()), flush=True)
    for n in ns:
        clear_results(os.path.join(ROOT, n))                       # so a run that never wrote them leaves none to be mistaken for new
    log = os.path.join(tempfile.gettempdir(), "gametest_settlementcore_savecheck.log")
    quoted = " ".join(f'"{x}"' if ";" in x or " " in x else x for x in args)
    with open(log, "w", encoding="utf-8", errors="replace") as f:
        p = subprocess.Popen(f'cmd /c "start /belownormal /wait /b gradlew.bat {quoted} --no-daemon --max-workers=2 -q"', cwd=REPO,
                             env={**os.environ, "JAVA_HOME": java_home()}, stdout=f, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            p.wait(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
            p.wait()
            print(f"STOPPED: the run passed {TIMEOUT // 60} minutes and was ended")
    text = open(log, encoding="utf-8", errors="replace").read()
    passed = re.search(r"All (\d+) required tests passed", text)
    print(f"gradle log: {log}")
    ok = bool(passed) and int(passed.group(1)) > 0
    for n in ns:
        d = os.path.join(ROOT, n)
        s = os.path.join(d, "summary.txt")
        if os.path.isfile(s):
            print(f"== {n}")
            lines = open(s, encoding="utf-8").read().splitlines()
            print("\n".join(lines[:60]) + (f"\n  ... {len(lines) - 60} more lines in {s}" if len(lines) > 60 else ""))
        else:
            print(f"== {n}: no summary.txt (the test did not check it: a failed compile, or a run without the property)")
            ok = False
    print("VERDICT: " + ("PASS" if ok else "FAIL"))
    sys.exit(0 if ok else 1)


# ------------------------------------------------------------------ keep, diff

def read_summary(d):
    """(counts, errors) of a finished run's summary.txt, or exit: no file means no run finished here, no 'errors N' line means it was cut
    short, and its counts must equal the files in in/ (one written per item)."""
    p = os.path.join(d, "summary.txt")
    if not os.path.exists(p):
        sys.exit(f"no summary.txt in {d}: no run finished for this copy: python tools/savecheck.py run")
    counts, errors, settlements = {}, None, None
    for line in open(p, encoding="utf-8").read().splitlines():
        if re.fullmatch(r"errors \d+", line):
            errors = int(line.split()[1])
        elif re.fullmatch(r"settlements \d+", line):
            settlements = int(line.split()[1])
        elif re.fullmatch(r"(block_entity|entity) \S+ \d+", line):
            key, _, n = line.rpartition(" ")
            counts[key] = int(n)
    if errors is None or settlements is None:
        sys.exit(f"{p} has no 'errors N' or 'settlements N' line: the run was cut short; run again")
    if not settlements:
        sys.exit(f"{p} counts no settlement: not a world with one, or a run that failed before reading it; nothing to keep or compare")
    written = sum(len(fs) for _, _, fs in os.walk(os.path.join(d, "in")))
    if written != sum(counts.values()):
        sys.exit(f"{p} counts {sum(counts.values())} items but in/ holds {written} files: the results are not from one run; run again")
    return counts, errors


def keep(label, given):
    label = safe_name(label)
    for n in names(given):
        d = os.path.join(ROOT, n)
        _, errors = read_summary(d)
        if errors:
            sys.exit(f"{n}: its summary.txt reports {errors} error(s): a kept output must be a clean run")
        dest = os.path.join(d, "kept", label)
        if os.path.exists(dest):
            print(f"{n}: replacing the kept {label}")
            shutil.rmtree(dest)
        shutil.copytree(os.path.join(d, "out"), dest)
        print(f"{n}: kept {sum(len(fs) for _, _, fs in os.walk(dest))} files as {label}")


def files(base):
    out = {}
    for dp, _, fns in os.walk(base):
        for fn in fns:
            p = os.path.join(dp, fn)
            out[os.path.relpath(p, base).replace("\\", "/")] = p
    return out


def diff(label, given):
    same_all = True
    for n in names(given):
        d = os.path.join(ROOT, n)
        base = os.path.join(d, "kept", label)
        if not os.path.isdir(base):
            sys.exit(f"{n}: nothing kept as {label} ({os.path.join(d, 'kept')})")
        _, errors = read_summary(d)
        a, b = files(base), files(os.path.join(d, "out"))
        gone, new = sorted(set(a) - set(b)), sorted(set(b) - set(a))
        changed = [k for k in sorted(set(a) & set(b)) if open(a[k], "rb").read() != open(b[k], "rb").read()]
        print(f"== {n}")
        for k in gone:
            print(f"GONE     {k}")
        for k in new:
            print(f"NEW      {k}")
        for k in changed:
            print(f"CHANGED  {k}")
            lines = difflib.unified_diff(open(a[k], encoding="utf-8").read().splitlines(), open(b[k], encoding="utf-8").read().splitlines(),
                                         label, "this build", lineterm="", n=1)
            for line in list(lines)[:30]:
                print("    " + line)
        same = not (gone or new or changed or errors)
        if errors:
            print(f"ERRORS   this build's summary.txt reports {errors}")
        print(("IDENTICAL" if same else "DIFFERENT") + f": {len(a)} files kept, {len(b)} now; {len(gone)} gone, {len(new)} new, "
              f"{len(changed)} changed")
        same_all = same_all and same
    sys.exit(0 if same_all else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="action", required=True)
    p = sub.add_parser("add", help="keep a world saved by older jars as a baseline")
    p.add_argument("world", help="a world folder (read only, never written) or a .zip of one")
    p.add_argument("--name", help="the baseline's name (default: the folder's)")
    p.add_argument("--mods", help="the mods folder whose jars saved it (default: the instance it sits in, if any)")
    sub.add_parser("list", help="the baselines, prepared copies and kept outputs")
    p = sub.add_parser("scan", help="a world's block entities and entities by mod, read in Python")
    p.add_argument("world", help="a world folder, a .zip of one, or a baseline's name")
    p = sub.add_parser("prepare", help="unzip baselines into runs/savecheck/<name>/world")
    p.add_argument("names", nargs="*")
    p = sub.add_parser("run", help="print the laptop's command, or --here run the check")
    p.add_argument("names", nargs="*")
    p.add_argument("--here", action="store_true", help="run gradle here (the laptop; never on the PC between ships)")
    p.add_argument("--allow", help="save-key paths a block entity may drop on purpose (settlement.old_key,...)")
    p.add_argument("--together", action="store_true",
                   help="in the all-mods run (tools/together.py prepare first): a race's world needs the race loaded")
    for verb in ("keep", "diff"):
        p = sub.add_parser(verb, help=f"{verb} this build's out/ {'as' if verb == 'keep' else 'against'} a label")
        p.add_argument("label")
        p.add_argument("names", nargs="*")
    a = ap.parse_args()
    if a.action == "add":
        add(a.world, a.name, a.mods)
    elif a.action == "list":
        list_all()
    elif a.action == "scan":
        scan(a.world)
    elif a.action == "prepare":
        prepare(a.names)
    elif a.action == "run":
        run(a.names, a.here, a.allow, a.together)
    elif a.action == "keep":
        keep(a.label, a.names)
    else:
        diff(a.label, a.names)


if __name__ == "__main__":
    main()
