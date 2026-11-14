# Events: run code when something happens, instead of checking again and again (design doc §4).
"""Run code when something happens, instead of checking again and again.

Say what to do when, then call run():

    import events
    from logistics import network
    net = network()

    @events.every(5)
    def report():
        print("store holds", net.interface("store").count())

    events.on_pin(net.pin("button"), "rising", lambda e: print("pressed"))
    events.on_change(net.bus("chest"), lambda change: print(change["item"], change["now"]))
    events.on_message(lambda msg: print("from", msg.sender, ":", msg.data))
    events.on_chat(net.chat_interface(), lambda e: print(e.player, "said", e.message))
    events.after(60, events.stop)       # stop after a minute

    events.run()                        # until events.stop() or Ctrl+C

Each kind can be given its function directly, or used above a def as a decorator. A
function that raises an error stops run() with that error. Handlers belong to one
program: a program started with run starts with none."""
import json
import time
import _host
from logistics import NetworkError, Watch, _call, _mac

# What to do, set up before run().
_timers = []      # Timer objects
_pins = []        # (mac, what, function)
_watches = []     # (Watch, function)
_messages = []    # functions
_displays = []    # (mac, widget number, web event name or None, "press", "change", "touch" or "web", function)
_chats = []       # (mac, function): chat heard by a chat interface
_stopping = False
_waiting = None   # the request for the next event, while one is outstanding
# How often watched containers are looked at while running, in milliseconds.
_WATCH_EVERY = 250


class Timer:
    """A function run after some seconds, once or again and again. cancel() stops it."""

    def __init__(self, seconds, function, repeat):
        if seconds <= 0:
            raise ValueError("A timer needs a time above 0 seconds, not %r." % (seconds,))
        self.seconds = seconds
        self.function = function
        self.repeat = repeat
        self.active = True
        self._ms = int(seconds * 1000)
        self._due = time.ticks_add(time.ticks_ms(), self._ms)

    def cancel(self):
        """Stops the timer: it won't run again."""
        self.active = False

    def __repr__(self):
        return "<Timer every %s s>" % self.seconds if self.repeat else "<Timer after %s s>" % self.seconds


class Event:
    """Something that happened, as handed to a function: its details are attributes.
    A pin's event has .name (the pin's name), .event ("rising", "falling", "change" or
    "block"), .level (the signal now, 0 to 15) and .block (what the pin points at)."""

    def __init__(self, info):
        for key in info:
            setattr(self, key, info[key])

    def __repr__(self):
        return "<Event %s %s>" % (getattr(self, "type", ""), getattr(self, "event", ""))


def every(seconds, function=None):
    """Runs a function every so many seconds, starting that many seconds from now.
    Returns a Timer; its cancel() stops it. As a decorator: @events.every(5)"""
    if function is None:
        return lambda f: (every(seconds, f), f)[1]
    timer = Timer(seconds, function, True)
    _timers.append(timer)
    return timer


def after(seconds, function=None):
    """Runs a function once, so many seconds from now. Returns a Timer; its cancel()
    stops it first. As a decorator: @events.after(10)"""
    if function is None:
        return lambda f: (after(seconds, f), f)[1]
    timer = Timer(seconds, function, False)
    _timers.append(timer)
    return timer


def on_pin(pin, what="change", function=None):
    """Runs a function when a Logic Pin sees something: "rising" (its signal comes on),
    "falling" (goes off), "change" (its level changes at all) or "block" (the block it
    points at changes, as an observer would see). The function gets an Event.
    pin is a pin from net.pin(), or its name or address."""
    if function is None:
        return lambda f: (on_pin(pin, what, f), f)[1]
    if what not in ("rising", "falling", "change", "block"):
        raise NetworkError('on_pin() takes "rising", "falling", "change" or "block", not %r.' % (what,))
    _pins.append((_mac(pin), what, function))
    return function


def on_change(device, function=None, what=None):
    """Runs a function for each change to what a device holds: a chest behind a bus, an
    interface, a tank, an energy store. what= narrows it to some items or fluids. The
    function gets the change, as a watch gives it: {"slot": 3, "item": ..., "was": 5,
    "now": 12}. device can also be a Watch from device.watch()."""
    if function is None:
        return lambda f: (on_change(device, f, what), f)[1]
    watch = device if isinstance(device, Watch) else device.watch(what)
    _watches.append((watch, function))
    return function


def on_message(function=None):
    """Runs a function for each message that arrives from another computer (see
    network.send). The function gets the message: .sender and .data. While run() is
    going, messages come here rather than to network.receive()."""
    if function is None:
        return lambda f: (on_message(f), f)[1]
    _messages.append(function)
    return function


