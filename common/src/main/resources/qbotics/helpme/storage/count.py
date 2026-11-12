# What is in a chest. An Item Serial Bus is a plate on a cable that works the block it
# points at. Put a chest down, a cable beside it, and right-click the chest holding the bus.
# Run it:  run count.py
from logistics import network

CHEST = None          # the bus's name ("chest"), or None for the only bus on the network

net = network()
chest = net.bus(CHEST)
print("The bus points at", chest.target)

# contents() lists every slot that has something: {"slot", "item", "count"}.
totals = {}
for stack in chest.contents():
    totals[stack["item"]] = totals.get(stack["item"], 0) + stack["count"]

# Most first.
for item, count in sorted(totals.items(), key=lambda pair: -pair[1]):
    print("%6d  %s" % (count, item))
print("In all:", chest.count(), "items")

# count() also takes a name or a filter: one item, a tag, or a pattern.
print("Cobblestone:", chest.count("cobblestone"))
print("Logs of any wood:", chest.count("#minecraft:logs"))
print("Anything ending in _ingot:", chest.count("*_ingot"))
