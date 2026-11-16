# Item logistics: the blocks cabled to this computer (design doc §11.0).
#
#     from logistics import network, move, Face
#     net = network()
#     chest = net.bus("chest")            # an Item Serial Bus on a cable, pointing at a chest
#     furnace = net.bus("furnace")
#     move(chest, furnace.destination(face=Face.TOP), "raw_iron", 16)
#
# Things are moved from sources to destinations. A device's source() and destination()
# describe one end (which face or slot, and what it may give or take); move() does the moving.
#
# Every call here is answered by the game on its next tick (a twentieth of a second).
#
# Wherever an item or a fluid is named, a filter can be written instead, in the words of
# the JEI mod's search box:
#     "coal"                that one item (short for "minecraft:coal")
#     "*_log"               wildcards: * is any run of characters, ? any one
#     "@create"             everything from a mod, by its ID or name (part of either is enough)
#     "#minecraft:logs"     everything carrying a tag ($ works too); "#logs" also works
#     "@minecraft *_ore"    several words: all must hold
#     "-*_slab"             a word starting with - must not hold
#     "coal | charcoal"     alternatives
#
# Items also have words about one stack's data, and the game's own item syntax:
#     "enchanted_book enchant=mending"   "potion effect=strength"
#     "#c:tools damaged"   "undamaged"   "enchanted"   "named"
#     "potion[potion_contents={potion:'minecraft:strong_strength'}]"
#
# Filter() builds a filter from others, to name once and use anywhere:
#     junk = Filter(only=["#c:stones", "dirt", "gravel"])
#     keep = Filter(only="@minecraft", without=junk) | "#c:gems"
import json
import time
import _host


class NetworkError(Exception):
    """The network couldn't do what was asked. The message says why."""


def _plain(value):
    # What goes to the game: a Filter as the filter words it stands for.
    if isinstance(value, Filter):
        return str(value)
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    return value


def _call(*args):
    reply = _host.wait(_host.submit(json.dumps(_plain(list(args)))))
    if reply is None:
        raise NetworkError("the call was interrupted")
    if not reply.startswith("{"):
        raise NetworkError(reply)
    reply = json.loads(reply)
    if "e" in reply:
        raise NetworkError(reply["e"])
    return reply["r"]


class Face:
    """Which face of its target a bus reaches through, seen from the bus."""
    NEAR = "near"      # the face touching the bus
    FAR = "far"        # the opposite face
    TOP = "top"
    BOTTOM = "bottom"
    LEFT = "left"
    RIGHT = "right"


_FACES = (Face.NEAR, Face.FAR, Face.TOP, Face.BOTTOM, Face.LEFT, Face.RIGHT)


class Slot:
    """A slot (or a tank) by its number. Numbers start at 0 and run left to right, top to
    bottom, as contents() lists them. Use it where an item's name would go:
    chest.count(Slot(0)) is how many are in the first slot."""

    def __init__(self, index):
        self.index = index

    def __repr__(self):
        return "Slot(%d)" % self.index


def _aim(what, face, slot):
    # The first thing given to count() or amount() may be a name, a Face or a Slot.
    # Returns (name or None, face or None, slot number or None).
    if isinstance(what, Slot):
        what, slot = None, what
    elif what in _FACES:
        what, face = None, what
    if isinstance(slot, Slot):
        slot = slot.index
    return what, face, slot


def _list(names):
    # only= and without= take one name (or Filter) or a list of them.
    if names is None:
        return []
    if isinstance(names, (str, Filter)):
        return [names]
    return list(names)


def _split(text, alternatives):
    # A filter cut at | (alternatives) or at spaces (words), but not inside [...], {...} or
    # quotes, which the game's item syntax uses. The same as the game reads it.
    parts = []
    part = []
    depth = 0
    quote = None
    i = 0
    while i < len(text):
        c = text[i]
        if quote:
            part.append(c)
            if c == "\\" and i + 1 < len(text):
                i += 1
                part.append(text[i])
            elif c == quote:
                quote = None
            i += 1
            continue
        if c == '"' or c == "'":
            quote = c
        elif c == "[" or c == "{":
            depth += 1
        elif (c == "]" or c == "}") and depth > 0:
            depth -= 1
        if depth == 0 and (c == "|" if alternatives else c.isspace()):
            if alternatives:
                parts.append("".join(part).strip())
            elif part:
                parts.append("".join(part))
            part = []
        else:
            part.append(c)
        i += 1
    if alternatives:
        parts.append("".join(part).strip())
    elif part:
        parts.append("".join(part))
    return parts


def _negate(word):
    return word[1:] if word.startswith("-") and len(word) > 1 else "-" + word


_MOST_ALTERNATIVES = 128


def _and(a, b):
    # Both must hold: every alternative of one with every alternative of the other.
    out = []
    for p in a:
        for q in b:
            words = list(p)
            for w in q:
                if w not in words:
                    words.append(w)
            if any(_negate(w) in words for w in words):
                continue          # a word and its opposite: nothing passes
            if words not in out:
                out.append(words)
    if len(out) > _MOST_ALTERNATIVES:
        raise NetworkError("That filter grew too large (over %d alternatives). Make it with fewer 'without' and '~' parts." % _MOST_ALTERNATIVES)
    return out


def _or(a, b):
    out = list(a)
    for q in b:
        if q not in out:
            out.append(q)
    return out


def _not(a):
    # Not (p or q or ...) is (not p) and (not q) and ...; not (x and y) is (not x) or (not y).
    out = [[]]
    for words in a:
        out = _and(out, [[_negate(w)] for w in words])
    return out


def _alternatives(given):
    # A name, filter words, a Filter, or a list of them (any may hold), as alternatives of words.
    if isinstance(given, Filter):
        return given._alternatives
    if isinstance(given, (list, tuple)):
        out = []
        for one in given:
            out = _or(out, _alternatives(one))
        return out
    text = str(given)
    out = []
    for alternative in _split(text, True):
        words = _split(alternative, False)
        if not words:
            raise NetworkError("'%s' has an empty part beside a |." % text)
        out = _or(out, [words])
    return out


class Filter:
    """A filter made from others, to name once and use wherever a name or filter goes:
    move(), count(), watch(), only= and without=, a bus's set_filter().

        junk     = Filter(only=["#c:stones", "dirt", "gravel", "*_seeds"])
        vanilla  = Filter(only="@minecraft", without="*stone")
        treasure = Filter(only=["#c:gems", "#c:ingots", "enchanted_book enchant=mending"])
        keep     = (vanilla | treasure) & ~junk

    only= is what passes (any of a list); without= is what never does (none of a list). With
    no only=, everything passes but what without= names. Combine them with | (either), & (both)
    and ~ (not). str(f) gives the filter words it stands for."""

    def __init__(self, only=None, without=None):
        self._alternatives = [[]] if only is None else _alternatives(_list(only))
        for one in _list(without):
            self._alternatives = _and(self._alternatives, _not(_alternatives(one)))

    @staticmethod
    def _of(alternatives):
        made = Filter()
        made._alternatives = alternatives
        return made

    def __or__(self, other):
        return Filter._of(_or(self._alternatives, _alternatives(other)))

    def __ror__(self, other):
        return Filter._of(_or(_alternatives(other), self._alternatives))

    def __and__(self, other):
        return Filter._of(_and(self._alternatives, _alternatives(other)))

    def __rand__(self, other):
        return Filter._of(_and(_alternatives(other), self._alternatives))

    def __invert__(self):
        return Filter._of(_not(self._alternatives))

    def __str__(self):
        if not self._alternatives:
            return "-*"           # nothing passes
        return " | ".join(" ".join(words) if words else "*" for words in self._alternatives)

    def __repr__(self):
        return "Filter(%r)" % str(self)


class Source:
    """Somewhere to draw from: a device, and what it may give. Make one with a device's
    source() and hand it to move(). It does nothing by itself, so it can be made once, kept
    in a variable or a list, and used again and again."""

    def __init__(self, device, only=None, without=None, face=None, slot=None):
        self.device = device
        self.only = _list(only)
        self.without = _list(without)
        self.face = face
        self.slot = slot.index if isinstance(slot, Slot) else slot

    def _spec(self):
        return {"mac": self.device.mac, "only": self.only, "without": self.without, "face": self.face, "slot": self.slot}

    def __repr__(self):
        return "<%s %s>" % (type(self).__name__.lower(), self.device.name or self.device.ip)


class Destination(Source):
    """Somewhere to put things: a device, and what it will take. Make one with a device's
    destination() and hand it to move()."""


def _ends(given, make):
    # One end of a move: a Source or Destination, a device, a Storage, a name, or a list of those.
    if not isinstance(given, (list, tuple)):
        given = [given]
    specs = []
    for end in given:
        if isinstance(end, str):
            end = _wrap(_call("find", end, None))
        if isinstance(end, Device):
            end = make(end)
        if isinstance(end, Storage):
            end = _StorageEnd(end)
        if isinstance(end, Entity):
            end = _EntityEnd(end)
        specs.append(end._spec())
    return specs


