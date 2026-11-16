# Lesson 11: On Screen

**Earns:** *On Screen*: write on a Text Monitor. **You need:** Text Monitors on the computer's
cables (put several side by side and they join into one bigger screen), and a chest with a bus.

## The idea

A **Text Monitor** is a screen of characters, in colours, that a program draws on: a status
board for your base. Players see it from across the room.

## Try it

    from logistics import network

    net = network()
    mon = net.monitor()

    mon.clear()
    mon.print("Hello from the base")
    mon.write_at(0, 2, "STORAGE", "yellow")
    columns, rows = mon.size()
    print("The screen is", columns, "by", rows)

## Your turn

Show what a chest holds, and keep it up to date (lesson 5). Write it in this folder as
`screen.py` (`edit screen.py`); lesson 17 runs it again:

    import events

    chest = net.bus("store")

    def show(change=None):
        mon.clear()
        mon.write_at(0, 0, "In the store", "white", "blue")
        for row, stack in enumerate(chest.contents()[:rows - 1]):
            name = stack["item"].split(":")[-1]
            mon.write_at(0, row + 1, "%5d %s" % (stack["count"], name))

    show()
    events.on_change(chest, show)
    events.run()

## New Python here

- `"%5d %s" % (count, name)` fills a text with values: `%d` a number (`%5d`: five wide), `%s`
  anything. Good for lining things up in columns.
- `for row, stack in enumerate(things):` gives each item with its number, 0, 1, 2...
- `"minecraft:iron_ingot".split(":")` cuts text at each `:` into a list; `[-1]` takes the last part.

## Hints

- Colours: `"white"`, `"red"`, `"green"`, `"blue"`, `"yellow"`, ..., as text and background.
- `mon.fill(x, y, width, height, colour)` paints a box: bars for a chart.
- `mon.scale = 2` makes the text twice as big, for a screen seen from far away.
- Touching a monitor is an event too: `mon.on_touch(f)` gets where it was touched.

**Next:** `cd /lessons/12_panel`. **More:** `/helpme/displays/monitor/status.py`. **Manual:** sections 7c.1 to 7c.4.
