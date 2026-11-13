# Waiting for a chest to change. A chest with a bus named "inbox". Put things in, take things
# out: the program says what changed. Between changes it sleeps, which costs the game nothing.
# Run it:  run 5_watch.py   (Ctrl+C stops it)
from logistics import network

net = network()
inbox = net.bus("inbox")

print("Watching the inbox. Change what is in it.")

# A watch hands out one change at a time, waiting when there is none:
#   {"slot": 3, "item": "minecraft:coal", "was": 5, "now": 12}
for change in inbox.watch():
    difference = change["now"] - change["was"]
    if difference > 0:
        print("+", difference, change["item"])
    else:
        print("-", -difference, change["item"])

# A watch can be narrowed:  inbox.watch("#c:ores")  sees only ores.
# One loop like this is fine for one job. For several jobs at once (sort this chest, feed that
# furnace, report every minute), use events: see 6_sort.py and the factory folder.
