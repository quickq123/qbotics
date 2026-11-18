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

> **Status (2026-11-12): decided and partly built** (`docs/reference.md` §7i). There is **no Advanced computer**: the
> Laptop is the mid tier, with 1 MB (one per player, so its memory costs little). The **Server** is built: a computer
> block with 2 MB, an 8 MB drive, a built-in uplink, and four hard-drive sockets of its own (mounted at `/net/disks`),
> limited by `limits.servers_per_player` (2). Recipe `ASA/RLR/ADA` (ancient debris, LED Screen, RAM, Laptop, Drive Bay);
> **RAM** is an integrated circuit in eight gold ingots. Raw ancient debris rather than netherite ingots, because getting
> the raw material is the hard part. Memory belongs to the machine that runs the program: a laptop or ssh session on a
> Server gets 2 MB, a laptop on a Qbot gets 256 KB. **Services built** (§7i.1): up to four background programs, each
> its own 256 KB Python on its own thread sharing the Server's drive, network and CPU share; `service
> start/list/log/restart/stop`; kept in `/.services` and restarted when the Server comes on; a light each on the rack
> (green running, red failed); messages to the Server go to the terminal's program if it listens, else to the first
> listening service; a service's events and displays are its own. **Chunk loading (decided 2026-11-12):** not built in; a Server takes the antennas' Chunk Loader Upgrade and holds its
> cable network's chunks the same way, under the same config and per-player loader limit, so chunk loading stays in one
> place and admins control it as before. **Answer size by tier (built 2026-11-12):** `max_response_kb` (24) scales
> with memory: laptop 96 KB, Server 192 KB; a program is handed at most a quarter of its heap (the engine's guard
> raised to 1 MB). No web browsing: simple pages and APIs only. Later, maybe: `requests.download(url, path)` writing
> straight to the drive, as git does. With laptops switched off by config, a Server can't be crafted.

---

## 4. Concurrency and scheduling

- **Workers:** each running computer has its own thread with a large stack (§14.5 explains why not virtual threads). A fair semaphore with `scheduler.worker_cores` permits (default 2) caps how many run Python at once. A computer gives up its permit while it waits on the world, and goes to the back of the queue at the end of each time slice.
- **Time slices:** each computer gets a slice per tick; scheduling is round-robin.
- **World-call executor:** runs queued world requests on the main thread, up to `scheduler.world_calls_budget_ms` per tick. Leftovers wait for the next tick.
- **Call costs:** each host or peripheral function declares a cost. Expensive calls are rate-limited per computer and per player.
- **Batch APIs required:** each call waits at least one tick (50 ms), so APIs must return bulk data (e.g. `me.items()` returns everything in one call).
- **Events over polling:** change feeds (`watch_items()`, redstone edge interrupts, radio receive) are the main pattern taught in docs and templates. Built 2026-11-11 as the `events` module (`docs/reference.md` §7b): each computer has an event queue that the game pushes into (pin events, messages; later, monitor touches), and `events.run()` sleeps on it between timers. Monitors' control surfaces (§10.2) are meant to push their clicks into the same queue.

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
- *As built (2026-11-11):* a device placed by a machine has no owner; until the programmer block exists, the first player to right-click it while under their limit switches it on and becomes its owner.

### 5.4 Placed by machines or Qbots

- **Any computer or Qbot placed by a machine or a Qbot is bricked**, with no exceptions and no config option.
- **Companion programmer block:** carries its owner's UUID and activates and programs inert devices that machines produced. Activation counts against the programmer owner's limit. **Built 2026-11-11 for computers** (`docs/reference.md` §9e): observer-style programming face, one hard-drive socket edited in a terminal-less editor, `programs.txt` mapping redstone levels 1–15 to folders, programs on a rising signal, on a computer's arrival (strongest signal wins) or from `net.programmer().program()`, once per target per tick, never another owner's computer. Qbots to follow.
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
- **This mod's chunk loading is an upgrade** (decided 2026-11-11): a **Chunk Loader Upgrade** in an antenna keeps that antenna's whole cable network loaded (up to `chunk_loading.chunks_per_loader` chunks, nearest first), switchable in the antenna's screen or from a program, with `chunk_loading.loaders_per_player` working loaders per player and a server switch for loaders of offline owners (on by default). Built: `docs/reference.md` §7.3b. The same upgrade item will fit Qbots (§8.5).
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

> **Status (2026-11-11): the Iron Qbot is built** (`docs/reference.md` §7d). Decided by the project owner: a Qbot is a **moving block** (not an entity), drawn by a renderer as a loose, original golem-like robot in white-ish iron that hovers, glides a block per step with a small hop, turns smoothly and swings its arms as it works (so its movement doesn't read as an uncanny slide). It is a computer (own terminal, editor, drive, limits as `qbots_per_player`, Programmer), carries **16 slots (4x4)**, a hand slot for a tool, one upgrade slot (Chunk Loader), a **1,000,000 FE cell** and **two 4,000 mB tanks** (water and lava apart). Iron tier: digs as iron tools, with real tools in hand used when better (wear, Fortune, Silk Touch); moves, turns, inspects six sides, digs, places, uses (blocks and creatures), attacks, drops/sucks/inserts/extracts with faces, moves energy and fluid, pumps and pours. Its program keeps running as it moves. Its main limits by design: iron-level digging and 16 slots, against a later tier's "full chest". Not yet: higher tiers, inventory filters, item/fluid/energy capabilities for pipes, Efficiency, parking on owner logout, the new-terrain rate limit.
>
> **Camera (built 2026-11-12, asked for by the project owner):** `qbot.snapshot()` casts a ray per pixel on the server
> (visual shapes, fluids, creature boxes; barrier, light and structure-void blocks are looked through), up to 32
> blocks, 128 x 96 at most, one a second per Qbot. Each pixel records block state (or creature kind), face, point on
> the face, light and distance; players' games colour it from the real block textures (tinted at the place it was
> taken), shaded by face, light and fog. Programs get what each pixel shows (`block_at`, `counts`). Shown on Web
> Panels as `<img src="image:name">` via `web.image(name, pic)`. Decided: textured, creatures as coloured shapes.
> **Replaced 2026-11-18 by the view** (project owner's decision): the server lists the faces and creatures in sight and
> players' games draw them with the game's own models (`qbotics.camera.ConeView`, `client.display.ConeRenderer`);
> `snapshot()` is another name for `view()`. See the 2026-11-18 rows in the decision log and `docs/reference.md` §7d.3b.

### 8.1 Design principles

- **No fuel or energy system.** (Its energy cell and tanks, added 2026-11-11, are cargo it carries to machines, not what it runs on.)
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
- The **same slot count as an Input block of the same tier**. Inventory upgrades are tiered, like Input blocks (§11.0). **Decided 2026-11-11:** the Iron Qbot has 16 slots (4x4); a higher tier gets a full chest's worth.
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

### 8.5 Chunk loading (an upgrade)

> **Changed 2026-11-11 (project owner):** Qbots do **not** load chunks on their own. A Qbot loads chunks only with a **Chunk Loader Upgrade** fitted, the same item antennas take (§6.1). Without it, a Qbot stops working when its chunk unloads, like any other block. The rules below apply to a Qbot with the upgrade.

- A Qbot loads the chunk it's in. When it's **within one block of a chunk border**, it also loads the neighbouring chunk, so the touching sides it senses are always loaded.
- Qbots in the same chunk share one load.
- The cost is capped through `limits.qbots_per_player`, not a chunk budget.

### 8.6 Learner-safe behaviour

- **No silent failures.** Every blocked action raises a clear, catchable error that names the cause, the Qbot's position and the fix.
- **Parking:** when the owner logs off or the server stops, a Qbot finishes its current action, saves its state, and pauses. It resumes when the owner returns (configurable). Stopping only between actions prevents item duplication.
- **Rate limit on new terrain:** moving into chunks that have never been generated is rate-limited, because world generation is expensive.

### 8.7 Qbot-related blocks

- **Programmer block:** activates and programs inert Qbots and computers (§5.4). It loads programs from **storage media** (§17.3) or a **drive bay** (§17.6), and activates the device under the programmer owner's UUID. **Built 2026-11-11 for computers**, with its own hard-drive socket (§5.4, `docs/reference.md` §9e).
- Upgrades (inventory tiers, antennas, tool-holder): to be defined. **Built 2026-11-11:** the upgrade item type and two upgrades, the **Transport Upgrade** and the **Chunk Loader Upgrade**, fitted to antennas now (two slots, one of each kind) and meant to fit Qbots later (`docs/reference.md` §7.3b).

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

> **Changed and built 2026-11-11 (project owner):** networks are **not merged**. Instead, with a **Transport Upgrade** in both antennas, a program reaches the devices behind another antenna (`net.remote("100.64.0.2")`) and uses them as its own, including `move()` between the two networks. Each network keeps its own names and addresses; a remote device's address carries the antenna's (`100.64.0.2/02:1a:...`). Free, but capped per antenna per second (`transport.items_per_second`, `fluid_per_second`, `energy_per_second`). Range, frequency and access rules apply as for messages. Displays aren't drawn on across networks. The bullets below are the earlier plan, kept for reference. Player-facing details: `docs/reference.md` §7.3b.

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

**Built since:** antenna access modes (private, team, public), the computer settings page and `ssh` (see `docs/reference.md` §1.5, §7.3, §7.4). **Built 2026-11-11:** reaching devices behind another antenna, and moving things through it, with Transport Upgrades (§9.2); chunk loading as an antenna upgrade (§6.1). **Not built yet:** frequency ownership, `async` receiving, antennas as upgrades fitted to computers and Qbots, in-game HTTP and names.

#### In-game HTTP and names

- **In-game HTTP:** `requests` works between computers, using the addresses above (`requests.get("http://100.64.0.12:1003/items")`). The ROM includes a micro web framework so computers can serve APIs (e.g. a REST endpoint for a storage system).
- Raw sockets are allowed **only inside the game**.
- **Pseudo-DNS** (names like `storage.mc`) maps a name to an `external IP:port`, so programs don't need to hard-code numbers:
  - **First come, first served.** A registered name belongs to its owner's UUID and **can't be taken by anyone else**.
  - **Names never expire.** Only a server admin can free one (`/pc dns release <name>`).
  - Suggested anti-squatting cap: `dns.names_per_player` (§15).

### 9.4 Uplink (real internet)

> **Status (2026-11-12): built** (`docs/reference.md` §7g): the Uplink block and the `requests` module (get, post,
> put, patch, delete, head; Response with status_code, ok, text, json(), headers, url, raise_for_status). Laptops have
> one built in. Its own small HTTPS client connects to the address it checked (no DNS rebinding), with the §13
> protections: private, loopback, link-local, shared, multicast and reserved addresses refused (IPv4 inside IPv6 too),
> each redirect checked, certificates checked, domain allow and deny lists, size cap (24 KB, for the small heap),
> timeout, per-player rate, logging by owner. Runs on its own threads. Not yet: WebSockets.

- An expensive, craftable block: the only route to the real internet. It can be disabled or restricted by the server.
- HTTPS and WebSockets only (no raw TCP at first).
- One `requests` API covers both in-game and real destinations; the router picks the route.

### 9.5 External access: built-in gateway

> **Status (2026-11-12): the SSH gateway is built** (`docs/reference.md` §7f), decided by the project owner as the way
> VS Code reaches computers (instead of a custom extension): an SSH and SFTP server inside the mod (Apache MINA SSHD,
> bundled), off by default, on 127.0.0.1:2222 unless the admin opens it. Keys only, added in game with
> `/qbotics ssh key add` and kept with the world; banned players refused. The login name picks the computer (`laptop`,
> a name, a hardware address); only the player's own unless `ssh_gateway.all_computers`. The shell is the computer's
> terminal (Ctrl+C, Ctrl+Z, Ctrl+D leaves); SFTP serves its real drive folder within its quota; no forwarding, no
> exec. VS Code: the SSH FS extension (files and terminal) or the SFTP extension (a synced local folder, where real
> Git works). Microsoft's Remote-SSH can't work (it installs a server program on the far end). The HTTP side below
> (web panels, in-game HTTP servers) is still to come.

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

> **Status (2026-11-11): Text Monitors and Control Panels built** (`docs/reference.md` §7c). Decided by the project owner: two blocks (Text Monitor, Control Panel); blocks of one kind side by side join into one screen up to 8x6 blocks (rectangles from the top left; the screen's identity and content survive growing and shrinking); reached over the network (`net.monitor()`, `net.panel()`), not by computer side; touched by right-clicking the face, with sneak + right-click for a close-up view where sliders drag. Drawing calls run at once on the computer's thread through a handle checked once a second, rather than one tick per call. Text: 15x10 characters per block at scale 1 (0.5 to 5), 16 dye colours. Panel: 4x4 cells per block; widgets button, toggle, slider, gauge, lamp, label, readout, graph. Clicks reach programs through the events module. Not yet: bound actions. Later the same day: display limits (§7c.6 of the reference), themes, dial, number input and item display; and screens on floors and ceilings (2026-11-12).

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

> **Status (2026-11-12): v1 built** (`docs/reference.md` §7c.5b), decided by the project owner: litehtml (pinned)
> compiled to Wasm (`engine/web`, without C++ exceptions; the charset sniffing is cut, pages are UTF-8) and run by
> Chicory in each player's game; the game answers its font and image questions and paints its display list with
> Java 2D into a texture, on a background thread. The Web Panel is a display block (joins like the others, 128 CSS px
> per block). Python: `net.web_panel()`, `show(path)` (stylesheets inlined), `html()`, `state` (sent alone when only
> it changes; `{{name}}` / `{{{name}}}` fill the page in the player's game), `on(name)`, `on_click()`. Clicks are
> hit-tested in the player's game (it has the layout) and sent as the element's tag, id, name, data-event and value;
> the close-up shows :hover and :active. Measured: engine load ~0.3-0.5 s once, a page update ~40 ms off-thread.
> **Storage additions (built 2026-11-12, asked for by the project owner for sorting and storage UIs):** item icons
> (`<img src="item:...">`: the page leaves the box, the game's item renderer draws the icon over the panel and in the
> close-up), text fields (`<input>` rewritten before layout as a box the game edits, per player; typed text reaches
> the program after a pause and on Enter), scrolling (per player: `overflow: auto` boxes through litehtml, else the
> page), and Mustache-style sections in the state template (`{{#list}}`, `{{^list}}`, `{{.}}`). Not yet: flex `gap`,
> select/textarea/checkbox, typing in the world.
>
> **Control Panel additions (built 2026-11-12):** themes (dark, light, industrial, blueprint, terminal; `panel.theme`),
> and widgets: dial (270-degree knob, set by clicking around it), number (- value +, by step), item display (the item
> renderer draws the icon on the panel and in the close-up; a click is a press).
>
> **Limits (decided 2026-11-12: "limit by what could be abused to lag the server or cause problems"):** Web Panels in use
> per player (`web_panels_per_player`, 16; checked when a program opens one, among loaded screens; no bricking); a
> page resent at most once a second per screen (state still 10/s); each client runs only the 8 nearest scripted panels'
> scripts. Monitors and Control Panels are cheap and unlimited.
>
> **Form controls (built 2026-11-12):** `select` (a list in the close-up, a window of buttons from the world), `textarea`
> (multi-line, Enter adds a line), checkbox and radio (a click flips them, in the world too), all rewritten before
> layout with `data-input="kind:name"` so a click knows what it hit; values per player, sent as events; scripts read
> `value` and `checked`. Text fields can be typed from the world: right-click opens a box over the game.
>
> **Layout additions (built 2026-11-12):** litehtml is patched at build time (`engine/web/patches.py`, each anchor checked):
> `gap`/`row-gap`/`column-gap` in flex layout (lines laid out narrower, items shifted), and CSS grid as a flex container
> laid out by `render_item_grid` (`engine/web/extra`): columns from `grid-template-columns` (fixed, %, em, fr, auto, minmax,
> repeat incl. auto-fill/fit), row-major placement with `grid-column` spans and starts, gaps, rows sized to their
> tallest item with stretch. Not: template rows/areas, `grid-row`, dense packing, item alignment.
>
> **JavaScript and canvas (built 2026-11-12):** quickjs-ng (pinned v0.10.1) compiled to Wasm (`engine/js`), one runtime
> per scripted page in each player's game, on the web worker thread: 16 MB, 0.4 s to load, 60 ms per event or frame
> (interrupt handler), no network. `prelude.js` is the browser it sees: a small DOM (parse, serialize, selectors,
> style/classList/dataset, events with bubbling and preventDefault), timers and requestAnimationFrame (woken about 10
> times a second, only while someone is looking), console, and `panel` (state, onState, send). When a script changes the
> DOM the page is serialized (elements numbered for clicks; canvases as `canvas:N` images) and laid out again; canvas
> calls are batched and painted with Java 2D. Clicks go to scripts first; `panel.send` reaches `@web.on` with `.data`;
> script errors reach the close-up and an `error` event. Speed: QuickJS's interpreter loop is split into one function
> per opcode at build time (`engine/js/split_interp.py`, below), as the Python engine's is. Not yet: fetch (by design), modules. `drawImage` (built): canvases, `<img>` and `Image` with item: (the item's
> texture, else a block's side, read from the resources) and image: (Qbot pictures), nearest-neighbour.

**JavaScript speed (2026-11-12).** `JS_CallInternal`, QuickJS's interpreter, was one 47 KB Wasm function: far over the
8,000 bytes of JVM bytecode HotSpot compiles, so every opcode, and every call (each call enters it again), ran in the
JVM's own interpreter. `engine/js/split_interp.py` rewrites it while building (QuickJS's source stays pinned and
untouched): each case's code, unchanged, becomes its own function; a slim loop (`js_vm_run`) dispatches to them; the
search for a `catch` is a function of its own (`js_vm_unwind`); the loop's shared locals live in a struct. The script
checks its assumptions about the source and stops rather than build a wrong engine. Two build traps: clang runs
wasm-opt itself after linking (`--no-wasm-opt` turns that off), and Binaryen inlines every function with one caller
(`-ocimfs=40` stops it); either puts the loop back together. Measured with `engine/bench` (`./gradlew -q jsbench`,
best of 8 runs, no JVM flags):

| Script | Before | Split |
|---|---|---|
| loop: 1,000,000 iterations | 2,040 ms | 505 ms |
| fib(24): calls | 330 ms | 56 ms |
| objects: 40,000 objects, Map, filter/map/reduce | 620 ms | 203 ms |
| strings: padStart, join, split | 180 ms | 60 ms |
| math: 200,000 x sin, cos, sqrt | 1,530 ms | 321 ms |
| json: stringify and parse 5,000 rows x 5 | 245 ms | 230 ms |

Checked with QuickJS's own tests (`./gradlew -q jstest`, one test function at a time): the split and unsplit builds
(`SPLIT=0 engine/js/build.sh`) give identical results. Tried and dropped: inlining the forty one-line opcodes into the
loop made it too big to compile again (loop 2,972 ms); inlining a few gained nothing. JSON is unchanged: its time is in
QuickJS's own JSON code, not in huge methods (switching HotSpot's limit off changes nothing now).

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

