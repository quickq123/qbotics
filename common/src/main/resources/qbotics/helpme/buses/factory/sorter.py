# Job: sorting. Everything that arrives in the inbox goes to the first chest whose rules take it.
#
# The rules are a table, not code: to add a chest, add a line.
import events
from logistics import move
from devices import net, inbox, store
from safely import safely

# One line per chest: (the bus's name, only, without)
#   only    - what the chest takes (names, patterns, #tags, @mods); None = anything
#   without - what it never takes; None = nothing refused
# Order matters: an item goes to the FIRST chest that takes it. Specific chests first.
RULES = [
    ("tools", ["#c:tools", "#c:armors", "#c:buckets"], None),
    ("ores",  ["#c:ores", "#c:raw_materials", "#c:ingots", "#c:gems", "coal", "charcoal"], None),
    ("food",  ["#c:foods"], ["rotten_flesh", "spider_eye"]),
]

# Made once, here, rather than on every sort. The store comes last and takes anything, so
# nothing is ever left stuck in the inbox.
DESTINATIONS = [net.bus(name).destination(only=only, without=without) for name, only, without in RULES]
DESTINATIONS.append(store.destination())


def sort(change=None):
    """Moves everything in the inbox to its chest."""
    moved = move(inbox, DESTINATIONS)
    if moved:
        print("Sorted", moved, "items")


def start():
    sort()                                              # what is in the inbox already
    events.on_change(inbox, safely("sorter", sort))     # then whenever it changes
