# Sorts the inbox: each item goes to the first chest whose rule takes it.
# Run it:  run start.py

from logistics import network, move

INBOX = "inbox"    # CHANGE ME: where things arrive

# CHANGE ME: one line per chest. The bus's name, then what that chest takes.
# The last one, with None, takes everything left over.
RULES = [
    ("ores", ["#c:ores", "#c:raw_materials", "#c:ingots"]),
    ("food", "#c:foods"),
    ("store", None),
]

net = network()
chests = []
for name, takes in RULES:
    chests.append(net.bus(name).destination(only=takes))

moved = move(net.bus(INBOX), chests)
print("Sorted", moved, "items")