### 10.5 Tablets

> **Status (2026-11-12): built** (`docs/reference.md` §7c.7). Decided by the project owner: a
> handheld display, opened by right-click, with an Infinite antenna in its recipe (so it works
> anywhere, like the Laptop's); programs reach it with `net.tablet()`; the program picks the
> screen kind; the screen also shows on the tablet while held; programs name what the tablet
> shows (`tab.title`).

- **Pairing:** sneak + right-click a network's router antenna (its access setting applies);
  again to unpair. Up to 8 networks per tablet.
- **Who controls the screen:** each paired network gets its own screen on the tablet, a tab;
  the holder picks the tab. Networks never contend for one screen; programs on one network
  share its tab as they share a Control Panel.
- **Screen:** 3 x 2 display blocks' worth (12 x 8 panel cells, 45 x 20 characters, 384 x 256
  CSS pixels). One kind at a time: opening another kind replaces it blank.
- **Where it lives:** on the server (saved data `qbotics:tablets`), under an ID on the item.
  Players' games know a tablet screen by a made-up block position at y = 2047, above any
  world's height, so display sending, drawing and touching work unchanged.
- **Traffic:** only the holder is sent the screen, and only the selected tab's. Records unseen
  in any player's inventory for 30 days (real time) are dropped.
- **In hand:** a special item renderer draws the screen on the model's glass in first and
  third person; elsewhere (inventory, ground, frames) the plain model.

---

## 11. Peripherals and mod integration

> **Priority:** a dedicated peripheral API and integrations with other mods are the **lowest priority** in the project. A well-made mod shouldn't need them: item, fluid and energy logistics through the loader's standard APIs cover most use cases. First come the mod's own logistics blocks (§11.0), which work with any inventory through the loader's standard item-handling API, so they cover most mods' chests and machines without dedicated adapters.

### 11.0 Logistics network (first-party, earlier)

Blocks joined by the mod's own **cables** form a **network**. A computer on the network runs it: any computer cabled in can see and command every item block on the same network, and any block can use items from any Input on it.

```
 [Computer]══cable══[Item Serial Bus]↔ chest
                 ║
                 ╚══cable══[USB]↔ machine (items, fluid and energy)
```

> **Changed 2026-11-12, decided by the project owner after playing:** Input and Output are gone. A
> Serial Bus does both of their jobs and more, and three look-alike plates confused new players.
> - There is one plate per medium: the **Item Serial Bus** (`bus`), **Fluid Serial Bus** (`fluid_bus`)
>   and **Energy Serial Bus** (`energy_bus`), each crafted from seven cables (`CCC` / `HCX` / `CCC`) with
>   a hopper on the left of the middle row and, on the right, a dropper, a bucket or a block of redstone.
> - The **Universal Serial Bus (USB)** (`usb`, royal purple) is the three combined, shapeless. It carries items,
>   fluid and energy: on its own it acts as the item bus; `usb.fluid` and `usb.energy` are views
>   of the same plate (addresses `mac@fluid`, `mac@energy`); in `move` it takes the medium of the
>   other ends; between USBs only, the name moved decides (a fluid name moves fluid), else items. `net.bus()` and its fluid and energy versions find a USB by name; with no key they
>   prefer a plain bus.
> - No direction setting: every bus works both ways.
> - Breaking change, accepted by the project owner: Input and Output items are gone. Plates placed
>   as Input or Output load as Item (or Fluid, Energy) Serial Buses. `net.input()`, `net.output()`
>   and their fluid and energy versions are removed.
> - Text below that mentions Input and Output plates is history: read "Serial Bus".

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
> - Text and examples below that use `extract` and `insert` describe the first API. It was replaced on 2026-11-11 by sources, destinations and `move` (§11.0.5); `docs/reference.md` is the current description.
>
> **Fluid and energy (built 2026-11-10):**
> - **Fluid Interface**: a full block with four tanks of four buckets (4,000 mB) each. A tank takes any fluid while empty, then only more of the same. A bucket fills or empties it by hand.
> - **Energy Interface**: a full block holding 2,000,000 FE (the unit mods share, also called RF).
> - Both are open to other mods' pipes and cables through the loader's standard fluid and energy APIs, and the plates reach other mods' tanks and machines the same way.
> - **Fluid and Energy versions of Input, Output and Bus**: the same plates and shapes, with a coloured collar and face ring: white for items, blue-grey for fluid, red for energy.
> - In Python the device kinds are `fluid_interface`, `fluid_input`, `fluid_output`, `fluid_bus` and the same four after `energy_`, with matching `net.fluid_bus()`-style lookups. Fluid amounts are millibuckets (1,000 is a bucket); energy amounts are FE. One call moves at most 16 buckets or 2,000,000 FE.
> - **Aiming without a name (built 2026-11-10).** `extract`, `insert`, `count` and `amount` take an item or fluid name, or none at all, and can be narrowed by `face=` and `slot=` (a slot or tank number, from 0, as `contents()` lists them). A `Face` or a `Slot(n)` can stand where the name would go: `bus.extract(Face.BOTTOM)` takes whatever a furnace has smelted, `bus.extract(Slot(0))` whatever is in the first slot. `dest_slot` and `source_slot` pick the Interface's slot or tank. With no name the default amount is everything there; with a name it is 1 item or one bucket.
> - **Filters in place of names (built 2026-11-10).** Wherever a program names an item or a fluid it can write a filter instead, in the words of the JEI mod's search box, which many players know: `@create` (everything from a mod, by ID or name; part of either is enough), `#minecraft:logs` or `$minecraft:logs` (a tag; `#logs` tries `minecraft:` then `c:`), `*_ore` (wildcards on the name), several words that must all hold (`@minecraft *_ore`), `-word` for "not", and `a | b` for alternatives. A bare word such as `coal` is still that one item. Filters work in `extract`, `insert`, `count`, `amount` and in an Interface's slot filters (`set_slot_filter(0, "#minecraft:logs")`). Not taken from JEI: searching by the name shown in tooltips, by tooltip text or by colour, because the server doesn't have players' translations.
> - Built 2026-11-11: the Fluid and Energy Interfaces' screens. A tank filter (one fluid) is set by clicking its socket with a bucket of the fluid, or from a program; each tank has a bucket slot that empties full containers into it and fills empty ones. The Energy Interface has a charge slot and a drain slot for batteries. See `docs/reference.md` §6.6.
> - Not built: flow rates or limits per tick, fluids that carry extra data (they are handled by plain kind only), and any screen for the two stores (right-click shows their contents as a line of text).
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
- **Events:** built 2026-11-11 as `for change in bus.watch()` (no `async`: the engine has no asyncio, and a plain loop is easier for learners). The server keeps a snapshot per watch and returns only the differences, so nothing is missed between looks; a watch expires after a minute unused, 16 per computer. Works on every readable device: interfaces, inputs, buses, fluid, energy and experience. See `docs/reference.md` §6.4b.

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

### 11.0.1 World plates: Logic Pin, Placer and Breaker (built 2026-11-11)

> **Status (2026-11-11): built**, with a game test (`world_plates`) and a demo on the test stage. Player-facing details: `docs/reference.md` §6.10. The open questions below were settled with these defaults, all open to change:
>
> - **Pin power:** an output pin powers the block it points at strongly (as a lever does), so it reaches through that block. One pin either reads or drives, not both; it reads only as an input. Redstone dust joins to a pin and to no other part of a cable.
> - **Pin events:** `wait_for("rising" | "falling" | "change" | "block")`. The server counts each event as it happens, so a program polling for them misses nothing.
> - **Breaker speed:** the real speed of the right unenchanted netherite tool. No energy cost and no per-player caps yet. No Fortune or Silk Touch.
> - **Placer:** places by using the item on the space as its owner would, so placement rules, block facing and protection all apply. With no item named, it places the first block it finds. Pouring uses the fluid's bucket, so water evaporates in the Nether as it would for a player.
> - **Recipes (placeholders):** four cables around an observer (Logic Pin), a dispenser (Placer) or a diamond pickaxe (Breaker).
> - **Cables became solid to fluids**, so water and lava flow around them instead of washing them away.

Three more plates. Like Input, Output and Bus they are items that attach to a cable, one per
side, pointing at the block next to that side, and each is a device on the network with its
own name and addresses. Where the logistics plates move things between containers, these act
on the world.

| Plate | What a program does with it |
|---|---|
| **Logic Pin** | Reads and drives redstone on the side it points at, the way a microcontroller's pin does, and notices when the block there changes, the way an observer does. |
| **Placer** | Places a block, or pours a fluid, into the space in front of it. |
| **Breaker** | Breaks the block in front of it, or takes up the fluid there. |

#### Logic Pin

A pin is configured by the program, as on a microcontroller, so one plate covers every use:

- **Mode**: input (reads the signal arriving from the block it points at) or output (emits a signal into it).
- **Level**: 0 to 15, not only on and off, so analogue signals (comparators, daylight sensors) can be read and written.
- **Pulses**: an output can be set for a number of ticks and then drop back by itself, so a program can clock a Crafter or a piston without having to stay awake to switch it off.
- **Events**: an input can wake a waiting program when its level changes (rising, falling or any change), instead of the program polling.
- **Observing**: like an observer, it notices when the block it points at changes: a crop growing a stage, a door opening, a furnace lighting, a block being placed or removed. A program can read what the block is and its state, and can wait for the next change. Unlike an observer it can say what changed, not only that something did.

