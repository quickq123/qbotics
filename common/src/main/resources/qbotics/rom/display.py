# Displays: Text Monitors and Control Panels on the network (design doc §10).
"""Text Monitors and Control Panels: screens on the network that programs draw on.

    from logistics import network
    net = network()

    mon = net.monitor("status")          # a Text Monitor, by name
    mon.clear()
    mon.print("Storage report")          # like print(), wrapping and scrolling
    mon.write_at(0, 2, "Iron: 512", "yellow")

    panel = net.panel("reactor")         # a Control Panel
    panel.clear()
    pump = panel.toggle("Pump", at=(0, 0), span=(2, 1))
    speed = panel.slider("Speed", 0, 100, at=(0, 1), span=(4, 1))
    power = panel.gauge("Power", 0, 1000, unit="FE", at=(0, 2), span=(4, 1))
    power.value = 640

Drawing happens at once (no waiting for the game's next tick). A player right-clicks a
screen to touch it; sneak and right-click with an empty hand for a close-up view. To act
on presses and touches, give functions to on_press, on_change or on_touch, then call
events.run()."""
import json
import _host
from logistics import Device, NetworkError, _call

COLORS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")


def _draw(display, method, *args):
    # A drawing call, carried out at once. A handle that stopped working (the screen was
    # rebuilt, or the chunk reloaded) is replaced once, by looking the display up again.
    for attempt in (0, 1):
        if display._handle is None:
            display._handle = _call("display_open", display.mac)["handle"]
        reply = _host.wait(_host.submit(json.dumps(["draw", display._handle, method] + list(args))))
        if reply is None:
            raise NetworkError("the call was interrupted")
        if not reply.startswith("{"):
            raise NetworkError(reply)
        reply = json.loads(reply)
        if reply.get("gone") and attempt == 0:
            display._handle = None
            continue
        if "e" in reply:
            raise NetworkError(reply["e"])
        return reply["r"]


class _Display(Device):
    def __init__(self, info):
        Device.__init__(self, info)
        self._handle = None

    def size(self):
        """(columns, rows): characters on a monitor, grid cells on a panel."""
        return tuple(_draw(self, "size"))

    def on_touch(self, function=None):
        """Runs a function when a player touches the screen; it gets an Event with .player.
        On a monitor it also has .x and .y, the character cell. On a panel it runs for every
        press and change, with .widget (the widget's number), .label, .event and .value.
        Needs events.run()."""
        import events
        if function is None:
            return lambda f: (self.on_touch(f), f)[1]
        events._on_display(self.mac, None, "touch", function)
        return function


class Monitor(_Display):
    """A Text Monitor: a grid of characters, each with its own text and background colour.
    Colours are names (see display.COLORS) or numbers 0-15."""

    def print(self, *things, sep=" "):
        """Writes a line at the cursor, as print() does: long lines wrap, and the screen
        scrolls up when the cursor goes past the bottom."""
        _draw(self, "print", sep.join(str(thing) for thing in things))

    def write(self, text):
        """Writes text at the cursor and moves the cursor on, without wrapping: what runs off
        the right edge is cut. A newline ("\\n") goes to the start of the next row."""
        _draw(self, "write", str(text))

    def write_at(self, x, y, text, color=None, background=None):
        """Writes text at column x, row y (0, 0 is the top left), in the given colours or the
        current ones. The cursor doesn't move."""
        _draw(self, "write_at", x, y, str(text), color, background)

    def set_cursor(self, x, y):
        """Moves the cursor to column x, row y."""
        _draw(self, "cursor", x, y)

    def cursor(self):
        """Where the cursor is: (x, y)."""
        return tuple(_draw(self, "get_cursor"))

    def color(self, text=None, background=None):
        """Sets the colours that writing uses from now on. None leaves one as it is."""
        _draw(self, "color", text, background)

    def clear(self, background=None):
        """Blanks the screen (in a background colour, if given) and puts the cursor at the top left."""
        _draw(self, "clear", background)

    def clear_line(self, y=None):
        """Blanks one row: the cursor's, or row y."""
        _draw(self, "clear_line", y)

    def fill(self, x, y, width, height, background, char=" "):
        """Fills a rectangle of cells with a background colour (and a character)."""
        _draw(self, "fill", x, y, width, height, background, char)

    def scroll(self, lines=1):
        """Moves everything up that many rows (down, if negative)."""
        _draw(self, "scroll", lines)

    @property
    def scale(self):
        """The text size: 1 is 15 characters by 10 rows to a block; 0.5 fits twice as many
        each way; up to 5, in steps of 0.5. Changing it keeps what fits."""
        return _draw(self, "get_scale")

    @scale.setter
    def scale(self, value):
        _draw(self, "scale", value)


