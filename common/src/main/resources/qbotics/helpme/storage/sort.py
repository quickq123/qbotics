# Sorting. Two chests, each with an Item Serial Bus, both on this computer's network.
# Name the buses "inbox" and "store" with the Network Probe. Things dropped in the inbox are
# moved to the store, except what you keep out; the program watches, so it sleeps until
# something arrives.
# Run it:  run sort.py   (Ctrl+C stops it)
from logistics import network, move
import events

net = network()
inbox = net.bus("inbox")
store = net.bus("store")

# The store takes everything but dirt and seeds (filters: names, tags, patterns).
KEEP_OUT = ["dirt", "*_seeds"]

def sort_now(change=None):
    moved = move(inbox, store.destination(without=KEEP_OUT))
    if moved:
        print("Moved", moved, "items. The store holds", store.count())

sort_now()                          # what is there already
events.on_change(inbox, sort_now)   # then whenever the inbox changes
print("Sorting. Drop things in the inbox chest.")
events.run()
