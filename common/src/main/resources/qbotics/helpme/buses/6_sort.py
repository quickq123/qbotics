# Sorting, for as long as the program runs. Two chests, each with an Item Serial Bus, named
# "inbox" and "store". Things dropped in the inbox are moved to the store, except what you keep
# out. The program sleeps until something arrives.
# Run it:  run 6_sort.py   (Ctrl+C stops it)
from logistics import network, move
import events

net = network()
inbox = net.bus("inbox")
store = net.bus("store")

# The store takes everything but dirt and seeds (filters: names, tags, patterns).
KEEP_OUT = ["dirt", "*_seeds"]

# events.on_change calls its function with one value, the change. "change=None" lets the
# same function be called by hand too, with nothing, as below.
def sort_now(change=None):
    moved = move(inbox, store.destination(without=KEEP_OUT))
    if moved:
        print("Moved", moved, "items. The store holds", store.count())

sort_now()                          # what is there already
events.on_change(inbox, sort_now)   # then whenever the inbox changes

# A function that needs more than the change: wrap it in a lambda that passes the rest.
#   def send(change, where): ...
#   events.on_change(inbox, lambda change: send(change, "store"))

print("Sorting. Drop things in the inbox chest.")
events.run()                        # handles events until Ctrl+C
