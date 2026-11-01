"""The test race's assets, deterministically (python settlementcore/tools/gen_testrace_assets.py; needs Pillow).

Writes under src/testrace/resources/assets/testrace/:
  blockstates/heart.json, models/block/heart.json, items/heart.json, textures/block/heart.png
  items/*_spawn_egg.json, models/item/*_spawn_egg.json, textures/item/*_spawn_egg.png
  lang/en_us.json
The Worker's and Guard's models, clips and textures are Blockbench's since 2026-11-01 (block bench/bbsrc/testrace_build.js, TRB):
this script no longer writes them, so running it never overwrites them. Its humanoid()/animations() are kept for a race that
wants box figures to start from (main() --figures).
"""
import json
import math
import os

from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "src", "testrace", "resources", "assets", "testrace")
TEX = 64


def write(rel, data):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


class Packer:
    """Lays out box UVs left to right in rows on a TEX x TEX sheet."""

    def __init__(self):
        self.x, self.y, self.row = 0, 0, 0
        self.placed = []

    def place(self, size, colour):
        w, h, d = size
        uw, uh = 2 * (w + d), d + h
        if self.x + uw > TEX:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        uv = [self.x, self.y]
        self.placed.append((uv, size, colour))
        self.x += uw
        self.row = max(self.row, uh)
        if self.y + uh > TEX:
            raise SystemExit("texture sheet full")
        return uv


def cube(p, origin, size, colour, inflate=0.0):
    c = {"origin": list(origin), "size": list(size), "uv": p.place(size, colour)}
    if inflate:
        c["inflate"] = inflate
    return c


def humanoid(name, scale, skin, cloth, extra):
    """A box humanoid {scale} times the worker's size; {extra}(packer, bones) adds the kind's own cubes."""
    p = Packer()
    s = scale
    leg_h, body_h, head = 6 * s, 7 * s, 6 * s
    leg_w, body_w, body_d, arm_w = 2.5 * s, 6 * s, 3.5 * s, 2 * s
    hip = leg_h
    shoulder = hip + body_h
    bones = [
        {"name": "root", "pivot": [0, 0, 0]},
        {"name": "body", "parent": "root", "pivot": [0, hip, 0],
         "cubes": [cube(p, (-body_w / 2, hip, -body_d / 2), (body_w, body_h, body_d), cloth)]},
        {"name": "head", "parent": "body", "pivot": [0, shoulder, 0],
         "cubes": [cube(p, (-head / 2, shoulder, -head / 2), (head, head, head), skin)]},
        {"name": "right_arm", "parent": "body", "pivot": [-body_w / 2 - arm_w / 2, shoulder - 0.5 * s, 0],
         "cubes": [cube(p, (-body_w / 2 - arm_w, shoulder - body_h, -arm_w / 2), (arm_w, body_h, arm_w), skin)]},
        {"name": "right_hand", "parent": "right_arm", "pivot": [-body_w / 2 - arm_w / 2, shoulder - body_h + 0.5, 0]},
        {"name": "left_arm", "parent": "body", "pivot": [body_w / 2 + arm_w / 2, shoulder - 0.5 * s, 0],
         "cubes": [cube(p, (body_w / 2, shoulder - body_h, -arm_w / 2), (arm_w, body_h, arm_w), skin)]},
        {"name": "right_leg", "parent": "root", "pivot": [-leg_w / 2, hip, 0],
         "cubes": [cube(p, (-leg_w, 0, -leg_w / 2), (leg_w, leg_h, leg_w), cloth)]},
        {"name": "left_leg", "parent": "root", "pivot": [leg_w / 2, hip, 0],
         "cubes": [cube(p, (0, 0, -leg_w / 2), (leg_w, leg_h, leg_w), cloth)]},
    ]
    extra(p, bones, s, shoulder, head, body_w, arm_w)
    geo = {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.testrace.{name}", "texture_width": TEX, "texture_height": TEX,
                        "visible_bounds_width": 3, "visible_bounds_height": 3.5, "visible_bounds_offset": [0, 1.5, 0]},
        "bones": bones}]}
    write(f"geckolib/models/entity/{name}.geo.json", geo)  # GeckoLib 5 reads only geckolib/models and geckolib/animations
    paint(name, p)


