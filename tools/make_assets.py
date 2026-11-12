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
    """pixels: 16 rows of 16 (r, g, b) tuples. A pixel of None is see-through."""
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    clear = any(px is None for row in pixels for px in row)
    if clear:
        pixels = [[(0, 0, 0, 0) if px is None else tuple(px) + (255,) for px in row] for row in pixels]
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6 if clear else 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(full, "wb") as f:
        f.write(png)


# ---- textures ----------------------------------------------------------------

# The mod's look: whitish iron (it is made of iron) with redstone red for whatever is live.
CASE = (216, 216, 216)
CASE_LIGHT = (238, 238, 238)
CASE_DARK = (172, 172, 172)
CASE_SHADE = (206, 206, 206)   # barely darker than CASE
EDGE = (128, 128, 130)
VENT = (92, 92, 96)
SCREEN = (16, 14, 16)
SCREEN_EDGE = (70, 68, 70)
RED = (200, 36, 28)
RED_BRIGHT = (255, 72, 48)
GREEN = RED_BRIGHT    # the ">_" on the computer's screen
LED = RED_BRIGHT


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
    # Two faint brushed lines, as on the game's iron block. Anything busier reads as sand.
    for y in (5, 10):
        for x in range(1, 15):
            px[y][x] = CASE_SHADE
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


def front_off():
    """A bricked computer (design doc §5.3): a dark, empty screen and a red power light."""
    px = front()
    for y in range(3, 10):
        for x in range(3, 13):
            px[y][x] = SCREEN
    px[13][3] = RED
    return px


write_png("assets/qbotics/textures/block/computer_front_off.png", front_off())

# ---- models and block state ---------------------------------------------------

write_json("assets/qbotics/models/block/computer.json", {
    "parent": "minecraft:block/orientable",
    "textures": {
        "front": "qbotics:block/computer_front",
        "side": "qbotics:block/computer_side",
        "top": "qbotics:block/computer_top",
    },
})
write_json("assets/qbotics/models/block/computer_off.json", {
    "parent": "minecraft:block/orientable",
    "textures": {
        "front": "qbotics:block/computer_front_off",
        "side": "qbotics:block/computer_side",
        "top": "qbotics:block/computer_top",
    },
})
COMPUTER_TURNS = {"north": 0, "east": 90, "south": 180, "west": 270}
computer_variants = {}
for facing, turn in COMPUTER_TURNS.items():
    for active, model in (("true", "qbotics:block/computer"), ("false", "qbotics:block/computer_off")):
        variant = {"model": model}
        if turn:
            variant["y"] = turn
        computer_variants["active=%s,facing=%s" % (active, facing)] = variant
write_json("assets/qbotics/blockstates/computer.json", {"variants": computer_variants})
write_json("assets/qbotics/items/computer.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:block/computer"},
})
write_json("assets/qbotics/lang/en_us.json", {
    "block.qbotics.computer": "Computer",
    "block.qbotics.cable": "Cable",
    "itemGroup.qbotics": "Qbotics",
    "block.qbotics.interface": "Interface",
    "block.qbotics.drive_bay": "Drive Bay",
    "block.qbotics.programmer": "Programmer",
    "screen.qbotics.edit_files": "Edit files",
    "screen.qbotics.programmer_hint": "Redstone on: installs that",
    "screen.qbotics.programmer_hint2": "level's program (programs.txt)",
    "block.qbotics.text_monitor": "Text Monitor",
    "item.qbotics.transport_upgrade": "Transport Upgrade",
    "block.qbotics.iron_qbot": "Iron Qbot",
    "block.qbotics.qbot_part": "Qbot Part",
    "screen.qbotics.qbot.hand": "Hand",
    "screen.qbotics.qbot.upgrade": "Upgrade",
    "screen.qbotics.qbot.energy": "Energy: %s / %s FE",
    "item.qbotics.chunk_loader_upgrade": "Chunk Loader Upgrade",
    "item.qbotics.transport_upgrade.tooltip": "Carries items, fluid and energy to other networks",
    "item.qbotics.chunk_loader_upgrade.tooltip": "Keeps its network's chunks loaded",
    "item.qbotics.upgrade.fits": "Fits an antenna (right-click it); a Chunk Loader also fits a Qbot",
    "screen.qbotics.antenna.upgrades": "Upgrades",
    "screen.qbotics.antenna.chunks": "Chunk loading:",
    "screen.qbotics.antenna.loading_on": "Chunk loading: On",
    "screen.qbotics.antenna.loading_off": "Chunk loading: Off",
    "screen.qbotics.antenna.status.loading": "Chunks held: %s",
    "screen.qbotics.antenna.status.capped": "Chunks held: %s of %s (server limit)",
    "screen.qbotics.antenna.status.no_upgrade": "Needs a Chunk Loader Upgrade",
    "screen.qbotics.antenna.status.off": "Switched off",
    "screen.qbotics.antenna.status.disabled": "Switched off by the server",
    "screen.qbotics.antenna.status.owner_offline": "Paused: its owner is offline",
    "screen.qbotics.antenna.status.over_limit": "Paused: owner has too many loaders",
    "screen.qbotics.antenna.status.inactive": "Inactive antenna",
    "screen.qbotics.antenna.status.no_owner": "No owner: placed by a machine",
    "block.qbotics.control_panel": "Control Panel",
    "screen.qbotics.close_up": "Close-up",
    "screen.qbotics.close_up_waiting": "Waiting for the screen...",
    "screen.qbotics.close_up_hint": "Click to touch; drag a slider. Esc closes.",
    "item.qbotics.hard_drive": "Hard Drive",
    "item.qbotics.hard_drive.blank": "Blank, %s MB. Put it in a Drive Bay to use it.",
    "item.qbotics.hard_drive.used": "Holds files (%s MB). In a Drive Bay, computers on its network see them in /net.",
    "screen.qbotics.drive_bay_hint": "Computers on this network",
    "screen.qbotics.drive_bay_hint2": "see these drives in /net",
    "item.qbotics.bus": "Item Serial Bus",
    "item.qbotics.pin": "Logic Pin",
    "screen.qbotics.tank": "Tank %s",
    "screen.qbotics.tank_empty": "Empty (holds %s mB)",
    "screen.qbotics.tank_holds": "%s: %s / %s mB",
    "screen.qbotics.tank_any": "Takes any fluid",
    "screen.qbotics.tank_only": "Takes only %s",
    "screen.qbotics.socket_fluid_help": "Click with a bucket of a fluid to keep this tank for it; with an empty hand to let it take any.",
    "screen.qbotics.bucket_help": "A full bucket put here empties into the tank above; an empty one fills from it.",
    "screen.qbotics.charge": "Charge",
    "screen.qbotics.drain": "Drain",
    "screen.qbotics.charge_help": "A battery put here is charged from this store.",
    "screen.qbotics.drain_help": "A battery put here is drained into this store.",
    "item.qbotics.placer": "Placer",
    "item.qbotics.breaker": "Breaker",
    "block.qbotics.experience_interface": "Experience Interface",
    "screen.qbotics.xp.holds": "Holds %s points: enough to take a new player to level %s",
    "screen.qbotics.xp.you": "You are level %s",
    "screen.qbotics.xp.locked": "This is locked: you can't store or take here",
    "screen.qbotics.xp.levels": "Levels",
    "screen.qbotics.xp.store": "Store",
    "screen.qbotics.xp.take": "Take",
    "screen.qbotics.xp.store_all": "Store All",
    "screen.qbotics.xp.take_all": "Take All",
    "screen.qbotics.xp.lock.private": "Lock: only the owner (click to change)",
    "screen.qbotics.xp.lock.team": "Lock: the owner's team (click to change)",
    "screen.qbotics.xp.lock.public": "Lock: open to everyone (click to change)",
    "block.qbotics.fluid_interface": "Fluid Interface",
    "block.qbotics.energy_interface": "Energy Interface",
    "item.qbotics.fluid_bus": "Fluid Serial Bus",
    "item.qbotics.energy_bus": "Energy Serial Bus",
    "item.qbotics.usb": "Universal Serial Bus (USB)",
    "item.qbotics.probe": "Network Probe",
    "tooltip.qbotics.probe.name": "Name in programs: %s",
    "tooltip.qbotics.probe.fluid": "Fluid inside: %s",
    "tooltip.qbotics.probe.hint": "Ctrl+C copies the name. Hold Shift for its tags.",
    "tooltip.qbotics.probe.copied": "Copied",
    "tooltip.qbotics.probe.no_tags": "  (no tags)",
    "tooltip.qbotics.probe.more_tags": "  ... and %s more",
    "item.qbotics.probe.mode.0": "Network Probe (Labels)",
    "item.qbotics.probe.mode.1": "Network Probe (Slots)",
    "message.qbotics.probe_mode.0": "Probe: Labels - right-click a device for its name and addresses. Sneak + scroll changes mode.",
    "message.qbotics.probe_mode.1": "Probe: Slots - open a chest, furnace or machine to see its slot numbers. Sneak + scroll changes mode.",
    "message.qbotics.probe_no_slots": "That block has no screen to show slots on.",
    "item.qbotics.screen": "Screen",
    "item.qbotics.motherboard": "Motherboard",
    "message.qbotics.probe_nothing": "That isn't a network device. Aim at a computer, an interface, an antenna or a plate.",
    "screen.qbotics.probe": "Network Probe",
    "screen.qbotics.probe.title": "This is: %s",
    "screen.qbotics.probe.hint": "Click a line to copy it, then paste it into your program with Ctrl+V",
    "screen.qbotics.probe.name": "Name (letters, digits, _ - .)",
    "screen.qbotics.probe.set_name": "Set name",
    "screen.qbotics.probe.python": "Python:  %s",
    "screen.qbotics.probe.name_row": "Name:  %s",
    "screen.qbotics.probe.ip": "Internal IP:  %s",
    "screen.qbotics.probe.mac": "Hardware address:  %s",
    "screen.qbotics.probe.external": "External address:  %s",
    "screen.qbotics.probe.copied": "Copied:  %s",
    "message.qbotics.plate_taken": "That side of the cable already carries a plate.",
    "message.qbotics.plate_needs_cable": "A plate goes on a cable: you need a cable in your inventory.",
    "block.qbotics.antenna_short": "Short-Range Antenna",
    "block.qbotics.antenna_long": "Long-Range Antenna",
    "block.qbotics.antenna_infinite": "Infinite Antenna",
    "message.qbotics.antenna": "%s  |  external %s  |  frequency %s  |  reach %s  |  %s",
    "container.qbotics.interface": "Filters / Items   %s",
    "message.qbotics.port": "%s, pointing at %s",
    "screen.qbotics.terminal": "Computer Terminal",
})

