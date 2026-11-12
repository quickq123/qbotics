# Qbots: a computer that moves (design doc §8).
"""This Qbot's body: moving, sensing, digging, placing, using, attacking, and what it carries.

    import qbot
    from qbot import inspect, inventory, tools, Side

    qbot.forward()
    if inspect.front().id == "minecraft:stone":
        qbot.dig()
    qbot.turn_right()
    inventory.drop(Side.FRONT, "minecraft:cobblestone")    # into a chest in front, or onto the ground

A step or a turn takes a quarter of a second. Anything it can't do raises QbotError,
saying why; nothing fails quietly. It digs as an iron tool would (an Iron Qbot); a tool in
its hand (tools.equip) digs, attacks and is used as a player's would, and wears out."""
import time
from logistics import NetworkError, _call


class QbotError(NetworkError):
    """The Qbot couldn't do what was asked. The message says why."""


class ToolBrokenError(QbotError):
    """The tool in the Qbot's hand broke. What it was doing was done; the tool is gone."""


class Side:
    """The six sides of a Qbot, as it faces."""
    FRONT = "front"
    BACK = "back"
    LEFT = "left"
    RIGHT = "right"
    TOP = "top"
    BOTTOM = "bottom"


def _q(*args):
    try:
        reply = _call(*args)
    except QbotError:
        raise
    except NetworkError as e:
        raise QbotError(str(e))
    if isinstance(reply, dict) and "tool_broke" in reply:
        raise ToolBrokenError("The %s in its hand broke. (What it was doing was done.)" % reply["tool_broke"])
    return reply


def _move(way):
    reply = _q("qbot_move", way)
    time.sleep(reply["wait"] / 20)
    return True


def forward():
    """Moves one block forward. Raises QbotError if something is in the way."""
    return _move("forward")


def back():
    """Moves one block back, without turning."""
    return _move("back")


def up():
    """Moves one block up."""
    return _move("up")


def down():
    """Moves one block down."""
    return _move("down")


def _turn(right):
    reply = _q("qbot_turn", right)
    time.sleep(reply["wait"] / 20)
    return reply["facing"]


def turn_left():
    """Turns a quarter to the left. Returns the way it faces now ("north", ...)."""
    return _turn(False)


def turn_right():
    """Turns a quarter to the right."""
    return _turn(True)


def turn_around():
    """Turns to face the other way."""
    _turn(True)
    return _turn(True)


def info():
    """Everything about it at once: tier, facing, x, y, z, energy, energy_capacity, tanks, tool."""
    return _q("qbot_info")


def position():
    """Where it is: (x, y, z). A Qbot always knows."""
    i = _q("qbot_info")
    return (i["x"], i["y"], i["z"])


def facing():
    """The way it faces: "north", "east", "south" or "west"."""
    return _q("qbot_info")["facing"]


def dig(side=Side.FRONT):
    """Digs the block on a side, as long as a player with the same tool would take, and
    returns what it got: {name: count}. Every drop must fit in its inventory, or nothing is
    dug. It digs as its tier's tools would unless the tool in its hand does better."""
    while True:
        reply = _q("qbot_dig", side)
        if "wait" not in reply:
            return reply["got"]
        time.sleep(min(reply["wait"] / 20, 0.1))


def place(side=Side.FRONT, item=None):
    """Places a block from its inventory on a side: the named one, or the first block it holds."""
    return _q("qbot_place", side, item)["placed"]


def use(side=Side.FRONT, item=None):
    """Uses something on a side as a player right-clicking would: the named item from its
    inventory, else the tool in its hand, else an empty hand (a lever, a door). On a creature
    too (shears on a sheep, a bucket on a cow). What it gives goes into its inventory.
    Returns True if it did something."""
    return _q("qbot_use", side, item)["used"]


def attack(side=Side.FRONT):
    """Attacks a creature on a side with the tool in its hand (or its fist). Returns the
    creature's name; raises QbotError if nothing is there."""
    return _q("qbot_attack", side)["attacked"]


def pump(side=Side.FRONT):
    """Takes up a source of water or lava (a bucket's worth) into one of its two tanks."""
    return _q("qbot_pump", side)["pumped"]


def pour(side=Side.FRONT, fluid=None):
    """Pours a bucket's worth from a tank onto a side, making a source block there."""
    return _q("qbot_pour", side, fluid)["poured"]


class Block:
    """What is on one side. id ("minecraft:stone"), air (True for nothing), state (its
    properties), tags, fluid (or None), source (a full fluid block), entities (the creatures
    and items there, by name)."""

    def __init__(self, seen):
        for key in seen:
            setattr(self, key, seen[key])

    def __repr__(self):
        return "<%s>" % self.id


class _Inspect:
    """Looks at the six sides. Seeing isn't looking inside: inventory.read() does that."""

    def side(self, side):
        return Block(_q("qbot_inspect", side))

    def front(self):
        return self.side(Side.FRONT)

    def back(self):
        return self.side(Side.BACK)

    def left(self):
        return self.side(Side.LEFT)

    def right(self):
        return self.side(Side.RIGHT)

    def top(self):
        return self.side(Side.TOP)

    def bottom(self):
        return self.side(Side.BOTTOM)


