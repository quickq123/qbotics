# Reacting to a chest. A chest with an Item Serial Bus named "chest" (name it with the Network
# Probe). Put things in and take them out while it runs.
# Run it:  run 3_chest.py   (Ctrl+C stops it)
import events
from logistics import network

net = network()
chest = net.bus("chest")

# Called with each change: {"slot": 3, "item": "minecraft:coal", "was": 5, "now": 12}.
def changed(change):
    difference = change["now"] - change["was"]
    print("%+d %s (slot %d)" % (difference, change["item"], change["slot"]))

events.on_change(chest, changed)

# A second handler for the same chest, narrowed to some items with what=. Both run.
events.on_change(chest, lambda change: print("   that was fuel: now", change["now"]),
                 what="coal | charcoal")

# Any number of other events can run alongside: here, a timer.
@events.every(30)
def heartbeat():
    print("Still watching. The chest holds", chest.count(), "items.")

print("Watching the chest. Change what is in it.")
events.run()
