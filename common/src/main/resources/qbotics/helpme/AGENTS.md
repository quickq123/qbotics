# Notes for AI coding assistants

You are helping someone program a computer in **Qbotics**, a Minecraft mod. These files are
that computer's drive. Read this first; the full manual is `REFERENCE.md` beside it (the
section numbers below are its sections). Copy both to the top folder of the drive (`/`) so
tools that open the drive as a project find them.

## Where the code runs

- **MicroPython**, not CPython, inside the game. No pip packages from the internet, no
  threads, no `FileNotFoundError` or `PermissionError` (catch `OSError`), no `subprocess`.
  Available: `time`, `json`, `os`, `sys`, `math`, `random`, `re`, `io`, `struct`, `collections`,
  `heapq`, `binascii`.
- The game's own modules: `logistics` (devices, moving items, fluids, energy), `events`
  (waiting for things), `network` (messages between computers), `display` (screens), `qbot`
  (robots), `requests` (HTTPS through an Uplink), `git`.
- Each computer has its own files, about 1 MB. Paths start at `/`. `import` looks in the
  program's own folder, then the current folder, then `/lib`.
- Programs are limited in memory, CPU time and calls into the game per tick. A loop that
  checks again and again without waiting wastes its share: wait with `time.sleep()` or `events`.

## Seeing the game from outside: one command at a time

Over the SSH gateway (`ssh <computer>@<server> -p 2222 "<command>"`), any shell command runs
on the computer as if typed, prints its output and ends with status 0 when it worked:

| Command | What you get |
|---|---|
| `devices --json` | Every device on the computer's network: `ip`, `mac`, `kind` (bus, interface, pin, antenna, ...), `name`, and more. Use the `name` (or `ip`) in code: `net.bus("chest")` |
| `run test.py` | Runs a program; its output and any traceback come back. Stopped after 120 seconds |
| `ls`, `cat file`, `mkdir`, `rm`, `mv`, `cp` | Files, as usual |
| `help` | Every shell command |

A program that never ends (one using `events.run()`) runs until the time limit: for testing,
write a version that does its work once and exits. A computer already running a program
answers "busy" (status 3) until that program is stopped.

## The shape of a program (sections 6 and 7b)

```python
from logistics import network, move, Face, Slot
import events

net = network()                    # this computer's cable network
inbox = net.bus("inbox")           # a Serial Bus by the name given with the Network Probe
store = net.bus("store")

def sort(change=None):             # handlers for on_change get the change
    move(inbox, store.destination(without="dirt"))

sort()
events.on_change(inbox, sort)      # run sort whenever the inbox changes
events.run()                       # wait for events until Ctrl+C
```

- `move(sources, destinations, what=None, count=None)` returns how many moved; nothing to
  move is not an error (section 6.4).
- Names and filters: `"coal"`, `"*_log"`, `"#c:ingots"` (a tag), `"@create"` (a mod),
  `"coal | charcoal"` (section 4).
- A furnace: `Face.TOP` takes what to smelt, a side (`Face.LEFT`) takes fuel, `Face.BOTTOM`
  gives the result; or `Slot(0)`, `Slot(1)`, `Slot(2)` (section 5).
- `events` handlers: `every`/`after` call with nothing, `on_change` with the change, `on_pin`
  and display handlers with an event. Pass extra values with a lambda (section 7b).

## Mistakes worth knowing

- `net.bus(name)` raises `NetworkError` if no device has that name: check `devices --json`.
- An error inside an events handler stops `events.run()` and every other handler.
- Long `time.sleep()` inside a handler holds up all other events: use `events.after()`.

The `/helpme` folder has worked examples of everything: `buses/` (and `buses/factory/`, a
program split into one job per file), `events/`, `redstone/`, `displays/`, `qbots/`,
`network/`.
