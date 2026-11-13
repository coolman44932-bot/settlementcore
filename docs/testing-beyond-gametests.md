# Testing beyond the GameTests: all mods together, old saves, long runs

The GameTests prove each mod alone, in a fresh world, for seconds of game time. Survival play is every mod at once, in a world saved by
older jars, for hours. Three runs close those gaps (2026-11-13). Each runs on the laptop: gradle never runs on the PC between ships.
Each tool prints the laptop's command unless it is given `--here`.

| Run | What it catches | Tool | Gradle |
|---|---|---|---|
| All mods together | a race that breaks on this engine, two mods clashing, errors in the log | `tools/together.py` | `runGameTestServerTogether` |
| Old saves | a settlement that loses people, stores or plans when this build loads a world older jars saved | `tools/savecheck.py` | `runGameTestServer -Ponly=savecheck -Psavecheck=...` |
| Soak | a registry, a cache or a count that grows without end, a tick that gets slower | `tools/soak.py` | `runGameTestServer -Ponly=soak -Psoak` |

Nothing below has run yet (written 2026-11-13; type-checked with plain javac, the tools tried on real ship records and worlds).

## A. All mods together

One GameTest server with the engine and test race of this checkout plus the jars the user's instance runs: Khazuk!, the Waaagh! and
Bloodlines, exactly as deployed. Every mod's own GameTests run in it, on this engine. FML loads `<game dir>/mods` in a dev run as in a
game (FancyModLoader's `ModsFolderLocator` reads `FMLPaths.MODSDIR`), so `runs/together/mods` is the run's mods folder, and each mod's
tests register from its jar's scan data as they do in the user's game. The dev run already has the engine, the test race and GeckoLib
on its classpath; `prepare` never copies a jar holding one of those mod ids (two copies of a mod id stop FML).

On the PC, from the ship records (`block bench/ships`: each mod's newest deployed jar, checked against its recorded hash, named as
deployed):

```powershell
python tools/together.py prepare
```

It prints the same set as GitHub release assets for the laptop, which has no ship records:

```powershell
# on the laptop, in its Settlement Core clone at the commit to test
python tools\together.py prepare --release bloodlines=laptop-5f666ffa:bloodlines-0.4.0.jar:<sha256> --release khazuk=... --release waaagh=...
python tools\together.py run --here            # gradle, then the report
```

Both print `together mods: <12 hex digits>`; the two must match. `--jar <path>` puts in given jars instead. `--pack <mods folder>` adds
the rest of the user's instance (his content mods, as `-Pcompat` did on 1.21.1): mods whose every game dependency is client-side are left
out, and `--skip <glob>` leaves out others (a client-only mod that does not say so, if the server crashes on it).

What the run checks beyond each mod's own tests (`test/TogetherGameTests`, from the list `run` writes into `runs/together/run.properties`):

- every jar loaded, as the version it is, and every mod that carries GameTests registered some;
- no two mods ship the same data file (a recipe, loot table, structure ...: the later pack silently wins), and no mod's tag file
  `replace`s a tag another mod adds to, where one of them is ours; clashes between two other mods are logged, not failed.

`python tools/together.py report` reads the run's `report.xml` (the JUnit report every GameTest server writes with `--report`) and
`logs/latest.log`: passes and failures per mod, and the WARN and ERROR lines naming each of our mods (by logger package or by `<modid>:`).
It fails on a failed test, a mod with tests that ran none, an ERROR line naming one of our mods (`--errors-ok` to only list them), and a
server that never reached its tests (it prints the log's first errors: a registry clash, or a method a race calls that this engine no
longer has). `run --only <batches>` filters every mod's tests (each mod's own `<modid>.only` too).

## B. Old saves

A baseline is a world saved by an older set of jars, kept zipped in `runs/savecheck/worlds/` (gitignored: never in git). The user's
instance has several such worlds; `add` zips one (the world is only read) and notes when it was last saved and which engine jars were
in its mods folder (one put in after the last save is marked: an older jar saved the world):

```powershell
python tools/savecheck.py scan "<instance>\minecraft\saves\New World (6)"   # what it holds of each mod, read in Python
python tools/savecheck.py add "<instance>\minecraft\saves\New World (6)" --name khazuk_shrine
python tools/savecheck.py prepare                    # unzip every baseline into runs/savecheck/<name>/world
python tools/savecheck.py run --together             # a race's world needs the race loaded: the all-mods run
python tools/savecheck.py run testrace_heart         # a test race world needs only the engine's run
```

