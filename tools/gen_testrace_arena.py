"""The test race's GameTest plot: an open 24x10x24 arena (python settlementcore/tools/gen_testrace_arena.py).

The engine's own plot, settlementcore:digsite, is a solid block of stone with a 5x5x3 pocket, made for digging. A race's units
need open ground: in the digsite they rose into the stone and choked, and a test that read a foe's health passed on its suffocation
(2026-11-01). The arena is a smooth stone floor at y 0 and air above it, written at Minecraft 26.1.2's data version (4790) with
block bench's structgen.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "tools"))
from structgen.canvas import Canvas  # noqa: E402

W, H = 24, 10
c = Canvas()
c.box(0, 0, 0, W - 1, 0, W - 1, "minecraft:smooth_stone")
c.box(0, 1, 0, W - 1, H - 1, W - 1, "minecraft:air")
out = os.path.join(HERE, "..", "src", "testrace", "resources", "data", "testrace", "structure", "arena.nbt")
c.save(os.path.normpath(out), data_version=4790)
print("wrote", os.path.normpath(out))
