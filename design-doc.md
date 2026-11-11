# Qbotics — Design Document

> **Name:** Qbotics (mod ID `qbotics`). Python computers, robots, radio and web displays for Minecraft.
> **Status:** Draft v0.3 — 2026-11-10. Milestones 0 and 0b (engine prototype and interpreter speed) are done; results in §14.5.
> **Platform target:** Minecraft 26.1.2 on NeoForge (Java 25), laid out so Fabric can be added later (see §0.1).
> **Built with AI:** Most of this project's design and code is AI-generated. See §0.3.

---

## 0. Before you start

### 0.1 ⚠️ Decide these before writing any code

These can't be changed later without breaking every existing world, config and modpack that uses the mod:

- [x] **Mod ID and name.** Decided: **Qbotics**, mod ID `qbotics`. The mod ID ends up in every block name, save file and config file. Renaming it later breaks worlds. The display name can change; the ID can't.
- [x] **Minecraft version and loader.** Decided: **Minecraft 26.1.2, NeoForge, Java 25**, with Fabric possible later. To keep that door open, the project is split from day one into a `common` module (engine, scheduler, device logic, anything that needs only Minecraft itself) and a thin `neoforge` module (registration, capabilities, networking, config). A `fabric` module can be added beside it later without Architectury.

### 0.2 Licence

