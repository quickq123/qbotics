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
   - [7l. Lessons: advancements for learning](#7l-lessons-advancements-for-learning)
   - [7m. The devkit: writing programs on your PC, away from the game](#7m-the-devkit-writing-programs-on-your-pc-away-from-the-game)
8. [The Network Probe](#8-the-network-probe)
9. [Limits](#9-limits)
10. [Errors](#10-errors)
11. [The test stage](#11-the-test-stage)

---

## 1. The computer

Right-click a computer to open its screen: files on the left, the editor in the middle, the
terminal below, and an optional Python scratchpad on the right. The files on the left keep up
with changes made from the terminal or by a program (`git clone`, `mkdir`, a script writing a
file) within half a second.

**Moving files on the left:** drag a file or folder onto a folder to move it there; dropping it on
a file puts it in that file's folder, and on the empty space below the list, at the top of the
drive. The folder it would go to is outlined while you drag; resting on a closed folder for a
moment opens it, and dragging near the top or bottom scrolls the list. Open tabs follow what was
moved. It won't replace a file of the same name there, or put a folder inside itself. A click
without moving still opens a file or a folder. Right-click a file or folder for **Rename** and
**Delete**. Renaming to a name that is already taken there is refused too: nothing is replaced.

An open file with nothing unsaved follows the drive: if a program, `git pull` or the AI helper
changes it, the tab shows the new text. A file you are in the middle of editing is left as you
have it.

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
| `read <file>` | Shows a markdown file (a README) laid out for the screen: headings underlined, `**`, backticks and links' brackets dropped, paragraphs wrapped, tables lined up in columns (or row by row if too wide). Typed at the terminal it stops after each screenful: Enter for more, `q` or Ctrl+C to stop. From a script or over ssh it prints it all |
| `mkdir <folder>` | Makes a folder |
| `rm [-r] <path>` | Deletes a file; `-r` deletes a folder and everything in it |
| `mv <from> <to>` | Renames or moves; moving onto a folder puts it inside |
| `cp <from> <to>` | Copies a file |
| `edit <file>` | Opens a file in the editor (a new file is created when saved) |
| `run <file.py>` | Runs a program. `python <file.py>` does the same |
| `python` | The Python prompt. `exit()` returns to the terminal |
| `devices` | Lists the devices on this computer's network (also `ifconfig`): address, kind, name ("(no name)" for none), and what each plate points at (`chest`, `furnace`, `nothing`). `devices --json`: every detail, as JSON, for programs and AI tools |
| `name <address> <name>` | Names a device on this network, as the Network Probe does: `name 192.168.1.4 inbox`. The address is an IP or hardware address from `devices`, or the device's name now (to rename it). `name <address>` alone says its name. In a script, a name that can't be given stops it |
| `recipe <item>` | The recipes that make an item, from every mod, as this world has them (section 7k). `--uses`: those it is used in; `--json`; `--limit n` |
| `item <item>` | An item's names, mod, tags, stack size, fuel and food values (`--json`) |
| `items <word>` | Finds items: a word, a `*pattern*`, a `#tag` or an `@mod` (`--json`) |
| `craft <item> [count]` | On a Qbot (through `ssh`): crafts from what it carries (section 7d.3a). `craft [count]` alone crafts what is laid out in its grid |
| `devices <external IP>` | Lists the devices behind another antenna |
| `ssh <name or address>` | Uses another computer from this one, its terminal and its files in the editor (section 7.4). `exit` returns |
| `settings` | Opens this computer's settings page (section 1.5) |
| `pip` | Installs libraries from a hard drive, on this network or another (section 9d) |
| `shutdown` | Switches this computer off; it stays off, even across restarts, until opened again (section 9b) |
| `clear` | Clears the terminal |

Names with spaces go in double quotes: `run "my program.py"`.

A line that isn't a command gets a hint: `run9_world.py` suggests `run 9_world.py`, and a
program's name typed on its own suggests `run` in front of it.

Ctrl+C stops a running program. A file named `/startup.py` runs when the computer starts.

A program can `import` the `.py` files beside it, wherever it is run from: import looks in the
program's own folder first, then the current folder, then `/lib`. Each run reads the files it
imports afresh, so a change to one takes effect the next time the program runs.

Ctrl+C reaches the program as `KeyboardInterrupt`, as in Python, straight away, even in the
middle of a `time.sleep()` or while a call waits for the game. `except Exception` doesn't catch
it, so a `while True` loop with `try`/`except Exception` inside still stops. A program can catch
`KeyboardInterrupt` itself to tidy up. If a program catches it and carries on (a bare `except:`
does this), a second Ctrl+C stops it for certain. The computer then restarts its Python,
which loses the variables typed at the `>>>` prompt but not your files.

### 1.1b Shell scripts

A file ending in `.sh` is a **shell script**: commands, one a line, run as if typed.

```sh
# /startup.sh: runs every time the computer starts
echo Starting the factory...
cd /helpme
git pull
service start /factory/smelter.py
run /factory/report.py
```

- `run setup.sh` or `sh setup.sh` runs one. Each command is shown as it runs, after a `+`.
- It runs **from its own folder**, so `run hello.py` finds a file beside it. A `cd` in a script
  lasts until the script ends; the shell is back in its own folder afterwards.
- Blank lines and lines starting with `#` are skipped.
- **The first command that fails stops it**, saying which line: a missing file, an unknown
  command, a `git` that refused, or a program that ended with an error or `sys.exit(1)`.
- Scripts can run scripts (up to 8 deep).
- **`/startup.sh`** runs when the computer starts: when it is switched on, and each time its part
  of the world loads again (you came back, the server restarted). Every new computer has one,
  holding only notes. **`/startup.py`** runs instead if `startup.sh` has no commands in it. A
  program you started by hand with `run` is not started again: put it in `startup.sh`.
- No variables, `if` or loops: for those, write a Python program and `run` it from the script.

| Command | Does |
|---|---|
| `echo <text>` | Prints the text |
| `sleep <seconds>` | Waits (decimals work; Ctrl+C stops it) |
| `mkdir -p <folder>` | Makes the folder and any above it, and doesn't mind if it is already there |

### 1.2 Editor keys

| Key | What it does |
|---|---|
| F5 | Saves and runs the open Python file |
| Shift+F5 | A dry run: runs it changing nothing in the world (below) |
| F8 | Steps the running program to the next line of your code and holds it there (section 1.3b) |
| Ctrl+S | Saves |
| Ctrl+F, Ctrl+H | Find; find and replace. F3 and Shift+F3 step through matches |
| Ctrl+Space | Suggests completions. Inside the text a finder is given, `net.bus("`, `net.pin('`, `net.storage("` and the like, the suggestions are the names of the devices of that kind on this computer's network, each with what it points at, and the address of any without a name yet. They come as soon as the quote is typed. Inside a string that names items (`move(a, b, "co`, `only="#c:`, `count("coal | ch`), item and tag names, those the network holds first, with how many |
| Ctrl+click | On a device in the code, shows the block it works over the editor (below) |
| Ctrl+/ | Comments or uncomments the selected lines |
| Ctrl+Z, Ctrl+Y | Undo, redo |
| Ctrl+W | Closes the tab |
| Shift+Alt+F | Formats a JSON file |
| Ctrl+Plus, Ctrl+Minus | Larger or smaller text |
| Ctrl+P | Switches between the sharp screen fonts (the default: JetBrains Mono for code, Inter for the rest) and Minecraft's pixel font |
| Tab, Shift+Tab | Indents or un-indents the selection |
| Esc | Closes the screen (nothing is lost) |

Resting the mouse on a function name shows how to call it and what it does.

**Seeing a device without leaving the editor.** Ctrl+click a device in the code: the call that
finds it (`net.bus("crafter")`) or a name set from one (`craft` anywhere after
`craft = net.bus("crafter")`). Over the editor comes the block that device works, as a player
sees its screen, with what its slots held at that moment:

- each slot's number as a program writes it, small and yellow in its top-left corner (the same numbers the probe's Slots mode shows);
- the slots nothing can be put in (a product) in red;
- beside it, the face the bus works through and the slots each face of the block reaches (a
  furnace: top slot 0, sides slot 1, bottom slots 2 and 1).

An interface, Collector or Player Interface shows its own slots; a fluid or energy bus, the
tanks or energy it reaches.

A **storage** (`net.storage("storage_*")`, or a list of names) shows its containers in the order
it fills them, each with how full it is, its bus's filter and any strays (things its filter
doesn't let in), and below them what they hold between them, the most first. Click a container
to see it as a device; Esc goes back to the list. Handy when a program puts things where you
didn't expect.

An **item name or filter** in a string (`"coal | charcoal"`, `"#c:ores"`, in a `move`, a
`count`, an `only=` and the like) shows the items it matches and where on the network those are,
so a filter can be tried without running anything. Click a place to see it.

Resting the mouse on an item in any of these shows the name programs use for it and its tags
(what a filter such as `"#c:cobblestones"` tests), as the probe shows them with Shift held; no
probe is needed here. Ctrl+C copies the name.

It is only a picture, as things were when you clicked: nothing can be moved in it, and a click
or Esc closes it (or goes back a step). A bus opens its block's screen for a moment to see it, as
`open_screen()` does, so it needs an owner (a bus placed by a player has one). Another mod's
screen that can't be drawn here shows as plain slots in the same places, still numbered. The
name must be written in the code as text: one worked out while the program runs can't be followed.

**The Git panel** (Git at the top of the files, on the left; Files goes back) works on the
folder chosen in Files, or the whole drive if none is. It has two modes:

- **Simple: copies between the computer and your PC**, with no GitHub and no Uplink, so it
  works on any server. *Save as .zip...* puts a copy of the folder on your PC, wherever you
  choose (your Downloads, to start with). *Load a .zip...* or *Folder...* brings one from your
  PC onto the computer, into a **new folder** at the top named after it (`/factory`,
  `/factory_2` if that's taken): nothing on the drive is overwritten. Before anything is sent
  you see how many files and how much, and what was left out. What comes from your PC is checked
  first: every name must be one the drive allows, nothing may point outside the folder (`..`,
  `C:\`, a path from the top of a disk), links aren't followed, at most 400 files and 2 MB
  (a .zip is unpacked only that far, so one that would unpack into something huge can't), and
  the clutter other systems add (`__MACOSX`, `.DS_Store`, `.git`) is left out. The server checks
  every path again, and the drive's space.
- **GitHub**: the same git as the `git` command (section 7h), with buttons. A cloned folder shows
  its repository, branch and commit, and your changes since the last pull or push (`M` changed,
  `A` new, `D` deleted), with **Pull**, **Push...** (asks for the commit's message) and
  **Refresh**; *Pull, dropping my changes...* asks first. Any other folder offers **Clone into
  here...**. Your **token**: *Set...* asks for it (Ctrl+V pastes; it shows as stars), *Forget*
  drops it, and the panel says whether one is kept and whether this server lets you push.

**The Network panel** (the Network button at the top right; it takes the place of the Chat
and the scratchpad on the right) lists every device on this computer's cables, by kind: its name
(or address, if it has none yet) and what it points at. Click one to see it over the editor, as
Ctrl+click in the code does. Refresh lists them again after the cables change.

**A dry run** (Shift+F5, or the Dry run button beside Run) runs the open file changing nothing
in the world. It reads the world as it likes; each `move()` is only tried, and says what it would
have done, then the program carries on as if it had:

```
[dry run] would move 32 coal: storage_1 -> oven (top)
```

Anything else that would change the world stops the program there and says what it was about to
do: using a block's screen (crafting), a Qbot moving or digging, a Logic Pin, the chat, writing a
file. Everything before that ran as a dry run; F5 runs it for real. (A move into or out of a
screen stops it too: what a screen makes depends on what is really laid out in it.)

### 1.2b Mistakes, marked as you type

Half a second after you stop typing, the editor checks the Python file and underlines what is
wrong with a wavy line: **red** for an error, something that stops the program from starting
or from running that line, and **amber** for a warning, something that is probably a mistake.
The line number turns red too. Rest the mouse on the underlined code to see what is wrong and
how to put it right; the status bar shows the problem on the cursor's line, or how many there are.

It finds, among others: a bracket or quote left open or closed with the wrong one, a missing
`:` after `if`, `for`, `def` and the others, `def name:` without its `()`, indentation that
doesn't line up, `=` where `==` was meant, `else if` for `elif`, `print "x"`, a name the mod's
modules don't have (`from logistics import mvoe`: did you mean `move`?), and names used but
never defined (`cuont`). When it finds no error, the computer's own Python compiles the file
too (without running it), so anything else it can't read is marked as well.

It also knows the computer's network: a device the code names that nothing on the network
answers to is underlined amber (`net.bus("chset")`: no bus named "chset" on this network now,
did you mean "chest"?), as is a name that belongs to another kind of device (a pin, not a bus)
and a `net.storage("storage_*")` pattern nothing matches. Files that reach other networks
(`remote()`) aren't checked this way, and neither is a laptop with no cables.

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
- **F8 with nothing running** starts the file open in the editor, held at its first line,
  before it has done anything (`[stepping: ...]` in the terminal); then each F8 runs one line.
  **Ctrl+C** while it is held stops it there, before that line runs. Stepping ends with the
  program: whatever runs next runs normally. F8 does nothing while the terminal is in an `ssh`
  session (the editor's files are this computer's).
- **F8** steps: the program runs to the next line of your code and holds there. A loop's lines
  come round again each turn, and stepping goes into your own functions. F8 while it runs
  stops it at the next line. Ctrl+Z carries on at full speed.
- Ctrl+C still stops it at any time.
- **While it is held on a step**, rest the mouse on a variable in the code to see its value:
  `count = int: 6`, `names = list: ['coal', 'charcoal']` (a long one is cut short). That works
  for the file's top-level variables, and for a function's parameters while the program is
  inside it. A function's other variables can't be shown: Python on the computer keeps those
  by number, not by name, so the box says so. Functions, modules and classes show their help
  as usual.

Watching costs nothing to programs nobody has open. A program whose editor is open runs a
little slower (for typical programs, which mostly wait on the world, not noticeably; tight
number-crunching loops at about half speed), and only while it is open.

### 1.4 The scratchpad

A box for trying a few lines of Python. Ctrl+Enter runs it. It shares variables with the
terminal's Python prompt. `input()` does not work there.

### 1.4b The Chat panel

**Chat** at the top right opens the game's chat beside your code, in the scratchpad's place:
what everyone says, and a box to type in (Enter sends; `/commands` work too). Handy in a class:
read the teacher's instructions and answer without leaving the editor.

With the AI agent set up and answering (section 7j), **To: Chat | AI** at the top of the panel
chooses where typing goes. To the AI, your question is sent with what the editor shows: the open
file, the cursor's line, what you have selected, unsaved changes, and the mistakes marked in it,
so "why doesn't this work?" is enough. Shift+Enter starts a new line. The answer appears here and
in the game's chat; when the AI asks to write or run something, **Allow** and **Deny** appear
above the box. If the AI stops answering, what you typed for it is not sent anywhere else.

### 1.5 The settings page

The **Settings** button at the top left (or the `settings` command) opens the computer's
settings in the editor, as a JSON file. Change a value and save with Ctrl+S. Lines whose name
starts with `_` are notes and are ignored. A value that isn't allowed is refused with a
message, and nothing is changed.

| Setting | Values | Meaning |
|---|---|---|
| `name` | text | What the computer is called on its network |
| `open` | `"owner"`, `"team"`, `"public"` | Who may open the computer by right-clicking it (a Qbot's and a Server's screen too). `owner`: only you (how every computer starts; operators always may). `team`: your team (FTB Teams, or the game's `/team`). `public`: anyone. Whoever opens it works as you: your files, your devices, your interfaces. Only the owner changes this page |
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
| `sys.exit()`, `sys.exit(1)`, `sys.exit("message")` | Ends the program quietly: with nothing or 0 it succeeded; with a number or a message (which is printed) it failed, and a shell script running it stops |

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
| `player_interface` | A bus on the player who placed it: their inventory, armour and off hand (section 6.11) |
| `chat_interface` | The server's chat: hears it, writes to it, runs commands as its owner (section 6.11) |
| `command_interface` | A command block on the network. No recipe: spawned in only (section 6.11) |
| `pin` | A Logic Pin: a redstone line in or out, and an eye on a block |
| `placer`, `breaker` | Plates that place blocks and fluid, or break them |
| `dropper` | A plate that throws items out as a dropper, or fires them as a dispenser (section 6.10) |
| `entity_sensor` | Sees the creatures, players and dropped things around it, and acts on them as its owner (section 6.12) |
| `collector` | A chest's worth of slots that picks up the dropped items around it, and experience into an Experience Interface (section 6.13) |
| `block_sensor` | Reads, watches and works the blocks in a small box near it: harvests, plants, uses items (section 6.14) |
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
| `"enchanted_book enchant=mending"`, `"potion effect=strength"` | Items only: a stack holding that enchantment (worn or stored), or a potion with that effect |
| `"damaged"`, `"undamaged"`, `"enchanted"`, `"named"` | Items only: a stack that is worn, unworn, enchanted, or renamed on an anvil |
| `"potion[potion_contents={potion:'minecraft:strong_strength'}]"` | Items only: a word with `[...]` is the game's own item syntax (as in `/clear`), for any data at all |

Fluids use the same words: `"water"`, `"@minecraft"`, `"#c:water"`.

**Filters from filters.** `Filter(only=..., without=...)` builds one from names, filter words or
other `Filter`s, and `|` (either), `&` (both) and `~` (not) combine them. A `Filter` goes
anywhere a name does, including a bus's `set_filter()`:

```python
from logistics import Filter
junk = Filter(only=["#c:stones", "dirt", "gravel"])
keep = Filter(only="@minecraft", without=junk) | "#c:gems"
move(drop_off, store.destination(only=keep))
```

`docs/filters.md` is a longer guide: worked examples, sorting into several chests, and a list
of useful tags for tools, armour, ores, food and building blocks, and the data words and
`Filter` in full. To see an item's own name and tags in the game, hold the Network Probe and
hover over the item; hold Shift for its tags, its data words and the exact filter naming that
stack, which Ctrl+Shift+C copies (section 8).

A name that doesn't exist is an error that suggests close matches. A filter that nothing in
the game passes is an error too.

With no count, **everything** that matches and fits moves, up to the limit for one move,
however it is named: `move(chest, store, "coal")` moves all the coal. Give a count to move
fewer: `move(chest, store, "coal", 8)`. This is the same for `move`, a screen's `put` and
`take`, a Dropper's `drop` and a Qbot's `inventory.drop` and `suck`. (Putting ingredients into a
crafting grid, give the count: `screen.put(Slot(1), "oak_planks", 1, sources=store)`.) A count
of 0 is an error: check it first (`if n > 0:`).

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

**Moving** with no face named goes through that face, taking out as well as putting in:
`move(furnace, store)` takes only what the bus's face gives. `Face.ALL` is the whole block,
every slot whichever face it is reached through: `move(furnace.source(face=Face.ALL), store)`.
A slot given by number (`slot=Slot(2)`) with no face is one of the whole block's.
**Looking** (`contents()`, `count()`, `watch()`) with no face named sees the whole block.

For a furnace: `Face.TOP` reaches the ingredient, a side reaches the fuel, `Face.BOTTOM`
reaches the product.

### 5.2 Slots

Slots and tanks are numbered from 0, left to right and top to bottom, exactly as `contents()`
lists them. `Slot(n)` names one.

A slot number with no face counts across the whole container. With a face it counts only the
slots that face exposes.

The probe's Slots mode shows these numbers on any container's screen (section 8).

**Walking the slots.** Everything that holds items in slots answers the same three calls: a
bus or USB (for the container it points at), an interface, a Player Interface, a Collector, an
open screen, a creature or cart an Entity Sensor sees, and a Qbot's `inventory`:

```python
chest = net.bus("chest")
print(chest.size())                      # 27
for s in chest.slots():                  # every slot in order, empty ones too
    print(s["slot"], s["item"], s["count"], s["accepts"])
print(chest.contents())                  # only the slots holding something

furnace = net.bus("furnace")
[s["slot"] for s in furnace.slots() if not s["accepts"]]    # [2]: where its product comes out
furnace.size("top")                      # 1: through its top it reaches only the input
```

| Call | Gives |
|---|---|
| `size()` | How many slots |
| `slots()` | Every slot in order, empty ones too: `{"slot": 0, "item": "minecraft:coal", "count": 12, "accepts": True}`; an empty slot has `"item": None` and `"count": 0` |
| `contents()` | Only the slots that hold something, in the same form without `"accepts"` |

`"accepts"` is `False` where nothing can be put in: a furnace's or crafting table's product, a
closed interface slot. It is learned by trying the slot's rules with what it holds and a set of
common items, so a slot for one unusual thing alone (some mods' machines) may show `False`
until it holds one. On a bus, `size(face)` and `slots(face)` count only what that face reaches,
numbered as that face numbers them. A storage of many chests has `size()` (all their slots) but
no `slots()`: its chests number their own, so walk each one from `store.devices()`.

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
| `net.player_interface(key)`, `net.chat_interface(key)`, `net.command_interface(key)` | The game interfaces (section 6.11) |
| `net.entity_sensor(key)` | An Entity Sensor (section 6.12) |
| `net.collector(key)` | A Collector (section 6.13) |
| `net.block_sensor(key)` | A Block Sensor (section 6.14) |
| `net.interfaces()`, `net.buses()`, `net.usbs()` | Every interface, Item Serial Bus or USB |
| `net.storage(names)` | Many chests as one: every interface and item bus the names find (section 6.4c) |

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
| `.size(face=None)`, `.slots(face=None)` | How many slots the target has, and every slot (section 5.2). With a face, only what that face reaches |

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

### 6.3a A bus's filter: what it lets in

An Item Serial Bus (or a USB, for items) can keep a **filter**: what it lets into the
container it points at. The bus keeps it, so it holds for every program's moves, for every
storage the bus is part of (section 6.4c), and for things pushed in through its tunnel.

```python
net.bus("ores").set_filter(["#c:ores", "#c:raw_materials", "coal"])
net.bus("food").set_filter("#c:foods")
net.bus("chest_3").set_filter(matching=True)          # only kinds already in it
net.bus("tools").set_filter("#c:tools", matching=True) # tools, and anything already there
net.bus("ores").set_filter()                           # cleared: anything goes in
```

| Call | Does |
|---|---|
| `bus.set_filter(only=None, matching=False)` | `only`: a name, filter or `Filter`, or a list of up to 32 (section 4). `matching=True`: also any item the container already holds. `matching="exact"`: only things identical to one already there, data and all. Nothing given: clears it |
| `bus.filter` | `{"only": [...], "matching": False, True or "exact"}`, or `None` for anything |

- **It only limits what goes in.** Taking out is never stopped, so something put in by hand
  (outside the filter) can still be counted and taken.
- **Matching** lets in an item only while the container holds some of it. When the last one
  is taken out, that item no longer goes in until one is put back by hand.
- **`matching=True` compares the item only:** a worn gold pickaxe lets in a new one, an
  enchanted book any enchanted book. **`matching="exact"`** compares everything, data
  included: a chest holding a Strength II potion takes Strength II potions only. Exact also
  means the same durability, so it suits potions and books, not tools.
- **Only through the bus.** A hopper or a player putting things straight into the chest
  isn't stopped: the filter belongs to the bus, not the chest.

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

### 6.4c Storage: many chests as one

A **storage** is a group of containers used as one: the chests, barrels or drawers behind
item buses, and interfaces, found by their names. Each call asks about all of them at once,
in one tick, however many there are, where asking each bus in turn takes a tick per bus.

```python
from logistics import network, move
net = network()
store = net.storage("chest*")          # every bus or interface whose name starts with chest

print(store.count("iron_ingot"))       # across every chest
for entry in store.contents():         # added up, the most first
    print(entry["count"], entry["item"])
move(store, net.bus("furnace"), "coal", 8)     # from whichever chests have it
move(net.bus("drop_off"), store)               # each kind to where it already is
```

**Naming what is in it.** `net.storage()` takes one or more of these, or a list:

| Given | Finds |
|---|---|
| A pattern: `"chest*"`, `"ore_?"` | Every device whose name matches (`*` any run of characters, `?` any one), in name order (`chest2` before `chest10`). Devices that hold no items (a computer named `chest_ctl`) are passed over |
| A name: `"storage"` | Every device with that name. Several buses can share one name |
| An address, or a device: `net.interface()` | That one |

Name a bus with `bus.rename("chest_3")`, or with the Network Probe. The names are looked
up again on every call, so a new bus named to match joins at once, and one taken off leaves.

| Call | Gives |
|---|---|
| `store.count(item=None)` | How many of an item (a name or filter) are in all of it; with none, how many items in all |
| `store.contents(details=False)` | `[{"item": "minecraft:coal", "count": 1200}, ...]`, added up across every container, the most first. With `details=True`, stacks that differ in data are counted apart, each with its details (section 6.5) |
| `store.where(item)` | Which containers hold an item: `[{"device": <bus>, "count": 40}, ...]` |
| `store.free()`, `store.size()` | Empty slots, and all slots, in all |
| `store.devices()` | The buses and interfaces it is made of now |
| `store.strays()` | Things in a chest whose bus's filter doesn't let them in (put there by hand): `[{"device": <bus>, "count": 5}]` |
| `store.tidy()` | Moves the strays to where they belong in the storage; returns how many moved. Things with nowhere to go stay |
| `store.source(only=None, without=None)`, `store.destination(...)` | It as one end of a move, with filters (section 6.4) |

A storage goes wherever a device does: `move()`, a recipe's `sources`, a placer's or
breaker's, and `game.plan(have=store)`.

**Sorting.** Give each chest's bus a filter, leave one with none for the overflow, and one
move sorts everything:

```python
net.bus("chest_ores").set_filter(["#c:ores", "#c:raw_materials"])
net.bus("chest_food").set_filter("#c:foods")
for bus in net.storage("chest_*").devices():
    if bus.name not in ("chest_ores", "chest_food", "chest_overflow"):
        bus.set_filter(matching=True)        # the rest: whatever you started them with
store = net.storage("chest_*")
move(net.bus("drop_off"), store)             # each kind to its own chest
```

With matching buses, put one of each kind in its chest by hand to say where it goes. A kind
that runs out goes to the overflow until one is put back.

**How it moves things.**

- **Order:** the containers are in the order they were named; those a pattern finds, in name order.
- **Taking:** from the containers in order, the first that has some first.
- **Putting:** each kind goes to the first of these with room:
  1. a bus whose filter (section 6.3a) lets it in, holding some already;
  2. a bus whose filter lets it in;
  3. a container with no filter, holding some already;
  4. a container with no filter: the overflow.

  A bus whose filter doesn't let a kind in never gets it, even if some is there already. An
  interface counts as having a filter (its slot filters). Inside each chest, stacks of the
  same kind are topped up before an empty slot is started.
- **To or from one of its own members:** `move(store, net.bus("chest_1"))` gathers things into
  chest_1 from the others; that chest isn't counted as a source as well.

**What it leaves out.**

- **Two buses on one container,** or one bus on each half of a double chest: it is counted once.
- **A container it can't reach now** (broken, or its area not loaded) is left out until it can
  be reached. `store.devices()` shows what is in now.
- **Behind another antenna:** `net.remote("100.64.0.2").storage("chest*")` works too, with the
  same rules as remote moves (section 7.3b).

**Limits.** A storage holds up to 256 containers, and items only. A move carries at most 576
items a call, as any move does. A storage can't be watched yet: watch its buses one by one.

### 6.5 Items

**Interface** (`net.interface()`): nine slots. Every slot starts closed.

| Call | Does |
|---|---|
| `store.contents(details=False)` | A list of `{"slot": 0, "item": "minecraft:coal", "count": 12}`. With `details=True` (below), each stack's data too |
| `store.count(item=None, slot=None)` | How many it holds of a name or filter; of everything with none |
| `store.filters()` | What each slot accepts; `None` for a closed slot |
| `store.set_slot_filter(slot, item)` | Opens a slot for a name or filter. `None` closes it |
| `store.size()`, `store.slots()` | How many slots it has (9), and every slot with what it holds and whether it is open (section 5.2) |
| `store.source(...)`, `store.destination(...)` | Section 6.4 |

A slot holds one kind of item at a time, up to one stack, even when its filter passes many.

**Item Serial Bus** (and a USB)

| Call | Does |
|---|---|
| `bus.contents(face=None, details=False)` | What the target holds |
| `bus.count(item=None, face=None, slot=None)` | How many the target holds |
| `bus.source(...)`, `bus.destination(...)` | Section 6.4 |

**Details.** `contents(details=True)` adds three things to each stack that has data:

| Key | Gives |
|---|---|
| `"data"` | What sets it apart from its kind: `{"minecraft:stored_enchantments": {"minecraft:mending": 1}}`; `{}` for a plain stack |
| `"filter"` | A filter naming exactly this stack, in the game's item syntax; the plain name for a plain stack |
| `"words"` | The short filter words that hold for it: `["enchant=mending", "enchanted"]` |

```python
for c in chest.contents(details=True):
    if "effect=strength" in c["words"]:
        print(c["slot"], c["filter"])
```

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
- **Hoppers and item pipes** reach the bucket slots from any side. They can put in a full
  bucket only where a tank has room for it (and its filter allows it), and an empty one only
  where a tank has a bucket's worth to give, so a bucket with nowhere to go stays where it
  was. It swaps at once, and a hopper underneath takes out the result: a hopper of water
  buckets on top, and the empty buckets collect in a hopper below.
- Using a bucket on the block itself still fills or empties it directly, as before.

| Call | On | Does |
|---|---|---|
| `tanks.contents()` | Fluid Interface | A list of `{"tank": 0, "fluid": "minecraft:water", "amount": 1000, "capacity": 4000}`. An empty tank's fluid is `None` |
| `tanks.amount(fluid=None, slot=None)` | Fluid Interface | Millibuckets held of a name or filter |
| `tanks.filters()` | Fluid Interface | The fluid each tank is kept for, e.g. `["minecraft:water", None, None, None]`; `None` where it takes any |
| `tanks.set_tank_filter(tank, fluid)` | Fluid Interface | Keeps a tank for one fluid (`"water"`); `None` lets it take any again |
| `tanks.buckets` | Fluid Interface | The four bucket slots as an item device for `move()`, `count()`, `contents()` and `watch()`. Slot 0 is tank 0's, up to slot 3. They take and give by a hopper's rules (above): `move(chest, tanks.buckets, "bucket")` puts empty buckets only where a tank has 1,000 mB to fill them, and `move(tanks.buckets, chest, "lava_bucket")` takes the full ones out |
| `bus.contents(face=None)` | Fluid Serial Bus, `usb.fluid` | The target's tanks |
| `bus.amount(fluid=None, face=None, slot=None)` | Fluid Serial Bus, `usb.fluid` | Millibuckets the target holds |

**Energy Interface** (`net.energy_interface()`): holds 2,000,000 FE.

Right-click it to open its screen: a gauge of what it holds between two battery slots.
**Charge** fills a battery (any item that holds FE, from any mod) from the store; **Drain**
empties one into the store. Each works every tick, up to 50,000 FE a tick or as fast as the
battery allows. Take the battery out when it is done.

Hoppers and item pipes reach the battery slots from any side. A battery that can take more
charge goes in **Charge**, and a full one goes in **Drain**. Each comes out again only once
it is done (charged full, or drained empty), so a hopper underneath collects finished
batteries. They never put in something that holds no energy, and they take out anything like
that a player left in a slot.

| Call | On | Gives |
|---|---|---|
| `cell.stored`, `cell.capacity` | Energy Interface | Energy held now; the most it can hold |
| `cell.batteries` | Energy Interface | The two battery slots (0 charges, 1 drains) as an item device for `move()`, by a hopper's rules: in go batteries where they fit, and out come only finished ones |
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
    screen.put(Slot(1), "oak_planks", 1, sources=store)
    screen.put(Slot(4), "oak_planks", 1, sources=store)
    print(screen.take(Slot(0), store))            # 4 sticks, into the store
```

| Call | Does |
|---|---|
| `bus.open_screen()` | Opens the screen of the block the Bus points at and returns a `Screen`. If this Bus already has one open, that one is returned |
| `screen.slots()`, `screen.size()` | Every slot of the screen's own (not the player's inventory part), as section 5.2 gives them: `[s["slot"] for s in screen.slots() if s["accepts"]]` are the ones to fill. And how many there are |
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
    screen.put(Slot(0), "iron_sword", 1, sources=store)      # two worn swords...
    screen.put(Slot(1), "iron_sword", 1, sources=store)
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

### 6.10 World plates: Logic Pin, Placer, Breaker and Dropper Plate

Four more plates that go on cables like the others, one per side. Instead of reaching into a
container, they act on the block they point at. Each is a device with a name and addresses,
found with `net.pin()`, `net.placer()`, `net.breaker()` and `net.dropper()`.

All four can say what is in front of them:

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
| `placer.place(what=None, sources=...)` | Takes one item from `sources` (one source or a list) and places it. `what` is a name or filter; left out, the first block found is placed. Returns the item's name. If it can't be placed (it can't stand there, or its owner may not build there), it goes back where it came from and `NetworkError` says so **It returns `False`, not an error, when nothing was placed** (the space is taken, the sources hold none, the owner may not build there); `placer.why` then says which |
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

**Dropper Plate** (`net.dropper()`). Sends items out into the world in front of it, as a
dropper or a dispenser does. Its shape is an Item Serial Bus turned round: a small spout at
the front, widening back to the cable.

```python
spout = net.dropper("spout")
spout.drop("cobblestone", 16, sources=store)    # thrown out as one stack

spout.dispense = True                           # from now on, fire things as a dispenser does
spout.drop("arrow", 5, sources=store)           # five arrows fly, one a tick
spout.drop("bone_meal", sources=store)          # grows the crop in front
spout.drop("water_bucket", sources=store)       # pours it; the empty bucket goes back to the store
```

| Call or attribute | Does |
|---|---|
| `dropper.drop(what=None, count=None, sources=..., dispense=None)` | Takes items from `sources` (one source or a list) and sends them out; returns how many went. `what` is a name or filter; left out, whatever comes first. `dispense=True` or `False` does it that way this once, whatever the setting **With no count, everything that matches goes** (up to 576 a call), as `move` does |
| `dropper.dispense` | `False` (the default): it throws items out as a dropper does. `True`: it fires them as a dispenser does. Set it to change; the plate keeps it |
| `dropper.drop_xp(points=None, levels=None, sources=...)` | Sends out exactly that much experience as orbs, from an Experience Interface or liquid experience in a fluid store (one source or a list, in order). Nothing, and `NetworkError`, if they hold less. Returns the points sent out |

**Experience.** `levels=` counts from level 0: `levels=30` is the 1,395 points that take a
player from 0 to 30. Each level costs more than the last, so a player already at level 20 gains
fewer than 30 levels from it; give `points=` to say exactly. The orbs are the game's own, so
items with Mending on a player picking them up take their share first. Up to 1,000,000 points
a call.

```python
spout.drop_xp(levels=30, sources=net.experience_interface())    # an enchanting table's worth
```

- **As a dropper** it throws up to 64 of one kind a tick, as one stack.
- **As a dispenser** it fires one item a tick, as a real dispenser would fire it: arrows,
  snowballs and fire charges fly, bone meal grows what is there, buckets pour and pick up,
  armour goes onto whoever stands in front, shears shear, and so on, other mods' dispenser
  behaviours included. What it hands back (an empty bucket, a glass bottle) goes back where the
  item came from, and so does an item it had no use for (bone meal with nothing to grow); then
  `drop()` stops and counts only what was used.
- It always sends things into the world, even with a chest in front. To put items into a
  container, use an Item Serial Bus.
- It doesn't act for its owner, any more than a dispenser does.

**Acting for the owner.** The Placer and Breaker act as the player who put them on (section
6.8), so they can't build or break where that player couldn't: in someone else's land claim,
say. A plate with no owner can't place or break at all.

**Cables and fluids.** Cables count as solid to water and lava, which flow around them rather
than washing them away.

**Recipes:** four cables around an observer (Logic Pin), a dispenser (Placer), a diamond
pickaxe (Breaker) or a dropper (Dropper Plate).

### 6.11 Game interfaces: the player, the chat and commands

Three blocks put a part of the game itself on the network. Each is a full block that joins a
network like an interface does.

| Block | Kind | Recipe |
|---|---|---|
| Player Interface | `player_interface` | Eight cables around an **Ender Chest** |
| Chat Interface | `chat_interface` | Eight cables around a **Book and Quill** |
| Command Interface | `command_interface` | None. It is spawned in, like a command block (below) |

Right-click one to see whose it is and whether they are online.

**Whose it is.** The player who places a Player or Chat Interface is its owner. It works only
for that player's own computers (and Qbots and Servers): another player's computer on the same
network is refused. A Player or Chat Interface placed by a machine or a command has no owner,
and works for no one.

#### The Player Interface: a bus on you

It works the inventory of the player who placed it, as a bus works a chest: wherever that
player is, in any dimension, while they are online. It is a source and a destination for
`move`, and it can be counted and watched.

```python
from logistics import network, move, PlayerInterface
net = network()
me = net.player_interface()
store = net.interface("store")

move(me, store, "cobblestone")                             # empty my cobblestone into the store
move(store, me, "torch", 64)                               # and bring me torches
move(store, me.destination(slot=PlayerInterface.HEAD), "iron_helmet")   # put a helmet on
print(me.armor())        # {'head': 'minecraft:iron_helmet', 'chest': None, 'legs': None, 'feet': None}
```

Its slots are numbered as the game numbers a player's:

| Slots | What |
|---|---|
| 0 to 8 | The hotbar |
| 9 to 35 | The rest of the inventory |
| 36, 37, 38, 39 | Feet, legs, chest, head (`PlayerInterface.FEET`, `.LEGS`, `.CHEST`, `.HEAD`) |
| 40 | The off hand (`PlayerInterface.OFFHAND`) |

- A `move` that names no slot uses slots 0 to 35 only, so nothing is put on or taken off the
  player's body unless a program names that slot.
- An armour slot takes only what can be worn there, one at a time. Curse of Binding keeps a
  piece on, as it does in the inventory screen.
- `count()` and `contents()` see all 41 slots, armour and off hand included.

| Attribute or call | Gives |
|---|---|
| `me.contents()`, `me.count(item, slot=None)` | What the player carries, as for an interface |
| `me.armor()` | What they wear: `{"head": ..., "chest": ..., "legs": ..., "feet": ...}`, each an item name or `None` |
| `me.info()` | The player now: `player`, `online`, `health`, `max_health`, `food`, `saturation`, `level`, `dimension`, `x`, `y`, `z`, `selected` (hotbar slot), `game_mode`, `held` |
| `me.player`, `me.online` | Their name, and whether they are on the server |
| `me.size()`, `me.slots()` | 41, and every slot (section 5.2) |

When the player is offline, moves and counts fail with an error saying so; `info()` gives only
`player` and `online`.

#### The Chat Interface

It hears what players say in chat, writes to chat, and runs commands as its owner.

```python
import events
from logistics import network
chat = network().chat_interface()

@chat.on_chat
def heard(e):
    if e.message == "!time":
        chat.say(str(chat.command("time query daytime")))

events.run()
```

| Call | Does |
|---|---|
| `chat.say(text)` | Writes a line to everyone's chat, as `[name] text`: its owner's name (so a program can't pass for the server or another player). Where the server sets `chat_interfaces.speak_as_owner` to false, the interface's own name, or `Chat Interface` |
| `chat.tell(player, text)` | The same, to one player only |
| `chat.command(command)` | Runs a command as the owner, as if they had typed it (a leading `/` is optional), and returns a command result (below). It has the owner's permissions and no more, and needs them online |
| `chat.on_chat(function)`, `events.on_chat(chat, function)` | Runs the function for each chat message while `events.run()` is going. It gets an event with `.player`, `.message`, `.uuid` (the player's) and `.name` (the interface's) |
| `chat.player` | The name of the player it acts for |

- It hears every player's chat on the server. Commands players type are not chat, and are not
  heard.
- A message is at most 256 characters, and one interface writes at most 5 messages a second.
  More is an error.
- A command's output comes back to the program, not to the player's chat. The server log and
  operators see the command, as they see any the player types.
- Saying, telling and listening work while the owner is offline; commands need them online.

#### The Command Interface

A command block on the network: it runs commands at the block, with a command block's
permissions.

```python
cmd = network().command_interface()
print(cmd.run("time query daytime"))           # The time is 1000
cmd.run("setblock ~ ~2 ~ minecraft:gold_block")    # ~ is the block itself
```

| Call | Does |
|---|---|
| `cmd.run(command)` | Runs a command (a leading `/` is optional) and returns a command result |

**Getting one.** It has no recipe, and breaking it drops nothing; it can't be broken in survival.
An operator in creative finds it in the **Operator Utilities** tab beside the command block, or
types `/give @s qbotics:command_interface`. As with a command block, only an operator in
creative can place it.

- One a player placed works only for that player's computers, and only while they are still an
  operator.
- One placed by a command or a structure (no owner) works for any computer on its network, as
  a command block works for any redstone.
- When the world turns command blocks off (the `commandBlocksWork` game rule), it runs nothing.

**A command result** (from `chat.command()` and `cmd.run()`):

| Attribute | Gives |
|---|---|
| `.ok` | `True` if the command worked. The result itself is true or false the same way |
| `.result` | The number it gave, as a comparator reads from a command block |
| `.output` | The lines it said, as a list. `print(result)` prints them |

---

### 6.12 Entity Sensor: creatures, players and things lying about

A full block on the network that sees the **entities** in a box around it (creatures,
players, villagers, dropped items, experience orbs), reads them, and acts on them **as its
owner**: using an item on them, attacking, trading, and picking things up.

**Recipe:** eight cables around a sculk sensor.

**Its area** is a box centred on the block: `radius` blocks out to each side and `height` up
and down, each 1 to 20 (default 8). At the most, 41 x 41 blocks and 41 tall. An entity is in
it when the spot it stands on is, so one by the border is plainly in or out.

**Right-click it** for its screen: the radius and height, each with -5, -1, +1 and +5
buttons (only its owner, or an operator, can change them), and **Show area**, which outlines
the box in the world in cyan, with a mark at every block along its top edges, until pressed again.

```python
sensor = net.entity_sensor()
store = net.storage("chest*")

for cow in sensor.entities("cow"):
    if cow.can_breed:
        cow.use("wheat", store)                       # breeds it
    cow.use("bucket", store, destination=store)       # milks it

for mob in sensor.entities("#minecraft:undead"):
    mob.attack("diamond_sword", store)                # full swings, the sword goes back

librarian = sensor.nearest("villager")
for offer in librarian.offers():
    if "enchant=mending" in offer["sell"].get("words", []):
        librarian.trade(offer, store, times=1)

sensor.collect(store)                                 # drops and experience lying in the area
```

| Call or attribute | Does |
|---|---|
| `sensor.entities(what=None)` | What is in the area now, nearest first (up to 256): a list of entities. `what` is a name or filter of a kind of entity, written as for items: `"cow"`, `"#minecraft:undead"`, `"@minecraft"`, `"-player"`, `"item"` (dropped items), `"experience_orb"` |
| `sensor.nearest(what=None)` | The nearest, or `None` |
| `sensor.collect(to, what=None)` | Picks up everything lying in the area: dropped items (those `what` passes, or all) into item destinations, and (with no `what`) experience orbs into an Experience Interface among `to`. Returns `{"items": n, "points": p}`. Needs no owner, as a hopper doesn't |
| `sensor.radius`, `sensor.height` | Its size |
| `sensor.set_area(radius=None, height=None)` | Changes it, each 1 to 20 |

**An entity** carries what the sensor read. Each key is also an attribute (`cow.health`);
keys a kind doesn't have are missing. `entity.refresh()` reads it again, and a call on an
entity that has died or left the area raises `NetworkError`.

| Key | Gives |
|---|---|
| `uuid`, `type`, `name`, `custom_name` | Its ID, its kind (`"minecraft:cow"`), its name, and a name given with a name tag (or `None`) |
| `pos`, `offset`, `distance` | Where it is: `[x, y, z]` in the world, from the sensor's centre, and how far |
| `player` | True for a player |
| `health`, `max_health`, `baby`, `held`, `hostile` | Creatures: health, a baby or not, what is in its main hand, and whether it is a monster |
| `age`, `in_love`, `can_breed` | Animals: `can_breed` is an adult ready to breed (not in love, not waiting since the last time) |
| `sheared`, `color` | Sheep |
| `tame`, `owner` | Tameable animals: tamed, and the owner's UUID |
| `leashed` | On a lead |
| `trading`, `profession`, `level` | Villagers (and `trading` for wandering traders): trading with a player now, their job and level |
| `item`, `count()` | A dropped item: what it is, and how many lie in the stack |
| `value` | An experience orb's points |
| `armor`, `offhand` | Creatures and armor stands: `{"head", "chest", "legs", "feet"}` (item names or `None`), and the off-hand item |
| `framed`, `rotation` | An item frame: the item in it (or `None`), and its turn, 0 to 7 |
| `size()`, `slots()` | How many slots its inventory has (0 for none), and every slot (section 5.2; below) |

| Call | Does |
|---|---|
| `entity.use(item=None, sources=None, destination=None)` | Right-clicks it as the owner, holding one item from `sources` (a name or filter), or an empty hand: a bucket milks a cow, wheat breeds it, shears shear a sheep, a name tag names it, a saddle saddles a pig. What comes of it (the milk bucket, the wool) goes to `destination`, or back to the sources; an item that wasn't used goes back. Returns `True` if it did something |
| `entity.attack(weapon=None, sources=None, destination=None, wait=True)` | Hits it as the owner with a weapon from `sources`, or a fist. The weapon goes back to the sources (or `destination`). Returns `{"attacked", "alive", "health"}` Afterwards the entity is as the hit left it: `zombie.health` has gone down and `zombie.alive` is `False` once it is dead, so `while zombie.alive: zombie.attack(...)` ends |
| `entity.offers()` | A villager's or wandering trader's offers: `[{"index", "buy": {"item", "count"}, "buy_b", "sell": {...}, "uses", "max_uses", "out_of_stock"}]`. A sold item with data (an enchanted book) also has `"words"` and an exact `"filter"` (section 4) |
| `entity.trade(offer, sources, destination=None, times=1)` | Makes an offer (its index or its dict), up to `times` times (at most 64 a call): the price is taken from `sources`, checked before anything moves; what it sells goes to `destination`, or back to the sources. Stops when the offer runs out or the price can't be paid. Returns how many trades were made **It returns -1 when none were made because the offer is sold out** (0 when the price couldn't be paid) |
| `entity.collect(to)` | Picks up a dropped item or an experience orb |

**Inventories.** An entity with items is a source and a destination for `move()`, as a chest
behind a bus is, slot by slot, while it is in the sensor's area:

| Entity | Its slots |
|---|---|
| Minecart with a chest or hopper, boat with a chest (and modded ones that offer their items to pipes) | The chest's |
| Horse, donkey, mule, llama | Its inventory: saddle and armour slots, then its packs |
| Any other creature, and armor stands | 0 main hand, 1 off hand, 2 feet, 3 legs, 4 chest, 5 head |
| Players | Never: a Player Interface is for those |

```python
cart = sensor.nearest("chest_minecart")
move(cart, store)                                          # unload it
stand = sensor.nearest("armor_stand")
move(store, stand.destination(slot=5), "iron_helmet")      # a helmet on its head
```

| Call | Does |
|---|---|
| `entity.contents(details=False)` | What it holds: `[{"slot", "item", "count"}]` |
| `entity.count(item=None, slot=None)` | How many of an item it holds (a dropped item: how many lie in the stack) |
| `entity.source(only=None, without=None, slot=None)`, `entity.destination(...)` | It as one end of a move; the entity itself works too: `move(cart, store)` |

An item frame shows one item without an inventory: put one in with `frame.use("diamond", store)`,
turn it with `frame.use()`, and knock it out with `frame.attack()`.

`use("lead")` is refused: it would tie the creature to the sensor's stand-in, which can't lead it
anywhere. The lead goes back.

**Across the radio.** Both sensors work on another network as any device does
(`net.remote("100.64.0.2").entity_sensor()`, section 7.3b). Items they carry across the antennas
(drops, trades, what `use` gives, what `collect` picks up) count against the antennas' Transport
rate like a move's; when it is used up for the second, the call is refused (`collect` and `trade`
stop short instead) until the next.

**Attacking.**

- The weapon counts as it would in a player's hand: its damage, its enchantments (Sharpness,
  Fire Aspect, Looting) and its wear. It goes back where it came from afterwards, unless it broke.
- **A kill is the owner's**, so a mob drops what it would for a player: rare drops, Looting,
  experience.
- **Each swing is a full one:** `attack()` waits for the weapon's cooldown since the sensor's
  last swing (a sword about half a second, an axe a second). `wait=False` swings at once, as
  weakly as the cooldown leaves it.
- Its drops land where the creature was; `sensor.collect(store)` picks them up.

**Trading** is at the trader's prices as they stand, without the discounts a player earns by
curing a zombie villager (automated trading doesn't need them). A villager counts the trades towards its level, as from a player. A
villager already trading with a player is busy.

**Acting for the owner.** Using, attacking and trading are done by the player who placed the
sensor, so they can't touch what that player couldn't: animals in someone else's claim, say.
Reaching into entity inventories (a chest minecart, a donkey) and picking up dropped items
and experience keep to land the owner may change too, and so does a Collector, unless the
server's `sensors.read_other_players_computers` setting lifts that.
A sensor nobody placed only looks and picks up. Reading needs no owner at all.

### 6.13 Collector: picking up what lies around

A full block on the network with a chest's worth of slots (27) that fills itself with what
lies in a box around it: the same box as an Entity Sensor's (section 6.12), `radius` blocks
out to each side and `height` up and down, each 1 to 20 (default 8), with the same screen and
Show area.

**Recipe:** seven cables around a hopper, with a chest below it:

```
C C C        C = cable, H = hopper, T = chest
C H C
C T C
```

- **Right-click** it to open its slots, as a chest's.
- **Sneak and right-click** with an empty hand for its size and Show area.
- **Twice a second** it picks up the dropped items in its area that it has room for. Like a
  player, it waits for an item's pickup delay (half a second after a Dropper throws it, say).
  Stacks of the same kind are topped up first.
- **Experience:** if an Experience Interface is on its cable network, it picks up experience
  orbs too and stores them there. With none, orbs stay where they are.
- **It needs no program and no owner.** Breaking it drops what it holds.

**To programs** it is an item store, like an Interface: a source and a destination for
`move()`, counted, listed and watched, and it can be part of a storage (section 6.4c).
Hoppers and other mods' pipes take from it (and put into it) as from a chest.

```python
drops = net.collector()
move(drops, net.storage("chest*"))          # empty it into storage
drops.set_area(radius=12, height=4)
```

| Call or attribute | Does |
|---|---|
| `collector.contents(details=False)` | What it holds: `[{"slot", "item", "count"}]` (section 6.5 for details) |
| `collector.count(item=None, slot=None)` | How many of an item it holds |
| `collector.radius`, `collector.height`, `collector.set_area(radius=None, height=None)` | Its area, each 1 to 20 |
| `collector.size()`, `collector.slots()` | 27, and every slot (section 5.2) |
| `collector.source(...)`, `collector.destination(...)`, `collector.watch(...)` | Sections 6.4 and 6.4b |

### 6.14 Block Sensor: farms and other blocks near it

A full block on the network that reads the **blocks** in a small box near it, tells a program
what changes there, and **breaks, places and uses items** there as its owner. Where the Entity
Sensor works creatures and things lying about, this works the blocks themselves: harvesting and
replanting a field, bone-mealing grass and gathering what grows.

**Recipe:** eight cables around an observer.

**Its box** starts as the **one block above it**; grow it to what you need. Each side is at most
**11 blocks** long (11 x 11 x 11 at the most), and the box stays within 11 blocks of the sensor,
so it can sit above, beside or below it: a sensor under the middle of a 9 x 9 field covers just
the field's layer.

**Right-click it** for its screen: each face (top, bottom, north, south, west, east) with - and +
to move it, shown as its offset from the sensor (Top +1 is the block above), and **Show area**,
which outlines the box until pressed again. Only its owner (or an operator) can change it.

**Places** are offsets from the sensor, `(dx, dy, dz)`: `(0, 1, 0)` is the block above it. A
program written this way keeps working if the farm is rebuilt elsewhere.

```python
farm = net.block_sensor()
store = net.storage("chest*")
farm.set_area(x=(-4, 4), y=2, z=(-4, 4))           # a 9 x 9 field, two above the sensor

for spot in farm.blocks("wheat age=7"):            # what is ripe now
    farm.break_block(spot["pos"], destination=store)
    farm.place(spot["pos"], "wheat_seeds", store)

for change in farm.watch("wheat age=7"):           # then, as each ripens
    # Harvesting ripe wheat is a change too (it was ripe, now it is a seedling): act only on
    # what is ripe now. State values are text, so "7", not 7.
    if change.get("block") == "minecraft:wheat" and change["state"].get("age") == "7":
        farm.break_block(change["pos"], destination=store)
        farm.place(change["pos"], "wheat_seeds", store)
```

| Call or attribute | Does |
|---|---|
| `sensor.blocks(what=None)` | The blocks in its box: `[{"pos": [dx, dy, dz], "block", "state", "air", "fluid", "biome", "light"}]`, with `"sign"` and `"book"` where there is one (below). `what` is a name or filter, with state words: `"wheat age=7"`, `"#minecraft:crops"`, `"air"`. With none, every block that isn't air |
| `sensor.block(pos, details=False)` | One block. `details=True` adds `"data"`: the block's own saved data in the game's raw form (a spawner's mob, a beehive's bees, a modded machine's settings), or `None` for a block with none. Read-only; past 32 KB it is `None` with `"data_too_large"` |
| `sensor.write_sign(pos, lines, back=False, color=None, glowing=None)` | Writes a sign as its owner would: up to four lines (a list), on its front or (`back=True`) its back, in a dye colour (`"green"`), glowing or not. A waxed sign can't be changed: wax is how a player locks one. Returns the block |
| `sensor.watch(what=None)` | Changes in its box, as they happen (below) |
| `sensor.break_block(pos, to=None)` | Breaks a block as its owner would with the right netherite tool, taking as long as that does (crops at once). Drops go to `to`, all or nothing; with no `to`, they fall where the block was, for a Collector to pick up. Returns `{name: count}` |
| `sensor.place(pos, item=None, sources=...)` | Places one item from `sources` there, as its owner would: a block, or seeds onto farmland. Returns `True` if it went there; if not, it goes back With no item named, the first block or seeds found. `False` also when the spot is taken or the sources hold none; `sensor.why` says which |
| `sensor.use(pos, item=None, sources=None, destination=None)` | Right-clicks the block there as its owner, holding one item from `sources` (or an empty hand): bone meal grows it, a hoe tills dirt, a bucket fills or empties, a lever flips. What comes of it goes to `destination`, or back to the sources. Returns `True` if it did something |
| `sensor.area`, `sensor.set_area(x=None, y=None, z=None)` | Its box, as `{"x": (low, high), ...}` offsets. Give each axis a pair, or one number for one layer |

**What each block says**, besides its name and state:

| Key | Gives |
|---|---|
| `biome` | `"minecraft:plains"` |
| `light` | `{"block": 0-15, "sky": 0-15, "now": 0-15}`: from torches and the like, from the sky, and both as they stand now (what crops and monster spawning go by) |
| `sign` | A sign's (or hanging sign's) `{"front": {"lines": [4 lines], "color", "glowing"}, "back": {...}, "waxed"}` |
| `book` | A lectern's book: `{"title", "author", "pages": [text of each], "page": the page it is open at, from 0, "item"}`. A book and quill has no title or author |

**Watching.** `watch()` is a watch like a chest's (section 6.4b): `for change in farm.watch():`
sleeps until something in the box changes. Each change is

```python
{"pos": [1, 1, -1], "block": "minecraft:wheat", "state": {"age": "7"},
 "was": "minecraft:wheat", "was_state": {"age": "6"}}
```

- **It costs nothing while nothing changes.** The game itself tells the sensor of each change in
  its box (crops growing, a player breaking a block, a program's own work); nothing is read again
  and again. So a 11 x 11 x 11 box costs no more to watch than one block.
- A filter passes a change if it passes the block before or after: `watch("wheat")` sees wheat
  grow and wheat broken.
- A place that changes several times between looks is one change, from its first state to its
  last; one that changed and changed back isn't a change.
- At most 4,096 places are kept between looks; past that, the list ends with `{"overflow": True}`:
  read `blocks()` afresh.

**Signs as displays.** A program can keep a sign up to date, as a cheap status board:

```python
farm.write_sign((0, 1, 2), ["Wheat", "ripe: %d" % len(farm.blocks("wheat age=7")), "", env.now()["clock"]])
```

**Acting for the owner.** Breaking, placing, using and writing signs are done by the player who placed the
sensor, so they can't touch what that player couldn't (a land claim, say). A sensor nobody
placed only reads and watches.

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
| `team` | The owner's devices, and those of players on the owner's team (a team in FTB Teams, if that mod is installed, or the game's own `/team`) |
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
drops them.

| Takes upgrades | Sockets | Which |
|---|---|---|
| An antenna (any tier) | 2 | A Transport and a Chunk Loader |
| A Qbot | 4 (a 2x2 in its screen) | One of each kind, the Sense and Netherite Upgrades too (section 7d) |
| A Server | 1 | A Chunk Loader Upgrade |

| Upgrade | Does |
|---|---|
| **Transport Upgrade** | Lets programs use the devices behind another antenna (that also has one), and `move()` items, fluid and energy between the two networks. In a Qbot: between the Qbot itself and the network behind an antenna its radio reaches (below) |
| **Chunk Loader Upgrade** | Keeps the chunks of the antenna's whole cable network loaded and working while nobody is near. A Server takes one too (section 7i) |
| **Sense Upgrade** | A Qbot's only. The Qbot senses blocks up to 4 away in every direction (a ball 9 blocks across), not only the six touching it: `inspect.at()` and `inspect.scan()` (section 7d) |
| **Netherite Upgrade** | A Qbot's only. With nothing in its hand (or a tool that can't harvest the block) it digs as netherite tools would, so obsidian and ancient debris too, and with Silk Touch on everything it digs. A tool in its hand that can harvest the block is used instead, as it is (its own level and enchantments). And the Qbot's item, broken with this upgrade in it, doesn't burn: dropped in lava or fire it floats, as netherite does (section 7d) |

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

**A Qbot with a Transport Upgrade** sends what it carries to a network far away, and fetches
from it, without driving home:

```python
from logistics import network, move
net = network()
base = net.remote("100.64.0.2")                        # the antenna at the base
print("Unloaded", move(net.me, base.bus("ore_chest")))   # everything it carries
move(base.bus("supplies"), net.me, "torch", 64)        # and torches back
move(base.fluid_interface(), net.me, "lava", 1000)     # into its tanks; energy too
```

- `net.me` is the Qbot: its 16 slots for items, its two tanks for fluid, its cell for energy.
  The other end says which a move carries.
- The Qbot needs a Transport Upgrade in one of its sockets, and the antenna needs one too.
- The Qbot must be **in the antenna's range** (its tier's reach; an Infinite antenna reaches any
  distance and across dimensions), and the antenna's access must let the Qbot's owner in. The
  Qbot's own radio hears every frequency.
- What crosses counts against that antenna's rate, as above.
- Without the upgrades, `net.remote(...)` still finds and lists the devices there; using them
  is refused, with a message that says which upgrade is missing.

A parked Qbot is also an end for `move()` on a cable network it stands next to, so a base
computer can load and unload it: `move(net.device("miner"), store)`.

#### Chunk loading

- With a **Chunk Loader Upgrade** fitted and **chunk loading switched on** (it is, unless
  switched off), the antenna keeps loaded every chunk its cable network reaches: the cables,
  computers, plates and displays. Machines there keep working.
- Up to **25 chunks per antenna** (the server sets it), the nearest to the antenna first. The
  screen says how many it holds, and if the network spreads over more than it may hold.
- A player may have **4 working loaders** (the server sets it; operators are exempt, online or not). Further
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
an eye of ender over the same). Sense Upgrade: `ASA` / `RMR` / `GGG` (amethyst shards and a spyglass
over the same). Netherite Upgrade: ` P ` / `RMR` / `GBG` (a netherite pickaxe over redstone and an
integrated circuit; gold nuggets either side of an enchanted book with Silk Touch, and nothing else on it).

### 7.4 Remote login: `ssh`

```
/ $ ssh workshop
/ $ ssh 100.64.0.12:1005
```

Typed in a computer's terminal, `ssh` turns this computer's screen into the other computer's,
as a laptop connected to a Qbot does: what you type runs there and its screen is shown here,
and the **editor shows its files**. Saving changes them there; Run (F5) runs them there, and
F8 steps through them there. `exit` (or `logout`) at the other computer's prompt ends the
session. Ctrl+C stops the program running there, and Ctrl+Z pauses it.

This is how to program a Qbot from a distance: `ssh miner`, open `startup.py`, change it, save,
and Run.

Two things decide whether you get in:

1. **The network.** The other computer must be on the same cables, or behind an antenna that
   is on your frequency, in range, and whose access setting lets your computer's owner in.
2. **The computer.** Its `remote_login` setting (section 1.5) must allow you: by default only
   computers of the same owner; with `password`, anyone who types the right one.

Things to know:

- Who you are is decided by who owns the computer you are typing at.
- The other computer is switched on if it wasn't. It shows a notice when someone logs in.
- While logged in, the title bar says so (`Computer → ssh miner`), and **Settings** opens the
  other computer's settings page (only its owner can change them). This computer's open files
  are put aside, unsaved changes and all, and come back when you `exit`.
- The Python Scratchpad still runs on this computer, so it waits until you `exit`.
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
| `events.on_pin(pin, what, function)`, `events.on_pin(pin, function)` | Runs the function when a Logic Pin sees `"rising"`, `"falling"`, `"change"` or `"block"` (section 6.10). The function gets an event with `.name`, `.event`, `.level` and `.block` |
| `events.on_change(device, function, what=None)` | Runs the function for each change to what a device holds (section 6.4b). `device` can also be a watch (a device's, or `env.watch()`) |
| `events.on_message(function)` | Runs the function for each message from another computer: it gets `.sender` and `.data`. While `run()` is going, messages come here rather than to `network.receive()` |
| `events.on_chat(chat, function)` | Runs the function for each message a player says in chat, as a Chat Interface hears it (section 6.11). It gets `.player` and `.message` |
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
  kind, it lies the same way, to grow the screen.
- **On a floor or ceiling:** look steeply down at the top of a block (or steeply up at the
  underside of one) as you place it, and the screen lies flat there, its **top edge pointing away
  from you**. A floor screen is a table: stand at its bottom edge to read it the right way up.
  Flat screens join like upright ones; an upright screen and a flat one never join.
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
| Dial | `panel.dial(label, min=0, max=100, at, span=(2, 2), value=None, step=1, unit="", color=None)` | `.value`: a round knob from bottom left (min) clockwise to bottom right (max); players click around it to turn it |
| Number | `panel.number(label, min=0, max=100, at, span=(3, 1), value=0, step=1, unit="", color=None)` | `.value`, with - and + either side: each click takes a step off or adds one. Its label shows when it is two cells tall |
| Item | `panel.item(label, item, at, span=(2, 2), count=0, color=None)` | An item's picture (`"minecraft:coal"` or `"coal"`), with its label and a count (`.value`; 0 shows none). `.set(item="...")` changes it; `on_press` runs when a player clicks it |

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

**Themes.** `panel.theme = "light"` changes the panel's look: `"dark"` (the default), `"light"`,
`"industrial"` (yellow on charcoal), `"blueprint"` (white on blue) or `"terminal"` (green on
black); `display.THEMES` lists them. Widgets' own `color=` still applies.

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

**Form controls.** Each player's typing and choices are their own; the program hears them as an
event named after the control's `name`, with `.value`:

| In the page | Players | `@web.on(name)` gets |
|---|---|---|
| `<input name="search" placeholder="Search...">` | Type in it: in the close-up, click and type; on the panel in the world, right-click it and a box opens | `.value`, the text: while typing (once they pause) and on Enter (`.enter` is true) |
| `<textarea name="notes" rows="4" cols="30">` | The same, over several lines (Enter starts a new line) | `.value`, the text |
| `<input type="checkbox" name="pump" checked>` | Click it (in the world or close up) to flip it | `.value`: its `value` (`"on"` unless given) when ticked, `""` when not |
| `<input type="radio" name="speed" value="fast">` | Click one of the group | `.value`: the chosen one's `value` |
| `<select name="ore"><option value="iron">Iron</option>...</select>` | Click it: its list opens (close up) or a window of choices (in the world) | `.value`: the chosen option's `value` (its text if it has none) |
| `<input type="button" value="Go">` | A button | a click, as any button |

`checked`, `selected` and `value=` in the page set where each starts.

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
| The box model, colours, backgrounds, borders, rounded corners | Grid rows by template (`grid-template-rows`, areas, `grid-row`) |
| Flexbox with `gap`, floats, `position` (relative, absolute) | Transforms, transitions, animations, shadows |
| CSS grid: `grid-template-columns` (px, %, em, `fr`, `auto`, `minmax()`, `repeat()` with a number, `auto-fill` or `auto-fit`), `grid-column` (`span 2`, `1 / 3`, `1 / -1`), `gap`, `row-gap`, `column-gap`; items placed row by row and stretched to their row | |
| Fonts: sans-serif, serif, monospace; bold, italic; underline, line-through | File inputs, sliders (`type="range"`), colour pickers |
| Linear and radial gradients; item icons (`item:`) | |
| Text fields, textareas, checkboxes, radio buttons, selects; scrolling (page and `overflow: auto` boxes) | `<canvas>` `getImageData`, `putImageData` |
| JavaScript: the DOM, events, timers, `<canvas>` (below) | `fetch`, modules (`import`), web fonts |
| `@media` by the panel's size, CSS variables, `:hover`, `:active`, `:nth-child` | |

**JavaScript.** A page can have scripts: `<script>` in the page, or `<script src="app.js">`
beside it (`show()` puts the file in, as it does stylesheets). They run in each player's game,
in a small browser of their own: the page's elements, events, timers and `<canvas>`. What one
player's script does to the page (a tab opened, a list sorted) is theirs; what everyone should
see goes through the program's `state`.

```html
<h2 id="title">Reactor</h2>
<span id="power"></span>
<canvas id="chart" width="240" height="100"></canvas>
<button id="scram">SCRAM</button>
<script>
  const chart = document.getElementById("chart").getContext("2d");
  const history = [];
  panel.onState(state => {                       // the program set web.state
    document.getElementById("power").textContent = state.power + " FE/t";
    history.push(state.power);
    if (history.length > 48) history.shift();
    chart.fillStyle = "#0b1a26"; chart.fillRect(0, 0, 240, 100);
    chart.strokeStyle = "orange"; chart.beginPath();
    history.forEach((p, i) => chart.lineTo(i * 5, 100 - p / 100));
    chart.stroke();
  });
  document.getElementById("scram").addEventListener("click", () => {
    panel.send("scram", {reason: "button"});     // the program's @web.on("scram") hears it
  });
</script>
```
```python
@web.on("scram")
def scram(e):
    print(e.player, "pressed SCRAM:", e.data["reason"])
```

| In scripts | |
|---|---|
| `panel.state`, `panel.onState(fn)` | The program's state, and a function run whenever it changes (with the new state) |
| `panel.send(name, data)` | Sends to the program: `@web.on(name)` gets it, with `.data` |
| `panel.width`, `panel.height` | The panel's size in CSS pixels |
| `document.getElementById`, `querySelector`, `querySelectorAll`, `createElement`, `createTextNode` | Finding and making elements (selectors: tag, `#id`, `.class`, `[attr=value]`, descendant and `>`) |
| `el.textContent`, `innerHTML`, `appendChild`, `remove`, `setAttribute`, `classList`, `style`, `dataset`, `value` | Changing them |
| `addEventListener("click", fn)`, `onclick="..."`, `event.preventDefault()` | Clicks (and `input`, `change` on fields). `preventDefault()` keeps a click from the program |
| `setTimeout`, `setInterval`, `requestAnimationFrame` | Timers and animation (up to about 10 frames a second, only while someone is looking) |
| `console.log` | To the game's log |
| `<canvas>`, `getContext("2d")` | Rectangles, paths, arcs, curves, text, gradients, transforms, `save`/`restore`, `clip`, line dashes |
| `ctx.drawImage(picture, x, y [, w, h])`, or with a part `(picture, sx, sy, sw, sh, x, y, w, h)` | Draws another canvas, an `<img>` on the page, or `new Image()` with `src` `"item:minecraft:diamond"` (the item's texture; a block's side) or `"image:cam"` (a Qbot's picture given with `web.image`). Pixels stay sharp. Pictures are there at once (`onload` runs straight away) |

A script that throws shows its error at the top of the close-up, and the program hears it as an
`"error"` event. A script gets 16 MB, and a run is stopped after 0.4 s (loading) or 60 ms (an event
or frame). There is no `fetch`: pages can't reach anything; data comes from the program. Scripts
run much slower than in a browser (a few million steps a second): plenty for pages and animated
gauges, not for heavy number crunching. When a page has scripts, `{{name}}` is filled in once,
when it loads; after that, `panel.onState` is how a script hears changes.

**Crafting (placeholder):** makes 2: `IRI` / `ISI` / `ICI`: iron around RAM, an LED Screen and a
cable.

### 7c.6 Limits

Screens are drawn by each player's game, so what is limited is what could flood players or slow
their games, not how many screens there are:

- **Web Panels in use:** a player may have up to `limits.web_panels_per_player` (16) Web Panels
  showing a page, in loaded areas. A program that tries to show another of theirs is told so
  (clear one with `web.clear()`, or break it). Text Monitors and Control Panels have no limit.
- **Updates:** what a screen shows goes to nearby players at most ten times a second, and a Web
  Panel's whole page at most once a second (its state, the part that usually changes, still
  updates ten times a second). A program changing things faster only sends the latest.
- **Scripts:** each player's game runs the scripts of the 8 nearest scripted Web Panels at a time;
  the others wait, showing their last picture, until you come closer.

- **Tablets:** only the player holding a tablet is sent its screen, and only the selected tab's.
  A tablet's Web Panel scripts run first among that player's scripted panels.

Widgets bound straight to an action without code are planned but not built.

### 7c.7 Tablets: a screen in your hand

A **Tablet** is a screen you carry. It has an Infinite antenna inside, so it works anywhere, in
any dimension.

**Crafting:** `ISI` / `ICI` / `IAI`: iron ingots around an LCD Screen, an Integrated Circuit and an
Infinite Antenna.

| Do | Get |
|---|---|
| Sneak and right-click a network's antenna | Pairs the tablet with that network: a new tab. Do it again to unpair. The antenna's access setting decides who may pair (private: its owner only). Pair with the network's router, its first antenna |
| Sneak and right-click a Qbot | Pairs the tablet with that Qbot: a tab for its own program, reached by its radio anywhere (section 7d.3c). Only its owner may. The tab stays the Qbot's when it is broken (renamed on an anvil, say) and placed again |
| Right-click in the air | The tablet's view: its tabs along the top, the selected tab's screen below. Click a tab to switch. Touch, drag and type as in a screen's close-up view |
| **Delete**, twice, in the tablet's view | Unpairs the tab showing, without going back to its antenna or Qbot: the first press asks ("Press Delete again to unpair ..."), a second within 3 seconds unpairs. While a page's text field is being typed in, Delete goes to the field |
| Hold it | The selected tab's screen shows on the tablet in your hand, live |

**Tabs, not fights.** Each network a tablet is paired with (up to 10; pairing an eleventh takes
the place of the oldest) has a screen of its own on
it: a tab. A network's programs only ever draw on their own tab, and you pick which tab shows.
Programs on one network share its tab, as they share a Control Panel.

**Programs.** A network's programs reach the tablets paired with it through its antenna:

```python
from logistics import network
net = network()
tab = net.tablet()          # the only tablet paired; or net.tablet("Steve"): by its player, name or address
tab.title = "Reactor"       # what this network's tab says (until set: the antenna's name or address)
panel = tab.panel()         # the tab's screen as a Control Panel: 12 x 8 cells
mon = tab.monitor()         # ...or a Text Monitor: 45 x 20 characters
web = tab.web_panel()       # ...or a Web Panel: 384 x 256 CSS pixels
```

| Call | Gives |
|---|---|
| `net.tablet(key=None)` | A tablet paired with this network (on a Qbot: with the Qbot): by the name of the player carrying it, its item name (rename it on an anvil) or its address; with no key, the only one |
| `net.tablets()` | Every tablet paired with this network |
| `tab.title` | What this network's tab says; set it to name the tab |
| `tab.holder` | The player who carried it last |
| `tab.monitor()`, `tab.panel()`, `tab.web_panel()` | This network's screen on it, as that kind of screen, with everything that kind can do (sections 7c.4 to 7c.5b) |
| `tab.refresh()` | Looks it up again (who carries it) |

The screen is as big as a display 3 blocks wide and 2 high. It is one kind at a time: asking for
another kind starts it blank as that kind. Touches and clicks reach programs as a screen's do,
from the player holding the tablet; their events also have `.tablet` (True) and `.title`. What a
program draws is kept with the world, so a tablet shows it again when picked up. A tablet nobody
has carried for 30 days is forgotten, with its pairings.

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
- `ssh` from a computer (or a laptop's terminal) reaches a Qbot from a distance: its terminal,
  and its files in the editor, as a laptop right-clicked on it gives you up close (section 7.4).


| | Iron Qbot |
|---|---|
| Inventory | **16 slots**, a 4x4 grid (numbered 0 to 15) |
| Hand | One tool, which it digs, attacks and is used with |
| Upgrade sockets | Four (a 2x2), one upgrade of each kind: a Chunk Loader Upgrade keeps the chunks it is in loaded; a Transport Upgrade lets its program move things to and from a network behind an antenna in range (section 7.3b) |
| Energy cell | **1,000,000 FE** |
| Tanks | **Two of 4,000 mB**, one fluid each, so it can carry water and lava at once |
| Digs as | An unenchanted **iron** tool, with nothing in its hand |
| Crafts | As a **crafting table**, from what it carries (section 7d.3a) |
| Radio | An **Infinite antenna**, from the laptop it is built from: its own address, anywhere (section 7d.3c) |

- **No fuel.** Moving and working cost nothing. The energy cell and tanks are things it
  carries, to fill machines and tanks with or bring back.
- **Placing it:** it faces you, like a computer.
- **Right-click:** its screen: inventory, hand and upgrade slots, gauges for its cell and
  tanks, and Run, Pause and Stop with what its program printed.
- **Breaking it:** everything stays with the item, as a shulker box keeps its things: its
  inventory, the tool in its hand, its upgrades, its files (its drive), name, energy and fluid.
  Placed again, it has them all. An explosion doesn't destroy it. A Qbot item carrying things
  can't go into a Qbot, a shulker box or a bundle (an empty one can). Broken in creative mode, it
  still drops itself if it carries anything. What resets: its owner (whoever places it), its
  address, its password and who may open or log in to it, and it is placed switched off.
- It is a computer on the network (cables and devices beside it join it), and counts against
  `limits.qbots_per_player` (5), not the computer limit. A machine-placed Qbot is inactive
  until someone claims it, and the Programmer programs Qbots like computers.
- A step or a turn takes a tenth of a second (2 game ticks). While it works, it swings its arms; it hovers
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
| `inspect.at(forward=0, up=0, right=0)` | The block that many blocks forward, up and to the right of the Qbot (negative numbers are back, down and left), with the same attributes: `inspect.at(forward=3, up=1).id`. It must be within the Qbot's reach: 1 block (the six touching it), or 4 with a Sense Upgrade; further is an error that says so |
| `inspect.scan(what=None)` | Every block within reach that isn't air, nearest first: a list of `{"forward", "up", "right", "distance", "id", "fluid"}`. `what` is a block name or filter, to find only those: `inspect.scan("#c:ores")`. With a Sense Upgrade that is every block within 4: a Qbot can find ore behind a wall and dig to it |
| `inspect.reach` | How many blocks away it senses: 1, or 4 with a Sense Upgrade |

**Sides** are `Side.FRONT`, `BACK`, `LEFT`, `RIGHT`, `TOP`, `BOTTOM` (or those words in lower
case), as the Qbot faces. Every call below that takes a side defaults to the front.

Seeing isn't looking inside: `inspect` says a chest is a chest; `inventory.read()` says what is in it.

### 7d.2 Working

| Call | Does |
|---|---|
| `qbot.dig(side)` | Digs the block there, taking as long as a player would with the same tool, and returns what it got: `{"minecraft:cobblestone": 1}`. Every drop must fit in its inventory, or nothing is dug |
| `qbot.place(side, item=None)` | Places a block from its inventory: the named one, or the first block it holds. Returns its name, or `False` if nothing was placed (it holds none, the space is taken, its owner may not build there); `qbot.why` then says which |
| `qbot.use(side, item=None)` | Right-clicks there as a player would: with the named item from its inventory, else the tool in its hand, else an empty hand. Works on blocks (levers, doors, bone meal, a hoe on dirt) and creatures (shears on a sheep, a bucket on a cow); what that gives goes into its inventory |
| `qbot.attack(side, wait=True)` | Attacks a creature there with the tool in its hand (or its fist), as a player swings: the tool's damage and enchantments count, and each swing waits for the tool's cooldown since the last (a sword about half a second). `wait=False` swings at once, as weakly as the cooldown leaves it |
| `qbot.pump(side)` | Takes up a source of water or lava (1,000 mB) into a tank |
| `qbot.pour(side, fluid=None)` | Pours 1,000 mB from a tank, making a source block there |

**Digging power.** With nothing in its hand, an Iron Qbot digs as the right iron tool would (an
iron pickaxe for stone, an iron axe for wood), so it can dig iron, gold, diamond and redstone ore
but not obsidian or ancient debris. **A tool in its hand** is used when it does better: its
level and speed count, and enchantments that change what drops (Fortune, Silk Touch) work as
they would for a player. (Efficiency doesn't speed it up yet.) **With a Netherite Upgrade** its
empty hand digs as netherite tools would, with Silk Touch (obsidian comes up as obsidian, glass
as glass, grass as grass blocks); a tool in its hand that can harvest the block still wins, so
hold a Fortune pickaxe for ores.

**Tools wear** like a player's. When one breaks, what the Qbot was doing is done and
`ToolBrokenError` is raised (a kind of `QbotError`).

| Call | Does |
|---|---|
| `tools.equip(item)` | Takes one of the item from its inventory into its hand; what was there goes back into the inventory. If the inventory has no room for it, the two swap places when the item was the only one in its slot, and otherwise `equip` raises an error and nothing changes |
| `tools.unequip()` | Puts it back into the inventory |
| `tools.equipped()` | `{"item", "damage", "max_damage"}`, or `None` |

Everything it does to the world, it does as its owner: land claims and protection apply, and a
Qbot nobody owns (placed by a machine) can't change the world.

### 7d.3 What it carries

| Call | Does |
|---|---|
| `inventory.list()` | `[{"slot": 0, "item": "minecraft:coal", "count": 12}, ...]` |
| `inventory.size()`, `inventory.slots()` | 16, and every slot, empty ones too, as a chest's (section 5.2) |
| `inventory.count(item=None)` | How many of an item (or everything). One exact name: a wildcard, tag or filter is an error (add up `inventory.list()` for those) |
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

### 7d.3a Crafting: a crafting table built in

A Qbot crafts from what it carries, as a player at a crafting table would, by this world's
own recipes (every mod's), by its program or by `craft` in its shell. Its screen shows the grid:
the crafting table's top, faintly, over slots 0-2, 4-6 and 8-10, with slot 7, where the product goes,
shaded. Each of its 16 slots shows its number in the corner.

```python
import qbot
qbot.craft("oak planks", 4)      # from a log it carries: 4
qbot.craft("stick", 4)
qbot.craft("torch", 16)          # as many as it can, up to 16 (whole crafts: may be a few more)
qbot.craft()                     # whatever is laid out in its grid, once
```

| Call | Does |
|---|---|
| `qbot.craft(item, count=1)` | Finds a crafting-table recipe for the item whose ingredients it holds, takes them from any of its slots, and crafts until it has made at least `count`. Returns how many it made: fewer if it ran out. `QbotError` if it can make none, saying what the recipe needs |
| `qbot.craft(count=1)` | Crafts what is laid out in the top-left 3x3 of its inventory, as a player's grid: slots `0 1 2` / `4 5 6` / `8 9 10`, one of each slot per craft. What it makes goes into slot 7, beside the grid's middle row (then 3, 11 and 12 to 15 once that is full). Every recipe works this way, those known only by their pattern too (fireworks, dyed armour, map copies) |

- **By name** it doesn't craft the parts: for torches it needs sticks and coal, not planks.
  `game.plan` (section 7k) works out the steps, and each step's `makes` names what to craft.
- For a tag ingredient ("any planks") it uses the kind it holds most of.
- **A craft is all or nothing.** If what it makes, and what the grid leaves (the buckets from a
  cake), won't fit in its inventory, that craft isn't done.

### 7d.3b The camera: pictures of what it sees

```python
import qbot
from logistics import network

pic = qbot.view()                    # 256 x 192 pixels of what is in front of it
print(pic.block_at(128, 96))         # "minecraft:stone", "monster", ... or None for the sky
print(pic.creature_at(128, 96))      # "minecraft:cow", ... or None
print(pic.counts())                  # how many pixels show each thing

web = network().web_panel("camera")
web.html('<img src="image:cam" style="width: 256px; height: 192px">')
web.image("cam", pic)                # players see the picture, drawn with the game's own blocks
```

| Call | Does |
|---|---|
| `qbot.view(width=256, height=192, pitch=0, wait=True)` | A picture of what is in front of it, up to 32 blocks away. `pitch` tilts it up (positive) or down, in degrees. At most 256 x 192, **5 a second** of game time (the server's `views_per_second` setting, 1 to 10): asked sooner, it waits until it may (`wait=False` raises `QbotError` instead). Don't time pictures by the clock: a busy server's second is longer than a real one. Even light everywhere: a Qbot sees in the dark |
| `qbot.snapshot(width=256, height=192, pitch=0, wait=True)` | The same as `view()` (another name for it) |
| `pic.width`, `pic.height` | Its size in pixels |
| `pic.block_at(x, y)` | What a pixel shows: a block's id, `"monster"`, `"animal"`, `"player"` or `"creature"`, or `None` for the sky (0, 0 is the top left). Past 128 x 96, each name covers a 2 x 2 square of pixels |
| `pic.creature_at(x, y)` | Which creature a pixel shows (`"minecraft:cow"`, `"minecraft:player"`, `"minecraft:item"` for an item on the ground, `"minecraft:item_frame"`...), or `None` if it shows no creature |
| `pic.item_at(x, y)` | Which item lies on the ground or hangs in a frame at that pixel (`"minecraft:diamond"`), or `None` |
| `pic.counts()` | How many pixels show each thing (creatures by kind, as `block_at` gives them) |
| `web.image(name, pic)` | Puts it on a web panel, as `<img src="image:name">` (up to 8 on a panel; `None` takes one off). The picture's number, `pic.id`, works too, so a Qbot can send it in a message for another computer to show |

**How it is made.** The server doesn't draw the picture: it lists the blocks ahead with a face
turned towards the Qbot and not covered by the block beside it, and the creatures and other
things in sight (a few KB). Each player's game draws those with the game's own models and
textures (resource packs and modded blocks included), nearest in front, in the background so the
game doesn't stutter; on a panel the last picture stays up until the next is drawn. A picture is
shown pixelated, two CSS pixels to a pixel unless CSS sizes it. Pictures are kept on the server
for a while (the latest 256), not with the world: after a restart, take them again.

**What it shows.** Creatures with the game's own models, turned the way they face (young ones
small), players with their skins; items on the ground, item frames with what they hold, armour
stands and paintings. What creatures hold or wear is left out, and each looks as a new one of
its kind would (a sheep white, a villager without a job). Water and lava slope as they do in the
game. Blocks the game draws with code (chests, signs, beds, banners, heads, shulker boxes, pots,
bells, conduits) are drawn from the game's own models for them, shut and without their text or
patterns; any other block that has no model of its own (some from other mods) shows as a box of
its outline. See-through pixels (glass, leaves) are left out rather than blended, so stained
glass shows only its frame. Barriers, light blocks and structure voids are seen through.

### 7d.3c Its radio: reached anywhere

A Qbot is built from a laptop, so it has the laptop's **Infinite antenna**. It has an external
address of its own, kept as it moves, and is reached there from anywhere, in any dimension:

```python
import qbot, network
print(qbot.address())                      # "100.64.0.9:1001": give this to your base
network.send("100.64.0.12:1003", "here")   # a computer behind your base's antenna
```

| | |
|---|---|
| `qbot.address()` | Its radio's address, as `network.send()` takes it. Its program can print it; `network.ifconfig()` on it says the same |
| Sending | To another antenna's address (`"100.64.0.12:1003"`), from anywhere: an Infinite antenna reaches every antenna in its dimension, and Infinite ones in others, on any frequency. That antenna's access setting still decides if it lets the Qbot in. Its messages always come from its own address, so answers find it wherever it has gone |
| Receiving | `network.send("100.64.0.9:1001", ...)` from a computer whose network has an antenna (in range of the Qbot by that antenna's reach, or anywhere if the antenna is Infinite), or from another Qbot. Only its **owner's** computers and Qbots may send to it |

On cables it is also on that network, as before, by its internal address. Its antenna doesn't
count against the antenna limit. A Qbot's radio carries messages; `ssh` still reaches a Qbot
through the network it is cabled to.

**A tablet of its own.** Sneak and right-click a Qbot with a tablet to pair the tablet with the
Qbot itself (again to unpair, or press Delete twice in the tablet's view; only its owner may
pair). The tab goes with the Qbot's drive, so it is kept when the Qbot is broken and placed again. The tablet gets a tab that is the Qbot's,
and the Qbot's program draws on it as any network's program does, with no antenna or base
computer, wherever the Qbot goes:

```python
from logistics import network
tab = network().tablet()      # on a Qbot: a tablet paired with this Qbot
tab.title = "Miner"
web = tab.web_panel()         # touches and clicks there reach this Qbot's program
```

On a Qbot, `net.tablet()` and `net.tablets()` find the tablets paired with the Qbot, not those
of a network it happens to be cabled to.

`helpme/qbots/remote` drives a Qbot from a tablet: its `startup.py` runs on the Qbot alone,
showing the controls and the camera view on the tablet and handling each button with `events`.
`helpme/qbots/remote/base` does the same from a base computer: `driver.py` on the Qbot obeys
orders sent by radio from `controller.py`.

### 7d.4 Not yet

Better tiers (diamond, with a full chest of slots) and inventory filters are planned but not built.
(Hoppers, pipes and cables do reach into a Qbot: its slots, tanks and energy cell, from any side.)

**Recipe:** `ITI` / `ALP` / `IHI`: a crafting table on top, an iron axe and an iron pickaxe either side
of a **Laptop**, a chest below, iron in the corners. The laptop's hard drives come back to you
when it is crafted; your laptop's files aren't in the laptop item (they are yours, on any laptop),
so nothing is lost.

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

**One command at a time.** Put a command after the login and it runs on the computer as if
typed at its prompt (it shows in its terminal too); its output comes back and ssh ends with
its status, 0 if it worked. This is how scripts, and AI assistants on your PC, look at the game
and test programs:

```
ssh miner@localhost -p 2222 "devices --json"   # the network, as JSON
ssh miner@localhost -p 2222 "run test.py"      # its output, and any error
```

A program still going after 120 seconds is stopped, as Ctrl+C would. A computer already
running a program answers "busy" (status 3) until it is stopped; commands that need the
keyboard (`python` alone, `ssh`, `edit`, `settings`) are refused (status 2).

**AI assistants.** `git clone helpme` brings `AGENTS.md`, a short guide for AI coding tools
(what the computers' Python can do, the shape of a program, how to test over ssh), and
`REFERENCE.md`, this manual. Copy both to the top of the drive (`cp /helpme/AGENTS.md /`) and
tools that open the drive in VS Code read them.

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

**Lessons that come with the mod.** `git clone lessons` copies twenty lessons into `/lessons`
(section 7l), and a new computer suggests it until `/lessons` is there. Both bundled
repositories always go to the top of the drive, wherever they are cloned from, and `git clone`
ends with where to start: `Start with:  cd /lessons    then:  read README.md`.

**start_here.py, for programmers.** The first time a player opens the editor of a computer,
server or laptop of their own (not a Qbot, and not someone else's), `/start_here.py` is put on
its drive and opened: a short program that crafts torches from two storage chests, showing
devices by name, a storage, moves, faces, slots, filters and a crafting table's screen. Each
player gets it once (a file of that name already there is kept); `git clone helpme` brings it
again on any computer.

**Examples that come with the mod.** `git clone helpme` copies a set of examples onto the
computer's drive, in `/helpme`: no Uplink or internet needed. They are sorted by topic, each
folder with a README saying what its examples do and which blocks they need:

| Folder | Examples |
|---|---|
| `basics` | Python, files, the network (start here) |
| `buses` | Seven steps with Serial Buses (count, move, filters, a furnace, watching, sorting, crafting), then `factory`: one program, one job per file, started together under `events` |
| `storage` | Many chests as one (`net.storage`), sorting with bus filters and matching, and data filters and `Filter` |
| `events` | Timers, handlers, passing your own values, keeping handlers quick (most need no blocks) |
| `world` | The Entity Sensor (animals, a guard, a trader), a self-tending wheat farm with a Block Sensor and a sign, emptying a Collector, the Dropper Plate, and `env` (day and night, weather) |
| `redstone` | Logic Pins and events |
| `displays` | A Text Monitor, a Control Panel, a Web Panel storage browser (item pictures, search) and a Web Panel dashboard (JavaScript, canvas) |
| `qbots` | A tunnel digger and the camera |
| `network` | Two computers talking |
| `internet` | `requests` and git |
| `server` | A background service |

They work like a cloned repository: `git status` shows what you changed, and `git pull` in the
folder fetches newer examples when the mod updates (refusing to overwrite your changes unless
`--force`). They can't be pushed. A computer says so when it starts, until `/helpme` exists.

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
- **Your token:** `/qbotics git token` opens a screen to enter it (or the editor's Git panel, section 1.2). It is kept in the world's files
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

## 7j. The AI agent: help in chat from your own model

Ask an AI model for help without leaving the game: it looks at your computers, devices and
files, and at what you are looking at, and can write and test programs. **It uses your own
model**: a local one (Ollama, LM Studio, llama.cpp's server) or any service with an
OpenAI-style API. The model's address and key stay on your PC; the mod has no model of its own.

**On a server that offers one, there is nothing to set up**: the server's owner has given it a
model, and `@ai` simply works (`/qbotics ai` says whether this server has one). The server calls
the model for you, so its address and any key stay on the server. `/ai use own` uses your own
model instead; `/ai use server` goes back.

**Your own model.** Where the server offers none, set one up once (kept in
`config/qbotics-ai.json` in your game folder):

```
/ai url http://localhost:11434/v1     # Ollama (the default); LM Studio: http://localhost:1234/v1
/ai model qwen3-vl:8b                 # the model's name, as your server lists it
/ai key <key>                         # only for services that need one; /ai key none removes it
```

**Asking.** Type `@ai` and a question in chat. Only you see it (unless the server shares
replies, below):

```
@ai why doesn't my sorter move coal?
@ai write me a program that keeps the furnace fed from the chest named ores
@ai look: how should I connect this?
```

Starting with `look` also shows the model a picture of your view and what the block you are
looking at is. That needs a model that can see pictures (a "VL" or vision model); for one that
can't, `/ai vision off`.

While it works, chat shows what it does in grey: `(devices)`, `(read file /main.py)`. It
remembers the conversation until `/ai clear`, or until you join another server or world (a
conversation is never carried from one to the next); `/ai stop` stops it mid-way.

| What it can do | |
|---|---|
| See your computers and laptop, and which you opened last | It works on that one unless told another |
| List a computer's devices, read its files, check code for mistakes, search this manual | Freely |
| See what you are looking at, or a picture of your view | When asked |
| **Other mods:** which are installed (with versions), what their guidebooks (Patchouli), tooltips and JEI information pages say, and **your key bindings** ("how do I turn on my jetpack?" is answered with your actual key) | Freely: all read from your own game, as it is set up now. A mod that explains itself only on a wiki isn't seen; the helper says when an answer comes from what it remembers instead |
| **Try Python, look-only**, on one of your computers: read item counts, chests and storage, what Entity and Block Sensors see, `env` | Freely: in this look-only scratchpad anything that would change something (moving items, using or attacking, breaking, placing, writing signs or files, the internet) is refused, and your files are untouched |
| **Stop the program running on a computer** so it can test there, and **start it again** afterwards | It asks first, each time. It sees which computers are running what, and stops a program as Ctrl+C does |
| **Write a file**, **run a command** (`run test.py`), or **run Python that may change things** | It asks first: **[Allow]** or **[Deny]** in chat; point at the question to see the file or code. A command it runs shows in the computer's terminal; both are stopped after 30 seconds |

**Its scratchpad.** So that its answers come from your world, not guesses, the helper can try
code on your computer before it answers: "how much iron is in my storage?" is answered by
counting it, "is the wheat ripe?" by asking your Block Sensor. Look-only runs don't ask you,
because they can't change anything: the computer itself refuses every call that would.

**Busy computers.** A computer running a program (an `events.run()` loop, say) can't run the
helper's code at the same time. The helper can ask to stop it ("[AI] wants to stop the program
running on farmpc to test there"), do its tests, and ask to start it again: by default the same
command it stopped (a Qbot's own program starts as its Run button starts it).

In the computer's editor, the **Chat** panel (section 1.4b) asks it with the open file, cursor
and selection sent along. `/ai auto on` lets it write and run without asking; `/ai auto off` puts the question back.
`/ai status` shows the settings. Models that think before answering (Qwen3 and
others) are asked to think only a little, which makes answers many times quicker: `/ai think off`, `low`
(the default), `medium` or `high` (as much as the model likes, for hard problems). Servers that don't
take these settings are sent the question without them. It reaches only your own computers, as you can over ssh
(section 7f). A computer running a program is busy: it can read its files but not run another.

**Offering a model to everyone** (server owners). In `qbotics-server.toml`, under `[ai]`:

```toml
url = "http://192.168.1.190:11435/v1"   # the model's OpenAI-style address, as the server reaches it
model = "qwen3.8-code"
key = ""                                # if it needs one; never sent to players
thinking = "low"                        # off, low, medium, high
vision = true                           # if it can see pictures
requests_per_minute = 30                # per player (each step of an answer is one)
max_parallel = 4                        # calls at once, for everyone; more wait their turn
```

When the server starts it tests the model with a short question, and offers it only if that
works; `/qbotics ai` shows how it went and `/qbotics ai test` (operators) tries again, after
starting the model's server, say. Players only ever learn the model's name. Since the server
makes the calls, players needn't reach the model themselves: one on your home network serves
players anywhere.

**For a class.** With the server's `ai.share_replies` on, every question asked of the agent and
its answer appear in everyone's chat, so a teacher and students can all work with one answer.
`ai.enabled` off stops the agent touching anything in the world (it can still answer).

**Choosing a model.** Small local models (7 to 14 billion parameters) can do this, better with
"tools" support listed by their server; one with vision for `look`. Larger ones are better at
writing whole programs.

## 7k2. Where a computer is: the `env` module

Every computer can read what the world is like where it is: the time and day, the moon, the
weather, the biome, the light, and its own place. **Where** is where the computer is: a placed
computer's block, a Qbot where it stands now, and a laptop the player carrying it.

```python
import env
r = env.now()
print(r["clock"], r["phase"], "day", r["day"], r["weather"], r["biome"])

for change in env.watch("dark"):                 # sleeps until it gets dark (or light)
    if change["now"]:
        chat.say("Night: monsters can spawn outside.")
```

| Call | Gives |
|---|---|
| `env.now()` | Everything below, as a dict |
| `env.position()` | `(x, y, z)`: where this computer is (a laptop: its player) |
| `env.is_day()`, `env.is_dark()` | From sunrise to sunset; dark enough for monsters to spawn in the open |
| `env.watch(what=None, every=1.0)` | Changes, as they come (below) |

| Key | Gives |
|---|---|
| `pos`, `dimension` | `[x, y, z]`, and `"minecraft:overworld"` |
| `has_time` | `False` where time stands still (the Nether, the End): then `time`, `clock`, `phase`, `day` and `moon` are `None` |
| `time`, `clock` | Ticks into the day, 0 to 23,999 (0 is just after sunrise), and as a clock: `"14:30"` |
| `phase` | `"sunrise"` (23,000 on), `"day"` (to 12,000), `"sunset"` (to 13,000), `"night"` (to 23,000) |
| `day` | Days since the world began |
| `moon`, `moon_name` | 0 to 7 (0 full, 4 new), and `"full_moon"`, `"waning_gibbous"`, ... |
| `dark` | `True` when it is dark enough outside for monsters to spawn in the open: at night, and in a thunderstorm |
| `weather` | `"clear"`, `"rain"` or `"thunder"`, for the whole world |
| `here` | What falls here: `"rain"`, `"snow"` or `"none"` (clear weather, a desert, a roof overhead) |
| `sky` | `True` if the sky is open above |
| `biome` | `"minecraft:plains"` |
| `light` | `{"block", "sky", "now"}`, 0 to 15, at the computer |

**Watching.** `env.watch()` looks again every second (one small call; nothing is scanned) and
gives each change as `{"what": "phase", "was": "sunset", "now": "night", "env": {...}}`. With no
`what`, it watches the day's phase, darkness, weather, what falls here, biome and dimension; give
one key or a list to watch others (`"day"`, `"moon"`, `"sky"`). Like other watches it has
`changes()`, `wait(timeout)`, and works in a `for` loop. Hand one to `events.on_change(env.watch(), f)` to
run alongside other events. A Qbot or a laptop that moves into
another biome, or through a portal, sees that as a change.

## 7k. Items and recipes: the `game` module

What the game itself knows about items and recipes: every item, tag and recipe loaded in this
world, from every mod, including the modpack's own changes. The same answers come from the
shell, from programs, and from the AI agent, so they always agree.

```
/ $ recipe crafting table
1 recipe make minecraft:crafting_table:
- minecraft:crafting_table  [minecraft:crafting_shaped]
  {"pattern": ["##", "##"], "key": {"#": "#minecraft:planks"}, "result": {"id": "minecraft:crafting_table", "count": 1}}
/ $ recipe --uses iron_ingot          # the recipes it is used in
/ $ item coal                         # names, tags, stack size, fuel_ticks 1600 ...
/ $ items @create                     # every item from a mod; or a word, *pattern*, #tag
```

`--json` gives the same as JSON, for programs and scripts (`ssh ... "recipe --json torch"`).

```python
import game
for r in game.recipes("Crafting Table"):    # a list of dicts, at most 10 (limit=, up to 50)
    print(r["type"], r["result"])
game.uses("iron_ingot", limit=20)           # the recipes it is used in
game.item("coal")["fuel_ticks"]             # 1600
game.find_items("#c:ingots")                # [{"id": "minecraft:iron_ingot", "name": "Iron Ingot"}, ...]
```

| Call | Gives |
|---|---|
| `game.recipes(item, limit=10)` | The recipes that make it: a list of dicts; empty if nothing makes it |
| `game.uses(item, limit=10)` | The recipes it is an ingredient of, those taking it by a tag it carries included |
| `game.item(item)` | `id`, `name`, `mod`, `tags`, `stack_size`, `durability`, `fuel_ticks`, `food_nutrition`, how many recipes make and use it |
| `game.find_items(query, limit=40)` | Items matching a word, pattern, tag or mod: `{"id", "name"}` each |
| `game.fuels(limit=20)` | What burns in a furnace, best first: `{"id", "name", "ticks", "smelts"}` (`items --fuels` in the shell) |
| `game.plan(item, count=1, have=None, stations=None)` | How to make it from what is in storage, worked out by the server (below) |

An item can be its full name (`minecraft:crafting_table`), its name without the mod
(`crafting_table`) or its display name (`"Crafting Table"`); a name that doesn't exist raises
`NetworkError` with close matches.

**Recipes ready to craft.** Each recipe the game can describe for a program also has `"craft"`:

```python
{"station": "minecraft:crafting_table",            # the block that makes it
 "inputs": [{"slot": 1, "item": "#minecraft:planks", "count": 1,
             "options": ["minecraft:oak_planks", "minecraft:spruce_planks", ...]}, ...],
 "output": {"slot": 0, "item": "minecraft:crafting_table", "count": 1},
 "leftovers": []}                                  # what the grid keeps: a bucket from milk
```

`slot` is the slot of that block's screen, as `Recipe` numbers them (a crafting table: 0 the
product, 1 to 9 the grid; a furnace: 0 in, 1 fuel, 2 out; a stonecutter: 0 in, 1 out, with the
`button` to press; a smithing table: 0 template, 1 base, 2 addition, 3 out). `item` is a filter
to give `move` or `Recipe` as it is: a name, a `#tag` or `"a | b"`; `options` are the items that
fit. Furnaces also give `cook_ticks`. Other mods' machines have no `"craft"` (their slots can't
be known from the recipe); their raw recipe is still there.

`Recipe.from_game(recipe, sources, destination)` (in `logistics`) makes a `Recipe` from one,
for a crafting table, stonecutter or smithing table: `max_runs()`, `can_craft()` and `craft()`
then work as for one written by hand.

**Plans: the whole tree, worked out by the server.** `game.plan(item, count, have)` works out
how to make it from what is in storage (`have`: a device such as a bus or an interface, a list
of them, `contents()`, or `{item: count}`), every step in order, and is done by the server, not
in the computer's small Python:

```python
from logistics import network, Recipe
import game
net = network()
store, crafter = net.interface("store"), net.bus("crafter")   # a bus on a crafting table

p = game.plan("chest", 4, store)
print(p["ok"], p["max_craftable"], p["missing"])     # can it, how many could, what is short
for step in p["steps"]:                              # planks, then the chests
    recipe = Recipe.from_game(step, sources=store, destination=store)
    with crafter.open_screen() as screen:
        recipe.craft(step["runs"], screen)
```

| Key | |
|---|---|
| `steps` | The recipes in the order to do them: `{"recipe", "station", "runs", "makes": {"item", "count"}, "uses": [...], "craft"}` |
| `from_storage` | What it takes from storage: `[{"item", "count"}]` |
| `missing` | What isn't there and can't be made: `[{"item", "count"}]` |
| `ok` | `True` if nothing is missing |
| `max_craftable` | The most that could be made from what is there |

Where several recipes make something, the one whose ingredients are most at hand is used (iron
ingots from the raw iron you have, not from a block you don't); for a tag ingredient, whatever
matching items are stored, mixed if need be. `stations=["crafting_table"]` keeps to the blocks
your program works. A very large tree answers `"too_big": True`.

A recipe is a dict in the form the game loaded it from:
`"type"` says the table or machine (`minecraft:crafting_shaped`, `minecraft:smelting`, or a
mod's own, such as a crushing wheel's), so its other keys depend on the type. An ingredient
written `"#c:ingots/iron"` is a tag: any item carrying it. Very long recipes come as just
`{"id", "type", "too_long": true}`.

## 7l. Lessons: advancements for learning

Twenty lessons take a player from `print("hello world")` to a base that runs itself. Each is
an **advancement**, in its own tab of the advancements screen (L): **Qbotics Lessons**, which
opens when the player first holds a computer or a laptop. Each lesson has a README in
`/lessons`: `git clone lessons` (no internet needed), then `cd /lessons` and `read README.md`.
Lessons 1 to 5 each come with a `start.py` that runs as it is, to change.

A lesson is met by what a program **does**, not how it is written: any program doing the job
counts, a helpme example too.

| # | Lesson | Met when a program... |
|---|---|---|
| 1 | Hello, World | prints "hello world" (any capitals and punctuation) |
| 2 | Who's There? | finds a device on its network (`net.bus("inbox")`) |
| 3 | Moving Day | moves at least one item with `move()` |
| 4 | A Place for Everything | moves items to a list of destinations, through `only=`/`without=`, or into a bus with a filter |
| 5 | Waiting, Not Looping | sees a change through a watch, or gets an event (a pin, a panel, a page, a message) while listening |
| 6 | Redstone, Programmed | sends a signal from a Logic Pin, **and** gets a pin's event through `events.on_pin` |
| 7 | Hot Stuff | takes what a furnace (or smoker, blast furnace) smelted |
| 8 | Made to Order | takes a crafting table's result through its screen |
| 9 | Tools of the Trade | takes the product of another block's screen: stonecutter, smithing table, grindstone, loom, anvil, or a modded one |
| 10 | Many Chests as One | moves items into or out of a storage (`net.storage`) of four or more containers |
| 11 | On Screen | opens a Text Monitor to draw on |
| 12 | Push the Button | gets a Control Panel widget's event |
| 13 | Web Developer | gets a Web Panel page's event |
| 14 | In the Palm of Your Hand | gets an event from its screen on a Tablet |
| 15 | Always On | started from `/startup.sh` or `/startup.py`, runs for a game day (24,000 ticks) without ending |
| 16 | Over the Air | sends a message to an external address (`100.64.0.2:1003`) that arrives, **and** works a device behind another antenna |
| 17 | At Your Service | runs as a Server's service for a game day |
| 18 | Down on the Farm | breaks a crop with a Block Sensor (its drops are `#c:crops`) **and** places a crop with one; **or** uses an item on an animal with an Entity Sensor |
| 19 | Robot Worker | on a Qbot, moves it **and** digs |
| 20 | The Factory | all of: crafts at a crafting table fed from a storage of eight or more; sorts (as lesson 4); listens to a display (lessons 11 to 14); runs unattended (lesson 15 or 17) |

- **Whose:** the player who placed the computer, Qbot or Server; a laptop's player. A
  computer nobody placed earns nothing.
- **Only while they are in the game.** Something done while its owner is away counts the next
  time it happens.
- The parts of lessons 6, 16, 18 and 20 can be done at different times, by different programs.
- For modpacks: the advancements are `qbotics:lessons/01_hello` to `qbotics:lessons/20_factory`,
  so quest mods (FTB Quests' advancement task) can hang quests and rewards on them. A data pack
  can add its own, met by the trigger `qbotics:lesson` with `{"lesson": "<step>"}`; the steps
  are `hello`, `network`, `move`, `sorting`, `events`, `pin_out`, `pin_in`, `smelting`, `craft`,
  `workstation`, `storage`, `monitor`, `panel`, `web`, `tablet`, `always_on`, `radio_message`,
  `radio_device`, `service`, `harvest`, `replant`, `rancher`, `qbot_move`, `qbot_dig` and
  `factory_craft`.

## 7m. The devkit: writing programs on your PC, away from the game

The **devkit** is a folder, *Qbotics Programs*, to open in VS Code on your PC. It comes with each
version of the mod (`qbotics-devkit-<version>.zip`) and needs no game running:

- **Hints as you type.** The computers' own modules (`qbot`, `logistics`, `display`, `events`
  and the rest) are in the folder, so VS Code's Python extension lists every call with its help
  (`net.` shows what a network can find; `net.bus("store").` what a bus can do). Names never
  defined are underlined, and `time` hints show the computers' `time` (`ticks_ms`, no `time()`).
- **Will it compile?** **Ctrl+Shift+B** runs the check on the open file: the in-game editor's
  own checker (section 1.2b), then the computers' own Python compiler (the same engine, inside
  the devkit), then whether what the program uses from a module is there on a computer
  (`time.monotonic` isn't). A file that passes here passes in the game.
- **Will it run?** The same check lists what the program needs in the game, read from its code:
  the devices it finds by name (*an Item Serial Bus (or USB) named "store"*), or with no name
  (*exactly one Text Monitor*), a Qbot (it imports `qbot`), an antenna (sending past the
  network, `remote()`, with its Transport Upgrades), an Uplink (`requests`, `git`), and files and
  modules it reads from the drive. A file of the program's own that it imports is checked too.
  A name worked out as the program runs is reported as such, not guessed.

Results show in VS Code's **Problems** panel: errors red, warnings amber, needs blue. From a
terminal in the folder: `.\check mine.py` (Windows), `./check.sh mine.py` (Mac, Linux), or a
folder name to check every program in it. Each line is `file:line:column: kind: message`.

The check needs **Java 25**, and finds the one Minecraft's launcher, the CurseForge app or
Prism Launcher brought (or `JAVA_HOME`, or `QBOTICS_JAVA`). The folder also holds `examples/`
(the `/helpme` programs), this manual as `REFERENCE.md`, and `programs/` for your own. Take a
program into the game by pasting it into the editor, over the SSH gateway (section 7f), or with
git (section 7h).

## 8. The Network Probe

A hand tool. Sneak and scroll the mouse wheel to change its mode; its name shows the mode.

| Mode | Right-click does |
|---|---|
| Labels | On a device: opens a pop-up with its name, a ready line of Python, its internal IP, hardware address and external address. Click a line to copy it. A box at the top names the device |
| Slots | Blocks open as usual, and every container screen shows each slot's number. A screen with one inventory (a chest, a furnace) shows that inventory's own numbers, which plates use. A screen with several (a crafting table's grid and product) shows the screen's numbers, which a Bus working the screen uses (section 6.8). Clicking a button on a screen shows the button's number above the screen and writes it to your chat (section 6.8) |
| Tunnels | Right-click a bus, then another, to open a tunnel between them (section 6.3b). Right-click a bus in a tunnel to see where it leads; again to close it |

In either mode, hovering an item in any inventory shows the name programs use for it. For a
bucket it also shows the fluid's name. Ctrl+C copies the name. Holding Shift lists the item's
tags and, for a stack with data (an enchanted book, a potion, a worn tool), its data words
(`enchant=mending`) and the filter naming exactly that stack; Ctrl+Shift+C copies that filter
(section 4).

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
loads (you came back to it, or the server restarted): it starts as from new, and its
`startup.sh` runs (or `startup.py`, section 1.1b). When its area unloads, the computer stops;
a Chunk Loader Upgrade keeps it loaded and running. A computer nobody placed (a command or a
structure put it there) stays off until a player right-clicks it and takes it on. Computers come
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
| `limits.web_panels_per_player` | 16 | Web Panels a player may have showing a page (in loaded areas); -1 for no limit (section 7c.6) |
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
| `ai.enabled` | true | `false`: the in-game AI agent can't look at or change anything (section 7j) |
| `ai.share_replies` | false | `true`: everyone sees every question asked of the agent, and its answer (for classes) |
| `ai.url`, `ai.model`, `ai.key` | "" | A model this server offers every player (section 7j); the key stays on the server (this file is never sent to players) |
| `ai.thinking` | low | How much the server's model may think: off, low, medium, high |
| `ai.vision` | false | `true` if the server's model can see pictures |
| `ai.requests_per_minute` | 30 | Calls to the server's model a minute, per player |
| `ai.max_parallel` | 4 | Calls to it at once, for everyone; more wait their turn |
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
| `sensors.read_other_players_computers` | false | `false`: sensors and Collectors keep to what their owner may change. A Block Sensor's raw data (`details=True`) leaves out other players' computers, Qbots and antennas, and any block on land the owner may not change (`"data"` is `None` and `"data_private"` is `True`); entity inventories and dropped items and experience are only reached on land the owner may change (land claims are respected). `true`: none of that is checked, and raw data includes other players' computers with their owner, drive and password hash (for servers that teach security by letting players try to break in) |
| `chat_interfaces.enabled` | true | `false`: Chat Interfaces do nothing: they neither write to chat, run commands, nor hear chat |
| `chat_interfaces.speak_as_owner` | true | `true`: what a Chat Interface writes is signed with its owner's name. `false`: with the interface's own name, which its owner chooses |
| `sensors.attack_players` | false | `true`: an Entity Sensor's `attack()` may hit players (where the server allows PvP). `false`: it raises an error instead |
| `qbots.views_per_second` | 5 | Pictures (`qbot.view()`) each Qbot may take a second of game time, 1 to 10. Each costs the server a few milliseconds and sends a few KB to each player near the screen showing it; screens update players at most 10 times a second |

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

**Hints.** Under a Python error the terminal adds a line in plain words saying what it usually
means and what to look at, for players new to programming:

```
Traceback (most recent call last):
  File "/lessons/03_first_move/start.py", line 9, in <module>
NameError: name 'mvoe' isn't defined
Hint: Python doesn't know `mvoe` on line 9. Check the spelling (capitals count), and that it
      is made (mvoe = ...) or imported above that line. Text needs quotes: "mvoe".
```

There is one for the common mistakes: unknown names (and the module a game name comes from,
`move` from `logistics`), unreadable lines, indentation, a handler taking the wrong number of
values, joining text and numbers, a missing attribute or `None`, missing keys and list items,
unknown modules, missing files, and running out of memory. The game's own errors
(`NetworkError`) already say what is wrong, so have none.

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