# ---- cable: texture, connecting model ------------------------------------------

SHEATH = (204, 204, 204)
SHEATH_LIGHT = (232, 232, 232)
SHEATH_DARK = (156, 156, 158)
CORE_RED = RED


def plain(colour):
    return [[colour for _ in range(16)] for _ in range(16)]


write_png("assets/qbotics/textures/block/cable.png", plain(SHEATH))
# Redstone red, a little lifted. Its glow (CORE_GLOW) is what shows in the dark; this is what shows by day.
CORE_COLOUR = (222, 46, 34)
write_png("assets/qbotics/textures/block/cable_core.png", plain(CORE_COLOUR))

CABLE_TEXTURES = {"all": "qbotics:block/cable", "core": "qbotics:block/cable_core", "particle": "qbotics:block/cable"}
# How brightly the red core glows by itself, 0 to 15. Redstone is lit but not a lamp.
CORE_GLOW = 5
# How far the core stands out of the sheath, in pixels: just enough to be the surface that is seen.
PROUD = 0.02


def faces(*names, texture="#all"):
    return {name: {"texture": texture} for name in names}


def glow(x0, y0, z0, x1, y1, z1, *shown):
    """A piece of the red core: it glows, and only the listed faces are drawn."""
    return {"from": [x0, y0, z0], "to": [x1, y1, z1], "light_emission": CORE_GLOW, "shade": False,
            "faces": faces(*shown, texture="#core")}


LOW, HIGH = 6 - PROUD, 10 + PROUD


def stripes(z0, z1):
    """The core along a length of cable that runs along z: a red line down the middle two
    pixels of each of its four faces, leaving the sheath's corners grey."""
    return [glow(7, LOW, z0, 9, HIGH, z1, "up", "down"), glow(LOW, 7, z0, HIGH, 9, z1, "east", "west")]


