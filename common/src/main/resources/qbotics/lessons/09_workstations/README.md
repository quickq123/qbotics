# Lesson 9: Tools of the Trade

**Earns:** *Tools of the Trade*: take what a workstation made. **You need:** a stonecutter (or a
smithing table, grindstone, loom or anvil) with an Item Serial Bus on it, and the `store`.

## The idea

Any block with a screen works like the crafting table in lesson 8: open its screen, put things
in its slots, take the product out. Each block numbers its slots its own way:

| Block | Slots |
|---|---|
| Stonecutter | input 0, product 1; choose what to make with `press` |
| Smithing table | template 0, base 1, addition 2, product 3 |
| Grindstone | inputs 0 and 1, product 2 |
| Loom | banner 0, dye 1, pattern 2, product 3; choose with `press` |
| Anvil | inputs 0 and 1, product 2; it costs levels |

Holding the **Network Probe** over a screen's slots (in its Slots mode) shows their numbers.

## Try it: a stonecutter

A stonecutter's choices are buttons, numbered across its grid from 0, top row first:

    from logistics import network, Slot

    net = network()
    store = net.bus("store")

    with net.bus("cutter").open_screen() as screen:
        screen.put(Slot(0), "stone", 1, sources=store)
        screen.press(0)                 # the first thing it offers
        print(screen.contents())        # what is in each slot now
        print("Made", screen.take(Slot(1), store))

## Your turn

Make stone bricks: try `press(n)` for each choice until slot 1 holds `minecraft:stone_bricks`,
and keep that number. Then cut a whole stack.

## New Python here

- `range(40)` counts 0, 1, 2 ... 39: `for n in range(40):` tries each number.
- `break` leaves a loop early, once what you were looking for is found.
- A **dictionary** keeps values under names: `entry["slot"]`, `entry["item"]`.

## The anvil

An anvil asks for levels, as it would from a player. Pay them from an **Experience Interface**:

    bank = net.experience_interface()
    with net.bus("anvil").open_screen(xp=bank) as screen:
        print("It costs", screen.cost(), "levels")

**More:** modded machines (saws, presses) are fed and emptied by buses, as the furnace in
lesson 7 is. **Manual:** sections 6.7 and 6.8.
