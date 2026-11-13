# Crafting. A chest with a bus named "store" holding coal (or charcoal) and sticks, and a
# crafting table with a bus named "crafter". The program crafts torches at the table.
# Run it:  run 7_craft.py
from logistics import network, Slot, Recipe

net = network()
store = net.bus("store")
table = net.bus("crafter")

# A Recipe says which slot gets which ingredient. A crafting table's slots: 0 is the result,
# 1 to 9 the grid, left to right and top to bottom:
#    1 2 3
#    4 5 6
#    7 8 9
torches = Recipe(sources=store, destination=store)
torches.ingredient("coal | charcoal", Slot(1))     # coal on top
torches.ingredient("stick", Slot(4))               # the stick below it
torches.result(Slot(0), "torch", 4)                # each run makes 4 torches

runs = min(torches.max_runs(), 8)                  # as many as the store allows, up to 8
if runs == 0:
    print("The store needs coal (or charcoal) and sticks.")
else:
    # open_screen() works the table as a player would. "with" closes it at the end, putting
    # back anything left on the grid.
    with table.open_screen() as screen:
        made = torches.craft(runs, screen)
    print("Made", made)
