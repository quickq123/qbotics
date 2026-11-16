# world: creatures, crops and things lying about

Blocks that reach out into the world around them:

| Block | What it does | Manual |
|---|---|---|
| **Entity Sensor** | Sees creatures, players, villagers and dropped items in a box around it (up to 41 x 41 x 41); uses items on them, attacks, trades, picks things up | 6.12 |
| **Collector** | 27 slots that fill themselves with what lies in a box around it (experience too, with an Experience Interface on its network) | 6.13 |
| **Block Sensor** | Reads the blocks in a small box (up to 11 x 11 x 11), says when they change, and breaks, places and uses items there; reads and writes signs | 6.14 |
| **Dropper Plate** | Throws items out, or fires them as a dispenser does; sends out experience | 6.10 |
| `env` (every computer) | Time, day or night, moon, weather, biome and light where the computer is | 7k2 |

**Their area.** Right-click a sensor or (sneaking, empty hand) a Collector for its size, and
**Show area** to see the box outlined in the world. A Block Sensor's box starts as the one
block above it: grow it on the screen, or with `set_area()` as `4_wheat_farm.py` does.

**Acting as you.** Using, attacking, trading, breaking and placing are done as the player who
placed the sensor, so they work where you could do them yourself. Reading needs no owner.

| File | What it shows | You need |
|---|---|---|
| `1_animals.py` | What an Entity Sensor sees; breeding and milking cows, shearing sheep | An Entity Sensor, buses named `chest_...` (a storage) holding wheat, buckets and shears |
| `2_guard.py` | Attacking monsters with a sword, and picking up what they drop, every few seconds | An Entity Sensor, a storage with a sword |
| `3_trader.py` | A villager's offers, and buying from it with emeralds from storage | An Entity Sensor by a villager, a storage with emeralds |
| `4_wheat_farm.py` | A whole farm: harvesting ripe wheat and replanting, as soon as each ripens, with a sign showing how it is doing | A Block Sensor under a field of farmland, a storage with seeds, a sign on the field |
| `5_collector.py` | Emptying a Collector into storage whenever it picks something up | A Collector and a storage |
| `6_dropper.py` | Throwing, firing and sending out experience | A Dropper Plate, a storage; an Experience Interface for experience |
| `7_day_night.py` | Reading the time and weather, and doing something when night falls | Nothing |

Run one:

    cd /helpme/world
    run 1_animals.py

The examples that keep going (`2_guard.py`, `4_wheat_farm.py`, `5_collector.py`,
`7_day_night.py`) use `events` (see `/helpme/events`) and stop with Ctrl+C. To have one
running all the time, start it from `/startup.sh`.

## Things worth knowing

- **Places are offsets from the Block Sensor**, `(dx, dy, dz)`: `(0, 1, 0)` is the block
  above it. A farm program keeps working when the farm is rebuilt somewhere else.
- **Watching a Block Sensor costs nothing while nothing changes**: the game tells it of each
  change. Wait for changes rather than reading the blocks again and again.
- **What it gets goes where you say:** `destination=` or `to=` (a bus, a storage, a list).
  Left out, it goes back where the item came from; a broken block's drops fall on the ground,
  for a Collector.
- **A creature that has left or died** raises `NetworkError` when used: read the list again.
- **On another network:** `net.remote("100.64.0.2").entity_sensor()` works across the radio,
  as any device does.