Do not add a world that is open in a game (its region files may be half written). To check on the laptop, copy
`runs/savecheck/worlds/` there (the carry folder, as the Under-Empire's), then `prepare` and `run ... --here [--together]`.

The test (`test/SaveCheckGameTests`) reads every block entity and entity of the engine and the mods built on it (the mods that depend on
`settlementcore`: no race is named) from the copy's region files, loads each with this build and saves it again, and writes sorted SNBT:
`in/` as the old jars saved it, `out/` after this build. It fails on:

- anything that does not load or save, or that the loader or saver reports a problem with;
- a key with something in it, or a list entry, that a block entity's old save had and this build's save drops (a drop that is meant is
  allowed by path: `--allow settlement.old_key,settlement.works.digs[].cells`);
- a settlement whose people, stores and plans read back different: roll, reserve, currency, ration, spoils, ledger stores, buildings,
  digs, sites, refund owed, crews and alarm, between this build's load of the old save and its load of its own save; and the digs,
  sites, buildings, spoils and ledger stores counted against the old save's own lists (a dig whose room is gone is dropped on load);
- chunks saved by another version of the game (this check does not run the DataFixer), or a world with no settlement in it.

An entity's dropped keys are warnings only. Things of mods the server has not loaded are listed as warnings (not checked).
`keep <label>` and `diff <label>` then compare one build's `out/` with another's, file by file. Not covered: the engine's saved data
(`data/`), attachments on players and on the level, and the live world.

## C. Soak

`testrace/test/TestRaceSoakGameTests`: a test race settlement walled into its plot, fed, with a Hoard, left running for an hour of game
time (72000 ticks), sampled every minute by `test/Soak`. Every third minute a worker walks home and sinks into the reserve, every fifth a
guard dies, so rounds of spawning, the roll, the reserve, the crews and the haul log keep turning over.

```powershell
python tools\soak.py run --here [--ticks 72000]     # on the laptop; then the report
python tools\soak.py report                         # first, middle, last and highest of every count, the verdict
```

Without `-Psoak` the soak test passes at once, so a full run costs nothing.

A sample (`Soak.sample`, logged as one `SOAK <name> tick=... wall=... mspt=... heap=... <key>=<n> ...` line):

- `wall`: the wall-clock time per tick since the last sample (the GameTest server never sleeps, so it is the work a tick costs); `mspt`:
  the server's own average of its last 100 ticks; `heap`: the used heap after one hint to collect (once a sample);
- `entity/<id>` for every entity type loaded, `entities`, `be/all` (block entities in loaded chunks), `be/ticking`, `chunks`;
- `engine/<Class>.<FIELD>`: the engine's static registries and caches (`Soak.ENGINE`, read by name; `SoakGameTests` fails in every
  run if one is renamed away), `data/...`: its saved data per level, `settlement/...`: the loaded settlements' parts summed (people,
  reserve, buildings, crews, rooms worked, digs, sites, owed lines, hauls, ledger stores, spoils, and `roll_not_loaded`: roll entries
  with no live unit, which grow when a death is missed);
- `race/<name>`: whatever a race watches.

The verdict (`Soak.judge`) fails a count, or the heap, that climbs without levelling off, fast (the last 10 samples all up or level, at
least half of them up, by more than 8 and 5%; the heap 64 MB) or slow (each third of the run above the third before, the last more than
the margin over the first), and a tick that more than doubles from its early median (and is over 2 ms). A count that rises to a cap and
stays, or rises and falls, passes. It needs a warm-up of 2 samples and two windows: 22 samples at least.

### A race's own soak

A race reuses the sampler in its own GameTest (its jar depends on the engine, so `claude.settlementcore.test.Soak` is there wherever its
tests run):

```java
@GameTest(template = "arena", timeoutTicks = Soak.MAX_TICKS, batch = "soak")
public static void aHoldLeftRunningKeepsNothingGrowing(GameTestHelper h) {
    long total = Soak.ticks();                       // -Psoak; 0 without it
    if (total <= 0) { h.succeed(); return; }
    MyHeart heart = foundAHold(h);                   // the race's own settlement, fed and working
    Soak.watch("grudges", server -> Grudges.size()); // any registry of the race's own, as race/grudges
    Soak.start(h, "khazuk").run(h, total, Soak.DEFAULT_EVERY, n -> {
        topUp(heart);                                // what keeps it working, called before each sample
        if (n % 3 == 0) sendOneHome(h, heart);
    });
}
```

Its build.gradle passes the property on, as the engine's does (`systemProperty 'settlementcore.soak', ...`); `tools/soak.py report
<log>` reads any run's SOAK lines. Or run it in the all-mods run with `-Psoak` (its `soakAndSavecheck` switches reach that run too).

## The laptop's requests

Proposed request lines for the laptop runner (docs/laptop-test-runner.md in the Under-Empire's repo has the runner's starter message):

- `together <hash>` with the `--release` line: `together.py prepare --release ...`, then `together.py run --here`; reply with the
  `tested commit:`, `tree:`, `together mods:` lines and the report's per-mod lines and VERDICT.
- `savecheck <hash> [names] [--together]`: carry `runs/savecheck/worlds` first; `savecheck.py prepare`, then
  `savecheck.py run ... --here`; reply with each world's summary head and VERDICT.
- `soak <hash> [ticks]`: `soak.py run --here --ticks N`; reply with the VERDICT line and the counts that grew.