- **Goal:** as open as possible for use, modification and forking. No conditions on what anyone does with their own copies.
- **Decision: the Unlicense** (a public-domain dedication, with a permissive fallback where public domain isn't recognised). No attribution, no notice requirements, no copyleft.
  - *Equivalent alternative:* **0BSD**, a plain licence grant with no conditions that holds up the same way in every country. Pick this if the Unlicense's public-domain wording causes trouble with mod hosting sites or contributors.
- **Note on AI-generated code:** copyright protection for purely AI-generated material is legally unsettled and, in some jurisdictions (e.g. the US), may not exist at all. A public-domain-style dedication fits that reality, and it still covers any human-written contributions.
- **Bundled dependencies keep their own licences.** These must ship with their notices: MicroPython (MIT), Chicory (Apache-2.0), QuickJS (MIT).
- Choose the licence **before the first outside contribution**; changing it later needs every contributor's agreement.
- *Not legal advice. Get a quick check if the project grows or goes commercial.*

### 0.3 AI disclosure

Be up front so players who prefer to avoid AI-built software can choose for themselves:

- A clear statement at the top of the README and on every mod listing page (Modrinth, CurseForge, etc.).
- An `AI_DISCLOSURE.md` in the repo: what was AI-generated (design, code, docs, art?), what was reviewed by a human, and which tools were used.
- Keep it accurate as the project evolves.

---

## 1. Vision

A Minecraft mod that adds programmable computers, Qbots (robots), radio networking and displays — programmed in **Python**, with **HTML, CSS and JavaScript** for screens.

ComputerCraft is the inspiration, not the template. The goal is to teach and use the languages that dominate real-world development, in a setting where results are visible and fun.

### 1.1 Audiences

| Audience | What they need |
|---|---|
| **Learners** | Gentle progression, clear error messages, in-game docs and templates. Skills that transfer to real-world Python and web development. |
| **Experienced developers** | Familiar APIs (`asyncio`, `requests`, DOM, `fetch`-style calls), a real editor workflow, type stubs and autocomplete. No invented quirks. |
| **Power users / modpack players** | Deep integration with other mods through peripherals (e.g. custom AE2 inventory interfaces, factory dashboards), events instead of polling, and predictable performance. |

### 1.2 Flagship examples

- **Custom AE2 terminal:** Python reads the ME network through a peripheral. A web panel shows a searchable item grid with a craft button.
- **Factory dashboard:** machine computers broadcast metrics over radio. A dashboard computer draws live charts on a large web panel, which can optionally be viewed in a real browser through the uplink gateway.
- **Embedded circuits:** door controllers, sensors and alarms written in a MicroPython hardware style (`machine.Pin`, interrupts, timers).

### 1.3 Explicit departures from ComputerCraft

| CC behaviour | This mod |
|---|---|
| Lua | Python (MicroPython), plus HTML/CSS/JS for displays |
| Turtle fuel/energy system | **Qbots** have no fuel or energy. They're limited by ownership limits. |
| Turtles see and act on only front, top and bottom | Qbots **sense and interact on all six sides**. Inventory contents are a separate, explicit command |
| Modems / Rednet | **Antennas** (radio), with tiered range, plus an **uplink** and **pseudo-DNS** |
| Sides: `left`/`right` plus compass directions; GPS must be built | Relative sides for actions: **front, right, back, left, top, bottom**. **GPS built into every computer and Qbot** |
| Computer program dies with its chunk | Program lifecycle is separate from the chunk (see §6) |

### 1.4 Non-goals (for now)

- Full CPython or `pip install` of arbitrary PyPI packages.
- A real embedded web browser (Chromium or similar).
- Raw TCP sockets to the real internet.
- Saving and resuming a running program across server restarts (computers reboot and run their startup script; revisit later).

---

## 2. Architecture overview

```
                         SERVER                                       CLIENT (each player)
┌──────────────────────────────────────────────────────────┐   ┌──────────────────────────────┐
│  Main thread (world tick, 50 ms)                         │   │  Render thread               │
│   ├─ World-call executor (budgeted ms/tick)  ◄───────┐   │   │   ├─ Terminal / monitor /    │
│   ├─ Logistics networks (items, fluids, energy)      │   │   │   │   control-surface render │
│   ├─ Ownership registry (UUID → devices)             │   │   │   ├─ Web panel renderer      │
│   └─ Boot queue                                      │   │   │   │   (broad HTML/CSS)       │
│                                                      │   │   │   └─ Panel JS sandbox        │
│                                                      │   │   │       (QuickJS/Wasm, opt.)   │
│  Computer threads (CPU permits = capped cores)       │   │   └──────────────▲───────────────┘
│   └─ Computer VMs: MicroPython → Wasm → Chicory ─────┘   │                  │
│        • fixed heap per tier                             │   panel state diffs / terminal
│        • instruction metering                            │   updates (delta-compressed,
│        • host modules: machine, qbot, gps,               │   rate-capped)  ◄──────────────
│          network, requests, display, logistics, …        │   clicks/input  ──────────────►
│                                                          │
│  Radio subsystem (antennas, routers, DNS, in-game HTTP)  │
│  Uplink service (real-internet HTTP/WS, SSRF-guarded)    │
│  Built-in gateway (external access, off by default)      │
└──────────────────────────────────────────────────────────┘
```

### 2.1 The three layers

The same split as CC (engine → host APIs → ROM). Each layer can change without rewriting the others.

1. **Engine:** a custom MicroPython build, compiled to WebAssembly and run on **Chicory** (a pure-Java Wasm runtime), compiled to JVM bytecode at build time.
2. **Host modules (Java):** `machine`, `qbot`, `gps`, `network` (radio), `requests`, `display`, `logistics`, `peripherals`, `fs`, `events`. Every host function is a security boundary.
3. **ROM (Python):** shell, editor, package manager, web-server micro-framework, Qbot helpers, networking library. Written in Python and frozen into the build or shipped as files.

### 2.2 Core rule: only the main thread touches the world

Python never touches world state directly. Any world-affecting call (redstone, Qbot movement, peripheral access) becomes a request queued for the main thread. The script waits on the result, which arrives in a later tick.

This rule is the main defence against crashes, item duplication and save corruption.

### 2.3 How Python calls into the game

**One mechanism, two styles.** The Java host exposes only two primitives to Python:

- `host.submit(call) -> ticket`: queue a call; returns immediately.
- `host.wait(tickets, timeout) -> results`: block the VM until one or more calls finish, or an event arrives.

Both styles are built on these in the Python ROM:

```python
# Blocking style: the default, best for learners
from qbot import move
move.forward()
move.turn_right()

# Async style: for doing several things at once
import asyncio
import network
from qbot.aio import move

radio = network.Radio()

async def main():
    await asyncio.gather(move.forward(), radio.aio.send("base", "moving"))

asyncio.run(main())
```

As in real Python, `await` and `async for` are only valid inside an `async def`; programs start their event loop with `asyncio.run(main())`.

- **Blocking (default):** `submit` + `wait`. Reads like ordinary Python.
- **Async (`.aio` submodules):** returns an awaitable. The `asyncio` event loop uses `host.wait` as its idle "selector", so many calls can be waiting at once without wasting CPU.
- **Why both:** learners never have to learn `async` just to move a Qbot, and experienced users get real concurrency. Both are generated from the same function definitions, so they can't drift apart.
- **Pausing a program:** a blocked `host.wait` blocks the computer's own thread. This needs no Asyncify (confirmed in milestone 0).

**Defining a host function (Java):**

```java
@HostFunction(module = "qbot.move", cost = 1, mainThread = true)
public MoveResult forward(Context ctx) { … }
```

- `cost` feeds the per-player rate limits (§4); `mainThread` routes the call to the world-call executor.
- Python bindings (`qbot.move.forward` and `qbot.aio.move.forward`) plus `.pyi` stubs are generated from these annotations.

**Errors:** use standard Python exceptions where they fit, plus a small hierarchy of the mod's own:

| Exception | Raised when |
|---|---|
| `TimeoutError`, `PermissionError`, `ValueError` (built-in) | as in normal Python (e.g. uplink domain denied → `PermissionError`) |
| `DeviceError` (base) | anything the mod raises. Fields: `.device`, `.position`, `.hint` |
| ├─ `BlockedError` | movement or placement blocked |
| ├─ `ChunkUnavailableError` | target chunk not loaded |
| ├─ `LimitError` | rate or ownership limit hit |
| ├─ `PeripheralError` | a peripheral call failed or the peripheral is gone |
| └─ `NetworkError` | radio or uplink failure |

The `.hint` field always holds a learner-friendly fix, e.g. *"There's a block in front. Use `dig.front()` first, or check with `inspect.front()`."*

**Naming:** standard Python conventions (PEP 8): `snake_case` functions, `PascalCase` classes, short lowercase modules. **Don't name a module `turtle`**: that clashes with CPython's built-in `turtle` graphics module and would confuse learners who later use real Python. The mod's module is `qbot`.

---

## 3. Engine: MicroPython on Chicory

### 3.1 Why MicroPython

- Real Python 3 syntax (3.4 baseline plus selected later features: f-strings, `async`/`await`, `:=`).
- Small and fast to start. The hardware-style APIs (`machine.Pin`, `irq`, timers) match the embedded-circuits concept.
- Sandboxed by construction: code can reach only the host functions provided.
- Hard memory caps per computer.

### 3.2 Known limitations (accepted)

- Partial object model: advanced multiple inheritance, `__new__` and builtin subclassing may differ from CPython. User-class `__del__` is unsupported.
- Reduced standard library (`json`, `re`, `asyncio`, `collections`, … as subsets). No `threading`; concurrency is `asyncio`.
- No C extensions and no `pip`. Packages come from an admin-controlled allowlist (§12.1).
- Type hints parse but have no runtime effect.

### 3.3 Engineering tasks

- **Custom Wasm build:** the official MicroPython WebAssembly port targets JavaScript hosts. A custom build replaces those browser bindings with this mod's host imports.
- **Pausing a program while it waits:** a call that waits for something (Qbot move, peripheral read) blocks the computer's **own thread** until the main thread completes the request. Confirmed in milestone 0 (§14.5); Asyncify isn't needed.
- **Metering:** count executed instructions, or check a time budget per slice. A computer that exceeds its slice is paused; repeated abuse raises `TooLongWithoutYielding`-style errors.
- **Benchmark harness (first milestone):** boot time, memory per instance, host calls per second, and interpreter throughput on Chicory.

### 3.4 Computer tiers

| Tier | Heap (target) | Notes |
|---|---|---|
| Basic | 256 KB | Circuits, small scripts |
| Advanced | 1 MB | Networking, larger programs |
| Server | 2 MB | Dashboards, in-game web servers |

---

## 4. Concurrency and scheduling

- **Workers:** each running computer has its own thread with a large stack (§14.5 explains why not virtual threads). A fair semaphore with `scheduler.worker_cores` permits (default 2) caps how many run Python at once. A computer gives up its permit while it waits on the world, and goes to the back of the queue at the end of each time slice.
- **Time slices:** each computer gets a slice per tick; scheduling is round-robin.
- **World-call executor:** runs queued world requests on the main thread, up to `scheduler.world_calls_budget_ms` per tick. Leftovers wait for the next tick.
- **Call costs:** each host or peripheral function declares a cost. Expensive calls are rate-limited per computer and per player.
- **Batch APIs required:** each call waits at least one tick (50 ms), so APIs must return bulk data (e.g. `me.items()` returns everything in one call).
- **Events over polling:** change feeds (`watch_items()`, redstone edge interrupts, radio receive) are the main pattern taught in docs and templates.

---

## 5. Ownership, limits and the bricked state

Cost is controlled by **how many active devices each player owns**, not by chunk loading. Other mods can load chunks, so the design assumes every active device is always loaded.

### 5.1 Ownership

- Every device stores its owner's **UUID**, not their username (usernames can change). Names appear only in messages.
- Counts live in a **server-wide registry saved with the world**, not in chunks, because Qbots move and chunks unload.

### 5.2 Limits by type

Cheap devices (basic coding terminals) can have high or unlimited caps. Expensive devices (web panels, Qbots that load their own chunks) get tight caps. Each type has its own limit (see §14).

### 5.3 Exceeding the limit: the bricked state

If placing a device would exceed the owner's limit for that type, the device becomes **bricked**:

- It's inert: dark screen, tooltip `Inactive: owner limit reached (10/10)`.
- **Zero ongoing cost:** no VM, no ticking block entity, no registry scans, no network sync beyond the normal block state. A bricked block costs the same as a decorative block.
- **Files are kept** where the device has them. Being over the limit never deletes code.
- **Reactivating it:**
  - break the block and have an owner who is under the limit place it again, **or**
  - the owner right-clicks it while under the limit.
  - The right-click check runs **only on interaction**, and is rate-limited per player (e.g. one check per second). Mass-placing bricked blocks therefore creates no server load.

### 5.4 Placed by machines or Qbots

- **Any computer or Qbot placed by a machine or a Qbot is bricked**, with no exceptions and no config option.
- **Companion programmer block:** carries its owner's UUID and activates and programs inert devices that machines produced. Activation counts against the programmer owner's limit.
- **Self-replicating Qbots:** a Qbot that builds and places a Qbot passes on its **owner UUID and program**. The new Qbot still counts against the owner's limit; if that would exceed it, the new Qbot is bricked.

### 5.5 Lowering limits (grandfathering)

If an admin lowers a limit, existing active devices stay active. The owner can't activate new devices of that type until their count drops below the new limit. The config file documents this. Admins are responsible for setting reasonable numbers from the start.

### 5.6 Ranks and teams (low priority)

- **Ranks:** a base limit per player plus extra allowances by rank, via a permissions mod (e.g. LuckPerms) if present.
- **Teams:** optional pooling via team mods (e.g. FTB Teams). Members who don't use the mod can give their allowance to teammates who do.
- **Operators:** exempt by default (`limits.operators_exempt`).

---

## 6. Chunk loading and boot

### 6.1 Assumptions

- Every active device is assumed to be **always loaded**. Whether that's done by this mod or another chunk-loading mod is a server setup choice.
- Worst-case capacity planning: `players × per-type limits × cost per device`.

### 6.2 Programs live apart from chunks

A Python program lives in the server-side device registry, not inside its block entity:

- If a block's chunk unloads, the program can keep running. World calls to an unloaded chunk wait, or fail with a clear error (configurable).
- Radio messages addressed to a device in an unloaded chunk can queue until it's loaded again.

### 6.3 Staggered boot queue

All activations go through one queue: server start, player login, and chunk loads caused by any mod.

- `boot.per_tick` devices start per tick (default 4).
- **Order:** dimension (Overworld first), then **owner round-robin**, then **distance from the world origin on x and z**, then coordinates as the final tie-break. The result is fair across players and the same every time.
- Optional boot priority per device (e.g. a base's main server first).

---

## 7. Hardware: circuits and redstone

MicroPython-style hardware API:

```python
from machine import Pin, Timer, Side

door = Pin(Side.LEFT, Pin.OUT)       # strings also accepted: Pin("left", Pin.OUT)
button = Pin(Side.BACK, Pin.IN)
button.irq(lambda p: door.toggle(), trigger=Pin.IRQ_RISING)
```

- `Pin` for each side, with digital and analog (0–15) values.
- `irq` edge interrupts become events, so no polling is needed.
- `Timer`, `time.sleep`, `asyncio.sleep`.
- Bundled-cable support, if a cable mod is present (later).

### 7.1 Sides and orientation

- **Relative sides for every action:** `FRONT`, `RIGHT`, `BACK`, `LEFT`, `TOP`, `BOTTOM` (constants on `Side`; matching lowercase strings accepted).
- Every computer and Qbot has a **visible face texture** marking its front.
- Compass directions never appear in action APIs. They're available only as information through GPS (§7.2).

### 7.2 Built-in GPS

Every computer and Qbot knows where it is, **with no upgrade and no GPS build needed**. Coordinates make far more programs possible: mapping, return-to-base, dashboards showing where things are.

```python
import gps
x, y, z = gps.position()      # block coordinates
gps.heading()                 # "north" | "east" | "south" | "west", the direction FRONT faces
gps.dimension()               # e.g. "minecraft:overworld"
```

---

## 8. Qbots

> **Naming:** the mod's mobile robot is the **Qbot**: Q (the author's initial) plus "cube bot". Its Python module is `qbot`, never `turtle` (§2.3).

### 8.1 Design principles

- **No fuel or energy system.**
- **Size:** 1×1×1. It moves one block at a time and turns left or right.
- The **Qbot's program and its body are separate**. The program runs on the server; movement and actions are world calls.

### 8.2 Sensing: six sides, inventories on request

- **Senses all six touching sides:** `inspect.front()`, `inspect.top()`, … return the block or entity there (ID, block state, tags).
- **Seeing isn't looking inside.** Inspecting a chest says it's a chest, not what's in it. Reading contents is a **separate, explicit command** (`inventory.read(Side.FRONT)`). What a Qbot does when it finds a container is up to the program, which teaches deliberate code.
- **Acts on all six sides:** dig, place, attack, use, insert and extract work on any side, not just the front.

```python
from qbot import inspect, inventory, Side

if inspect.front().id == "minecraft:chest":
    for slot in inventory.read(Side.FRONT):
        print(slot.item, slot.count)
```

### 8.3 Inventory

- Works **like an Input block** (§11.0): chest-like slots with **optional per-slot filters**, configurable in the GUI or code, up to one stack per slot.
- The **same slot count as an Input block of the same tier**. Inventory upgrades are tiered, like Input blocks (§11.0).
- **Access face for insert and extract**, like Output blocks and buses: the Qbot doesn't need to be on the face it inserts through.

```python
from logistics import Face
# Qbot sits beside a furnace, but fills the top (ingredients) and a side (fuel)
inventory.insert(Side.FRONT, "minecraft:raw_iron", 16, face=Face.TOP)
inventory.insert(Side.FRONT, "minecraft:coal", 2, face=Face.LEFT)
```

### 8.4 Mining and tools

- **Built-in mining level by Qbot tier:** basic = **iron** level, advanced = **diamond** level. No tool item is needed for that.
- **Optional real tools:** for netherite-level blocks, enchantments (Fortune, Silk Touch) or modded tools, the program equips a tool from the Qbot's inventory and uses it:

```python
from qbot import tools, Side
pick = tools.equip("minecraft:netherite_pickaxe")   # from inventory
pick.use(Side.FRONT)                                 # mine with this tool's level and enchantments
```

- Real tools **wear down** like a player's would. When one breaks, it's gone and the program gets a clear `ToolBrokenError`.

### 8.5 Self chunk loading

- A Qbot loads the chunk it's in. When it's **within one block of a chunk border**, it also loads the neighbouring chunk, so the touching sides it senses are always loaded.
- Qbots in the same chunk share one load.
- The cost is capped through `limits.qbots_per_player`, not a chunk budget.

### 8.6 Learner-safe behaviour

- **No silent failures.** Every blocked action raises a clear, catchable error that names the cause, the Qbot's position and the fix.
- **Parking:** when the owner logs off or the server stops, a Qbot finishes its current action, saves its state, and pauses. It resumes when the owner returns (configurable). Stopping only between actions prevents item duplication.
- **Rate limit on new terrain:** moving into chunks that have never been generated is rate-limited, because world generation is expensive.

### 8.7 Qbot-related blocks

- **Programmer block:** activates and programs inert Qbots and computers (§5.4). It loads programs from **storage media** (§17.3) or a **drive bay** (§17.6), and activates the device under the programmer owner's UUID.
- Upgrades (inventory tiers, antennas, tool-holder): to be defined.

---

## 9. Radio, networking and the uplink

### 9.1 Antennas

**Range tiers.** Range is a **cylinder**: a radius measured on x and z, with **unlimited height**. Weather has no effect.

| Tier | Radius | Across dimensions |
|---|---|---|
| Short | 24 blocks | No |
| Long | 100 blocks | No |
| Infinite | Unlimited | **Yes** (the only tier that works across dimensions) |

**Link rule (proposed, to confirm):**
- **Both ends need an antenna.**
- Two antennas in the **same dimension** connect if their horizontal distance is within the **larger** of their two ranges. A big base tower can then serve small antennas on Qbots and outposts.
- **Across dimensions**, **both ends must be Infinite tier**.

**Frequencies and access.**
- Every antenna is tuned to a **frequency code** (e.g. `"4471"` or a name such as `"mine-ops"`). Only antennas on the same frequency hear each other.
- **Access mode** per frequency:
  - `private`: only the owner's devices (by UUID)
  - `team`: the owner's team (via a team mod, §5.6)
  - `public`: anyone on that frequency
- The first antenna to claim a `private` or `team` frequency owns it; others can't join it.

**Where antennas go.**
- Placed as blocks, or fitted to computers and Qbots as upgrades.
- An antenna block **attached to a logistics cable network bridges it wirelessly** (§11.0).

```python
import asyncio
import network

radio = network.Radio()            # this device's antenna
radio.tune("mine-ops")
radio.send("base", {"ore": 128})

async def listen():
    async for msg in radio.aio.receive():
        print(msg.sender, msg.data)

asyncio.run(listen())
```

### 9.2 Wireless logistics bridging

- An antenna attached to a logistics network joins that network to **every other network on the same frequency** that's within range.
- The bridged networks act as **one network with a single controller**. The controller can sit on either side, so an antenna at the main base can run a factory at a remote outpost.
- **Only one controller per bridged network.** If two networks with controllers are bridged, the second controller goes inactive and shows a clear warning.
- Both ends are assumed to be chunk-loaded (§6.1). If one isn't, transfers to it fail with `ChunkUnavailableError`.
- Wireless transfers share the network's throughput limits (§15); an optional extra wireless cap per bridge is possible.

### 9.3 In-game network: addresses, ports and names

#### Addressing: the antenna is the router

The in-game network is modelled on a home internet connection, so learners meet real networking ideas (IP addresses, private networks, ports, a router) in a small, safe form. All addresses are **IPv4**.

- **Every cable network is a private network, with or without an antenna.** Each addressable block on it (computers, Inputs, Outputs, Buses, and later addressable items such as a hard drive in a drive bay) has three ways to be found:

  | | Example | Who sets it | When it changes |
  |---|---|---|---|
  | **Name** | `furnace_bus` | The player: rename the item in an anvil before placing it, or `device.rename(...)` in code | Only when the player changes it |
  | **Internal IP address** | `192.168.1.4` | The network, automatically, the moment the block is cabled in | Kept across restarts. Changes only if two networks are joined and both had a device on that address: the device with the lower hardware address keeps it |
  | **Hardware (MAC) address** | `02:1A:2B:3C:4D:5E` | Fixed when the block is placed | Never, while the block stays placed. Breaking and re-placing a block gives a new one, like its device ID (§17.1) |

  Programs normally use the name. The IP address is what the antenna forwards to. The hardware address is what a program holds on to internally, so a device object keeps working if its name or IP changes.
- **No antenna is needed inside a network.** A computer talks to the blocks it is cabled to directly (§11.0). This is the real-world split: a home network without an internet connection still lets its own devices reach each other.
- **The antenna is the router/modem.** Every antenna has one **external IP address** on the server-wide radio network, the game's "internet". Attaching one to a cable network gives each device on it a **port** on the antenna, used from outside, and becomes that network's gateway at `192.168.1.1`. Devices keep the internal addresses they already had.
- **Reaching a device:**

| From | Address to use | Example |
|---|---|---|
| The same network | Its internal IP | `192.168.1.5` |
| Anywhere else | The antenna's external IP and the device's port | `100.64.0.12:1003` |

- The antenna forwards each port to its device. This is what lets a remote controller run a separate network: it addresses the device as `external IP:port` without being cabled to it.
- **Addresses are stable.** They are saved in the server registry (§5.1), don't change on restart, and are freed when the device or antenna is removed. The owner can see them in each device's GUI.
- **An antenna fitted as an upgrade** to a computer or Qbot makes that device a network of one: it has the external IP itself.
- **Addresses say where, not whether.** Frequency, access mode and range (§9.1) still decide whether two antennas can talk at all.

```python
import network

radio = network.Radio()
print(radio.external_ip)                 # "100.64.0.7"
print(network.ifconfig())                # this device's internal IP, port and gateway

radio.send("192.168.1.5", "hello")                 # a device on this network
radio.send("100.64.0.12:1003", {"ore": 128})       # a device behind another antenna
```

**Proposed numbering (to confirm):**

| What | Proposal | Why |
|---|---|---|
| External IPs | From `100.64.0.0/10`, one per antenna | `requests` serves both in-game and real addresses (§9.4). This range is never used on the public internet, so an address can only mean one or the other. |
| Internal IPs | `192.168.1.0/24` in every network; the antenna is `192.168.1.1`, devices count up from `.2` | Looks like a home network, and shows that private addresses repeat from one network to the next. 253 devices fits the network size cap (§15). |
| Ports | `1000` + the last number of the device's internal IP (`192.168.1.5` is port `1005`) | Short, easy to read, and a learner can work it out. (First proposal: given out in order from `1000`.) |

**Where this is simpler than real networking** (the in-game docs should say so plainly):

- On a real network a port identifies a program on a machine, and a router forwards a port only when someone sets that up. Here every device gets exactly one forwarded port automatically.
- There is no address lease, subnet mask or routing table to configure.
- On a real home network the router hands out the internal addresses, so with no router devices fall back to self-assigned ones in a different range (`169.254.x.x`). Here the cable network itself hands out `192.168.1.x` addresses, so adding an antenna later changes nothing a program already uses.

**Built so far (2026-11-10), not yet played:**

- Names, internal IP addresses and hardware addresses on cable networks; `network.ifconfig()`, `network.hostname()`; the shell's `devices` command.
- **Antenna blocks** in the three range tiers (§9.1). One antenna per cable network is its router at `192.168.1.1`; a second antenna on the same network is an ordinary device for now (open question 3a).
- **External addresses**, given out in order from `100.64.0.1`, saved with the world, and returned to the pool when the antenna is broken.
- **Ports:** a device's port is 1000 plus the last number of its internal IP, so `192.168.1.5` is port `1005`. Nothing extra to store, and a learner can work it out.
- **Messages between computers:**

```python
import network

network.send("192.168.1.3", {"ore": 128})     # same cable network: no antenna needed
network.send("100.64.0.12:1003", "hello")     # behind another antenna: needs one here too
msg = network.receive()                       # waits; receive(5) gives up after 5 seconds and returns None
print(msg.sender, msg.data)
network.send(msg.sender, "thanks")            # msg.sender is the address to answer to

radio = network.Radio()                       # the antenna cabled to this computer
print(radio.external_ip, radio.frequency, radio.tier, radio.range)
radio.tune("mine-ops")
```

  - `send` returns `True` if delivered, `False` if the address is right but that computer is switched off or its inbox (64 messages) is full. A wrong address, an antenna out of range or on another frequency raises `NetworkError` saying which.
  - Data is anything JSON can hold (text, numbers, lists, dicts), up to 4,096 characters.
  - Right-clicking an antenna shows its addresses, frequency and reach.

**Not built yet:** access modes and frequency ownership (§9.1; every frequency is public for now), `async` receiving, reaching item blocks behind another antenna, antennas as upgrades fitted to computers and Qbots, wireless bridging (§9.2), in-game HTTP and names.

#### In-game HTTP and names

- **In-game HTTP:** `requests` works between computers, using the addresses above (`requests.get("http://100.64.0.12:1003/items")`). The ROM includes a micro web framework so computers can serve APIs (e.g. a REST endpoint for a storage system).
- Raw sockets are allowed **only inside the game**.
- **Pseudo-DNS** (names like `storage.mc`) maps a name to an `external IP:port`, so programs don't need to hard-code numbers:
  - **First come, first served.** A registered name belongs to its owner's UUID and **can't be taken by anyone else**.
  - **Names never expire.** Only a server admin can free one (`/pc dns release <name>`).
  - Suggested anti-squatting cap: `dns.names_per_player` (§15).

### 9.4 Uplink (real internet)

- An expensive, craftable block: the only route to the real internet. It can be disabled or restricted by the server.
- HTTPS and WebSockets only (no raw TCP at first).
- One `requests` API covers both in-game and real destinations; the router picks the route.

### 9.5 External access: built-in gateway

The gateway runs **inside the mod**, on the Minecraft server, or on the built-in server in single-player. No separate relay program is needed.

- **Off by default.** The server admin turns it on (`gateway.enabled`).
- **What it serves:**
  - web panels (§10.3), so the same dashboard works in a real browser on a phone or PC
  - in-game HTTP servers that players write (§9.3)
  - live updates over WebSocket
- **URL scheme:** `/c/<device-id>/…`, with a **token per device** that the owner can create and revoke in game.
- **Port:**
  - a **separate port** (simplest), **or**
  - **sharing the game port**: the server checks the first bytes of each new connection and passes HTTP traffic to the gateway. This helps on hosting providers that only open the game port.
- **Single-player:** listens on **localhost only** by default, so only the player's own PC can reach it.
- **HTTPS:** built-in HTTP is plain text, so tokens could be read by someone on the network. Recommend putting it behind a reverse proxy that handles TLS (Caddy, nginx), or support a certificate setting in the config.
- Every request counts against the device's world-call and bandwidth limits, like in-game traffic.
- **External relay:** optional, for setups where no port can be opened. Not part of the core mod.

---

## 10. Displays

### 10.1 Tiers

| Device | Content | Server cost | Learning step |
|---|---|---|---|
| Terminal | The computer's own screen, a character grid | Cheap | `print`, `input` |
| Text monitor | Character grid, colors | Cheap | Formatting output |
| **Control surface** | Prebuilt widgets: buttons, toggles, sliders, gauges, lamps, labels, simple graphs | Low | Events and callbacks |
| **Web panel** | Broad HTML/CSS, `<canvas>`, optional JS | Higher, late game | Real web development |

**No separate graphics monitor.** Pixel drawing lives in the web panel's `<canvas>`, using the real browser drawing API, which is more transferable than a mod-specific one. The control surface covers the most common display use, a control panel, more cheaply than either.

### 10.2 Control surfaces

A monitor with **ready-made widgets**, laid out on a grid and driven from Python. Each widget is drawn by the player's game (no HTML), and only **widget values** are sent from the server, so it's much lighter than a web panel.

```python
import asyncio
from display import ControlSurface
from machine import Side

cs = ControlSurface(Side.TOP, cols=6, rows=4)

pump   = cs.toggle("Pump", at=(0, 0))
speed  = cs.slider("Speed", 0, 100, at=(1, 0), span=(3, 1))
power  = cs.gauge("Power", 0, 1_000_000, unit="FE", at=(0, 1), span=(2, 2))
alarm  = cs.lamp("Overheat", color="red", at=(2, 1))
scram  = cs.button("SCRAM", style="danger", at=(5, 3))

@pump.on_change
def _(on):
    pump_valve.set(on)

@scram.on_press
def _():
    reactor_feed.stop()

async def main():
    while True:
        power.value = energy_bus.stored()
        alarm.on = temp_sensor.read() > 900
        await asyncio.sleep(0.5)

asyncio.run(main())
```

- **Widgets (initial set):** button, toggle, slider, dial, number input, gauge (bar or round), lamp, label, value readout, line graph (recent values), item slot display.
- **Bindings:** a widget can be connected to a callback, or **bound directly** to a logistics or redstone action with no extra code, e.g. `cs.button("Smelt", action=output.push_action("minecraft:raw_iron", 64))`.
- **Themes:** a few built-in looks (industrial, clean, retro terminal).
- **Upgrade path:** later, a control surface layout can be exported to HTML as a starting point for a web panel.

### 10.3 Web panels

**Principle:** Python owns the data and the actions. The panel only displays and handles interaction on screen.

**Goal: cover the broadest set of everyday HTML and CSS.** The panel is a learning tool, so things taught in a typical web course should **just work**, and experienced developers shouldn't keep hitting walls.

**Target coverage (staged):**

| Stage | HTML | CSS | Other |
|---|---|---|---|
| **v1** | Common text and structure elements (`div`, `span`, `p`, `h1–h6`, lists, `a`, `img`, `table`), forms (`button`, `input`, `select`, `textarea`, `label`) | Box model, colours, backgrounds, borders and radius, fonts and text, `display` (block/inline/none), **flexbox**, positioning (relative/absolute/fixed), `overflow`, units (`px`, `%`, `em`, `rem`, `vw/vh` = panel size), CSS variables, common selectors and pseudo-classes (`:hover`, `:active`, `:focus`, `:nth-child`) | `item:<id>` images |
| **v2** | `<canvas>` (2D), inline `<svg>` basics, `progress`, `meter` | **Grid**, transitions, `@keyframes` animations, transforms, gradients, shadows, `@media` by panel size | JS sandbox (DOM, events, `requestAnimationFrame`) |
| **v3** | Remaining common elements | Filters, `position: sticky`, more selectors | Web fonts bundled with the panel |

**Engine approach:** writing a broad HTML/CSS engine from scratch is very large. Evaluate first whether an **existing lightweight engine** can be compiled to WebAssembly and run on Chicory, like MicroPython and QuickJS: for example litehtml (C++), with the mod drawing its output. Check each candidate's flexbox and grid support before committing; write custom only for what's missing.

**Honesty with learners:**
- A public **supported-features page** (like "Can I use") lists what works and what doesn't.
- Unsupported CSS is **ignored and reported** in the panel's dev console, never silently broken.
- The gateway (§9.5) serves the same page to a real browser, so differences are easy to spot. This also works as a compatibility test.

**Rules:**
- **No real browser.**
- **Server-authoritative:** Python sends HTML and CSS plus state updates. Nearby players' games render them, and clicks come back to Python as events.
- **No network access from the page.** Panels can't fetch anything. Data and images come through the computer, and `item:<id>` URLs give access to the game's item icons.
- **Shared state:** `panel.state` in Python mirrors `panel.onState()` in JS. `panel.send()` and `@panel.on()` carry messages between them.
- **JS sandbox (v2):** QuickJS compiled to Wasm via Chicory, running on each player's game. No network, a memory and time budget, and messages to Python only.
- **Projects:** `main.py`, `index.html`, `style.css`, `app.js`.

```python
import asyncio
from logistics import network
from display import Panel
from machine import Side

net = network()
storage = net.bus("main_storage")
panel = Panel(Side.LEFT, page="/apps/inventory/index.html")

@panel.on("withdraw")
async def withdraw(evt):
    await storage.aio.extract(evt.data["item"], evt.data["count"], dest=net.input("desk"))

async def main():
    async for change in storage.aio.watch():
        panel.state["items"] = storage.contents()

asyncio.run(main())
```

### 10.4 Sync protocol

- Control surfaces: widget values only. Web panels: delta updates (diffs only), compressed.
- Update rate cap per panel (e.g. 5–10 per second) and a bandwidth cap per panel.
- Sent only to players within the panel render distance.

---

## 11. Peripherals and mod integration

> **Priority:** a dedicated peripheral API and integrations with other mods are the **lowest priority** in the project. A well-made mod shouldn't need them: item, fluid and energy logistics through the loader's standard APIs cover most use cases. First come the mod's own logistics blocks (§11.0), which work with any inventory through the loader's standard item-handling API, so they cover most mods' chests and machines without dedicated adapters.

### 11.0 Logistics network (first-party, earlier)

Blocks joined by the mod's own **cables** form a **network**. A computer on the network runs it: any computer cabled in can see and command every item block on the same network, and any block can use items from any Input on it.

```
 [Computer]══cable══[Input]══cable══[Output]→ furnace
                 ║
                 ╚══cable══[Bus]↔ chest / machine
```

> **Status (2026-11-10): the item blocks are built, not yet played.** Cables, network discovery, addresses (§9.3), the Input, Output and Bus blocks and the `logistics` Python module exist and are covered by in-game tests. The Input's screen has a row of filters above its row of slots: click a filter slot holding an item to set it, or with an empty hand to close it (the filter row is drawn as darker sockets). Not built yet: a screen for setting a bus's or output's face by hand, tier upgrades, throughput limits, change events (`watch()`), wireless bridging, fluids and energy.
>
> **Changed 2026-11-10 (after the first play test), decided by the project owner:**
> - The filtered storage block is now the **Interface** (`net.interface()`), leaving room for fluid and energy interfaces later.
> - **Input** is a new directional device: it takes items out of the container it points at, into Interfaces. Later it will be able to pull on its own.
> - **Input, Output and Bus are plates on cables**, not blocks: they share the cable's block, in the way players know from Applied Energistics and Refined Storage. Click the container with the plate in hand; it goes on the cable in front of that face (a cable from the inventory is placed if there is none). Sneak and right-click with an empty hand takes it off.
> - A cable carries **up to six plates, one per side**, each a device of its own with its own address. This is for tight spaces: one cable between several machines serves them all. Click a plate (not the cable) to read it or take it off.
> - How it is built: each side of a cable is a block-state value (none, cable, input, output, bus), which gives the cable 15,625 block states and about two seconds of extra start-up. It needs no model code. If the state count becomes a problem, the alternative is a model built in code.
> - Breaking: aim at a plate and break, and only that plate comes off. Aim at the cable itself and the cable breaks, dropping every plate. (The mining time is the cable's either way.)
> - Text below that says "Input block" for the filtered storage means the Interface.
>
> **Fluid and energy (built 2026-11-10):**
> - **Fluid Interface**: a full block with four tanks of four buckets (4,000 mB) each. A tank takes any fluid while empty, then only more of the same. A bucket fills or empties it by hand.
> - **Energy Interface**: a full block holding 2,000,000 FE (the unit mods share, also called RF).
> - Both are open to other mods' pipes and cables through the loader's standard fluid and energy APIs, and the plates reach other mods' tanks and machines the same way.
> - **Fluid and Energy versions of Input, Output and Bus**: the same plates and shapes, with a coloured collar and face ring: white for items, blue-grey for fluid, red for energy.
> - In Python the device kinds are `fluid_interface`, `fluid_input`, `fluid_output`, `fluid_bus` and the same four after `energy_`, with matching `net.fluid_bus()`-style lookups. Fluid amounts are millibuckets (1,000 is a bucket); energy amounts are FE. One call moves at most 16 buckets or 2,000,000 FE.
> - **Aiming without a name (built 2026-11-10).** `extract`, `insert`, `count` and `amount` take an item or fluid name, or none at all, and can be narrowed by `face=` and `slot=` (a slot or tank number, from 0, as `contents()` lists them). A `Face` or a `Slot(n)` can stand where the name would go: `bus.extract(Face.BOTTOM)` takes whatever a furnace has smelted, `bus.extract(Slot(0))` whatever is in the first slot. `dest_slot` and `source_slot` pick the Interface's slot or tank. With no name the default amount is everything there; with a name it is 1 item or one bucket.
> - **Filters in place of names (built 2026-11-10).** Wherever a program names an item or a fluid it can write a filter instead, in the words of the JEI mod's search box, which many players know: `@create` (everything from a mod, by ID or name; part of either is enough), `#minecraft:logs` or `$minecraft:logs` (a tag; `#logs` tries `minecraft:` then `c:`), `*_ore` (wildcards on the name), several words that must all hold (`@minecraft *_ore`), `-word` for "not", and `a | b` for alternatives. A bare word such as `coal` is still that one item. Filters work in `extract`, `insert`, `count`, `amount` and in an Interface's slot filters (`set_slot_filter(0, "#minecraft:logs")`). Not taken from JEI: searching by the name shown in tooltips, by tooltip text or by colour, because the server doesn't have players' translations.
> - Not built: fluid filters, flow rates or limits per tick, fluids that carry extra data (they are handled by plain kind only), and any screen for the two stores (right-click shows their contents as a line of text).
>
> **Changed from the first design: there is no controller block.** The computer plugs straight into the cable. Reasons: one less block to craft and explain before a learner's first program moves an item; the network needs no stored state of its own (it is worked out from the cables, and each block remembers its own address); and limits can count the item blocks themselves. A controller can come back later if something needs a single owner per network, such as wireless bridging (§9.2), which still assumes one.

**How blocks join a network**

- A cable joins every network block it touches. Network blocks that touch each other are joined too, so neighbours need no cable between them.
- An Output or Bus does **not** join through the side that points at its target. Two networks can therefore share a chest without becoming one network.
- A network is found by walking the cables from the computer. The result is reused for up to a second, or until a cable or network block is placed or removed anywhere. Only loaded chunks are walked.
- Cap: 4,096 cables and blocks, and 253 addressable devices, per network (to become config, §15). A network over the cap refuses every command with a message that says why.

#### Blocks

| Block | Role |
|---|---|
| **Cable** | Connects network blocks into one network. Cheap and passive. |
| **Interface** (a full block; called "Input block" before 2026-11-10) | Chest-like storage. **Every slot starts closed.** Code sets which item each slot accepts (a GUI for this comes later; right-clicking today opens a plain nine-slot view that enforces the filters and lets the player take items out); each slot holds **up to one stack**. Several slots can accept the **same item**, for bulk, or **different items**, for recipes. Other mods' pipes, hoppers and machines can insert through the loader's standard item API, but only into slots open for that item. |
| **Input** (plate on a cable) | **Reads** the container it points at and **extracts** items from it into an Interface, as directed by code. |
| **Output** (plate on a cable) | Takes items **from any Interface on the network** and inserts them into the container it points at, as directed by code. |
| **Bus** (plate on a cable) | An Input and an Output in one, for any container: chests, furnaces, other mods' machines. **Reads** its contents, and can **extract** items into an Interface or **insert** items from an Interface, over the network. |

#### Why chest-like Input slots

- **Recipe automation:** set up slots as an exact recipe (e.g. 3 iron, 2 sticks), so only the right ingredients come in.
- **Bulk buffering:** set eight slots to accept iron, for eight stacks.
- **Works with other mods:** the Input looks like an ordinary inventory to other mods, so their pipes and machines work with it out of the box.

#### Insert direction (Output and Bus)

Many machines treat items differently depending on **which face they enter from**. A vanilla furnace takes ingredients from the top, fuel from the sides, and gives output from the bottom. So each Output and Bus has two settings:

1. **Attachment side:** the block **points at whatever it was placed against**. Click a furnace with a Bus in hand and the Bus attaches to that furnace. (First design: a setting on the block; placement is simpler.)
2. **Access face:** which face of the target the transfer acts as if it came through, **relative to the Bus looking at the target**:
   `TOP`, `BOTTOM`, `NEAR` (the face touching the Bus, and the default), `FAR` (the opposite face), `LEFT`, `RIGHT`.
   When the Bus is above or below its target there is no natural "top", so the faces are named as on a map: `TOP` is north, `LEFT` is west.

So a Bus attached to a furnace's side can still insert into the furnace's top (input) or side (fuel) slots, without moving the Bus. Later, in the GUI, the player picks the face by clicking a small 3D preview of the target block.

```python
from logistics import network, Face

net = network()                          # the cable network this computer is on
smelter = net.bus("furnace_bus")         # by name, IP address or hardware address
inbox = net.interface()                  # no argument: the network's only Interface

# open Input slots for the items they should accept
inbox.set_slot_filter(0, "minecraft:raw_iron")
inbox.set_slot_filter(1, "coal")                  # "minecraft:" is assumed
inbox.set_slot_filter(2, "minecraft:iron_ingot")

smelter.insert("minecraft:raw_iron", 16, source=inbox, face=Face.TOP)   # ingredient
smelter.insert("minecraft:coal", 2, face=Face.LEFT)                     # fuel, from any Input
moved = smelter.extract("minecraft:iron_ingot", 64, face=Face.BOTTOM)   # up to 64; returns how many

print(smelter.contents())                # every slot of the furnace: slot, item, count
print(net.devices())                     # [<computer 192.168.1.2 02:..>, <input ...>, <bus "furnace_bus" ...>]
```

| Call | What it does |
|---|---|
| `net.devices()`, `net.interfaces()`, `net.inputs()`, `net.outputs()`, `net.buses()` | Lists of devices, in address order |
| `net.device(key)`, `net.interface(key)`, `net.input(key)`, `net.output(key)`, `net.bus(key)` | One device by name, IP or hardware address. With no key: the only one of its kind, or an error saying how many there are |
| `net.me` | This computer |
| `device.name`, `.ip`, `.mac`, `.kind`, `device.rename(name)`, `device.refresh()` | Identity. The values are as they were when the device was looked up |
| `input.contents()`, `input.count(item)`, `input.filters()`, `input.set_slot_filter(slot, item or None)` | The Input's own slots |
| `port.insert(item, count=1, source=None, face=None)` | Output and Bus: Inputs → target. Returns how many moved |
| `bus.extract(item, count=1, dest=None, face=None)` | Bus only: target → Inputs |
| `bus.contents(face=None)`, `bus.count(item, face=None)` | Bus only. With no face it reads the whole container; with a face, only the slots that face gives access to |
| `port.set_face(face)`, `port.face`, `port.target` | The default face, and the block it points at |

Mistakes raise `logistics.NetworkError` with a sentence saying what is wrong, for example `No bus on this network has the name or address 'nope'.`

#### Rules and performance

- **Transfers run on the main thread** (§2.2). A program's call is answered on the next game tick, so one computer makes at most about 20 calls a second.
- **A transfer moves up to the count asked for and returns how many moved.** Each step is tried out before it is carried out, so a full or unwilling destination can't duplicate or delete items. (First design said "all-or-nothing"; that made `extract(..., 64)` useless on a furnace holding 5 ingots.)
- Items are matched by type. Stacks of one item that differ in other ways (damage, enchantments) all move, and keep their data.
- **Throughput limits:** one call moves at most 576 items (nine stacks). Per-network transfers per tick are not limited yet (§15).
- **Events:** buses can provide change events (`async for change in bus.watch()`), so scripts don't have to poll. Not built yet.

#### Tiered upgrades

Input blocks and Qbot inventories get bigger through **tier upgrades applied in place**, like Mekanism's upgrade system. No rebuilding needed, and filters and contents are kept.

| Tier | Slots (target) |
|---|---|
| Basic | 9 |
| Advanced | 18 |
| Elite | 27 |
| Ultimate | 54 |

Tier names and slot counts are placeholders, to be balanced later.

#### Fluids and energy

The same model as items, on the same cable network:

| Items | Fluids | Energy |
|---|---|---|
| Input block (filtered slots) | **Tank input**: filtered tanks, one fluid type per tank, capacity by tier | **Energy buffer**: stores energy, capacity by tier |
| Output block | **Fluid output** | **Energy output** |
| Bus (read / insert / extract) | **Fluid bus** | **Energy bus**: reads, charges and drains machines |

- Energy uses the loader's standard energy API (Forge Energy / NeoForge energy capability), so it works with most tech mods.
- Qbots still need **no** energy; energy logistics are for running other mods' machines.
- Access faces (`TOP`, `NEAR`, …) work the same for fluids and energy.

Open: whether one Output can feed several targets.

### 11.1 Public peripheral API (separate small jar)

```java
@PyFunction public long getEnergy() { … }
```

- Python bindings and `.pyi` stubs generated automatically.
- Each function declares a cost (§4) and whether it must run on the main thread.
- Change-feed support for event-driven APIs.

### 11.2 First-party adapters

Other mods won't target a new peripheral API at first, so the mod ships its own integrations: **AE2, Create, Mekanism, Thermal** (initial list to be confirmed).

- Each adapter is its own module, enabled only if the target mod is present.
- These will break when the target mods update; this is a permanent maintenance cost. Keep adapters thin and covered by tests.

---

## 12. Developer experience

- **Errors:** line, cause and a hint. Messages written for learners first.
- **In-game docs** plus starter templates ("blank dashboard", "door controller", "control surface", "storage terminal", "mining Qbot").
- **VS Code extension:** syncs files with an in-game computer and ships `.pyi` stubs for autocomplete.
- **In-game package manager** (`pkg install <name>`), MicroPython `mip`-style. The **server admin controls what's available** (§12.1).
- **Familiar APIs:** stay close to `requests`, `asyncio`, DOM and `fetch` conventions.
- **Learning path:** redstone with Python → text monitors → control surfaces → Qbots → radio → logistics → uplink → web panels.

### 12.1 Package allowlist (admin-controlled)

Whoever controls the server config controls the packages: the server admin, or the player themselves in single-player. The allowlist uses a **`requirements.txt`-style file**:

```text
# config/qbotics/packages.txt
# One package per line. Players can only install what's listed here.
# Sources: the mod's bundled set, micropython-lib, or a Git repo (if [git] is enabled).

logging==0.6
datetime
mpy-chart==1.2.0
github:someuser/qbot-pathfinding@v2.1   --hash=sha256:3f1a…
```

- **Version pins** (`==`) are optional; no pin means the latest allowed version.
- **Optional `--hash`:** the server refuses a download that doesn't match, which protects against a changed or compromised source.
- The **server downloads and caches** packages, subject to uplink and Git rules (§9.4, §17.5). Players' computers install from that cache, so they never download anything themselves.
- `pkg list` shows what's available; `pkg install` gives a clear error for anything not on the list.
- A small **bundled default set** works with no config, so single-player learners get useful libraries out of the box.

### 12.2 The computer's screen: a code editor

Right-clicking a computer opens an editor laid out like the code editors developers already use, so there is little need to program anywhere else and what a learner picks up carries over. It fills the whole game window and draws at the window's real resolution (about one screen pixel per font pixel for every 500 pixels of window height), not Minecraft's enlarged GUI scale. A 1920×1080 window shows roughly 160 columns by 50 rows of text. Ctrl+Plus and Ctrl+Minus change the size.

| Area | What it does |
|---|---|
| **Explorer** (left) | The drive's folders and files as a tree. Click a file to open it. Right-click, or use **+ File** and **+ Folder**, to create, rename and delete. |
| **Editor** (middle) | Open files in tabs. Line numbers, Python and JSON colouring, selection by keyboard and mouse, copy/cut/paste, undo/redo, automatic indentation, Tab/Shift+Tab on a selection, Ctrl+/ to comment. An unsaved file shows a marker on its tab. |
| **Terminal** (below the editor) | The computer itself: a small shell (below). **Run** (or F5) saves the open Python file and runs it here. |
| **Python Scratchpad** (right, toggled) | A box for a few lines of Python with a **Run** button, and their output beneath. It runs on the computer and shares variables with the terminal's Python, so it doubles as a quick-action pad. Its output doesn't touch the terminal. |
| **Status bar** | Cursor position, language, saved or unsaved, and messages such as a JSON mistake with its line. |

- **JSON:** mistakes are found as you type and explained in plain words ("Remove the comma before ]"); **Format JSON** re-indents the file.
- **Nothing is lost by closing the screen:** tabs, unsaved edits and the scratchpad stay as they were until the game is closed.
- **Scrolling:** the mouse wheel and draggable scroll bars in the editor (Shift+wheel for long lines; Ctrl+Up/Down scrolls without moving the cursor). The terminal keeps 500 lines of scroll-back (wheel, Page Up/Down; typing returns to the prompt), and its height changes by dragging the line above it.
- **Find and replace:** Ctrl+F and Ctrl+H open a bar at the top right of the file. Matches are highlighted and counted as you type; Enter and Shift+Enter (or F3) step through them; **Aa** matches capitals exactly; Replace changes one and All changes every match as a single undo step.
- **Autocomplete (Python):** suggestions appear after two letters or a dot, or on Ctrl+Space. They come from the names in the file, Python's own words, and the members of modules the file imports (`time.`, `os.`, `math.`, ...). Tab accepts; Enter accepts only after the arrow keys were used to pick one, so Enter at the end of a line stays a new line. It works from the text and doesn't understand types.
- **`input()`** works in the terminal: the program waits for a typed line. Ctrl+C gets a waiting program out; in the scratchpad, where there is nowhere to ask, it is an error rather than a hang.
- **Limits for now:** files up to 30 KB (roughly 800–1,000 lines) can be opened and saved from the editor; larger ones still work from Python. A Basic computer's 256 KB of memory couldn't comfortably load a bigger program in any case. One terminal per computer.

**The shell.** The terminal starts in a shell, as a terminal in a code editor does:

| Command | Does |
|---|---|
| `ls`, `cd`, `pwd`, `cat`, `mkdir`, `rm [-r]`, `mv`, `cp` | File handling |
| `run file.py` (or `python file.py`) | Runs a program |
| `python` | The Python prompt; `exit()` returns to the shell |
| `edit file` | Opens a file in the editor |
| `devices` (or `ifconfig`) | Lists the blocks cabled to this computer: IP address, hardware address, kind and name (§9.3) |
| `devices 100.64.0.12` | Lists the blocks behind another antenna, with the `address:port` to send to. Needs an antenna here, in range and on the same frequency. `network.scan(address)` gives the same list to a program |
| `clear`, `help` | |

Text in the terminal can be selected by dragging (double-click selects a word, such as an address). Ctrl+C copies the selection; with nothing selected it stops the program as before. Ctrl+V pastes, and a right-click menu offers Copy, Copy All, Paste and Select All.

**Network Probe.** A hand tool. Right-click any device (a computer, an interface, an antenna, or one plate on a cable) and a pop-up lists its name, a ready line of Python (`net.bus("smelter")`), its internal IP, its hardware address and, if the network has an antenna, its external address and port. Clicking a line copies it for pasting into the editor. A box at the top names the device, and the Python line then uses the name. While a probe in this mode is held, right-clicking a device reads it; the device's own screen does not open.

The probe has two modes, changed by sneaking and scrolling the mouse wheel with it in hand; its name shows the mode. **Labels** is the mode just described. In **Slots** mode, blocks open as usual, and every container screen (a chest, a furnace, the Interface, another mod's machine) shows a number on each of its slots, counted from 0. Those are the numbers a program gives as `Slot(n)` or `slot=`. A furnace shows 0 on its ingredient, 1 on its fuel and 2 on its product.

**Hover hints.** Resting the mouse on a function name in a Python file shows how it is called and what it does. The text comes from the Python source itself: the docstrings of the mod's own modules (`logistics`, `network`, copied into the jar by `engine/build.sh`) and of functions in the file being edited. Only Python's built-ins are described by hand (`qbotics.ide.Docs`). It does not understand types: it guesses a variable's class from a line such as `inbox = net.input()`, and otherwise shows every class that has a method by that name.

Ctrl+C stops a running program.

**How it is built and checked.** The editor's behaviour and appearance live in one class that doesn't depend on Minecraft's renderer. The game draws it through a thin adapter; a development tool (`gradlew :common:idePreview`) draws the same thing to image files using Minecraft's font, which is how the layout was checked on a machine with no display. The text-editing, colouring and JSON logic are covered by the engine harness, and the shell and scratchpad by in-game tests.

---

## 13. Security

- **Sandbox:** Wasm guests can reach only declared host functions. Every host function is reviewed as an attack surface.
- **Uplink protection against SSRF** (tricking the server into requests it shouldn't make):
  - block private, loopback and link-local addresses, plus cloud metadata addresses (e.g. `169.254.169.254`)
  - validate the IP **after** the DNS lookup, to defeat DNS rebinding
  - domain allowlist and denylist
- **Limits:** response size, timeouts, concurrent requests, and rate limits per computer and per player.
- **Logging:** outbound requests logged by owner UUID.
- **Panels:** no network access, which protects players' IP addresses. The JS sandbox has no world access.
- **Gateway:** off by default, with a token per computer.

---

## 14. Performance budgets

> Figures in §14.2 are **estimates**. Server-side engine figures have since been measured: see §14.5.

### 14.1 Where costs land

- **Server:** all computer VMs, world calls, radio, the uplink.
- **Clients:** rendering only, scaled by what's **in view**, not by how many devices exist.
- **Single-player:** the built-in server and the client run in one process, so the player's PC pays both. Lower single-player defaults are needed.

### 14.2 Estimates

| Item | Estimate |
|---|---|
| Server RAM per active computer | ~1–4 MB (heap + Wasm memory + Java objects) |
| Shared engine code | ~10–50 MB, one-time |
| Client: text monitor / control surface | ~0.25–4 MB texture each |
| Client: web panel | ~4–8 MB texture + layout, plus ~1–3 MB if it uses JS |
| World-call latency | at least 1 tick (50 ms) per call |
| Panel click round-trip | ~100–200 ms plus player ping |

### 14.3 Main risks

1. **Peripheral calls on the main thread** lower ticks per second for everyone. Mitigated by the per-tick budget, call costs and caching.
2. **Panel sync bandwidth** with large data sets (e.g. 10k AE2 items). Mitigated by delta updates and rate caps.
3. **Rendering many web panels on the client.** Mitigated by render distance, a JS panel cap and texture caps.
4. **Engine speed on Chicory.** Unknown; benchmark first.

### 14.4 Tooling

- `/pc profile`: cost by device, owner and call type (CPU, world-call ms, bandwidth).
- `/pc list <player>`: devices, status (active or bricked) and type counts.

### 14.5 Engine measurements (milestones 0 and 0b, 2026-11-10)

Measured by the harness in `engine/bench` on the development PC (JDK 21.0.13, Chicory 1.7.5 runtime compiler, MicroPython 1.29.0). Re-run with `gradlew run` after any engine change.

**How the engine is built** (`engine/build.sh`, in Docker):

- A custom MicroPython port (`engine/port`), compiled with wasi-sdk 34, then processed by Binaryen 133.
- Binaryen's `--spill-pointers` pass is what makes the garbage collector safe: Wasm hides local variables from the collector, and this pass copies possible pointers to where it can see them.
- Exceptions (`setjmp`/`longjmp`) use Wasm exception handling, which Chicory supports. Asyncify isn't needed.
- The module is 611 KB and imports **only six host functions** (`write`, `ticks_us`, `sleep_ms`, `slice`, `submit`, `wait`). It has no operating-system (WASI) imports at all.
- MicroPython's interpreter loop (`py/vm.c`) is replaced by `engine/port/vm_split.c` (see "Interpreter speed" below).

**What works** (checks pass in both compiled and interpreted Chicory modes):

- `print`, f-strings, big integers, floats, exceptions and tracebacks with line numbers.
- Recursion 510 levels deep, then a Python `RuntimeError`. Running out of memory raises `MemoryError`. The computer keeps working after either.
- The garbage collector keeps live objects through many collections in a 256 KB heap.
- `host.submit` / `host.wait`, including timeouts and waiting on several tickets.
- Endless loops can be stopped: politely (a Python `RuntimeError`), or hard (the host abandons the instance) when the program swallows exceptions.

**Numbers:**

| Item | Measured | Earlier estimate |
|---|---|---|
| One-time parse + compile to JVM bytecode | 0.45 s (build-time compilation removes it) | n/a |
| Boot one computer and run its first script | 0.26 ms | n/a |
| Server RAM per computer: Basic / Advanced / Server | 0.7 / 1.5 / 2.5 MB (Python heap + about 470 KB) | 1–4 MB |
| Direct host call | about 7,700,000 per second | n/a |
| `submit` + `wait` round trip, excluding the tick wait | about 820,000 per second | n/a |
| 200 computers each making 10 world calls, 2 CPU permits | 355 ms (about 10 ticks) | n/a |
| 10 world calls by one computer while 8 others run tight loops, 2 CPU permits | 803 ms (ideal 500 ms) | n/a |

**Interpreter speed (milestone 0b):**

| Script (`engine/bench/scripts`) | Before | After | Desktop CPython 3.11 |
|---|---|---|---|
| `loop.py`: 1,000,000 loop iterations | 5,500 ms | 247 ms | 36 ms |
| `fib.py`: `fib(25)` | 1,600 ms | 121 ms | 16 ms |
| `objects.py`: 40,000 objects plus dict work | 1,100 ms | 405 ms | 18 ms |

- **The problem:** MicroPython's interpreter loop is one very large C function. It became a 42,685-byte JVM method, and HotSpot never optimises methods over 8,000 bytes, so about 90% of run time was spent in the JVM's own slow interpreter. Compiler size settings and the JVM flag `-XX:-DontCompileHugeMethods` did not fix it.
- **The fix:** `vm_split.c` moves each bytecode's code, unchanged, into its own small function, with a slim dispatch loop on top. Every method is now small enough to optimise.
- **How it's checked:** the harness's `difftest` mode runs 807 of MicroPython's own tests (basics, float, micropython, misc, stress, unicode) on both the original and the split interpreter. All 807 give identical output. `build.sh SPLIT_VM=0` builds the original for this comparison.
- **Maintenance:** on each MicroPython update, apply the changes made to `py/vm.c` to the matching handlers in `vm_split.c`, then re-run `difftest`.
- **Where it stands:** about 7 times slower than desktop CPython on loops and calls, about 22 times on allocation-heavy code. Allocation and the garbage collector are the next place to look if more speed is needed.

**Threads: ordinary threads, not virtual threads.**

- Each level of Python recursion needs roughly 2–4 KB of JVM stack. A virtual thread's stack can't be enlarged, and it overflowed before MicroPython's own recursion limit, which crashes the computer instead of raising a Python error.
- Each running computer therefore gets an ordinary thread with an explicit 8 MB stack. That is address space, not memory in use: only the pages actually touched are committed. 510 levels of recursion fit in under 2 MB.
- CPU is capped by a fair semaphore with one permit per worker core (§4), not by the number of threads. A computer holds a permit only while running Python.
- If the JVM stack ever does overflow, Chicory reports it as an error and the host abandons that instance, like a hard stop.

---

## 15. Configuration reference

### 15.1 Server (`config/qbotics-server.toml`)

```toml
[limits]
# Per-player device limits by type. Devices over the limit are placed "bricked" (inert, zero cost).
# NOTE: Lowering a limit does NOT disable existing devices (grandfathering).
#       Players over the new limit can't activate more until they drop below it.
#       Set reasonable values from the start.
computers_per_player   = 10
terminals_per_player   = -1     # -1 = unlimited (cheap devices)
qbots_per_player     = 5
monitors_per_player    = 10
control_surfaces_per_player = 10
web_panels_per_player  = 3
antennas_per_player    = 10
operators_exempt       = true
team_pooling           = false  # low priority
# Machine/Qbot-placed computers and Qbots are ALWAYS bricked (not configurable).

[boot]
per_tick = 4
order    = "dimension,owner_round_robin,distance_xz,coords"

[scheduler]
worker_cores              = 2
world_calls_budget_ms     = 3   # per tick, across all computers
reactivation_checks_per_s = 1   # per player, right-click reactivation

[qbots]
self_chunkload           = true
park_on_owner_logout     = true
new_terrain_moves_per_s  = 2    # per Qbot; moves into never-generated chunks
inspect_per_tick         = 4    # per Qbot; inventory.read() costs more than inspect
tool_wear                = true # real tools lose durability like a player's would

[radio]
short_radius             = 24
long_radius              = 100
# Infinite tier: unlimited range; the only tier that works across dimensions.
infinite_enabled         = true
frequencies_per_player   = 16   # private/team frequencies a player can claim

[dns]
names_per_player         = 10   # first come, first served; names never expire.
                                # Admins free names with /pc dns release <name>.

[uplink]
enabled             = true
allow_domains       = []        # empty = all public domains allowed (deny rules still apply)
deny_domains        = []
max_response_kb     = 1024
timeout_s           = 10
max_concurrent_per_computer = 2

[gateway]
enabled          = false
port             = 0            # 0 = share the game port (HTTP detected on connect); otherwise a dedicated port
bind_address     = "0.0.0.0"   # single-player always uses 127.0.0.1
tls_cert_file    = ""          # optional; otherwise put a TLS reverse proxy in front
max_requests_per_s_per_device = 10

[packages]
allowlist_file   = "config/qbotics/packages.txt"   # requirements.txt-style
include_bundled_defaults = true

[files]
drive_quota_kb        = 1024    # per internal drive
player_quota_mb       = 64      # across all drives a player owns
copy_files_on_duplicate = false # creative pick-block/dupes: new empty drive (false) or copy of files (true)

[git]
# External communication: off unless the admin enables it.
enabled        = false
allowed_hosts  = ["github.com", "gitlab.com", "codeberg.org"]
# Push uses players' own tokens, stored on this server. ADMINS CAN READ THEM.
# Enable only on trusted servers. Players see a warning before saving a token.
allow_push     = false
max_repo_kb    = 2048

[logistics]
controllers_per_player   = 5     # unused while there is no controller block (§11.0)
max_blocks_per_network   = 4096  # cables + network blocks; at most 253 of them addressable devices
max_items_per_transfer   = 576   # nine stacks
max_transfers_per_tick   = 8     # per network

[panels]
max_updates_per_s = 10
max_kb_per_s      = 64
```

### 15.2 Client (`config/qbotics-client.toml`)

```toml
[panels]
render_distance      = 32
max_js_panels        = 8
max_texture_size     = 1024
static_fallback      = false    # show static snapshots; update only when close
```

### 15.3 Single-player defaults

Lower heaps and fewer computers running at once by default, because one PC carries both server and client costs.

---

## 16. Build order (milestones)

> **Prerequisite:** the §0.1 checklist (mod ID/name, Minecraft version/loader) is done.

| # | Milestone | Proves |
|---|---|---|
| 0 | ✅ **Engine prototype + benchmark:** MicroPython on Chicory; `print`; `host.submit`/`host.wait` (§2.3). Done 2026-11-10; results in §14.5 | Feasibility, calling convention and real numbers for §14 |
| 0b | ✅ **Interpreter speed:** split MicroPython's interpreter loop so the JVM can optimise it. Done 2026-11-10: 3–22× faster (§14.5) | Speed good enough for data-heavy programs |
| 0c | ✅ **Project scaffold:** `common` + `neoforge` Gradle modules for Minecraft 26.1.2; the mod jar bundles the engine and Chicory; `gradlew :neoforge:runData` runs a Python self-test inside NeoForge. Done 2026-11-10 | The engine works inside the real mod loader |
| 1 | Computer block, terminal, REPL, file system, editor, device UUIDs. **Built, not yet played:** everything is covered by in-game tests and offline renders of the editor screen (§12.2), but nobody has used it in the game client yet | Core loop |
| 1b | Storage media (floppy) + disk drive; programmer block (loading programs from media) | Moving code between devices |
| 2 | `machine.Pin` redstone, events, timers | Embedded circuits |
| 3 | Ownership registry, limits, bricked state, boot queue | Performance controls (early on purpose) |
| 4 | Text monitors, then control surfaces (§10.2) | Displays foundation; cheap control panels |
| 5 | Qbots: movement, six-side sensing and actions, filtered inventory, tool use, self chunk loading, parking, programmer-block activation of inert Qbots (§5.4) | Qbot experience |
| 6a | Antennas, brought forward: three range tiers, external addresses, ports, frequencies, messages between computers (§9.3). **Built 2026-11-10, not yet played** | Computers in separate bases can talk |
| 6 | Antennas (tiers, frequencies, access modes), in-game network, pseudo-DNS, in-game HTTP | Radio |
| 7a | Item blocks, brought forward: cable networks, internal addresses (§9.3), Input, Output, Bus with access faces, the `logistics` module. **Built 2026-11-10, not yet played** (§11.0) | A program can move items between containers |
| 7 | Rest of the logistics network: filter and face GUI, tier upgrades, throughput limits, change events; wireless bridging (§9.2); drive bays (§17.6) | Inventory and machine automation |
| 7b | Fluid and energy logistics (§11.0) | Full factory automation |
| 8 | Uplink (with all §13 protections), then Git import/export (§17.5) | Real-internet access; moving code between worlds |
| 9 | Web panels v1: engine evaluation, HTML/CSS v1 coverage, sync protocol, events | Flagship UI |
| 10 | Web panels v2: JS sandbox, `<canvas>`, grid, animations | Full web development |
| 11 | Built-in gateway, VS Code extension, package allowlist | Ecosystem |
| 12 | *(Lowest priority)* Public peripheral API + first-party adapters | Deep mod integration, only if needed |

---

## 17. Files, storage media and identity

### 17.1 Two kinds of ID

| ID | Assigned | Purpose |
|---|---|---|
| **Device UUID** | **On every placement** | Identifies the placed machine (ownership, limits, radio address). A creative copy or a re-placed block always gets a new UUID, so no two devices are ever identical. |
| **Drive ID** (UUID) | When a device's internal drive is first written | Identifies the stored files. Kept in the item's data when the block is broken, so files travel with the item. |

**On placement:**
1. Assign a new device UUID.
2. If the item carries a drive ID **that isn't attached to any other placed device**, attach it, so the files come back.
3. If that drive ID is **already in use** (creative pick-block, a duplication glitch), create a new empty drive, or copy the files if `files.copy_files_on_duplicate = true`.

### 17.2 Where files live on disk

```
<world>/data/qbotics/
  registry.dat               # ownership registry (versioned)
  drives/<drive-id>/         # one folder per drive: real files
    meta.json                # format version, owner UUID, size, last used
    main.py …
  media/<media-id>/          # floppies, CDs, hard drives
```

- **Real folders, not a database or archive:** admins can inspect and back them up, and the layout fits Git and VS Code sync. It also matches the learning goal: these are real files.
- **Safety:** all paths sanitised (no `..`, absolute paths or links); quotas enforced in code (§15); writes are atomic (write a temp file, then rename).
- **Format version** in every `meta.json` and in the registry, so future updates can migrate old data.
- **Cleanup:** never auto-delete. A drive's item may sit in an unloaded chest for months. Instead, an admin command such as `/pc prune --unused-days 180` lists drives and lets the admin delete them after review.

### 17.3 Storage media (items)

| Media | Capacity (target) | Notes |
|---|---|---|
| **Floppy disk** | Small (~128 KB) | Cheap; read/write; early game |
| **CD** | Medium | **Write once, then read-only.** Good for distributing finished programs. |
| **Hard drive** | Large | Fits in computers or servers; read/write; late game |

- **Disk drive block** (or a built-in slot) reads and writes media.
- **Programmer block** loads media onto inert or machine-placed devices (§5.4, §8.7).
- Media contents are stored under `media/<media-id>/`. Copying media works like §17.1 step 3 (new ID).

### 17.4 Shell commands

`ls`, `cp`, `mv`, `rm`, `edit`, `run`, plus `copy /disk/prog.py /` between drives and media.

### 17.5 Git import and export

Moves code between worlds and teaches real Git and GitHub habits.

- **Admin-controlled:** off by default; host allowlist; import-only unless `git.allow_push`; size caps (§15). Counts as external communication, like the uplink.
- **Commands (simplified Git):**
  - `git clone <url> [dir]` downloads a public repo's archive over HTTPS; no `git` binary needed.
  - `git pull` refreshes from the same source.
  - `git push` (if allowed) uses the hosting site's API to commit changed files.
- **Pushing (to the player's own repo):**
  - The server admin must enable it first (`git.allow_push`).
  - The player enters **their own** access token in a GUI on their game. It's never typed into script code.
  - **The token is stored on the server, so server admins can see it.** This can't be avoided, because anyone with access to the server's files can read anything stored there. Pushing is meant **only for trusted setups**: your own server, single-player, or a server run by people you trust.
  - Before the token is saved, a **warning screen** says this plainly, and the player has to confirm.
  - Inside the server, the token is **never readable by Python scripts** (so a malicious script can't steal it) and **never sent to other players**.
  - Recommend fine-grained tokens limited to a single repo, with an expiry date, so a leaked token can do little harm.
  - **Safer alternative (later, with the VS Code extension):** push from the player's own PC. Files sync from the in-game computer to a local folder, and the player uses normal Git there. The token never leaves their machine.
- Repos can include a `pcproject.toml` (name, entry point, required tier), so a cloned project runs with one command.

### 17.6 Drive bays (shared program storage)

A block that holds several **hard drives**, like the drive bays in Applied Energistics and Refined Storage, and gives a base **shared program storage**: an internal code repository.

- **Connects by cable or antenna** (§9.2) to the computers and Qbots on its network.
- **Mounted as a folder:** `/net/<bay-name>/` on every connected device.
- **Use cases:**
  - one shared `lib/` that every computer imports from
  - a base-wide program library that the programmer block flashes onto new Qbots
  - updating one file to update every device that runs it
- **Permissions per bay:** `read-only` or `read-write`, and owner, team or public, matching antenna access modes.
- **Concurrent writes:** last write wins, with whole-file atomic writes (§17.2). Optional file locking (`fs.lock()`) for programs that need it.
- Each hard drive counts toward its owner's file quota (§15).
- *Later idea:* simple version history in the bay (`history`, `revert`), as a step toward real Git (§17.5).

---

## 18. Decision log

| Date | Decision | Status |
|---|---|---|
| 2026-11-10 | Engine: MicroPython → Wasm → Chicory | Decided (benchmark in M0) |
| 2026-11-10 | Ownership by player UUID; per-type device limits | Decided |
| 2026-11-10 | Over-limit devices are bricked (zero cost); reactivate by re-placing or right-clicking (rate-limited) | Decided |
| 2026-11-10 | Machine- or Qbot-placed devices always bricked; programmer block activates them | Decided |
| 2026-11-10 | Lowering a limit grandfathers existing devices | Decided |
| 2026-11-10 | All active devices assumed chunk-loaded; staggered boot by owner round-robin, then distance | Decided |
| 2026-11-10 | Licence: Unlicense (0BSD as fallback); clear AI disclosure | Decided |
| 2026-11-10 | Git push with the player's own token, admin-enabled, stored on the server (admin-visible, with a warning); local VS Code push later | Decided |
| 2026-11-10 | Logistics network: cables + controller; filtered, chest-like Input slots; Output/Bus with attachment side + access face | Decided |
| 2026-11-10 | PEP 8 conventions; relative sides (front/right/back/left/top/bottom) for actions | Decided |
| 2026-11-10 | Robot named **Qbot**, Python module `qbot` | Decided |
| 2026-11-10 | GPS built into every computer and Qbot (position, heading, dimension) | Decided |
| 2026-11-10 | Antennas: 24 / 100 / infinite radius, cylinder with unlimited height, no weather; only infinite works across dimensions; frequency codes with private/team/public access | Decided (link rule proposed) |
| 2026-11-10 | Wireless bridging of logistics networks; one controller per bridged network | Decided |
| 2026-11-10 | Qbots sense and act on all six sides; reading inventories is a separate command; filtered inventory like Input blocks; access faces | Decided |
| 2026-11-10 | Qbot mining: iron (basic) / diamond (advanced) built in; real tools via `equip().use()`, with wear | Decided |
| 2026-11-10 | Tiered in-place upgrades for Input blocks and Qbot inventories; fluid and energy logistics mirror items | Decided |
| 2026-11-10 | Drive bays for shared program storage | Decided |
| 2026-11-10 | DNS: first come, first served; never expires; admins release names | Decided |
| 2026-11-10 | Web panels target broad, everyday HTML/CSS, staged v1–v3; evaluate an existing engine on Wasm first | Decided |
| 2026-11-10 | Graphics monitors dropped; **control surfaces** (prebuilt widgets) added; pixel drawing via `<canvas>` | Decided |
| 2026-11-10 | Peripheral API and other-mod adapters moved to lowest priority; first-party logistics blocks come first | Decided |
| 2026-11-10 | External access through a built-in gateway (server or single-player built-in server); external relay optional | Decided |
| 2026-11-10 | Packages: admin-controlled, requirements.txt-style allowlist; server downloads and caches | Decided |
| 2026-11-10 | Device UUID per placement; separate drive ID travels with the item | Decided |
| 2026-11-10 | Calling convention: `submit`/`wait` primitives, blocking default plus `.aio` async | Decided |
| 2026-11-10 | Files as real folders under the world save | Decided |
| 2026-11-10 | Milestone 0: engine is feasible. Build = wasi-sdk + Binaryen `--spill-pointers`; exceptions via Wasm exception handling; no Asyncify | Decided (measured, §14.5) |
| 2026-11-10 | Interpreter loop replaced by `vm_split.c` (one small function per bytecode) so the JVM optimises it; checked against MicroPython's test suite | Decided (measured, §14.5) |
| 2026-11-10 | One ordinary thread per running computer (8 MB stack) plus a fair CPU semaphore, instead of virtual threads | Decided (measured, §14.5) |
| 2026-11-10 | Stay on MicroPython; a cut-down CPython was considered and rejected (larger interpreter loop, far more memory per computer, no hard heap cap) | Decided |
| 2026-11-10 | Minecraft 26.1.2, NeoForge, Java 25. Loader-independent code in a `common` module so a Fabric module can be added later | Decided |
| 2026-11-10 | Name **Qbotics**, mod ID `qbotics` | Decided |
| 2026-11-10 | Network addressing modelled on a home router: each antenna has an external IPv4 address; devices on its cable network get an internal IPv4 address and a forwarded port; remote devices are addressed as `external IP:port` (§9.3) | Decided (numbering proposed) |
| 2026-11-10 | Recipes: 16 cables from 6 iron + 3 redstone (vertical or horizontal lines); computer from 7 cables, redstone and glass (§20) | Decided |
| 2026-11-10 | The computer's screen is a code editor (explorer, tabs, terminal, Python scratchpad) drawn at the window's real resolution; the terminal starts in a shell (§12.2) | Decided |
| 2026-11-10 | Files: plain `open(..., "w")` writes go straight to the drive, so an unclosed file keeps its data; drive ID travels with the broken computer's item | Decided |
| 2026-11-10 | Every cable network gives its devices internal IP addresses without an antenna; each device also has a fixed hardware (MAC) address and an optional name. The antenna only adds the external address and ports (§9.3) | Built; to confirm |
| 2026-11-10 | No controller block: the computer plugs straight into the cable (§11.0). Reverses the earlier controller decision | Built; to confirm |
| 2026-11-10 | Transfers move up to the count asked for and return how many moved, instead of all-or-nothing (§11.0) | Built; to confirm |
| 2026-11-10 | Outputs and Buses point at the block they were placed against; they don't join the network through that side (§11.0) | Built; to confirm |
| 2026-11-10 | A device's port on its antenna is 1000 + the last number of its internal IP, instead of ports handed out in order (§9.3) | Built; to confirm |
| 2026-11-10 | Computers on one cable network can message each other without an antenna; an antenna is needed only to reach another network (§9.3) | Built; to confirm |
| 2026-11-10 | The filtered storage block is the Interface; Input is a directional device that extracts from what it points at | Decided |
| 2026-11-10 | Input, Output and Bus are plates sharing a cable's block, in the style of Applied Energistics | Decided |
| 2026-11-10 | A cable carries up to six plates, one per side (for tight spaces) | Decided |
| 2026-11-10 | Plates are block-state values on the cable (15,625 states). Fluid and energy plates did **not** need a model built in code after all: what a plate carries is kept by the cable and only colours the plate (a tint), so the state count is unchanged. A model built in code is still the route if plates ever need different shapes per medium | Built; to confirm |
| 2026-11-10 | Fluid Interface (4 tanks x 4 buckets), Energy Interface (2,000,000 FE), and fluid and energy Input, Output and Bus | Decided |
| 2026-11-10 | Transfers can be aimed by face or slot/tank number with no item or fluid named; slots are numbered from 0, left to right, top to bottom | Decided |
| 2026-11-10 | Item and fluid filters follow JEI's search words (`@mod`, `#tag` or `$tag`, wildcards, `-not`, `a \| b`) | Decided |
| 2026-11-10 | Recipes: Bus = Input + Output; fluid version = item version + bucket; energy version = item version + block of redstone (all shapeless) | Decided |
| 2026-11-10 | Breaking a cable while aiming at a plate takes off only that plate; aiming at the cable breaks everything | Decided |
| 2026-11-10 | Parts: Screen (glowstone dust, RGB dye, glass panes; makes 4) and Motherboard (copper, glass, green dye); the computer is 7 cables, a motherboard and a screen. Cable recipes stay | Decided |
| 2026-11-10 | Look: whitish iron with redstone red; the plates carry no arrows because their shapes show direction | Decided |
| 2026-11-10 | Network Probe: a hand tool that reads a device's name and addresses with one-click copy, and names the device. Names are the recommended way to address many devices in code | Built; to confirm |

---

## 19. Open questions

**Before starting**
1. ~~Mod ID and name.~~ Settled: Qbotics, `qbotics`.

**Settled by milestone 0**
2. ~~Virtual thread per computer vs. Asyncify; real performance numbers.~~ Settled: ordinary threads with a large stack, no Asyncify; numbers are in §14.5.

**Gameplay (confirm or decide when you reach the feature)**
3. Antenna link rule: is "the larger of the two ranges" right, and must *both* ends be Infinite tier across dimensions (§9.1)? Do antennas fitted as upgrades to computers and Qbots count against `antennas_per_player`?
3a. Network addressing (§9.3):
   - Confirm the proposed ranges: external `100.64.0.0/10`, internal `192.168.1.0/24`, ports from `1000`.
   - Two antennas on one cabled network, or two networks joined by wireless bridging (§9.2): does each antenna keep its own external IP with its own port table for the same devices, or does one antenna become the network's router?
   - Can a computer open extra ports of its own (for example a web server on one and a control API on another), or is it always one port per device?
   - Which items are addressable, beyond hard drives in a drive bay?
4. Qbot tiers: confirm basic = iron and advanced = diamond mining level (§8.4).
5. Logistics: network size cap, whether one Output can feed several targets, slot counts and names for each tier (§11.0).
5a. Confirm the four "Built; to confirm" rows in §18: addresses without an antenna, no controller block, up-to-count transfers, placement sets the target. Also: should the hardware address travel with the item when a block is broken and re-placed (as a real network card's would), rather than being new each time? And the names: the doc says Input and Output; "import" and "export" have also been used for them.
6. Storage media capacities; whether a hard drive doubles as a computer's internal drive (§17.3).
7. Pseudo-DNS: whether `names_per_player` should exist at all, or stay unlimited (§9.3).

**Later features**
8. HTML/CSS engine: which existing engine (if any) to build on, after checking its flexbox and grid support (§10.3).
9. Initial control surface widget set and grid sizes per monitor size (§10.2).
10. Gateway: support TLS directly, or only recommend a reverse proxy (§9.5)?
11. Ranks and teams (§5.6; low priority).
12. Peripheral API and adapters, only if a real need appears (§11; lowest priority).

**Deferred on purpose**
- Recipes (other than those in §20), crafting costs, balance; final textures, models and sounds.

---

## 20. Recipes decided so far

All are shaped crafting-table recipes. `I` = iron ingot, `R` = redstone dust, `G` = glass block, `C` = Qbotics cable.

| Result | Pattern | Notes |
|---|---|---|
| **16 Cable** | `IRI` / `IRI` / `IRI` | Vertical lines: 6 iron, 3 redstone |
| **16 Cable** | `III` / `RRR` / `III` | Horizontal lines: the same ingredients |
| **Screen** (a part; makes 4) | `GRP` / `GEP` / `GBP` | Three upright lines side by side: glowstone dust, then red, green and blue dye, then glass panes. Will also be used for monitors |
| **Motherboard** (a part) | `CCC` / `LGL` / `CCC` | Copper ingots above and below; glass, green dye, glass between. The core of every computer |
| **Computer** | `CCC` / `CMC` / `CSC` | Cable in the shape of leggings, a motherboard in the middle, a screen at the bottom middle. Costs 7 cables, 1 motherboard, 1 screen |

**Placeholders, not decided:** the item blocks need recipes to be craftable at all, so each is four cables around the vanilla block it most resembles.

| Result | Pattern | Middle |
|---|---|---|
| **Interface** | ` C ` / `CXC` / ` C ` | `X` = chest |
| **Input** | ` C ` / `CXC` / ` C ` | `X` = hopper |
| **Output** | ` C ` / `CXC` / ` C ` | `X` = dropper |
| **Bus** | Input + Output (any arrangement) | |
| **Fluid Interface, Fluid Input, Fluid Output, Fluid Bus** | The item version + a bucket (any arrangement) | A Fluid Bus can also be made from a Fluid Input + a Fluid Output |
| **Energy Interface, Energy Input, Energy Output, Energy Bus** | The item version + a block of redstone (any arrangement) | An Energy Bus can also be made from an Energy Input + an Energy Output |
| **Network Probe** | `R` / `C` / `I` (one column) | Redstone, cable, iron ingot |
| **Short-Range Antenna** | `T` / `I` / `C` (one column) | Redstone torch, iron ingot, cable |
| **Long-Range Antenna** | `D` / `A` / `C` (one column) | Diamond, short-range antenna, cable |
| **Infinite Antenna** | `E` / `A` / `C` (one column) | Eye of ender, long-range antenna, cable |