def move(sources, destinations, what=None, count=None, need=None, wait=False):
    """Moves things from sources to destinations and returns how many moved.

    sources and destinations are each one thing or a list: a Source or Destination
    (made with a device's source() or destination()), or simply a device. Sources are
    drawn on in order, and each fills the destinations in order.

    what is a name or a filter ("coal", "#minecraft:logs", "@create"); leave it out to
    move whatever the sources and destinations allow. For energy and experience leave
    it out and give only an amount.

    count moves up to that many; fewer (even 0) is not an error. Without it, one of a
    named item (one bucket of a named fluid) moves, or everything for a filter.

    need moves exactly that many or nothing at all, and raises NetworkError if that
    many can't be moved right now. With wait=True it waits until they can instead
    (wait=30 gives up after 30 seconds); Ctrl+C stops the wait.

        move(chest, furnace.destination(face=Face.TOP), "raw_iron", 8)
        move(all_fuel, furnace.destination(face=Face.LEFT), "#minecraft:coals", need=4, wait=True)
        move(furnace.source(face=Face.BOTTOM), store)
    """
    froms = _ends(sources, Source)
    tos = _ends(destinations, Destination)
    if wait and need is None:
        need = count if count is not None else 1
    started = time.ticks_ms()
    while True:
        reply = _call("move", froms, tos, what, count, need)
        if not reply.get("short"):
            return reply["moved"]
        if not wait:
            raise NetworkError("Only %d of the %d needed can be moved right now. Nothing was moved." % (reply["could"], need))
        if wait is not True and time.ticks_diff(time.ticks_ms(), started) >= wait * 1000:
            raise NetworkError("Gave up after %s seconds: only %d of the %d needed could be moved." % (wait, reply["could"], need))
        time.sleep(0.5)


def _mac(device):
    # A device may be given as an object, or as a name or address to look up.
    if device is None:
        return None
    if isinstance(device, Device):
        return device.mac
    return _call("find", device, None)["mac"]


class Device:
    """A block on the network. ip, mac, name and kind are as they were when it was looked up."""

    def __init__(self, info):
        self._update(info)

    def _update(self, info):
        self.ip = info["ip"]        # internal address on this network, e.g. "192.168.1.4"
        self.mac = info["mac"]      # hardware address; never changes while the block stays placed
        self.name = info["name"]    # "" if it has no name
        self.kind = info["kind"]    # "computer", "antenna", "interface", "bus", "fluid_bus", "usb", ...
        self._info = info

    def rename(self, name):
        """Gives the device a name to find it by. "" removes the name."""
        self._update(_call("rename", self.mac, name))

    def refresh(self):
        """Looks the device up again, in case its name or address has changed."""
        self._update(_call("find", self.mac, None))
        return self

    def __repr__(self):
        name = ' "%s"' % self.name if self.name else ""
        return "<%s%s %s %s>" % (self.kind, name, self.ip, self.mac)


class _Gives:
    # For devices that things can be taken from.
    def source(self, only=None, without=None, face=None, slot=None):
        """This device as somewhere to draw from, for move(). only= lists the names or
        filters it may give (nothing else); without= lists what it must keep. face= and
        slot= narrow it to one face or one slot (or tank) of the container."""
        return Source(self, only, without, face, slot)


class _Takes:
    # For devices that things can be put into.
    def destination(self, only=None, without=None, face=None, slot=None):
        """This device as somewhere to put things, for move(). only= lists the names or
        filters it will take (nothing else); without= lists what it refuses. face= and
        slot= narrow it to one face or one slot (or tank) of the container."""
        return Destination(self, only, without, face, slot)


class Watch:
    """Watches a container, or a store, for changes. Make one with a device's watch().

        for change in chest.watch():          # sleeps until something changes
            print(change["item"], change["was"], "->", change["now"])

    Each change is a dictionary:
        items:   {"slot": 3, "item": "minecraft:coal", "was": 5, "now": 12}
        fluid:   {"tank": 0, "fluid": "minecraft:water", "was": 1000, "now": 0}
        energy and experience: {"was": 2000, "now": 1500}
    When one kind of item replaces another in a slot, that is two changes: the old one
    going to 0, and the new one coming from 0.

    Nothing is missed between looks: each look compares with the last one. A watch nobody
    looks at for a minute ends; a computer can have 16 open at once."""

    def __init__(self, device, face=None, what=None, every=0.25):
        self.device = device
        self.every = every    # seconds between looks while waiting
        self._token = _call("watch_open", device.mac, face, what)

    def changes(self):
        """What has changed since the last look (or since the watch began), straight
        away: a list, empty if nothing has."""
        return _call("watch_poll", self._token)

    def wait(self, timeout=None):
        """Sleeps until something changes and returns the list of changes. With timeout (in
        seconds), returns an empty list if nothing changes in that time. Ctrl+C stops it."""
        started = time.ticks_ms()
        while True:
            found = self.changes()
            if found:
                return found
            if timeout is not None and time.ticks_diff(time.ticks_ms(), started) >= timeout * 1000:
                return []
            time.sleep(self.every)

    def __iter__(self):
        # One change at a time, for ever: use it in a for loop, and break out when done.
        while True:
            for change in self.wait():
                yield change

    def close(self):
        """Ends the watch. Not needed, but frees it at once."""
        _call("watch_close", self._token)

    def __enter__(self):
        return self

    def __exit__(self, kind, error, trace):
        self.close()
        return False


class _Watches:
    # For devices whose contents can be read, and so watched.
    def watch(self, what=None, face=None, every=0.25):
        """Watches for changes, so a program can sleep until something arrives instead of
        reading again and again. what= narrows it to some items or fluids (a name or
        filter); face= to one face of the target. Returns a Watch:

            for change in chest.watch("coal"):
                print("coal in slot", change["slot"], "now", change["now"])

            changes = chest.watch().wait(10)    # whatever changes in the next 10 seconds"""
        return Watch(self, face, what, every)


class Storage:
    """Many containers used as one: the chests (or barrels, drawers, interfaces) behind
    item buses, found by their names. Make one with net.storage():

        store = net.storage("chest*")            # every bus or interface named chest...
        store = net.storage("storage")           # every one named "storage"
        store = net.storage(["ores", "chest*", net.interface()])

    Each call asks about all of them at once, in one tick, however many there are:

        store.count("iron_ingot")                # across every chest
        store.contents()                         # [{"item": ..., "count": ...}], most first
        move(store, furnace, "coal", 8)          # from whichever chests have it
        move(furnace.source(face=Face.BOTTOM), store)   # to where that item already is

    The names are looked up again on every call, so a bus named to match joins at once.
    A bus on a container another member already reaches (both halves of a double chest)
    is counted once. A member whose chest can't be reached now (gone, or not loaded) is
    left out until it can be."""

    def __init__(self, keys, address=None):
        if isinstance(keys, (str, Device)):
            keys = [keys]
        self.keys = [key.mac if isinstance(key, Device) else str(key) for key in keys]
        self._address = address
        if not self.keys:
            raise NetworkError('A storage needs at least one name or device: net.storage("chest*").')

    def _spec(self, only=None, without=None):
        return {"group": self.keys, "address": self._address, "only": _list(only), "without": _list(without)}

    def source(self, only=None, without=None):
        """The storage as somewhere to draw from, for move(). only= and without= as for a device."""
        return _StorageEnd(self, only, without)

    def destination(self, only=None, without=None):
        """The storage as somewhere to put things, for move(). Each kind goes first to a
        bus whose filter lets it in (where some already is, then any with room), then to a
        container with no filter (again where some already is first): the overflow. A bus
        whose filter doesn't let it in never gets it."""
        return _StorageEnd(self, only, without)

    def _stock(self, item=None, details=False):
        return _call("stock", [self._spec()], item, details)

    def count(self, item=None):
        """How many of an item (a name or filter) are in all of it; with no item, how many
        items there are in all."""
        return _call("available", [self._spec()], item)

    def contents(self, details=False):
        """What it holds, added up across every container: a list of
        {"item": "minecraft:coal", "count": 1200}, the most first. With details=True, stacks
        that differ in data (a Strength potion, a Swiftness potion) are counted apart, each
        with its "data", exact "filter" and "words"."""
        return self._stock(None, bool(details))["items"]

    def where(self, item):
        """Which containers hold an item (a name or filter), and how many each:
        a list of {"device": <bus>, "count": 40}."""
        return [{"device": _wrap(m["device"]), "count": m["count"]} for m in self._stock(item)["members"] if m["count"]]

    def free(self):
        """How many empty slots there are, in all."""
        return sum(m["free"] for m in self._stock()["members"])

    def slots(self):
        """How many slots there are, in all."""
        return sum(m["slots"] for m in self._stock()["members"])

    def devices(self):
        """The buses and interfaces it is made of now, in order."""
        return [_wrap(m["device"]) for m in self._stock()["members"]]

    def strays(self):
        """Things sitting in a chest whose bus's filter doesn't let them in (put there by
        hand, or before the filter was set): a list of {"device": <bus>, "count": 3}."""
        return [{"device": _wrap(m["device"]), "count": m["strays"]} for m in self._stock()["members"] if m["strays"]]

    def tidy(self):
        """Moves strays to where they belong in this storage: to a chest whose filter takes
        them, or the overflow. Returns how many moved. Things with nowhere to go stay put."""
        moved = 0
        for m in self._stock()["members"]:
            left = m["strays"]
            while left > 0:
                bus = _wrap(m["device"])
                step = move(bus.source(without=m["filter"]["only"]), self, count=left)
                if step == 0:
                    break
                moved += step
                left -= step
        return moved

    def __repr__(self):
        return "<storage %s>" % ", ".join(self.keys)


class _StorageEnd(Source):
    # A storage as one end of a move.
    def __init__(self, storage, only=None, without=None):
        self.storage = storage
        self.device = None
        self.only = _list(only)
        self.without = _list(without)
        self.face = None
        self.slot = None

    def _spec(self):
        return self.storage._spec(self.only, self.without)

    def __repr__(self):
        return "<storage end %s>" % ", ".join(self.storage.keys)


