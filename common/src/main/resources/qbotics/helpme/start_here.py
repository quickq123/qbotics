# start_here.py: crafts torches from two storage chests and puts them back. The logistics
# module in one short program; hover over any call in the editor for its full help.
#
# Needs: two chests with Item Serial Buses named "storage_1" and "storage_2" (holding coal and
# sticks), and a crafting table with a bus named "crafter", all cabled to this computer.
# Name a bus by right-clicking it with the Network Probe.

from logistics import network, move, Face, Slot, Filter, Recipe

net = network()                              # everything cabled to this computer

# Devices are found by name (set with the probe), IP ("192.168.1.5") or hardware address.
# One lookup per kind: net.bus, net.fluid_bus, net.energy_bus, net.usb, net.interface...
chest_1 = net.bus("storage_1")
chest_2 = net.bus("storage_2")
table = net.bus("crafter")

# A storage: many containers used as one, chosen by name pattern.
store = net.storage("storage_*")
print("coal:", store.count("coal | charcoal"), " sticks:", store.count("stick"))

# Simple move: from sources to destinations. With no count, all that matches and fits moves.
move(chest_2, chest_1, "stick")

# In depth: an end can name a face of the block, a slot, and filters of what it handles.
fuel = Filter(only="coal | charcoal")
move(chest_1.source(slot=Slot(0), only=fuel), chest_2.destination(face=Face.NEAR), count=1)

# A crafting table's grid exists only on its screen, so the bus opens the screen and works
# its slots: 0 is the product, 1 to 9 the grid. A Recipe says which slot gets what.
torches = Recipe(sources=store, destination=store)
torches.ingredient(fuel, Slot(1)).ingredient("stick", Slot(4))    # coal above a stick
torches.result(Slot(0), "torch", 4)

runs = min(torches.max_runs(), 8)
if runs:
    with table.open_screen() as screen:      # closed at the end; leftovers go back
        print("made", torches.craft(runs, screen))

move(store, chest_1, "torch")                # gather every torch into storage_1
print("torches in storage_1:", chest_1.count("torch"))
