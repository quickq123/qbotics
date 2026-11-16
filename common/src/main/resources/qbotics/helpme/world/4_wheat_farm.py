# A wheat farm that looks after itself. A Block Sensor under the middle of a 9 x 9 field
# (the farmland one block above the sensor, the wheat two above), a storage holding seeds,
# and, if you like, a sign on the field to show how it is doing.
#
#    W W W W W W W W W      the wheat, two above the sensor
#    F F F F F F F F F      the farmland, one above
#            S              the sensor, with a cable to the computer
#
# Run it:  run 4_wheat_farm.py   (Ctrl+C stops it)
from logistics import network
import events
import env

SIGN = None            # where the sign is, from the sensor, e.g. (0, 2, 5); None for no sign

net = network()
farm = net.block_sensor()
store = net.storage("chest*")

# The box: 4 out each way, on the layer two above. Places are offsets from the sensor, so the
# program keeps working if the farm is rebuilt somewhere else.
farm.set_area(x=(-4, 4), y=2, z=(-4, 4))
if SIGN:
    # The sign is in the box too, a layer of its own: grow the box to take it in.
    farm.set_area(y=(2, SIGN[1]) if SIGN[1] > 2 else (SIGN[1], 2))

harvested = 0

def harvest(pos):
    global harvested
    got = farm.break_block(pos, to=store)            # crops break at once; all drops to the store
    harvested += got.get("minecraft:wheat", 0)
    farm.place(pos, "wheat_seeds", store)            # replant

def show():
    if SIGN:
        growing = len(farm.blocks("wheat"))
        farm.write_sign(SIGN, ["Wheat farm", "%d growing" % growing, "%d harvested" % harvested,
                               "day %s" % env.now()["day"]])

def ripened(change):
    # A change passes the filter if the block passes before or after it, so harvesting ripe
    # wheat is a change too (to air): act only on wheat that is ripe now.
    if change["block"] == "minecraft:wheat" and change["state"].get("age") == "7":
        harvest(change["pos"])
        show()

# What is ripe already, and empty farmland left by hand (on the wheat's layer, pos[1] == 2).
for spot in farm.blocks("wheat age=7"):
    harvest(spot["pos"])
for spot in farm.blocks("air"):
    if spot["pos"][1] == 2:
        farm.place(spot["pos"], "wheat_seeds", store)
show()

# Then, as each one ripens. Watching costs nothing while nothing changes: the game tells the
# sensor. A change has "pos", "block", "state", and what it "was".
events.on_change(farm, ripened, what="wheat age=7")
print("Farming. Harvested", harvested, "so far. Ctrl+C stops it.")
events.run()
