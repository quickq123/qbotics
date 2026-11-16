# Remote control, the Qbot's side. This runs ON the Qbot (see README.md for how to get it
# there). It waits for orders sent by radio from controller.py (on a computer at your base),
# does each one, and answers with what happened and a picture of what it sees now.
#
# How it fits together: every order arrives as a message, and events.on_message runs
# obey() for it. The orders it knows are the ORDERS table below: a word, and the function
# that carries it out. To teach it a new order, write a function and add a line to ORDERS.
#
# A Qbot is built from a laptop, so it has an Infinite antenna: your base reaches it
# anywhere, at the address this prints when it starts (it shows on the Qbot's screen too).
# Only its owner's computers and Qbots can send it orders.
import time
import events
import network
import qbot
from qbot import inspect, Side, QbotError

print("Ready. My radio address is", qbot.address())

# ---- the orders -------------------------------------------------------------------------
# Each takes the order (a dict, for orders with options) and returns a few words saying what
# it did. A QbotError (something in the way, nothing to place) is caught in obey().

def dug(got):
    # {"minecraft:cobblestone": 1} -> "dug 1 cobblestone"
    if not got:
        return "dug nothing"
    return "dug " + ", ".join("%d %s" % (n, name.split(":")[-1]) for name, n in got.items())

ORDERS = {
    "forward": lambda order: qbot.forward() and "moved forward",
    "back": lambda order: qbot.back() and "moved back",
    "up": lambda order: qbot.up() and "moved up",
    "down": lambda order: qbot.down() and "moved down",
    "left": lambda order: "now facing " + qbot.turn_left(),
    "right": lambda order: "now facing " + qbot.turn_right(),
    "dig": lambda order: dug(qbot.dig(order.get("side", Side.FRONT))),
    "place": lambda order: "placed " + qbot.place(order.get("side", Side.FRONT)),
    "look": lambda order: "looking",
}

# ---- what it sees -----------------------------------------------------------------------
# A Qbot takes one picture a second at most: snapshot() waits until it may take the next.

def picture():
    return qbot.snapshot(96, 64)

# ---- obeying ----------------------------------------------------------------------------

@events.on_message
def obey(message):
    # An order is {"do": "forward"} (or just "forward"); options go alongside: {"do": "dig", "side": "top"}.
    order = message.data if isinstance(message.data, dict) else {"do": message.data}
    word = order.get("do")
    ok = False
    if word not in ORDERS:
        said = "I don't know the order '%s'" % word
    else:
        try:
            said = ORDERS[word](order)
            ok = True
        except QbotError as e:
            said = str(e)
    print(word, "->", said)
    # The answer: what happened, where it is now, and its view. Only the picture's number is
    # sent: the controller shows it on the tablet with web.image("cam", number).
    x, y, z = qbot.position()
    network.send(message.sender, {
        "ok": ok,
        "said": said,
        "picture": picture().id,
        "where": "%d, %d, %d facing %s" % (x, y, z, qbot.facing()),
        "front": inspect.front().id,
        "items": inventory_count(),
    })

def inventory_count():
    return sum(stack["count"] for stack in qbot.inventory.list())

events.run()
