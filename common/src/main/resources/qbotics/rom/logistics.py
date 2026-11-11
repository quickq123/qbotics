# Item logistics: the blocks cabled to this computer (design doc §11.0).
#
#     from logistics import network, Face
#     net = network()
#     furnace = net.bus("furnace")      # a bus plate on a cable, pointing at a furnace
#     furnace.insert("minecraft:raw_iron", 16, face=Face.TOP)
#
# Every call here is answered by the game on its next tick (a twentieth of a second).
import json
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


class Interface(Device):
    """Filtered storage: the network's chest. Inputs and buses deliver to it;
    outputs and buses draw from it."""

    def contents(self):
        """What it holds: a list of {"slot": 0, "item": "minecraft:coal", "count": 12}."""
        return _call("contents", self.mac, None)

    def count(self, item=None):
        """How many of an item it holds (or of everything, with no item)."""
        item = _full_name(item)
        return sum(e["count"] for e in self.contents() if item is None or e["item"] == item)

    def filters(self):
        """What each slot accepts: an item name, or None for a closed slot."""
        return _call("filters", self.mac)

    def set_slot_filter(self, slot, item):
        """Opens a slot for one kind of item. None closes it again."""
        _call("set_filter", self.mac, slot, item)

    @property
    def slots(self):
        """How many slots it has."""
        return self._info["slots"]


class Port(Device):
    """A plate on a cable that points at a container: an input, an output or a bus."""

    def set_face(self, face):
        """Sets the face used when a call doesn't name one."""
        self._update(_call("set_face", self.mac, face))

    @property
    def face(self):
        """The face used when a call doesn't name one."""
        return self._info["face"]

    @property
    def target(self):
        """The block it points at, e.g. "minecraft:furnace"."""
        return self.refresh()._info["target"]

    def _contents(self, face):
        return _call("contents", self.mac, face)

    def _extract(self, item, count, dest, face):
        return _call("extract", self.mac, item, count, _mac(dest), face)

    def _insert(self, item, count, source, face):
        return _call("insert", self.mac, item, count, _mac(source), face)


class Input(Port):
    """Takes items out of the container it points at, into the network's interfaces."""

    def contents(self, face=None):
        """What the target holds, as seen through a face (or all of it, with no face)."""
        return self._contents(face)

    def count(self, item=None, face=None):
        """How many of an item the target holds (or of everything, with no item)."""
        item = _full_name(item)
        return sum(e["count"] for e in self.contents(face) if item is None or e["item"] == item)

    def extract(self, item, count=1, dest=None, face=None):
        """Moves up to count of an item out of the target into an interface (any
        interface with a slot open for it, unless dest says which). Returns how many moved."""
        return self._extract(item, count, dest, face)


class Output(Port):
    """Puts items from the network's interfaces into the container it points at."""

    def insert(self, item, count=1, source=None, face=None):
        """Moves up to count of an item from an interface (any interface, unless source
        says which) into the target. Returns how many moved; 0 if none could."""
        return self._insert(item, count, source, face)


class Bus(Port):
    """An input and an output in one: reads its target, takes items out and puts items in."""

    def contents(self, face=None):
        """What the target holds, as seen through a face (or all of it, with no face)."""
        return self._contents(face)

    def count(self, item=None, face=None):
        """How many of an item the target holds (or of everything, with no item)."""
        item = _full_name(item)
        return sum(e["count"] for e in self.contents(face) if item is None or e["item"] == item)

    def extract(self, item, count=1, dest=None, face=None):
        """Moves up to count of an item out of the target into an interface (any
        interface with a slot open for it, unless dest says which). Returns how many moved."""
        return self._extract(item, count, dest, face)

    def insert(self, item, count=1, source=None, face=None):
        """Moves up to count of an item from an interface (any interface, unless source
        says which) into the target. Returns how many moved; 0 if none could."""
        return self._insert(item, count, source, face)


# ---- fluid: amounts are in millibuckets (mB); 1000 is one bucket ----


def _amount(tanks, fluid):
    fluid = _full_name(fluid)
    return sum(t["amount"] for t in tanks if fluid is None or t["fluid"] == fluid)


class FluidInterface(Device):
    """The network's fluid store: four tanks of four buckets (4000 mB) each. Fluid
    inputs and buses deliver to it; fluid outputs and buses draw from it."""

    def contents(self):
        """What each tank holds: a list of {"tank": 0, "fluid": "minecraft:water",
        "amount": 1000, "capacity": 4000}. An empty tank's fluid is None."""
        return _call("contents", self.mac, None)

    def amount(self, fluid=None):
        """How many millibuckets of a fluid it holds (or of everything, with no fluid)."""
        return _amount(self.contents(), fluid)


