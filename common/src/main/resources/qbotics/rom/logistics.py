# Item logistics: the blocks cabled to this computer (design doc §11.0).
#
#     from logistics import network, move, Face
#     net = network()
#     chest = net.bus("chest")            # a bus plate on a cable, pointing at a chest
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
import json
import time
import _host


class NetworkError(Exception):
    """The network couldn't do what was asked. The message says why."""


def _call(*args):
    reply = _host.wait(_host.submit(json.dumps(list(args))))
    if reply is None:
        raise NetworkError("the call was interrupted")
    if not reply.startswith("{"):
        raise NetworkError(reply)
    reply = json.loads(reply)
    if "e" in reply:
        raise NetworkError(reply["e"])
    return reply["r"]


class Face:
    """Which face of its target a bus or output reaches through, seen from the bus."""
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
    # only= and without= take one name or a list of them.
    if names is None:
        return []
    if isinstance(names, str):
        return [names]
    return list(names)


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
    # One end of a move: a Source or Destination, a device, a name, or a list of those.
    if not isinstance(given, (list, tuple)):
        given = [given]
    specs = []
    for end in given:
        if isinstance(end, str):
            end = _wrap(_call("find", end, None))
        if isinstance(end, Device):
            end = make(end)
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
        self.kind = info["kind"]    # "computer", "antenna", "interface", "input", "output", "bus",
        #                             or one of the last four after "fluid_" or "energy_"
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


class Port(Device):
    """A plate on a cable that points at a container: an input, an output or a bus."""

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


class _Reads:
    # For plates that can see what their target holds.
    def contents(self, face=None):
        """What the target holds, as seen through a face (or all of it, with no face)."""
        return _call("contents", self.mac, face)

    def count(self, item=None, face=None, slot=None):
        """How many of an item the target holds: chest.count("coal"). With no item, how
        many of everything. Narrow it with face= or slot=, or give a Face or a Slot in
        place of the item: chest.count(Face.BOTTOM), chest.count(Slot(2)). For fluid the
        answer is in millibuckets."""
        item, face, slot = _aim(item, face, slot)
        return _call("count", self.mac, item, face, slot)


class Interface(Device, _Gives, _Takes):
    """Filtered storage: the network's chest. A source and a destination for move()."""

    def contents(self):
        """What it holds: a list of {"slot": 0, "item": "minecraft:coal", "count": 12}."""
        return _call("contents", self.mac, None)

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


class Input(Port, _Reads, _Gives):
    """Takes items out of the container it points at. A source for move()."""


class Output(Port, _Takes):
    """Puts items into the container it points at. A destination for move()."""


class _ScreenEnd(Destination):
    # One slot of an open screen, as an end of a move.
    def _spec(self):
        spec = Source._spec(self)
        spec["screen"] = True
        return spec


class Screen:
    """The screen of the block a bus points at, opened as a player would open it. Its
    slots are numbered as the screen numbers them (the probe's Slots mode shows them).

    Use it in a with block, so it is closed even if the program stops with an error:

        with table.open_screen() as screen:
            screen.put(Slot(1), "oak_planks", sources=store)
            screen.take(Slot(0), store)

    Items exist in a screen only while it is open. When it closes, whatever the block hands
    back goes to an interface; anything with nowhere to go is dropped at the bus."""

    def __init__(self, bus, slots):
        self.bus = bus
        self._slots = [entry["slot"] for entry in slots]
        self.open = True

    def slots(self):
        """The numbers of the screen's own slots."""
        return list(self._slots)

    def contents(self):
        """What its slots hold now: a list of {"slot": 0, "item": "minecraft:stick", "count": 4}."""
        return _call("screen_contents", self.bus.mac)

    def source(self, slot, only=None, without=None):
        """One slot of this screen as a source, for move()."""
        return _ScreenEnd(self.bus, only, without, None, slot)

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

    def press(self, button):
        """Presses one of the screen's buttons, as a player clicking it. A stonecutter's or a
        loom's choices are buttons 0, 1, 2... in the order the screen shows them, for what is
        in its slots now. Returns False if the screen refused it. Some screens accept any
        number, so look at the product slot to see what was chosen."""
        return _call("screen_button", self.bus.mac, button)

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
    """An input and an output in one: a source and a destination for move()."""

    def open_screen(self):
        """Opens the screen of the block it points at, for blocks whose slots are only on
        their screen (a crafting table) or that offer nothing else. Returns a Screen.
        The bus acts for its owner, so it can open what its owner could."""
        return Screen(self, _call("screen_open", self.mac))


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

    ingredients and results are ordinary dictionaries, keyed by slot number, and sources
    and destination are ordinary attributes: read them or change them as you like."""

    def __init__(self, sources=None, destination=None):
        self.sources = sources            # one source or a list, as move() takes
        self.destination = destination    # one destination or a list
        self.ingredients = {}             # slot number -> {"what": name or filter, "amount": per run}
        self.results = {}                 # slot number -> {"what": name or None, "amount": per run or None, "destination": or None}

    def ingredient(self, what, slot, amount=1):
        """Adds an ingredient: this name or filter, into this slot, this many per run."""
        self.ingredients[_aim(None, None, slot)[2]] = {"what": what, "amount": amount}
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
        item name to how many. Each run lays the ingredients into their slots and takes
        the results out. If a run can't be done it raises NetworkError; ingredients
        already laid out go back when the screen closes. wait=True pauses for missing
        ingredients instead (a number waits that many seconds)."""
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
            shown = {entry["slot"]: entry for entry in screen.contents()}
            for slot in sorted(self.results):
                line = self.results[slot]
                there = shown.get(slot)
                if there is None:
                    raise NetworkError("Run %d made nothing in slot %d. Is the recipe right for this block?" % (run + 1, slot))
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


