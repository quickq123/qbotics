# Job: smelting. Keeps every furnace fed with ore and fuel from the ores chest, and takes out
# what it makes.
import events
from logistics import move, Face
from devices import net, ores
from safely import safely

# Every furnace to look after, by name. More furnaces: add their names.
FURNACES = [net.bus("furnace")]

ORE = "#c:raw_materials"
FUEL = "coal | charcoal"


def tend(furnace):
    """Empties one furnace, then tops it up. Doing all three every time means a furnace is
    refilled when it USES ore, not only when new ore arrives."""
    # A furnace's faces: BOTTOM gives what it made, TOP takes what to smelt, a side takes fuel.
    move(furnace.source(face=Face.BOTTOM), ores)
    # Small amounts share the ore between furnaces instead of the first taking a whole stack.
    move(ores, furnace.destination(face=Face.TOP), ORE, 16)
    move(ores, furnace.destination(face=Face.LEFT), FUEL, 8)


def tend_all(change=None):
    for furnace in FURNACES:
        tend(furnace)


def start():
    tend_all()
    # Ore or fuel arrived: feed the furnaces. The filter keeps the ingots coming back from
    # the furnaces from setting this off.
    events.on_change(ores, safely("smelter", tend_all), ORE + " | " + FUEL)
    # A furnace changed (used ore or fuel, or made something): tend that one.
    # "furnace=furnace" fixes each lambda to its own furnace; without it, every lambda would
    # see the last furnace in the list.
    for furnace in FURNACES:
        events.on_change(furnace, safely("smelter", lambda change, furnace=furnace: tend(furnace)))
    # And every 30 seconds, in case a chest was full and has room again.
    events.every(30, safely("smelter", tend_all))
