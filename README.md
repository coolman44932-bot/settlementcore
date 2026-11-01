# Settlement Core

The settlement engine every race mod builds on: rooms dug from plans and raised on the surface, their crews and work, the haul
and the hoard, larders and fare, a settlement that defends itself, the GameTest harness, and crowds drawn fast. It registers
nothing a player sees: a race does.

- **Minecraft 26.1.2**, NeoForge 26.1.2.125, GeckoLib 5.5.2, Java 25. (Its 1.21.1 past is the Under-Empire repo's `core/`,
  tag `mc-1.21.1-final`; this repo was split out of it with its history on 2026-10-31.)
- Mod id `settlementcore`, package `claude.settlementcore`.

## Build and test

```bash
gradlew.bat build                      # build/libs/settlementcore-<v>.jar and settlementcore-testrace-<v>.jar
python tools/gametest.py all           # every GameTest (the engine's and the test race's)
python tools/gametest.py settlement    # one batch, or tests by name (-Ponly)
python tools/gametest.py all --ns settlementcore   # only the engine's
gradlew.bat runClient                  # a dev client with the engine and the test race
```

## How a race plugs in

A race mod depends on `settlementcore` and fills in what is its own:

| The engine's | The race gives |
|---|---|
| `settlement/SettlementBlockEntity` | its block entity: what its people eat, how they are made (`Spawning`), its caps, currency, land |
| `unit/SettlerMob` | its units: their orders, crew state, going into and out of the world, courage |
| `client/HordeRenderer`, `HordeGeoModel` | its units' GeckoLib models, drawn the crowd's way |
| `settlement/Currencies`, `Settlements` | its currency and how to find a player's settlement |
| `room/Rooms`, `work/RoomWorks` | its rooms and their works |
| `test/GameTests` | `GameTests.register(container, modBus)` from its constructor: its `@GameTest`s, as 1.21.1 wrote them |

The **test race** (`src/testrace`, mod id `testrace`, dev-only) is the smallest whole race: a Heart, a Worker and a Guard, emeralds,
a crowd, and its own GameTests. Copy it to start a new race. Its Worker and Guard are modelled in Blockbench (`block bench/bbsrc/testrace_build.js`); `tools/gen_testrace_assets.py`
makes its block, item and lang files (`--figures` for box-figure units to start a race from).

## Rules the build keeps

- `checkCoreNamespace`: the engine's data and code never name a race's namespace (a race adds to the engine's tags from its own jar).
- GameTests register only where they are on (dev runs and the GameTest server); a player's game has none of them.
