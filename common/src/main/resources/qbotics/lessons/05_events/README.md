# Lesson 5: Waiting, Not Looping

**Earns:** *Waiting, Not Looping*. **You need:** the sorting chests from lesson 4.

## The idea

A sorter should sort whenever something arrives, not just once. The first idea is a loop:

    while True:
        move(inbox, chests)
        time.sleep(1)

That works, but it checks every second whether anything has happened, mostly to find that
nothing has. The better way is to **wait**: tell the game what you are waiting for, and sleep
until it happens. A waiting program costs the game nothing.

## Try it: one watch

    from logistics import network, move

    net = network()
    inbox = net.bus("inbox")
    chests = [net.bus("ores"), net.bus("food"), net.bus("store")]

    for change in inbox.watch():              # sleeps until the inbox changes
        print(change["item"], change["was"], "->", change["now"])
        move(inbox, chests)

## Your turn: with events

`events` waits for many things at once: chests, timers, levers, buttons, messages. `start.py`
is the lesson 4 sorter made to keep going this way: `run start.py`, drop things in the inbox,
and Ctrl+C to stop it. Later lessons run it by itself; it is your sorter from now on.

The heart of it:

    import events

    def sort(change=None):
        print("Sorted", move(inbox, chests))

    sort()                                    # what is there already
    events.on_change(inbox, sort)             # whenever the inbox changes
    events.every(60, lambda: print("store:", net.bus("store").count()))
    events.run()                              # wait, calling them as needed

Drop something in the inbox and watch it go.

## New Python here

- `def sort(change=None):` makes your own **function**: the lines indented under it run each time
  it is called. `change=None` means it takes one value, and works without one too.
- Lines that start further in (4 spaces) belong to the line above them: the body of a `def`,
  `for` or `if`. That is how Python knows where a block ends.
- `events.on_change(inbox, sort)` hands over the function itself (no brackets), so events can call
  it later, again and again.

## Hints

- Hand over the function (`sort`), don't call it (`sort()`).
- `on_change` calls your function with the change, so take it: `def sort(change=None):`.
- Keep handlers quick: one runs at a time. To do something later, use `events.after(seconds, f)`.

**Next:** `cd /lessons/06_redstone`, `cd /lessons/11_monitor`, `cd /lessons/15_always_on`. **More:** all of `/helpme/events`.
**Manual:** sections 6.4b and 7b.
