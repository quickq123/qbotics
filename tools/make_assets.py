"""Writes Qbotics' generated resources (16x16 textures, models, recipes, loot tables, tags)
into /res, which should be common/src/main/resources. The outputs are committed; edit this
script and re-run it to change them. It needs only Python 3, for example in the engine
toolchain container, from the repository root:

    docker run --rm -v "$PWD/tools/make_assets.py:/m.py:ro" \\
        -v "$PWD/common/src/main/resources:/res" qbot-engine-toolchain python3 /m.py
"""
import json
import os
import struct
import zlib

RES = "/res"


def write_json(path, obj):
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def write_png(path, pixels):
    """pixels: 16 rows of 16 (r, g, b) tuples."""
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(full, "wb") as f:
        f.write(png)


# ---- textures ----------------------------------------------------------------

CASE = (150, 154, 160)
CASE_LIGHT = (176, 180, 186)
CASE_DARK = (112, 116, 122)
EDGE = (78, 82, 88)
VENT = (60, 63, 68)
SCREEN = (12, 18, 22)
SCREEN_EDGE = (40, 44, 50)
GREEN = (110, 224, 124)
LED = (90, 220, 100)
RED = (200, 60, 50)


def case_base():
    """A slightly shaded metal panel with a darker outline."""
    px = [[CASE for _ in range(16)] for _ in range(16)]
    for i in range(16):
        px[0][i] = CASE_LIGHT
        px[i][0] = CASE_LIGHT
        px[15][i] = CASE_DARK
        px[i][15] = CASE_DARK
    for i in range(16):
        px[0][i] = EDGE if i in (0, 15) else px[0][i]
        px[15][i] = EDGE if i in (0, 15) else px[15][i]
    # subtle speckle so it doesn't look flat
    for (x, y) in [(3, 4), (11, 2), (6, 12), (13, 9), (2, 10), (9, 6), (12, 13), (5, 8)]:
        px[y][x] = CASE_LIGHT
    for (x, y) in [(4, 11), (10, 4), (7, 3), (13, 5), (3, 7), (9, 13)]:
        px[y][x] = CASE_DARK
    return px


def side():
    px = case_base()
    for y in (5, 7, 9):          # vent slots
        for x in range(4, 12):
            px[y][x] = VENT
    return px


def top():
    px = case_base()
    for y in range(4, 12):       # a recessed square panel
        for x in range(4, 12):
            px[y][x] = CASE_DARK if x in (4, 11) or y in (4, 11) else CASE
    px[7][7] = px[7][8] = px[8][7] = px[8][8] = RED   # the redstone at its heart
    return px


def front():
    px = case_base()
    for y in range(2, 11):       # screen with a thin frame
        for x in range(2, 14):
            px[y][x] = SCREEN_EDGE if x in (2, 13) or y in (2, 10) else SCREEN
    # ">_" prompt
    for (x, y) in [(4, 4), (5, 5), (4, 6), (7, 6), (8, 6)]:
        px[y][x] = GREEN
    # control strip: power light and two buttons
    px[13][3] = LED
    px[13][11] = VENT
    px[13][12] = VENT
    for x in range(5, 10):
        px[13][x] = CASE_DARK    # drive slot
    return px


write_png("assets/qbotics/textures/block/computer_side.png", side())
write_png("assets/qbotics/textures/block/computer_top.png", top())
write_png("assets/qbotics/textures/block/computer_front.png", front())

# ---- models and block state ---------------------------------------------------

write_json("assets/qbotics/models/block/computer.json", {
    "parent": "minecraft:block/orientable",
    "textures": {
        "front": "qbotics:block/computer_front",
        "side": "qbotics:block/computer_side",
        "top": "qbotics:block/computer_top",
    },
})
write_json("assets/qbotics/blockstates/computer.json", {
    "variants": {
        "facing=north": {"model": "qbotics:block/computer"},
        "facing=east": {"model": "qbotics:block/computer", "y": 90},
        "facing=south": {"model": "qbotics:block/computer", "y": 180},
        "facing=west": {"model": "qbotics:block/computer", "y": 270},
    },
})
write_json("assets/qbotics/items/computer.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:block/computer"},
})
write_json("assets/qbotics/lang/en_us.json", {
    "block.qbotics.computer": "Computer",
    "block.qbotics.cable": "Cable",
    "screen.qbotics.terminal": "Computer Terminal",
})

# ---- cable: texture, connecting model ------------------------------------------

SHEATH = (92, 96, 104)
SHEATH_LIGHT = (122, 126, 134)
SHEATH_DARK = (64, 67, 74)
CORE_RED = (190, 40, 34)


