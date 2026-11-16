# A Collector picks up what lies around it by itself, with no program. This one empties it
# into storage whenever it picks something up, so it never fills. A Collector and buses named
# "chest_..." on the same network.
# Run it:  run 5_collector.py   (Ctrl+C stops it)
from logistics import network, move
import events

net = network()
drops = net.collector()
store = net.storage("chest*")

print("Collecting %d blocks out and %d up and down." % (drops.radius, drops.height))
# drops.set_area(radius=12, height=4)     # its size, each 1 to 20 (or on its screen)

def empty(change=None):
    moved = move(drops, store)
    if moved:
        print("Put away", moved, "items")

empty()                               # what it holds already
events.on_change(drops, empty)        # then whenever it picks something up
events.run()