def on_chat(chat, function=None):
    """Runs a function for each message a player says in chat, as a chat interface hears
    it. The function gets an Event: .player (who said it), .message, .uuid (the player's)
    and .name (the interface's). Commands players type are not chat and don't come here.
    chat is a chat interface from net.chat_interface(), or its name or address."""
    if function is None:
        return lambda f: (on_chat(chat, f), f)[1]
    _chats.append((_mac(chat), function))
    return function


def _on_display(mac, widget, what, function):
    # Called by display's on_press, on_change and on_touch.
    _displays.append((mac, widget, what, function))


def on_touch(display, function=None):
    """Runs a function when a player touches a text monitor or a control panel. The same as
    display.on_touch(function)."""
    if function is None:
        return lambda f: (on_touch(display, f), f)[1]
    _on_display(_mac(display), None, "touch", function)
    return function


def stop():
    """Makes run() return, once whatever is running now has finished."""
    global _stopping
    _stopping = True


def clear():
    """Forgets every timer and every function given to on_pin, on_change and on_message."""
    del _timers[:]
    del _pins[:]
    del _watches[:]
    del _messages[:]
    del _displays[:]
    del _chats[:]


def _next_event(wait_ms):
    # The next event the game has pushed, or None if none comes within wait_ms.
    global _waiting
    if _waiting is None:
        _waiting = _host.submit('["event"]')
    reply = _host.wait(_waiting, max(0, wait_ms))
    if reply is None:
        return None
    _waiting = None
    if not reply.startswith("{"):
        raise NetworkError(reply)
    return json.loads(reply)["r"]


def _dispatch(info):
    kind = info.get("type")
    if kind == "pin":
        for mac, what, function in _pins:
            if info["mac"] == mac and info["event"] == what:
                function(Event(info))
    elif kind == "panel" or kind == "touch":
        for mac, widget, what, function in _displays:
            if info["mac"] == mac and (what == "touch" or (widget == info["widget"] and what == info["event"])):
                function(Event(info))
    elif kind == "web":
        for mac, widget, what, function in _displays:
            if info["mac"] == mac and (what == "touch" or (what == "web" and widget in (None, info["event"], info["id"]))):
                function(Event(info))
    elif kind == "chat":
        for mac, function in _chats:
            if info["mac"] == mac:
                function(Event(info))
    elif kind == "message":
        from network import Message
        message = Message(info["sender"], json.loads(info["data"]))
        for function in _messages:
            function(message)


def run():
    """Handles events until stop() is called or Ctrl+C is pressed: runs timers when they
    are due, and the functions given to on_pin, on_change and on_message as things happen.
    It sleeps in between, so a waiting program costs the game nothing."""
    global _stopping, _waiting
    if not (_timers or _pins or _watches or _messages or _displays or _chats):
        raise NetworkError("There is nothing to wait for. Set up something first: events.every(), "
                           "events.on_pin(), events.on_change(), events.on_message(), events.on_chat(), or a "
                           "display's on_press, on_change or on_touch.")
    _stopping = False
    _waiting = None
    session = _call("events_start", bool(_messages))
    signed = []
    for mac, what, function in _pins:
        if mac not in signed:
            _call("pin_listen", mac, session)
            signed.append(mac)
    for mac, widget, what, function in _displays:
        if mac not in signed:
            _call("display_listen", mac, session)
            signed.append(mac)
    for mac, function in _chats:
        if mac not in signed:
            _call("chat_listen", mac, session)
            signed.append(mac)
    next_look = time.ticks_ms()
    try:
        while not _stopping:
            now = time.ticks_ms()
            # Timers that are due.
            for timer in list(_timers):
                if timer.active and time.ticks_diff(now, timer._due) >= 0:
                    if timer.repeat:
                        timer._due = time.ticks_add(timer._due, timer._ms)
                        if time.ticks_diff(now, timer._due) >= 0:
                            timer._due = time.ticks_add(now, timer._ms)   # fell behind: no rush to catch up
                    else:
                        timer.active = False
                    timer.function()
                    if _stopping:
                        return
            _timers[:] = [timer for timer in _timers if timer.active]
            # Watched containers.
            if _watches and time.ticks_diff(now, next_look) >= 0:
                next_look = time.ticks_add(now, _WATCH_EVERY)
                for watch, function in _watches:
                    for change in watch.changes():
                        function(change)
                        if _stopping:
                            return
            if not (_timers or _pins or _watches or _messages or _displays or _chats):
                return   # nothing is left to wait for
            # Sleep until the next timer, the next look, or an event the game pushes.
            wait = 1000
            for timer in _timers:
                wait = min(wait, time.ticks_diff(timer._due, time.ticks_ms()))
            if _watches:
                wait = min(wait, time.ticks_diff(next_look, time.ticks_ms()))
            info = _next_event(wait)
            while info is not None:
                _dispatch(info)
                if _stopping:
                    return
                info = _next_event(0)
    finally:
        _stopping = False
        # Messages go back to network.receive(), and pins stop sending.
        _call("events_start", False)
        _waiting = None