# The centre piece, always shown: grey, with the core showing as a red square in the middle
# of each face. An arm on a side covers that side's square and runs its own red lines across.
write_json("assets/qbotics/models/block/cable_core.json", {
    "parent": "minecraft:block/block",
    "textures": CABLE_TEXTURES,
    "elements": [
        {"from": [6, 6, 6], "to": [10, 10, 10], "faces": faces("north", "south", "east", "west", "up", "down")},
        glow(LOW, 7, 7, HIGH, 9, 9, "east", "west"),
        glow(7, LOW, 7, 9, HIGH, 9, "up", "down"),
        glow(7, 7, LOW, 9, 9, HIGH, "north", "south"),
    ],
})
# An arm from the centre to the north face; the block state rotates it for other sides. Its
# red lines carry on across the centre piece (to z = 9), so a straight cable shows unbroken
# lines and a corner shows them meeting.
write_json("assets/qbotics/models/block/cable_side.json", {
    "parent": "minecraft:block/block",
    "textures": CABLE_TEXTURES,
    "elements": [{"from": [6, 6, 0], "to": [10, 10, 6], "faces": faces("east", "west", "up", "down")}] + stripes(0, 9),
})
# In the hand and inventory: a straight length of cable.
write_json("assets/qbotics/models/item/cable.json", {
    "parent": "minecraft:block/block",
    "textures": CABLE_TEXTURES,
    "elements": [{"from": [6, 6, 0], "to": [10, 10, 16], "faces": faces("north", "south", "east", "west", "up", "down")}]
                + stripes(0, 16) + [glow(7, 7, -PROUD, 9, 9, 16 + PROUD, "north", "south")],
})
write_json("assets/qbotics/items/cable.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:item/cable"},
})


def arm(side, **rotation):
    apply = {"model": "qbotics:block/cable_side"}
    apply.update(rotation)
    return {"when": {side: "true"}, "apply": apply}


# (The cable's block state is written further down, after the plates' models.)

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

# Computer: cable in the shape of leggings, a motherboard in the middle, a screen at the bottom middle.
write_json("data/qbotics/recipe/computer.json", shaped(
    ["CCC", "CMC", "CSC"],
    {"C": "qbotics:cable", "M": "qbotics:motherboard", "S": "qbotics:screen"},
    "qbotics:computer"))

# ---- parts: the screen and the motherboard --------------------------------------------


def screen_item():
    """A small display: an iron frame around dark glass lit in red, green and blue."""
    px = [[None] * 16 for _ in range(16)]
    for y in range(3, 13):
        for x in range(1, 15):
            if x in (1, 14) or y in (3, 12):
                px[y][x] = CASE_DARK if (x == 14 or y == 12) else CASE_LIGHT
            else:
                glow = ((230, 60, 50), (80, 210, 90), (70, 110, 235))[(x - 2) % 3]
                px[y][x] = glow if y in (5, 6, 8, 9) and 3 <= x <= 12 else SCREEN
    for x in range(6, 10):
        px[13][x] = EDGE     # a short stand
    return px


def motherboard_item():
    """A green board with copper tracks, a chip in the middle and glass at two corners."""
    board, track, chip, glass = (38, 120, 62), (214, 132, 82), (30, 30, 34), (190, 226, 236)
    px = [[None] * 16 for _ in range(16)]
    for y in range(2, 14):
        for x in range(2, 14):
            px[y][x] = (26, 88, 46) if x in (2, 13) or y in (2, 13) else board
    for i in range(3, 13):
        px[4][i] = track
        px[11][i] = track
    for y in range(4, 12):
        px[y][4] = track
        px[y][11] = track
    for y in range(6, 10):
        for x in range(6, 10):
            px[y][x] = chip
    for (x, y) in ((7, 5), (8, 10), (5, 7), (10, 8)):
        px[y][x] = track
    for (x, y) in ((3, 3), (12, 12)):
        px[y][x] = glass
    return px


for part, picture in (("screen", screen_item()), ("motherboard", motherboard_item())):
    write_png("assets/qbotics/textures/item/%s.png" % part, picture)
    write_json("assets/qbotics/models/item/%s.json" % part, {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "qbotics:item/%s" % part},
    })
    write_json("assets/qbotics/items/%s.json" % part, {
        "model": {"type": "minecraft:model", "model": "qbotics:item/%s" % part},
    })
# Screen: lines of glowstone dust, dye and glass pane, standing side by side. Makes four.
write_json("data/qbotics/recipe/screen.json", shaped(
    ["GRP", "GEP", "GBP"],
    {"G": "minecraft:glowstone_dust", "R": "minecraft:red_dye", "E": "minecraft:green_dye", "B": "minecraft:blue_dye",
     "P": "minecraft:glass_pane"},
    "qbotics:screen", 4))
# Motherboard: copper above and below glass, green dye, glass.
write_json("data/qbotics/recipe/motherboard.json", shaped(
    ["CCC", "LGL", "CCC"],
    {"C": "minecraft:copper_ingot", "L": "minecraft:glass", "G": "minecraft:green_dye"},
    "qbotics:motherboard"))

# A broken computer keeps its drive (its files).
write_json("data/qbotics/loot_table/blocks/computer.json",
           drops_itself("qbotics:computer", copy_components=["qbotics:drive_id", "minecraft:custom_name"]))
write_json("data/qbotics/loot_table/blocks/cable.json", drops_itself("qbotics:cable"))

write_json("data/minecraft/tags/block/mineable/pickaxe.json", {
    "replace": False,
    "values": ["qbotics:computer", "qbotics:cable", "qbotics:interface", "qbotics:fluid_interface", "qbotics:energy_interface", "qbotics:experience_interface",
               "qbotics:antenna_short", "qbotics:antenna_long", "qbotics:antenna_infinite", "qbotics:drive_bay", "qbotics:programmer",
               "qbotics:text_monitor", "qbotics:control_panel", "qbotics:iron_qbot"],
})
# What a cable reaches out to.
write_json("data/qbotics/tags/block/cable_connectable.json", {
    "replace": False,
    "values": ["qbotics:cable", "qbotics:computer", "qbotics:interface", "qbotics:fluid_interface", "qbotics:energy_interface", "qbotics:experience_interface",
               "qbotics:antenna_short", "qbotics:antenna_long", "qbotics:antenna_infinite", "qbotics:drive_bay", "qbotics:programmer",
               "qbotics:text_monitor", "qbotics:control_panel", "qbotics:iron_qbot"],
})
# ---- the interface (a full block) and the plates that go on cables (design doc §11.0) ----



def interface_side():
    """Nine small slots, like the block's nine filtered slots."""
    px = case_base()
    for gy in range(3):
        for gx in range(3):
            for y in range(3):
                for x in range(3):
                    px[3 + gy * 4 + y][3 + gx * 4 + x] = VENT if (x, y) != (1, 1) else SCREEN
    return px


def interface_top():
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
    """Plain iron. A plate is built from small boxes, each showing only a corner of this
    picture, so any line or border drawn here turns up as stray marks."""
    return [[CASE for _ in range(16)] for _ in range(16)]


def port_front():
    """The face that touches the target: a dark opening in an iron frame."""
    px = case_base()
    for y in range(3, 13):
        for x in range(3, 13):
            px[y][x] = EDGE if x in (3, 12) or y in (3, 12) else SCREEN
    return px


# What a plate or store carries, and the colour that says so. (PlateTints.java holds the same colours.)
MEDIA = {"": (246, 246, 246), "fluid_": (96, 128, 168), "energy_": (200, 36, 28)}
# A Universal Serial Bus carries all three, and is gold.
USB_GOLD = (224, 168, 44)
XP_GREEN = (128, 224, 64)