It is called a Logic Pin, not a redstone pin, because it does both jobs: redstone signals and watching blocks.

Sketch of the Python, to be settled when it is built:

```python
pin = net.pin("door")
pin.mode = "output"
pin.write(15)            # on, full strength
pin.pulse(4)             # on for 4 ticks, then off
pin.mode = "input"
level = pin.read()       # 0 to 15
pin.wait_for("rising")   # sleep until the signal goes from 0 to above 0

crop = net.pin("wheat")
crop.block()             # {"block": "minecraft:wheat", "state": {"age": "7"}}
crop.wait_for("change")  # sleep until the block or its state changes
```

A cable can carry up to six pins, which gives a computer several independent lines from one
block, in the way a microcontroller board has many pins. This also closes a gap found on
2026-11-11: a Bus can lay out a recipe in the vanilla Crafter, but nothing yet lets a program
send the redstone pulse that makes it craft.

#### Placer

- Places a block in the space in front of it, as if a player had placed it there.
- The block comes from the network's Interfaces, named or filtered the same way as in a transfer (§11.0).
- Also pours fluid: given a fluid, it takes it from a Fluid Interface (one bucket) and places a source block. There is no separate fluid version.
- Does nothing, and says why, if the space is already filled.

#### Breaker

- Breaks the block in front of it as the **highest tier of tool**: anything a player could break, at that tool's speed, but **never bedrock** (or anything else the game makes unbreakable).
- Holds nothing itself: **no slot and no buffer** (decided 2026-11-11, replacing the earlier one-slot idea).
- **The program must say where the drops go**: an Interface, or an Output or Bus (whose target then receives them). With no destination the Breaker does nothing and says why.
- Also takes up fluid: pointed at a fluid source, it removes it and sends a bucket's worth to a Fluid Interface. There is no separate fluid version.
- **Recipe must include a diamond pickaxe**, to match its strength.

How every drop is caught, including when a block gives several different items:

- Drops never fall into the world. The Breaker works out what the block gives, then hands those exact stacks to the destination, as an ordinary transfer does.
- It is all or nothing. Before breaking, it checks that **every** stack has somewhere to go. If any one doesn't (no slot open for it, or no room), the block is not broken and the error names the item that had nowhere to go. So a block that gives two different items needs a destination that takes both.
- The drops are decided once per block and remembered until that block changes. Without this a program could keep trying until chance gave it the drop it wanted (flint from gravel, say).
- A container is broken together with its contents: they count as drops and must fit too.
- Not caught: experience, and other blocks that fall as a result (a torch on the broken block, sand above it). Those behave as in the ordinary game.

#### Things to decide before building

1. ~~**A model built in code comes first.**~~ **Done 2026-11-11.** Each side of a cable was a block-state value, one of five (nothing, an arm, Input, Output, Bus), giving 15,625 states; three more plate kinds would have made 262,144. Now the block state holds only the six arms (64 states), and every cable has a block entity that keeps its plates: each plate's kind, what it carries, and its identity. A model built in code (`CableModels`, NeoForge side) wraps the cable's ordinary core-and-arms model and adds a part for each plate its block entity lists, from the plate model files `block/plate_<kind>_<side>`. A new plate kind needs only its model files and its kind. Plates saved by earlier versions are not carried over: those cables come back bare.
2. **Protected land.** Settled: see §11.0.3. A Breaker and a Placer act as their owner.
3. **Limits.** How fast a Breaker works (the tool's real speed is proposed), whether breaking and placing cost energy from an Energy Interface, and per-player caps (§5, §15).
4. **Enchantments.** With no tool slot, a Breaker has no Fortune or Silk Touch. Whether that is wanted later (as separate plates, an upgrade, or a setting) is open.
5. **Pin strength and direction.** Whether an output pin powers only the block it points at or also provides strong power through it, and whether one pin can be both read and driven.
6. **Recipes** for the Pin and the Placer.

### 11.0.2 Screen access: a Bus working a block's screen (built 2026-11-11)

> **Status (2026-11-11): built** and tested on the crafting table, smithing table, grindstone, stonecutter and loom (game tests `screen_access`, `vanilla_screens`; a demo on the test stage). Buttons (the stonecutter's and loom's choices) are pressed with `screen.press(n)`. Other mods' screens are expected to work but not yet tried. Paying experience (anvil) is not built. Decided by the project owner: this is a function of the Bus alone. Player-facing details: `docs/reference.md` §6.8.
>
> **As built, differing from the rules below:** leftovers that no Interface will take are **dropped beside the Bus** rather than keeping the screen open, because a screen can't be held open forever (a stopped program, an unloaded chunk) and a drop is the one place they are certainly not lost. A screen unused for a minute closes itself. `put` names its `sources=`, and `take` its destinations, as `move` does.

**Why.** A plate reaches a block's contents directly when the block keeps them in the world
and offers them through the loader's standard access (§11.0). Some blocks don't. A crafting
table, an anvil or a smithing table holds nothing: its slots exist only in the screen of the
player who has it open. Many other mods' machines are the same. The aim is not crafting for
its own sake but being able to work any block whose slots are only on its screen.

**How.** The Bus opens the block's screen on the server as a stand-in for its owner (§11.0.3)
and lets the program work the slots of that open screen, by the screen's own rules. A result
slot fills in exactly as it would for a player.

```python
with bus.open_screen() as screen:        # opens the crafting table's screen
    screen.put(Slot(1), "oak_planks")    # from an Interface
    screen.put(Slot(4), "oak_planks")
    sticks = screen.take(Slot(0))        # the result, into an Interface
```

Rules:

- **Bus only.** Input and Output plates don't get it. Direct access stays the normal way; screen access is for blocks that offer nothing else.
- **A session with an end.** Items in screen slots exist only while the screen is open. When the session closes, whatever is left goes back to an Interface. If it can't all go back, the session does not close silently: nothing is ever lost or dropped.
- **Best effort.** It goes through the same door a player uses, so most slot screens should work, including other mods'. Where a mod refuses stand-in players or does part of its work in the player's own game, the call fails with a message. That is accepted.
- **Slot numbers are the screen's.** A screen can hold several inventories (a crafting table's grid and its result), so slots are numbered across the whole screen, not within each. The probe's Slots mode must show these same numbers on such screens.
- **Experience:** a screen that charges levels (anvil, enchanting table) is paid from a source the program names; see §11.0.4.

First targets, to prove it: crafting table, stonecutter, loom, smithing table, grindstone.

### 11.0.3 Acting for the owner: stand-in players and land claims (built for screen access)

> **Status (2026-11-11):** the stand-in is built and used by screen access: a NeoForge stand-in player per Bus, with the owner's ID and (when the server knows it) name; never a lookup over the internet. Not yet tested against FTB Chunks.

Screen access, the Placer and the Breaker all do things only a player normally does. Each
does them as a **stand-in for the plate's owner**: an invisible player on the server that
carries the owner's identity (the same player ID and name) and stands at the plate.

- **Same rights as the owner, no more.** Whatever protection applies to the owner applies to the plate: where the owner couldn't build, break or open a block, neither can the plate, and the program gets an error that says so.
- **Best effort across mods.** The stand-in is made the standard way for the loader, so mods that recognise stand-in players treat it as one. A mod or server that blocks stand-in players blocks these plates too. That is accepted.
- **FTB Chunks comes first.** It is the claim mod most modpacks use, so it is the one to test against and keep working. It has three team settings for stand-in players: allow all of them, allow named ones, and allow those carrying the ID of a real player (`allow_fake_players`, `allow_named_fake_players`, `allow_fake_players_by_id` in its source). Carrying the owner's ID is what lets the third setting treat a plate as its owner, so a team member's plates work inside that team's claims without the claim being opened to every machine. To confirm against the real mod when built, including what its defaults are.
- **A plate with no owner** (placed by a machine or a command) has nobody to stand in for, and does none of these things.

### 11.0.4 Experience: the Experience Interface, and paying for things that cost levels

> **Status (2026-11-11):** the Experience Interface block is **built**, and so is **paying for screens** (anvil, enchanting table) from an Experience Interface or liquid experience, with a game test for the Experience Interface. Liquid experience is untested: vanilla has no such fluid, so it waits for a play test with a mod that adds one. Player-facing details: `docs/reference.md` §6.8.

**The Experience Interface** is a full block that joins a network like the other Interfaces.

- It holds **real experience only**, counted in points so that what goes in is exactly what comes out. It has no practical limit.
- A player right-clicks it, types a number of levels, and presses Store or Take. Store All and Take All do what they say. Storing takes the player's top levels at what those cost; taking gives enough to climb that many levels from where the player is.
- It has a **lock**: private (the owner), the owner's team, or public. Only the owner changes it. A block nobody owns is open to all.
- Picked up, it keeps its experience in the item.
- Recipe: eight cables around a glass bottle.
- It is there so that plain Minecraft, with no other mods, has a way to pay for things that cost levels, and so that experience stored by mistake can be taken back.

**It never takes liquid experience.** Other mods' experience fluids each have their own rate. If a fluid could be poured in and taken out as levels, a fluid with a generous rate would be a way to print experience. So the block and fluids are kept apart.

**Paying for an interaction (built 2026-11-11).** When a program does something that costs levels, such as using an anvil through a Bus's screen access, it must **say where the experience comes from**:

| Source named by the program | What is spent |
|---|---|
| An Experience Interface | Real experience, in points |
| A Fluid Interface holding liquid experience | The fluid, at 20 mB a point |
| A Fluid Input or Fluid Bus pointing at a tank of liquid experience | The fluid, at 20 mB a point |

- Liquid experience is only ever **consumed** by an interaction. It is never turned into experience a player can take.
- 20 mB a point is assumed for every experience fluid for now. A mod whose fluid uses another rate will be over- or under-charged; accepted for now, and the place for a per-fluid setting later.
- The operation charges exactly the points it costs, as if the stand-in player had just enough levels.

**Experience a block gives out (left as the game does it, 2026-11-11).** A furnace keeps a hidden tally of the experience its smelting earned. Taking its output through a plate releases none, as with a hopper, so it builds up until a player takes from the output slot by hand or the furnace is broken. Taking the output through a Bus's screen releases it as orbs beside the Bus, where nobody may collect them. Decided by the project owner to leave unhandled for now. If wanted later: a screen's `xp` source could gather exactly the orbs its own action released, and the probe could show a furnace's tally.

### 11.0.5 Sources, destinations and `move` (built 2026-11-11)

**Why.** The first API moved things with `extract` and `insert` on a plate, always by way of an
Interface. Reading such a program, it was hard to tell where something went between one line
and the next, and the Interface in the middle was a requirement with no reason behind it.

**The shape now.** `extract` and `insert` are gone. A program describes the two ends and then
moves between them:

- `device.source(only=, without=, face=, slot=)` and `device.destination(...)` describe an end and do nothing by themselves, so they can be made once, named, pooled in a list and reused.
- `move(sources, destinations, what, count)` does the moving, directly between the ends. An Interface is an end like any other.
- Either side can be one end or a list. Sources are drawn on in order; each fills the destinations in order.
- Interfaces, Inputs and Buses can be sources; Interfaces, Outputs and Buses can be destinations. The Experience Interface is both.
- A destination's own lists are separate from an Interface's slot filters. The slot filters belong to the block and are what stop other mods' pipes; that is their purpose.

**When there isn't enough.** `move` returns how many moved, and none is not an error, so a loop that drains a chest ends quietly. `need=` asks for exactly that many or nothing, and raises if they can't be moved now, which is a useful first lesson in handling "not enough yet". `wait=` with `need=` pauses until they can: a factory that makes parts at different times can wait for each step before going on.

Details and examples: `docs/reference.md` §6.4.

**Recipes** (design agreed and built 2026-11-11; `docs/reference.md` §6.9).

A recipe is an object a program fills in once and then uses many times. It says which slots
of a screen get which ingredient, and which slots the product is taken from.

```python
blocks = Recipe(sources=parts, destination=store)  # where ingredients come from, where products go
for n in range(1, 10):                             # a crafting table's grid is slots 1 to 9
    blocks.ingredient("coal", Slot(n), amount=1)
blocks.result(Slot(0))                             # ...and its product appears in slot 0

if blocks.can_craft(4):                            # are there ingredients for four runs?
    with table.open_screen() as screen:            # table is a Bus on a crafting table
        blocks.craft(4, screen)                    # raises NetworkError if a run fails
```

- **`recipe.ingredient(what, slot, amount=1)`** adds one line to the recipe: this name or filter, into this slot, this many per run. The lines are kept in a dictionary by slot, so setting a slot again replaces it.
- **`recipe.result(slot, what=None, amount=None)`** names a slot the product is taken from. It can be called more than once, for machines that give several outputs. `what` and `amount` are optional: with them the run checks it got that; without, it takes whatever is there.
- **A result can have its own destination**: `recipe.result(Slot(3), "slag", destination=waste)`. That result goes there; results without one go to the recipe's `destination`. So one machine's outputs can be split between places. The entry in `recipe.results` holds it, as `{"what": ..., "amount": ..., "destination": ...}`, and it is one destination or a list, as everywhere else.
- **`recipe.can_craft(runs=1)`** answers True or False: do the sources hold enough of every ingredient for that many runs? The same item in several slots is added up. It does not look at the machine, so it can't know whether the recipe is a real one or whether the destination has room.
- **`recipe.craft(runs=1, screen)`** does the runs on an open screen: lays the ingredients into their slots, takes the product from the result slots to the destination, and repeats. If a run fails (an ingredient is missing, a result slot stays empty, the product has nowhere to go) it raises `NetworkError`; ingredients already placed go back when the screen closes, as for any screen session.
- **Working out how many runs.** Some jobs start from what there is ("make as many as the stock allows"); others start from what is wanted ("I need 7, and each run gives 2"). Both need the recipe to be readable, not only runnable:
  - `recipe.ingredients` and `recipe.results` are plain dictionaries, keyed by slot number. Each entry holds what was given: `{"what": "coal", "amount": 1}`, and for a result `{"what": "coal_block", "amount": 1}` with `None` where the name or amount was left out. A program can read them, loop over them, sort them, or change them.
  - `recipe.max_runs()` answers how many runs the sources can supply now (0 if none). `can_craft(n)` is the same question as `max_runs() >= n`.
  - `recipe.runs_for(count, slot=None)` answers how many runs give at least that many of the product: 4 runs for 7 items at 2 a run. It needs the result's `amount` to have been given; `slot` picks which result when there are several.
  - `recipe.craft(...)` returns what it took out: a dictionary of item name to how many, so a program can check what a machine with several outputs actually produced.

  ```python
  runs = planks.runs_for(7)                  # 2 a run: 4 runs
  runs = min(runs, planks.max_runs())        # ...or as many as there is wood for
  made = planks.craft(runs, screen)          # {"minecraft:oak_planks": 8}
  ```

