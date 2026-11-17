# Moves items from one chest to another.
# Run it:  run start.py

from logistics import network, move

FROM = "inbox"     # CHANGE ME: the bus on the chest to take from
TO = "store"       # CHANGE ME: the bus on the chest to put into
WHAT = None        # CHANGE ME: an item, like "cobblestone" (all of it); None: everything

net = network()
source = net.bus(FROM)
destination = net.bus(TO)

moved = move(source, destination, WHAT)
print("Moved", moved, "items from", FROM, "to", TO)
print(FROM, "holds", source.count(), "and", TO, "holds", destination.count())