def tint_value(colour):
    """A colour as the game's item files want it: one signed 32-bit number, fully opaque."""
    value = (255 << 24) | (colour[0] << 16) | (colour[1] << 8) | colour[2]
    return value - (1 << 32)


def store_side(colour, cells):
    """A store's side: iron, with windows showing what is inside."""
    px = case_base()
    dark = tuple(c * 2 // 3 for c in colour)
    for (x0, y0, x1, y1) in cells:
        for y in range(y0, y1):
            for x in range(x0, x1):
                edge = x in (x0, x1 - 1) or y in (y0, y1 - 1)
                px[y][x] = EDGE if edge else (colour if y >= (y0 + y1) // 2 else dark)
    return px


def store_top(colour):
    px = case_base()
    for y in range(4, 12):
        for x in range(4, 12):
            px[y][x] = EDGE if x in (4, 11) or y in (4, 11) else colour
    return px


write_png("assets/qbotics/textures/block/interface_side.png", interface_side())
write_png("assets/qbotics/textures/block/interface_top.png", interface_top())
write_png("assets/qbotics/textures/block/port_side.png", port_side())
write_png("assets/qbotics/textures/block/plate_front.png", port_front())


def pin_front():
    """A Logic Pin's face: a redstone-red contact in an iron frame."""
    px = port_front()
    for y in range(6, 10):
        for x in range(6, 10):
            px[y][x] = RED_BRIGHT if (x, y) in ((6, 6), (7, 6), (6, 7)) else RED
    return px


def placer_front():
    """A Placer's face: an open mouth with a lighter rim, where blocks come out."""
    px = port_front()
    for y in range(4, 12):
        for x in range(4, 12):
            if x in (4, 11) or y in (4, 11):
                px[y][x] = CASE_LIGHT
    return px


def breaker_front():
    """A Breaker's face: a dark opening crossed by grinding teeth."""
    px = port_front()
    for y in range(4, 12):
        for x in range(4, 12):
            if (x + y) % 4 == 0:
                px[y][x] = CASE_DARK
    return px


write_png("assets/qbotics/textures/block/pin_front.png", pin_front())
write_png("assets/qbotics/textures/block/placer_front.png", placer_front())
write_png("assets/qbotics/textures/block/breaker_front.png", breaker_front())
write_png("assets/qbotics/textures/block/plate_ring.png", [[(255, 255, 255)] * 16 for _ in range(16)])
# The fluid store shows four tanks; the energy store shows one tall cell.
write_png("assets/qbotics/textures/block/fluid_interface_side.png",
          store_side(MEDIA["fluid_"], [(2, 2, 8, 8), (8, 2, 14, 8), (2, 8, 8, 14), (8, 8, 14, 14)]))
write_png("assets/qbotics/textures/block/fluid_interface_top.png", store_top(MEDIA["fluid_"]))
write_png("assets/qbotics/textures/block/energy_interface_side.png",
          store_side(MEDIA["energy_"], [(3, 2, 6, 14), (6, 2, 10, 14), (10, 2, 13, 14)]))
write_png("assets/qbotics/textures/block/energy_interface_top.png", store_top(MEDIA["energy_"]))
# The experience store shows one round bottle of green.
write_png("assets/qbotics/textures/block/experience_interface_side.png",
          store_side(XP_GREEN, [(5, 2, 11, 5), (3, 5, 13, 14)]))
write_png("assets/qbotics/textures/block/experience_interface_top.png", store_top(XP_GREEN))
write_json("data/qbotics/recipe/experience_interface.json", shaped(
    ["CCC", "CBC", "CCC"], {"C": "qbotics:cable", "B": "minecraft:glass_bottle"}, "qbotics:experience_interface"))
for store in ("fluid_interface", "energy_interface", "experience_interface"):
    write_json("assets/qbotics/models/block/%s.json" % store, {
        "parent": "minecraft:block/cube_column",
        "textures": {"end": "qbotics:block/%s_top" % store, "side": "qbotics:block/%s_side" % store},
    })
    write_json("assets/qbotics/blockstates/%s.json" % store, {"variants": {"": {"model": "qbotics:block/%s" % store}}})
    write_json("assets/qbotics/items/%s.json" % store, {
        "model": {"type": "minecraft:model", "model": "qbotics:block/%s" % store},
    })
    # A store named in an anvil keeps its name when broken; the experience store keeps its experience too.
    write_json("data/qbotics/loot_table/blocks/%s.json" % store,
               drops_itself("qbotics:%s" % store, copy_components=["minecraft:custom_name", "qbotics:xp_points"]
                            if store == "experience_interface" else ["minecraft:custom_name"]))

write_json("assets/qbotics/models/block/interface.json", {
    "parent": "minecraft:block/cube_column",
    "textures": {"end": "qbotics:block/interface_top", "side": "qbotics:block/interface_side"},
})
write_json("assets/qbotics/blockstates/interface.json", {"variants": {"": {"model": "qbotics:block/interface"}}})


# ---- hard drives and the drive bay (design doc §17.3, §17.6) ----

def drive_bay_front():
    """Four drive sockets stacked down the face, each with a small green light."""
    px = case_base()
    for socket in range(4):
        top = 2 + socket * 3
        for y in range(top, top + 2):
            for x in range(3, 13):
                px[y][x] = EDGE if y == top else SCREEN
        px[top + 1][11] = GREEN
    return px


def hard_drive():
    """A hard drive, as an item: a grey box with a label strip and a connector."""
    px = [[None] * 16 for _ in range(16)]
    for y in range(3, 13):
        for x in range(2, 14):
            edge = x in (2, 13) or y in (3, 12)
            px[y][x] = CASE_DARK if edge else CASE
    for x in range(4, 12):          # the label
        px[5][x] = CASE_LIGHT
        px[6][x] = CASE_LIGHT
    for x in range(4, 12, 2):       # the connector's pins
        px[11][x] = EDGE
    px[9][11] = GREEN               # its light
    return px


write_png("assets/qbotics/textures/block/drive_bay_front.png", drive_bay_front())


def programmer_front():
    """The Programmer's programming face: a contact pad of pins in a frame, and a light."""
    px = case_base()
    for y in range(3, 13):
        for x in range(3, 13):
            px[y][x] = EDGE if x in (3, 12) or y in (3, 12) else SCREEN
    for y in range(5, 11, 2):
        for x in range(5, 11, 2):
            px[y][x] = CASE_LIGHT
    px[13][7] = px[13][8] = RED_BRIGHT
    return px


def programmer_side():
    """Plain case, with a drive slot across it."""
    px = case_base()
    for x in range(4, 12):
        px[7][x] = EDGE
        px[8][x] = SCREEN
    return px


write_png("assets/qbotics/textures/block/programmer_front.png", programmer_front())
write_png("assets/qbotics/textures/block/programmer_side.png", programmer_side())
write_json("assets/qbotics/models/block/programmer.json", {
    "parent": "minecraft:block/cube",
    "textures": {
        "particle": "qbotics:block/programmer_side",
        "north": "qbotics:block/programmer_front",
        "south": "qbotics:block/programmer_side", "east": "qbotics:block/programmer_side",
        "west": "qbotics:block/programmer_side", "up": "qbotics:block/interface_top", "down": "qbotics:block/interface_top",
    },
})
# Its programming face (north in the model) turns to face the way the block does, as an observer's.
PROGRAMMER_TURNS = {"north": {}, "south": {"y": 180}, "east": {"y": 90}, "west": {"y": 270}, "up": {"x": 270}, "down": {"x": 90}}
write_json("assets/qbotics/blockstates/programmer.json", {"variants": {
    "facing=%s" % facing: dict({"model": "qbotics:block/programmer"}, **turn) for facing, turn in PROGRAMMER_TURNS.items()}})
write_json("assets/qbotics/items/programmer.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:block/programmer"},
})
write_png("assets/qbotics/textures/item/hard_drive.png", hard_drive())


