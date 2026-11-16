# buses: moving items with Serial Buses

An **Item Serial Bus** is a plate on a cable that works the container it points at: it can
count what is inside, take things out and put things in. Put a chest down, a cable beside it,
and right-click the chest holding the bus. Almost everything a base needs (sorting, smelting,
crafting, keeping things stocked) is buses and `move`.

**Names.** The examples find buses by name, like `net.bus("inbox")`. Name a bus by
right-clicking it with the **Network Probe** and typing a name. Each example says at the top
which names it uses. An address works too: `net.bus("192.168.1.5")`.

## Start here: one idea at a time

Run them in order: each builds on the one before.

| File | What it shows | You need |
|---|---|---|
| `1_count.py` | What a chest holds, totals, and counting by name, tag or pattern | A bus on a chest |
| `2_move.py` | `move`: one item, a number of items, a filter, everything | Buses named `inbox` and `store` |
| `3_filters.py` | Where things are allowed to go: `only`, `without`, and a list of chests tried in order | Buses named `inbox`, `ores`, `food` and `store` |
| `4_furnace.py` | Faces and slots: ore in the top, fuel in the side, smelted items out of the bottom | Buses named `store` and `furnace` |
| `5_watch.py` | Waiting for a chest to change, without checking again and again | A bus named `inbox` |
| `6_sort.py` | The same with `events` (explained in `/helpme/events`): sort whatever arrives, for as long as it runs | Buses named `inbox` and `store` |
| `7_craft.py` | Crafting torches at a crafting table with a `Recipe` | Buses named `store` and `crafter` (on a crafting table) |

Run one:

    cd /helpme/buses
    run 2_move.py

## Then: a whole factory, one job per file

`factory/` is a complete program made the way bigger programs should be: every device named
in one file, each job (sorting, smelting, a stock report) in a file of its own, and a short
`main.py` that starts the jobs and lets `events` run them. Read `factory/README.md`.

## Things worth knowing

- `move(sources, destinations, ...)` returns how many moved. A full chest, or nothing to
  move, is not an error: it moves fewer, or 0.
- A `source()` or `destination()` only describes one end. Nothing happens until `move`.
- Filters: `"coal"` is one item, `"*_log"` a pattern, `"#c:ingots"` a tag, `"@create"` a whole
  mod, `"coal | charcoal"` either. The reference (section 4) lists them all.
- **Many chests?** `/helpme/storage` uses a row of them as one (`net.storage("chest*")`), and
  sorts into them with filters kept on the buses.
- For a storage screen with item pictures and a search box, see `/helpme/displays/web_storage`.
