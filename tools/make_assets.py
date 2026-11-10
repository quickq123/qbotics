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
    "block.qbotics.input": "Input",
    "block.qbotics.output": "Output",
    "block.qbotics.bus": "Bus",
    "container.qbotics.input": "Input  %s",
    "message.qbotics.port": "%s, pointing at %s",
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
           drops_itself("qbotics:computer", copy_components=["qbotics:drive_id", "minecraft:custom_name"]))
write_json("data/qbotics/loot_table/blocks/cable.json", drops_itself("qbotics:cable"))

write_json("data/minecraft/tags/block/mineable/pickaxe.json", {
    "replace": False,
    "values": ["qbotics:computer", "qbotics:cable", "qbotics:input", "qbotics:output", "qbotics:bus"],
})
# What a cable reaches out to.
write_json("data/qbotics/tags/block/cable_connectable.json", {
    "replace": False,
    "values": ["qbotics:cable", "qbotics:computer", "qbotics:input", "qbotics:output", "qbotics:bus"],
})
# ---- item blocks: input, output, bus (design doc §11.0) ---------------------------

ARROW_IN = (110, 224, 124)
ARROW_OUT = (240, 160, 60)


def input_side():
    """Nine small slots, like the block's nine filtered slots."""
    px = case_base()
    for gy in range(3):
        for gx in range(3):
            for y in range(3):
                for x in range(3):
                    px[3 + gy * 4 + y][3 + gx * 4 + x] = VENT if (x, y) != (1, 1) else SCREEN
    return px


def input_top():
    """A hatch items drop into."""
    px = case_base()
    for y in range(3, 13):
        for x in range(3, 13):
            px[y][x] = EDGE if x in (3, 12) or y in (3, 12) else VENT
    for i in range(5, 11):
        px[i][i] = SCREEN_EDGE
        px[i][15 - i] = SCREEN_EDGE
    return px


def port_side():
    """Plain casing with a cable-coloured band, so the block reads as part of the network."""
    px = case_base()
    for x in range(1, 15):
        px[7][x] = SHEATH_DARK
        px[8][x] = CORE_RED if x % 4 in (1, 2) else SHEATH_DARK
    return px


def port_front(colours):
    """The face that touches the target: a dark opening with coloured marks."""
    px = case_base()
    for y in range(3, 13):
        for x in range(3, 13):
            px[y][x] = EDGE if x in (3, 12) or y in (3, 12) else SCREEN
    for (x, y), colour in colours.items():
        px[y][x] = colour
    return px


# Output: one arrow pointing in to the target. Bus: arrows both ways.
OUTPUT_MARKS = {(7, 5): ARROW_OUT, (8, 5): ARROW_OUT, (6, 6): ARROW_OUT, (9, 6): ARROW_OUT,
                (7, 7): ARROW_OUT, (8, 7): ARROW_OUT, (7, 8): ARROW_OUT, (8, 8): ARROW_OUT,
                (7, 9): ARROW_OUT, (8, 9): ARROW_OUT, (7, 10): ARROW_OUT, (8, 10): ARROW_OUT}
BUS_MARKS = {(5, 5): ARROW_OUT, (6, 5): ARROW_OUT, (5, 6): ARROW_OUT, (6, 6): ARROW_OUT, (5, 7): ARROW_OUT,
             (6, 7): ARROW_OUT, (4, 8): ARROW_OUT, (7, 8): ARROW_OUT, (5, 9): ARROW_OUT, (6, 9): ARROW_OUT,
             (9, 6): ARROW_IN, (10, 6): ARROW_IN, (8, 7): ARROW_IN, (11, 7): ARROW_IN, (9, 8): ARROW_IN,
             (10, 8): ARROW_IN, (9, 9): ARROW_IN, (10, 9): ARROW_IN, (9, 10): ARROW_IN, (10, 10): ARROW_IN}

write_png("assets/qbotics/textures/block/input_side.png", input_side())
write_png("assets/qbotics/textures/block/input_top.png", input_top())
write_png("assets/qbotics/textures/block/port_side.png", port_side())
write_png("assets/qbotics/textures/block/output_front.png", port_front(OUTPUT_MARKS))
write_png("assets/qbotics/textures/block/bus_front.png", port_front(BUS_MARKS))

write_json("assets/qbotics/models/block/input.json", {
    "parent": "minecraft:block/cube_column",
    "textures": {"end": "qbotics:block/input_top", "side": "qbotics:block/input_side"},
})
write_json("assets/qbotics/blockstates/input.json", {"variants": {"": {"model": "qbotics:block/input"}}})
write_json("assets/qbotics/items/input.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:block/input"},
})

for port in ("output", "bus"):
    # The model's north face is the front; the block state turns it to wherever the block points.
    write_json("assets/qbotics/models/block/%s.json" % port, {
        "parent": "minecraft:block/cube",
        "textures": {
            "particle": "qbotics:block/port_side",
            "north": "qbotics:block/%s_front" % port,
            "south": "qbotics:block/port_side",
            "east": "qbotics:block/port_side",
            "west": "qbotics:block/port_side",
            "up": "qbotics:block/port_side",
            "down": "qbotics:block/port_side",
        },
    })
    model = {"model": "qbotics:block/%s" % port}
    write_json("assets/qbotics/blockstates/%s.json" % port, {
        "variants": {
            "facing=north": model,
            "facing=east": dict(model, y=90),
            "facing=south": dict(model, y=180),
            "facing=west": dict(model, y=270),
            "facing=up": dict(model, x=270),
            "facing=down": dict(model, x=90),
        },
    })
    write_json("assets/qbotics/items/%s.json" % port, {
        "model": {"type": "minecraft:model", "model": "qbotics:block/%s" % port},
    })

# Placeholder recipes (design doc §20): four cables around the vanilla block each one resembles.
for name, heart in (("input", "minecraft:chest"), ("output", "minecraft:dropper"), ("bus", "minecraft:hopper")):
    write_json("data/qbotics/recipe/%s.json" % name, shaped(
        [" C ", "CHC", " C "], {"C": "qbotics:cable", "H": heart}, "qbotics:%s" % name))
    # A device named in an anvil keeps its name when broken.
    write_json("data/qbotics/loot_table/blocks/%s.json" % name,
               drops_itself("qbotics:%s" % name, copy_components=["minecraft:custom_name"]))

print("assets written")
