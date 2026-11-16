# Lessons: from hello world to a factory that runs itself

Twenty lessons, each a small job for a program. Do one and its **advancement** comes up: press
**L** and open the **Qbotics Lessons** tab to see them all, which are done, and what comes next.
The advancement comes from what your program *does* in the world, so any program that does the
job counts, whether you wrote it from scratch or changed an example.

Each lesson is a folder. Go into one with `cd`, and read it with `read`:

    cd /lessons/01_hello
    read README.md

Each lesson says what it teaches and what blocks you need, shows the idea in a few lines, and
then sets **your turn**: the job that earns it. The first five each have a `start.py` that works
as it is: run it with `run start.py`, then open it with `edit start.py` and change it. Write
your programs in the lesson's folder, beside its README.

Some lessons point at worked examples in `/helpme`. Get those with `git clone helpme`.

## The lessons

| # | Lesson | You learn |
|---|---|---|
| 1 | `01_hello` | Running a program |
| 2 | `02_network` | What is on your network, and finding a device by name |
| 3 | `03_first_move` | Moving items between chests with `move` |
| 4 | `04_sorting` | Sending each thing to the right chest |
| 5 | `05_events` | Waiting for things to happen, instead of checking again and again |
| 6 | `06_redstone` | Logic Pins: sending and reading redstone |
| 7 | `07_smelting` | Furnaces: what goes in which side, and taking out what they made |
| 8 | `08_crafting` | Crafting tables and `Recipe` |
| 9 | `09_workstations` | Stonecutters, smithing tables, grindstones, looms, anvils |
| 10 | `10_storage` | Many chests as one |
| 11 | `11_monitor` | Text Monitors: a screen for your base |
| 12 | `12_panel` | Control Panels: buttons and switches |
| 13 | `13_web` | Web Panels: pages in HTML |
| 14 | `14_tablet` | Tablets: a page in your hand |
| 15 | `15_always_on` | Programs that start by themselves and keep running |
| 16 | `16_radio` | Antennas: other computers and other networks, far away |
| 17 | `17_service` | Servers: programs in the background |
| 18 | `18_farmer` | Sensors: farms and animals |
| 19 | `19_qbot` | Qbots: robots that move and dig |
| 20 | `20_factory` | All of it together |

They are in order, but not a line: after lesson 5 they branch (displays, redstone, running on
its own), and after lesson 10 (sensors, the factory). The advancements screen shows the tree.

## Things worth knowing

- **Advancements go to whoever placed the computer** (for a laptop, its player), and only
  while that player is in the game. A job done while you are away counts the next time it is done.
- **Stuck?** Each lesson points at a worked example elsewhere in /helpme. The AI helper in chat
  can explain things too, if your server has one (`/ai`).
- **The manual** is `/helpme/REFERENCE.md` (after `git clone helpme`); each lesson names its sections.
- **New lessons** come with the mod's updates: `cd /lessons` and `git pull`. If you changed files
  it asks first; your changes are yours to keep or copy elsewhere.