class FluidInput(Port):
    """Takes fluid out of the tank it points at, into the network's fluid interfaces."""

    def contents(self, face=None):
        """The target's tanks, as seen through a face (or all of them, with no face)."""
        return self._contents(face)

    def amount(self, fluid=None, face=None):
        """How many millibuckets of a fluid the target holds (or of everything)."""
        return _amount(self.contents(face), fluid)

    def extract(self, fluid, amount=1000, dest=None, face=None):
        """Moves up to an amount (in millibuckets; 1000 is a bucket) of a fluid out of the
        target into a fluid interface (any, unless dest says which). Returns how much moved."""
        return self._extract(_full_name(fluid), amount, dest, face)


class FluidOutput(Port):
    """Puts fluid from the network's fluid interfaces into the tank it points at."""

    def insert(self, fluid, amount=1000, source=None, face=None):
        """Moves up to an amount (in millibuckets; 1000 is a bucket) of a fluid from a fluid
        interface (any, unless source says which) into the target. Returns how much moved."""
        return self._insert(_full_name(fluid), amount, source, face)


class FluidBus(Port):
    """A fluid input and a fluid output in one."""

    def contents(self, face=None):
        """The target's tanks, as seen through a face (or all of them, with no face)."""
        return self._contents(face)

    def amount(self, fluid=None, face=None):
        """How many millibuckets of a fluid the target holds (or of everything)."""
        return _amount(self.contents(face), fluid)

    def extract(self, fluid, amount=1000, dest=None, face=None):
        """Moves up to an amount (in millibuckets; 1000 is a bucket) of a fluid out of the
        target into a fluid interface (any, unless dest says which). Returns how much moved."""
        return self._extract(_full_name(fluid), amount, dest, face)

    def insert(self, fluid, amount=1000, source=None, face=None):
        """Moves up to an amount (in millibuckets; 1000 is a bucket) of a fluid from a fluid
        interface (any, unless source says which) into the target. Returns how much moved."""
        return self._insert(_full_name(fluid), amount, source, face)


# ---- energy: amounts are in FE, the unit mods share (also called RF) ----


class EnergyInterface(Device):
    """The network's energy store: it holds 2,000,000 FE. Energy inputs and buses
    charge it; energy outputs and buses draw from it."""

    @property
    def stored(self):
        """How much energy it holds now."""
        return _call("energy", self.mac, None)["stored"]

    @property
    def capacity(self):
        """The most it can hold."""
        return self._info["capacity"]


class EnergyInput(Port):
    """Takes energy out of the machine it points at, into the network's energy interfaces."""

    def stored(self, face=None):
        """How much energy the target holds."""
        return _call("energy", self.mac, face)["stored"]

    def capacity(self, face=None):
        """The most the target can hold."""
        return _call("energy", self.mac, face)["capacity"]

    def extract(self, amount, dest=None, face=None):
        """Moves up to an amount of energy out of the target into an energy interface
        (any, unless dest says which). Returns how much moved."""
        return self._extract(None, amount, dest, face)


class EnergyOutput(Port):
    """Puts energy from the network's energy interfaces into the machine it points at."""

    def insert(self, amount, source=None, face=None):
        """Moves up to an amount of energy from an energy interface (any, unless source
        says which) into the target. Returns how much moved."""
        return self._insert(None, amount, source, face)


class EnergyBus(Port):
    """An energy input and an energy output in one."""

    def stored(self, face=None):
        """How much energy the target holds."""
        return _call("energy", self.mac, face)["stored"]

    def capacity(self, face=None):
        """The most the target can hold."""
        return _call("energy", self.mac, face)["capacity"]

    def extract(self, amount, dest=None, face=None):
        """Moves up to an amount of energy out of the target into an energy interface
        (any, unless dest says which). Returns how much moved."""
        return self._extract(None, amount, dest, face)

    def insert(self, amount, source=None, face=None):
        """Moves up to an amount of energy from an energy interface (any, unless source
        says which) into the target. Returns how much moved."""
        return self._insert(None, amount, source, face)


def _full_name(item):
    return item if item is None or ":" in item else "minecraft:" + item


_KINDS = {"interface": Interface, "input": Input, "output": Output, "bus": Bus,
          "fluid_interface": FluidInterface, "fluid_input": FluidInput, "fluid_output": FluidOutput, "fluid_bus": FluidBus,
          "energy_interface": EnergyInterface, "energy_input": EnergyInput, "energy_output": EnergyOutput,
          "energy_bus": EnergyBus}


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
