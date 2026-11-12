# Displays: Text Monitors, Control Panels and Web Panels on the network (design doc §10).
"""Text Monitors, Control Panels and Web Panels: screens on the network that programs draw on.

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

    web = net.web_panel("factory")       # a Web Panel: HTML and CSS
    web.show("/app/index.html")          # its page; {{iron}} in it shows web.state["iron"]
    web.state["iron"] = 128

    @web.on("smelt")                     # <button data-event="smelt">
    def smelt(event):
        print(event.player, "pressed smelt")

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


THEMES = ("dark", "light", "industrial", "blueprint", "terminal")


class Panel(_Display):
    """A Control Panel: widgets on a grid of cells, four to a block each way. at=(x, y) is a
    widget's top-left cell (0, 0 is the panel's top left) and span=(w, h) its size in cells.
    panel.theme sets its look: one of display.THEMES."""

    @property
    def theme(self):
        return _draw(self, "theme", None)

    @theme.setter
    def theme(self, name):
        _draw(self, "theme", name)

    def dial(self, label, min=0, max=100, at=(0, 0), span=(2, 2), value=None, step=1, unit="", color=None):
        """A round knob from min to max: players click around it to turn it. .value reads or
        sets it; on_change runs when a player turns it."""
        return self._add("dial", label=label, min=min, max=max, at=at, span=span,
                         value=min if value is None else value, step=step, unit=unit, color=color)

    def number(self, label, min=0, max=100, at=(0, 0), span=(3, 1), value=0, step=1, unit="", color=None):
        """A number with - and + either side: each click takes a step off or adds one. .value
        reads or sets it; on_change runs when a player changes it."""
        return self._add("number", label=label, min=min, max=max, at=at, span=span, value=value, step=step,
                         unit=unit, color=color)

    def item(self, label, item, at=(0, 0), span=(2, 2), count=0, color=None):
        """An item's picture, with a label and a count (.value; 0 shows none). Change the item
        with .set(item="minecraft:coal"). on_press runs when a player clicks it."""
        return self._add("item", label=label, item=item, at=at, span=span, value=count, color=color)

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


def _inline_css(html, folder):
    # <link rel="stylesheet" href="style.css"> becomes <style> with the file's CSS: a panel
    # can't fetch anything itself, so the page is sent whole.
    out = []
    at = 0
    while True:
        start = html.find("<link", at)
        if start < 0:
            out.append(html[at:])
            return "".join(out)
        end = html.find(">", start)
        if end < 0:
            out.append(html[at:])
            return "".join(out)
        tag = html[start:end + 1]
        out.append(html[at:start])
        at = end + 1
        href = None
        for quote in ('"', "'"):
            i = tag.find("href=" + quote)
            if i >= 0:
                j = tag.find(quote, i + 6)
                href = tag[i + 6:j] if j > i else None
                break
        if "stylesheet" not in tag or not href:
            out.append(tag)
            continue
        path = href if href.startswith("/") else folder + "/" + href
        with open(path) as f:
            out.append("<style>\n" + f.read() + "\n</style>")


def _inline_scripts(html, folder):
    # <script src="app.js"></script> becomes the script itself, read from beside the page.
    out = []
    at = 0
    while True:
        start = html.find("<script", at)
        if start < 0:
            out.append(html[at:])
            return "".join(out)
        end = html.find(">", start)
        close = html.find("</script>", end)
        if end < 0 or close < 0:
            out.append(html[at:])
            return "".join(out)
        tag = html[start:end + 1]
        src = None
        for quote in ('"', "'"):
            i = tag.find("src=" + quote)
            if i >= 0:
                j = tag.find(quote, i + 5)
                src = tag[i + 5:j] if j > i else None
                break
        out.append(html[at:start])
        if src:
            path = src if src.startswith("/") else folder + "/" + src
            with open(path) as f:
                out.append("<script>\n" + f.read() + "\n</script>")
        else:
            out.append(html[start:close + 9])
        at = close + 9


class _State:
    """A web panel's state: values the page shows where it says {{name}}. Setting one sends
    it to the panel (players see it at once); reading gives what was last set."""

    def __init__(self, panel):
        self._panel = panel
        self._values = None

    def _all(self):
        if self._values is None:
            self._values = _draw(self._panel, "get_state")
        return self._values

    def __getitem__(self, name):
        return self._all()[name]

    def get(self, name, default=None):
        return self._all().get(name, default)

    def __contains__(self, name):
        return name in self._all()

    def keys(self):
        return self._all().keys()

    def items(self):
        return self._all().items()

    def __setitem__(self, name, value):
        self.update({name: value})

    def __delitem__(self, name):
        self.update({name: None})

    def update(self, values=None, **more):
        """Changes several values at once (one update for the players): state.update(iron=5, gold=2)."""
        changes = dict(values or {})
        changes.update(more)
        _draw(self._panel, "state", changes)
        values = self._all()
        for name in changes:
            if changes[name] is None:
                values.pop(name, None)
            else:
                values[name] = changes[name]

    def __repr__(self):
        return "<state %r>" % (self._all(),)


class WebPanel(_Display):
    """A Web Panel: a page of HTML and CSS, drawn in each player's game. 128 CSS pixels to a
    block. The page shows the panel's state where it says {{name}} ({{{name}}} for HTML);
    clicks on elements with a data-event or an id reach on() and on_click()."""

    def __init__(self, info):
        _Display.__init__(self, info)
        self.state = _State(self)

    def size(self):
        """(width, height) in CSS pixels."""
        return tuple(_draw(self, "size"))

    def show(self, path):
        """Shows a page from the drive: index.html, with its <link rel="stylesheet"> files
        put in. The state is kept."""
        folder = path[:path.rfind("/")] if "/" in path else "."
        with open(path) as f:
            page = f.read()
        _draw(self, "page", _inline_scripts(_inline_css(page, folder or "/"), folder or "/"))

    def html(self, text):
        """Shows HTML given as text."""
        _draw(self, "page", text)

    def clear(self):
        """An empty page, and no state."""
        _draw(self, "clear")
        self.state._values = {}

    def image(self, name, picture):
        """Puts a picture (a Qbot's snapshot()) on the panel as <img src="image:name">.
        None takes it off. A panel holds up to 8 pictures."""
        _draw(self, "image", name, None if picture is None else picture.id)

    def on(self, name, function=None):
        """Runs a function when a player clicks an element with data-event="name" (or
        id="name"), types in a field named name, or the page's script calls
        panel.send("name", data). It gets an Event with .event, .id, .tag, .name, .value,
        .player, and .data (what the script sent). "error" hears the script's errors.
        Needs events.run(). As a decorator: @panel.on("smelt")"""
        import events
        if function is None:
            return lambda f: (self.on(name, f), f)[1]
        events._on_display(self.mac, name, "web", function)
        return function

    def on_click(self, function=None):
        """Runs a function for every click on an element with a data-event or an id."""
        import events
        if function is None:
            return lambda f: (self.on_click(f), f)[1]
        events._on_display(self.mac, None, "web", function)
        return function
