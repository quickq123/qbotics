# Looking after animals with an Entity Sensor. Put the sensor in the pen (right-click it to
# set its area to the pen's size), and give the storage (buses named "chest_...") wheat,
# buckets and shears.
# Run it:  run 1_animals.py
from logistics import network

net = network()
sensor = net.entity_sensor()
store = net.storage("chest*")

# What it sees, nearest first. Each entity's keys are attributes too: cow.health, cow.baby.
print("In the area (%d out, %d up and down):" % (sensor.radius, sensor.height))
for e in sensor.entities():
    print("  %-22s %4.1f blocks away" % (e.name, e.distance))

# Kinds are named as items are: "cow", "#minecraft:undead", "@minecraft", "-player".
bred = milked = sheared = 0
for cow in sensor.entities("cow"):
    if cow.baby:
        continue
    if cow.can_breed and cow.use("wheat", store):             # True if it did something
        bred += 1
    if cow.use("bucket", store, destination=store):           # the milk goes to the store
        milked += 1

for sheep in sensor.entities("sheep"):
    if not sheep.baby and not sheep.sheared and sheep.use("shears", store):
        sheared += 1                                          # the shears go back

print("Fed", bred, "cows, milked", milked, "and sheared", sheared, "sheep")

# The wool fell on the ground: pick it up.
print("Picked up", sensor.collect(store)["items"], "items")