class Port(Device):
    """A plate on a cable that points at a container: a Serial Bus."""

    def set_face(self, face):
        """Sets the face used when nothing names one."""
        self._update(_call("set_face", self.mac, face))

    @property
    def face(self):
        """The face used when nothing names one."""
        return self._info["face"]

    @property
    def target(self):
        """The block it points at, e.g. "minecraft:furnace"."""
        return self.refresh()._info["target"]

    def tunnel(self, other):
        """Opens a tunnel between this bus and another (a device, or its name or address).
        Each end then offers, to hoppers, pipes and cables touching its face, the container
        the other end points at: what goes in at one end comes out at the other, with no
        program running. A tunnel either bus was in is closed first. Both must be on this
        network, or behind antennas that both hold a Transport Upgrade."""
        self._update(_call("tunnel", self.mac, _mac(other)))

    def untunnel(self):
        """Closes this bus's tunnel, at both ends."""
        self._update(_call("tunnel", self.mac, None))

    @property
    def tunneled(self):
        """True if this bus is one end of a tunnel."""
        return "tunnel" in self.refresh()._info

    @property
    def tunnel_end(self):
        """The bus at the other end of its tunnel, or None if it has none or the other end
        can't be reached now (its area unloaded, or an antenna out of range)."""
        address = self.refresh()._info.get("tunnel")
        return _wrap(_call("find", address, None)) if address else None


class _Reads(_Watches):
    # For plates that can see what their target holds.
    def contents(self, face=None, details=False):
        """What the target holds, as seen through a face (or all of it, with no face): a list
        of {"slot": 0, "item": "minecraft:coal", "count": 12}. With details=True each also has
        its "data" (what sets it apart from its kind), a "filter" naming exactly it, and the
        short filter "words" that hold for it, such as ["enchant=mending", "enchanted"]."""
        return _call("contents", self.mac, face, bool(details))

    def count(self, item=None, face=None, slot=None):
        """How many of an item the target holds: chest.count("coal"). With no item, how
        many of everything. Narrow it with face= or slot=, or give a Face or a Slot in
        place of the item: chest.count(Face.BOTTOM), chest.count(Slot(2)). For fluid the
        answer is in millibuckets."""
        item, face, slot = _aim(item, face, slot)
        return _call("count", self.mac, item, face, slot)


class Interface(Device, _Gives, _Takes, _Watches):
    """Filtered storage: the network's chest. A source and a destination for move()."""

    def contents(self, details=False):
        """What it holds: a list of {"slot": 0, "item": "minecraft:coal", "count": 12}.
        details=True adds each stack's "data", exact "filter" and "words", as for a bus."""
        return _call("contents", self.mac, None, bool(details))

    def count(self, item=None, slot=None):
        """How many of an item it holds (or of everything, with no item). slot= narrows it
        to one slot; a Slot can also stand in for the item: store.count(Slot(0))."""
        item, _, slot = _aim(item, None, slot)
        return _call("count", self.mac, item, None, slot)

    def filters(self):
        """What each slot accepts: a name or filter, or None for a closed slot."""
        return _call("filters", self.mac)

    def set_slot_filter(self, slot, item):
        """Opens a slot for one kind of item, or for every item a filter passes:
        "coal", "#minecraft:logs", "@create", "*_ore". None closes it again. This is a
        rule of the block itself: it also stops hoppers and other mods' pipes."""
        _call("set_filter", self.mac, slot, item)

    @property
    def slots(self):
        """How many slots it has."""
        return self._info["slots"]


class _ScreenEnd(Destination):
    # One slot of an open screen, as an end of a move. It carries the screen's xp, so a
    # take that costs levels (the anvil's product) is paid from it.
    _screen = None

    def _spec(self):
        spec = Source._spec(self)
        spec["screen"] = True
        if self._screen is not None:
            spec["xp"] = self._screen._xp()
        return spec


class Screen:
    """The screen of the block a bus points at, opened as a player would open it. Its
    slots are numbered as the screen numbers them (the probe's Slots mode shows them).

    Use it in a with block, so it is closed even if the program stops with an error:

        with table.open_screen() as screen:
            screen.put(Slot(1), "oak_planks", sources=store)
            screen.take(Slot(0), store)

    Items exist in a screen only while it is open. When it closes, whatever the block hands
    back goes to an interface; anything with nowhere to go is dropped at the bus.

    A screen that costs levels (an anvil, an enchanting table) is paid from xp: an
    Experience Interface, a fluid interface or fluid plate holding liquid experience, or a
    list of those, tried in order. Give it when opening, open_screen(xp=bank), or set
    screen.xp at any time. The bus acts as a player with exactly the levels asked, so only
    what the block really charges is taken."""

    def __init__(self, bus, slots, xp=None):
        self.bus = bus
        self._slots = [entry["slot"] for entry in slots]
        self.open = True
        self.xp = xp

    def _xp(self):
        return None if self.xp is None else _ends(self.xp, Source)

    def cost(self):
        """What the screen asks in levels right now. An anvil: one number, for taking its
        product (0 when it has nothing to make). An enchanting table: a list of three, one
        for each offer, pressed as buttons 0, 1 and 2 (0 where there is no offer). Other
        screens: None."""
        return _call("screen_cost", self.bus.mac)

    def slots(self):
        """The numbers of the screen's own slots."""
        return list(self._slots)

    def contents(self):
        """What its slots hold now: a list of {"slot": 0, "item": "minecraft:stick", "count": 4}."""
        return _call("screen_contents", self.bus.mac)

    def source(self, slot, only=None, without=None):
        """One slot of this screen as a source, for move()."""
        end = _ScreenEnd(self.bus, only, without, None, slot)
        end._screen = self
        return end

    def destination(self, slot, only=None, without=None):
        """One slot of this screen as a destination, for move()."""
        return _ScreenEnd(self.bus, only, without, None, slot)

    def put(self, slot, what=None, count=None, sources=None, need=None, wait=False):
        """Moves items from sources into one slot of the screen; returns how many moved.
        what, count, need and wait mean what they do in move()."""
        if sources is None:
            raise NetworkError("put() needs sources= to say where the items come from.")
        return move(sources, self.destination(slot), what, count, need, wait)

    def take(self, slot, destinations, what=None, count=None):
        """Moves what is in one slot of the screen to destinations; returns how many moved.
        Without count, all of it moves, even when what names one item. A product slot (a
        crafting table's result) only gives its whole stack, and taking from it uses up the
        ingredients, as it does for a player."""
        if count is None:
            count = 1 << 20    # the whole slot: a take is never more than one slot holds
        return move(self.source(slot), destinations, what, count)

    def press(self, button, times=1):
        """Presses one of the screen's buttons, as a player clicking it; times presses it
        that many times, for a screen with one button that steps through its outputs. A
        stonecutter's or a loom's choices are buttons 0, 1, 2... in the order the screen
        shows them, for what is in its slots now. The probe in Slots mode tells you a
        button's number when you click it. Returns False if the screen refused the last
        press. Some screens accept any number, so look at the product slot to see what was
        chosen."""
        if not isinstance(times, int) or times < 1:
            raise NetworkError("times= is how often to press the button: 1 or more, not %r." % (times,))
        took = False
        for _ in range(times):
            took = _call("screen_button", self.bus.mac, button, self._xp())
        return took

    def close(self, leftovers=None):
        """Closes the screen. What the block hands back goes to leftovers (one destination
        or a list), then to any interface on the network. Returns how many items had
        nowhere to go and were dropped at the bus."""
        if not self.open:
            return 0
        self.open = False
        return _call("screen_close", self.bus.mac, [] if leftovers is None else _ends(leftovers, Destination))

    def __enter__(self):
        return self

    def __exit__(self, kind, error, trace):
        self.close()
        return False


class Bus(Port, _Reads, _Gives, _Takes):
    def set_filter(self, only=None, matching=False):
        """Sets what this bus lets into the container it points at, kept by the bus for every
        move, every storage it is part of, and things pushed through its tunnel. only= is a
        name, filter or Filter, or a list of them. matching=True also lets in any item the
        container already holds (a worn pickaxe lets in a new one); matching="exact" only
        things identical to one already there, data and all (a Strength II potion, not any
        potion). Taking things out is never stopped, so something put in by hand can still be
        counted and taken. set_filter() with nothing clears it: anything goes in.

            net.bus("ores").set_filter(["#c:ores", "#c:raw_materials"])
            net.bus("chest_3").set_filter(matching=True)        # only items already in it
            net.bus("potions").set_filter(matching="exact")     # only potions just like those in it"""
        how = matching if matching == "exact" else bool(matching)
        self._update(_call("set_bus_filter", self.mac, [str(n) for n in _list(only)] or None, how))

    @property
    def filter(self):
        """What it lets in: {"only": [...], "matching": False, True or "exact"}, or None for anything."""
        return self.refresh()._info.get("filter")

    """An Item Serial Bus: takes items out of the container it points at and puts them in.
    A source and a destination for move()."""

    def open_screen(self, xp=None):
        """Opens the screen of the block it points at, for blocks whose slots are only on
        their screen (a crafting table) or that offer nothing else. Returns a Screen.
        The bus acts for its owner, so it can open what its owner could. xp says where
        levels are paid from, for screens that cost them (see Screen)."""
        return Screen(self, _call("screen_open", self.mac), xp)


