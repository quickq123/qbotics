# A Qbot that digs a tunnel and comes back. This runs ON the Qbot.
#
# To put it on a Qbot: from a computer cabled to it,  ssh <the Qbot's name or address>,
# then  git clone helpme  there, and  run /helpme/qbots/tunnel.py  (Ctrl+C stops it).
# To make it the Qbot's own program (its Run button), copy it as startup.py:
#   cp /helpme/qbots/tunnel.py /startup.py
# Put a chest behind the Qbot first, if you want what it digs dropped there.
import qbot
from qbot import inspect, inventory, Side, QbotError

LENGTH = 10
dug = 0

for step in range(LENGTH):
    # Dig what is in front (and above, for a 2-high tunnel), then step in.
    if not inspect.front().air:
        got = qbot.dig()
        dug += sum(got.values())
    if not inspect.top().air:
        qbot.dig(Side.TOP)
    try:
        qbot.forward()
    except QbotError as e:
        print("Stopped:", e)
        break
print("Dug", dug, "blocks. Coming back.")

qbot.turn_around()
while True:
    try:
        qbot.forward()
    except QbotError:
        break                         # back at the start (or something is in the way)
qbot.turn_around()

# Into the chest behind it, if there is one.
if inspect.back().id.endswith("chest"):
    print("Unloaded", inventory.drop(Side.BACK), "items into the chest.")
