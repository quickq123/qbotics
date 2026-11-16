# A Dropper Plate sends items out into the world, as a dropper does, or fires them as a
# dispenser does. A Dropper Plate on a cable, a storage ("chest_..." buses) holding
# cobblestone, bone meal and arrows, and for experience an Experience Interface.
# Run it:  run 6_dropper.py
from logistics import network

net = network()
spout = net.dropper()
store = net.storage("chest*")

# As a dropper: thrown out as one stack.
print("Threw", spout.drop("cobblestone", 16, sources=store), "cobblestone")

# As a dispenser, this once: arrows fly, bone meal grows the crop in front. What it hands
# back (an empty bucket) or had no use for goes back to the store.
print("Fired", spout.drop("arrow", 3, sources=store, dispense=True), "arrows")
print("Used", spout.drop("bone_meal", sources=store, dispense=True), "bone meal")

# spout.dispense = True     # from now on fire things as a dispenser does; the plate keeps it

# Experience: exactly so much, as orbs, from an Experience Interface (or liquid experience).
try:
    bank = net.experience_interface()
    points = spout.drop_xp(levels=5, sources=bank)     # what takes a player from level 0 to 5
    print("Sent out", points, "experience points")
except Exception as e:
    print("No experience sent:", e)
