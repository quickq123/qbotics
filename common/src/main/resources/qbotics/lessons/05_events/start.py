# A sorter that keeps going: whatever arrives in the inbox is sorted.
# Run it:  run start.py     (Ctrl+C stops it)

from logistics import network, move
import events

INBOX = "inbox"    # CHANGE ME: where things arrive

RULES = [          # CHANGE ME: the same rules as lesson 4
    ("ores", ["#c:ores", "#c:raw_materials", "#c:ingots"]),
    ("food", "#c:foods"),
    ("store", None),
]

net = network()
inbox = net.bus(INBOX)
chests = []
for name, takes in RULES:
    chests.append(net.bus(name).destination(only=takes))

def sort(change=None):
    moved = move(inbox, chests)
    if moved:
        print("Sorted", moved, "items")

sort()                            # what is in the inbox already
events.on_change(inbox, sort)     # then every time it changes
print("Sorting. Drop things in the inbox. Ctrl+C stops it.")
events.run()