# ---- the Iron Qbot (design doc §8): a loose, boxy little golem in white-ish iron, hovering ----
QB_IRON = (226, 228, 231)
QB_SHADE = (204, 207, 212)
QB_EDGE = (156, 160, 167)
QB_DARK = (72, 76, 84)
QB_EYE = (120, 220, 255)
QB_GLOW = (150, 205, 255)


def qbot_plate():
    """Iron plating: light, with a faint panel line and rivets at the corners."""
    px = [[QB_IRON for _ in range(16)] for _ in range(16)]
    for i in range(16):
        px[0][i] = px[15][i] = QB_SHADE
        px[i][0] = px[i][15] = QB_SHADE
    for y in (7,):
        for x in range(2, 14):
            px[y][x] = QB_SHADE
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px[y][x] = QB_EDGE
    return px


def qbot_face():
    """The head's front: iron, with a dark visor band and two bright eyes (in rows 3 to 8, columns 4 to 12)."""
    px = qbot_plate()
    for y in range(4, 7):
        for x in range(5, 11):
            px[y][x] = QB_DARK
    for (x, y) in ((6, 5), (9, 5)):
        px[y][x] = QB_EYE
    px[4][6] = px[4][9] = (190, 240, 255)
    return px


def qbot_dark():
    """Joints and the hover base: dark metal."""
    px = [[QB_DARK for _ in range(16)] for _ in range(16)]
    for i in range(0, 16, 4):
        for j in range(16):
            px[j][i] = (60, 63, 70)
    return px


def qbot_glow():
    """The underside of its hover base: a soft blue glow."""
    px = [[QB_GLOW for _ in range(16)] for _ in range(16)]
    for i in range(16):
        px[0][i] = px[15][i] = px[i][0] = px[i][15] = (110, 170, 230)
    return px


write_png("assets/qbotics/textures/block/qbot_iron.png", qbot_plate())
write_png("assets/qbotics/textures/block/qbot_iron_face.png", qbot_face())
write_png("assets/qbotics/textures/block/qbot_dark.png", qbot_dark())
write_png("assets/qbotics/textures/block/qbot_glow.png", qbot_glow())


def box(x0, y0, z0, x1, y1, z1, texture="#iron", north=None, down=None, emissive=False):
    """A model element with every face textured; north and down may differ."""
    def face(name, tex):
        f = {"texture": tex, "uv": uv(name)}
        return f

    def uv(name):
        if name in ("north", "south"):
            return [x0, 16 - y1, x1, 16 - y0]
        if name in ("east", "west"):
            return [z0, 16 - y1, z1, 16 - y0]
        return [x0, z0, x1, z1]

    faces = {name: face(name, texture) for name in ("north", "south", "east", "west", "up", "down")}
    if north:
        faces["north"] = {"texture": north, "uv": [4, 3, 12, 8]}
    if down:
        faces["down"] = {"texture": down, "uv": [0, 0, 16, 16]}
    element = {"from": [x0, y0, z0], "to": [x1, y1, z1], "faces": faces}
    if emissive:
        element["light_emission"] = 12
    return element


QBOT_TEXTURES = {"particle": "qbotics:block/qbot_iron", "iron": "qbotics:block/qbot_iron", "face": "qbotics:block/qbot_iron_face",
                 "dark": "qbotics:block/qbot_dark", "glow": "qbotics:block/qbot_glow"}
BODY_ELEMENTS = [
        box(4, 0, 4, 12, 3, 12, "#dark", down="#glow", emissive=True),   # hover base
        box(3, 3, 3, 13, 10, 13),                                        # torso
        box(5, 10, 5, 11, 10.5, 11, "#dark"),                            # neck
        box(4, 10.5, 4, 12, 15.5, 12, north="#face"),                    # head
        box(7.5, 15.5, 7.5, 8.5, 17, 8.5, "#dark"),                      # antenna
        box(7.25, 17, 7.25, 8.75, 17.75, 8.75, "#glow", emissive=True),  # its light
]
write_json("assets/qbotics/models/block/iron_qbot_body.json", {"textures": QBOT_TEXTURES, "elements": BODY_ELEMENTS})
def qbot_arm(dx):
    return [box(dx, 4, 7, dx + 2, 10.5, 9), box(dx, 3, 6.5, dx + 2, 4, 9.5, "#dark")]


write_json("assets/qbotics/models/block/iron_qbot_arm.json", {"textures": QBOT_TEXTURES, "elements": qbot_arm(0)})
QBOT_TURNS = {"north": 0, "east": 90, "south": 180, "west": 270}
write_json("assets/qbotics/blockstates/iron_qbot.json", {"variants": {
    "active=%s,facing=%s" % (active, facing): dict({"model": "qbotics:block/iron_qbot_body"}, **({"y": turn} if turn else {}))
    for facing, turn in QBOT_TURNS.items() for active in ("true", "false")}})
write_json("assets/qbotics/blockstates/qbot_part.json", {"variants": {
    "part=iron_body": {"model": "qbotics:block/iron_qbot_body"},
    "part=iron_arm": {"model": "qbotics:block/iron_qbot_arm"},
}})
write_json("assets/qbotics/items/iron_qbot.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:item/iron_qbot"},
})
# In the hand and inventory: the body with both arms, turned to show its face.
write_json("assets/qbotics/models/item/iron_qbot.json", {
    "parent": "minecraft:block/block",
    "textures": QBOT_TEXTURES,
    "elements": BODY_ELEMENTS + qbot_arm(0.5) + qbot_arm(13.5),
})


