# Faces and slots: smelting. A chest with a bus named "store" (with raw iron, or any ore, and
# coal in it) and a furnace with a bus named "furnace".
# Run it:  run 4_furnace.py
import time
from logistics import network, move, Face, Slot

net = network()
store = net.bus("store")
furnace = net.bus("furnace")

ORE = "#c:raw_materials"      # raw iron, raw gold, raw copper...
FUEL = "coal | charcoal"

# A furnace treats its sides differently, as with hoppers: the TOP takes what to smelt, a side
# takes fuel, the BOTTOM gives what it made. Each face is named from the bus's point of view
# (reference section 5.1).
move(store, furnace.destination(face=Face.TOP), ORE, 8)
move(store, furnace.destination(face=Face.LEFT), FUEL, 2)

# Slots do the same, by number: a furnace's slot 0 is the ingredient, 1 the fuel, 2 the result.
# These two lines would do what the two above did:
#   move(store, furnace.destination(slot=Slot(0)), ORE, 8)
#   move(store, furnace.destination(slot=Slot(1)), FUEL, 2)
# Faces work on most modded furnaces, whose slot numbers can differ. The Network Probe's Slots
# mode shows any block's slot numbers.

print("Smelting...")
for second in range(120):                       # two minutes at most
    if furnace.count(Face.TOP) == 0:            # nothing left to smelt
        break
    time.sleep(1)

# What it made, out of the bottom and back to the store.
print("Collected", move(furnace.source(face=Face.BOTTOM), store), "items")
