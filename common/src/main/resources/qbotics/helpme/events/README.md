# events: doing things when something happens

Most programs for a base wait for something: an item arriving, a lever being pulled, a button
pressed on a screen, a message from another computer, a minute passing. The `events` module is
the way to wait for all of them at once.

## Loops and events

A loop checks again and again:

    while True:
        if chest.count() > 0:
            sort()
        time.sleep(1)

That's fine for one thing. But a loop can only wait for one thing at a time: add a lever, a
timer and a screen, and it soon gets tangled. With events you say **what to do when**, then
hand over:

    events.on_change(chest, sort)          # when the chest changes, call sort
    events.on_pin(lever, "rising", open)   # when the lever goes on, call open
    events.every(60, report)               # every minute, call report
    events.run()                           # then wait, calling them as needed

`events.run()` sleeps until something happens, calls the right function, and sleeps again.
A waiting program costs the game nothing.

## The three steps

1. **Write functions** for what should happen. These are *handlers*.
2. **Hand them over**: `events.on_change(...)`, `events.every(...)` and the rest. Nothing runs yet.
3. **Call `events.run()`**. It handles events until `events.stop()` is called, or Ctrl+C.

## What you can wait for, and what your function gets

| Hand it over with | It runs | Your function gets |
|---|---|---|
| `events.every(seconds, f)` | Again and again, every so many seconds | Nothing: `def f():` |
| `events.after(seconds, f)` | Once, so many seconds from now | Nothing |
| `events.on_change(device, f, what=None)` | When what a chest, tank or interface holds changes | The change: `change["item"]`, `["slot"]`, `["was"]`, `["now"]` |
| `events.on_pin(pin, "rising", f)` | When a Logic Pin's signal comes on (`"falling"`: goes off, `"change"`: any change) | An event: `e.level`, `e.name`, `e.event` |
| `events.on_message(f)` | When another computer sends a message | The message: `msg.sender`, `msg.data` |
| `widget.on_press(f)`, `display.on_touch(f)` | When a player presses a panel button or touches a screen | An event: `e.player`, ... |

`every` and `after` return a timer: `timer.cancel()` stops it.

**The function must take what it is given.** `on_change` calls `f(change)`, so `def f():`
with no parameter fails when the first change arrives. If you don't need it, take it anyway:
`def f(change=None):`. The `=None` lets you call `f()` yourself too.

## Passing your own values

You hand over a function; events calls it, with only what the table says. To give it more,
wrap it in a `lambda` that adds your values:

    def send(change, where):
        ...
    events.on_change(inbox, lambda change: send(change, "store"))

In a loop, add `name=name`, or every lambda sees the loop's *last* value:

    for name in ["north", "south"]:
        pin = net.pin(name)
        events.on_pin(pin, "rising", lambda e, name=name: open_door(name))

## Rules worth knowing

- **Hand over the function, don't call it:** `events.every(5, report)`, not `report()`.
- **Keep handlers quick.** One handler runs at a time. `time.sleep(10)` in a handler holds
  up every other event for ten seconds. To do something later, use `events.after`.
- **An error in a handler stops `run()`**, and with it every handler. See
  `/helpme/buses/factory/safely.py` for a way to print the error and carry on.
- **`run()` returns** when `events.stop()` is called, or when nothing is left to wait for
  (only `after` timers, and they have all run). The program carries on after it.
- **Handlers last as long as the program.** When it ends (or Ctrl+C), they are gone. To have
  them running all the time, start the program from `/startup.sh`.

## The examples

| File | What it shows | You need |
|---|---|---|
| `1_timers.py` | `every`, `after`, `cancel` and `stop`, and when `run()` returns | Nothing |
| `2_handlers.py` | Handing functions over, decorators, passing your own values, remembering between calls | Nothing |
| `3_chest.py` | Reacting to a chest, narrowing it to some items, and a timer alongside | A bus named `chest` |
| `4_quick.py` | Why handlers should be quick, and how to wait without holding everything up | Nothing |

More events elsewhere in helpme:

| Where | Events |
|---|---|
| `/helpme/redstone/door.py` | A lever (`on_pin`) opens a door, and `after` closes it |
| `/helpme/network/pong.py` | Answering messages (`on_message`) |
| `/helpme/displays/panel` | Buttons and switches on a Control Panel (`on_press`, `on_change`) |
| `/helpme/buses/6_sort.py` | Sorting whatever arrives (`on_change`) |
| `/helpme/buses/factory` | A bigger program: one job per file, each adding its own handlers, one `events.run()` for all |
| `/helpme/world/4_wheat_farm.py` | Harvesting wheat as each one ripens (`on_change` on a Block Sensor) |
| `/helpme/world/5_collector.py` | Emptying a Collector when it picks something up |
| `/helpme/world/7_day_night.py` | Night falling, the weather changing (`on_change` with `env.watch()`) |
