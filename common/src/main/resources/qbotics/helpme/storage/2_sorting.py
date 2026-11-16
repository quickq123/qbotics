# Sorting into chests with bus filters. Buses named:
#   "chest_ores", "chest_food"  chests for one sort of thing each
#   "chest_overflow"            takes anything that has nowhere else to go
#   more "chest_..." buses      each takes what you have put in it by hand
#   "inbox"                     where things are dropped off
# Run it once to set the filters: the buses keep them, so after that any program (or this
# one again) only has to move things into the storage.
# Run it:  run 2_sorting.py
from logistics import network, move

net = network()

# Each bus says what it lets into its chest. Names, tags, patterns and mods, as anywhere.
net.bus("chest_ores").set_filter(["#c:ores", "#c:raw_materials", "#c:ingots", "coal"])
net.bus("chest_food").set_filter("#c:foods")

# Every other chest with no filter yet takes what it already holds (matching), except the
# overflow, which keeps none. To start a chest for a new kind, put one in by hand.
store = net.storage("chest_*")
for bus in store.devices():
    if bus.filter is None and bus.name != "chest_overflow":
        bus.set_filter(matching=True)
    print(bus.name, "takes", bus.filter or "anything")

# One move sorts everything: each kind to the chest that takes it.
moved = move(net.bus("inbox"), store)
print("Sorted", moved, "items")

# Things put straight into a chest by hand may not belong there. tidy() moves them to where
# they do.
for stray in store.strays():
    print(stray["count"], "things in", stray["device"].name, "don't belong there")
print("Tidied", store.tidy(), "items")

# To sort for as long as the program runs, wait for the inbox to change (/helpme/events):
#   import events
#   inbox = net.bus("inbox")
#   events.on_change(inbox, lambda change: move(inbox, store))
#   events.run()
