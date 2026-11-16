# Lesson 10: Many Chests as One

**Earns:** *Many Chests as One*: move into or out of a storage of four or more chests. **You
need:** four chests or more, each with an Item Serial Bus, named `chest_1`, `chest_2`, ... and
the `inbox`.

## The idea

A base outgrows one chest fast. A **storage** is a row of chests that a program uses as one:
counts across all of them, takes from whichever has it, and puts each kind away where it
already is. However many chests there are, each call asks them all at once.

## Try it

    from logistics import network, move

    net = network()
    store = net.storage("chest*")      # every bus named chest...

    print(len(store.devices()), "chests,", store.count(), "items")
    print(store.free(), "slots free")
    for entry in store.contents()[:10]:    # the most first
        print(entry["count"], entry["item"])

## Your turn

Put the inbox away, and take something out again:

    print("Put away", move(net.bus("inbox"), store))
    print("Took", move(store, net.bus("inbox"), "torch", 16))

## Then: sort into it

Give some chests a bus filter (lesson 4) and the rest `matching=True`, and one move sorts
everything into its own chest:

    net.bus("chest_1").set_filter(["#c:ores", "#c:ingots"])
    net.bus("chest_2").set_filter("#c:foods")
    net.bus("chest_3").set_filter(matching=True)    # what you start it with
    move(net.bus("inbox"), store)       # chest_4, with no filter: the rest

## New Python here

- `store.contents()[:10]` is the first ten items of a list (a **slice**). `[0]` is the first one,
  `[-1]` the last.
- `len(x)` is how many items a list (or letters a text) has.
- `entry["count"]` reads one value from a dictionary by its name (its **key**).

## Hints

- A new chest joins as soon as its bus gets a matching name: `bus.rename("chest_5")`.
- A storage goes anywhere a bus does: `move`, a `Recipe`'s sources, the sensors.
- `store.where("diamond")` says which chests hold it; `store.tidy()` moves things put in the
  wrong chest by hand.

**Next:** `cd /lessons/18_farmer`, `cd /lessons/20_factory`. **More:** all of `/helpme/storage`. **Manual:** section 6.4c.