- **Slot numbers are the screen's own**, as the probe's Slots mode shows them on that screen. That is why the result's slot is named and not assumed: a crafting table's product is slot 0 and its grid 1 to 9, and another mod's machine will differ.
- **Sources and destination** are given when the recipe is made. They are ordinary attributes, `recipe.sources` and `recipe.destination`, so a program can read or replace them at any time (`blocks.sources = other_parts`, `blocks.sources.append(chest.source())`), and either can be given for one call only (`blocks.craft(4, screen, destination=outbox)`). They are the same sources and destinations `move` uses: one or a list.
- **Opening and closing.** `with bus.open_screen() as screen:` is the recommended form because the screen is closed, and leftovers returned, even if the program stops with an error. `screen = bus.open_screen()` ... `screen.close()` also works.
- **Waiting.** `recipe.craft(4, screen, wait=True)` pauses for missing ingredients instead of failing, in the way `move(..., need=, wait=)` does.

**Much later: recipes written for you.** Typing out a recipe slot by slot is slow. A recipe viewer such as JEI already knows every recipe, so it could hand the player the code: look a recipe up there, press a button, and get a filled-in `Recipe` (ingredients by slot, results with their amounts) as text to paste into the editor. Not designed yet; noted here so the Recipe object stays simple enough for a tool to write, which is one more reason to keep it as plain data.

