# What the world is like where this computer is (docs/reference.md section 7k2).
#
#     import env
#     r = env.now()
#     print(r["clock"], r["phase"], r["weather"], r["biome"])
#
#     for change in env.watch(["phase", "dark"]):      # sleeps until night falls, say
#         if change["what"] == "dark" and change["now"]:
#             chat.say("Dark outside: monsters can spawn.")
#
# "Here" is where the computer is: a placed computer's block, a Qbot where it stands now, a
# laptop the player carrying it.
import time
from logistics import _call, NetworkError


def now():
    """Everything at once, as a dict:
        pos        [x, y, z]: where the computer is (a laptop: its player)
        dimension  "minecraft:overworld"
        has_time   False where time stands still (the Nether, the End); then the time keys are None
        time       0 to 23999, ticks into the day (0 is just after sunrise)
        clock      "14:30", as a clock in the game would show it
        phase      "sunrise", "day", "sunset" or "night"
        day        days since the world began
        moon       0 to 7 (0 is full), and moon_name: "full_moon", "waning_gibbous", ...
        dark       True when it is dark enough outside for monsters to spawn in the open
        weather    "clear", "rain" or "thunder", for the whole world
        here       what falls here: "rain", "snow" or "none" (clear, a desert, a roof overhead)
        sky        True if the sky is open above
        biome      "minecraft:plains"
        light      {"block": 0-15, "sky": 0-15, "now": 0-15}: from torches, from the sky, and both as they stand now"""
    return _call("env")


def position():
    """Where this computer is: (x, y, z). A laptop's is its player's."""
    return tuple(now()["pos"])


def is_day():
    """True from sunrise to sunset."""
    return now()["phase"] in ("sunrise", "day")


def is_dark():
    """True when it is dark enough outside for monsters to spawn in the open."""
    return now()["dark"]


# What watch() looks for unless told otherwise.
_WATCHED = ("phase", "dark", "weather", "here", "biome", "dimension")


class Watch:
    """Changes in the environment, as they come. Each change is a dict:
        {"what": "phase", "was": "sunset", "now": "night", "env": {...everything now...}}
    It looks again every `every` seconds (one small call; nothing is scanned)."""

    def __init__(self, what=None, every=1.0):
        if what is None:
            what = _WATCHED
        elif isinstance(what, str):
            what = (what,)
        self.what = tuple(what)
        self.every = every
        self._last = now()

    def changes(self):
        """What has changed since the last look, straight away: a list, empty if nothing has."""
        current = now()
        found = []
        for key in self.what:
            if self._last.get(key) != current.get(key):
                found.append({"what": key, "was": self._last.get(key), "now": current.get(key), "env": current})
        self._last = current
        return found

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
        while True:
            for change in self.wait():
                yield change

    def close(self):
        """Nothing to free: here for the same shape as other watches."""


def watch(what=None, every=1.0):
    """Watches the environment for changes: what is one key or a list of them ("phase",
    "dark", "weather", "here", "biome", "dimension", "day", "moon", "sky", ...); with none,
    the day's phase, darkness, weather, what falls here, biome and dimension.

        for change in env.watch("dark"):
            print("dark now" if change["now"] else "light again")"""
    return Watch(what, every)
