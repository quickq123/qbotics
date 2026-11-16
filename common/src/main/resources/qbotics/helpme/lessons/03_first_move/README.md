# Lesson 3: Moving Day

**Earns:** *Moving Day*. **You need:** two chests, each with an Item Serial Bus, named `inbox`
and `store`, on the computer's cables. Something in the inbox.

## The idea

Almost everything a base does is moving items: from a chest to a furnace, from a furnace to
storage, from storage to a crafter. One function does all of it:

    move(sources, destinations, what=None, count=None)

It moves what it can and says how many moved. A full chest, or nothing to move, is not an
error: it just moves fewer, or 0.

## Try it

    from logistics import network, move

    net = network()
    inbox = net.bus("inbox")
    store = net.bus("store")

    print("Moved", move(inbox, store, "cobblestone", 16), "cobblestone")
    print("Moved", move(inbox, store), "of everything else")

## Your turn

Move one thing back from the store to the inbox, then count what is where:

    move(store, inbox, "torch", 8)
    print("inbox:", inbox.count(), "store:", store.count())
    for stack in store.contents():
        print(stack["count"], stack["item"])

## Hints

- **Naming things:** `"coal"` is one item, `"*_log"` any log, `"#c:ingots"` a tag, `"@create"` a
  mod, `"coal | charcoal"` either one.
- With one item name and no count, `move` moves 1. With a filter or nothing, it moves everything
  (up to 576 a call).

**Next:** `04_sorting`, and `07_smelting`. **More:** `/helpme/buses/1_count.py`, `2_move.py`.
**Manual:** sections 4 and 6.4.
