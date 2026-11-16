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

`start.py` moves everything from `inbox` to `store`. Run it with `run start.py`; open it with
`edit start.py` to change the names, or `WHAT` to move only one kind of item. Inside, it is:

    from logistics import network, move

    net = network()
    inbox = net.bus("inbox")
    store = net.bus("store")

    print("Moved", move(inbox, store, "cobblestone", 16), "cobblestone")
    print("Moved", move(inbox, store), "of everything else")

## Watch it run, line by line

A program runs too fast to see. The editor can slow it down to one line at a time:

1. `edit start.py`, and look at the code.
2. Press **F8**. The program starts and stops at its first line, marked in **amber**.
3. Press **F8** again: that one line runs, and the mark moves to the next. Watch the terminal
   below as each line does its part: nothing happens until the `move` line, then the `print`s.
4. **Ctrl+Z** runs the rest at full speed. **Ctrl+C** stops it.

Do this whenever a program surprises you: step until the line where it does something you
didn't expect. That line is where to look. F8 works on any program, in every lesson.

## Your turn

Move one thing back from the store to the inbox, then count what is where:

    move(store, inbox, "torch", 8)
    print("inbox:", inbox.count(), "store:", store.count())
    for stack in store.contents():
        print(stack["count"], stack["item"])

## New Python here

- A function can take several **values** in its brackets, in order: `move(source, destination, WHAT)`.
- `None` means "nothing" or "not given". `move(a, b, None)` moves every kind of item.
- **Numbers** are written plainly, `16`, with no quotes. A function can give one back:
  `moved = move(...)` keeps the number it gives.

## Hints

- **Naming things:** `"coal"` is one item, `"*_log"` any log, `"#c:ingots"` a tag, `"@create"` a
  mod, `"coal | charcoal"` either one.
- With one item name and no count, `move` moves 1. With a filter or nothing, it moves everything
  (up to 576 a call).

**Next:** `cd /lessons/04_sorting`, and `cd /lessons/07_smelting`. **More:** `/helpme/buses/1_count.py`, `2_move.py`.
**Manual:** sections 4 and 6.4.