class FluidInterface(Device, _Gives, _Takes):
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


class _ReadsFluid:
    def contents(self, face=None):
        """The target's tanks, as seen through a face (or all of them, with no face)."""
        return _call("contents", self.mac, face)

    def amount(self, fluid=None, face=None, slot=None):
        """How many millibuckets of a fluid the target holds (or of everything). Narrow it
        with face= or slot= (a tank's number), or give a Face or a Slot in place of the fluid."""
        fluid, face, slot = _aim(fluid, face, slot)
        return _call("count", self.mac, fluid, face, slot)


class FluidInput(Port, _ReadsFluid, _Gives):
    """Takes fluid out of the tank it points at. A source for move()."""


class FluidOutput(Port, _Takes):
    """Puts fluid into the tank it points at. A destination for move()."""


class FluidBus(Port, _ReadsFluid, _Gives, _Takes):
    """A fluid input and a fluid output in one."""


# ---- energy: amounts are in FE, the unit mods share (also called RF) ----


class EnergyInterface(Device, _Gives, _Takes):
    """The network's energy store: it holds 2,000,000 FE."""

    @property
    def stored(self):
        """How much energy it holds now."""
        return _call("energy", self.mac, None)["stored"]

    @property
    def capacity(self):
        """The most it can hold."""
        return self._info["capacity"]


class _ReadsEnergy:
    def stored(self, face=None):
        """How much energy the target holds."""
        return _call("energy", self.mac, face)["stored"]

    def capacity(self, face=None):
        """The most the target can hold."""
        return _call("energy", self.mac, face)["capacity"]


class EnergyInput(Port, _ReadsEnergy, _Gives):
    """Takes energy out of the machine it points at. A source for move()."""


class EnergyOutput(Port, _Takes):
    """Puts energy into the machine it points at. A destination for move()."""


class EnergyBus(Port, _ReadsEnergy, _Gives, _Takes):
    """An energy input and an energy output in one."""


class ExperienceInterface(Device, _Gives, _Takes):
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


_KINDS = {"interface": Interface, "input": Input, "output": Output, "bus": Bus,
          "fluid_interface": FluidInterface, "fluid_input": FluidInput, "fluid_output": FluidOutput, "fluid_bus": FluidBus,
          "energy_interface": EnergyInterface, "energy_input": EnergyInput, "energy_output": EnergyOutput,
          "energy_bus": EnergyBus, "experience_interface": ExperienceInterface}


def _wrap(info):
    return _KINDS.get(info["kind"], Device)(info)


class Network:
    """The cable network this computer is on."""

    def devices(self, kind=None):
        """Every device on the network (or every one of a kind), in address order."""
        return [_wrap(i) for i in _call("devices") if kind is None or i["kind"] == kind]

    def device(self, key):
        """A device by name, IP address or hardware address."""
        return _wrap(_call("find", key, None))

    def interface(self, key=None):
        """An interface by name or address; with no argument, the network's only interface."""
        return _wrap(_call("find", key, "interface"))

    def input(self, key=None):
        """An input by name or address; with no argument, the network's only input."""
        return _wrap(_call("find", key, "input"))

    def output(self, key=None):
        """An output by name or address; with no argument, the network's only output."""
        return _wrap(_call("find", key, "output"))

    def bus(self, key=None):
        """A bus by name or address; with no argument, the network's only bus."""
        return _wrap(_call("find", key, "bus"))

    def fluid_interface(self, key=None):
        """A fluid interface by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "fluid_interface"))

    def fluid_input(self, key=None):
        """A fluid input by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "fluid_input"))

    def fluid_output(self, key=None):
        """A fluid output by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "fluid_output"))

    def fluid_bus(self, key=None):
        """A fluid bus by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "fluid_bus"))

    def energy_interface(self, key=None):
        """An energy interface by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "energy_interface"))

    def energy_input(self, key=None):
        """An energy input by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "energy_input"))

    def energy_output(self, key=None):
        """An energy output by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "energy_output"))

    def energy_bus(self, key=None):
        """An energy bus by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "energy_bus"))

    def experience_interface(self, key=None):
        """An experience interface by name or address; with no argument, the network's only one."""
        return _wrap(_call("find", key, "experience_interface"))

    def interfaces(self):
        """Every interface on the network."""
        return self.devices("interface")

    def inputs(self):
        """Every input on the network."""
        return self.devices("input")

    def outputs(self):
        """Every output on the network."""
        return self.devices("output")

    def buses(self):
        """Every bus on the network."""
        return self.devices("bus")

    @property
    def me(self):
        """This computer."""
        return _wrap(_call("self"))


def network():
    """The cable network this computer is on. Ask it for its blocks:
    net.interface(), net.input(), net.output(), net.bus(), net.devices()."""
    return Network()


def _print_devices(address=None):
    # The shell's "devices" command; with an address, the devices behind another antenna.
    try:
        if address is not None:
            print("%-22s %-14s %-9s %s" % ("Address", "IP there", "Kind", "Name"))
            for d in _call("devices", address):
                print("%-22s %-14s %-9s %s" % ("%s:%d" % (d["external_ip"], d["port"]), d["ip"], d["kind"], d["name"]))
            return
        me = _call("self")
        print("%-14s %-18s %-9s %s" % ("IP address", "Hardware address", "Kind", "Name"))
        for d in _call("devices"):
            note = "  (this computer)" if d["mac"] == me["mac"] else ""
            print("%-14s %-18s %-9s %s%s" % (d["ip"], d["mac"], d["kind"], d["name"], note))
    except NetworkError as e:
        print(e)
