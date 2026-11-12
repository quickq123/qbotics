# Qbotics reference

What a player can type, call and use in Qbotics. This file is the source for the in-game or
web help pages, so it is kept current: every feature that a player's code or hands can reach
is added here when it is built.

Each entry gives the form, what it does, and an example. Things that are planned but not
built are not listed.

Contents

1. [The computer](#1-the-computer)
2. [Python on a computer](#2-python-on-a-computer)
3. [Networks and addresses](#3-networks-and-addresses)
4. [Naming items and fluids: names and filters](#4-naming-items-and-fluids-names-and-filters)
5. [Faces and slots](#5-faces-and-slots)
6. [The `logistics` module: items, fluid, energy](#6-the-logistics-module)
7. [The `network` module: messages and antennas](#7-the-network-module)
   - [7b. The `events` module](#7b-the-events-module-running-code-when-things-happen)
   - [7c. Displays: Text Monitors and Control Panels](#7c-displays-text-monitors-and-control-panels)
   - [7d. Qbots: computers that move](#7d-qbots-computers-that-move)
8. [The Network Probe](#8-the-network-probe)
9. [Limits](#9-limits)
10. [Errors](#10-errors)
11. [The test stage](#11-the-test-stage)

---

## 1. The computer

Right-click a computer to open its screen: files on the left, the editor in the middle, the
terminal below, and an optional Python scratchpad on the right.

### 1.0 Crafting: screens and the computer

Screens come in two tiers. The **LCD Screen** is the early-game one; the **LED Screen** costs
glowstone and gold.

| Result | Pattern | Ingredients |
|---|---|---|
| 3 Liquid Crystal Panel | `PRP` / `SGP` / `PBP` | Glass panes, with red, green and blue dye down the middle and redstone dust on the left of the middle row |
| 4 LCD Screen | `TLP` / `TLP` / `TLP` | A column each of redstone torches, Liquid Crystal Panels and glass panes |
| 3 Light Emitting Diode | `GGG` / `RNB` / `GGG` | Red, green and blue dye between two rows of glowstone dust |
| 4 LED Screen | `ODL` / `ODL` / `ODL` | A column each of gold ingots, Light Emitting Diodes and glass blocks |
| Integrated Circuit | `CCC` / `LGL` / `CCC` | Copper ingots above and below; glass, green dye, glass between |
| Computer | `CCC` / `CMC` / `CSC` | Seven cables, an integrated circuit in the middle, an **LCD Screen** at the bottom middle |
| RAM | `GGG` / `GIG` / `GGG` | An Integrated Circuit in eight gold ingots. For the Server (section 7i) |

The Text Monitor takes an LCD Screen and the Control Panel an LED Screen (section 7c).

### 1.1 Terminal commands

| Command | What it does |
|---|---|
| `help` | Lists the commands |
| `ls [folder]` | Lists a folder |
| `cd <folder>` | Changes the current folder (`cd` alone goes to `/`) |
| `pwd` | Shows the current folder |
| `cat <file>` | Prints a file |
| `mkdir <folder>` | Makes a folder |
| `rm [-r] <path>` | Deletes a file; `-r` deletes a folder and everything in it |
| `mv <from> <to>` | Renames or moves; moving onto a folder puts it inside |
| `cp <from> <to>` | Copies a file |
| `edit <file>` | Opens a file in the editor (a new file is created when saved) |
| `run <file.py>` | Runs a program. `python <file.py>` does the same |
| `python` | The Python prompt. `exit()` returns to the terminal |
| `devices` | Lists the devices on this computer's network (also `ifconfig`) |
| `devices <external IP>` | Lists the devices behind another antenna |
| `ssh <name or address>` | Uses another computer's terminal from this one (section 7.4). `exit` returns |
| `settings` | Opens this computer's settings page (section 1.5) |
| `pip` | Installs libraries from a hard drive, on this network or another (section 9d) |
| `shutdown` | Switches this computer off; it stays off, even across restarts, until opened again (section 9b) |
| `clear` | Clears the terminal |

Names with spaces go in double quotes: `run "my program.py"`.

A line that isn't a command gets a hint: `run9_world.py` suggests `run 9_world.py`, and a
program's name typed on its own suggests `run` in front of it.

Ctrl+C stops a running program. A file named `/startup.py` runs when the computer starts.

Ctrl+C reaches the program as `KeyboardInterrupt`, as in Python, straight away, even in the
middle of a `time.sleep()` or while a call waits for the game. `except Exception` doesn't catch
it, so a `while True` loop with `try`/`except Exception` inside still stops. A program can catch
`KeyboardInterrupt` itself to tidy up. If a program catches it and carries on (a bare `except:`
does this), a second Ctrl+C stops it for certain. The computer then restarts its Python,
which loses the variables typed at the `>>>` prompt but not your files.

### 1.2 Editor keys

| Key | What it does |
|---|---|
| F5 | Saves and runs the open Python file |
| F8 | Steps the running program to the next line of your code and holds it there (section 1.3b) |
| Ctrl+S | Saves |
| Ctrl+F, Ctrl+H | Find; find and replace. F3 and Shift+F3 step through matches |
| Ctrl+Space | Suggests completions |
| Ctrl+/ | Comments or uncomments the selected lines |
| Ctrl+Z, Ctrl+Y | Undo, redo |
| Ctrl+W | Closes the tab |
| Shift+Alt+F | Formats a JSON file |
| Ctrl+Plus, Ctrl+Minus | Larger or smaller text |
| Tab, Shift+Tab | Indents or un-indents the selection |
| Esc | Closes the screen (nothing is lost) |

Resting the mouse on a function name shows how to call it and what it does.

### 1.3 Terminal mouse and keys

| Action | What it does |
|---|---|
| Drag | Selects text. Double-click selects a word |
| Ctrl+C | Copies the selection; with nothing selected, stops the program |
| Ctrl+Z | Pauses the running program where it is, and says where: `[paused at line 10 of /patrol.py]`; again, it carries on. In an ssh session it pauses the program on the computer logged in to |
| F8 | Steps the paused (or running) program to its next line (section 1.3b) |
| Ctrl+V | Pastes; each pasted line is entered |
| Right-click | Copy, Copy All, Paste, Select All |
| Click on the line being typed | Puts the cursor there |
| Left, Right | Moves the cursor along the line; with Ctrl, a word at a time |
| Home, End | The start or end of the line (End also returns to the prompt if scrolled back) |
| Backspace, Delete | Deletes before or after the cursor; with Ctrl, a word |
| Up, Down | Earlier commands |
| Page Up, Page Down, wheel | Scrolls back through output. End returns |

### 1.3b Watching a program run, and stepping through it

While a computer's editor is open (on the computer, by ssh, or a laptop connected to a Qbot),
the editor marks the line of **your own code** the program is on: **green** while it runs,
**amber** while it is paused. While your code is inside a library (a `qbot.forward()` that is
still moving, a `time.sleep`), the marked line is the one that called it.

- **Ctrl+Z** pauses the program there; the editor opens that file at that line, and the terminal
  says `[paused at line N of /file.py]`. Ctrl+Z again carries on.
- **F8** steps: the program runs to the next line of your code and holds there. A loop's lines
  come round again each turn, and stepping goes into your own functions. F8 while it runs
  stops it at the next line. Ctrl+Z carries on at full speed.
- Ctrl+C still stops it at any time.

Watching costs nothing to programs nobody has open. A program whose editor is open runs a
little slower (for typical programs, which mostly wait on the world, not noticeably; tight
number-crunching loops at about half speed), and only while it is open.

### 1.4 The scratchpad

A box for trying a few lines of Python. Ctrl+Enter runs it. It shares variables with the
terminal's Python prompt. `input()` does not work there.

### 1.5 The settings page

The **Settings** button at the top left (or the `settings` command) opens the computer's
settings in the editor, as a JSON file. Change a value and save with Ctrl+S. Lines whose name
starts with `_` are notes and are ignored. A value that isn't allowed is refused with a
message, and nothing is changed.

| Setting | Values | Meaning |
|---|---|---|
| `name` | text | What the computer is called on its network |
| `remote_login.allow` | `"owner"`, `"password"`, `"off"` | Who may log in with `ssh`. `owner`: only computers placed by the same player (how every computer starts). `password`: those, and anyone who gives the password. `off`: nobody |
| `remote_login.password` | text | Type a new one to set it. It is shown as `********` once set and is never shown again; empty removes it |
| `antenna.access` | `"private"`, `"team"`, `"public"` | Who may reach this network by radio (section 7.3). Only shown when an antenna is cabled in |
| `antenna.frequency` | text | The antenna's frequency |

`ip` and `antenna.external_ip` are shown for reference and can't be changed there.

The page is not a file on the drive, so programs can't read it.

---

## 2. Python on a computer

Computers run MicroPython, a small version of Python 3. Most everyday Python works: functions,
classes, lists, dicts, f-strings, `try`/`except`, `with`, generators.

Modules available: `time`, `os`, `json`, `math`, `random`, `sys`, `gc`, and the two this mod
adds, `logistics` and `network`.

| Thing | Notes |
|---|---|
| `print(...)` | Writes to the terminal |
| `input(prompt)` | Waits for a line typed in the terminal. Ctrl+C raises `KeyboardInterrupt` |
| `open(path, mode)` | Files on the computer's own drive |
| `time.sleep(seconds)` | Waits; decimals work |
| `execfile(path)` | Runs another file |

---

## 3. Networks and addresses

Devices joined by cable, or touching each other, form one network. Every device has three
ways to be named:

| Identifier | Looks like | Notes |
|---|---|---|
| Name | `smelter` | Given by you, with the probe, an anvil or `rename()`. Letters, digits, `_`, `-`, `.`; up to 24 characters. The best thing to write in code |
| Internal IP | `192.168.1.5` | Given by the network. Can change if two networks are joined |
| Hardware address | `02:3F:42:CB:A9:E3` | Fixed while the device stays placed; new if it is picked up and placed again |

With an antenna on the network, each device can also be reached from other networks:

| Identifier | Looks like | Notes |
|---|---|---|
| External address | `100.64.0.12:1005` | The antenna's external IP, then the device's port. The port is 1000 plus the last number of the internal IP |

### 3.1 Kinds of device

| Kind (as programs see it) | What it is |
|---|---|
| `computer` | A computer |
| `antenna` | An antenna. The first on a network is its router, at `192.168.1.1` |
| `interface` | Item storage: nine filtered slots |
| `bus` | An Item Serial Bus: a plate on a cable that takes items out of the container it points at and puts them in |
| `fluid_interface` | Fluid storage: four tanks of 4,000 mB |
| `fluid_bus` | A Fluid Serial Bus: the same for fluid |
| `energy_interface` | Energy storage: 2,000,000 FE |
| `energy_bus` | An Energy Serial Bus: the same for energy |
| `usb` | A Universal Serial Bus (USB): item, fluid and energy bus in one plate (section 6.3) |
| `experience_interface` | Experience storage, with no limit |
| `pin` | A Logic Pin: a redstone line in or out, and an eye on a block |
| `placer`, `breaker` | Plates that place blocks and fluid, or break them |
| `drive_bay` | Holds hard drives; every computer on its network sees them under `/net` |
| `programmer` | Puts programs from its hard drive onto the computer in front of it |
| `monitor`, `panel` | A Text Monitor or a Control Panel: a screen, of one block or several (section 7c) |

Plates share a cable's block, one per side, and point at the block next to that side. Their
colour says what they carry: white for items, blue-grey for fluid, red for energy, royal
purple for a USB.

---

## 4. Naming items and fluids: names and filters

Wherever a program names an item or a fluid, it can write one name or a filter. The filter
words follow the JEI mod's search box.

| Written | Means |
|---|---|
| `"minecraft:coal"` | That one item |
| `"coal"` | The same: `minecraft:` is assumed |
| `"*_log"` | Wildcards on the name. `*` is any run of characters, `?` any single one. Without a colon the part after the colon is matched, in any mod |
| `"minecraft:*_ore"` | Wildcards on the whole name |
| `"@create"` | Everything from a mod, by its ID or its name. Part of either is enough |
| `"#minecraft:logs"` | Everything carrying a tag. `#logs` matches `minecraft:logs` or `c:logs`, whichever exists |
| `"$minecraft:logs"` | The same; `$` is JEI's tag prefix |
| `"@minecraft *_ore"` | Several words: all must hold |
| `"-*_slab"` | A word starting with `-` must not hold |
| `"coal \| charcoal"` | Alternatives |

Fluids use the same words: `"water"`, `"@minecraft"`, `"#c:water"`.

`docs/filters.md` is a longer guide: worked examples, sorting into several chests, and a list
of useful tags for tools, armour, ores, food and building blocks. To see an item's own name and
tags in the game, hold the Network Probe and hover over the item; hold Shift for its tags
(section 8).

A name that doesn't exist is an error that suggests close matches. A filter that nothing in
the game passes is an error too.

How many move when no count is given:

| Written | Default amount |
|---|---|
| One item name | 1 |
| One fluid name | 1,000 mB (one bucket) |
| A filter, or nothing | Everything there, up to the limit for one move |

---

## 5. Faces and slots

### 5.1 Faces

A plate reaches its target through one of the target's faces. This matters for blocks that
treat their sides differently, such as a furnace.

| Face | Which side of the target |
|---|---|
| `Face.NEAR` | The side touching the plate (the default) |
| `Face.FAR` | The opposite side |
| `Face.TOP`, `Face.BOTTOM` | Above and below |
| `Face.LEFT`, `Face.RIGHT` | As seen from the plate, looking at the target |

For a plate pointing up or down, `TOP` is north and `LEFT` is west, as on a map.

`plate.set_face(face)` changes the face used when a call names none.

For a furnace: `Face.TOP` reaches the ingredient, a side reaches the fuel, `Face.BOTTOM`
reaches the product.

### 5.2 Slots

Slots and tanks are numbered from 0, left to right and top to bottom, exactly as `contents()`
lists them. `Slot(n)` names one.

A slot number with no face counts across the whole container. With a face it counts only the
slots that face exposes.

The probe's Slots mode shows these numbers on any container's screen (section 8).

### 5.3 In counting

`count` and `amount` take a name or filter, or a `Face`, or a `Slot`, as their first value,
and `face=` and `slot=` alongside a name.

```python
chest.count("coal")              # all the coal
chest.count(Slot(2))             # whatever is in slot 2
furnace.count(Face.BOTTOM)       # what the furnace has made
```

For moving, the face and slot belong to the source or destination (section 6.4).

---

## 6. The `logistics` module

```python
from logistics import network, Face, Slot
net = network()
```

Every call is answered by the game on its next tick (a twentieth of a second).

### 6.1 The network

| Call | Gives |
|---|---|
| `network()` | The network this computer is on |
| `net.devices()` | Every device, in address order |
| `net.device(key)` | One device by name, internal IP or hardware address |
| `net.me` | This computer |
| `net.interface(key)`, `net.bus(key)` | One item device. With no key: the only one of its kind, or an error saying how many there are |
| `net.fluid_interface(key)`, `net.fluid_bus(key)` | The same for fluid |
| `net.energy_interface(key)`, `net.energy_bus(key)` | The same for energy |
| `net.usb(key)` | A Universal Serial Bus. `net.bus()`, `net.fluid_bus()` and `net.energy_bus()` find a USB by name too, working as that kind of bus; with no key they pick a USB only when there is no plain bus of that kind |
| `net.experience_interface(key)` | The experience store |
| `net.interfaces()`, `net.buses()`, `net.usbs()` | Every interface, Item Serial Bus or USB |

### 6.2 Every device

| Attribute or call | Gives |
|---|---|
| `.name`, `.ip`, `.mac`, `.kind` | As they were when the device was looked up |
| `.rename(name)` | Names the device. `""` removes the name |
| `.refresh()` | Looks the device up again |

### 6.3 Serial Buses

A Serial Bus is a plate on a cable. It works the container it points at: it takes things
out and puts things in, so it is both a source and a destination for `move`. There is one
for each medium, and one for all three:

| Plate | Kind | Carries | Colour |
|---|---|---|---|
| Item Serial Bus | `bus` | Items. It can also work a block's screen (section 6.8) | White |
| Fluid Serial Bus | `fluid_bus` | Fluid | Blue-grey |
| Energy Serial Bus | `energy_bus` | Energy | Red |
| Universal Serial Bus (USB) | `usb` | Items, fluid and energy | Royal purple |

**Crafting.** Seven cables, with a hopper on the left of the middle row and, on the right:

```
C C C        C = cable, H = hopper
H C X        X = dropper (Item Serial Bus), bucket (Fluid), block of redstone (Energy)
C C C
```

A USB is the three together, in any order: an Item, a Fluid and an Energy Serial Bus.

**Placing.** Right-click the block it should work on, with the plate in hand: it goes on the
cable in front of that face (a cable is taken from your inventory if there is none). Sneak and
right-click it with an empty hand to take it off.

| Attribute or call | Gives |
|---|---|
| `.target` | The block it points at, such as `"minecraft:furnace"` |
| `.face` | The face used when a call names none |
| `.set_face(face)` | Changes that face |

**A USB in programs.** On its own a USB works as an Item Serial Bus (`usb.count("coal")`,
`usb.open_screen()`). `usb.fluid` and `usb.energy` are the same plate as a fluid bus and an
energy bus, and `usb.items` as an item bus. In `move`, a USB carries whatever the other ends
do, so with a fluid interface at the other end it moves fluid. When every end is a USB, the
thing named decides (`"lava"` is fluid); with nothing named, it moves items. Use a view to say
so: `move(m.energy, n, count=1000)` moves energy between two USBs.

```python
machine = net.usb("machine")
move(store, machine, "raw_iron", 16)              # items in
move(machine, net.fluid_interface(), "lava", 1000)  # fluid out
move(net.energy_interface(), machine, count=50000)  # energy in
print(machine.fluid.amount(), machine.energy.stored())
```

### 6.3b Tunnels between buses

Two buses can be joined by a **tunnel**. Each end then offers, to whatever touches its face
(hoppers, pipes and cables from other mods, generators), the container the **other** end
points at. A hopper pushing into one end fills the chest behind the other; a generator
feeding one end powers the machine behind the other; a pipe pulling from one end empties
what is behind the other. No program runs and nothing has to be moved by `move`.

```
[hopper] -> [bus a]===cables===[bus b] -> [chest]       items pushed into a land in the chest
[generator] -> [energy bus]=====[USB] -> [machine]       power fed in at one end runs the machine
```

- **What it carries:** what both ends carry. Two Item Serial Buses carry items; a USB and a
  Fluid Serial Bus carry fluid; two USBs carry items, fluid and energy.
- **Both ways:** either end can be pushed into or pulled from.
- **Reach:** both ends on one cable network; or on two networks whose antennas both hold a
  Transport Upgrade and can reach each other by radio (frequency, range and access, as for
  remote moves). Between networks the Transport rate caps apply (section 7.3b).
- **When the other end can't be reached** (its area unloaded, an antenna out of range), the
  end is empty and takes nothing until it can.
- **Still a bus:** programs use a tunnelled bus as before, on its own target.
- Taking either plate off closes the tunnel.

**The tunnel light.** Every bus has a light on its edges: two pixels in the middle of each of
the four edges of its front plate (the face itself is against its target).

| Light | Means |
|---|---|
| Off (dark) | Not in a tunnel |
| Amber | In a tunnel, but the other end can't be reached now: its area isn't loaded, an antenna is out of range or lost its Transport Upgrade |
| Green | In a tunnel and connected |

It is checked once a second.

**With the Network Probe**, in its Tunnels mode (sneak and scroll): right-click one bus,
then the other. Right-clicking a bus that is already in a tunnel says where it leads; then
right-click another bus to re-route it, or the same one again to close it.

**From a program:**

| Call | Does |
|---|---|
| `bus.tunnel(other)` | Opens a tunnel to another bus (a device, or its name or address; `"100.64.0.2/02:..."` for one behind another antenna). A tunnel either was in is closed first |
| `bus.untunnel()` | Closes its tunnel, at both ends |
| `bus.tunneled` | True if it is one end of a tunnel |
| `bus.tunnel_end` | The bus at the other end, or `None` (also when it can't be reached now) |

```python
net.bus("hopper_in").tunnel(net.bus("storage"))
net.energy_bus("generator").tunnel(net.usb("machine"))
```

### 6.4 Moving things: sources, destinations and `move`

Things are moved **from sources to destinations**. A source or destination is only a
description of one end: which device, optionally which face or slot, and what it may give or
take. Nothing happens until `move` is called, so an end can be made once, kept in a variable,
put in a list, and used again.

Nothing has to pass through an Interface. One bus's chest can feed another bus's furnace directly.

| What | Can be a source | Can be a destination |
|---|---|---|
| Interface, Fluid Interface, Energy Interface, Experience Interface | yes | yes |
| A Serial Bus or USB: the container it points at | yes | yes |

**Describing an end**

```python
device.source(only=None, without=None, face=None, slot=None)
device.destination(only=None, without=None, face=None, slot=None)
```

| Argument | Meaning |
|---|---|
| `only` | A name or filter, or a list of them (section 4). The end handles these and nothing else |
| `without` | A name or filter, or a list. The end never handles these |
| `face` | Work through this face of the container (section 5.1) |
| `slot` | Work with this one slot, or tank (section 5.2) |

With no arguments an end handles everything. A device can be given to `move` as it is, which
means the same as its `source()` or `destination()` with no arguments.

**Moving**

```python
move(sources, destinations, what=None, count=None, need=None, wait=False)
```

It returns how many moved.

| Argument | Meaning |
|---|---|
| `sources` | One source or a list. They are drawn on in the order given |
| `destinations` | One destination or a list. Each source fills them in the order given |
| `what` | A name or filter. Left out: whatever the ends allow. For energy and experience it is always left out |
| `count` | Move up to this many. Fewer, even none, is not an error. Left out: 1 of a named item, one bucket of a named fluid, or everything for a filter or no name |
| `need` | Move exactly this many, or nothing at all. If that many can't be moved now, `NetworkError` is raised and nothing has moved |
| `wait` | With `need`: `True` waits until that many can be moved; a number waits that many seconds and then raises. Ctrl+C stops the wait |

What moves must pass three things: `what`, the source's own lists, and the destination's own
lists. An Interface's slot filters (section 6.5) apply as well; those belong to the block and
also stop hoppers and other mods' pipes.

```python
from logistics import network, move, Face
net = network()
store, chest, furnace = net.interface("store"), net.bus("chest"), net.bus("furnace")

move(chest, furnace.destination(face=Face.TOP), "raw_iron", 8)    # ore in through the top
move(furnace.source(face=Face.BOTTOM), store)                    # collect whatever it made

fuel = [store.source(only=["coal", "charcoal"]), chest.source(only="*coal")]
move(fuel, furnace.destination(face=Face.LEFT), count=4)          # from the first source that has some

move(fuel, furnace.destination(face=Face.LEFT), "charcoal", need=8, wait=True)   # pause until 8 are there
```

`need` checks before it moves. When a filter matches several kinds of item that compete for
the same free slots, the check can be optimistic; if fewer than `need` then move, that is
reported as an error saying how many did.

### 6.4b Watching for changes

Anything whose contents a program can read can be **watched**: interfaces, inputs and buses,
and their fluid and energy versions, and the Experience Interface. A watch lets a program
sleep until something changes, instead of reading the container again and again.

```python
for change in chest.watch():                 # sleeps until the chest changes
    print(change["item"], change["was"], "->", change["now"])

for change in chest.watch("#minecraft:logs"):   # only logs
    move(chest.source(slot=change["slot"]), store)

changes = tanks.watch().wait(10)             # whatever changes in the next 10 seconds
```

| Call | Does |
|---|---|
| `device.watch(what=None, face=None, every=0.25)` | Starts a watch. `what` narrows it to a name or filter; `face` to one face of a plate's target; `every` is how often it looks while waiting, in seconds |
| `for change in watch:` | One change at a time, sleeping between them, for ever. `break` out when done |
| `watch.wait(timeout=None)` | Sleeps until something changes and returns the list of changes; with a timeout in seconds, an empty list if nothing did |
| `watch.changes()` | What changed since the last look, straight away; an empty list if nothing |
| `watch.close()` | Ends it. Using it in a `with` block closes it at the end |

Each change is a dictionary:

| Watching | A change |
|---|---|
| Items | `{"slot": 3, "item": "minecraft:coal", "was": 5, "now": 12}` |
| Fluid | `{"tank": 0, "fluid": "minecraft:water", "was": 1000, "now": 0}` |
| Energy, experience | `{"was": 2000, "now": 1500}` |

When one kind of item replaces another in a slot, that is two changes: the old one going to 0
and the new one coming from 0. Nothing is missed between looks: each compares with the one
before. A watch nobody looks at for a minute ends; a computer can have 16 at once.

### 6.5 Items

**Interface** (`net.interface()`): nine slots. Every slot starts closed.

| Call | Does |
|---|---|
| `store.contents()` | A list of `{"slot": 0, "item": "minecraft:coal", "count": 12}` |
| `store.count(item=None, slot=None)` | How many it holds of a name or filter; of everything with none |
| `store.filters()` | What each slot accepts; `None` for a closed slot |
| `store.set_slot_filter(slot, item)` | Opens a slot for a name or filter. `None` closes it |
| `store.slots` | How many slots it has |
| `store.source(...)`, `store.destination(...)` | Section 6.4 |

A slot holds one kind of item at a time, up to one stack, even when its filter passes many.

**Item Serial Bus** (and a USB)

| Call | Does |
|---|---|
| `bus.contents(face=None)` | What the target holds |
| `bus.count(item=None, face=None, slot=None)` | How many the target holds |
| `bus.source(...)`, `bus.destination(...)` | Section 6.4 |

### 6.6 Fluid and energy

Fluid amounts are in millibuckets (mB): 1,000 mB is one bucket. Energy amounts are in FE, the
unit mods share (also called RF).

**Fluid Interface** (`net.fluid_interface()`): four tanks of 4,000 mB. A tank takes any fluid
while empty, then only more of the same, unless it has a filter.

Right-click it to open its screen: the four tanks, each with a **filter socket** above and a
**bucket slot** below. Hover a tank to see what it holds.

- **Filters.** Click a tank's socket while holding a bucket of a fluid (or any container
  holding one): that tank now takes only that fluid, from players, pipes and programs alike.
  The bucket stays in your hand. Click with an empty hand to let it take any fluid again.
  What a tank already holds stays when its filter changes.
- **Bucket slots.** A full bucket (or another mod's fluid container) put in a tank's slot
  empties into that tank; an empty one fills from it. Take the result out of the slot.
  Shift-click moves buckets in and out.
- Using a bucket on the block itself still fills or empties it directly, as before.

| Call | On | Does |
|---|---|---|
| `tanks.contents()` | Fluid Interface | A list of `{"tank": 0, "fluid": "minecraft:water", "amount": 1000, "capacity": 4000}`. An empty tank's fluid is `None` |
| `tanks.amount(fluid=None, slot=None)` | Fluid Interface | Millibuckets held of a name or filter |
| `tanks.filters()` | Fluid Interface | The fluid each tank is kept for, e.g. `["minecraft:water", None, None, None]`; `None` where it takes any |
| `tanks.set_tank_filter(tank, fluid)` | Fluid Interface | Keeps a tank for one fluid (`"water"`); `None` lets it take any again |
| `bus.contents(face=None)` | Fluid Serial Bus, `usb.fluid` | The target's tanks |
| `bus.amount(fluid=None, face=None, slot=None)` | Fluid Serial Bus, `usb.fluid` | Millibuckets the target holds |

**Energy Interface** (`net.energy_interface()`): holds 2,000,000 FE.

Right-click it to open its screen: a gauge of what it holds between two battery slots.
**Charge** fills a battery (any item that holds FE, from any mod) from the store; **Drain**
empties one into the store. Each works every tick, up to 50,000 FE a tick or as fast as the
battery allows. Take the battery out when it is done.

| Call | On | Gives |
|---|---|---|
| `cell.stored`, `cell.capacity` | Energy Interface | Energy held now; the most it can hold |
| `bus.stored(face=None)`, `bus.capacity(face=None)` | Energy Serial Bus, `usb.energy` | The same for the target |

All of them have `source(...)` and `destination(...)` as section 6.4 says. In a fluid end,
`slot` is a tank number. Energy has no kinds, so `only`, `without` and `what` are not used:

```python
move(net.fluid_bus("boiler"), net.fluid_interface("tanks"), "water", 3000)
move(net.energy_interface("battery"), net.energy_bus("machine"), count=50000)
```

### 6.7 Experience

**Experience Interface** (`net.experience_interface()`): a block that holds experience. It
holds real experience only and has no limit.

At the block, right-click to open its screen: type a number of levels and press **Store** or
**Take**, or use **Store All** and **Take All**. Storing takes your top levels at what those
cost, and taking gives enough to climb that many levels from where you are, so nothing is
gained or lost. The **Lock** button, which only the owner can press, sets who may store and
take: the owner, the owner's team, or everyone. Picked up, the block keeps its experience.

It does not accept liquid experience from other mods.

A program can read it, and `move` between two of them moves points (`move(bank, other, count=500)`):

| Attribute | Gives |
|---|---|
| `bank.points` | Experience held, in points |
| `bank.levels` | The level those points would take a player to from nothing |
| `bank.lock` | `"private"`, `"team"` or `"public"` |

### 6.8 Screens: an Item Serial Bus working a block's screen

Some blocks keep nothing in the world for a plate to reach. A crafting table's slots exist only
on the screen of whoever has it open, and many other mods' machines are the same. For these, a
**Item Serial Bus** (or a USB) can open the block's screen and work its slots.

The Bus opens the screen as its **owner**, the player who put the plate on, the same way that
player's right-click would. So a block, or a land-claim mod, that wouldn't let the owner in won't
let the Bus in either. A Bus with no owner (placed by a command or a machine) can't open screens.

```python
from logistics import network, Slot
net = network()
store, table = net.interface("store"), net.bus("crafter")

with table.open_screen() as screen:               # closed at the end, even after an error
    screen.put(Slot(1), "oak_planks", sources=store)
    screen.put(Slot(4), "oak_planks", sources=store)
    print(screen.take(Slot(0), store))            # 4 sticks, into the store
```

| Call | Does |
|---|---|
| `bus.open_screen()` | Opens the screen of the block the Bus points at and returns a `Screen`. If this Bus already has one open, that one is returned |
| `screen.slots()` | The numbers of the screen's own slots |
| `screen.contents()` | What they hold: a list of `{"slot": 0, "item": "minecraft:stick", "count": 4}` |
| `screen.put(slot, what=None, count=None, sources=..., need=None, wait=False)` | Moves into one slot from `sources`; returns how many. `what`, `count`, `need` and `wait` mean what they do in `move` |
| `screen.take(slot, destinations, what=None, count=None)` | Moves out of one slot; returns how many. With no `count`, the whole slot |
| `screen.source(slot)`, `screen.destination(slot)` | One slot as an end for `move` (section 6.4), with `only=` and `without=` as usual |
| `screen.press(button, times=1)` | Presses a button on the screen, as a player clicking it; `times` presses it that many times, for a screen with one button that steps through its outputs. Returns `False` if the last press was refused; some screens accept any number, so check the product slot |
| `screen.close(leftovers=None)` | Closes it. Returns how many items had nowhere to go |

**Slot numbers are the screen's.** A crafting table's screen holds its product (slot 0) and its
grid (1 to 9, left to right, top row first). Hold the probe in Slots mode (section 8) and open
the block to see them.

Tested vanilla screens and their slots:

| Block | Slots |
|---|---|
| Crafting table | product 0, grid 1 to 9 |
| Smithing table | template 0, base 1, addition 2, product 3 |
| Grindstone | inputs 0 and 1, product 2 |
| Stonecutter | input 0, product 1; choose with `press` |
| Loom | banner 0, dye 1, pattern item 2, product 3; choose with `press` |

**Button numbers.** A screen's buttons aren't labelled the way its slots are, so the probe tells
you each one when you click it: with the probe in Slots mode in your hand, click a button on
any screen and its number shows above the screen for a moment and is written to your chat
(only you see it), for example `[Probe] Button 2 on Stonecutter. In a program: screen.press(2)`.
A stonecutter's or loom's choices are numbered 0, 1, 2… across the grid, top row first.

If a click shows no number, that screen's button works some other way, and a program can't
press it.

A stonecutter's list of choices changes with its input, so a number found for stone isn't the
same for granite. A program can also try buttons until the product is the one it wants:

```python
with cutter.open_screen() as screen:
    screen.put(Slot(0), "stone", 1, sources=store)
    for n in range(40):
        screen.press(n)
        if [e for e in screen.contents() if e["slot"] == 1 and e["item"] == "minecraft:stone_bricks"]:
            break
    screen.take(Slot(1), store)
```

**Screens that cost levels.** An anvil charges levels when its product is taken out; an
enchanting table charges when one of its offers is pressed. A program pays from a source it
names, never from a player:

```python
bank = net.experience_interface("bank")

with net.bus("anvil").open_screen(xp=bank) as screen:
    screen.put(Slot(0), "iron_sword", sources=store)      # two worn swords...
    screen.put(Slot(1), "iron_sword", sources=store)
    print(screen.cost())                                  # ...asks 2 levels
    screen.take(Slot(2), store)                           # repaired; the levels come from bank

with net.bus("table").open_screen(xp=bank) as screen:
    screen.put(Slot(0), "book", 1, sources=store)
    screen.put(Slot(1), "lapis_lazuli", 3, sources=store)
    print(screen.cost())                                  # [3, 6, 9]: buttons 0, 1 and 2
    screen.press(2)                                       # the third offer
    screen.take(Slot(0), store)
```

| Call or attribute | Does |
|---|---|
| `bus.open_screen(xp=None)` | `xp` says where levels are paid from |
| `screen.xp` | The same, changeable at any time. `None` means nowhere: a step that costs levels raises `NetworkError` |
| `screen.cost()` | What the screen asks now, in levels. An anvil: one number (0 when it has nothing to make). An enchanting table: a list of three, one per offer (0 where there is none). Other screens: `None` |

What can pay:

| Source | Spends |
|---|---|
| An Experience Interface | Its points |
| A Fluid Interface, Fluid Serial Bus or USB holding liquid experience | The fluid, at 20 mB a point. Only fluids other mods mark as liquid experience (the `c:experience` tag) count. It is only used up, never turned into levels anyone can take |
| A list of these | The first one that holds enough |

The Bus acts as a player who has exactly the levels the screen asks, and only what the block
really charges is taken. A 2-level anvil job costs 16 points, the price of levels 0 to 2. An
enchanting table offer at level 9 needs the source to hold level 9's worth (135 points), but
takes only the third offer's 3 levels from the top: levels 6 to 9, 63 points. A player who
has more levels than they need pays more for the same thing, so a Bus is never more
expensive than a player.

Not yet: renaming on the anvil.

**Furnace experience.** A furnace keeps the experience its smelting earns until a player
takes from its output slot by hand, or it is broken; then it comes out as orbs. A plate
emptying a furnace releases none, as a hopper doesn't, so it waits in the furnace. A Bus taking
the output through the furnace's screen releases it as orbs on the ground beside the Bus.

**A product slot** (the crafting table's slot 0) gives only its whole stack, and taking from it
uses up the ingredients, as it does for a player. Nothing can be put into it.

**Items on a screen exist only while it is open.** When it closes, the block hands back whatever
was left in it, as it would to a player. That goes to `leftovers` if given (one destination or a
list), then to any Interface on the network that will take it. Anything with nowhere to go is
dropped beside the Bus, never destroyed. A screen left unused for a minute closes itself, so a
program that stops half-way can't keep items locked in a grid. After it has closed, using the
`Screen` raises `NetworkError`.

It works through the same door a player uses, so most other mods' screens should work too.
Where a mod refuses stand-in players, or does part of its work only in the player's own game,
the call fails or does nothing.

### 6.9 Recipes

A `Recipe` says which slots of a screen get which ingredient, and which slot the product comes
out of. Fill it in once; use it as often as you like.

```python
from logistics import network, Slot, Recipe
net = network()
store, table = net.interface("store"), net.bus("crafter")

blocks = Recipe(sources=store, destination=store)
for n in range(1, 10):
    blocks.ingredient("coal", Slot(n))            # one coal in every grid slot
blocks.result(Slot(0), "coal_block", 1)           # makes one block of coal, in slot 0

if blocks.can_craft(4):
    with table.open_screen() as screen:
        print(blocks.craft(4, screen))            # {'minecraft:coal_block': 4}
```

| Call or attribute | Does |
|---|---|
| `Recipe(sources=None, destination=None, xp=None)` | A new, empty recipe. Ingredients come from `sources`; products go to `destination` (each one end or a list); levels the screen costs are paid from `xp`, or from the screen's own `xp` if left out |
| `recipe.ingredient(what, slot, amount=1)` | This name or filter, into this slot, this many each run. Returns the recipe, so calls can be chained |
| `recipe.button(number, times=1)` | A button pressed each run, after the ingredients are in and before the product is taken: a stonecutter's or loom's choice. `times` presses it that many times, for a screen with one button that steps through its outputs. Call it again for more presses; they happen in the order added |
| `recipe.result(slot, what=None, amount=None, destination=None)` | A slot the product is taken from. Call it once for each product slot. `what` and `amount` are checked after each run; `destination` sends this product somewhere other than the recipe's own |
| `recipe.max_runs(sources=None)` | How many runs the sources can supply now |
| `recipe.can_craft(runs=1)` | `True` if they hold enough for that many runs |
| `recipe.runs_for(count, slot=None)` | How many runs make at least `count` of the product. Needs the result's `amount` |
| `recipe.craft(runs=1, screen, wait=False)` | Does the runs on an open screen and returns what was made, as `{item: count}`. `wait=True` waits for missing ingredients |
| `recipe.ingredients` | A dict: slot number to `{"what": ..., "amount": ...}` |
| `recipe.results` | A dict: slot number to `{"what": ..., "amount": ..., "destination": ...}` |
| `recipe.buttons` | A list of presses in order: `{"button": ..., "times": ...}` |
| `recipe.sources`, `recipe.destination`, `recipe.xp` | Plain attributes: read or change them |

A recipe that chooses with a button, here a red pattern on a banner at the loom (click the
pattern once with the probe in Slots mode to learn its number):

```python
banner = Recipe(sources=store, destination=store)
banner.ingredient("white_banner", Slot(0)).ingredient("red_dye", Slot(1))
banner.button(1)                                  # the pattern
banner.result(Slot(3), "white_banner", 1)

with net.bus("loom").open_screen() as screen:
    banner.craft(1, screen)
```

If a run can't be finished (an ingredient runs out, the product isn't what the recipe says, or
it has nowhere to go), `craft` raises `NetworkError`. What was already laid out goes back when
the screen closes, so with a `with` block nothing is lost.

### 6.10 World plates: Logic Pin, Placer and Breaker

Three more plates that go on cables like the others, one per side. Instead of reaching into a
container, they act on the block they point at. Each is a device with a name and addresses,
found with `net.pin()`, `net.placer()` and `net.breaker()`.

All three can say what is in front of them:

| Call or attribute | Gives |
|---|---|
| `plate.block()` | `{"block": "minecraft:wheat", "state": {"age": "7"}, "air": False, "fluid": None}`. State values are text (`"true"`, `"7"`), as the game writes them. `fluid` names a fluid in that space, or is `None` |
| `plate.target` | Just the block's name |

**Logic Pin** (`net.pin()`). One redstone line, like a pin on a microcontroller, and an eye on
the block it points at, like an observer.

```python
door = net.pin("door")
door.write(15)              # power it at full strength; write(0) switches it off
door.pulse(4)               # on for 4 ticks (a fifth of a second), then off by itself

sensor = net.pin("sensor")  # a new pin reads
if sensor.read() > 0:
    print("powered")
sensor.wait_for("rising")   # sleep until the signal comes on

crop = net.pin("wheat")
crop.wait_for("block")      # sleep until the wheat grows a stage
print(crop.block()["state"]["age"])
```

| Call or attribute | Does |
|---|---|
| `pin.mode` | `"input"` or `"output"`. Set it to change; `write()` and `pulse()` make it an output |
| `pin.read()` | The level, 0 to 15: what arrives as an input, or what it drives as an output |
| `pin.write(level)` | Drives a level, 0 to 15 (`True` and `False` mean 15 and 0) |
| `pin.pulse(ticks=2, level=15)` | Drives a level for some ticks (20 a second), then drops to 0 by itself |
| `pin.wait_for(what="change", timeout=None)` | Sleeps until `"rising"` (the signal comes on), `"falling"` (goes off), `"change"` (its level changes at all) or `"block"` (the block it points at, or its state, changes). Returns `True`, or `False` if `timeout` seconds pass first |

- As an output, a pin powers the block it points at **strongly**, as a lever powers the block it
  is on, so the signal also reaches through that block. Redstone dust joins to a pin.
- Signals are read only while the pin is an input; as an output it would only read its own.
- Nothing is missed between looks: even a pulse too short to see is counted.

**Placer** (`net.placer()`). Puts something into the space in front of it, as its owner would.
The space must be air, or hold something that gives way (grass, flowing water).

```python
placer.place("cobblestone", sources=store)    # one item from the sources, placed
placer.pour("water", sources=tanks)           # a bucket's worth (1000 mB), poured as a source
```

| Call | Does |
|---|---|
| `placer.place(what=None, sources=...)` | Takes one item from `sources` (one source or a list) and places it. `what` is a name or filter; left out, the first block found is placed. Returns the item's name. If it can't be placed (it can't stand there, or its owner may not build there), it goes back where it came from and `NetworkError` says so |
| `placer.pour(fluid=None, sources=...)` | Takes 1000 mB from a fluid interface, fluid input or fluid bus and pours it. Returns the fluid's name |

**Breaker** (`net.breaker()`). Breaks the block in front of it and holds nothing itself:

```python
got = breaker.break_block(to=store)    # {"minecraft:cobblestone": 1}
```

| Call | Does |
|---|---|
| `breaker.break_block(to)` | Breaks the block in front, taking as long as the right tool would, and returns what it got: `{name: count}`. `to` is one destination or a list: interfaces, outputs or buses for items; fluid ones for fluid |

- It breaks as the best **unenchanted netherite** tool for the block would (pickaxe, axe, shovel,
  hoe or sword), at that tool's speed: cobblestone takes about a third of a second, obsidian
  about eight seconds. It never breaks bedrock, or anything else the game makes unbreakable.
- **All or nothing.** Every drop must have somewhere to go before the block breaks. If one
  doesn't, nothing breaks and `NetworkError` names the item that had no room. A container breaks
  with its contents, which count as drops (a shulker box keeps them inside itself, as usual).
- Drops are worked out once for a block and kept until that block changes, so breaking can't be
  retried until chance gives a better drop.
- Pointed at a fluid's **source**, it takes it up: 1000 mB into a fluid destination.
- Not caught: experience, and blocks that fall as a result (a torch on the broken block).
- No Fortune or Silk Touch.

**Acting for the owner.** The Placer and Breaker act as the player who put them on (section
6.8), so they can't build or break where that player couldn't: in someone else's land claim,
say. A plate with no owner can't place or break at all.

**Cables and fluids.** Cables count as solid to water and lava, which flow around them rather
than washing them away.

**Recipes:** four cables around an observer (Logic Pin), a dispenser (Placer) or a diamond
pickaxe (Breaker).

---

## 7. The `network` module

```python
import network
```

### 7.1 This computer

| Call | Gives |
|---|---|
| `network.ifconfig()` | A dict: `ip`, `mac`, `name`, and `gateway`, `external_ip`, `port` (these three are `None` without an antenna) |
| `network.hostname()` | This computer's name |
| `network.hostname(name)` | Sets it |

### 7.2 Messages

| Call | Does |
|---|---|
| `network.send(address, data)` | Sends text, numbers, lists or dicts to another computer. Returns `True` if delivered, `False` if that computer is off or its inbox is full |
| `network.receive(timeout=None)` | Waits for the next message. With a timeout in seconds, returns `None` if none arrives |
| `network.scan(external_ip)` | The devices behind another antenna: a list of dicts with `address`, `ip`, `mac`, `kind`, `name` |

An address is a name, internal IP or hardware address on the same network (no antenna
needed), or `"external IP:port"` for a computer behind another antenna.

A received message has `.sender` (the address to answer) and `.data`.

```python
network.send("192.168.1.3", {"ore": 128})
msg = network.receive(5)
if msg:
    network.send(msg.sender, "thanks")
```

A computer receives only while it is switched on. Computers start when first opened.

### 7.3 The antenna

| Call | Gives |
|---|---|
| `network.Radio()` | The antenna that is this network's router |
| `radio.external_ip` | The address other networks reach this one by |
| `radio.frequency` | The frequency it is tuned to |
| `radio.tune(frequency)` | Changes it. Only antennas on the same frequency hear each other |
| `radio.access` | Who may reach this network from outside: `"private"`, `"team"` or `"public"` |
| `radio.set_access(access)` | Changes it |
| `radio.tier` | `"short"`, `"long"` or `"infinite"` |
| `radio.range` | Reach in blocks; `None` for infinite |

Two antennas can talk when they are on the same frequency and within the larger of their two
ranges. Height does not count. Across dimensions, both must be Infinite.

The antenna being reached also decides who it lets in. This applies to messages, to listing
devices and to `ssh`:

| Access | Lets in |
|---|---|
| `private` | Only devices placed by the antenna's owner. Every antenna starts like this |
| `team` | The owner's devices, and those of players on the owner's team (the game's `/team`) |
| `public` | Anyone on the same frequency and in range |

Devices on the same cables never need an antenna and are not affected by this.

| Antenna | Reach |
|---|---|
| Short-Range | 24 blocks |
| Long-Range | 100 blocks |
| Infinite | Any distance, any dimension |

### 7.3b Antenna upgrades: transport and chunk loading

**Right-click an antenna** for its screen: two upgrade slots, and the switch for chunk loading.
Its addresses still show above the hotbar. An antenna takes one upgrade of each kind; breaking it
drops them. (Qbots will take the same upgrade items.)

| Upgrade | Does |
|---|---|
| **Transport Upgrade** | Lets programs use the devices behind another antenna (that also has one), and `move()` items, fluid and energy between the two networks |
| **Chunk Loader Upgrade** | Keeps the chunks of the antenna's whole cable network loaded and working while nobody is near. A Server takes one too (section 7i) |

#### Transport

```python
from logistics import network, move
net = network()
mine = net.remote("100.64.0.2")             # the network behind that antenna
move(mine.bus("ore_chest"), net.interface("store"), "raw_iron", 64)
print(mine.energy_interface().stored())
```

- `net.remote(address)` is the network behind another antenna, by its external address. It
  has the same finders as `net`: `.interface()`, `.bus("name")`, `.devices()` and the rest.
- Its devices work like local ones: `count()`, `contents()`, `watch()`, a pin's `read()`,
  a placer, and in `move()` as sources and destinations, from either side. Their address says
  where they are: `mine.bus("ore_chest").mac` is `"100.64.0.2/02:1a:..."`.
- **Both antennas need a Transport Upgrade.** Without them, finding and listing devices works,
  but using them is refused with a message that says which antenna lacks one.
- The radio's rules still apply (section 7.3): the same frequency, in range, and the far
  antenna's access must let this computer's owner in. Infinite antennas carry across dimensions.
- **Rate:** each antenna carries a limited amount each second, set by the server: 1,024 items,
  64,000 mB and 2,000,000 FE by default. A move through it carries no more than may cross now;
  `move(..., need=n)` waits (with `wait=`) or reports when the rate is used up. Experience isn't
  limited.
- Displays can't be drawn on from another network.
- Both ends must be loaded: a Chunk Loader Upgrade, or a player nearby.

#### Chunk loading

- With a **Chunk Loader Upgrade** fitted and **chunk loading switched on** (it is, unless
  switched off), the antenna keeps loaded every chunk its cable network reaches: the cables,
  computers, plates and displays. Machines there keep working.
- Up to **25 chunks per antenna** (the server sets it), the nearest to the antenna first. The
  screen says how many it holds, and if the network spreads over more than it may hold.
- A player may have **4 working loaders** (the server sets it; operators are exempt). Further
  ones pause, and the screen says so.
- Switch it on and off with the button in the antenna's screen, or from a program:

```python
import network
radio = network.Radio()
radio.chunk_loading = False
print(radio.upgrades())            # ['chunk_loader', 'transport']
print(radio.chunks())              # {'status': 'off', 'chunks': 0, 'wanted': 0}
```

`radio.chunks()["status"]` is `"loading"`, `"off"`, `"no_upgrade"`, `"disabled"` (by the
server), `"owner_offline"` (when the server pauses loaders of players who are offline),
`"over_limit"`, `"inactive"` (the antenna is) or `"no_owner"` (a machine placed it).

What is loaded is remembered across restarts. Breaking the antenna, taking the upgrade out or
switching it off lets the chunks go within a second.

**Recipes (placeholders):** Transport Upgrade: `EHE` / `RMR` / `GGG` (ender pearls and a hopper,
redstone, an integrated circuit, gold nuggets). Chunk Loader Upgrade: `OYO` / `RMR` / `GGG` (obsidian and
an eye of ender over the same).

### 7.4 Remote login: `ssh`

```
/ $ ssh workshop
/ $ ssh 100.64.0.12:1005
```

Typed in a computer's terminal, `ssh` turns that terminal into the other computer's: what you
type runs there and its screen is shown here. `exit` (or `logout`) at the other computer's
prompt ends the session. Ctrl+C stops the program running there.

Two things decide whether you get in:

1. **The network.** The other computer must be on the same cables, or behind an antenna that
   is on your frequency, in range, and whose access setting lets your computer's owner in.
2. **The computer.** Its `remote_login` setting (section 1.5) must allow you: by default only
   computers of the same owner; with `password`, anyone who types the right one.

Things to know:

- Who you are is decided by who owns the computer you are typing at.
- The other computer is switched on if it wasn't. It shows a notice when someone logs in.
- The files and the editor stay this computer's. Only the terminal is the other one's.
- The link is checked each time you enter a line. If an antenna has been retuned, moved out
  of range or made private, the session ends.
- A password is visible while you type it, though it isn't written to the terminal afterwards.

---

## 7b. The `events` module: running code when things happen

```python
import events
```

Instead of a loop that checks again and again, a program says what to do when, then calls
`events.run()`. In between, it sleeps: a waiting program costs the game nothing.

```python
import events
from logistics import network
net = network()

@events.every(5)                                    # every five seconds
def report():
    print("the store holds", net.interface("store").count())

events.after(60, events.stop)                       # once, a minute from now
events.on_pin(net.pin("button"), "rising", lambda e: net.pin("door").pulse(20))
events.on_change(net.bus("chest"), lambda change: print(change["item"], change["now"]))
events.on_message(lambda msg: print("from", msg.sender, ":", msg.data))

events.run()                                        # until events.stop() or Ctrl+C
```

| Call | Does |
|---|---|
| `events.every(seconds, function)` | Runs the function every so many seconds, starting that many seconds from now. Returns a timer; `timer.cancel()` stops it |
| `events.after(seconds, function)` | Runs the function once, so many seconds from now. Returns a timer |
| `events.on_pin(pin, what, function)` | Runs the function when a Logic Pin sees `"rising"`, `"falling"`, `"change"` or `"block"` (section 6.10). The function gets an event with `.name`, `.event`, `.level` and `.block` |
| `events.on_change(device, function, what=None)` | Runs the function for each change to what a device holds (section 6.4b). `device` can also be a watch |
| `events.on_message(function)` | Runs the function for each message from another computer: it gets `.sender` and `.data`. While `run()` is going, messages come here rather than to `network.receive()` |
| `events.on_touch(display, function)` | Runs the function when a player touches a monitor or panel (section 7c). Widgets have their own `on_press` and `on_change` |
| `events.run()` | Handles everything above until `events.stop()` or Ctrl+C |
| `events.stop()` | Makes `run()` return once the function running now has finished |
| `events.clear()` | Forgets every timer and handler |

- Each of `every`, `after`, `on_pin`, `on_change` and `on_message` can be given its function, or
  used as a decorator above a `def`, as `@events.every(5)` is above.
- A function that raises an error stops `run()` with that error, as in any program.
- Pin events and messages are pushed by the game the moment they happen; nothing polls for
  them. Watched containers are looked at four times a second while `run()` is going.
- Timers count real time. If the program falls behind (a function took a long time), a
  repeating timer carries on from now rather than running several times to catch up.
- Each program started with `run` starts with no handlers. After `run()` returns, the
  handlers stay set up, so it can be called again.

---

## 7c. Displays: Text Monitors and Control Panels

Two blocks show what a program draws:
- A **Text Monitor** is a grid of characters, each with its own text and background colour.
- A **Control Panel** holds ready-made widgets that players can press and slide: buttons,
  toggles, sliders, gauges, lamps, labels, readouts and graphs.

Both are devices on a cable network (or touching a computer), found like any other device:

```python
from logistics import network
net = network()
mon = net.monitor("status")      # by name; net.monitor() if the network has only one
panel = net.panel("reactor")
```

### 7c.1 Placing them: screens of several blocks

- A display faces the player who places it. Placed against the face side of another of the same
  kind, it faces the same way, to grow the screen.
- **Blocks of one kind, side by side and facing the same way, join into one screen**, up to
  **8 blocks wide and 6 high**. A bigger group, or one that isn't a rectangle, is split into
  rectangles from the top left: each takes as many blocks across as it can, then as many full
  rows down as it can.
- A screen is **one device** on the network, whatever its size. Its name and address belong to
  the screen, not to a block: adding blocks or breaking one keeps them (and what it shows), as
  long as part of the screen is left. Two screens that join keep the bigger one's.
- The probe, used on any block of a screen, names the whole screen.
- The screen's face doesn't join the network; its other sides do.
- Screens light themselves, so they read the same by day and night.

### 7c.2 Touching them

- **Right-click** a screen to touch it: a panel's button is pressed, its toggle flipped, and a
  slider jumps to where you clicked. Any item can be in your hand, except a block (which is
  placed) and the probe.
- **Sneak and right-click with an empty hand** for a **close-up view**: the screen as large as
  the window allows. Click to touch, drag a slider. Escape (or walking more than 12 blocks away)
  closes it.
- What the touch does reaches a program through the events module (section 7b): see
  `on_press`, `on_change` and `on_touch` below.

### 7c.3 Drawing

**Drawing happens at once.** Unlike other calls on the network, a program doesn't wait for the
game's next tick: writing a hundred lines takes a moment, not five seconds. Players within 64
blocks see changes up to ten times a second.

What a screen shows is kept with the world, and stays when the program that drew it stops.

### 7c.4 Text Monitor

At text scale 1, each block holds **15 columns and 10 rows** (a 3x2 screen: 45 by 20).
Columns and rows count from 0 at the top left.

| Call | Does |
|---|---|
| `mon.print(*things)` | Writes a line at the cursor, as `print()` does: long lines wrap, and the screen scrolls up at the bottom |
| `mon.write(text)` | Writes at the cursor and moves it on, without wrapping (what runs off the right is cut). `"\n"` starts the next row |
| `mon.write_at(x, y, text, color=None, background=None)` | Writes at column x, row y. The cursor doesn't move |
| `mon.set_cursor(x, y)`, `mon.cursor()` | Moves the cursor; where it is, as `(x, y)` |
| `mon.color(text=None, background=None)` | The colours writing uses from now on. `None` leaves one as it is |
| `mon.clear(background=None)` | Blanks the screen, and puts the cursor at the top left |
| `mon.clear_line(y=None)` | Blanks the cursor's row, or row y |
| `mon.fill(x, y, width, height, background, char=" ")` | Fills a rectangle of cells: bars and boxes |
| `mon.scroll(lines=1)` | Moves everything up (down, if negative) |
| `mon.size()` | `(columns, rows)` |
| `mon.scale` | The text size, from 0.5 (twice as many columns and rows) to 5, in steps of 0.5. Changing it keeps what fits |
| `mon.on_touch(function)` | Runs the function when a player touches the screen: it gets `.x` and `.y` (the character cell) and `.player` |

**Colours** are names or numbers 0 to 15: `white`, `orange`, `magenta`, `light_blue`,
`yellow`, `lime`, `pink`, `gray`, `light_gray`, `cyan`, `purple`, `blue`, `brown`, `green`,
`red`, `black` (the dye colours, in their order). Text starts white on black.

```python
mon.clear()
mon.fill(0, 0, 45, 1, "blue")
mon.write_at(1, 0, "STORAGE", "white", "blue")
for row, stack in enumerate(net.interface("store").contents()):
    mon.write_at(1, 2 + row, stack["item"], "light_gray")
    mon.write_at(30, 2 + row, str(stack["count"]), "lime")
```

### 7c.5 Control Panel

A panel is a grid of cells, **four to a block each way** (a 2x2 panel: 8 by 8). A widget is
placed with `at=(x, y)`, its top-left cell, and `span=(w, h)`, its size in cells. A widget placed
later is drawn over one placed before.

| Widget | Made with | Its value |
|---|---|---|
| Button | `panel.button(label, at, span=(2, 1), color=None)` | The number of presses isn't kept; use `on_press` |
| Toggle | `panel.toggle(label, at, span=(2, 1), on=False, color=None)` | On or off: `.on` |
| Slider | `panel.slider(label, min=0, max=100, at, span=(4, 1), value=None, step=1, unit="", color=None)` | `.value`, in steps of `step` (0 for any value). Taller than wide, it stands upright |
| Gauge | `panel.gauge(label, min=0, max=100, at, span=(4, 1), value=0, unit="", color=None)` | `.value`, shown as a bar. Taller than wide, it fills upwards |
| Lamp | `panel.lamp(label, at, span=(2, 1), on=False, color="red")` | On or off: `.on` |
| Label | `panel.label(text, at, span=(4, 1), color=None)` | Text. Two or more cells high, short text is drawn large |
| Readout | `panel.readout(label, at, span=(2, 2), value=0, unit="", color=None)` | `.value`, shown as a number |
| Graph | `panel.graph(label, at, span=(4, 2), min=0, max=0, unit="", color=None)` | `graph.add(value)` adds a point; it shows the last 60. With `min` and `max` equal, it scales itself |

Every widget has:

| | |
|---|---|
| `widget.value` | Its value, read or set (a toggle or lamp: 1 for on, 0 for off) |
| `widget.on` | For a toggle or a lamp: `True` or `False`, read or set |
| `widget.label` | Its label, read or set |
| `widget.set(label=, color=, value=, min=, max=, step=, unit=, at=, span=)` | Changes several things at once |
| `widget.remove()` | Takes it off the panel |
| `widget.on_press(function)` | For a button: runs the function when a player presses it. It gets `.player` |
| `widget.on_change(function)` | For a toggle or a slider: runs the function when a player changes it. It gets `.value` and `.player` |

And the panel: `panel.clear()` takes every widget off; `panel.size()` is `(columns, rows)`;
`panel.on_touch(function)` runs for every press and change on it (the event has `.widget`, the
widget's number, and `.label`).

`on_press`, `on_change` and `on_touch` need `events.run()` (section 7b) and can be used as
decorators:

```python
import events
from logistics import network
net = network()
panel = net.panel("reactor")
panel.clear()
panel.label("Reactor 1", at=(0, 0), span=(8, 1), color="yellow")
pump = panel.toggle("Pump", at=(0, 1), span=(3, 1))
scram = panel.button("SCRAM", at=(6, 1), span=(2, 1), color="red")
speed = panel.slider("Speed", 0, 100, at=(0, 2), span=(5, 1), unit="%")
power = panel.gauge("Power", 0, 2000000, unit="FE", at=(0, 3), span=(5, 1))
load = panel.graph("Stored", at=(0, 4), span=(8, 4))

@pump.on_change
def pump_changed(event):
    net.pin("pump").write(15 if event.value else 0)

@scram.on_press
def stop_everything(event):
    print(event.player, "pressed SCRAM")
    events.stop()

@events.every(1)
def update():
    stored = net.energy_interface().stored()
    power.value = stored
    load.add(stored)

events.run()
```

### 7c.5b Web Panels: HTML and CSS

A **Web Panel** shows a page of HTML and CSS. Like the other displays, Web Panels side by side
join into one screen (up to 8 x 6 blocks), and it is reached over the network. Each block is
**128 CSS pixels** across, so a 3 x 2 panel is a 384 x 256 page.

```python
import events
from logistics import network

net = network()
web = net.web_panel("smeltery")
web.show("/app/index.html")         # the page, with its stylesheet
web.state["iron"] = 1284            # the page shows it where it says {{iron}}
web.state.update(gold=312, running=5)

@web.on("smelt")                    # <button data-event="smelt">Smelt all</button>
def smelt(event):
    print(event.player, "pressed", event.event)

events.run()
```

```html
<!-- /app/index.html -->
<html><head><link rel="stylesheet" href="style.css"></head><body>
  <h1>Smeltery</h1>
  <div class="row"><div class="card">Iron <b>{{iron}}</b></div><div class="card">Gold <b>{{gold}}</b></div></div>
  <p>Furnaces running: {{running}} of 8</p>
  <button data-event="smelt">Smelt all</button>
</body></html>
```

| Call | Does |
|---|---|
| `web.show(path)` | Shows a page from the drive. `<link rel="stylesheet" href="...">` files beside it are put into the page |
| `web.html(text)` | Shows HTML given as text |
| `web.state[name] = value`, `web.state.update(...)` | Values the page shows. Players see a change at once; the page itself isn't sent again |
| `web.state[name]`, `web.state.get(name)` | What was last set |
| `web.on(name, function)` | Runs a function when a player clicks an element with `data-event="name"` or `id="name"` (needs `events.run()`) |
| `web.on_click(function)` | Runs a function for every such click |
| `web.size()` | (width, height) in CSS pixels |
| `web.clear()` | An empty page and no state |

**State in the page.** `{{name}}` is replaced by the value, shown as text (`<` stays `<`, it isn't
HTML). `{{{name}}}` puts the value in as HTML. A name can reach inside a dict or a list:
`{{ores.iron}}`, `{{items.0}}`. A name with no value shows nothing. It works anywhere in the page,
in text or attributes: `style="width: {{progress}}%"`.

**Lists.** `{{#items}} ... {{/items}}` repeats what is between for each item of a list; inside,
an item's fields are names (`{{name}}`, `{{count}}`), and `{{.}}` is the item itself.
`{{^items}} ... {{/items}}` shows only when the list is empty (or the value is missing, false or
0). For a value that isn't a list, `{{#name}}` shows its inside when the value is true.

```html
<div class="list">
  {{#items}}<div class="row"><img src="item:{{id}}"> {{name}} <b>{{count}}</b></div>{{/items}}
</div>
{{^items}}<p>Nothing matches.</p>{{/items}}
```
```python
web.state["items"] = [{"id": "minecraft:iron_ingot", "name": "Iron Ingot", "count": 1284}, ...]
```

**Item pictures.** `<img src="item:minecraft:iron_ingot">` shows an item's icon, drawn by the game
as the inventory draws it (block items in 3D, modded items too). It is 16 x 16 unless CSS sizes it.

**Text fields.** `<input name="search" placeholder="Search...">` is a field players type in, in the
close-up: click it, type, Enter. Each player types into their own copy. The program hears it as an
event named after the field: `@web.on("search")` gets `.value` (the text), while typing (once the
player pauses) and again on Enter, when `.enter` is true. `<input type="button" value="Go">` is a
button.

```python
@web.on("search")
def search(e):
    web.state["items"] = [i for i in everything if e.value.lower() in i["name"].lower()]
```

**Scrolling.** In the close-up the mouse wheel scrolls: a box with `overflow: auto` (or `scroll`)
under the pointer scrolls its contents; otherwise the whole page scrolls, if it is taller than the
panel. Each player scrolls their own view, and the panel in the world shows where you left it.

**Clicks.** Right-click the panel to click what is under your crosshair. **Sneak + right-click
with an empty hand** opens it close up, where the mouse hovers (`:hover` and `:active` styles
show), clicks, types and scrolls. The function gets an Event with `.event` (the `data-event`, or else the `id`),
`.id`, `.tag`, `.name`, `.value` (the element's attributes) and `.player`.

**How it works.** The server keeps the page and its state; each player's game lays the page out
and draws it (with litehtml, built into the mod), so a page costs the server almost nothing.
Pages can't reach the internet or load anything: what they show comes from the program. A page
is at most 128 KB, its state 32 KB.

**What works (v1):**

| Works | Not yet |
|---|---|
| Text and structure: `div`, `span`, `p`, `h1`-`h6`, lists, `a`, `b`, `i`, `table` | Pictures other than items (camera pictures come next) |
| The box model, colours, backgrounds, borders, rounded corners | CSS grid, `gap` between flex items |
| Flexbox, floats, `position` (relative, absolute) | Transforms, transitions, animations, shadows |
| Fonts: sans-serif, serif, monospace; bold, italic; underline, line-through | `select`, `textarea`, checkboxes |
| Linear and radial gradients; item icons (`item:`) | Typing on a panel in the world (use the close-up) |
| Text fields, scrolling (page and `overflow: auto` boxes) | JavaScript and `<canvas>` |
| `@media` by the panel's size, CSS variables, `:hover`, `:active`, `:nth-child` | |

**Crafting (placeholder):** makes 2: `IRI` / `ISI` / `ICI`: iron around RAM, an LED Screen and a
cable.

### 7c.6 Not yet

Display limits per player (section 9b counts computers and antennas only), themes, and widgets
bound straight to an action without code are planned but not built.

**Recipes (placeholders):** Text Monitor (makes 2): `III` / `ISI` / `ICI` (iron, an LCD Screen,
a cable). Control Panel (makes 2): `BLB` / `ISI` / `ICI` (stone buttons and a lever over iron, an
LED Screen and a cable).

---

## 7d. Qbots: computers that move

A **Qbot** is a computer in a small robot's body. It runs a Python program, and moves one block
at a time, digs, places, uses and attacks, and carries things. The first is the **Iron Qbot**.

A Qbot has **no terminal or editor of its own**: you can't reprogram it on the spot. Its
program is put on it by a **Programmer** (section 9e), as `startup.py`, and worked from its
screen:

| Button | Does |
|---|---|
| **Run** | Starts its program (`startup.py`) from the top. While paused it says **Resume**, and carries on from where it stopped |
| **Pause** | Holds the program where it is, like a breakpoint: at its next step, in the middle of a `time.sleep`, or as a world call comes back. Nothing it was doing is lost |
| **Stop** | Ends the program at once. Its Python is started afresh; its files and what it carries are untouched |

Under the buttons, the screen shows what the program is doing (Running, Paused, Finished,
Stopped, or Stopped with an error) and the last two lines it printed, so `print` is how a
program reports, and an error shows there.

**Log** (in the corner of that strip) shows everything the program has printed, read-only,
over the whole window: up to the last 500 lines, kept while it runs. Scroll to read back; it
follows new output while scrolled to the end. **Back** or Escape returns to the screen.

To copy from it: drag across lines to select them (shift-click extends a selection, Ctrl+A
selects everything), then **Ctrl+C** or **Copy selected**. With nothing selected, Ctrl+C and
**Copy all** copy the whole log. Escape clears a selection before it closes the log.

- A Programmer that installs a program on a Qbot also starts it.
- A Qbot whose program was running when its area unloaded (or the world closed) starts it
  again, from the top, when it loads.
- `ssh` from a computer still reaches a Qbot's shell (section 7.4).


| | Iron Qbot |
|---|---|
| Inventory | **16 slots**, a 4x4 grid (numbered 0 to 15) |
| Hand | One tool, which it digs, attacks and is used with |
| Upgrade slot | One: a Chunk Loader Upgrade (section 7.3b) |
| Energy cell | **1,000,000 FE** |
| Tanks | **Two of 4,000 mB**, one fluid each, so it can carry water and lava at once |
| Digs as | An unenchanted **iron** tool, with nothing in its hand |

- **No fuel.** Moving and working cost nothing. The energy cell and tanks are things it
  carries, to fill machines and tanks with or bring back.
- **Placing it:** it faces you, like a computer.
- **Right-click:** its screen: inventory, hand and upgrade slots, gauges for its cell and
  tanks, and Run, Pause and Stop with what its program printed.
- **Breaking it:** its items fall out. Its files (its drive), name, energy and fluid stay with
  the item.
- It is a computer on the network (cables and devices beside it join it), and counts against
  `limits.qbots_per_player` (5), not the computer limit. A machine-placed Qbot is inactive
  until someone claims it, and the Programmer programs Qbots like computers.
- A step or a turn takes a quarter of a second. While it works, it swings its arms; it hovers
  and glides from block to block.

```python
import qbot
from qbot import inspect, inventory, tools, tanks, energy, Side, QbotError

qbot.forward()
while inspect.front().id == "minecraft:stone":
    qbot.dig()
    qbot.forward()
qbot.turn_around()
inventory.drop(Side.BOTTOM, "cobblestone")      # into a chest below
```

### 7d.1 Moving and sensing

| Call | Does |
|---|---|
| `qbot.forward()`, `back()`, `up()`, `down()` | Moves one block. Raises `QbotError` if something solid or alive is in the way, the area isn't loaded, or its owner may not build there |
| `qbot.turn_left()`, `turn_right()`, `turn_around()` | Turns on the spot. Returns the way it faces now |
| `qbot.position()` | `(x, y, z)`: a Qbot always knows where it is |
| `qbot.facing()` | `"north"`, `"east"`, `"south"` or `"west"` |
| `qbot.info()` | All at once: tier, facing, position, energy, tanks, tool |
| `inspect.front()`, `back()`, `left()`, `right()`, `top()`, `bottom()` | What is on that side: `.id`, `.air`, `.state` (its properties), `.tags`, `.fluid`, `.source`, `.entities` (creatures and items there, by name) |

**Sides** are `Side.FRONT`, `BACK`, `LEFT`, `RIGHT`, `TOP`, `BOTTOM` (or those words in lower
case), as the Qbot faces. Every call below that takes a side defaults to the front.

Seeing isn't looking inside: `inspect` says a chest is a chest; `inventory.read()` says what is in it.

### 7d.2 Working

| Call | Does |
|---|---|
| `qbot.dig(side)` | Digs the block there, taking as long as a player would with the same tool, and returns what it got: `{"minecraft:cobblestone": 1}`. Every drop must fit in its inventory, or nothing is dug |
| `qbot.place(side, item=None)` | Places a block from its inventory: the named one, or the first block it holds |
| `qbot.use(side, item=None)` | Right-clicks there as a player would: with the named item from its inventory, else the tool in its hand, else an empty hand. Works on blocks (levers, doors, bone meal, a hoe on dirt) and creatures (shears on a sheep, a bucket on a cow); what that gives goes into its inventory |
| `qbot.attack(side)` | Attacks a creature there with the tool in its hand (or its fist), at full strength |
| `qbot.pump(side)` | Takes up a source of water or lava (1,000 mB) into a tank |
| `qbot.pour(side, fluid=None)` | Pours 1,000 mB from a tank, making a source block there |

**Digging power.** With nothing in its hand, an Iron Qbot digs as the right iron tool would (an
iron pickaxe for stone, an iron axe for wood), so it can dig iron, gold, diamond and redstone ore
but not obsidian or ancient debris. **A tool in its hand** is used when it does better: its
level and speed count, and enchantments that change what drops (Fortune, Silk Touch) work as
they would for a player. (Efficiency doesn't speed it up yet.)

**Tools wear** like a player's. When one breaks, what the Qbot was doing is done and
`ToolBrokenError` is raised (a kind of `QbotError`).

| Call | Does |
|---|---|
| `tools.equip(item)` | Takes the item from its inventory into its hand (what was there goes back) |
| `tools.unequip()` | Puts it back into the inventory |
| `tools.equipped()` | `{"item", "damage", "max_damage"}`, or `None` |

Everything it does to the world, it does as its owner: land claims and protection apply, and a
Qbot nobody owns (placed by a machine) can't change the world.

### 7d.3 What it carries

| Call | Does |
|---|---|
| `inventory.list()` | `[{"slot": 0, "item": "minecraft:coal", "count": 12}, ...]` |
| `inventory.count(item=None)` | How many of an item (or everything) |
| `inventory.drop(side, item=None, count=None, face=None)` | Into the block there (a chest, a furnace); with nothing there that takes items, onto the ground. Returns how many went |
| `inventory.suck(side, item=None, count=None, face=None)` | From the block there; with no container, picks up items lying there |
| `inventory.insert(side, ...)`, `inventory.extract(side, ...)` | The same, but only with a container |
| `inventory.read(side, face=None)` | What the container there holds |
| `energy.stored()`, `energy.capacity()` | Its cell |
| `energy.give(side, amount=None)`, `energy.take(side, amount=None)` | Energy to or from the block there |
| `tanks.list()` | `[{"fluid": "minecraft:water", "amount": 3000, "capacity": 4000}, {...}]` |
| `tanks.give(side, fluid=None, amount=None)`, `tanks.take(side, fluid=None, amount=None)` | Fluid to or from the block there (a tank, a machine) |

**`face=`** picks which face of that block it reaches, as a bus does: `"near"` (the face touching
the Qbot; the default), `"far"`, `"top"`, `"bottom"`, `"left"`, `"right"`. From beside a
furnace, `inventory.drop(Side.FRONT, "raw_iron", 16, face="top")` fills its input and
`face="left"` (or right) its fuel.

### 7d.3b The camera: pictures of what it sees

```python
import qbot
from logistics import network

pic = qbot.snapshot()                # 64 x 48 pixels of what is in front of it
print(pic.block_at(32, 24))          # "minecraft:stone", "monster", ... or None for the sky
print(pic.counts())                  # how many pixels show each thing

web = network().web_panel("camera")
web.html('<img src="image:cam" style="width: 256px; height: 192px">')
web.image("cam", pic)                # players see the picture, coloured from the real textures
```

| Call | Does |
|---|---|
| `qbot.snapshot(width=64, height=48, pitch=0)` | A picture of what is in front of it, up to 32 blocks away. `pitch` tilts the camera up (positive) or down, in degrees. At most 128 x 96, one a second |
| `pic.width`, `pic.height` | Its size in pixels |
| `pic.block_at(x, y)` | What a pixel shows: a block's id, `"monster"`, `"animal"`, `"player"` or `"creature"`, or `None` for the sky (0, 0 is the top left) |
| `pic.counts()` | How many pixels show each thing |
| `web.image(name, pic)` | Puts it on a web panel, as `<img src="image:name">` (up to 8 on a panel; `None` takes one off) |

The picture is taken on the server: a ray goes from the Qbot's front through each pixel and
records what it hits (blocks, fluids, creatures) and how lit it is. Each player's game then
colours it from the block textures it has (resource packs and modded blocks included), shaded by
light and distance; creatures are flat shapes in a colour by kind (red monsters, green animals,
blue players). On a panel a picture is shown pixelated, two CSS pixels to a pixel unless CSS sizes
it. Pictures are kept on the server for a while (the latest 256), not with the world: after a
restart, take them again.

### 7d.4 Not yet

Better tiers (diamond, with a full chest of slots), inventory filters, pipes and hoppers reaching
into a Qbot, and the Transport Upgrade in a Qbot are planned but not built.

**Recipe (placeholder):** `III` / `ICI` / `IHI`: iron around a computer, over a chest.

---

## 7e. The Laptop: your own computer, carried

A **Laptop** is a computer you carry, for working on computers and Qbots wherever you are.

**Crafting:** shapeless, a Universal Serial Bus, a Computer and an Infinite Antenna. The
computer's files come with it: the first time the laptop is opened they are copied into your
laptop files, under `/imported/computer-<id>/`, so nothing already there is overwritten.

**Memory:** a laptop's programs have 1 MB, four times a computer's. Connected to a computer
or Qbot, though, you work on *that* machine, with its memory.

**One laptop computer per player.** Every laptop you open is the same computer: one shell, one
set of files, kept for you. Craft a new laptop and your files are there. Someone else opening
your laptop gets *their* laptop computer, not yours.

| Do | Get |
|---|---|
| Right-click in the air | The laptop: editor, terminal and files, as on a computer (there is no Settings page) |
| Sneak and right-click in the air | Its two hard-drive slots. The drives appear at `/net/disks/disk1` and `/net/disks/disk2`, for carrying and copying files |
| Right-click a computer or Qbot of yours | A **direct connection**: that machine's own editor and terminal, as if you were at its keyboard. For a Qbot this is the way to edit its files and use its shell by hand; its program keeps running, Ctrl+C stops it and Ctrl+Z pauses it. Someone else's machine can't be opened this way: ssh in, if its owner allows |

- **It runs while you are online and carry a laptop** (anywhere in your inventory), even with
  its screen closed. Otherwise what it runs is **held**: paused until you are back with a
  laptop. Logging off, being banned, dropping or storing it, or burning it all hold it.
- **Lava** (or anything else) that destroys a laptop destroys it and the hard drives in it, with
  their files. Your laptop files themselves stay yours.
- **Reaching other machines:** a laptop has a built-in Infinite antenna. In its terminal,
  `ssh 100.64.0.12:1005` logs in to a computer or Qbot behind an antenna, and `devices
  100.64.0.12` lists what is behind one. It reaches any antenna in its own dimension, and
  Infinite antennas in other dimensions (as two placed antennas do). It hears every frequency;
  each antenna's access setting, and each computer's remote-login setting, still decide who
  gets in.
- A laptop isn't on a cable network, so calls such as `network().bus()` say to use ssh.
- `laptops.enabled = false` in the server config stops laptops opening.

Its editor shows the line a program is on, and F8 steps through it (section 1.3b).

## 7f. The SSH gateway: VS Code, ssh and scp on your PC

The game can run an **SSH server**, so tools on your PC reach your in-game computers, Qbots and
laptop: their files (SFTP) and their terminal (the shell). It is **off** until the server's
admin turns it on, and by default it only accepts connections from the PC the server runs on.

**Turning it on** (`qbotics-server.toml`; in single-player, the world's `serverconfig` folder):

```toml
[ssh_gateway]
enabled = true
port = 2222
bind_address = "127.0.0.1"   # "0.0.0.0" to accept other PCs (open the port; know who has keys)
all_computers = false        # true: logged-in players reach every computer, not only their own
```

**Your key.** You log in with an SSH key, never a password. On your PC, once:

```
ssh-keygen -t ed25519
```

Then copy the line in `id_ed25519.pub` (in `C:\Users\you\.ssh\` on Windows) and, in game:

```
/qbotics ssh key add ssh-ed25519 AAAAC3Nza... you@pc
```

`/qbotics ssh` says whether the gateway is on; `/qbotics ssh key list` and `/qbotics ssh key
remove <number>` manage your keys. A banned player's keys don't work.

**Logging in.** The login name picks the machine:

| Login name | Reaches |
|---|---|
| `laptop` | Your laptop |
| A computer's or Qbot's name (`miner`) | That computer of yours, if it is running (its area loaded) |
| Its hardware address, or device ID | The same, when two share a name |

```
ssh miner@localhost -p 2222          # its terminal
scp -P 2222 mine.py miner@localhost:/  # copy a file onto it
```

In the shell, typing works as in the game's terminal: Ctrl+C stops a program, Ctrl+Z pauses it,
and Ctrl+D on an empty line leaves. A laptop whose player is offline (or carries no laptop) is
held: you can still use its files.

**VS Code.** Microsoft's Remote-SSH extension doesn't work (it installs a server program on the
other end, which an in-game computer can't run). These do:

- **SSH FS** (Kelvin Schoofs): opens a computer's files as a folder in VS Code, with its terminal
  in a terminal tab. In its settings, add a host: host `localhost`, port `2222`, username the
  computer's name, private key your `id_ed25519`, root `/`.
- **SFTP** (Natizyskunk): keeps a folder on your PC in step with a computer. That folder is
  ordinary files, so **real Git works there**: commit and push from your PC, and your GitHub
  token never leaves it. Its `sftp.json`:

```json
{ "name": "miner", "host": "localhost", "port": 2222, "username": "miner",
  "privateKeyPath": "C:/Users/you/.ssh/id_ed25519", "remotePath": "/", "uploadOnSave": true }
```

**What it reaches:** only that computer's own drive (not `/net`), within its 1 MB quota. Nothing
else: no port forwarding, no commands outside the computer's own shell.

## 7g. The Uplink: the real internet (`requests`)

An **Uplink** block on a cable network lets that network's computers make HTTPS requests to the
real internet. A laptop has one built in (its Infinite antenna).

```python
import requests
r = requests.get("https://api.github.com/repos/python/cpython")
print(r.status_code, r.json()["stargazers_count"])
requests.post("https://example.com/api", json={"ore": 64})
```

| Call | Does |
|---|---|
| `requests.get(url, params=None, headers=None)` | Asks for a page or an API's data. `params` (a dict) is added to the address |
| `requests.post(url, data=None, json=None, headers=None)` | Sends data: `json=` as JSON, `data=` a dict as a form, or text |
| `requests.put`, `patch`, `delete`, `head` | The other methods |
| `r.status_code`, `r.ok`, `r.reason` | The answer's status; `ok` is true below 400 |
| `r.text`, `r.json()`, `r.content` | The body: text, read as JSON, or bytes |
| `r.headers` | Its headers, names in lower case |
| `r.url` | Where it ended up, after redirects |
| `r.raise_for_status()` | Raises `requests.HTTPError` for a status of 400 or more |

A request that can't be made raises `requests.RequestException`, saying why.

- **HTTPS only.** The site's certificate is checked against its name.
- **Never private or local addresses** (this server, its network, cloud metadata), whatever a
  name resolves to, including after redirects (up to 5).
- **Limits:** an answer is at most 24 KB on a computer or Qbot, 96 KB on a laptop and 192 KB on
  a Server (reading one takes room for two or three copies of it), a request takes at most
  10 seconds, and one player's computers make at most 30 a minute between them (the server's
  admin sets these). Each request is logged with its owner (site and path, not the query).
- Nothing about you or the server is sent: the request says only that it is from Qbotics.

**Crafting (placeholder):** `DED` / `GAG` / `DMD`: diamonds, ender eyes and gold around a
Long-range Antenna, over an integrated circuit.

## 7h. git: GitHub repositories on a computer

With an **Uplink** on its network (or on a laptop), a computer can copy a GitHub repository onto
its drive, keep it up to date, and push changes back. Type these at the computer's prompt:

| Command | Does |
|---|---|
| `git clone <url> [folder]` | Copies a repository into a folder (by default one named after it). `https://github.com/me/factory`, or `.../tree/dev` for a branch |
| `git pull [folder]` | Brings the folder up to the repository's newest commit. Refuses if you changed files there; `--force` drops your changes |
| `git status [folder]` | What you changed since the last clone, pull or push: `M` changed, `A` new, `D` deleted |
| `git push -m "message" [folder]` | Makes a commit of your changes and pushes it to the branch |
| `git help` | These commands |

The folder is the one you're in unless you give one. In a program, `import git`:

```python
import git
git.clone("https://github.com/me/factory", "/factory")
s = git.status("/factory")        # {"modified": [...], "added": [...], "deleted": [...], ...}
git.pull("/factory")              # force=True drops your changes
git.push("/factory", "Faster smelting")
```

A call that can't be done raises `git.GitError`, saying why.

- **The game does the work**, not the computer's Python, so a repository can be bigger than a
  program's memory: up to 512 KB and 500 files (the admin sets the size). The drive's own space
  still applies.
- **Clone and pull** take the repository's files at one commit (no history). A public repository
  needs nothing else; a private one needs your token.
- A cloned folder keeps a file `.gitqbot`: where it came from, and what each file was, for
  `status`. Leave it there.
- **Push** needs the server to allow it (`git.allow_push`, off unless the admin turns it on) and
  your **GitHub token**. If someone else pushed since your last pull, it refuses: copy your
  changes aside, pull, put them back, push.
- **Your token:** `/qbotics git token` opens a screen to enter it. It is kept in the world's files
  on the server (anyone who can read those can read it). No program can read it, and it is never
  sent to other players. Use a **fine-grained token** for only the repositories you push to, with
  *Contents: read and write* and an expiry date (github.com > Settings > Developer settings).
  The same screen forgets it.
- **Or push from your PC instead:** copy the files out over the SSH gateway (section 7f) and use
  real git there. Your token never leaves your PC.
- Each git command counts as one request against the uplink's limit (30 a minute).

## 7i. The Server: the late-game computer

A **Server** is a computer with room to spare. It does everything a Computer does (terminal,
editor, network, `ssh`, the Programmer), and more:

| | Computer | Laptop | Server |
|---|---|---|---|
| Memory for programs | 256 KB | 1 MB | **2 MB** |
| Its own drive | 1 MB | 1 MB | **8 MB** |
| Hard drives it holds | none (use a Drive Bay) | 2, at `/net/disks` | **4, at `/net/disks`** |
| Programs in the background | none | none | **4 services** (section 7i.1) |
| Largest `requests` answer | 24 KB | 96 KB | **192 KB** |
| The internet (`requests`, `git`) | needs an Uplink on its network | built in | **built in** |

- **Right-click** opens its screen, as a computer's does. **Sneak + right-click** opens its
  hardware: four hard-drive sockets and an upgrade slot. Its drives appear only to the Server
  itself, at `/net/disks/disk1` and on (or the name the drive was given in an anvil). A Drive
  Bay's drives, on the same cables, appear beside them in `/net` as usual.
- **Chunk loading:** fit a **Chunk Loader Upgrade** and the Server keeps its cable network's
  chunks loaded, exactly as an antenna does (section 7.3b): nearest the Server first, up to
  `chunk_loading.chunks_per_loader`, counted in your `chunk_loaders_per_player`, and only if
  the server allows chunk loading. The same screen has its on/off switch and says what it is
  doing. Without the upgrade a Server loads nothing, so its services run only while its area
  is loaded.
- **Memory is the computer's own, not the screen's.** When you connect to a Server, from a
  laptop or with `ssh` from a basic computer, the programs run on the Server with its 2 MB.
  The same goes the other way: a laptop connected to a Qbot works with the Qbot's 256 KB.
- **Limit:** `limits.servers_per_player` (2), counted apart from computers. Each running
  Server holds 2 MB of the game server's memory, eight times a computer.
- Broken, it keeps its files (they travel with the item, as a computer's do) and drops its
  hard drives.

**Crafting:** `ASA` / `RLR` / `ADA`: ancient debris at the four corners, an **LED Screen** at
the top, **RAM** on either side of a **Laptop**, a **Drive Bay** at the bottom. **RAM** is an
Integrated Circuit surrounded by eight gold ingots (`GGG` / `GIG` / `GGG`).

**Web answers:** a Server takes `requests` answers up to 192 KB (a computer 24 KB, a laptop
96 KB): enough for APIs, plain files and simple pages, not for rendering whole websites.

### 7i.1 Services: programs in the background

A Server runs up to **four services**: programs that keep running in the background while
its terminal is free for other work. Each has its own Python with 256 KB of memory (the
terminal keeps the Server's 2 MB), and its own light on the rack: **green** while it runs,
**red** if it failed, dark when it has finished or the slot is free.

| Command | Does |
|---|---|
| `service start <file.py> [name]` | Runs a program as a service. Its name is the file's, without `.py`, unless you give one |
| `service list` | The services: their light number, name, state (running, done, failed) and file |
| `service log <name>` | The last 40 lines it printed, errors included |
| `service restart <name>` | Runs it again from the start |
| `service stop <name>` | Stops it and takes it off the list |

- **They come back.** The list is kept on the Server's drive (`/.services`), so the services
  start again whenever the Server comes on, after a restart or when its area loads.
- A service runs in its file's folder, sees the same drive and network as the Server, and
  can use `events`, displays, `requests` and `git`. It can't use `input()`: nobody types to it.
- **Messages to the Server** go to its terminal's program if that is waiting for them
  (`network.receive()` or `events.on_message`); otherwise to the first service that is.
- Ctrl+C in the terminal doesn't touch the services. `service stop` does.
- Services share the game's computer time with every other computer: four services are four
  programs, not a faster one.

## 8. The Network Probe

A hand tool. Sneak and scroll the mouse wheel to change its mode; its name shows the mode.

| Mode | Right-click does |
|---|---|
| Labels | On a device: opens a pop-up with its name, a ready line of Python, its internal IP, hardware address and external address. Click a line to copy it. A box at the top names the device |
| Slots | Blocks open as usual, and every container screen shows each slot's number. A screen with one inventory (a chest, a furnace) shows that inventory's own numbers, which plates use. A screen with several (a crafting table's grid and product) shows the screen's numbers, which a Bus working the screen uses (section 6.8). Clicking a button on a screen shows the button's number above the screen and writes it to your chat (section 6.8) |
| Tunnels | Right-click a bus, then another, to open a tunnel between them (section 6.3b). Right-click a bus in a tunnel to see where it leads; again to close it |

In either mode, hovering an item in any inventory shows the name programs use for it. For a
bucket it also shows the fluid's name. Ctrl+C copies the name. Holding Shift lists the item's
tags.

---

## 9. Limits

| What | Limit |
|---|---|
| Items moved by one `move` | 576 (nine stacks) |
| Fluid moved by one `move` | 16,000 mB |
| Energy moved by one `move` | 2,000,000 FE |
| Sources or destinations in one `move` | 32 each |
| A message | 4,096 characters once written as JSON |
| A computer's inbox | 64 messages |
| Calls waiting for the game at once | 32 per computer. Calls a program never waited for, or that Ctrl+C cut short, are forgotten when it ends |
| A network | 4,096 cables and blocks; 253 devices |
| A device name | 24 characters |
| A computer's drive | 1 MB (a Server's, 8 MB) |
| Memory for programs | 256 KB on a computer or Qbot, 1 MB on a laptop, 2 MB on a Server |
| A file opened in the editor | 30 KB |

---

## 9b. Limits, inactive devices and starting up

**Limits.** Each player may have a set number of active computers and antennas: 10 of each
unless the server says otherwise. Server operators have no limit. `/qbotics limits` shows yours
and where each one is; operators can use `/qbotics limits all` for everyone's.

**Inactive (bricked) devices.** A computer or antenna placed beyond its owner's limit is placed
**inactive**: it does nothing and costs the server nothing. An inactive computer's screen is dark,
it runs no program, and it isn't on its cable network; an inactive antenna connects to nothing.
Its files are kept. You are told when you place one.

- To switch one on, get under your limit (break or pick up another), then **right-click it**.
- Or break it and place it again while under your limit.
- A computer or antenna placed by a **machine** (a dispenser, a Placer) is always inactive, and
  has no owner. The first player to right-click it while under their limit switches it on, and
  it becomes theirs.
- If the server lowers a limit, devices already on stay on. You can't switch on more until
  you're below the new limit.

**Starting up.** A computer that was on comes back on by itself when its area of the world
loads, for example after the server restarts: its `startup.py` runs as usual. Computers come
back a few at a time (4 a tick by default): the Overworld first, players taking turns, those
nearest the world's centre first.

**Switching off.** Type `shutdown` in a computer's terminal to switch it off. It stays off,
even across restarts, until someone opens it again.

**Server settings** (`config/qbotics-server.toml` in the game or server folder):

| Setting | Default | What it does |
|---|---|---|
| `limits.computers_per_player` | 10 | Active computers per player; -1 for no limit |
| `limits.antennas_per_player` | 10 | Active antennas per player; -1 for no limit |
| `limits.qbots_per_player` | 5 | Active Qbots per player; -1 for no limit |
| `limits.servers_per_player` | 2 | Active Servers per player; -1 for no limit |
| `limits.operators_exempt` | true | Operators have no limit |
| `boot.per_tick` | 4 | Computers started per tick as they come back on |
| `scheduler.worker_cores` | 2 | How many computers run Python at the same moment (read at server start) |
| `scheduler.reactivation_checks_per_s` | 1 | How often one player may try to switch on an inactive device |
| `chunk_loading.enabled` | true | `false` switches every Chunk Loader Upgrade off (section 7.3b) |
| `laptops.enabled` | true | `false` stops laptops opening (section 7e) |
| `ssh_gateway.enabled` | false | `true` starts the SSH gateway with the server (section 7f) |
| `ssh_gateway.port` | 2222 | Its port |
| `ssh_gateway.bind_address` | 127.0.0.1 | Where it listens: this PC only; `0.0.0.0` for every network the server is on |
| `ssh_gateway.all_computers` | false | `true`: a logged-in player reaches every computer, not only their own |
| `uplink.enabled` | true | `false`: no computer reaches the internet (section 7g) |
| `uplink.allow_domains` | [] | Sites it may reach (a domain covers names under it); empty: any public site |
| `uplink.deny_domains` | [] | Sites it never reaches |
| `uplink.max_response_kb` | 24 | The largest answer a computer is given (1 to 40); a laptop gets 4 times this, a Server 8 |
| `uplink.timeout_seconds` | 10 | How long a request may take |
| `uplink.requests_per_minute` | 30 | Requests one player's computers make a minute, between them |
| `git.enabled` | true | `false`: no git commands (section 7h) |
| `git.allow_push` | false | `true`: players may push to GitHub with their own token |
| `git.max_repo_kb` | 512 | The largest repository a computer may clone |
| `chunk_loading.loaders_per_player` | 4 | Working chunk loaders per player; -1 for no limit (operators exempt as above) |
| `chunk_loading.chunks_per_loader` | 25 | The most chunks one antenna holds |
| `chunk_loading.while_owner_offline` | true | Whether loaders keep working while their owner is offline |
| `transport.items_per_second` | 1024 | Items one antenna carries to and from other networks each second |
| `transport.fluid_per_second` | 64000 | The same for fluid, in mB |
| `transport.energy_per_second` | 2000000 | The same for energy, in FE |

## 9c. Hard drives and the Drive Bay: moving files between computers

A **Hard Drive** is an item that holds files (4 MB). A **Drive Bay** is a block with four sockets
for them; right-click it to put drives in or take them out. Join the bay to a cable network and
**every computer on that network sees each drive as a folder**:

```
/net/<bay>/<drive>/
```

- `<bay>` is the bay's name (name it with the probe, or in an anvil before placing it), or
  `bay` and its number on the network if it has none.
- `<drive>` is the name the drive was given in an anvil, or `drive` and its socket's number
  (`drive1` to `drive4`).

Everything that works on a computer's own files works there: `ls`, `cd`, `cat`, `cp`, `mv`, `rm`,
`run`, the editor's file tree, and Python's `open()`, `os.listdir()` and `import`:

```
/ $ cp main.py /net/shared/backup/
/ $ run /net/shared/backup/main.py
```

```python
import sys
sys.path.append("/net/shared/lib")     # a library every computer on the network shares
import greetings
```

- **Files travel with the drive.** Take it out, put it in a bay on another network, and its
  files are there. Breaking a bay drops its drives with their files.
- **Moving** a file between drives (or between a drive and the computer's own) copies it and
  removes the original. Folders can't be moved between drives; copy their files instead.
- A drive appears within half a second of going in, and its folder goes when it is taken out.
- `/net` and the bay folders themselves can't be written to: only the drives inside them.
- Any computer on the network can read and change every drive in the bay; keep a bay on its
  own network if that matters.

From a program, `net.drive_bay()` gives the bay: `bay.path` is its folder (`"/net/shared"`) and
`bay.drives()` the names of the drives in it.

### 9d. Libraries from a repository: `pip`

A hard drive full of libraries can serve as a **repository**, on your own network or on another
one reached by radio, and `pip` installs from it into your computer's `/lib` folder, where
`import` finds them:

```
# requirements.txt
--source 100.64.0.2/repo/lib     # the drive lib, in the bay repo, behind the antenna at 100.64.0.2
tools
greetings
```

```
/ $ pip install -r requirements.txt
installed tools (2 files)
installed greetings (1 file)
/ $ python
>>> import tools
```

| Command | Does |
|---|---|
| `pip install -r requirements.txt` | Installs what a requirements file lists, from its `--source` |
| `pip install <name> ... --source <source>` | Installs packages by name. `-s` is short for `--source` |
| `pip available --source <source>` | Lists the packages a source has |
| `pip list` | Lists what is installed in `/lib` |
| `pip uninstall <name>` | Removes an installed package |
| `pip help` | All of this |

**A source** is a bay and a drive: `shared/lib` on this network, or `100.64.0.2/shared/lib` behind
the antenna at that external address. Reaching another network follows the radio's rules: the same
frequency, in range, and that antenna's access setting must let your computer's owner in (section
7.3). **Another network's drives can only be read**, never changed, so a repository can't be
spoiled from outside.

**A package** on the drive is a file `name.py`, or a folder `name/` with an `__init__.py` (and
anything else it needs). A package folder may have its own `requirements.txt`; those packages are
installed too, from the same source. Installing again replaces the earlier copy.

**`requirements.txt`**: one package name a line; `--source` (or `-s`) on a line of its own says
where they come from; `#` starts a comment. Versions (`name==1.0`) aren't supported yet.

From a program: `import pip`, then `pip.install(["tools"], "100.64.0.2/repo/lib")`,
`pip.available(source)`, `pip.installed()`, `pip.uninstall(name)`.

**Publishing** to a repository means putting files on its drive: from a computer on the
repository's own network (`cp mylib.py /net/repo/lib/`), or by carrying the drive there.

**Recipes (placeholders):** Hard Drive: iron around redstone, with a gold ingot at the bottom
middle (`III` / `IRI` / `IGI`). Drive Bay: four cables around a barrel.

### 9e. The Programmer: putting programs onto computers

A **Programmer** copies a program from its hard drive onto the computer in front of it, then
restarts that computer so its `startup.py` runs. It runs nothing itself, and needs no computer to
work it: redstone is enough.

- **Placing it:** like an observer, its **programming face** (the contact pad) points at the
  block you clicked to place it.
- **Its drive:** right-click it for its screen, with one hard-drive socket. **Edit files** opens
  the drive in the editor, with no terminal and no Run: files only.
- **What a program is:** a folder on the drive, copied onto the computer's own drive (files of the
  same name are replaced; others are left alone). With no `programs.txt`, the whole drive is the
  program.

**`programs.txt`** on the drive picks a folder for each redstone level:

```
# level: folder
1: basic
3: miner
15: guard
*: basic          # any other level, and a computer arriving with no signal
```

**When it programs:**

| What happens | Which program |
|---|---|
| Its redstone signal turns on (rises from 0) | That level's line, else the `*` line |
| A computer is placed in front of it | The level of its signal now, or `*` with none |
| A program asks: `net.programmer().program(3)` or `.program("miner")` | That level, or that folder by name |

A signal that stays on, or changes from one level to another without going to 0, doesn't program
again. Any redstone the programmer receives counts, as with a lamp.

**Whose computer:** the programmer works for its owner (whoever placed it).
- A computer that a machine or a Qbot placed (bricked, owned by nobody) becomes the programmer
  owner's and switches on, if that owner is under their limit (section 9b). If not, nothing is
  copied and the programmer says why.
- A computer that already belongs to the same owner is programmed as it is.
- **Another player's computer is never programmed.**

**Two programmers facing one computer:**
- A computer is programmed **at most once a game tick**. If two programmers go off in the same
  tick, the second is refused ("That computer was already programmed this tick").
- When a computer is placed with several programmers facing it, **only the one with the strongest
  redstone signal** programs it. A tie goes to the first in the order: below, above, north, south,
  west, east.
- Otherwise each acts on its own signal, so the last one to fire is what the computer runs. To
  choose between programs, use one programmer and its levels instead of two.

**What happened** shows above the hotbar of players within 16 blocks, and as `status`:

```python
p = net.programmer()          # or net.programmer("name")
print(p.status)               # "Installed 'miner' (2 files) and restarted the computer."
p.program("miner")            # raises NetworkError if it couldn't, saying why
```

The programmer joins the cable network on every side but its programming face, so the computer
it programs isn't joined to its network through it. Up to 200 files are copied per program.

**Recipe (placeholder):** four cables around an integrated circuit (` C ` / `CMC` / ` C `).

---

## 10. Errors

Mistakes in `logistics` and `network` calls raise `NetworkError` with a message in plain
words. Catch it like any exception:

```python
from logistics import network, move, NetworkError
try:
    move(network().bus("smelter"), network().interface("store"), "iron_ingot", need=8)
except NetworkError as problem:
    print("could not move:", problem)
```

A move that moves nothing is not an error: `move` returns 0. Ask for `need=` when it should be one.

---

## 11. The test stage

`/qbotics stage` builds a ready-made place to try things, so a feature can be tested without
placing blocks or typing a program first. It needs permission to use commands (cheats on, or
an operator).

Working from the mod's source, `gradlew :neoforge:runPlaytest` starts the game straight into a
lasting creative world with the stage already built, and with the other mods listed in
`neoforge/playtest-mods.txt` (FTB Chunks, JEI and Functional Storage to begin with) loaded
alongside, to try Qbotics with them.

It builds a floor of 15 by 9 blocks to the south-east of where you stand, **replacing
whatever is there**, and gives you a Network Probe if you have none. Everything it places
belongs to you.

| On the stage | Name |
|---|---|
| A computer with an antenna, and every device beside it | `main` |
| An Interface: coal, logs, raw iron, ingots, then five open slots | `store` |
| An Item Serial Bus on a chest of coal, logs, raw iron, cobblestone and ingots | `chest` |
| An Item Serial Bus on a furnace | `furnace` |
| Item Serial Buses on a barrel of redstone and gold, and on an empty barrel | `inbox`, `outbox` |
| A Fluid Interface; a Fluid Serial Bus on a tank of water and lava | `tanks`, `big_tank` |
| An Energy Interface; an Energy Serial Bus on a charged cell | `battery`, `machine` |
| An Experience Interface holding 30 levels' worth | `xp` |
| An Item Serial Bus on a crafting table | `crafter` |
| A Drive Bay with two hard drives (`lib` and `drive2`) | `shared` |
| On `remote`'s network, a package repository: a Drive Bay with a drive `lib` holding `greetings` and `stock`; `main` has a `requirements.txt` for it | `repo` |
| A Logic Pin on a redstone lamp | `lamp` |
| A Placer and a Breaker facing the same empty space | `placer`, `breaker` |
| Buses on an anvil and an enchanting table (the chest holds two worn swords, books and lapis for them) | `anvil`, `enchanter` |
| A second computer on its own cables and antenna, with an Interface and a Bus on a chest | `remote`, `remote_store`, `remote_chest` |

On `main`, `README.txt` lists all of this with the two computers' addresses, and `demos/`
holds one short program per feature:

| Program | Shows |
|---|---|
| `demos/1_items.py` | Counting, moving into the furnace, collecting what it made |
| `demos/2_filters.py` | Tags, wildcards, mods, and slots by number |
| `demos/3_fluid.py` | Moving water and lava by name and by tank |
| `demos/4_energy.py` | Reading and moving energy |
| `demos/5_radio.py` | Listing the other network, sending a message, reading the reply |
| `demos/6_devices.py` | Every device on the network |
| `demos/7_sources.py` | Sources and destinations: pools, keeping things back, exact amounts |
| `demos/8_crafting.py` | Opening the crafting table's screen: planks by hand, then sticks with a `Recipe` |
| `demos/9_world.py` | Blinking the lamp, placing a block and breaking it again |
| `demos/10_levels.py` | Repairing on the anvil and enchanting a book, paid from `xp` |
| `demos/11_watch.py` | Watches the chest for 30 seconds while you move things in and out by hand |
| `demos/13_drives.py` | A shared library written to a hard drive and imported from there |
| `demos/12_events.py` | Timers and events together for 30 seconds: the lamp blinks while the chest is watched and messages are shown |

`remote` runs `demos/echo.py` from the start, which answers every message, so the radio demo
works at once. Log in to it with the `ssh` line the README gives; Ctrl+C stops the echo.

Running the command again in the same place rebuilds everything as it was. The computers
get new, empty drives each time.
