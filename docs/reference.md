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
8. [The Network Probe](#8-the-network-probe)
9. [Limits](#9-limits)
10. [Errors](#10-errors)
11. [The test stage](#11-the-test-stage)

---

## 1. The computer

Right-click a computer to open its screen: files on the left, the editor in the middle, the
terminal below, and an optional Python scratchpad on the right.

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
| `clear` | Clears the terminal |

Names with spaces go in double quotes: `run "my program.py"`.

Ctrl+C stops a running program. A file named `/startup.py` runs when the computer starts.

### 1.2 Editor keys

| Key | What it does |
|---|---|
| F5 | Saves and runs the open Python file |
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
| Ctrl+V | Pastes; each pasted line is entered |
| Right-click | Copy, Copy All, Paste, Select All |
| Up, Down | Earlier commands |
| Page Up, Page Down, wheel | Scrolls back through output. End returns |

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
| `input`, `output`, `bus` | Item plates on a cable: take out, put in, or both |
| `fluid_interface` | Fluid storage: four tanks of 4,000 mB |
| `fluid_input`, `fluid_output`, `fluid_bus` | Fluid plates |
| `energy_interface` | Energy storage: 2,000,000 FE |
| `energy_input`, `energy_output`, `energy_bus` | Energy plates |
| `experience_interface` | Experience storage, with no limit |

Plates share a cable's block, one per side, and point at the block next to that side. Their
colour says what they carry: white for items, blue-grey for fluid, red for energy.

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
| `"#minecraft:logs"` | Everything carrying a tag. `#logs` tries `minecraft:logs`, then `c:logs` |
| `"$minecraft:logs"` | The same; `$` is JEI's tag prefix |
| `"@minecraft *_ore"` | Several words: all must hold |
| `"-*_slab"` | A word starting with `-` must not hold |
| `"coal \| charcoal"` | Alternatives |

Fluids use the same words: `"water"`, `"@minecraft"`, `"#c:water"`.

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
| `net.interface(key)`, `net.input(key)`, `net.output(key)`, `net.bus(key)` | One item device. With no key: the only one of its kind, or an error saying how many there are |
| `net.fluid_interface(key)`, `net.fluid_input(key)`, `net.fluid_output(key)`, `net.fluid_bus(key)` | The same for fluid |
| `net.energy_interface(key)`, `net.energy_input(key)`, `net.energy_output(key)`, `net.energy_bus(key)` | The same for energy |
| `net.experience_interface(key)` | The experience store |
| `net.interfaces()`, `net.inputs()`, `net.outputs()`, `net.buses()` | Every item device of a kind |

### 6.2 Every device

| Attribute or call | Gives |
|---|---|
| `.name`, `.ip`, `.mac`, `.kind` | As they were when the device was looked up |
| `.rename(name)` | Names the device. `""` removes the name |
| `.refresh()` | Looks the device up again |

### 6.3 Plates (`input`, `output`, `bus` and their fluid and energy versions)

| Attribute or call | Gives |
|---|---|
| `.target` | The block it points at, such as `"minecraft:furnace"` |
| `.face` | The face used when a call names none |
| `.set_face(face)` | Changes that face |

### 6.4 Moving things: sources, destinations and `move`

Things are moved **from sources to destinations**. A source or destination is only a
description of one end: which device, optionally which face or slot, and what it may give or
take. Nothing happens until `move` is called, so an end can be made once, kept in a variable,
put in a list, and used again.

Nothing has to pass through an Interface. An Input's chest can feed an Output's furnace directly.

| What | Can be a source | Can be a destination |
|---|---|---|
| Interface, Fluid Interface, Energy Interface, Experience Interface | yes | yes |
| Input (and Fluid Input, Energy Input): the container it points at | yes | no |
| Output (and its fluid and energy versions): the container it points at | no | yes |
| Bus (and its fluid and energy versions): the container it points at | yes | yes |

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

**Input**, **Output** and **Bus**

| Call | On | Does |
|---|---|---|
| `plate.contents(face=None)` | Input, Bus | What the target holds |
| `plate.count(item=None, face=None, slot=None)` | Input, Bus | How many the target holds |
| `plate.source(...)` | Input, Bus | Section 6.4 |
| `plate.destination(...)` | Output, Bus | Section 6.4 |

### 6.6 Fluid and energy

Fluid amounts are in millibuckets (mB): 1,000 mB is one bucket. Energy amounts are in FE, the
unit mods share (also called RF).

**Fluid Interface** (`net.fluid_interface()`): four tanks of 4,000 mB. A tank takes any fluid
while empty, then only more of the same.

| Call | On | Does |
|---|---|---|
| `tanks.contents()` | Fluid Interface | A list of `{"tank": 0, "fluid": "minecraft:water", "amount": 1000, "capacity": 4000}`. An empty tank's fluid is `None` |
| `tanks.amount(fluid=None, slot=None)` | Fluid Interface | Millibuckets held of a name or filter |
| `plate.contents(face=None)` | Fluid Input, Fluid Bus | The target's tanks |
| `plate.amount(fluid=None, face=None, slot=None)` | Fluid Input, Fluid Bus | Millibuckets the target holds |

**Energy Interface** (`net.energy_interface()`): holds 2,000,000 FE.

| Call | On | Gives |
|---|---|---|
| `cell.stored`, `cell.capacity` | Energy Interface | Energy held now; the most it can hold |
| `plate.stored(face=None)`, `plate.capacity(face=None)` | Energy Input, Energy Bus | The same for the target |

All of them have `source(...)` and `destination(...)` as section 6.4 says. In a fluid end,
`slot` is a tank number. Energy has no kinds, so `only`, `without` and `what` are not used:

```python
move(net.fluid_bus("boiler"), net.fluid_interface("tanks"), "water", 3000)
move(net.energy_interface("battery"), net.energy_output("machine"), count=50000)
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

## 8. The Network Probe

A hand tool. Sneak and scroll the mouse wheel to change its mode; its name shows the mode.

| Mode | Right-click does |
|---|---|
| Labels | On a device: opens a pop-up with its name, a ready line of Python, its internal IP, hardware address and external address. Click a line to copy it. A box at the top names the device |
| Slots | Blocks open as usual, and every container screen shows each slot's number |

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
| Calls waiting for the game at once | 32 per computer |
| A network | 4,096 cables and blocks; 253 devices |
| A device name | 24 characters |
| A computer's drive | 1 MB |
| A file opened in the editor | 30 KB |

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

It builds a floor of 15 by 9 blocks to the south-east of where you stand, **replacing
whatever is there**, and gives you a Network Probe if you have none. Everything it places
belongs to you.

| On the stage | Name |
|---|---|
| A computer with an antenna, and every device beside it | `main` |
| An Interface: coal, logs, raw iron, ingots, then five open slots | `store` |
| A Bus on a chest of coal, logs, raw iron, cobblestone and ingots | `chest` |
| A Bus on a furnace | `furnace` |
| An Input on a barrel of redstone and gold; an Output on an empty barrel | `inbox`, `outbox` |
| A Fluid Interface; a Fluid Bus on a tank of water and lava | `tanks`, `big_tank` |
| An Energy Interface; an Energy Bus on a charged cell | `battery`, `machine` |
| An Experience Interface holding 30 levels' worth | `xp` |
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

`remote` runs `demos/echo.py` from the start, which answers every message, so the radio demo
works at once. Log in to it with the `ssh` line the README gives; Ctrl+C stops the echo.

Running the command again in the same place rebuilds everything as it was. The computers
get new, empty drives each time.