Open points: whether `can_craft` should also try the recipe in the machine (slower, but would catch a wrong recipe); and how filters that overlap between slots are counted (an oak plank can't be used for two slots at once).

### 11.0.6 Tunnels between buses (built 2026-11-12)

Decided by the project owner: two buses can be linked so that each end's face offers other mods' blocks the container behind the other end. It lets hoppers, pipes and generators feed through the network with no program, and is the way to bring power into a machine without `move` calls.

- **Linking:** with the Network Probe's Tunnels mode (click one bus, then the other; click a linked bus to see its end, again to close) or from programs: `bus.tunnel(other)`, `bus.untunnel()`, `bus.tunneled`, `bus.tunnel_end`.
- **Reach:** the same cable network, or two networks whose antennas both hold a Transport Upgrade and can reach each other by radio. Between networks the Transport per-second rate caps apply, counted only for transfers that are committed (a pipe's simulated transfer is free).
- **Media:** what both ends carry (a USB pairs with any bus; two USBs carry all three).
- **A tunnelled bus is still usable** by programs on its own target.
- **How:** each end keeps a link (the other end's identity and place), saved with the plate. The cable's block entity offers NeoForge item, fluid and energy capabilities on a tunnel end's side; each handler looks the other end up on every call (so pipes caching it never hold a stale container), routes are re-checked once a second, and a depth limit stops tunnels that lead into themselves. An end whose partner is missing or relinked forgets its link.
- **Tunnel light** (decided by the project owner): two pixels at the middle of each edge of a bus's front plate, the sides facing out from its axis (the face itself is against the target). Off with no tunnel, amber while the other end can't be reached, green when connected. The server checks loaded tunnel ends once a second and tells players' games when one changes; the colour is a tint (sources 6-11 of the cable's tint list, after the six ring colours).
- Not yet: tunnels between Qbots and buses.

### 17.9 Lessons: advancements for learning the system (built 2026-11-16)

Asked for by the project owner: a structure for a player learning the system, from print("hello world") to an
auto-crafting storage system, as achievements. Twenty vanilla advancements (data/qbotics/advancement/lessons, written
by make_assets.py from one table), in their own tab, each with a README in the bundled lessons repository (git clone lessons, its own folder /lessons
so a new programmer needn't follow nested paths; helpme stays the examples). One trigger,
qbotics:lesson, with a step name; hooks where the game already sees each step, so no lesson inspects code: program
output (hello world), events reaching a listening program (ServerComputer.pushEvent: pins, panels, web pages,
tablets), each call that worked (LessonHooks.afterCall: find, send to an external address, pin_set, display_open on
a monitor, sensor breaks, places and uses, Qbot moves and digs, anything reached behind another antenna), each move
of items (LessonHooks.beforeMove/afterMove: a list of destinations or a filter, a storage of four, a furnace's
product slot going down, a screen slot nothing may be put in, crafting fed from a storage of eight), and running
unattended a game day (startup, services). The owner of the computer gets it, while online; steps are told once a
game. Reference §7l.

### 17.8b The AI helper's scratchpad (built 2026-11-16)

Asked for by the project owner: the in-game AI helper should use the blocks in the world to inform its answers, with
a shell scratchpad of its own to test and find things out. A `python` tool runs a snippet on one of the player's
computers as the editor's scratchpad does (ServerComputer.scratchForAgent), without touching its files, stopped after
30 seconds. Look-only by default: the engine (Computer.lookOnly, LookOnly) answers only world calls that read (devices,
counts, contents, storage, sensors' lists and reads, env, watches, recipes) and refuses every other call and every file
write, so a look-only run needs no permission; with change "true" it may change things and the player is asked, as for
write_file and run. The helper's guide (helpme/AGENTS.md, in its system prompt) gained a map of the newer blocks and
modules, and its prompt tells it to check facts with the scratchpad before answering.
- Busy computers (asked for next): `computers` shows each one's running_program (ServerComputer records the command at
  its shell); `stop_program` stops it as Ctrl+C (twice if need be, leaving the Python prompt), remembering it;
  `start_program` starts it again in the background (by default what was stopped; a Qbot's program via its Run path).
  Both are asked of the player, and the prompt tells the helper to always put back what it stopped.

### 11.0.14 Sign writing, raw block data, and testing against real mods (built 2026-11-16)

- Block Sensor: `write_sign` (front or back, colour, glow; waxed signs refused, claims respected) and
  `block(pos, details=True)`: a block entity's saved data as JSON (read-only, capped at 32 KB).
- Entity Sensor: a lead is refused (it would tie the creature to the stand-in).
- Found on the way: items a sensor carried across antennas (drops, trades, use results, collect) skipped the
  Transport rate, which only move() charged. Now charged too (NetworkCalls.transportCheck/Spend/Room).
- Testing: remote sensors in the game tests; a mod test run (gradlew :neoforge:runModTests, QboticsModTests,
  neoforge/modtest-mods.txt) runs Qbotics against real mods from the modpack: Mystical Agriculture's crops with the
  Block Sensor, Animal Garden's crocodile with the Entity Sensor. Both pass.

### 11.0.13 Environment, and sensors reading more (built 2026-11-16)

Asked for by the project owner:
- **env module** on every computer (Environment.read, the "env" call): time (the dimension's world clock), day,
  moon (the MOON_PHASE environment attribute), weather and what falls here (biome precipitation, open sky),
  darkness (isDarkOutside), biome, light, position and dimension. A placed computer is its block, a Qbot where it
  stands, a laptop its player (the laptop's calls are already answered at the player). Watching is the program asking
  again every second and comparing (a tiny call), not a server event.
- **Block Sensor** reads biome, light, sign text (both sides, colour, glow, wax) and a lectern's book (title, author,
  pages, open page). Read-only; turning pages left out by the owner.
- **Entity Sensor** reads armour, off hand, item frames; an entity's inventory is an end of a move (an End with
  "entity", through the sensor that sees it), by NeoForge's entity item handles: automation for minecarts and boats,
  then the general one (horse packs; hands and armour for creatures and armor stands). Players never.

### 11.0.12 Block Sensor (built 2026-11-16)

Asked for by the project owner, for "dumb" block work such as an automated wheat farm, after weighing cost: a block that
reads, watches and works the blocks in a small box (BlockSensorBlockEntity, BlockSensorCalls). The box is six offsets
from the sensor, up to 11 per side and within 11 of it, starting as the one block above it (the owner's idea: the player
grows it to what they need); its screen moves each face. Watching is event-driven, not scanning: a mixin on
ServerLevel.updatePOIOnBlockStateChange (every real block change; the game's own POI hook) hands changes to sensors
that a program is watching (a set checked only when non-empty), which coalesce them per place for the watch. Reading
the whole box is on demand (blocks()). Acting as the owner's stand-in: break (WorldPlates.toolFor/breakTicks, drops to
destinations all-or-nothing or into the world), place (useItemOn at the spot), use (useItemOn, then useItem). Block
filters gained state words (`age=7`). Recipe: eight cables around an observer.

### 11.0.11 Collector (built 2026-11-16)

Asked for by the project owner: a block with a chest's worth of slots that collects what lies in an area like the
Entity Sensor's (both are AreaBlockEntity now: radius and height 1 to 20, one screen, Show area). Twice a second it
takes the dropped items it has room for, past their pickup delay, and experience orbs into an Experience Interface
on its cable network (none: orbs stay). An item store to programs (NetworkCalls.ownItems), a chest to hoppers and
pipes, and a chest's menu on right-click; sneak-right-click opens its area screen. Recipe: cables around a hopper
over a chest. An entity is in an area by where it stands (AreaBlockEntity.holds), not by its edges.
- Found on the way: the helpme Qbot remote example timed its one-a-second pictures by the clock; on a busy server a
  second of game time is longer, so a picture could be refused and the example crash (the occasional qbot_tablet
  test failure). qbot.snapshot() now waits for the game itself (wait=True), and the examples just call it.

### 11.0.10 Entity Sensor (built 2026-11-16)

Asked for by the project owner: a block that handles the entities in a box around it (radius each side and
height up and down, 1 to 20, default 8), with a screen to size it and a Show area outline. EntitySensorBlock /
EntitySensorBlockEntity; calls in EntitySensorCalls. Lists entities nearest first (a filter over entity types, in
the item filter language), reads each kind's data, and acts as its owner's stand-in: use (interactOn holding one
item from sources; results to a destination or back), attack (the weapon's attribute modifiers applied by hand and
the cooldown charged from the sensor's last swing, since stand-ins are never ticked; full swings wait), trade
(AbstractVillager offers; price checked and taken from sources, notifyTrade as the stand-in), and collect (items and
orbs, which made a separate collector plate unnecessary). Recipe: eight cables around a sculk sensor.
- Found on the way: a Qbot's attack had the same never-ticked stand-in, so after its first swing it hit at about a
  fifth of its strength, and a weapon in its hand added no damage. Fixed 2026-11-16: both use Swings (the weapon's
  modifiers put on by hand, the cooldown charged from the last swing; full swings wait).
- Decided by the project owner: no villager discounts (cured-zombie prices) for automated trading.

### 11.0.9 Dropper Plate (built 2026-11-16)

Asked for by the project owner: a world plate (Kind.DROPPER) that sends items out of the system as a dropper
or a dispenser would, shaped as an Item Serial Bus turned round (a small spout, widening back to the cable).
Recipe: four cables around a dropper. `drop(what, count, sources, dispense)` takes from sources; a `dispense`
setting kept by the plate chooses the mode. As a dropper: up to 64 of one kind a tick, thrown as one stack
(DefaultDispenseItemBehavior.spawnItem). As a dispenser: one item a tick, through the game's own dispenser
behaviour for it (DispenserBlock's registry, equipment and spawn eggs as the game picks them), run from a
dispenser state at the plate facing its way, with an unplaced DispenserBlockEntity to catch what a behaviour
hands back; that, and an item it had no use for, go back to the source. Into the world only, never into a
container in front (a bus does that). Not as the owner: a dispenser isn't a player.

### 11.0.8 Storage: many containers as one (built 2026-11-16)

Asked for by the project owner, who wants storage and logistics to be efficient for programs without
remaking Refined Storage or Applied Energistics (no storage network, no terminal to manage items by hand).
`net.storage(names)` (logistics.Storage) is a group of interfaces and item buses found by name pattern
(`chest*`), name or address, looked up again on every call. On the server it is one end of a move
(an End with group members, NetworkCalls.storageMembers), whose container is StoragePort: the members' slots
in order. One call counts, lists (`stock`) or moves across every member, instead of a tick per bus.

- Putting: a kind goes first to a member already holding it, then the first with room.
- Taking: members in order.
- A move between a storage and one of its own members leaves that member out.
- Members reaching one container (two buses on a chest, or a double chest's halves) count once (NetworkCalls.places).
- Unreachable members are skipped.
- At most 256 members, items only.
- **Sorting** (asked for by the project owner next): an item bus keeps a filter (BusFilter on the plate:
  names, and *matching*: kinds the container already holds), applied when things go into its container
  (NetworkCalls.target and wholeTarget wrap it; tunnels check the far bus), never when they come out, so
  strays put in by hand stay countable. A storage puts each kind into a member whose filter takes it
  (holding some first), then members with no filter (the overflow); `strays()` and `tidy()` find and
  move what a filter doesn't take. Matching was the owner's idea: cheap to set up (all matching but one
  overflow, a sample of each kind placed by hand), at the cost that a kind run out falls to the overflow.
- **Data filters** (asked for by the project owner): filters judge whole stacks, not just kinds
  (NameMatch.testStack). Short words `enchant=`, `effect=`, `damaged`, `undamaged`, `enchanted`, `named`; any
  word with `[...]` is handed to the game's ItemPredicateArgument (the /clear syntax) for anything else.
  `contents(details=True)` gives each stack's data, an exact filter in that syntax and its words (ItemData); the
  probe shows them with Shift and Ctrl+Shift+C copies the exact filter. `matching="exact"` on a bus compares data
  too (matching=True stays by kind: the owner wants fuzzy matching by default). `Filter` objects in Python combine
  filters with only/without and | & ~, compiled to the same words (alternatives of words), so they work anywhere.
- Inserting into another mod's or a vanilla container now tops up stacks before starting an empty slot
  (ResourceHandlerUtil.insertStacking); before, it filled slots in order.
- Not yet: watching a storage; a dense item vault (kinds and counts, no slots, awkward by hand) is the
  owner's idea for later.

### 11.0.7 Game interfaces: the player, the chat and commands (built 2026-11-14)

Asked for by the project owner: interfaces to the vanilla game itself. Three full blocks (GameInterfaceBlock, one block
entity type, GameInterfaceBlockEntity, whose kind comes from its block); programs reach them through GameInterfaceCalls.

- **Player Interface** (`player_interface`; eight cables around an Ender Chest): a bus on the player who placed it, in
  any dimension, while they are online. PlayerItemPort numbers slots as the game does (0-35 inventory, 36-39 armour feet
  to head, 40 off hand); it is a source and destination for `move`, counted and watched like an Interface
  (NetworkCalls.ownItems). A move naming no slot uses 0-35 only, so armour goes on or off only by slot; armour slots take
  what can be worn there, one at a time, and Curse of Binding holds. `info()` gives health, food, level, place, held item.
- **Chat Interface** (`chat_interface`; eight cables around a Book and Quill): hears every player's chat (NeoForge
  ServerChatEvent, handed to listeners on the server thread as `chat` events: `events.on_chat`), writes `[name] text` to
  everyone or one player (256 characters, 5 messages a second per interface), and runs commands as its owner with the
  owner's own permissions (their command source, output captured for the program; operators are informed as for any of
  the player's commands). Saying and listening work while the owner is offline; commands need them online.
- **Command Interface** (`command_interface`): a command block on the network: commands at the block, at the command
  block's permission level (GAMEMASTER), output captured. No recipe, no drop, unbreakable in survival; a GameMasterBlockItem,
  so only an operator in creative places it, found in the Operator Utilities tab. Honours `commandBlocksWork`.
- **Who may use them:** a Player or Chat Interface only for its owner's computers (the calling computer's owner,
  NetworkCalls.callerOwner); one with no owner works for no one. A Command Interface a player placed: only their computers,
  and only while they are still an operator. One with no owner (a command or a structure placed it): any computer on its
  network, as a command block answers any redstone.
- Commands from either return a CommandResult: `.ok`, `.result` (the comparator number), `.output` (lines).

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

### 14.6 Watching and stepping (2026-11-12)

The editor's current-line display and F8 Step need the engine to know which line of the
player's code is running. MicroPython's `sys.settrace` can't be used: the split interpreter
(`vm_split.c`) doesn't support it. Designs tried, measured with `engine/bench` (`throughput`
mode, best of several runs; loop is tight arithmetic, fib is calls, objects is lists and dicts):

| Design | loop | fib | objects | Notes |
|---|---|---|---|---|
| Shipped engine (before) | 225 ms | 120 ms | 390 ms | |
| A flag test before every opcode | +6% | +3% | 0% | Paid by every program |
| A list of running frames, pushed and popped per call | 0% | **+10%** | 0% | The stores on every call cost more than expected under Chicory |
| Two copies of the loop, chosen in `mp_execute_bytecode` | 0% | **+12-15%** | 0% | That function runs on every call; growing it slowed calls |
| **Two copies, the plain loop handing over to the traced one itself** (built) | **0%** | **+0-5% (noise)** | **0%** | |

The built design: `vm_loop.inc` is compiled twice. The **plain** copy does nothing extra. It
hands over to the **traced** copy only at its entry (one flag test per call) and on its rare
switch exit, raised from the time-slice checkpoint's "budget ran out" branch, so its hot path
is unchanged. The host asks for tracing through two spare bits of the budget it hands out
(watching: someone has the editor open; stepping), so nothing is written into a running engine
from another thread. The traced copy calls `vm_trace` before each opcode, which keeps the
stretch of bytecode on the current line and only decodes line numbers again outside it, and
notes the last line of the player's own code (files whose names start with "/"; libraries and
typed lines don't). Stepping calls the host (`line` import) at each new line, which holds the
program there. Watched cost: loop about 2x, fib about 1.6x, objects about 1.07x. Programs that
mostly sleep or wait on the world (Qbots) keep their pace (`patrol` mode: 1,016 ms unwatched,
1,017 ms watched). Engine size +5.4 KB (673,693 bytes).

## 15. Configuration reference

### 15.1 Server (`config/qbotics-server.toml`; the `[limits]`, `[boot]` and `[scheduler]` settings below that exist so far are built, see `docs/reference.md` §9b)

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
# self_chunkload: removed 2026-11-11. Qbots load chunks only with a Chunk Loader Upgrade (§8.5).
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
| 1b (as built) | **Built 2026-11-11, changed by the project owner:** hard drives instead of floppies, in a Drive Bay (§17.6) rather than a one-disk drive. A Hard Drive (4 MB, files kept under `media/<id>/` and travelling with the item) goes in one of a bay's four sockets; every computer on the bay's cable network sees it as `/net/<bay>/<drive>/`. Not yet: per-bay permissions (read-only, owner/team/public), mounting over an antenna, the duplicate-drive rule of §17.1, the programmer block | |
| 2 | `machine.Pin` redstone, events, timers. **Events and timers built 2026-11-11** as the `events` module (timers, pin edges and block changes, container changes, messages; one `run()` loop; the game pushes pin events and messages into a per-computer queue). Redstone goes through the Logic Pin (§11.0.1) rather than the computer's own sides; `machine.Pin` on the computer itself is not built | Embedded circuits |
| 3 | Ownership registry, limits, bricked state, boot queue. **Built 2026-11-11** for computers and antennas (`docs/reference.md` §9b): a server-wide registry saved with the world, per-type limits from `qbotics-server.toml` with operators exempt, bricked placement over the limit or by machines (dark screen, off the network, files kept), right-click reactivation (once a second per player), grandfathering, a boot queue for computers that were on (dimension, owner round-robin, distance, coordinates), a `shutdown` command, and `/qbotics limits`. The programmer block (§5.4) built 2026-11-11 for computers (§9e). Not yet: ranks and team pooling (§5.6), boot priority | Performance controls (early on purpose) |
| 4 | Text monitors, then control surfaces (§10.2). **Built 2026-11-11** (`docs/reference.md` §7c); limits and themes to follow | Displays foundation; cheap control panels |
| 5 | Qbots: movement, six-side sensing and actions, filtered inventory, tool use, self chunk loading, parking, programmer-block activation of inert Qbots (§5.4). **Iron Qbot built 2026-11-11** (§8 status); filters, parking, higher tiers to follow | Qbot experience |
| 6a | Antennas, brought forward: three range tiers, external addresses, ports, frequencies, messages between computers (§9.3). **Built 2026-11-10, not yet played** | Computers in separate bases can talk |
| 6 | Antennas (tiers, frequencies, access modes), in-game network, pseudo-DNS, in-game HTTP | Radio |
| 7a | Item blocks, brought forward: cable networks, internal addresses (§9.3), Input, Output, Bus with access faces, the `logistics` module. **Built 2026-11-10, not yet played** (§11.0) | A program can move items between containers |
| 7 | Rest of the logistics network: filter and face GUI, tier upgrades, throughput limits, change events; wireless bridging (§9.2); drive bays (§17.6). **Wireless transport (as remote devices) and chunk loading built 2026-11-11** as antenna upgrades | Inventory and machine automation |
| 7b | Fluid and energy logistics (§11.0) | Full factory automation |
| 7c | World plates (§11.0.1 to §11.0.3): the cable's model built in code, stand-in players acting for the owner, then Logic Pin, Placer, Breaker and the Bus's screen access | Programs that sense and change the world: redstone logic, auto-crafting, mining and building in place |
| 8 | Uplink (with all §13 protections), then Git import/export (§17.5) | Real-internet access; moving code between worlds |
| 9 | Web panels v1: engine evaluation, HTML/CSS v1 coverage, sync protocol, events | Flagship UI |
| 10 | Web panels v2: JS sandbox, `<canvas>`, grid, animations | Full web development |
| 11 | Built-in gateway, VS Code extension, package allowlist. **SSH gateway built 2026-11-12** (§9.5): VS Code works through existing SSH extensions; HTTP gateway, allowlist to come | Ecosystem |
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

> **helpme (built 2026-11-12, asked for by the project owner, for new players):** examples ship in the mod
> (`qbotics/helpme`, listed in `files.txt` by make_assets.py) and `git clone helpme` copies them to `/helpme` without the
> Uplink, as a repository whose commit is a fingerprint of the files: `git pull` updates them when the mod does, with the
> usual refusal over local changes; no push. Sorted by topic folders with a README each (decided: easier than one
> numbered list): basics, buses, redstone, displays (monitor, panel, web storage, web dashboard), qbots, network,
> internet, server. `git status` never needs the Uplink. Game tests run the bus examples against real blocks.
> **buses (2026-11-13, asked for by the project owner: buses are the most used feature):** replaced `storage`.
> Numbered beginner steps (1_count to 7_craft), then `factory/`: devices in one file, one job per file with a
> `start()` that does the job once and adds its handlers to `events`, `main.py` starting them all, and `safely()` so
> one job's error doesn't stop the rest. It needed two changes to `run` (rom/_boot.py `_start`): import looks in the
> program's own folder first (as in CPython), and modules imported from files are forgotten before each program
> (the computer's Python lives as long as the shell, so an edited module used to stay as first imported).

> **Status (2026-11-12): built** (`docs/reference.md` §7h), for GitHub: `git clone/pull/status/push` at the shell and
> `import git`. The game does the work through the uplink (all its checks) and writes the drive directly, so a repo may
> exceed the heap (cap `git.max_repo_kb`, 512 KB, 500 files). Clone/pull take the archive at the branch head; a
> `.gitqbot` file keeps the source, commit and file fingerprints for status. Pull refuses over local changes unless
> `--force`. Push builds blobs, a tree on the base commit's tree, a commit, and moves the ref without force; it refuses
> if the branch moved. Token GUI (`/qbotics git token`) with the warning; stored in `data/qbotics/git_tokens.json`,
> never given to Python. **Departure:** `git.enabled` defaults to true (it is only reads through the uplink, which is
> already on); `git.allow_push` stays off by default. Not yet: other hosts, `pcproject.toml`, merging.

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

> **Status (2026-11-11): built** for cable networks (`docs/reference.md` §9c), and as **package repositories over radio** (§9d): `pip install -r requirements.txt` copies packages from a drive on this or another network (read-only across the radio, same access rules as messages) into `/lib`, which is on the import path; packages may list their own requirements. Not yet: versions, writing to another network's drives: four hard-drive sockets; each drive is mounted as `/net/<bay>/<drive>/` (the design below had one folder per bay; with several drives per bay, each gets its own folder under it). Not yet: antenna mounting, per-bay permissions, file locking, version history.

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

### 17.8 The in-game AI agent

Asked for by the project owner (2026-11-13), for beginners: an AI agent in chat, on the player's own model (they use
local models such as Qwen through an OpenAI-style API), that can see the game, including a picture of the player's view,
and work on their code.

- **The agent runs in the player's game** (client/agent/ClientAgent), not the server: the model is on their PC, and its
  address and key never leave it. It speaks the OpenAI chat-completions API with tools, which Ollama, LM Studio,
  llama.cpp, vLLM and the hosted services all offer. Qwen-style tool calls written in the text are understood too, and
  `<think>` reasoning is dropped.
- **Tools that touch the world are done by the server, as the player** (agent/AgentTools, AgentPayloads.ToolCall): their
  computers and laptop (the same set ssh reaches, SshGateway.computersOf), a computer's devices (NetworkCalls.describeAll),
  list, read and write files, run a command (ServerComputer.exec: shown in its terminal, stopped after 30 s), and what
  the player looks at (the block, the probe's reading, what a plate points at, container contents). Done in the game:
  check_code (the editor's Checker), search_manual (REFERENCE.md by section), screenshot (the player's view, at most 1024
  px wide, as a PNG data URL; a picture goes in a user message after the tool's answer, since not every server takes one
  in a tool message). Pictures are dropped from the conversation after the turn.
- **Approval** (decided with the owner): reading is free; writing a file or running a command asks [Allow] / [Deny] in
  chat (clickable; the file shows on hover), unless the player turns on auto mode (/ai auto on). Vision only when asked
  ("@ai look ...", or the model's screenshot tool).
- **Chat**: "@ai ..." is caught before it is sent (ClientChatEvent) and only the player sees it. With the server's
  ai.share_replies (decided with the owner, for teacher and student sessions), the question and answer go to the server
  (AgentPayloads.Share) and are broadcast to everyone instead. ai.enabled off makes every tool answer that it is off.
- **A model the server offers** (asked for by the owner: a server owner sets up the connector for everyone, the setup
  hidden from players, above all any key; players set up their own where there is none). ai.url, ai.model, ai.key,
  ai.thinking, ai.vision in qbotics-server.toml. Since a key must not reach players, the server calls the model for them
  (agent/ServerModel, AgentPayloads.ModelRequest and ModelReply): a player's game sends only the conversation (messages
  and tools); the model, thinking and key are the server's, whatever else the request held. A short test when the
  server starts (/qbotics ai test again) decides whether it is offered; players learn only its name (Settings). Fair use:
  ai.requests_per_minute per player, ai.max_parallel overall. A bonus: players needn't reach the model themselves.
  Players are sent only the model's message (text and tool calls), never the rest of its server's reply (build, timings)
  nor its errors, which can name files and settings on the machine running it: those go to the server's log.
  The player's own model is used where none is offered, or by choice (/ai use own).
- **Game knowledge** (asked for by the owner: the agent didn't know recipes, especially modded ones; then also for
  developers, as a shell command and a Python call). agent/GameKnowledge reads what the server has loaded: items (by
  name, display name, or the mod's filters through NameMatch), their tags, fuel and food values, and recipes of every
  type, each turned back into its JSON by Recipe.CODEC, so other mods' machine recipes need nothing special. An index of
  which items (and #tags) each recipe makes and uses comes from walking that JSON (values under result/output keys are
  made, the rest used), built in the background when the server starts and again after /reload (1,552 vanilla recipes
  in 38 ms). The same answers go to the agent (find_items, item_info, recipes), the shell (recipe, item, items, with
  --json) and programs (the game module), through the game_* calls of NetworkCalls (computers and laptops).
- **A Qbot's radio** (decided with the owner: a Qbot is built from a Laptop instead of a Computer, being worth more, and
  has the laptop's Infinite antenna; it doesn't count against antennas_per_player, Qbots having their own limit): each
  Qbot has an external address of its own (RadioNetwork.takeAddress, kept in its block data as it moves; port 1001).
  It sends to antennas as a laptop does (NetworkCalls.radioFar: Infinite reach, every frequency, the far antenna's
  access applies), always from its own address. Computers reach it through their network's antenna (by that
  antenna's reach, the Qbot counting as Infinite), and Qbots from anywhere; only its owner's (access private).
  ComputerManager.qbotByRadio finds the running Qbot. web.image takes a picture's number, so a picture can be sent.
  Crafting a Qbot hands back the laptop's hard drives. helpme/qbots/remote/base (driver.py on the Qbot, controller.py at
  the base, sharing remote's index.html and style.css) drives a Qbot from a tablet by radio; the qbot_remote test runs
  driver.py by radio.
- **A tablet paired with a Qbot** (asked for by the owner): sneak and right-click a Qbot with a tablet pairs it with
  the Qbot itself (owner only), a tab keyed by the Qbot's drive ID where networks' tabs are keyed by their router
  antenna's (Tablets.pair for a Qbot; NetworkCalls.tabletKey). The drive ID, not the device ID: a Qbot gets a new
  device ID each time it is placed, so a Qbot broken, renamed on an anvil and placed again paired anew and its old
  tab could not be unpaired (found by the owner). Delete pressed twice in the tablet's view unpairs the tab showing
  (TabletPayloads.Unpair; the first press asks on the tab bar), for any tab left behind. The Qbot's program finds it with network().tablet()
  and draws there wherever it goes: tablet handles don't depend on where the computer is. helpme's
  qbots/remote is the controller on the Qbot alone, as the folder's main project for beginners (asked for by the owner:
  new players can drive a Qbot early): its startup.py draws the page and handles each button with events, copied to
  the Qbot's /startup.py so Run starts it. The qbot_tablet test runs it and presses Fwd.
- **Moving files by dragging** (asked for by the owner: more intuitive than mv for some): in the explorer a press waits for
  the release to open, and a move of 4 units starts a drag; the folder under it (a file's folder, or the top below the
  list) is outlined, a closed folder opens after resting on it, the list scrolls at its edges. A drop is the drive's
  RENAME, refused first in the editor if the name is taken there or a folder would go inside itself.
- **A Qbot's crafting table** (asked for by the owner, with its recipe: a crafting table on top, an iron axe and
  pickaxe either side of the computer, a chest below): qbot.craft(item, count) crafts by name from anything it carries,
  by the game's crafting-table recipes (the craft forms of §17.8, the kind it holds most of for a tag), checked and made
  by the game's own RecipeManager; qbot.craft() with no item crafts the top-left 3x3 of its 4x4 inventory as a grid,
  products into slot 7 first (shaded on its screen), then the other slots outside the grid, so pattern-only recipes work too. Each craft is done on a copy and kept only if
  the product and remainders fit. The shell has craft (a Qbot only). network/QbotCrafting; the qbot_crafting test.
- **Crafting for programs** (asked for by the owner, for a JEI-like auto-crafter on a Control Panel without hard-coded
  recipes): each recipe the game can describe gets a craft form (GameKnowledge.craftForm, from the recipe's own JSON):
  station, inputs by slot of that block's screen (Recipe's numbers), each with a filter usable as it is and the items
  that fit (tags expanded), output and slot, leftovers (crafting remainders), stonecutter button, cooking time.
  logistics.Recipe.from_game builds a Recipe from it. game.fuels lists what burns. game.plan (agent/CraftPlanner,
  decided with the owner: tedious for players and better on the server's threads than in a computer) works out the
  whole tree from what storage holds: steps in order, what is taken, what is missing, the most that could be made
  (doubling then halving), choosing among recipes by what is at hand and never following a cycle, with a work cap. All
  game_* calls are answered off the server's main thread (ComputerManager GAME_PREFIX). The agent gains plan (from
  what the player carries). The auto_crafting test plans torches from a log and coal and crafts them with from_game.
- **The editor's Chat panel** (asked for by the owner, also for teacher and student sessions): in the scratchpad's
  place, the game's chat (client/ide/ChatLog: what arrives, and the agent's own lines) with a box that sends as the chat
  box does. With the agent available (a model set and its server answering, looked at every 30 s), To: Chat | AI sends
  to the agent instead, with the editor's open file, cursor, selection, unsaved text and marked mistakes. A message for the
  AI is never sent to public chat when the AI is unavailable.
- Checked: the agent_tools game test (each server tool as a player, a computer not theirs, ai.enabled off); the agent
  screenshot section runs the whole loop against a stand-in model on a local port (look, screenshot, devices, a write
  waiting for [Allow], the answer, and shared replies coming back through the server).

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
| 2026-11-10 | Wireless bridging of logistics networks; one controller per bridged network | Superseded 2026-11-11 |
| 2026-11-11 | Transport across antennas: programs reach remote devices (`net.remote()`), networks aren't merged; free with per-antenna rate caps; needs a Transport Upgrade in both antennas | Decided |
| 2026-11-11 | Chunk loading is an upgrade (antennas now, Qbots later): loads the antenna's whole network up to a cap; per-player loader limit; works while the owner is offline unless the server says otherwise. Qbots don't load chunks without it | Decided |
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
| 2026-11-10 | `docs/reference.md` is the running player reference (commands, Python calls, filters, probe, limits), updated with every player-facing feature; it is the source for a later help page | Decided |
| 2026-11-10 | Finding an item's name: the probe adds it to item tooltips (Ctrl+C copies, Shift lists tags), and a wrong name in a program suggests the closest real ones | Built; to confirm |
| 2026-11-10 | Reaching a network from outside has two gates. First the antenna's access (private by default, team, public), which covers messages, device listing and login. Then, for login, the computer's own remote-login setting (owner by default, password, off) | Decided |
| 2026-11-10 | Each computer has a settings page, opened from the top left into the editor as JSON: name, remote login and password, antenna access and frequency. It is not a drive file, so programs can't read the password; only a salted hash is stored | Decided |
| 2026-11-10 | `ssh <address>`: one computer's terminal drives another's (terminal only; files and editor stay local). Identity is the owner of the computer typed at. Teams are the game's scoreboard teams | Built; to confirm |
| 2026-11-11 | Planned (§11.0.1): Logic Pin, Placer and Breaker as plates on cables. The Placer and Breaker also handle fluid, so there are no fluid versions. The Breaker breaks as the highest tool tier but never bedrock, holds nothing itself (the program names where the drops go, and it breaks only if every drop fits), and needs a diamond pickaxe in its recipe. The pin is a Logic Pin: redstone in and out, plus observer-like watching of the block it points at | Decided; not built |
| 2026-11-11 | Crafting tables, anvils and smithing tables hold nothing in the world, so plates can't reach them directly; the vanilla Crafter can be reached, and needs a Logic Pin to trigger it | Noted |
| 2026-11-11 | Planned (§11.0.2): screen access, a function of the Bus only: it opens a block's screen as a stand-in player and works its slots, best effort across mods | Built 2026-11-11 |
| 2026-11-11 | Planned (§11.0.3): screen access, the Placer and the Breaker act as a stand-in for the plate's owner, with the owner's rights and no more. Mods that block stand-in players block them; FTB Chunks is the claim mod to keep working | Decided; not built |
| 2026-11-11 | Experience Interface: real experience only, unlimited, stored and taken by levels at the block, locked to owner, team or public; eight cables around a glass bottle. It never accepts liquid experience | Decided; built |
| 2026-11-11 | A program that spends experience must name its source: an Experience Interface, or liquid experience (20 mB a point, assumed for all such fluids) in a Fluid Interface or behind a fluid plate. Liquid experience is only consumed, never converted | Built 2026-11-11 |
| 2026-11-11 | `extract` and `insert` removed. Things move with `move(sources, destinations, ...)` between ends described by `source()` and `destination()`; nothing has to pass through an Interface (§11.0.5) | Decided; built |
| 2026-11-11 | `move` returns how many moved (none is not an error); `need=` is all-or-nothing and raises; `wait=` pauses until the amount can be moved | Decided; built |
| 2026-11-11 | Planned: a `Recipe` object: `ingredient(what, slot, amount)`, `result(slot)` (one or several), `can_craft(runs)` as a True/False check against sources, and `craft(runs, screen)` on an open Bus screen, raising if a run fails. Needs screen access | Built 2026-11-11 |
| 2026-11-11 | A recipe result can name its own destination, to split a machine's outputs | Built 2026-11-11 |
| 2026-11-11 | Logic Pin, Placer and Breaker built (§11.0.1), with the defaults listed there: strong power from an output pin, `wait_for` on counted events, netherite-tool speed for the Breaker, and placeholder recipes. Cables are now solid to fluids | Built 2026-11-11 |
| 2026-11-11 | The cable's model is built in code: the block state keeps only the arms, and every cable has a block entity that keeps its plates (kind, medium, identity), which the model reads when drawing. Clears the way for the Logic Pin, Placer and Breaker (§11.0.1). Plates from worlds saved before this are dropped | Built 2026-11-11 |
| 2026-11-11 | Much later: a recipe viewer (JEI) hands the player ready-made `Recipe` code for a recipe | Idea; not designed |
| 2026-11-12 | Uplink, Git and VS Code: build the SSH gateway first (VS Code through SSH FS or SFTP, with real Git on the PC), then the uplink, then in-game Git with clone, pull **and push** (token in a GUI, server-stored, admin switch and warning as §17.5). SSH logins by key set in game; a config to reach every computer | SSH gateway built 2026-11-12 |
| 2026-11-12 | The Laptop (mid to late game): shapeless USB + Computer (its files copied in under /imported/) + Infinite Antenna. One laptop computer, shell and set of files per player, whichever laptop item is used, so two laptops never work on the same files apart. Runs while its player is online and carries a laptop, else held (paused), so absent or banned players' programs stop. Two hard-drive slots (sneak + right-click in the air) at /net/disks/disk1-2. Built-in Infinite antenna: ssh to any antenna in its dimension and Infinite ones in others, all frequencies, access rules apply; not on a cable network. Config laptops.enabled. Direct connection: right-clicking a computer or Qbot of yours with the laptop opens its editor and terminal (others: ssh). Ctrl+Z pauses and resumes in every terminal (through ssh too). Step 3: the editor marks the running line (green, amber while paused), Ctrl+Z says where it paused, F8 steps line by line; free for programs nobody watches (§14.6) | Built 2026-11-12 |
| 2026-11-12 | Qbots have no programming interface of their own, to raise the bar for programming bots (you rarely reprogram a machine on the fly): right-click opens its storage screen with Run (Resume while paused), Pause (a breakpoint: holds at the next step, sleep or returning world call) and Stop (kills it; Python restarts). The screen shows the state and the last two printed lines. Programs come from a Programmer, which also starts them; a running program restarts after a reload. The Qbot keeps its shell, so `ssh` still reaches it: a planned laptop will use ssh to program bots and networks on the fly. A **Log** button shows its whole output read-only, for debugging | Built 2026-11-12 |
| 2026-11-12 | Changed items and recipes are removed outright for now (one playtester); no legacy items | Decided |
| 2026-11-12 | Screens in two tiers, for early and mid game: LCD Screen (from Liquid Crystal Panels: glass panes, RGB dye, redstone; then redstone torches and panes) for the Computer and Text Monitor; LED Screen (from Light Emitting Diodes: glowstone and RGB dye; then gold and glass) for the Control Panel. The old Screen is removed | Built 2026-11-12 |
| 2026-11-12 | Tunnels between buses: each end offers other mods the container behind the other end; linked with the probe or `bus.tunnel()`; same network or across Transport antennas with rate caps; tunnelled buses stay usable by programs (§11.0.6) | Built 2026-11-12 |
| 2026-11-12 | Input and Output plates removed; the bus is the one plate per medium, renamed Item, Fluid and Energy Serial Bus (`CCC`/`HCX`/`CCC` recipes), plus the Universal Serial Bus (USB) carrying all three. No direction setting. Saved Inputs and Outputs load as buses (§11.0) | Built 2026-11-12 |
| 2026-11-10 | Recipes: Bus = Input + Output; fluid version = item version + bucket; energy version = item version + block of redstone (all shapeless) | Decided |
| 2026-11-10 | Breaking a cable while aiming at a plate takes off only that plate; aiming at the cable breaks everything | Decided |
| 2026-11-10 | Parts: Screen (glowstone dust, RGB dye, glass panes; makes 4) and Motherboard (copper, glass, green dye); the computer is 7 cables, a motherboard and a screen. Cable recipes stay | Decided |
| 2026-11-12 | Tiers: no Advanced computer; the Laptop is the mid tier (1 MB); the Server is late game (2 MB, 8 MB drive, uplink and four drive sockets built in, `servers_per_player` 2). RAM (an integrated circuit in eight gold) is its memory part. Recipe `ASA/RLR/ADA` with raw ancient debris | Built 2026-11-12 (services and chunk loading to follow) |
| 2026-11-12 | Shell scripts (`.sh`): one command a line, run from the script's folder, `#` comments, the first failure stops it; `echo`, `sleep`, `mkdir -p`, `/startup.sh` before `startup.py`. No variables or control flow (Python is for that). `sys.exit()` ends a program quietly (failure for a number or message); git's refusals are failures | Built 2026-11-12 |
| 2026-11-12 | The Motherboard is renamed the Integrated Circuit (item `qbotics:integrated_circuit`); same recipe and uses. Old motherboards in a world are lost | Built 2026-11-12 |
| 2026-11-18 | The offline devkit (asked for by the project owner: develop programs away from the game; a simulator was deferred): a "Qbotics Programs" folder for VS Code, made by `gradlew :devkit:devkit` from the same sources as the mod. Hints: the ROM modules themselves on Pylance's extraPaths (lookups annotated with their classes, `TYPE_CHECKING` importing display for the editor only), MicroPython's `time` as a stub, `execfile` in `__builtins__.pyi`, type checking "off" (the modules build objects at run time). The check (qcheck.jar, Java 25, found in the launchers' runtimes): the in-game Checker, then the real engine's compile (SyntaxCheck, shared with the editor's CHECK), then module names against the engine's own dir(); then needs read from the code: lookups on a network (named, unnamed, worked out at run time; constants at the top of a file read), storage patterns, Qbot, antenna and Transport Upgrades, Uplink, files read, the program's own modules (followed). Output file:line:col for VS Code's problem matchers. | Built 2026-11-18 |
| 2026-11-18 | The editor's Git panel (asked for by the project owner), on the left in place of the files, on the folder chosen there. Simple: the folder to a .zip on the player's PC, or a .zip or folder from the PC into a new folder at the top (named after it, made unique; never overwriting), through the editor's own file requests (READ_AT and APPEND added for files over 30 KB) and the PC's own dialogs (LWJGL tinyfd, off the game's thread but on macOS). What comes from the PC is checked first (client.ide.DriveTransfer): names as the drive allows them, no '..', drive letters or rooted paths, links not followed, 400 files and 2 MB at most (a .zip read only that far), clutter left out, one top folder dropped; the server's drive checks every path and its quota again. GitHub: the git module's calls made as the computer's program would make them (FileRequest GIT, ServerComputer.worldCall), status, pull, push, clone, and the token (the existing SetToken message; the dialog's input hides it, takes 200 characters and pastes). | Built 2026-11-18 |
| 2026-11-18 | Values while held on a step (asked for by the project owner): resting the mouse on a variable in the editor shows its value ("type: repr", cut at 600 bytes). The editor asks (FileRequest VALUE); the program's own thread answers while it waits held on the line (engine.Computer.valueOf, answerValues), calling the engine's mpy_value: a parameter of the function it is in (names from the function's header; cells unwrapped), else a name of its module. A function's other locals can't be named (MicroPython keeps them by number), so the box says so; keeping their names would mean changing MicroPython's compiler. While a value is shown (a __repr__ may run Python) the program isn't stepped or held. | Built 2026-11-18 |
| 2026-11-18 | The editor's Network panel (asked for by the project owner, in place of a pinned live view): a third right panel beside Chat and the scratchpad, one at a time, listing the devices on the computer's cables by kind (name or address, what it points at), from the device list the editor already asks for; Refresh asks again. A click shows the device over the editor (the inspect call, by address). Recipe help is left for a JEI integration, not Ctrl+click. | Built 2026-11-18 |
| 2026-11-18 | Less back and forth with the game while programming (asked for by the project owner: four of seven proposals). Device names the code uses that nothing on the network answers to are warned of as you type, with the nearest name (qbotics.ide.WorldHints.deviceProblems, from the device list the editor already has; storage patterns too; not for files using remote()). Inside strings that name items (values of move, count, ingredient and the like, and only=/without=), Ctrl+Space suggests every item and tag of this game, those the network holds first with counts (the inspect call on "*"). Ctrl+click on such a string shows what it matches and where the network holds those (client.ide.FilterLook; inspect kind filter). A dry run (Shift+F5; engine.DryRun): reads go through, move() is only tried (its dry flag: the trial sweep) and prints what it would do, and any other change, a screen included, stops the program saying what it was about to do; files aren't written. | Built 2026-11-18 |
| 2026-11-18 | Items in the editor's device and storage views show their program name and tags on hover (asked for by the project owner): the probe's tooltip lines (client.ItemNames), tags without Shift, and no probe needed while a view is open; Ctrl+C copies the name. Tags come from the player's game, which the server sends them to. | Built 2026-11-18 |
| 2026-11-18 | Ctrl+click on a storage in the editor's code (asked for by the project owner, for writing and debugging programs): net.storage("pattern") or a list, or a name set from one, shows its containers in the order it fills them (how full, the bus's filter, strays) and what they hold between them, the most first (the inspect call answers with the stock call's members and items). A click on a container opens it as a device over the list; Esc goes back (client.ide.StorageLook; the editor keeps a stack of looks). | Built 2026-11-18 |
| 2026-11-18 | Ctrl+click on a device in the editor's code shows the block it works over the editor (asked for by the project owner: looking up a block's slots and faces meant leaving the editor; Ctrl+click, as double-click selects). qbotics.ide.DeviceAt reads the finder call or a name set from one from the text; the editor asks with a FileRequest (INSPECT), answered on the server thread by the network's inspect call: the device, its slots, what each face of the block reaches (WorldlyContainer's slots for a face, else a count), and for a bus with an owner the block's screen (menu type, slot places, numbers as the probe gives them, items, accepts), opened for a moment through ScreenSessions and closed again unless a program had it open. The player's game makes the same menu type with the items and draws its real screen class over the editor (client.ide.DeviceLook), numbers in yellow and product slots in red; a screen that can't be made there (another mod's needing data from opening) is drawn as plain slots. A look-only snapshot (recommended to the project owner, not yet confirmed): a usable screen would reach into any container from anywhere. | Built 2026-11-18 |
| 2026-11-18 | Slots walk the same way everywhere (asked for by the project owner): a bus or USB, an interface, a Player Interface, a Collector, an open screen, an entity the sensor sees and a Qbot's inventory each have size() and slots() (every slot, empty ones too, {slot, item, count, accepts}) beside contents(). `accepts` is False where nothing can be put in (a product slot, a closed interface slot): ItemPort.mayPlace asks each kind of slot its rules (Slot.mayPlace, Container.canPlaceItem, ResourceHandler.isValid, an interface's filters), tried with what the slot holds and a set of common items. A storage has size() only. The old forms are gone: an interface's and collector's .slots number, a Player Interface's and a storage's slots() number, a screen's slots() of numbers, an entity's .slots. | Built 2026-11-18 |
| 2026-11-18 | How many pictures a Qbot takes a second is a server setting (the project owner's decision): `qbots.views_per_second`, 1 to 10, default 5 so servers whose settings file isn't edited aren't loaded down. Measured in the view-blocks scene (about 1,100 faces, 14 creatures): about 2 to 5 ms of the server's time a picture, about 4 ms to draw in a player's game (on the web worker). 10 is the top because screens update players every other tick. | Built 2026-11-18 |
| 2026-11-18 | start_here.py for programmers (asked for by the project owner): a short program, readable in about 20 seconds, that crafts torches from two storage chests (buses storage_1 and storage_2, a crafter on a crafting table) and shows devices by name, a storage, moves, faces, slots, filters and a screen. Given once per player (the owner's first editor of a computer, server or laptop; not Qbots, not others' computers), opened in the editor, recorded with the world (computer.StartHere); also in helpme. | Built 2026-11-18 |
| 2026-11-18 | The view takes over from the camera (the project owner's decision; not yet playtested): snapshot() is another name for view() (256 x 192 by default, four a second, 32 blocks); the ray-traced camera on the server, its pictures and their colouring in players' games are removed (Camera keeps the store of numbered pictures; Picture keeps the creature kinds). The help examples use view(): the camera example says which creature is in front (creature_at), the tablet remote takes 240 x 160, the size of its box (one picture pixel to a CSS pixel). | Built 2026-11-18 |
| 2026-11-18 | qbot.view() before it takes over from snapshot() (the project owner's list): players drawn with their skins (a stand-in player by their UUID, its skin read from the texture the game downloaded); items on the ground, item frames with their item, armour stands and paintings (the recorder also takes down items, block models and painted shapes, finding a shape's sprite in the game's sprite sheets and reading sprites from the picture the game keeps); the server sends each entity's position, the item it is or shows, a hung one's facing and a painting's variant. pic.creature_at(x, y) and pic.item_at(x, y) name what a pixel shows ("minecraft:cow", "minecraft:diamond"); block_at and counts still give kinds. Range 32 blocks, as snapshot(); even light kept. A view's message is about 5.6 KB in the test (was under 4). | Built 2026-11-18 |
| 2026-11-18 | qbot.view(): water and creatures as the game draws them (asked for by the project owner: both matter for a Qbot to see and understand; stained glass can wait). The server sends each fluid's faces and its surface's height at the four corners, worked out as the game's fluid renderer does (waterlogged blocks' water too); creatures carry their entity type, body yaw and whether young. Players' games draw sloped water, and each kind of creature from the game's own renderer: made once per kind on the render thread, standing and facing south, with its drawing taken down through a stand-in for the game's draw collector (models, textures, tints, layers), then turned and placed. Box if a kind can't be drawn so. Also: two camera tests put their pig in the test area's barrier ring, where it suffocated; cleared. | Built 2026-11-18 |
| 2026-11-18 | qbot.view() shows the blocks the game draws with code (asked for by the project owner: unseen blocks broke the view; a sign's text doesn't matter): chests (single, double, ender, trapped, copper), standing, wall and hanging signs, beds, banners (base colour), heads, shulker boxes, decorated pots, bells and conduits, posed and textured from the game's own entity models and its renderers' placements (client.display.EntityBlockModels); any other block with no model of its own is a box of its outline in its particle texture. Screenshot section viewblocks shows them, with stained glass, water and creatures as they are today, beside the game's own picture. | Built 2026-11-18 |
| 2026-11-17 | qbot.view() built out beside snapshot() (asked for by the project owner, to swap in once confirmed): the cone view with pitch, creatures (boxes by kind) and the program's per-pixel names drawn on the server as plain shapes (a grid of at most 128 x 96: bigger views name 2 x 2 squares, to keep the answer small); up to 256 x 192, four a second per Qbot; a Shot (Picture or View) under one run of numbers, so web.image takes either; players' games draw views on the web worker. Barriers, light and structure voids are seen through, as the camera does. | Built 2026-11-17 |
| 2026-11-17 | Cone view, a proof of concept beside the camera (asked for by the project owner): the server lists the blocks in the Qbot's view with a face turned to it and uncovered (camera.ConeView), and the player's game draws their model faces in software with a depth buffer (client.display.ConeRenderer), with even night-vision light. Measured on the screenshot scene: the list 2.5 ms on the server for 522 blocks (about 2 KB), against 27 ms for today's 128x96 picture; drawing 256x192 takes 8 ms in the player's game. Not used by programs or panels yet; screenshot section cone. | Prototype 2026-11-17 |
| 2026-11-17 | Qbots step and turn in 2 ticks (was 5 and 4), asked for by the project owner: a Qbot moves as fast as its program lets it, so efficient programs are rewarded. The wait is only the qbot library's sleep; a program calling the host directly can skip it. Kept on purpose (the owner's call): not a bug to close. | Built 2026-11-17 |
| 2026-11-17 | ssh opens the other computer's editor too (asked for by the project owner: ssh should work as a laptop connected to a Qbot does). During a session the editor's explorer, tabs, Save, Run (F5), F8 stepping, the line marker and the Settings page are the computer logged in to; this computer's tabs are put aside and come back after exit. Each file request names the drive the editor shows, and a request for another one (sent as a session began or ended) is refused. The scratchpad stays this computer's. | Built 2026-11-17 |
| 2026-11-17 | A broken Qbot keeps everything; Netherite Upgrade (asked for by the project owner). Broken, a Qbot's item carries its inventory, hand and upgrades (minecraft:container: 16 slots, the hand, the 4 sockets), as a shulker box does; it drops even when exploded, and in creative mode if it carries anything; a carrying Qbot can't go in a Qbot, shulker box or bundle. The Netherite Upgrade, a Qbot's only: its built-in tools are netherite with Silk Touch; a tool in its hand that can harvest the block wins, as it is; the item with the upgrade in it is fire resistant. Recipe: netherite pickaxe, a Silk Touch book (neoforge:components ingredient), redstone, circuit, gold nuggets. | Built 2026-11-17 |
| 2026-11-17 | Sense Upgrade (asked for by the project owner): a third upgrade, a Qbot's only (an antenna and a Server refuse it). A Qbot senses 1 block away without it (the six touching it) and 4 in every direction with it: inspect.at(forward, up, right), offsets from the Qbot as it faces, and inspect.scan(what), nearest first; inspect.reach. Recipe a placeholder (amethyst, a spyglass). | Built 2026-11-17 |
| 2026-11-17 | Qbot upgrades (asked for by the project owner): four sockets (a 2x2), one upgrade of each kind; the Transport Upgrade in a Qbot lets its program move items, fluid and energy between the Qbot (net.me: its slots, tanks and cell) and the network behind an antenna its radio reaches, which must have a Transport Upgrade too and let the Qbot's owner in. A Qbot is an end for move() on a cable network it stands beside. | Built 2026-11-17 |
| 2026-11-17 | Efficiency (three reviews of what the mod costs the game, and the project owner's choice of what was worth its risk): a network is walked again only when something changed at or beside one of its blocks, or a chunk at its edge loaded or unloaded (not every second, and not every network for any change anywhere), and devices are found by address from tables; waiting programs sleep until woken (Computer.wake) and no longer look every 50 ms; a Text Monitor's letters are prepared once and sent a batch per font sheet, and a Control Panel's draw list is kept; a Web Panel ignores updates that say nothing new, doesn't repaint for a script timer that changed nothing, and lets go of its picture, page and script when unseen (on a timer, keeping what it was sent); a changed screen is encoded once for all nearby players; tablets are saved on change; a Collector keeps its box and doesn't look for items when full; addresses are kept. Measured: engine benchmark unchanged (round trips 679k/s before, 700-720k/s after, same machine). Left out as not worth their risk: releasing idle threads, Python on first use, laptops at logout, the Qbot's step, the recipe index on first use. | Built 2026-11-17 |
| 2026-11-17 | Surprises (a review of what would surprise players, and the project owner's decision on each): a computer opens for its owner only, or its team or anyone by its "open" setting; "team" is FTB Teams too (by reflection); a computer stops when its area unloads and starts afresh (startup.sh, else startup.py) when it loads, and every new one has a startup.sh of notes; nobody's computer stays off; operators are exempt offline; Chat Interfaces can be switched off and speak as their owner; sensors and Collectors keep to land their owner may change (Boundaries), under the same setting as reading other players' computers; hoppers, pipes and cables reach a Qbot's slots, tanks and cell. For programs: no count moves everything; moving with no face goes through the bus's face (Face.ALL is the whole block; looking is still of the whole); on_pin(pin, function); attack leaves the entity as it is afterwards; rename refuses a name it would change; trade answers -1 when sold out; place answers False with .why; destination= everywhere; count() by exact name says so; hints for a count of 0 and for text compared with numbers. For beginners: the pager runs a command typed at it; Run does nothing while something runs; lessons 1, 2, 3, 4, 15 and 18 reworded. | Built 2026-11-17 |
| 2026-11-17 | Review fixes, second part (the smaller findings the project owner chose): a screen that changes kind drops the old view in players' games; tablets are sent whole again after a dimension change, respawn or join; a page's once-a-second limit is per player; a computer that left a screen's network loses its handle and its touches; a Block Sensor's watch follows the sensor through a chunk reload; a storage leaves out a Player Interface whose player is away; count reads the bus's own dimension; slot= is kept to by drop, place and the sensors; events.run() on a laptop; the editor reads module members from the modules (env too); long web clicks and AI requests are cut to fit their messages; the helper's conversation is not carried to another server, and stopped tools are answered; the uplink's time limit covers the reply's head; unloaded targets are said to be so, not loaded (buses, tunnels, sensors); a Qbot with no owner keeps its items; a right-clicked villager is not left trading. | Built 2026-11-17 |
| 2026-11-17 | Review fixes (a five-part code review asked for by the project owner, and his decisions on each): the settings file is registered COMMON so NeoForge no longer copies it (with the AI key) to every player; sensors.read_other_players_computers and sensors.attack_players (both off); "access" is not a look-only call; /ai url is checked and tried; drop_xp sends out only what it took; the Breaker asks for room before putting anything in; sensors settle the destination and owner before taking an item; equip refuses or swaps instead of overwriting; stepping and pause end with the program, Ctrl+C on a step stops before the line (engine: host_line < 0), F8 is not forwarded over ssh; the scratchpad hands the output watcher back; a Qbot's status shows the error, not the hint; a display sizes before it loads; tablets and displays save long text in pieces, 10 tabs with the oldest replaced; a refused widget change is undone; the editor's check respects the file limit, devices are asked for once, clean tabs reload, rename never replaces. | Built 2026-11-17 |
| 2026-11-16 | The AI helper and other mods (asked for by the project owner, best effort): tools mods (the loaded mods), mod_docs (their language files' explaining lines and Patchouli guidebook entries, searched; ModDocs, tested without a client) and controls (the player's key bindings), all read in the player's game. | Built 2026-11-16 |
| 2026-11-16 | For new players (asked for by the project owner): F8 with nothing running starts the open file held at its first line; the editor suggests the network's device names (and unnamed ones' addresses) inside net.bus("..."); devices shows what each plate points at; a name command names devices from the terminal or a script; each lesson has a "New Python here" box, and lesson 3 teaches stepping. | Built 2026-11-16 |
| 2026-11-16 | Lessons (asked for by the project owner): 20 advancements from hello world to a self-running factory, met by what programs do (one qbotics:lesson trigger, hooks on output, events, calls, moves and uptime), each with a README in helpme/lessons. §17.9 | Built 2026-11-16 |
| 2026-11-16 | The AI helper's scratchpad (asked for by the project owner): a python tool, look-only by default (enforced in the engine: only reading calls, no file writes) and so needing no permission; changing runs asked for. Its guide knows the newer blocks. §17.8b | Built 2026-11-16 |
| 2026-11-16 | Sign writing and raw block data on the Block Sensor; leads refused; sensors across the radio counted against the Transport rate; a mod test run against real modpack mods. §11.0.14 | Built 2026-11-16 |
| 2026-11-16 | Environment and sensor data (asked for by the project owner): an env module with time, day, moon, weather, biome, light and position, watchable; the Block Sensor reads biome, light, signs and lecterns; the Entity Sensor reads armour and item frames, and moves items into and out of entity inventories (minecarts, packs, armor stands). §11.0.13 | Built 2026-11-16 |
| 2026-11-16 | Block Sensor (asked for by the project owner): a box up to 11 x 11 x 11 near it, starting as the block above; reads its blocks, watches changes as the game makes them (no scanning), and breaks, places and uses items there as its owner. Block filters gained state words (`wheat age=7`). §11.0.12 | Built 2026-11-16 |
| 2026-11-16 | Collector (asked for by the project owner): 27 slots filled from the dropped items in an area like the Entity Sensor's, experience into an Experience Interface on its network; an item store to programs and pipes. qbot.snapshot() waits out its one-a-second limit in game time. §11.0.11 | Built 2026-11-16 |
| 2026-11-16 | Entity Sensor (asked for by the project owner): a box of up to 41 x 41 x 41 around it, a screen with Show area; lists and reads entities, and as its owner uses items on them, attacks with weapons from storage at full strength, trades with villagers, and picks up drops and experience. §11.0.10 | Built 2026-11-16 |
| 2026-11-16 | Dropper Plate (asked for by the project owner): a world plate shaped as a bus turned round, four cables around a dropper; `drop()` throws items out as a dropper, or with `dispense` set fires them through the game's own dispenser behaviours, giving back what a behaviour hands back. §11.0.9 | Built 2026-11-16 |
| 2026-11-16 | Data filters (asked for by the project owner): enchant=, effect=, damaged, undamaged, enchanted, named; the game's item syntax in any word with [...]; contents(details=True) and the probe's Shift view give a stack's data and exact filter; matching="exact"; Filter objects with only/without and \| & ~. §11.0.8 | Built 2026-11-16 |
| 2026-11-16 | Sorting (asked for by the project owner): item bus filters kept by the plate (names, and matching), honoured by moves, storages and tunnels, never on the way out; storages put each kind where a filter takes it before the overflow; `strays()`, `tidy()`; stacks topped up before empty slots. §11.0.8 | Built 2026-11-16 |
| 2026-11-16 | Storage (asked for by the project owner): `net.storage("chest*")` uses many buses and interfaces as one container, one call for count, contents and move, kinds gathered where they already are, double chests counted once. Before any dense storage block; not a storage network. §11.0.8 | Built 2026-11-16 |
| 2026-11-14 | Game interfaces (asked for by the project owner): a Player Interface (Ender Chest recipe) that is a bus on its owner's inventory, armour and off hand; a Chat Interface that hears chat as events, writes to it and runs commands as its owner; a Command Interface with no recipe (operators in creative, or commands) that runs commands as a command block. Owner-only, as in §11.0.7 | Built 2026-11-14 |
| 2026-11-13 | Tablets: Delete twice unpairs the tab showing; a Qbot's tab follows its drive, so it survives the Qbot being broken and placed again | Built 2026-11-13 |
| 2026-11-13 | A tablet pairs with a Qbot (sneak and right-click it): a tab the Qbot's own program draws on with network().tablet(), anywhere; helpme qbots/remote is now that, one startup.py on the Qbot (the radio version moved to qbots/remote/base) | Built 2026-11-13 |
| 2026-11-13 | A Qbot is built from a Laptop and has its Infinite antenna: its own radio address (qbot.address()), messages to and from it anywhere, owner only, not counted as an antenna; web.image takes a picture number; helpme/qbots/remote drives a Qbot from a tablet with its camera view | Built 2026-11-13 |
| 2026-11-13 | The editor's explorer: drag files and folders onto a folder to move them (a rename on the drive; tabs and open folders follow) | Built 2026-11-13 |
| 2026-11-13 | A Qbot crafts: qbot.craft by name or from its 3x3 grid, the shell's craft; its recipe gains a crafting table, an iron axe and an iron pickaxe | Built 2026-11-13 |
| 2026-11-13 | Crafting for programs: recipes in a ready-to-craft form (station, slots, options, leftovers), Recipe.from_game, game.fuels, and game.plan (the whole tree from storage, done by the server) (§17.8) | Built 2026-11-13 |
| 2026-11-13 | Items and recipes from this world's data, every mod's (§17.8): the AI agent's find_items, item_info and recipes; the shell's recipe, item and items (--json); the game module for programs | Built 2026-11-13 |
| 2026-11-13 | A model the server offers its players (§17.8): set up by its owner (ai.url, ai.model, ai.key), called by the server for them so the address and key never reach players, tested when the server starts (/qbotics ai, /qbotics ai test), per-player and overall limits; players set up their own where none is offered | Built 2026-11-13 |
| 2026-11-13 | The agent's thinking is limited (asked for by the project owner: Qwen took minutes thinking about simple questions): /ai think off, low (default), medium, high, sent as OpenAI's reasoning_effort (off: enable_thinking false through the chat template); a server that refuses them is asked again without. Measured on Qwen with llama.cpp: full 54 s, low 6 s, off 3 s | Built 2026-11-13 |
| 2026-11-13 | The editor's Chat panel: the game's chat beside the code, for everyone; To: Chat or AI when the agent is available, the AI getting the open file, cursor and selection (§17.8) | Built 2026-11-13 |
| 2026-11-13 | AI assistants, step 2: the in-game agent (§17.8): "@ai ..." in chat to the player's own model (OpenAI-style API, local or hosted), tools done by the server as the player, writes and runs asked first unless auto mode, look and screenshots when asked, ai.enabled and ai.share_replies (teacher and student sessions) | Built 2026-11-13 |
| 2026-11-13 | AI assistants, step 1 (asked for by the project owner): ssh name@server "command" runs one shell command (ServerComputer.exec: shown in its terminal, output collected, stopped after a time limit, "busy" at once if not at its prompt, keyboard-only commands refused) and returns its output and status; devices --json; helpme gains AGENTS.md (a guide for AI coding tools) and REFERENCE.md (this manual, copied in by the build). Step 2, an in-game agent on the player's own model, follows | Built 2026-11-13 |
| 2026-11-13 | Mistakes marked as you type (asked for by the project owner): qbotics.ide.Checker, plain Java, reads the file half a second after typing stops and explains brackets, quotes, colons, def brackets, indentation, = for ==, else if, print without brackets, misspelt names from the mod's modules (errors), and names never defined (warnings, with did-you-mean; one union of every name bound in the file, so no false alarms from scopes). Wavy underlines, red line numbers, a hover box and the status bar. When it finds no error, FileRequest.CHECK has the server compile the file with a small dedicated Python (CompileCheck), so the engine's own SyntaxError is marked too. Bench checks: each mistake at its place, none on correct code or any helpme example, 1,800 lines under 20 ms | Built 2026-11-13 |
| 2026-11-13 | Editor text in screen fonts: JetBrains Mono for code and the terminal, Inter for the rest (SIL OFL, subset to Latin, punctuation, arrows and box drawing, ~100 KB each). Minecraft's TTF provider with one font per zoom level (oversample = window pixels per editor unit), so glyphs are drawn pixel for pixel and stay sharp; size 10 makes the mono advance exactly the 6-unit cell. Ctrl+P switches back to the pixel font. Asked for by the project owner: the pixel font made > and ) look alike. `runClientShots -Pshots=editor` takes only the editor's pictures (26 s) | Built 2026-11-13 |
| 2026-11-13 | helpme `events`: a README explaining events (loops versus events, the three steps, what each handler gets, passing values, rules) and four examples, three needing no blocks (asked for by the project owner) | Built 2026-11-13 |
| 2026-11-13 | helpme `buses` (replaces `storage`): seven beginner steps and `factory/`, a program with one job per file started under events. `run` now imports from the program's own folder first and rereads imported files each run (rom/_boot.py `_start`) | Built 2026-11-13 |
| 2026-11-12 | Web Panel JavaScript: QuickJS's interpreter loop split into one function per opcode at build time, so the JVM compiles it: 3 to 6 times faster (§10.3) | Built 2026-11-12 |
| 2026-11-12 | Tablets: a handheld screen with an Infinite antenna; paired with networks by their antennas, a tab per network (the holder picks), drawn on with `net.tablet()` as any kind of screen, titled by the program, and shown on the tablet while held (§10.5) | Built 2026-11-12 |
| 2026-11-12 | Displays can lie on floors and ceilings: placed looking steeply down (pitch over 50 degrees) at a top face, or up at an underside, the screen lies flat with its top edge pointing away from the player. Block states gain six facings and `top`; flat screens join only screens lying the same way | Built 2026-11-12 |
| 2026-11-10 | Look: whitish iron with redstone red; the plates carry no arrows because their shapes show direction | Decided |
| 2026-11-10 | Network Probe: a hand tool that reads a device's name and addresses with one-click copy, and names the device. Names are the recommended way to address many devices in code | Built; to confirm |

---

## 19. Open questions

**Before starting**
1. ~~Mod ID and name.~~ Settled: Qbotics, `qbotics`.

**Settled by milestone 0**
2. ~~Virtual thread per computer vs. Asyncify; real performance numbers.~~ Settled: ordinary threads with a large stack, no Asyncify; numbers are in §14.5.

**Gameplay (confirm or decide when you reach the feature)**
3. Antenna link rule: is "the larger of the two ranges" right, and must *both* ends be Infinite tier across dimensions (§9.1)? Do antennas fitted as upgrades to computers and Qbots count against `antennas_per_player`? (Decided 2026-11-13 for Qbots: a Qbot's built-in radio doesn't count.)
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
9. ~~Initial control surface widget set and grid sizes per monitor size (§10.2).~~ Decided 2026-11-11: 4x4 cells per block; button, toggle, slider, gauge, lamp, label, readout, graph first (§10 status).
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
| **Liquid Crystal Panel** (a part; makes 3) | `PRP` / `SGP` / `PBP` | Glass panes; red, green and blue dye down the middle; redstone dust left of the middle row |
| **LCD Screen** (early tier; makes 4) | `TLP` / `TLP` / `TLP` | Columns of redstone torches, Liquid Crystal Panels, glass panes. Used by the Computer and Text Monitor |
| **Light Emitting Diode** (a part; makes 3) | `GGG` / `RNB` / `GGG` | Red, green and blue dye between rows of glowstone dust |
| **LED Screen** (mid tier; makes 4) | `ODL` / `ODL` / `ODL` | Columns of gold ingots, Light Emitting Diodes, glass blocks. Used by the Control Panel |
| **Integrated Circuit** (a part; was the Motherboard) | `CCC` / `LGL` / `CCC` | Copper ingots above and below; glass, green dye, glass between. The core of every computer |
| **Computer** | `CCC` / `CMC` / `CSC` | Cable in the shape of leggings, an integrated circuit in the middle, an LCD Screen at the bottom middle. Costs 7 cables, 1 integrated circuit, 1 LCD Screen |

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
| **Logic Pin** | ` C ` / `CXC` / ` C ` | `X` = observer |
| **Placer** | ` C ` / `CXC` / ` C ` | `X` = dispenser |
| **Breaker** | ` C ` / `CXC` / ` C ` | `X` = diamond pickaxe |
