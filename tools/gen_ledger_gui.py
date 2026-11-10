"""Draw the Ledger screen's texture (client/LedgerScreen): the 256 x 256 sheet a LedgerLook names.

    python settlementcore/tools/gen_ledger_gui.py                      (the engine's own: stone and parchment)
    python settlementcore/tools/gen_ledger_gui.py --out my/ledger.png --set panel=#3a2d22 --set rim_light=#cbbd9a
    python settlementcore/tools/gen_ledger_gui.py --out my/ledger.png --palette my_palette.json

A race draws its own skin by calling this with its palette, from the command line or from its own asset script:

    import gen_ledger_gui
    gen_ledger_gui.write("src/main/resources/assets/myrace/textures/gui/ledger.png", {"panel": "#3a2d22", ...})

and registers a LedgerLook with that texture (client/LedgerLooks#register). A palette names only what it changes; the
rest is STONE's.

The layout is the screen's, measured from the Under-Empire's Hoard-Ledger (its gen_assets.py, ledger_textures) and
must not move, as the screen draws and clicks by the same numbers (ledger/LedgerMenu's constants):

    the panel            0, 0      195 x 242, a one-pixel outline and a one-pixel rim inside it
    the search's well    8, 19     120 x 14
    the grid             8, 36     9 x 6 wells of 18 x 18
    the scrollbar track  175, 36   12 x 108
    the rule             8, 157    179 x 1
    the inventory        8, 159    9 x 3 wells of 18 x 18 (the slots' items sit a pixel in: 9, 160)
    the hotbar           8, 217    9 wells of 18 x 18 (9, 218)
    the studs            4 px in from each corner, 3 x 3
    the scrollbar knob   196, 0    12 x 15, and its dull one (nothing to scroll) at 208, 0
"""
import argparse
import json
import os
import random
import sys

from PIL import Image

SHEET = 256
W, H = 195, 242
SEARCH = (8, 19, 120, 14)
GRID = (8, 36, 9, 6)
TRACK = (175, 36, 12, 108)
RULE = (8, 157, 179, 1)
INVENTORY = (8, 159, 9, 3)
HOTBAR = (8, 217, 9, 1)
KNOB = (196, 0, 12, 15)
KNOB_DULL = (208, 0, 12, 15)

# The engine's own skin: worked stone with a parchment rim. Colours are "#rrggbb"; grain and speckle are how far a row
# and a pixel of the panel stray from its colour (0: flat), and seed fixes the speckle so the file is the same each run.
STONE = {
    "panel": "#4c4a46",
    "grain": 0.07,
    "speckle": 0.10,
    "seed": 26,
    "outline": "#141312",
    "rim_light": "#d8cba8",
    "rim_dark": "#786e58",
    "well": "#222120",
    "well_shadow": "#100f0e",
    "well_light": "#706c64",
    "slot": "#2e2d2b",
    "rule": "#a8966c",
    "stud": "#968c70",
    "stud_light": "#d6caaa",
    "stud_dark": "#463f32",
    "knob": "#d8cba8",
    "knob_dull": "#7c7668",
}

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "main", "resources", "assets",
                   "settlementcore", "textures", "gui", "ledger.png")


def rgb(value):
    """A palette colour as (r, g, b): "#rrggbb", or three numbers."""
    if isinstance(value, str):
        v = value.lstrip("#")
        if len(v) != 6:
            raise ValueError("a colour is #rrggbb, not " + value)
        return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))
    return tuple(int(c) for c in value[:3])


def shade(colour, k):
    return tuple(max(0, min(255, int(round(c * k)))) for c in colour)


