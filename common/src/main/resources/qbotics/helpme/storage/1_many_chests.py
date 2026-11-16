# Many chests as one. Chests with Item Serial Buses named "chest_1", "chest_2", ... (any
# number), and one more named "inbox" where things are dropped off.
# Run it:  run 1_many_chests.py
from logistics import network, move

net = network()
store = net.storage("chest*")       # every bus whose name starts with "chest", in name order

print("The storage is", len(store.devices()), "chests")
print("It holds", store.count(), "items;", store.free(), "of", store.slots(), "slots are empty")

# Everything in it, added up across the chests, the most first.
print("Most of all:")
for entry in store.contents()[:10]:
    print("  %5d  %s" % (entry["count"], entry["item"]))

# Counting takes names and filters, as everywhere: a tag, a pattern, a mod.
print("Ingots:", store.count("#c:ingots"), " Logs:", store.count("*_log"))

# Which chests hold something.
for spot in store.where("cobblestone"):
    print("Cobblestone:", spot["count"], "in", spot["device"].name)

# Putting away: each kind goes to a chest that already has some, before an empty one.
moved = move(net.bus("inbox"), store)
print("Put away", moved, "items from the inbox")

# Taking: from whichever chests have it. Here back to the inbox, to show it:
#   move(store, net.bus("inbox"), "torch", 16)
