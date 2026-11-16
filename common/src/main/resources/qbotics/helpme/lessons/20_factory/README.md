# Lesson 20: The Factory

**Earns:** *The Factory*, the last lesson. Everything together, in one base that runs itself:

| It must | From lesson |
|---|---|
| **Craft** at a crafting table from a storage of **eight or more** chests | 8, 10 |
| **Sort** what arrives into the storage | 4, 5 |
| **Show** what is going on, on a Text Monitor, a Control Panel, a Web Panel or a Tablet | 11 to 14 |
| **Run unattended**: from startup for a game day, or as a Server's service | 15, 17 |

The parts can be done in any order, and by different programs.

## How to build it

Bigger programs are easier as **one job per file**: a file naming every device, one file for
each job (sorting, crafting, the screen), and a short `main.py` that starts the jobs and hands
over to `events`. `/helpme/buses/factory` is made that way: read its README, copy it with
`sh /helpme/buses/factory/copy.sh`, and change it to your base.

    main.py ── sorter.start()   ── events.on_change(inbox, ...)
            ├─ crafter.start()  ── events.every(30, ...): keep 64 torches
            ├─ screen.start()   ── events.every(5, ...): show the stock
            └─ events.run()

## A crafting job

Keep a stock of something, crafted from the storage when it runs low:

    from logistics import network, Slot, Recipe

    net = network()
    store = net.storage("chest*")
    table = net.bus("crafter")

    torches = Recipe(sources=store, destination=store)
    torches.ingredient("coal", Slot(2))
    torches.ingredient("stick", Slot(5))
    torches.result(Slot(0), "torch", 4)

    def restock():
        short = 64 - store.count("torch")
        runs = min(torches.max_runs(), (short + 3) // 4)
        if runs > 0:
            with table.open_screen() as screen:
                torches.craft(runs, screen)

## Your turn

Build it: an inbox sorted into eight or more chests, something crafted from them to keep a
stock, a screen that shows the stock, all started from `/startup.sh` (or as services), and left
running for a game day. When it is done, your base runs itself.

## Then what?

- Plan whole crafting trees with `game.plan("piston", 8, have=store)` (manual 7k).
- Run smelters and modded machines from the same storage (lesson 7).
- Watch it from anywhere on your tablet (lesson 14), and link outposts by radio (lesson 16).

**Manual:** the whole of it. Good luck.
