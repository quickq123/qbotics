# Lesson 18: Down on the Farm

**Earns:** *Down on the Farm*: harvest **and** replant a crop with a Block Sensor, **or** tend an
animal with an Entity Sensor. **You need:** a storage (lesson 10), and a **Block Sensor** under a
field of farmland, or an **Entity Sensor** in an animal pen.

## The idea

Sensors reach out into the world around them, and act as the player who placed them:

- A **Block Sensor** reads the blocks in a small box (up to 11 x 11 x 11), tells a program when
  they change, and breaks, places and uses items there.
- An **Entity Sensor** sees the creatures in a box around it (up to 41 x 41 x 41), and uses items
  on them, attacks and trades.

Right-click a sensor for its size, and **Show area** to see its box. Places near a Block Sensor
are offsets from it: `(0, 1, 0)` is the block above.

## Try it: the farm

Put the sensor under the middle of a 9 x 9 field: farmland one block above it, wheat two above.

    from logistics import network

    net = network()
    farm = net.block_sensor()
    store = net.storage("chest*")
    farm.set_area(x=(-4, 4), y=2, z=(-4, 4))        # the wheat's layer

    for spot in farm.blocks("wheat age=7"):          # what is ripe
        farm.break_block(spot["pos"], to=store)
        farm.place(spot["pos"], "wheat_seeds", store)

## Or: the pen

    pen = net.entity_sensor()
    for cow in pen.entities("cow"):
        if cow.can_breed:
            cow.use("wheat", store)                   # breeds it
        cow.use("bucket", store, destination=store)   # milks it

## Your turn

Make the farm look after itself: harvest each wheat the moment it ripens, with
`events.on_change(farm, ripened, what="wheat age=7")`, and run it from startup (lesson 15).

## New Python here

- `(dx, dy, dz)` in round brackets is a **tuple**: a list that can't change, here a position.
- `d.get("age")` reads a dictionary's value like `d["age"]`, but gives `None` instead of an error
  when it isn't there.
- `cow.can_breed`: a value belonging to an object (an **attribute**), read with a dot, no brackets.

## Hints

- A watch passes a change if the block passes **before or after**, so breaking ripe wheat is a
  change too: check `change["state"].get("age") == "7"` in the handler.
- A **Collector** near the field picks up whatever falls (`/helpme/world/5_collector.py`).

**More:** `/helpme/world` (`4_wheat_farm.py`, `1_animals.py`). **Manual:** sections 6.12 to 6.14.
