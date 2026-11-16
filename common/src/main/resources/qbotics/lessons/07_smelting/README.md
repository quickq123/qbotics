# Lesson 7: Hot Stuff

**Earns:** *Hot Stuff*: take what a furnace has smelted. **You need:** a furnace with an Item
Serial Bus on it (named `furnace`), and the `store` chest with raw iron (or any ore) and coal.

## The idea

A furnace has three slots, and a block's **faces** say which one you reach, as hoppers do:

| Face | Slot |
|---|---|
| `Face.TOP` | What to smelt (slot 0) |
| `Face.LEFT`, `Face.RIGHT`, ... a side | Fuel (slot 1) |
| `Face.BOTTOM` | What it made (slot 2) |

`bus.destination(face=...)` and `bus.source(face=...)` pick the face, or `Slot(n)` names a slot.

## Try it

    from logistics import network, move, Face

    net = network()
    furnace = net.bus("furnace")
    store = net.bus("store")

    move(store, furnace.destination(face=Face.TOP), "raw_iron", 8)
    move(store, furnace.destination(face=Face.LEFT), "coal", 1)

## Your turn

Wait for the iron to be smelted (8 take about 80 seconds) and bring it back:

    for change in furnace.watch():
        got = move(furnace.source(face=Face.BOTTOM), store)
        if got:
            print("Smelted", got)

## Hints

- One coal smelts eight items; a lava bucket a hundred.
- Several furnaces: give each a bus named `furnace_1`, `furnace_2`... and loop over them.
- Other mods' machines work the same way: their sides lead to their own slots.

**Next:** `cd /lessons/08_crafting`. **More:** `/helpme/buses/4_furnace.py`, `/helpme/buses/factory/smelter.py`.
**Manual:** sections 5 and 6.4.
