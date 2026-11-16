# Drive your Qbot from your tablet. This whole program runs ON the Qbot.
#
# It puts a controller on your tablet: a picture of what the Qbot sees, and buttons to move,
# turn, dig and place. Press a button and the Qbot does it, then shows you its new view.
#
# A Qbot runs /startup.py when you press Run on its screen. Before you start (README.md
# has the steps):
#   1. Pair your tablet with the Qbot: sneak and right-click the Qbot with the tablet.
#   2. On the Qbot:  cp /helpme/qbots/remote/startup.py /startup.py   then press Run.
#   3. Right-click in the air with the tablet to open it.
# To change it, edit /startup.py (the copy) and press Stop, then Run.
#
# How it works, in three parts:
#   1. SET UP    find the tablet and show the page (index.html) on it.
#   2. ORDERS    a table: each button's word, and what the Qbot does for it.
#   3. EVENTS    when a button is pressed, events runs obey(), which carries out the order
#                and shows the new view. events.run() at the end waits for presses forever.
import time
import events
import logistics
import qbot
from qbot import inspect, QbotError

# ---- 1. SET UP ------------------------------------------------------------------------
PAGE = "/helpme/qbots/remote/index.html"   # the page with the picture and the buttons

tab = logistics.network().tablet()     # on a Qbot: the tablet you paired with it
tab.title = "Qbot"                     # the name on the tablet's tab
web = tab.web_panel()                  # the tab's screen, as a web page
web.show(PAGE)

# ---- 2. ORDERS ------------------------------------------------------------------------
# A button on the page says data-event="forward"; here "forward" says what to do. Each one
# gives back a few words to show under the picture.
# To add an order: add a line here, and a button in index.html with the same word.

def dug(got):
    # What qbot.dig() got, {"minecraft:cobblestone": 1}, in words: "dug 1 cobblestone".
    if not got:
        return "dug nothing"
    return "dug " + ", ".join("%d %s" % (n, name.split(":")[-1]) for name, n in got.items())

ORDERS = {
    "forward": lambda: qbot.forward() and "moved forward",
    "back": lambda: qbot.back() and "moved back",
    "up": lambda: qbot.up() and "moved up",
    "down": lambda: qbot.down() and "moved down",
    "left": lambda: "now facing " + qbot.turn_left(),
    "right": lambda: "now facing " + qbot.turn_right(),
    "dig": lambda: dug(qbot.dig()),
    "dig_up": lambda: dug(qbot.dig("top")),
    "dig_down": lambda: dug(qbot.dig("bottom")),
    "place": lambda: "placed " + qbot.place(),
    "look": lambda: "looking",
}

# ---- what it sees ----------------------------------------------------------------------
# A Qbot takes one picture a second at most: snapshot() waits until it may take the next.

def picture():
    return qbot.snapshot(96, 64)

def show(said):
    # The picture, then the words under it: what it did, where it is, what is in front.
    web.image("cam", picture())
    x, y, z = qbot.position()
    web.state.update(said=said, where="%d, %d, %d facing %s" % (x, y, z, qbot.facing()),
                     front=inspect.front().id.split(":")[-1],
                     items=sum(s["count"] for s in qbot.inventory.list()))

# ---- 3. EVENTS -------------------------------------------------------------------------
# obey() runs each time a button is pressed. event.event is the button's word.

def obey(event):
    word = event.event
    try:
        said = ORDERS[word]()
    except QbotError as e:
        said = "Couldn't: " + str(e)   # something in the way, nothing to place...
    print(word, "->", said)            # shows on the Qbot's screen too
    show(said)

for word in ORDERS:
    web.on(word, obey)                 # every button runs obey()

show("Ready. Press a button to drive me.")
print("On the tablet carried by", tab.holder)
events.run()                           # wait for button presses, for ever