def cable_texture():
    """Iron-grey sheath with a redstone stripe; tiles along the cable's length."""
    px = [[SHEATH for _ in range(16)] for _ in range(16)]
    for y in range(16):
        for x in range(16):
            band = x % 4
            if band == 0:
                px[y][x] = SHEATH_LIGHT
            elif band == 3:
                px[y][x] = SHEATH_DARK
            if y % 4 in (1, 2) and band in (1, 2):
                px[y][x] = CORE_RED
    return px


write_png("assets/qbotics/textures/block/cable.png", cable_texture())

CABLE_TEXTURES = {"all": "qbotics:block/cable", "particle": "qbotics:block/cable"}


def faces(*names):
    return {name: {"texture": "#all"} for name in names}


# The centre piece, always shown.
write_json("assets/qbotics/models/block/cable_core.json", {
    "parent": "minecraft:block/block",
    "textures": CABLE_TEXTURES,
    "elements": [{
        "from": [6, 6, 6], "to": [10, 10, 10],
        "faces": faces("north", "south", "east", "west", "up", "down"),
    }],
})
# An arm from the centre to the north face; the block state rotates it for other sides.
write_json("assets/qbotics/models/block/cable_side.json", {
    "parent": "minecraft:block/block",
    "textures": CABLE_TEXTURES,
    "elements": [{
        "from": [6, 6, 0], "to": [10, 10, 6],
        "faces": faces("north", "east", "west", "up", "down"),
    }],
})
# In the hand and inventory: a straight length of cable.
write_json("assets/qbotics/models/item/cable.json", {
    "parent": "minecraft:block/block",
    "textures": CABLE_TEXTURES,
    "elements": [{
        "from": [6, 6, 0], "to": [10, 10, 16],
        "faces": faces("north", "south", "east", "west", "up", "down"),
    }],
})
write_json("assets/qbotics/items/cable.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:item/cable"},
})


def arm(side, **rotation):
    apply = {"model": "qbotics:block/cable_side"}
    apply.update(rotation)
    return {"when": {side: "true"}, "apply": apply}


write_json("assets/qbotics/blockstates/cable.json", {
    "multipart": [
        {"apply": {"model": "qbotics:block/cable_core"}},
        arm("north"),
        arm("east", y=90),
        arm("south", y=180),
        arm("west", y=270),
        arm("up", x=270),
        arm("down", x=90),
    ],
})

# ---- data: recipes, drops, tags -------------------------------------------------


def shaped(pattern, key, result, count=1):
    out = {"id": result}
    if count != 1:
        out["count"] = count
    return {
        "type": "minecraft:crafting_shaped",
        "category": "redstone",
        "key": key,
        "pattern": pattern,
        "result": out,
    }


def drops_itself(name, copy_components=None):
    entry = {"type": "minecraft:item", "name": name}
    if copy_components:
        entry["functions"] = [{
            "function": "minecraft:copy_components",
            "include": copy_components,
            "source": "block_entity",
        }]
    return {
        "type": "minecraft:block",
        "pools": [{
            "bonus_rolls": 0.0,
            "conditions": [{"condition": "minecraft:survives_explosion"}],
            "entries": [entry],
            "rolls": 1.0,
        }],
        "random_sequence": name.replace(":", ":blocks/"),
    }


# Cable: 6 iron ingots and 3 redstone give 16, as vertical or as horizontal lines.
CABLE_KEY = {"I": "minecraft:iron_ingot", "R": "minecraft:redstone"}
write_json("data/qbotics/recipe/cable.json",
           shaped(["IRI", "IRI", "IRI"], CABLE_KEY, "qbotics:cable", 16))
write_json("data/qbotics/recipe/cable_horizontal.json",
           shaped(["III", "RRR", "III"], CABLE_KEY, "qbotics:cable", 16))

# Computer: cable in the shape of leggings, redstone in the middle, glass at the bottom middle.
write_json("data/qbotics/recipe/computer.json", shaped(
    ["CCC", "CRC", "CGC"],
    {"C": "qbotics:cable", "R": "minecraft:redstone", "G": "minecraft:glass"},
    "qbotics:computer"))

# A broken computer keeps its drive (its files).
write_json("data/qbotics/loot_table/blocks/computer.json",
           drops_itself("qbotics:computer", copy_components=["qbotics:drive_id"]))
write_json("data/qbotics/loot_table/blocks/cable.json", drops_itself("qbotics:cable"))

write_json("data/minecraft/tags/block/mineable/pickaxe.json", {
    "replace": False,
    "values": ["qbotics:computer", "qbotics:cable"],
})
# What a cable reaches out to.
write_json("data/qbotics/tags/block/cable_connectable.json", {
    "replace": False,
    "values": ["qbotics:cable", "qbotics:computer"],
})
print("assets written")