def upgrade_card(mark):
    """An upgrade: a circuit card with gold contacts, and its mark in the middle."""
    px = [[None] * 16 for _ in range(16)]
    board = (40, 92, 60)
    board_edge = (24, 60, 38)
    for y in range(2, 14):
        for x in range(2, 14):
            px[y][x] = board_edge if x in (2, 13) or y in (2, 13) else board
    for x in range(4, 12, 2):               # gold contacts along the bottom
        px[13][x] = (230, 186, 60)
        px[12][x] = (230, 186, 60)
    for (x, y), colour in mark.items():
        px[y][x] = colour
    return px


ARROW = (120, 200, 255)
TRANSPORT_MARK = {}
for x in range(4, 11):                      # an arrow to the right, and one back
    TRANSPORT_MARK[(x, 5)] = ARROW
    TRANSPORT_MARK[(x + 1, 9)] = (255, 160, 80)
for d in (1, 2):
    TRANSPORT_MARK[(10 - d, 5 - d)] = ARROW
    TRANSPORT_MARK[(10 - d, 5 + d)] = ARROW
    TRANSPORT_MARK[(5 + d, 9 - d)] = (255, 160, 80)
    TRANSPORT_MARK[(5 + d, 9 + d)] = (255, 160, 80)
TRANSPORT_MARK[(5, 9)] = (255, 160, 80)
CHUNK_MARK = {}
for y in range(4, 11):                      # a square of chunks, the middle one lit
    for x in range(4, 11):
        if x in (4, 7, 10) or y in (4, 7, 10):
            CHUNK_MARK[(x, y)] = (180, 180, 190)
for (x, y) in ((5, 5), (6, 5), (5, 6), (6, 6)):
    CHUNK_MARK[(x + 3, y + 3)] = (90, 230, 120)
write_png("assets/qbotics/textures/item/transport_upgrade.png", upgrade_card(TRANSPORT_MARK))
write_png("assets/qbotics/textures/item/chunk_loader_upgrade.png", upgrade_card(CHUNK_MARK))
for upgrade in ("transport_upgrade", "chunk_loader_upgrade"):
    write_json("assets/qbotics/models/item/%s.json" % upgrade, {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": "qbotics:item/%s" % upgrade},
    })
    write_json("assets/qbotics/items/%s.json" % upgrade, {
        "model": {"type": "minecraft:model", "model": "qbotics:item/%s" % upgrade},
    })


# ---- displays (design doc §10): a dark face that the game draws the screen onto ----
PANEL_FACE = (27, 31, 36)


def display_front(inside):
    """A screen to the edges, with a one-pixel frame: side by side, they read as one screen."""
    px = [[inside for _ in range(16)] for _ in range(16)]
    for i in range(16):
        px[0][i] = px[15][i] = px[i][0] = px[i][15] = SCREEN_EDGE
    return px


write_png("assets/qbotics/textures/block/text_monitor_front.png", display_front(SCREEN))
write_png("assets/qbotics/textures/block/control_panel_front.png", display_front(PANEL_FACE))
write_png("assets/qbotics/textures/block/display_side.png", case_base())
# The screen is north in the model, turned to face the way the block does.
DISPLAY_TURNS = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}}
for display in ("text_monitor", "control_panel"):
    write_json("assets/qbotics/models/block/%s.json" % display, {
        "parent": "minecraft:block/cube",
        "textures": {
            "particle": "qbotics:block/display_side",
            "north": "qbotics:block/%s_front" % display,
            "south": "qbotics:block/display_side", "east": "qbotics:block/display_side",
            "west": "qbotics:block/display_side", "up": "qbotics:block/display_side", "down": "qbotics:block/display_side",
        },
    })
    write_json("assets/qbotics/blockstates/%s.json" % display, {"variants": {
        "facing=%s" % facing: dict({"model": "qbotics:block/%s" % display}, **turn) for facing, turn in DISPLAY_TURNS.items()}})
    write_json("assets/qbotics/items/%s.json" % display, {
        "model": {"type": "minecraft:model", "model": "qbotics:block/%s" % display},
    })
write_json("assets/qbotics/models/block/drive_bay.json", {
    "parent": "minecraft:block/cube_column",
    "textures": {"end": "qbotics:block/interface_top", "side": "qbotics:block/drive_bay_front"},
})
write_json("assets/qbotics/blockstates/drive_bay.json", {"variants": {"": {"model": "qbotics:block/drive_bay"}}})
write_json("assets/qbotics/items/drive_bay.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:block/drive_bay"},
})
write_json("assets/qbotics/models/item/hard_drive.json", {
    "parent": "minecraft:item/generated",
    "textures": {"layer0": "qbotics:item/hard_drive"},
})
write_json("assets/qbotics/items/hard_drive.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:item/hard_drive"},
})
write_json("assets/qbotics/items/interface.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:block/interface"},
})

# Each plate is three stacked boxes between the cable (at z = 6) and the block it points at
# (at z = 0), drawn pointing north. A Serial Bus is a flat panel, as buses are in other
# logistics mods. The middle box is a collar in the colour of what the plate carries (white
# items, blue-grey fluid, red energy, gold for a USB), and the face has a ring in the same colour.
PLATE_BOXES = {
    "bus": [(2, 2, 0, 14, 14, 2), (4, 4, 2, 12, 12, 4), (6, 6, 4, 10, 10, 6)],
    # The world plates (design doc §11.0.1): a slim pin, a deep placer, a heavy breaker.
    "pin": [(5, 5, 0, 11, 11, 1), (6, 6, 1, 10, 10, 3), (7, 7, 3, 9, 9, 6)],
    "placer": [(3, 3, 0, 13, 13, 3), (5, 5, 3, 11, 11, 5), (6, 6, 5, 10, 10, 6)],
    "breaker": [(2, 2, 0, 14, 14, 1), (3, 3, 1, 13, 13, 4), (6, 6, 4, 10, 10, 6)],
}
# Plates that act on the world have one version, with a face of their own; the bus comes
# in item, fluid, energy and universal versions and shares a face.
WORLD_PLATES = ("pin", "placer", "breaker")
PLATE_TURNS = {"north": {}, "east": {"y": 90}, "south": {"y": 180}, "west": {"y": 270}, "up": {"x": 270}, "down": {"x": 90}}
# The game numbers the six sides like this. A plate on a side takes its colour from the
# colour source with that side's number (see PlateTints.java), so each side needs its own model.
SIDE_NUMBERS = {"down": 0, "up": 1, "north": 2, "south": 3, "west": 4, "east": 5}
ALL_FACES = ("north", "south", "east", "west", "up", "down")


