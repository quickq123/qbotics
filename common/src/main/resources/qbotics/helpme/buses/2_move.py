# Moving items. Two chests, each with an Item Serial Bus: name them "inbox" and "store" with the
# Network Probe. Put some cobblestone, logs and anything else in the inbox, then run this.
# Run it:  run 2_move.py
from logistics import network, move

net = network()
inbox = net.bus("inbox")
store = net.bus("store")

# move(from, to, what, count) returns how many moved. Nothing to move is not an error: it is 0.

# One of a named item: give the count. (With no count, ALL of it moves.)
print("1 cobblestone:", move(inbox, store, "cobblestone", 1))

# A number of them. Fewer move if there aren't that many.
print("Up to 10 more:", move(inbox, store, "cobblestone", 10))

# A filter instead of a name. "#minecraft:logs" is a tag:
# every kind of log.
print("Every log:", move(inbox, store, "#minecraft:logs"))

# Nothing named: everything that is left.
print("Everything else:", move(inbox, store))

print("The store now holds", store.count(), "items; the inbox", inbox.count())
