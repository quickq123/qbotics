# factory: one program, several jobs, one file each

A program that does one thing can be one file. A base's program does many: sort what comes in,
keep the furnaces going, warn when coal runs low. Put all that in one file and it soon gets
hard to change. This folder shows a better shape.

| File | What it is |
|---|---|
| `main.py` | Starts every job, then hands over to `events`. Short, and the only file you run |
| `devices.py` | Every device the jobs use, found by name, in one place |
| `safely.py` | Wraps a job's function so an error is printed instead of stopping every job |
| `sorter.py` | Job: moves what arrives in the inbox to the chest whose rules take it |
| `smelter.py` | Job: keeps furnaces fed with ore and fuel and takes out what they make |
| `report.py` | Job: every minute, says what is running low |

## How it fits together

Each job file has a `start()` function. It does the job once (for whatever is already
there), then tells `events` when to do it again: when a chest changes, or on a timer.
`main.py` calls each job's `start()` and then `events.run()`, which waits and calls the
right function whenever something happens.

    main.py ── sorter.start()   ── events.on_change(inbox, ...)
            ├─ smelter.start()  ── on_change(furnace, ...), on_change(ores)
            ├─ report.start()   ── events.every(60, ...)
            └─ events.run()        waits, and calls them as things happen

The jobs don't know about each other. To add a job, write a file with a `start()` and add it
to the list in `main.py`. To turn one off, take it out of the list.

## You need

Item Serial Buses, named with the Network Probe:

| Name | On |
|---|---|
| `inbox` | The chest things arrive in |
| `tools`, `ores`, `food` | Storage chests (see the rules in `sorter.py`; remove lines you don't have) |
| `store` | A chest for everything else |
| `furnace` | A furnace (add more in `smelter.py`) |

Raw ore sorted into `ores` is smelted, and the ingots come back to `ores`.

## Run it

    run /helpme/buses/factory/main.py

A program imports files from its own folder, so this works from any folder.

To keep your own copy (so `git pull` in /helpme never touches it), run `copy.sh`: it puts
the files in /factory. Then, to start it every time the computer starts, make /startup.sh:

    edit /startup.sh

with the line `run /factory/main.py` in it, and save.