def draw(palette=None):
    """The whole sheet as an RGBA image, in STONE with whatever {@code palette} changes."""
    p = dict(STONE)
    unknown = sorted(set(palette or {}) - set(STONE))
    if unknown:
        raise ValueError("no such palette key: " + ", ".join(unknown) + " (the keys: " + ", ".join(sorted(STONE)) + ")")
    p.update(palette or {})
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    px = img.load()

    def rect(x, y, w, h, colour):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                px[xx, yy] = colour + (255,)

    # the panel: rows of grain, each pixel a little off its row
    rnd = random.Random(int(p["seed"]))
    panel, grain, speckle = rgb(p["panel"]), float(p["grain"]), float(p["speckle"])
    for y in range(H):
        row = 1 + grain * ((y * 7) % 5 - 2) / 2
        for x in range(W):
            px[x, y] = shade(panel, row * (1 + (rnd.random() - 0.5) * speckle)) + (255,)
    outline, light, dark = rgb(p["outline"]), rgb(p["rim_light"]), rgb(p["rim_dark"])
    rect(0, 0, W, 1, outline)
    rect(0, H - 1, W, 1, outline)
    rect(0, 0, 1, H, outline)
    rect(W - 1, 0, 1, H, outline)
    rect(1, 1, W - 2, 1, light)
    rect(1, 1, 1, H - 2, light)
    rect(1, H - 2, W - 2, 1, dark)
    rect(W - 2, 1, 1, H - 2, dark)

    def well(x, y, w, h, fill):
        """A sunk box: dark along the top and left, light along the bottom and right."""
        rect(x, y, w, h, fill)
        rect(x, y, w, 1, rgb(p["well_shadow"]))
        rect(x, y, 1, h, rgb(p["well_shadow"]))
        rect(x, y + h - 1, w, 1, rgb(p["well_light"]))
        rect(x + w - 1, y, 1, h, rgb(p["well_light"]))

    def wells(x, y, cols, rows):
        for r in range(rows):
            for c in range(cols):
                well(x + c * 18, y + r * 18, 18, 18, rgb(p["slot"]))

    well(*SEARCH, rgb(p["well"]))
    wells(*GRID)
    well(*TRACK, rgb(p["well"]))
    rect(*RULE, rgb(p["rule"]))
    wells(*INVENTORY)
    wells(*HOTBAR)
    for x, y in ((4, 4), (W - 7, 4), (4, H - 7), (W - 7, H - 7)):
        rect(x, y, 3, 3, rgb(p["stud"]))
        px[x, y] = rgb(p["stud_light"]) + (255,)
        px[x + 2, y + 2] = rgb(p["stud_dark"]) + (255,)
    for (x, y, w, h), key in ((KNOB, "knob"), (KNOB_DULL, "knob_dull")):
        base = rgb(p[key])
        rect(x, y, w, h, base)
        rect(x, y, w, 1, shade(base, 1.12))
        rect(x, y, 1, h, shade(base, 1.12))
        rect(x, y + h - 1, w, 1, shade(base, 0.55))
        rect(x + w - 1, y, 1, h, shade(base, 0.55))
        for yy in (4, 7, 10):
            rect(x + 3, y + yy, 6, 1, shade(base, 0.7))
    return img


def write(path, palette=None):
    """Draw the sheet and save it at {@code path} (its folders made if need be)."""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    draw(palette).save(path)
    return path


def main(argv=None):
    ap = argparse.ArgumentParser(description="Draw the Ledger screen's texture.")
    ap.add_argument("--out", default=OUT, help="where the PNG goes (default: the engine's own ledger.png)")
    ap.add_argument("--palette", help="a JSON file of palette keys to change")
    ap.add_argument("--set", action="append", default=[], metavar="KEY=VALUE", help="one palette key, e.g. panel=#3a2d22 (may repeat)")
    ap.add_argument("--keys", action="store_true", help="print the palette's keys and the engine's values, and draw nothing")
    args = ap.parse_args(argv)
    if args.keys:
        print(json.dumps(STONE, indent=2))
        return 0
    palette = {}
    if args.palette:
        with open(args.palette, encoding="utf-8") as f:
            palette.update(json.load(f))
    for pair in args.set:
        key, sep, value = pair.partition("=")
        if not sep:
            ap.error("--set takes KEY=VALUE, not " + pair)
        palette[key] = float(value) if key in ("grain", "speckle") else int(value) if key == "seed" else value
    print(write(args.out, palette))
    return 0


if __name__ == "__main__":
    sys.exit(main())