def plate_elements(boxes, tint):
    front, collar, stem = boxes
    coloured = {"texture": "#ring", "tintindex": tint}
    elements = [
        {"from": list(front[:3]), "to": list(front[3:]),
         "faces": dict({side: {"texture": "#side"} for side in ALL_FACES}, north={"texture": "#front"})},
        {"from": list(collar[:3]), "to": list(collar[3:]), "faces": {side: dict(coloured) for side in ALL_FACES}},
        {"from": list(stem[:3]), "to": list(stem[3:]), "faces": {side: {"texture": "#side"} for side in ALL_FACES}},
    ]
    # The ring on the face: four thin bars standing a hair proud of it, set in from its edge.
    x0, y0, _, x1, y1, _ = front
    inset = 2 if x1 - x0 >= 8 else 1
    a, b, c, d = x0 + inset, y0 + inset, x1 - inset, y1 - inset
    bars = [(a, b, c, b + 1), (a, d - 1, c, d), (a, b, a + 1, d), (c - 1, b, c, d)]
    for (bx0, by0, bx1, by1) in bars:
        elements.append({"from": [bx0, by0, -PROUD], "to": [bx1, by1, 0.5], "faces": {"north": dict(coloured)}})
    return elements


cable_parts = [
    {"apply": {"model": "qbotics:block/cable_core"}},
    arm("north"),
    arm("east", y=90),
    arm("south", y=180),
    arm("west", y=270),
    arm("up", x=270),
    arm("down", x=90),
]
for plate, boxes in PLATE_BOXES.items():
    for side, number in SIDE_NUMBERS.items():
        write_json("assets/qbotics/models/block/plate_%s_%s.json" % (plate, side), {
            "parent": "minecraft:block/block",
            "textures": {
                "particle": "qbotics:block/port_side",
                "side": "qbotics:block/port_side",
                "front": "qbotics:block/%s_front" % plate if plate in WORLD_PLATES else "qbotics:block/plate_front",
                "ring": "qbotics:block/plate_ring",
            },
            "elements": plate_elements(boxes, number),
        })
        # Not in the cable's block state: plates are kept by the cable's block entity, and the
        # model built in code (CableModels.java) adds each one, turned by PLATE_TURNS[side].
    # In the hand and inventory the plate is shown on its own, in its medium's colour. It uses
    # the "down" model, whose colour is number 0: the first (and only) tint an item lists.
    versions = {"": MEDIA[""]} if plate in WORLD_PLATES else dict(MEDIA, usb=USB_GOLD)
    for prefix, colour in versions.items():
        write_json("assets/qbotics/items/%s.json" % ("usb" if prefix == "usb" else prefix + plate), {
            "model": {"type": "minecraft:model", "model": "qbotics:block/plate_%s_down" % plate,
                      "tints": [{"type": "minecraft:constant", "value": tint_value(colour)}]},
        })
# The cable's block state: its core and arms only.
write_json("assets/qbotics/blockstates/cable.json", {"multipart": cable_parts})

# The Network Probe: a small hand-held reader, drawn from two boxes.
write_json("assets/qbotics/models/item/probe.json", {
    "parent": "minecraft:block/block",
    "textures": {"particle": "qbotics:block/port_side", "side": "qbotics:block/port_side", "front": "qbotics:block/plate_front"},
    "elements": [
        {"from": [6, 0, 7], "to": [10, 9, 9],
         "faces": {side: {"texture": "#side"} for side in ALL_FACES}},
        {"from": [3, 9, 6], "to": [13, 16, 10],
         "faces": dict({side: {"texture": "#side"} for side in ("south", "east", "west", "up", "down")},
                       north={"texture": "#front"})},
    ],
})
write_json("assets/qbotics/items/probe.json", {
    "model": {"type": "minecraft:model", "model": "qbotics:item/probe"},
})
write_json("data/qbotics/recipe/probe.json", shaped(
    ["R", "C", "I"], {"R": "minecraft:redstone", "C": "qbotics:cable", "I": "minecraft:iron_ingot"}, "qbotics:probe"))


def shapeless(ingredients, result):
    return {"type": "minecraft:crafting_shapeless", "category": "redstone", "ingredients": ingredients, "result": {"id": result}}


# Recipes (design doc §20). The interface: four cables around a chest.
write_json("data/qbotics/recipe/interface.json", shaped(
    [" C ", "CHC", " C "], {"C": "qbotics:cable", "H": "minecraft:chest"}, "qbotics:interface"))
# The Serial Buses: cables in an H, a hopper (takes out) on the left of the bar and, on the
# right, a dropper (puts in) for items, a bucket for fluid, a block of redstone for energy.
# The Universal Serial Bus is the three together.
for prefix, right in (("", "minecraft:dropper"), ("fluid_", "minecraft:bucket"), ("energy_", "minecraft:redstone_block")):
    write_json("data/qbotics/recipe/%sbus.json" % prefix, shaped(
        ["C C", "HCR", "C C"], {"C": "qbotics:cable", "H": "minecraft:hopper", "R": right}, "qbotics:%sbus" % prefix))
write_json("data/qbotics/recipe/usb.json", shapeless(["qbotics:bus", "qbotics:fluid_bus", "qbotics:energy_bus"], "qbotics:usb"))
# The world plates, the same way: an observer for the pin that watches, a dispenser for the
# placer, and for the breaker a diamond pickaxe, to match what it can break.
for name, heart in (("pin", "minecraft:observer"), ("placer", "minecraft:dispenser"), ("breaker", "minecraft:diamond_pickaxe")):
    write_json("data/qbotics/recipe/%s.json" % name, shaped(
        [" C ", "CHC", " C "], {"C": "qbotics:cable", "H": heart}, "qbotics:%s" % name))
# The fluid interface is the interface and a bucket; the energy interface, the interface and a
# block of redstone.
for prefix, extra in (("fluid_", "minecraft:bucket"), ("energy_", "minecraft:redstone_block")):
    write_json("data/qbotics/recipe/%sinterface.json" % prefix, shapeless(["qbotics:interface", extra], "qbotics:%sinterface" % prefix))
# A device named in an anvil keeps its name when broken.
# Placeholder recipes: a hard drive is iron around redstone with a gold contact; a drive bay is
# cables around a barrel, like the other item blocks.
write_json("data/qbotics/recipe/hard_drive.json", shaped(
    ["III", "IRI", "IGI"], {"I": "minecraft:iron_ingot", "R": "minecraft:redstone", "G": "minecraft:gold_ingot"}, "qbotics:hard_drive"))
write_json("data/qbotics/recipe/drive_bay.json", shaped(
    [" C ", "CBC", " C "], {"C": "qbotics:cable", "B": "minecraft:barrel"}, "qbotics:drive_bay"))
