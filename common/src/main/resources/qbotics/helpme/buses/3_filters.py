# Where things may go. Four chests with Item Serial Buses, named "inbox", "ores", "food" and
# "store". Each chest is given rules; everything in the inbox goes to the first chest whose rules
# take it, and the store takes whatever is left.
# Run it:  run 3_filters.py
from logistics import network, move

net = network()
inbox = net.bus("inbox")

# A destination describes one end of a move: which chest, and what it takes.
#   only=    what it accepts (one filter, or a list)
#   without= what it never accepts
# Nothing moves yet: these are only descriptions, made once and used as often as you like.
ores = net.bus("ores").destination(only=["#c:ores", "#c:raw_materials", "#c:ingots", "coal"])
food = net.bus("food").destination(only="#c:foods", without=["rotten_flesh", "spider_eye"])
store = net.bus("store").destination()      # no rules: takes anything

# A list of destinations is tried in order: specific chests first, the catch-all last.
CHESTS = [ores, food, store]

moved = move(inbox, CHESTS)
print("Sorted", moved, "items")

# The same rules work as a source: take only ores back out of the inbox, say.
#   move(inbox.source(only="#c:ores"), net.bus("store"))