inspect = _Inspect()


class _Inventory:
    """Its 16 slots (a 4x4 grid; numbers 0 to 15), and moving items between them and the
    blocks beside it. face= picks which face of that block (as for a bus): "near" (the one
    touching the Qbot; the default), "far", "top", "bottom", "left" or "right" — so it can
    fill a furnace's top from beside it."""

    def list(self):
        """What it holds: [{"slot": 0, "item": "minecraft:coal", "count": 12}, ...]."""
        return _q("qbot_items")

    def count(self, item=None):
        """How many of an item (or of everything) it holds."""
        total = 0
        for stack in _q("qbot_items"):
            if item is None or stack["item"] == item or stack["item"] == "minecraft:" + item:
                total += stack["count"]
        return total

    def drop(self, side=Side.FRONT, item=None, count=None, face=None):
        """Puts items into the block on a side (a chest, a furnace); with nothing there that
        takes them, drops them on the ground. Returns how many went."""
        return _q("qbot_drop", side, item, count, face)

    def suck(self, side=Side.FRONT, item=None, count=None, face=None):
        """Takes items from the block on a side; with no container there, picks up items
        lying there. Returns how many it took."""
        return _q("qbot_suck", side, item, count, face)

    def insert(self, side, item=None, count=None, face="near"):
        """The same as drop(), but only into a container (never onto the ground)."""
        return _q("qbot_drop", side, item, count, face)

    def extract(self, side, item=None, count=None, face="near"):
        """The same as suck(), but only from a container."""
        return _q("qbot_suck", side, item, count, face)

    def read(self, side=Side.FRONT, face=None):
        """What the container on a side holds, as list() gives it."""
        return _q("qbot_read", side, face)


inventory = _Inventory()


class _Tools:
    """The tool in its hand: it digs, attacks and is used with it, and it wears like a
    player's. When it breaks, the action is done and ToolBrokenError is raised."""

    def equip(self, item):
        """Takes an item from its inventory into its hand (what was there goes back)."""
        return _q("qbot_equip", item)

    def unequip(self):
        """Puts the tool in its hand back into its inventory. Returns its name, or None."""
        return _q("qbot_unequip")

    def equipped(self):
        """The tool in its hand: {"item", "damage", "max_damage"}, or None."""
        return _q("qbot_info")["tool"]


tools = _Tools()


class _Energy:
    """Its energy cell: 1,000,000 FE it carries (it doesn't need energy to work)."""

    def stored(self):
        return _q("qbot_info")["energy"]

    def capacity(self):
        return _q("qbot_info")["energy_capacity"]

    def give(self, side=Side.FRONT, amount=None, face=None):
        """Moves energy from its cell into the block on a side. Returns how much."""
        return _q("qbot_energy", side, amount, True, face)

    def take(self, side=Side.FRONT, amount=None, face=None):
        """Moves energy from the block on a side into its cell. Returns how much."""
        return _q("qbot_energy", side, amount, False, face)


energy = _Energy()


class _Tanks:
    """Its two tanks of 4,000 mB each: one fluid in each, so it can carry water and lava at once."""

    def list(self):
        """[{"fluid": "minecraft:water", "amount": 3000, "capacity": 4000}, {...}]."""
        return _q("qbot_info")["tanks"]

    def give(self, side=Side.FRONT, fluid=None, amount=None, face=None):
        """Moves fluid from its tanks into the block on a side (a tank, a machine)."""
        return _q("qbot_fluid", side, fluid, amount, True, face)

    def take(self, side=Side.FRONT, fluid=None, amount=None, face=None):
        """Moves fluid from the block on a side into its tanks."""
        return _q("qbot_fluid", side, fluid, amount, False, face)


tanks = _Tanks()


class Picture:
    """A picture the Qbot took (snapshot()): .width and .height in pixels. block_at(x, y)
    says what a pixel shows: a block's id ("minecraft:stone"), "monster", "animal", "player"
    or "creature" for a creature, or None for the sky. Show it on a web panel with
    web.image("name", picture) and <img src="image:name">."""

    def __init__(self, info):
        self.id = info["id"]
        self.width = info["width"]
        self.height = info["height"]
        self._names = info["names"]
        self._pixels = info["pixels"]

    def block_at(self, x, y):
        if not (0 <= x < self.width and 0 <= y < self.height):
            raise IndexError("the picture is %d x %d" % (self.width, self.height))
        name = self._names[ord(self._pixels[y * self.width + x]) - 256]
        return name or None

    def counts(self):
        """How many pixels show each thing: {"minecraft:stone": 812, ...} (the sky is None)."""
        seen = {}
        for ch in self._pixels:
            name = self._names[ord(ch) - 256] or None
            seen[name] = seen.get(name, 0) + 1
        return seen

    def __repr__(self):
        return "<Picture %d x %d>" % (self.width, self.height)


def snapshot(width=64, height=48, pitch=0):
    """Takes a picture of what is in front of it, up to 32 blocks away: blocks, fluids and
    creatures. pitch tilts the camera up (positive) or down, in degrees. At most 128 x 96
    pixels, and one picture a second."""
    return Picture(_q("qbot_snapshot", width, height, pitch))