# Placeholder: a Programmer is cables around a motherboard, a computer's heart without its screen.
write_json("data/qbotics/recipe/programmer.json", shaped(
    [" C ", "CMC", " C "], {"C": "qbotics:cable", "M": "qbotics:motherboard"}, "qbotics:programmer"))
write_json("data/qbotics/loot_table/blocks/programmer.json",
           drops_itself("qbotics:programmer", copy_components=["minecraft:custom_name"]))
# Placeholder: an Iron Qbot is a computer in iron, over a chest (what it carries).
write_json("data/qbotics/recipe/iron_qbot.json", shaped(
    ["III", "ICI", "IHI"], {"I": "minecraft:iron_ingot", "C": "qbotics:computer", "H": "minecraft:chest"}, "qbotics:iron_qbot"))
# A broken Qbot keeps its drive (files), name, and its energy and fluid (custom data); its items fall out.
write_json("data/qbotics/loot_table/blocks/iron_qbot.json",
           drops_itself("qbotics:iron_qbot", copy_components=["qbotics:drive_id", "minecraft:custom_name", "minecraft:custom_data"]))
# Placeholders: upgrades are a motherboard with what they do. Transport: ender pearls (things cross a
# distance) and a hopper; Chunk Loader: an eye of ender over obsidian.
write_json("data/qbotics/recipe/transport_upgrade.json", shaped(
    ["EHE", "RMR", "GGG"], {"E": "minecraft:ender_pearl", "H": "minecraft:hopper", "R": "minecraft:redstone",
                            "M": "qbotics:motherboard", "G": "minecraft:gold_nugget"}, "qbotics:transport_upgrade"))
write_json("data/qbotics/recipe/chunk_loader_upgrade.json", shaped(
    ["OYO", "RMR", "GGG"], {"O": "minecraft:obsidian", "Y": "minecraft:ender_eye", "R": "minecraft:redstone",
                            "M": "qbotics:motherboard", "G": "minecraft:gold_nugget"}, "qbotics:chunk_loader_upgrade"))
# Placeholders: a Text Monitor is a screen in iron, over a cable; a Control Panel adds a button and a lever.
write_json("data/qbotics/recipe/text_monitor.json", shaped(
    ["III", "ISI", "ICI"], {"I": "minecraft:iron_ingot", "S": "qbotics:screen", "C": "qbotics:cable"}, "qbotics:text_monitor", 2))
write_json("data/qbotics/recipe/control_panel.json", shaped(
    ["BLB", "ISI", "ICI"], {"B": "minecraft:stone_button", "L": "minecraft:lever", "I": "minecraft:iron_ingot",
                            "S": "qbotics:screen", "C": "qbotics:cable"}, "qbotics:control_panel", 2))
for display in ("text_monitor", "control_panel"):
    write_json("data/qbotics/loot_table/blocks/%s.json" % display,
               drops_itself("qbotics:%s" % display, copy_components=["minecraft:custom_name"]))
write_json("data/qbotics/loot_table/blocks/drive_bay.json",
           drops_itself("qbotics:drive_bay", copy_components=["minecraft:custom_name"]))
write_json("data/qbotics/loot_table/blocks/interface.json",
           drops_itself("qbotics:interface", copy_components=["minecraft:custom_name"]))


# ---- antennas: one model, a different coloured tip per range tier (design doc §9.1) ----


def flat(colour, light, dark):
    """A plain surface with a lighter top-left and darker bottom-right edge."""
    px = [[colour for _ in range(16)] for _ in range(16)]
    for i in range(16):
        px[0][i] = light
        px[i][0] = light
        px[15][i] = dark
        px[i][15] = dark
    return px


write_png("assets/qbotics/textures/block/antenna_base.png", case_base())
write_png("assets/qbotics/textures/block/antenna_mast.png", flat(SHEATH, SHEATH_LIGHT, SHEATH_DARK))
ANTENNA_TIPS = {
    "short": (RED, RED_BRIGHT, (140, 24, 18)),                        # redstone red
    "long": ((70, 140, 220), (120, 180, 245), (40, 90, 160)),        # blue
    "infinite": ((160, 90, 220), (200, 140, 250), (104, 50, 160)),   # purple
}
ANTENNA_NAMES = {"short": "Short-Range Antenna", "long": "Long-Range Antenna", "infinite": "Infinite Antenna"}

for tier, colours in ANTENNA_TIPS.items():
    name = "antenna_%s" % tier
    write_png("assets/qbotics/textures/block/%s_tip.png" % name, flat(*colours))
    all_faces = ("north", "south", "east", "west", "up", "down")
    write_json("assets/qbotics/models/block/%s.json" % name, {
        "parent": "minecraft:block/block",
        "textures": {
            "base": "qbotics:block/antenna_base",
            "mast": "qbotics:block/antenna_mast",
            "tip": "qbotics:block/%s_tip" % name,
            "particle": "qbotics:block/antenna_base",
        },
        "elements": [
            {"from": [2, 0, 2], "to": [14, 4, 14], "faces": {f: {"texture": "#base"} for f in all_faces}},
            {"from": [7, 4, 7], "to": [9, 12, 9], "faces": {f: {"texture": "#mast"} for f in all_faces[:4]}},
            {"from": [6, 12, 6], "to": [10, 16, 10], "faces": {f: {"texture": "#tip"} for f in all_faces}},
        ],
    })
    write_json("assets/qbotics/blockstates/%s.json" % name, {"variants": {"": {"model": "qbotics:block/%s" % name}}})
    write_json("assets/qbotics/items/%s.json" % name, {
        "model": {"type": "minecraft:model", "model": "qbotics:block/%s" % name},
    })
    write_json("data/qbotics/loot_table/blocks/%s.json" % name,
               drops_itself("qbotics:%s" % name, copy_components=["minecraft:custom_name"]))

# Placeholder recipes (design doc §20): a column, each tier built from the one before.
write_json("data/qbotics/recipe/antenna_short.json", shaped(
    ["T", "I", "C"], {"T": "minecraft:redstone_torch", "I": "minecraft:iron_ingot", "C": "qbotics:cable"},
    "qbotics:antenna_short"))
write_json("data/qbotics/recipe/antenna_long.json", shaped(
    ["D", "A", "C"], {"D": "minecraft:diamond", "A": "qbotics:antenna_short", "C": "qbotics:cable"},
    "qbotics:antenna_long"))
write_json("data/qbotics/recipe/antenna_infinite.json", shaped(
    ["E", "A", "C"], {"E": "minecraft:ender_eye", "A": "qbotics:antenna_long", "C": "qbotics:cable"},
    "qbotics:antenna_infinite"))

print("assets written")
