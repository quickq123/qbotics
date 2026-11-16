# Lesson 6: Redstone, Programmed

**Earns:** *Redstone, Programmed*: send a signal **and** react to one. **You need:** two
**Logic Pins** on the computer's cables: one pointing at a redstone lamp (name it `lamp`), one
pointing at a lever or a button (name it `switch`).

## The idea

A **Logic Pin** is a plate on a cable that is one redstone line. As an **output** it drives a
signal into the block it points at, 0 (off) to 15 (full). As an **input** it reads the signal
from that block. Everything redstone (doors, pistons, machines from other mods) can be worked
this way.

## Try it: send a signal

    from logistics import network
    import time

    net = network()
    lamp = net.pin("lamp")

    lamp.write(15)          # on
    time.sleep(2)
    lamp.write(0)           # off
    lamp.pulse(10)          # on for 10 ticks (half a second), then off

## Your turn: react to one

Make the lever switch the lamp, through your program:

    import events

    switch = net.pin("switch")

    def flipped(event):
        print("The switch is at", event.level)
        lamp.write(event.level)

    events.on_pin(switch, "change", flipped)    # or "rising", "falling"
    events.run()

## New Python here

- `True` and `False` are Python's yes and no.
- `if`, `elif` and `else` choose what to do:

      if event.level > 0:
          print("on")
      else:
          print("off")

- `>`, `<`, `==` (equals: two `=`), `!=` (not equals) compare values, giving `True` or `False`.

## Hints

- `pin.read()` gives the level now. A new pin reads; `write()` makes it an output.
- A pin's output also reaches through a solid block it points at, as a redstone torch's does.
- To make a door close itself, open it on `"rising"` and use `events.after(3, close)`.

**More:** `/helpme/redstone/door.py`. **Manual:** section 6.10.
