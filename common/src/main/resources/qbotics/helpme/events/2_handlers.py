# Handlers: the functions you hand to events. Nothing to build: timers stand in for chests,
# levers and buttons, which work the same way.
# Run it:  run 2_handlers.py   (it stops by itself after 4 seconds)
import events

# 1. A handler is an ordinary function. Hand it over without (): events calls it later.
def hello():
    print("1. hello from a handler")

events.after(0.5, hello)

# 2. The same, written as a decorator above the def. Use whichever reads better.
@events.after(1)
def decorated():
    print("2. hello from a decorated handler")

# 3. Passing your own values: events calls a handler only with what it has (a timer, with
#    nothing). A lambda adds the rest.
def greet(name, times):
    print("3. hello", name, "x", times)

events.after(1.5, lambda: greet("Steve", 3))

# 4. In a loop, "name=name" keeps each lambda's own value. Without it, all three would print
#    "east": a lambda looks its variables up when it runs, after the loop has finished.
for name in ["north", "south", "east"]:
    events.after(2, lambda name=name: print("4. door", name))

# 5. Remembering between calls: keep it outside the function. A dict (or list) can be changed
#    from inside without "global".
totals = {"ticks": 0}

@events.every(0.5)
def counting():
    totals["ticks"] += 1

events.after(3, lambda: print("5. counted", totals["ticks"], "times"))

# 6. Handlers for chests, pins and messages are given one value, so they take one parameter:
#        def arrived(change): ...                    events.on_change(chest, arrived)
#        lambda change: send(change, "store")        with your own values too
#    "def arrived(change=None)" lets you call it yourself as well, with nothing.

events.after(4, events.stop)
events.run()
print("Done.")