class Recipe:
    """What goes into which slot of a screen, and which slots the product comes out of.
    Fill it in once and use it many times:

        blocks = Recipe(sources=store, destination=store)
        for n in range(1, 10):                       # a crafting table's grid is slots 1 to 9
            blocks.ingredient("coal", Slot(n))
        blocks.result(Slot(0), "coal_block", 1)      # and its product is slot 0

        if blocks.can_craft(4):
            with table.open_screen() as screen:
                blocks.craft(4, screen)

    Screens where you choose what to make, such as a stonecutter or a loom, also need the
    button for that choice, pressed after the ingredients are in:

        bricks = Recipe(sources=store, destination=store)
        bricks.ingredient("stone", Slot(0)).button(4).result(Slot(1), "stone_bricks", 1)

    (The probe in Slots mode tells you a button's number when you click it.)

    ingredients and results are ordinary dictionaries, keyed by slot number, buttons is an
    ordinary list, and sources and destination are ordinary attributes: read them or change
    them as you like."""

    def __init__(self, sources=None, destination=None, xp=None):
        self.sources = sources            # one source or a list, as move() takes
        self.destination = destination    # one destination or a list
        self.xp = xp                      # where levels are paid from; None uses the screen's
        self.ingredients = {}             # slot number -> {"what": name or filter, "amount": per run}
        self.results = {}                 # slot number -> {"what": name or None, "amount": per run or None, "destination": or None}
        self.buttons = []                 # presses each run, in order, after the ingredients are in: {"button": n, "times": t}

    @staticmethod
    def from_game(recipe, sources=None, destination=None, xp=None):
        """A Recipe made from the game's own (game.recipes, or a step of game.plan), ready to
        craft at its station: a crafting table, a stonecutter or a smithing table, through a
        bus on that block (open_screen). Furnaces cook over time instead: put the input in
        with move() and collect the output (the recipe's "craft" gives their slots)."""
        craft = recipe.get("craft", recipe) if isinstance(recipe, dict) else None
        if not craft or "station" not in craft:
            raise NetworkError("The game can't describe that recipe for a program (another mod's machine, or a special recipe).")
        station = craft["station"]
        if station not in ("minecraft:crafting_table", "minecraft:stonecutter", "minecraft:smithing_table"):
            raise NetworkError("A %s works over time, not through its screen: put the input in its slot %d with move() and collect "
                               "the output from slot %s." % (station.split(":")[-1], craft["inputs"][0]["slot"],
                                                             craft["output"].get("slot", "-")))
        made = Recipe(sources, destination, xp)
        for line in craft["inputs"]:
            made.ingredient(line["item"], Slot(line["slot"]), line["count"])
        if "button" in craft:
            made.button(craft["button"])
        out = craft["output"]
        made.result(Slot(out["slot"]), out["item"], out["count"])
        return made

    def ingredient(self, what, slot, amount=1):
        """Adds an ingredient: this name or filter, into this slot, this many per run."""
        self.ingredients[_aim(None, None, slot)[2]] = {"what": what, "amount": amount}
        return self

    def button(self, number, times=1):
        """Adds a button press to each run, made after the ingredients are laid out and
        before the product is taken: a stonecutter's or a loom's choice. times presses it
        that many times, for a screen with one button that steps through its outputs. Call
        it again for more presses; they are made in the order added."""
        if not isinstance(number, int) or number < 0:
            raise NetworkError("A button is a number from 0, as the probe shows when you click it; not %r." % (number,))
        if not isinstance(times, int) or times < 1:
            raise NetworkError("times= is how often to press the button: 1 or more, not %r." % (times,))
        self.buttons.append({"button": number, "times": times})
        return self

    def result(self, slot, what=None, amount=None, destination=None):
        """Names a slot the product is taken from. Call it once per output slot. what and
        amount are optional: give them to have each run checked, and to let runs_for()
        work. destination sends this result somewhere other than the recipe's own."""
        self.results[_aim(None, None, slot)[2]] = {"what": what, "amount": amount, "destination": destination}
        return self

    def _needs(self):
        # What one run uses: each name or filter, and how many of it over all its slots.
        needs = {}
        for line in self.ingredients.values():
            needs[line["what"]] = needs.get(line["what"], 0) + line["amount"]
        return needs

    def max_runs(self, sources=None):
        """How many runs the sources can supply right now."""
        sources = self.sources if sources is None else sources
        if sources is None:
            raise NetworkError("This recipe has no sources. Give sources= when making it, or set recipe.sources.")
        if not self.ingredients:
            return 0
        specs = _ends(sources, Source)
        return min(_call("available", specs, what) // each for what, each in self._needs().items())

    def can_craft(self, runs=1, sources=None):
        """True if the sources hold enough of every ingredient for that many runs."""
        return self.max_runs(sources) >= runs

    def runs_for(self, count, slot=None):
        """How many runs give at least count of the product. With several results, slot
        says which. The result's amount must have been given."""
        if slot is None:
            if len(self.results) != 1:
                raise NetworkError("This recipe has %d results. Say which one with slot=." % len(self.results))
            slot = list(self.results)[0]
        slot = _aim(None, None, slot)[2]
        each = self.results[slot]["amount"]
        if not each:
            raise NetworkError("The result in slot %d has no amount, so runs can't be worked out. Give it one: recipe.result(Slot(%d), what, amount)." % (slot, slot))
        return (count + each - 1) // each

    def craft(self, runs=1, screen=None, wait=False, sources=None, destination=None):
        """Does the runs on an open screen and returns what was made, as a dictionary of
        item name to how many. Each run lays the ingredients into their slots, presses
        the recipe's buttons, and takes the results out. If a run can't be done it raises
        NetworkError; ingredients already laid out go back when the screen closes.
        wait=True pauses for missing ingredients instead (a number waits that many
        seconds). Levels the screen costs are paid from the recipe's xp, or if it has
        none, from the screen's."""
        if screen is None or self.xp is None:
            return self._craft(runs, screen, wait, sources, destination)
        before = screen.xp
        screen.xp = self.xp
        try:
            return self._craft(runs, screen, wait, sources, destination)
        finally:
            screen.xp = before

    def _craft(self, runs, screen, wait, sources, destination):
        if screen is None:
            raise NetworkError("craft() needs an open screen: with bus.open_screen() as screen: recipe.craft(1, screen)")
        sources = self.sources if sources is None else sources
        destination = self.destination if destination is None else destination
        if sources is None or not self.results:
            raise NetworkError("A recipe needs sources and at least one result() before it can be crafted.")
        made = {}
        for run in range(runs):
            for slot in sorted(self.ingredients):
                line = self.ingredients[slot]
                screen.put(slot, line["what"], sources=sources, need=line["amount"], wait=wait)
            for line in self.buttons:
                screen.press(line["button"], line["times"])
            shown = {entry["slot"]: entry for entry in screen.contents()}
            for slot in sorted(self.results):
                line = self.results[slot]
                there = shown.get(slot)
                if there is None:
                    raise NetworkError("Run %d made nothing in slot %d. Is the recipe right for this block%s?" % (run + 1, slot, ", and its buttons" if self.buttons else ""))
                if line["amount"] is not None and there["count"] < line["amount"]:
                    raise NetworkError("Run %d made %d in slot %d, not the %d the recipe says." % (run + 1, there["count"], slot, line["amount"]))
                to = line["destination"] if line["destination"] is not None else destination
                if to is None:
                    raise NetworkError("The result in slot %d has nowhere to go. Give the recipe a destination." % slot)
                moved = screen.take(slot, to, line["what"])
                if moved == 0:
                    raise NetworkError("The %s in slot %d could not be taken out: it is not what the recipe names, or its destination has no room." % (there["item"], slot))
                made[there["item"]] = made.get(there["item"], 0) + moved
        return made


# ---- fluid: amounts are in millibuckets (mB); 1000 is one bucket ----


class FluidInterface(Device, _Gives, _Takes, _Watches):
    """The network's fluid store: four tanks of four buckets (4000 mB) each."""

    def contents(self):
        """What each tank holds: a list of {"tank": 0, "fluid": "minecraft:water",
        "amount": 1000, "capacity": 4000}. An empty tank's fluid is None."""
        return _call("contents", self.mac, None)

    def amount(self, fluid=None, slot=None):
        """How many millibuckets of a fluid it holds (or of everything, with no fluid).
        slot= narrows it to one tank; a Slot can also stand in for the fluid."""
        fluid, _, slot = _aim(fluid, None, slot)
        return _call("count", self.mac, fluid, None, slot)

    def filters(self):
        """The fluid each tank is kept for, e.g. ["minecraft:water", None, None, None];
        None where a tank takes any fluid."""
        return _call("filters", self.mac)

    def set_tank_filter(self, tank, fluid):
        """Keeps a tank for one fluid ("water", "minecraft:lava"); None lets it take any
        again. What the tank already holds stays. This is a rule of the block itself: it
        also stops other mods' pipes. On the block's screen, click a tank's socket with a
        bucket of the fluid to do the same."""
        _call("set_filter", self.mac, tank, fluid)


class _ReadsFluid(_Watches):
    def contents(self, face=None):
        """The target's tanks, as seen through a face (or all of them, with no face)."""
        return _call("contents", self.mac, face)

    def amount(self, fluid=None, face=None, slot=None):
        """How many millibuckets of a fluid the target holds (or of everything). Narrow it
        with face= or slot= (a tank's number), or give a Face or a Slot in place of the fluid."""
        fluid, face, slot = _aim(fluid, face, slot)
        return _call("count", self.mac, fluid, face, slot)


class FluidBus(Port, _ReadsFluid, _Gives, _Takes):
    """A Fluid Serial Bus: takes fluid out of the tank it points at and puts it in."""


# ---- energy: amounts are in FE, the unit mods share (also called RF) ----


class EnergyInterface(Device, _Gives, _Takes, _Watches):
    """The network's energy store: it holds 2,000,000 FE."""

    @property
    def stored(self):
        """How much energy it holds now."""
        return _call("energy", self.mac, None)["stored"]

    @property
    def capacity(self):
        """The most it can hold."""
        return self._info["capacity"]


class _ReadsEnergy(_Watches):
    def stored(self, face=None):
        """How much energy the target holds."""
        return _call("energy", self.mac, face)["stored"]

    def capacity(self, face=None):
        """The most the target can hold."""
        return _call("energy", self.mac, face)["capacity"]


class EnergyBus(Port, _ReadsEnergy, _Gives, _Takes):
    """An Energy Serial Bus: takes energy out of the machine it points at and puts it in."""


class USB(Bus):
    """A Universal Serial Bus: an item, a fluid and an energy bus in one plate.

    On its own it works as the item bus: usb.count("coal"), usb.open_screen().
    usb.fluid and usb.energy are the same plate as a fluid bus and an energy bus:
    usb.fluid.amount("water"), usb.energy.stored(). In move() it carries whatever the
    other ends do, so move(usb, tanks) moves fluid when tanks is a fluid interface."""

    def _as(self, medium):
        return _wrap(_call("find", self.mac + "@" + medium, None))

    @property
    def items(self):
        """This plate as an item bus."""
        return self._as("item")

    @property
    def fluid(self):
        """This plate as a fluid bus."""
        return self._as("fluid")

    @property
    def energy(self):
        """This plate as an energy bus."""
        return self._as("energy")


class ExperienceInterface(Device, _Gives, _Takes, _Watches):
    """The network's experience store. It holds real experience only, with no limit;
    players store and take levels at the block. move() between two of them moves points."""

    @property
    def points(self):
        """Experience held, in points (what the game counts underneath levels)."""
        return self.refresh()._info["points"]

    @property
    def levels(self):
        """The level those points would take a player to from nothing."""
        return self.refresh()._info["levels"]

    @property
    def lock(self):
        """Who may store and take at the block: "private", "team" or "public"."""
        return self.refresh()._info["lock"]


class PlayerInterface(Device, _Gives, _Takes, _Watches):
    """A bus on the player who placed it: their inventory, armour and off hand, wherever
    they are, while they are online. Only their own computers can use it. A source and a
    destination for move(), numbered as the game numbers a player's slots:

        0-8 the hotbar, 9-35 the rest, 36 feet, 37 legs, 38 chest, 39 head, 40 the off hand

    A move with no slot uses slots 0-35, so armour is put on or taken off only by naming
    its slot: move(store, me.destination(slot=PlayerInterface.HEAD), "iron_helmet")."""

    FEET, LEGS, CHEST, HEAD, OFFHAND = 36, 37, 38, 39, 40
    HOTBAR = range(0, 9)

    def contents(self):
        """What the player carries: a list of {"slot": 0, "item": "minecraft:torch", "count": 12}."""
        return _call("contents", self.mac, None)

    def count(self, item=None, slot=None):
        """How many of an item the player carries, armour and off hand included (or of
        everything, with no item). slot= narrows it to one slot."""
        item, _, slot = _aim(item, None, slot)
        return _call("count", self.mac, item, None, slot)

    def info(self):
        """The player now: {"player": "Steve", "online": True, "health": 20.0, "max_health":
        20.0, "food": 20, "saturation": 5.0, "level": 7, "dimension": "minecraft:overworld",
        "x": 12.5, "y": 64.0, "z": -3.5, "selected": 0, "game_mode": "survival",
        "held": "minecraft:iron_pickaxe"}. Offline, only "player" and "online"."""
        return _call("player_info", self.mac)

    def armor(self):
        """What the player wears: {"head": "minecraft:iron_helmet", "chest": None, ...}."""
        held = {entry["slot"]: entry["item"] for entry in self.contents()}
        return {"head": held.get(39), "chest": held.get(38), "legs": held.get(37), "feet": held.get(36)}

    @property
    def player(self):
        """The name of the player it reaches."""
        return self.refresh()._info["player"]

    @property
    def online(self):
        """True while that player is on the server."""
        return self.refresh()._info["online"]

    @property
    def slots(self):
        """How many slots it has: 41."""
        return self._info["slots"]


class CommandResult:
    """What a command did: .ok (True if it worked), .result (the number it gave, as a
    command block's comparator would read it) and .output (the lines it said). It is true
    when ok, and prints as its output."""

    def __init__(self, answer):
        self.ok = answer["ok"]
        self.result = answer["result"]
        self.output = answer["output"]

    def __bool__(self):
        return self.ok

    def __str__(self):
        return "\n".join(self.output)

    def __repr__(self):
        return "<CommandResult %s %r>" % ("ok" if self.ok else "failed", self.output)


class ChatInterface(Device):
    """The server's chat. It hears what players say (events.on_chat, or chat.on_chat),
    writes to chat, and runs commands as the player who placed it, with their permissions.
    Only their own computers can use it; commands need them online."""

    def say(self, text):
        """Writes a line to everyone's chat, as "[name] text" (the interface's name, or
        Chat Interface). At most 256 characters, and 5 messages a second."""
        _call("chat_say", self.mac, str(text))

    def tell(self, player, text):
        """Writes a line to one player's chat only."""
        _call("chat_tell", self.mac, str(player), str(text))

    def command(self, command):
        """Runs a command as the owner, as if they had typed it ("/" is optional), and
        returns a CommandResult. What it says comes back to the program, not to their chat."""
        return CommandResult(_call("chat_command", self.mac, str(command)))

    def on_chat(self, function=None):
        """Runs a function for each chat message while events.run() is going. The same as
        events.on_chat(chat, function)."""
        import events
        return events.on_chat(self, function)

    @property
    def player(self):
        """The name of the player it acts for."""
        return self.refresh()._info["player"]


class CommandInterface(Device):
    """A command block on the network. It has no recipe: an operator in creative places
    it, or a command does. Commands run at the block, at a command block's permission level."""

    def run(self, command):
        """Runs a command ("/" is optional) and returns a CommandResult:
        print(cmd.run("time query daytime"))."""
        return CommandResult(_call("command_run", self.mac, str(command)))


class _WorldPlate(Device):
    # A plate that acts on the world in front of it rather than on a container.

    @property
    def target(self):
        """The block it points at, e.g. "minecraft:stone"."""
        return self.block()["block"]

    def block(self):
        """The block it points at, and its state:
        {"block": "minecraft:wheat", "state": {"age": "7"}, "air": False, "fluid": None}.
        fluid names the fluid in that space ("minecraft:water"), or is None."""
        return _call("look", self.mac)


class Pin(_WorldPlate):
    """A Logic Pin: one redstone line, in or out, and an eye on the block it points at.

        door = net.pin("door")
        door.write(15)              # power it, full strength
        door.pulse(4)               # on for 4 ticks (a fifth of a second), then off
        sensor = net.pin("sensor")  # a new pin reads
        if sensor.read() > 0: ...
        sensor.wait_for("rising")   # sleep until its signal comes on

    As an output it powers the block it points at strongly, as a lever powers the block it
    is on, so the signal also reaches through that block. Redstone dust joins to it."""

    def _state(self):
        return _call("pin", self.mac)

    @property
    def mode(self):
        """"input" (it reads) or "output" (it drives). Set it to change. A new pin reads."""
        return self._state()["mode"]

    @mode.setter
    def mode(self, mode):
        _call("pin_set", self.mac, mode, None, None)

    def read(self):
        """The signal level, 0 to 15: what arrives from the block it points at, or as an
        output, the level it drives."""
        return self._state()["level"]

    def write(self, level):
        """Drives a level, 0 to 15 (0 is off), and makes the pin an output. True and False
        work as 15 and 0."""
        if level is True:
            level = 15
        elif level is False:
            level = 0
        _call("pin_set", self.mac, "output", level, None)

    def pulse(self, ticks=2, level=15):
        """Drives a level for some ticks (20 a second), then drops to 0 by itself, so a
        program can clock a machine without staying awake to switch it off."""
        _call("pin_set", self.mac, "output", level, ticks)

    def wait_for(self, what="change", timeout=None):
        """Sleeps until something happens, and returns True; with timeout (in seconds),
        returns False if it doesn't happen in that time. Ctrl+C stops the wait.
        what is one of:
            "rising"   the signal comes on (from 0 to more)
            "falling"  the signal goes off (to 0)
            "change"   the signal's level changes at all
            "block"    the block it points at changes, or its state does (a crop grows a
                       stage, a door opens, a furnace lights), as an observer would see
        Signals are read only while the pin is an input. Nothing is missed between
        looks: even a pulse too short to see counts."""
        key = {"rising": "rising", "falling": "falling", "change": "changes", "block": "blocks"}.get(what)
        if key is None:
            raise NetworkError('wait_for() takes "rising", "falling", "change" or "block", not %r.' % (what,))
        before = self._state()[key]
        started = time.ticks_ms()
        while True:
            time.sleep(0.05)
            if self._state()[key] != before:
                return True
            if timeout is not None and time.ticks_diff(time.ticks_ms(), started) >= timeout * 1000:
                return False


class Placer(_WorldPlate):
    """A Placer: puts a block, or pours a fluid, into the space in front of it, as its
    owner would. The space must be empty, or hold something that gives way (grass,
    flowing water); it does nothing, and says why, if it is taken.

        placer.place("cobblestone", sources=store)
        placer.pour("water", sources=tanks)"""

    def place(self, what=None, sources=None):
        """Takes one item from sources (one source or a list) and places it. what is a
        name or filter; left out, the first block found is placed. Returns the item's
        name. Items the owner couldn't place there go back where they came from."""
        if sources is None:
            raise NetworkError("place() needs sources= to say where the block comes from.")
        return _call("place", self.mac, _ends(sources, Source), what)["placed"]

    def pour(self, fluid=None, sources=None):
        """Takes a bucket's worth (1000 mB) of fluid from sources and pours it as a source
        block. Returns the fluid's name."""
        if sources is None:
            raise NetworkError("pour() needs sources= to say where the fluid comes from.")
        return _call("pour", self.mac, _ends(sources, Source), fluid)["poured"]


class Dropper(_WorldPlate):
    """A Dropper plate: sends items out into the world in front of it. As a dropper (the
    default) it throws them out; with dispense set, it fires them as a dispenser would:
    arrows and snowballs fly, bone meal grows what is there, a bucket of water is poured,
    armour goes onto whoever stands in front.

        dropper.drop("cobblestone", 16, sources=store)    # thrown out, as one stack
        dropper.dispense = True
        dropper.drop("arrow", 5, sources=store)           # five arrows fired, one a tick

    It throws out into the world even when a chest is in front: a bus is for containers."""

    @property
    def dispense(self):
        """True if it fires items as a dispenser does; False (the default) if it throws them out
        as a dropper does. The plate keeps the setting."""
        return self.refresh()._info["dispense"]

    @dispense.setter
    def dispense(self, fire):
        _call("dropper_set", self.mac, bool(fire))

    def drop(self, what=None, count=1, sources=None, dispense=None):
        """Takes items from sources (one source or a list) and sends them out; returns how many
        went. what is a name or filter; left out, whatever comes first. Up to count go: as a
        dropper, up to 64 of one kind a tick; as a dispenser, one item a tick. dispense= True or
        False does it that way this once, whatever the setting. An item the dispenser can't use
        (bone meal with nothing to grow) goes back, and so does what it hands back (an empty
        bucket); then it stops."""
        if sources is None:
            raise NetworkError("drop() needs sources= to say where the items come from.")
        ends = _ends(sources, Source)
        sent = 0
        while sent < count:
            reply = _call("drop", self.mac, ends, what, count - sent, dispense)
            if not reply["dropped"]:
                break
            sent += reply["dropped"]
        return sent


    def drop_xp(self, points=None, levels=None, sources=None):
        """Sends out experience as orbs, taken from sources (an Experience Interface, or liquid
        experience in a fluid store; one or a list, drawn on in order). Give points=, or levels=
        counted from 0: levels=30 is the 1,395 points that take a player from level 0 to 30
        (one already above 0 gains fewer levels from it). Exactly that much, or nothing and a
        NetworkError if the sources hold less. Returns the points sent out.

            spout.drop_xp(levels=30, sources=net.experience_interface())"""
        if sources is None:
            raise NetworkError("drop_xp() needs sources= to say where the experience comes from.")
        return _call("drop_xp", self.mac, _ends(sources, Source), points, levels)["dropped"]


class Entity:
    """One entity an Entity Sensor sees: a creature, a player, a dropped item or an experience
    orb. What the sensor read is in .data, and each key is also an attribute:

        cow.type        "minecraft:cow"          cow.name, cow.custom_name
        cow.health      10.0                     cow.max_health
        cow.baby        False                    cow.can_breed (adult, ready), cow.in_love
        cow.pos         [x, y, z]                cow.offset (from the sensor), cow.distance
        sheep.sheared, sheep.color   villager.profession, villager.level   wolf.tame, wolf.owner
        item.item, item.count() (a dropped item) orb.value (an experience orb)
        frame.framed, frame.rotation (an item frame)   stand.armor {"head", "chest", "legs", "feet"}
        e.slots: how many slots its inventory has (0 for none): see contents() and source()
        e.hostile, e.held, e.player, e.leashed, e.age

    Keys a kind doesn't have are missing: a cow has no "sheared". refresh() reads it again."""

    def __init__(self, sensor, info):
        self._sensor = sensor
        self._update(info)

    def _update(self, info):
        self.data = info
        self.uuid = info["uuid"]
        self.type = info["type"]
        self.name = info["name"]

    def __getattr__(self, key):
        data = self.__dict__.get("data")
        if data is not None and key in data:
            return data[key]
        raise AttributeError("%s has no %s" % (self.__dict__.get("type", "entity"), key))

    def refresh(self):
        """Reads it again. NetworkError if it is gone or has left the area."""
        self._update(_call("sensor_entity", self._sensor.mac, self.uuid))
        return self

    def use(self, item=None, sources=None, destination=None):
        """Right-clicks it as the sensor's owner, holding one item from sources (a name or
        filter; with no item, an empty hand): a bucket milks a cow, wheat breeds it, shears
        shear a sheep, a name tag names it. What comes of it (the milk bucket, the wool) goes
        to destination, or back to the sources; an item that wasn't used goes back. Returns
        True if it did something.

            for cow in sensor.entities("cow"):
                cow.use("bucket", store, destination=milk_chest)"""
        reply = _call("sensor_use", self._sensor.mac, self.uuid, None if sources is None else _ends(sources, Source),
                      item, None if destination is None else _ends(destination, Destination))
        if reply["entity"]:
            self._update(reply["entity"])
        return reply["used"]

    def attack(self, weapon=None, sources=None, destination=None, wait=True):
        """Hits it as the sensor's owner, with a weapon (a name or filter) from sources, or a
        fist. The weapon's damage, enchantments (Looting too) and wear count, and a kill counts
        as the owner's. Each swing is a full one: it waits for the weapon's cooldown since the
        sensor's last swing (wait=False hits at once, as hard as the cooldown allows). The
        weapon goes back to the sources (or destination). Returns {"attacked", "alive", "health"},
        and "weapon_broke" if it broke."""
        while True:
            reply = _call("sensor_attack", self._sensor.mac, self.uuid, None if sources is None else _ends(sources, Source),
                          weapon, None if destination is None else _ends(destination, Destination), bool(wait))
            if "wait" not in reply:
                return reply
            time.sleep(reply["wait"] / 20)

    def offers(self):
        """A villager's or wandering trader's offers: a list of {"index", "buy": {"item",
        "count"}, "buy_b" (a second price, or None), "sell": {...}, "uses", "max_uses",
        "out_of_stock"}. A sold item with data (an enchanted book) also has its "words"
        (["enchant=mending", ...]) and exact "filter"."""
        return _call("sensor_offers", self._sensor.mac, self.uuid)

    def trade(self, offer, sources, destination=None, times=1):
        """Makes an offer (its index, or the dict from offers()) up to times times, as the
        sensor's owner: the price is taken from sources, checked before anything moves; what it
        sells goes to destination, or back to the sources. Stops early when it runs out or the price
        can't be paid. Returns how many trades were made."""
        index = offer["index"] if isinstance(offer, dict) else offer
        reply = _call("sensor_trade", self._sensor.mac, self.uuid, index, _ends(sources, Source),
                      None if destination is None else _ends(destination, Destination), times)
        return reply["traded"]

    def collect(self, to):
        """Picks it up, if it is a dropped item (into item destinations) or an experience orb
        (into an Experience Interface among them). Returns {"items": n, "points": p}."""
        return _call("sensor_collect", self._sensor.mac, self.uuid, _ends(to, Destination), None)

    # Its inventory, for an entity that has one (data["slots"] > 0): a minecart's or boat's
    # chest, a donkey's or llama's packs; a creature's or armor stand's hands and armour, as
    # slots 0 main hand, 1 off hand, 2 feet, 3 legs, 4 chest, 5 head. Never a player's.

    def contents(self, details=False):
        """What it holds: [{"slot", "item", "count"}], as a chest's contents()."""
        return _call("sensor_contents", self._sensor.mac, self.uuid, bool(details))

    def count(self, item=None, slot=None):
        """How many of an item (a name or filter) it holds; of everything, with none. A dropped
        item holds the stack lying there: item.count() is how many."""
        if not self.data.get("slots") and "count" in self.data:
            named = None if item is None else (item if ":" in item else "minecraft:" + item)
            return self.data["count"] if named is None or named == self.data.get("item") else 0
        if isinstance(slot, Slot):
            slot = slot.index
        return _call("available", [_EntityEnd(self, slot=slot)._spec()], item)

    def source(self, only=None, without=None, slot=None):
        """Its inventory as somewhere to draw from, for move(): move(cart, store)."""
        return _EntityEnd(self, only, without, slot)

    def destination(self, only=None, without=None, slot=None):
        """Its inventory as somewhere to put things, for move(): move(store, stand.destination(slot=5), "iron_helmet")."""
        return _EntityEnd(self, only, without, slot)

    def __repr__(self):
        named = ' "%s"' % self.data["custom_name"] if self.data.get("custom_name") else ""
        return "<%s%s %.1f m>" % (self.type.replace("minecraft:", ""), named, self.data["distance"])


class _Area:
    # For blocks that work a box around them: radius out to each side, height up and down.
    @property
    def radius(self):
        """How far it reaches out to each side, in blocks."""
        return self.refresh()._info["radius"]

    @property
    def height(self):
        """How far it reaches up and down, in blocks."""
        return self.refresh()._info["height"]

    def set_area(self, radius=None, height=None):
        """Sets how far it reaches: radius out to each side, height up and down, each 1 to 20."""
        self._update(_call("area", self.mac, radius, height))


class Collector(Device, _Area, _Gives, _Takes, _Watches):
    """A Collector: a chest's worth of slots (27) that fills itself with what lies in a box
    around it. Twice a second it picks up the dropped items it has room for (once their
    pickup delay is over, as a player would), and experience orbs into an Experience
    Interface on its network, if there is one (with none, orbs stay where they are).

    It is a source and a destination for move(), as a chest behind a bus is:

        drops = net.collector()
        move(drops, net.storage("chest*"))
        print(drops.count("rotten_flesh"))"""

    def contents(self, details=False):
        """What it holds: a list of {"slot": 0, "item": "minecraft:bone", "count": 12}.
        details=True adds each stack's "data", exact "filter" and "words"."""
        return _call("contents", self.mac, None, bool(details))

    def count(self, item=None, slot=None):
        """How many of an item (a name or filter) it holds; of everything, with none."""
        item, _, slot = _aim(item, None, slot)
        return _call("count", self.mac, item, None, slot)

    @property
    def slots(self):
        """How many slots it has (27)."""
        return self._info["slots"]


class BlockSensor(Device, _Watches):
    """A Block Sensor: reads the blocks in a small box near it, tells a program what changes
    there, and breaks, places and uses items there as its owner. The box starts as the one
    block above it; grow it on its screen or with set_area(), up to 11 blocks along each
    side, within 11 of the sensor. Places are offsets from the sensor, (dx, dy, dz).

        farm = net.block_sensor()
        farm.set_area(x=(-4, 4), y=(1, 1), z=(-4, 4))     # a 9 x 9 field on its level, above it
        for change in farm.watch("wheat age=7"):          # sleeps until wheat ripens
            farm.break_block(change["pos"], to=store)
            farm.place(change["pos"], "wheat_seeds", store)

    Watching costs nothing while nothing changes: the game itself tells the sensor of each
    change in its box."""

    @property
    def area(self):
        """Its box: {"x": (low, high), "y": (low, high), "z": (low, high)}, offsets from it."""
        b = self.refresh()._info["box"]
        return {"x": (b[0], b[3]), "y": (b[1], b[4]), "z": (b[2], b[5])}

    def set_area(self, x=None, y=None, z=None):
        """Sets its box along any of x, y and z: a pair (low, high) of offsets from the sensor,
        or one number for one layer. Each side at most 11 long, within 11 of the sensor."""
        b = list(self.refresh()._info["box"])
        for axis, given in ((0, x), (1, y), (2, z)):
            if given is not None:
                low, high = given if isinstance(given, (list, tuple)) else (given, given)
                b[axis], b[axis + 3] = low, high
        self._info["box"] = _call("bsensor_box", self.mac, b)

    def blocks(self, what=None):
        """The blocks in its box: a list of {"pos": [dx, dy, dz], "block", "state", "air",
        "fluid"}. what is a name or filter, with state words: "wheat age=7", "#minecraft:crops",
        "air". With none, every block that isn't air."""
        return _call("bsensor_blocks", self.mac, what)

    def block(self, pos, details=False):
        """One block in its box, at an offset (dx, dy, dz). details=True adds "data": the
        block's own saved data in the game's raw form (a spawner's mob, a beehive's bees, a
        modded machine's settings), or None for a block that has none. Read-only."""
        return _call("bsensor_block", self.mac, list(pos), bool(details))

    def write_sign(self, pos, lines, back=False, color=None, glowing=None):
        """Writes a sign at an offset as its owner would: up to four lines (a list of text),
        on its front, or its back with back=True. color= a dye colour ("green"), glowing=True
        or False. A waxed sign can't be changed. Returns the block, as block() does."""
        return _call("bsensor_sign", self.mac, list(pos), list(lines), bool(back), color, glowing)

    def break_block(self, pos, to=None):
        """Breaks the block at an offset as its owner would with the right netherite tool,
        taking as long as that does (crops give way at once). The drops go to to (one
        destination or a list), all or nothing; with none, they fall where the block was.
        Returns what it got: {name: count}."""
        ends = None if to is None else _ends(to, Destination)
        while True:
            reply = _call("bsensor_break", self.mac, list(pos), ends)
            if "wait" not in reply:
                return reply["got"]
            time.sleep(reply["wait"] / 20)

    def place(self, pos, item=None, sources=None):
        """Places one item (a name or filter) from sources at an offset, as its owner would:
        a block, or seeds onto farmland. Returns True if it went there; if not, it goes back."""
        if sources is None:
            raise NetworkError("place() needs sources= to say where the item comes from.")
        return _call("bsensor_place", self.mac, list(pos), _ends(sources, Source), item)["placed"]

    def use(self, pos, item=None, sources=None, destination=None):
        """Right-clicks the block at an offset as its owner, holding one item from sources (or
        an empty hand): bone meal grows what is there, a hoe tills dirt, a bucket fills or
        empties, a lever flips. What comes of it goes to destination, or back to the sources.
        Returns True if it did something."""
        reply = _call("bsensor_use", self.mac, list(pos), None if sources is None else _ends(sources, Source), item,
                      None if destination is None else _ends(destination, Destination))
        return reply["used"]


class _EntityEnd(Source):
    # An entity's inventory as one end of a move, reached through the sensor that sees it.
    def __init__(self, entity, only=None, without=None, slot=None):
        self.entity = entity
        self.device = entity._sensor
        self.only = _list(only)
        self.without = _list(without)
        self.face = None
        self.slot = slot.index if isinstance(slot, Slot) else slot

    def _spec(self):
        spec = Source._spec(self)
        spec["entity"] = self.entity.uuid
        return spec

    def __repr__(self):
        return "<inventory of %s>" % self.entity.type


class EntitySensor(Device, _Area):
    """An Entity Sensor: sees the creatures, players, dropped items and experience orbs in a
    box around it (radius blocks out to each side, height up and down, each 1 to 20), and acts
    on them as its owner. Right-click it for its screen and Show area.

        sensor = net.entity_sensor()
        for cow in sensor.entities("cow"):
            if cow.can_breed:
                cow.use("wheat", store)
        for zombie in sensor.entities("#minecraft:undead"):
            zombie.attack("diamond_sword", store)
        sensor.collect(store)                   # the drops"""

    def entities(self, what=None):
        """What is in its area now, nearest first: a list of Entity. what is a name or filter
        of a kind of entity, as for items: "cow", "#minecraft:undead", "@minecraft",
        "-player", "item" (dropped items), "experience_orb"."""
        return [Entity(self, info) for info in _call("sensor_entities", self.mac, what)]

    def nearest(self, what=None):
        """The nearest entity (of a kind), or None."""
        found = self.entities(what)
        return found[0] if found else None

    def collect(self, to, what=None):
        """Picks up everything lying in its area: dropped items (those a name or filter what
        passes, or all) into item destinations, and, with no what, experience orbs into an
        Experience Interface among them. Returns {"items": n, "points": p}."""
        return _call("sensor_collect", self.mac, None, _ends(to, Destination), what)


class Breaker(_WorldPlate):
    """A Breaker: breaks the block in front of it, as the best tool for that block would
    (a netherite pickaxe, axe, shovel, hoe or sword, unenchanted), but never bedrock or
    anything else that can't be broken. It holds nothing itself:

        got = breaker.break_block(to=store)    # {"minecraft:cobblestone": 1}

    Every drop must have somewhere to go before the block breaks; if one doesn't, nothing
    breaks and NetworkError names it. A container breaks with its contents, which count
    as drops. Pointed at a fluid's source, it takes it up into a fluid store instead."""

    def break_block(self, to):
        """Breaks the block in front, waiting as long as breaking it takes, and returns
        what it got: {name: count}. to is one destination or a list: interfaces
        or buses for items, fluid ones for fluid (a USB is both)."""
        ends = _ends(to, Destination)
        while True:
            reply = _call("break", self.mac, ends)
            if "wait" not in reply:
                return reply["got"]
            # The game counts in ticks (20 a second when it keeps up); look again soon.
            time.sleep(min(reply["wait"] / 20, 0.1))


class DriveBay(Device):
    """A Drive Bay: four sockets for hard drives. Every computer on its network sees each
    drive as a folder, path + "/" + drive name, and reads and writes files there as on its
    own drive:

        bay = net.drive_bay()
        open(bay.path + "/backup/main.py", "w").write(code)

    In the shell:  cp main.py /net/<bay>/backup/   and   run /net/<bay>/backup/main.py"""

    @property
    def path(self):
        """The bay's folder, "/net/" and its name (or "bay" and its number if it has none)."""
        return "/net/" + self.refresh()._info["folder"]

    def drives(self):
        """The names of the drives in it: each one's anvil name, or "drive" and its socket."""
        return list(self.refresh()._info["drives"])


class Programmer(Device):
    """A Programmer: copies a program from its hard drive onto the computer in front of its
    programming face, makes it its owner's (switching it on if a machine placed it), and
    restarts it so its startup.py runs. Redstone usually starts it; a program can too:

        net.programmer().program(3)          # level 3's program, by the drive's programs.txt
        net.programmer().program("miner")    # the drive's miner folder"""

    @property
    def status(self):
        """What happened the last time it tried."""
        return self.refresh()._info["status"]

    def program(self, choice=None):
        """Programs the computer in front: choice is a redstone level (1 to 15), the name
        of a folder on the drive, or None for programs.txt's * line (or the whole drive).
        Returns what it did; raises NetworkError if it couldn't."""
        return _call("program", self.mac, None if choice is None else str(choice))


_KINDS = {"interface": Interface, "bus": Bus, "usb": USB, "drive_bay": DriveBay, "programmer": Programmer,
          "pin": Pin, "placer": Placer, "breaker": Breaker, "dropper": Dropper,
          "fluid_interface": FluidInterface, "fluid_bus": FluidBus,
          "energy_interface": EnergyInterface, "energy_bus": EnergyBus, "experience_interface": ExperienceInterface,
          "player_interface": PlayerInterface, "chat_interface": ChatInterface, "command_interface": CommandInterface,
          "entity_sensor": EntitySensor, "collector": Collector, "block_sensor": BlockSensor}


def _wrap(info):
    if info["kind"] == "tablet":
        import display
        return display.Tablet(info)
    if info["kind"] in ("monitor", "panel", "web_panel"):
        import display     # the display module brings its own classes
        return {"monitor": display.Monitor, "panel": display.Panel, "web_panel": display.WebPanel}[info["kind"]](info)
    return _KINDS.get(info["kind"], Device)(info)


class Network:
    """The cable network this computer is on."""

    def _find(self, key, kind):
        return _wrap(_call("find", key, kind))

    def _list(self):
        return _call("devices")

    def remote(self, address):
        """The network behind another antenna, by its external address ("100.64.0.2"): its
        devices are found and used as this network's are, and move() carries things
        between the two. Needs a Transport Upgrade in both antennas, and the radio's rules
        (same frequency, in range, access) to let this computer's owner in."""
        return RemoteNetwork(address)

    def devices(self, kind=None):
        """Every device on the network (or every one of a kind), in address order."""
        return [_wrap(i) for i in self._list() if kind is None or i["kind"] == kind]

    def device(self, key):
        """A device by name, IP address or hardware address."""
        return self._find(key, None)

    def interface(self, key=None):
        """An interface by name or address; with no argument, the network's only interface."""
        return self._find(key, "interface")

    def bus(self, key=None):
        """An Item Serial Bus by name or address; with no argument, the network's only one.
        A USB counts too, working on items."""
        return self._find(key, "bus")

    def usb(self, key=None):
        """A Universal Serial Bus by name or address; with no argument, the network's only one."""
        return self._find(key, "usb")

    def fluid_interface(self, key=None):
        """A fluid interface by name or address; with no argument, the network's only one."""
        return self._find(key, "fluid_interface")

    def fluid_bus(self, key=None):
        """A Fluid Serial Bus by name or address; with no argument, the network's only one.
        A USB counts too, working on fluid."""
        return self._find(key, "fluid_bus")

    def energy_interface(self, key=None):
        """An energy interface by name or address; with no argument, the network's only one."""
        return self._find(key, "energy_interface")

    def energy_bus(self, key=None):
        """An Energy Serial Bus by name or address; with no argument, the network's only one.
        A USB counts too, working on energy."""
        return self._find(key, "energy_bus")

    def experience_interface(self, key=None):
        """An experience interface by name or address; with no argument, the network's only one."""
        return self._find(key, "experience_interface")

    def player_interface(self, key=None):
        """A player interface by name or address; with no argument, the network's only one."""
        return self._find(key, "player_interface")

    def chat_interface(self, key=None):
        """A chat interface by name or address; with no argument, the network's only one."""
        return self._find(key, "chat_interface")

    def command_interface(self, key=None):
        """A command interface by name or address; with no argument, the network's only one."""
        return self._find(key, "command_interface")

    def entity_sensor(self, key=None):
        """An Entity Sensor by name or address; with no argument, the network's only one."""
        return self._find(key, "entity_sensor")

    def collector(self, key=None):
        """A Collector by name or address; with no argument, the network's only one."""
        return self._find(key, "collector")

    def block_sensor(self, key=None):
        """A Block Sensor by name or address; with no argument, the network's only one."""
        return self._find(key, "block_sensor")

    def monitor(self, key=None):
        """A text monitor by name or address; with no argument, the network's only one.
        See the display module for what it can do."""
        return self._find(key, "monitor")

    def panel(self, key=None):
        """A control panel by name or address; with no argument, the network's only one.
        See the display module for what it can do."""
        return self._find(key, "panel")

    def web_panel(self, key=None):
        """A web panel by name or address; with no argument, the network's only one.
        See the display module for what it can do."""
        return self._find(key, "web_panel")

    def web_panels(self):
        """Every web panel on the network."""
        return self.devices("web_panel")

    def tablet(self, key=None):
        """A Tablet paired with this network's antenna: by the name of the player carrying
        it, its name or its address; with no argument, the only one paired. See the
        display module's Tablet."""
        return _wrap(_call("tablet_find", key))

    def tablets(self):
        """Every Tablet paired with this network's antenna."""
        return [_wrap(i) for i in _call("tablets")]

    def monitors(self):
        """Every text monitor on the network."""
        return self.devices("monitor")

    def panels(self):
        """Every control panel on the network."""
        return self.devices("panel")

    def programmer(self, key=None):
        """A programmer by name or address; with no argument, the network's only one."""
        return self._find(key, "programmer")

    def drive_bay(self, key=None):
        """A drive bay by name or address; with no argument, the network's only one."""
        return self._find(key, "drive_bay")

    def pin(self, key=None):
        """A logic pin by name or address; with no argument, the network's only one."""
        return self._find(key, "pin")

    def placer(self, key=None):
        """A placer by name or address; with no argument, the network's only one."""
        return self._find(key, "placer")

    def breaker(self, key=None):
        """A breaker by name or address; with no argument, the network's only one."""
        return self._find(key, "breaker")

    def dropper(self, key=None):
        """A dropper plate by name or address; with no argument, the network's only one."""
        return self._find(key, "dropper")

    def pins(self):
        """Every logic pin on the network."""
        return self.devices("pin")

    def interfaces(self):
        """Every interface on the network."""
        return self.devices("interface")

    def buses(self):
        """Every Item Serial Bus on the network (not USBs: see usbs())."""
        return self.devices("bus")

    def usbs(self):
        """Every Universal Serial Bus on the network."""
        return self.devices("usb")

    def storage(self, *keys):
        """Many containers used as one (see Storage): every interface and item bus a name,
        a pattern ("chest*"), an address or a device given here finds.

            store = net.storage("chest*")
            print(store.count("iron_ingot"))"""
        if len(keys) == 1 and isinstance(keys[0], (list, tuple)):
            keys = keys[0]
        return Storage(list(keys), getattr(self, "address", None))

    @property
    def me(self):
        """This computer."""
        return _wrap(_call("self"))


class RemoteNetwork(Network):
    """The network behind another antenna (see Network.remote). Its devices carry the
    antenna's address in theirs, as "100.64.0.2/02:1a:...", so every call reaches them
    there. Rate: each antenna carries a limited amount each second (the server sets it);
    a move asks for no more than may cross now."""

    def __init__(self, address):
        self.address = str(address)

    def _find(self, key, kind):
        return _wrap(_call("find", key, kind, self.address))

    def _list(self):
        return _call("devices", self.address)

    @property
    def me(self):
        raise NetworkError("This computer isn't on that network.")

    def remote(self, address):
        return RemoteNetwork(address)

    def __repr__(self):
        return "<network behind %s>" % self.address


def network():
    """The cable network this computer is on. Ask it for its blocks:
    net.interface(), net.bus(), net.fluid_bus(), net.usb(), net.devices()."""
    return Network()


def _print_devices(address=None, as_json=False):
    # The shell's "devices" command; with an address, the devices behind another antenna.
    # as_json: "devices --json", every detail the game knows, for programs and AI tools.
    try:
        if as_json:
            import json
            listed = _call("devices", address) if address is not None else _call("devices")
            if address is None:
                me = _call("self")["mac"]
                for d in listed:
                    d["this_computer"] = d["mac"] == me
            print(json.dumps(listed))
            return
        if address is not None:
            print("%-22s %-14s %-9s %s" % ("Address", "IP there", "Kind", "Name"))
            for d in _call("devices", address):
                print("%-22s %-14s %-9s %s" % ("%s:%d" % (d["external_ip"], d["port"]), d["ip"], d["kind"], d["name"]))
            return
        me = _call("self")
        print("%-14s %-9s %-18s %s" % ("IP address", "Kind", "Name", "Points at"))
        unnamed = None
        for d in _call("devices"):
            if d["mac"] == me["mac"]:
                points = "(this computer)"
            else:
                points = d.get("target", "")
                points = points[len("minecraft:"):] if points.startswith("minecraft:") else points
                points = "nothing" if points == "air" else points
            if not d["name"] and d["mac"] != me["mac"] and unnamed is None:
                unnamed = d["ip"]
            print("%-14s %-9s %-18s %s" % (d["ip"], d["kind"], d["name"] or "(no name)", points))
        if unnamed:
            print("Name one so programs can find it:  name %s inbox" % unnamed)
    except NetworkError as e:
        print(e)


def _name_device(address, name=None):
    # The shell's "name" command: name <address> <name> names a device on this network (an
    # IP address, a hardware address or its name now); name <address> alone says its name.
    try:
        device = network().device(address)
    except NetworkError as e:
        raise SystemExit(str(e))
    if name is None:
        print(device.ip, device.kind, device.name or "(no name)")
        return
    was = device.name
    try:
        device.rename(name)
    except NetworkError as e:
        raise SystemExit(str(e))
    if was:
        print("Renamed %s %s from %s to %s" % (device.kind, device.ip, was, device.name))
    else:
        print("Named %s %s: %s" % (device.kind, device.ip, device.name))
