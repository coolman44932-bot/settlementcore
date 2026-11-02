# Walls: settlements that know their walls, and settlers who build them

The user, 2026-11-01: "you know what i realized? our settlement core doesnt take walls into accountment", then "make sure they can
build walls too".

**Until now:** a settlement's land was a box round its heart (the race's `inSettlement`), and walls counted for nothing.
- A foe on the far side of a palisade, inside the box, raised the alarm.
- Walling in more ground didn't make the settlement bigger.
- Nothing about a wall helped the defence.

## E1: the engine finds its wall (Walls)

- **The scan.** From the heart, it walks the ground outward the way a mob would:
  - a mob steps up 1 and drops at most 3, and swims across water;
  - a cell is walkable when it has 2 collision-free blocks over a floor;
  - fence gates and doors always count as wall, open or shut, so the settlers' own gates never break the ring.
  - If the walk never gets further than `Walls.RADIUS` (64) from the heart, the settlement is **walled**, and the columns it reached
    are **inside the walls**.
  - It scans every 30 s, and 5 s after any block near the settlement is placed or broken. An unloaded column counts as open (it can't
    be checked).
- **A walled settlement:**
  - **Alarms:** only foes inside the walls raise the alarm and draw defenders. Outside is outside, even inside the race's box
    (`Muster.holds`).
  - **Spawning:** no monster spawns anywhere inside the walls, as well as in the box (`SettlementSpawns`).
  - **Building:** a building can be raised anywhere inside the walls, as well as in the box (`Works.raise`).
  - The race's own box is unchanged, so nothing that worked before stops working.
- **Breaches.** When a walled settlement's scan finds a way out, its owner is told where the wall was broken
  (`Settlement.wallBrokenLine`). When a ring closes, the owner is told it's walled in (`wallClosedLine`). A race can say both in its
  own voice.
- **Opting out.** A race keeps walls off with `takesWalls()`. It's on by default; an underground race may want it off.

## E2: settlers build walls (WallJob) - built

- **The style.** A race registers a `WallStyle` at start-up (`WallStyle.register(namespace, style)`):
  - its height (2 at least: a mob steps over 1), the price a column, and how wide a gate is;
  - what goes in each course of a column and of a gate column, asked by column index, course, height and the direction across the
    wall (toward the settlement). The answer must be the same every time: a saved wall is rebuilt from it.
  - The style is also a raised building of the race's, made in code (`Rooms.family`, room id `wall_<id>`), so a wall being raised
    is one of the settlement's sites like any building: same crews, Ledger line, call-off and refund.
- **The order.** `Works.raiseWall(level, posts, style, ring, gatePosts, free)`:
  - the posts are joined by straight runs of columns side by side (never only corner to corner), a ring closing back to the first;
  - each post in `gatePosts` is the middle of a gate;
  - each column stands on its own ground: no levelling, no ramps. A column in water (4 deep at most) is footed on the bed and that
    much higher. Lava refuses it.
  - Refused (before anything is paid) past `Walls.RADIUS` from the heart, in unloaded ground, over 512 columns, or unpaid.
- **The job.** `WallJob` extends `RaiseJob` with its own task list:
  - the clearing: a tree whose log stands in the wall's cells is felled whole (logs and wild leaves, up to `Siting.TREE_MAX`), and a
    leaf there is cut. A log with no leaves is a player's: it stays, and is part of the wall.
  - then the courses, all along the wall: the first everywhere, then the second, under the course window, heaved when stalled.
  - each block is joined to its neighbours as it goes in (fences, gates in walls), never a loose post.
  - A player's block or a building's in a cell stays, and is part of the wall.
- **Saving.** It saves the columns' feet and heights, which way is across, the gates and the trees, and rebuilds from those and the
  style alone, never from the world.
- **When it's done,** the settlement records the wall (E3) and scans its walls again soon: the ring it closed is found.

## E3: gates, manning the wall, patches - built

- **The gates** are found by the scan: every fence gate and door the walk met as wall.
- **Foes at the walls.** A walled settlement's muster also watches outside: a foe within `Walls.AT_THE_WALLS` (4) of the wall,
  outside it, is at the walls.
  - The gates shut, and the owner is told (`Settlement.besiegedLine`, no bell: nothing is in yet).
  - Defenders come up as for any alarm, but the muster's ground is inside the walls, so they come up just inside the wall nearest
    the foe: they man it.
  - If a foe then gets in, the alarm proper is raised (the bell and `alarmLine`).
  - At the all-clear the gates open.
  - Foes further out than that are nothing to it, as in E1.
- **Patches.** A wall the settlers raised is recorded (`Walls.record`: its style and line).
  - Every 30 s, with no alarm up and no patch at work, a recorded cell that lost its block (air, water, the land or a plant now)
    is patched by the builders, free, just those cells (`WallJob.patch`, `Works.patchWall`). The owner is told
    (`Settlement.wallPatchLine`).
  - A block a player breaks out of a recorded wall stays out (`Walls.forget`): that's their way through, never patched.
  - The record is saved with the settlement (key `walls`, only once it has raised a wall).

## Races

- **The Waaagh!:** the spiked log palisade (camp/CampWalls): spruce and dark oak logs three high, a plate of scrap here and
  there, sharpened stakes and skulls on top, a three-wide gate of open fence gates under a log lintel with skulls on it; a tooth a
  stake and a scrap every 8. Gork's Wall Scrawl sets posts (on the first again closes the ring, sneak-use puts up the line, on a
  post makes a gate), and on the Idol walls da whole camp, the gate facing Gork. The camp's lines are in Ork.
- **Later ports:** the Great Plan's temple walls, the Chaos Dwarfs' hold walls. The Under-Empire stays off (underground).

## Tests (the engine's)

- **E1:**
  - a 2-high stone ring walls a settlement, and a gap opens it;
  - a 1-high ring doesn't (mobs step over);
  - an open gate still holds;
  - a foe outside the wall raises no alarm, and one inside does;
  - no spawns inside the walls beyond the box;
  - a breach is told.
- **E2:**
  - crewed test-race workers lay a wall along a line over uneven ground, every column on its own ground;
  - a ring closes and walls the settlement;
  - save and load mid-wall;
  - a cancel refunds.
- **E3:** gates shut and open with the alarm; defenders at the gate; a broken wall is patched.
