# Lesson 4: A Place for Everything

**Earns:** *A Place for Everything*. **You need:** the `inbox` and `store` from lesson 3, and two
more chests with buses, named `ores` and `food`.

## The idea

Sorting is moving with rules: each chest says what it takes. There are two ways:

- **In the move:** give `move` a list of destinations, each with its rules. Each item goes to
  the first one that takes it.
- **On the bus:** `bus.set_filter(...)` tells a bus what it lets into its chest, for every
  program from then on. The bus keeps it.

## Try it: rules in the move

`start.py` sorts with rules like these, written as a table you can change: `run start.py`, and
`edit start.py` to add a chest (one more line in `RULES`). The idea, written out:

    from logistics import network, move

    net = network()
    ORES = ["#c:ores", "#c:raw_materials", "#c:ingots"]
    ores = net.bus("ores").destination(only=ORES)
    food = net.bus("food").destination(only="#c:foods")
    store = net.bus("store").destination()      # anything: the catch-all, last

    print("Sorted", move(net.bus("inbox"), [ores, food, store]))

## Your turn: rules on the bus

Give the buses their filters once, then sort with a plain list:

    net.bus("ores").set_filter(["#c:ores", "#c:raw_materials", "#c:ingots"])
    net.bus("food").set_filter("#c:foods")
    chests = [net.bus("ores"), net.bus("food"), net.bus("store")]
    move(net.bus("inbox"), chests)

## Hints

- `without=` keeps things out: `store.destination(without=["dirt", "*_seeds"])`.
- `set_filter(matching=True)` lets in only kinds the chest already holds: put one in by hand
  to say "this chest is for these".
- A bus's filter only limits what goes **in**. Things put in by hand can still be taken out.

**Next:** `cd /lessons/05_events`, `cd /lessons/10_storage`. **More:** `/helpme/buses/3_filters.py`, `/helpme/storage/2_sorting.py`.
**Manual:** sections 6.3a and 6.4.
