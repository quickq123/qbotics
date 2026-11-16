# storage: many chests as one, and sorting into them

One chest fills up fast. A **storage** is a row of chests (each with an Item Serial Bus) that
a program uses as one: count everything in all of them, take from whichever has it, and put
things away, each kind into the chest where it already is. However many chests there are,
each call asks them all at once, in one tick.

**Names.** A storage finds its buses by name: `net.storage("chest*")` is every bus whose
name starts with `chest`. Name buses `chest_1`, `chest_2`, ... with the **Network Probe**,
and a new chest joins the storage as soon as its bus gets a name like that.

| File | What it shows | You need |
|---|---|---|
| `1_many_chests.py` | Counting and listing across every chest, where something is, room left, and putting the inbox away | Buses named `chest_1`, `chest_2`, ... and `inbox` |
| `2_sorting.py` | Sorting with **bus filters**: each chest says what it takes, the rest take what they already hold, and an overflow takes anything | Buses named `chest_ores`, `chest_food`, `chest_overflow`, more `chest_...`, and `inbox` |
| `3_filters.py` | Filters on an item's data (enchantments, potions, wear) and filters built from filters with `Filter` | The same buses, and `chest_books`, `chest_repair` |

Run one:

    cd /helpme/storage
    run 1_many_chests.py

## Things worth knowing

- **A bus's filter belongs to the bus.** `bus.set_filter(...)` is kept by the bus itself, so
  it holds for every program, and for every storage the bus is part of. It only limits what
  goes **in**: something put in by hand that the filter wouldn't let in can still be taken.
- **Matching:** `set_filter(matching=True)` lets in only kinds the chest already holds. Put
  one of each kind in by hand to say where it goes. `matching="exact"` also compares data:
  a chest of Strength II potions takes only those.
- **Putting things away** tries, in turn: a chest whose filter takes it and that holds some
  already, a chest whose filter takes it, a chest with no filter holding some, then any chest
  with no filter (the overflow).
- `store.strays()` lists things in a chest that its filter wouldn't let in, and `store.tidy()`
  moves them to where they belong.
- A storage works wherever a bus does: `move()`, recipes, Placers and Breakers, the sensors in
  `/helpme/world`, and the storage page in `/helpme/displays/web_storage`.
- The manual: section 6.4c (storage), 6.3a (bus filters), 4 (filter words and `Filter`).
