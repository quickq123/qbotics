# helpme: examples to learn Qbotics from

You got these with `git clone helpme`. They are yours: read them, run them, change them.
When the mod brings new examples, `git pull` (in this folder) fetches them. If you changed
files, it asks first: `git status` shows what you changed, and `git pull --force` puts the
originals back.

Each folder is one topic, with a README of its own saying what each example does and which
blocks it needs. Go to a folder and run an example:

    cd /helpme/basics
    read README.md
    run hello.py

`read` lays a README out for this screen, a screenful at a time (Enter for more, q to stop).
`cat` shows the file as it is written, marks and all.

Open a file in the editor with `edit <file>`. Ctrl+C stops a running program.

| Folder | What is in it |
|---|---|
| `basics` | **Start here.** Python on a computer, files, and what is on the network |
| `buses` | Counting, moving, sorting, smelting and crafting with Serial Buses, step by step; then `factory`, a whole program with one job per file |
| `storage` | **Many chests as one:** counting across them, putting each kind away where it belongs, sorting with bus filters, and filters on enchantments and potions |
| `events` | **How to wait for things:** timers, chests, levers, messages, all at once, with `events` |
| `world` | **Farms and creatures:** the Entity Sensor (animals, monsters, villagers), the Block Sensor (a wheat farm), the Collector, the Dropper Plate, and `env` (time, weather, biome) |
| `redstone` | Redstone in and out with Logic Pins, and reacting to events |
| `displays` | Screens: a Text Monitor, a Control Panel, and Web Panels (HTML, CSS, JavaScript) |
| `qbots` | Robots: a Qbot that digs a tunnel, its camera, and driving one from your tablet |
| `network` | Computers sending each other messages |
| `internet` | The real internet (an Uplink), and git |
| `server` | Programs that run in the background on a Server |

**Names.** Many examples find devices by name, like `net.bus("chest")`. Name a device by
right-clicking it with the **Network Probe** and typing a name. Or change the name at the top
of the program to `None`, which uses the only device of that kind on the network.

**Stuck?** `help` lists the shell's commands. In the editor, hover over a function to see what
it does.