class Widget:
    """A widget on a Control Panel. Its value (a number), label and colour can be read and
    changed; remove() takes it off the panel."""

    def __init__(self, panel, number, kind, label=""):
        self.panel = panel
        self.number = number
        self.kind = kind
        self._label = label

    @property
    def value(self):
        return _draw(self.panel, "get", self.number)

    @value.setter
    def value(self, value):
        _draw(self.panel, "set", self.number, {"value": value})

    @property
    def on(self):
        """For a toggle or a lamp: True when on."""
        return self.value != 0

    @on.setter
    def on(self, on):
        self.value = bool(on)

    def set(self, **fields):
        """Changes several things at once: label, color, value, min, max, step, unit, at, span."""
        _draw(self.panel, "set", self.number, _spec(fields))

    @property
    def label(self):
        return self._label

    @label.setter
    def label(self, text):
        self.set(label=str(text))
        self._label = str(text)

    def remove(self):
        """Takes the widget off the panel."""
        _draw(self.panel, "remove", self.number)

    def on_press(self, function=None):
        """Runs a function when a player presses this button. It gets an Event with
        .player. Needs events.run(). Usable as a decorator."""
        import events
        if function is None:
            return lambda f: (self.on_press(f), f)[1]
        events._on_display(self.panel.mac, self.number, "press", function)
        return function

    def on_change(self, function=None):
        """Runs a function when a player flips this toggle or moves this slider. It gets an
        Event with .value (the new value) and .player. Needs events.run()."""
        import events
        if function is None:
            return lambda f: (self.on_change(f), f)[1]
        events._on_display(self.panel.mac, self.number, "change", function)
        return function

    def __repr__(self):
        return "<%s %d on %r>" % (self.kind, self.number, self.panel)


class Graph(Widget):
    def add(self, value):
        """Adds a point to the graph: it keeps the last 60."""
        self.value = value

    def clear(self):
        """Forgets the graph's points."""
        _draw(self.panel, "clear_graph", self.number)


def _spec(fields):
    # The widget description the panel takes: at=(x, y) and span=(w, h) become x, y, w, h.
    spec = {}
    for key in fields:
        value = fields[key]
        if key == "at":
            spec["x"], spec["y"] = value
        elif key == "span":
            spec["w"], spec["h"] = value
        elif key == "on":
            spec["value"] = bool(value)
        else:
            spec[key] = value
    return spec


class Panel(_Display):
    """A Control Panel: widgets on a grid of cells, four to a block each way. at=(x, y) is a
    widget's top-left cell (0, 0 is the panel's top left) and span=(w, h) its size in cells."""

    def clear(self):
        """Takes every widget off the panel."""
        _draw(self, "clear")

    def _add(self, kind, cls=Widget, **fields):
        fields["type"] = kind
        return cls(self, _draw(self, "add", _spec(fields)), kind, str(fields.get("label", "")))

    def button(self, label, at=(0, 0), span=(2, 1), color=None):
        """A button: on_press runs a function when a player presses it."""
        return self._add("button", label=label, at=at, span=span, color=color)

    def toggle(self, label, at=(0, 0), span=(2, 1), on=False, color=None):
        """A switch, on or off: .on reads or sets it; on_change runs when a player flips it."""
        return self._add("toggle", label=label, at=at, span=span, on=on, color=color)

    def slider(self, label, min=0, max=100, at=(0, 0), span=(4, 1), value=None, step=1, unit="", color=None):
        """A slider from min to max, moving in steps (0 for any value). Taller than wide, it
        stands upright. .value reads or sets it; on_change runs when a player moves it."""
        return self._add("slider", label=label, min=min, max=max, at=at, span=span,
                         value=min if value is None else value, step=step, unit=unit, color=color)

    def gauge(self, label, min=0, max=100, at=(0, 0), span=(4, 1), value=0, unit="", color=None):
        """A bar showing a value between min and max. Taller than wide, it fills upwards."""
        return self._add("gauge", label=label, min=min, max=max, at=at, span=span, value=value, unit=unit, color=color)

    def lamp(self, label, at=(0, 0), span=(2, 1), on=False, color="red"):
        """A light, on or off: .on reads or sets it."""
        return self._add("lamp", label=label, at=at, span=span, on=on, color=color)

    def label(self, text, at=(0, 0), span=(4, 1), color=None):
        """Text. Given two or more rows of height, short text is drawn large."""
        return self._add("label", label=text, at=at, span=span, color=color)

    def readout(self, label, at=(0, 0), span=(2, 2), value=0, unit="", color=None):
        """A value shown as a number, with its label above."""
        return self._add("readout", label=label, at=at, span=span, value=value, unit=unit, color=color)

    def graph(self, label, at=(0, 0), span=(4, 2), min=0, max=0, unit="", color=None):
        """A graph of the last 60 values given to add(). With min and max equal, it scales
        itself to the values."""
        return self._add("graph", Graph, label=label, at=at, span=span, min=min, max=max, unit=unit, color=color)
