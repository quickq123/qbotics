# Lesson 8: Made to Order

**Earns:** *Made to Order*: craft at a crafting table with a program. **You need:** a crafting
table with an Item Serial Bus on it (named `crafter`), and the `store` with coal and sticks.

## The idea

A bus on a crafting table can **open its screen**, as a player would, and work its slots:
put ingredients in, take the result out. The slots are numbered: 0 is the result, 1 to 9 the
grid, top row first.

    1 2 3
    4 5 6      ->  0
    7 8 9

A `Recipe` writes a crafting job down once, so it can be crafted again and again.

## Try it: by hand

    from logistics import network, Slot, Recipe

    net = network()
    store = net.bus("store")

    with net.bus("crafter").open_screen() as screen:
        screen.put(Slot(2), "coal", sources=store)      # coal on top
        screen.put(Slot(5), "stick", sources=store)     # a stick below
        print("Made", screen.take(Slot(0), store), "torches")

## Your turn: a Recipe

    torches = Recipe(sources=store, destination=store)
    torches.ingredient("coal", Slot(2))
    torches.ingredient("stick", Slot(5))
    torches.result(Slot(0), "torch", 4)

    print("Enough for", torches.max_runs(), "runs")
    with net.bus("crafter").open_screen() as screen:
        print(torches.craft(5, screen))                 # five runs: 20 torches

## New Python here

- `with ... as screen:` opens something for the lines under it, and always closes it after, even if
  something goes wrong.
- An **object** carries values and functions of its own: `torches.ingredient(...)` and
  `torches.max_runs()` belong to the recipe `torches`.
- `Recipe(sources=store, destination=store)` names its values (**keyword arguments**), so their
  order doesn't matter.

## Hints

- `Recipe.from_game(game.recipes("torch")[0], sources=store, destination=store)` builds one from
  the game's own recipe (`import game`), for any mod's item.
- `recipe <item>` at the prompt shows how an item is made.
- Use the screen in a `with` block: when it closes, anything left in it comes back.

**Next:** `cd /lessons/09_workstations`. **More:** `/helpme/buses/7_craft.py`. **Manual:** sections 6.8 and 6.9.