def paint(name, p):
    img = Image.new("RGBA", (TEX, TEX), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for (u, v), (w, h, dd), colour in p.placed:
        w, h, dd = (int(math.ceil(x)) for x in (w, h, dd))
        r, g, b = colour
        shade = lambda k: (int(r * k), int(g * k), int(b * k), 255)
        d.rectangle([u + dd, v, u + dd + w - 1, v + dd - 1], fill=shade(1.1 if r < 230 else 1.0))       # top
        d.rectangle([u + dd + w, v, u + dd + 2 * w - 1, v + dd - 1], fill=shade(0.7))                   # bottom
        d.rectangle([u, v + dd, u + dd - 1, v + dd + h - 1], fill=shade(0.85))                          # right
        d.rectangle([u + dd, v + dd, u + dd + w - 1, v + dd + h - 1], fill=shade(1.0))                  # front
        d.rectangle([u + dd + w, v + dd, u + 2 * dd + w - 1, v + dd + h - 1], fill=shade(0.85))         # left
        d.rectangle([u + 2 * dd + w, v + dd, u + 2 * dd + 2 * w - 1, v + dd + h - 1], fill=shade(0.75))  # back
    path = os.path.join(ROOT, "textures", "entity", f"{name}.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def worker_extra(p, bones, s, shoulder, head, body_w, arm_w):
    bones[2]["cubes"].append(cube(p, (-head / 2 - 0.5, shoulder + head - 1, -head / 2 - 0.5), (head + 1, 1.5, head + 1), (150, 110, 60)))  # a cap


def guard_extra(p, bones, s, shoulder, head, body_w, arm_w):
    bones[2]["cubes"].append(cube(p, (-head / 2, shoulder + head, -1), (head, 2, 2), (200, 40, 40)))                # the helmet's crest
    bones[5]["cubes"].append(cube(p, (body_w / 2 + arm_w, shoulder - 7 * s, -3 * s), (1, 6 * s, 6 * s), (90, 90, 110)))  # a shield


def animations(name):
    def loop(length, bones):
        return {"loop": True, "animation_length": length, "bones": bones}

    def swing(deg, length):
        return {"0.0": [deg, 0, 0], str(round(length / 2, 3)): [-deg, 0, 0], str(length): [deg, 0, 0]}

    walk = 0.8
    anims = {
        "animation.testrace.idle": loop(2.0, {
            "body": {"rotation": {"0.0": [0, 0, 0], "1.0": [2, 0, 0], "2.0": [0, 0, 0]}},
            "head": {"rotation": {"0.0": [0, 0, 0], "1.0": [-3, 4, 0], "2.0": [0, 0, 0]}}}),
        "animation.testrace.walk": loop(walk, {
            "right_leg": {"rotation": swing(30, walk)}, "left_leg": {"rotation": swing(-30, walk)},
            "right_arm": {"rotation": swing(-25, walk)}, "left_arm": {"rotation": swing(25, walk)},
            "body": {"position": {"0.0": [0, 0, 0], str(walk / 4): [0, 0.4, 0], str(walk / 2): [0, 0, 0],
                                  str(3 * walk / 4): [0, 0.4, 0], str(walk): [0, 0, 0]}}}),
        "animation.testrace.work": loop(0.6, {
            "right_arm": {"rotation": {"0.0": [-150, 0, 0], "0.3": [-40, 0, 0], "0.6": [-150, 0, 0]}},
            "left_arm": {"rotation": {"0.0": [-60, 0, 0], "0.3": [-30, 0, 0], "0.6": [-60, 0, 0]}},
            "body": {"rotation": {"0.0": [10, 0, 0], "0.3": [20, 0, 0], "0.6": [10, 0, 0]}}}),
    }
    write(f"geckolib/animations/entity/{name}.animation.json", {"format_version": "1.8.0", "animations": anims})


def solid_png(rel, colour, border):
    img = Image.new("RGBA", (16, 16), colour + (255,))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], outline=border + (255,))
    d.rectangle([5, 5, 10, 10], fill=border + (255,))
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def egg_png(rel, shell, spots):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([3, 1, 12, 14], fill=shell + (255,), outline=tuple(int(c * 0.6) for c in shell) + (255,))
    for x, y in ((6, 4), (9, 7), (5, 9), (8, 11)):
        d.rectangle([x, y, x + 1, y + 1], fill=spots + (255,))
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def main():
    import sys
    if "--figures" in sys.argv:                          # box figures only on request: the real models come from Blockbench
        humanoid("worker", 1.0, (230, 190, 150), (90, 130, 70), worker_extra)
        humanoid("guard", 1.2, (220, 180, 140), (70, 80, 150), guard_extra)
        animations("worker")
        animations("guard")
    solid_png("textures/block/heart.png", (60, 200, 110), (20, 90, 50))
    write("blockstates/heart.json", {"variants": {"": {"model": "testrace:block/heart"}}})
    write("models/block/heart.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "testrace:block/heart"}})
    write("items/heart.json", {"model": {"type": "minecraft:model", "model": "testrace:block/heart"}})
    for name, shell, spots in (("worker", (90, 130, 70), (230, 190, 150)), ("guard", (70, 80, 150), (200, 40, 40))):
        egg_png(f"textures/item/{name}_spawn_egg.png", shell, spots)
        write(f"models/item/{name}_spawn_egg.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"testrace:item/{name}_spawn_egg"}})
        write(f"items/{name}_spawn_egg.json", {"model": {"type": "minecraft:model", "model": f"testrace:item/{name}_spawn_egg"}})
    write("lang/en_us.json", {
        "block.testrace.heart": "Test Heart",
        "entity.testrace.worker": "Test Worker",
        "entity.testrace.guard": "Test Guard",
        "item.testrace.worker_spawn_egg": "Test Worker Spawn Egg",
        "item.testrace.guard_spawn_egg": "Test Guard Spawn Egg",
    })
    print("assets written under", ROOT)


main()
