# Where this computer is: the time, the weather, the biome. Every computer has env; a Qbot
# reads where it stands, a laptop where its player is. Nothing to place.
# Run it:  run 7_day_night.py   (Ctrl+C stops it)
import env
import events

r = env.now()
print("At", r["pos"], "in", r["biome"], "(" + r["dimension"] + ")")
if r["has_time"]:
    print("Day %d, %s (%s), %s" % (r["day"], r["clock"], r["phase"], r["moon_name"]))
print("Weather:", r["weather"], "- here:", r["here"], "- open sky:", r["sky"])
print("Light:", r["light"]["now"], "- dark enough for monsters:", r["dark"])

# Doing something when it changes. A watch looks again every second (one small call).
# Each change: {"what": "phase", "was": "sunset", "now": "night", "env": {...}}
def changed(change):
    if change["what"] == "dark":
        print("Monsters can spawn outside now." if change["now"] else "Safe again: it is light.")
    else:
        print(change["what"], "is now", change["now"], "(was %s)" % change["was"])

# With no keys it watches the day's phase, darkness, weather, what falls here, biome and
# dimension. Name keys to watch others: env.watch(["day", "moon"]).
events.on_change(env.watch(), changed)

# The same without events, for a program that only waits for this:
#   for change in env.watch("dark"):
#       ...
print("Watching. Ctrl+C stops it.")
events.run()
