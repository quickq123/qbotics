# Remote control with no base computer: the Qbot itself puts the controller on your tablet.
# This runs ON the Qbot. It is driver.py and controller.py in one program: a button press on
# the tablet runs obey() right here on the Qbot, which moves and shows what it sees.
#
# Pair your tablet with the Qbot first: sneak and right-click the Qbot with the tablet. The
# Qbot's tab appears on the tablet, and its radio reaches the tablet wherever you both are.
# Then, on the Qbot (see README.md):
#     cp /helpme/qbots/remote/tablet_qbot.py /startup.py
# and press Run on its screen. Right-click in the air with the tablet to open it.
import time
import events
import logistics
import qbot
from qbot import inspect, QbotError

PAGE = "/helpme/qbots/remote/index.html"   # the same page controller.py shows

tab = logistics.network().tablet()     # on a Qbot: the tablet paired with this Qbot
tab.title = "Qbot"
web = tab.web_panel()
web.show(PAGE)

# ---- the orders: a word (a button's data-event) and what it does ------------------------

def dug(got):
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

# ---- what it sees: one picture a second at most -----------------------------------------
last_picture = time.ticks_ms() - 2000

def picture():
    global last_picture
    wait = 1050 - time.ticks_diff(time.ticks_ms(), last_picture)
    if wait > 0:
        time.sleep(wait / 1000)
    last_picture = time.ticks_ms()
    return qbot.snapshot(96, 64)

def show(said):
    # The picture, then the words under it: where it is, what is in front, what it carries.
    web.image("cam", picture())
    x, y, z = qbot.position()
    web.state.update(said=said, where="%d, %d, %d facing %s" % (x, y, z, qbot.facing()),
                     front=inspect.front().id.split(":")[-1],
                     items=sum(s["count"] for s in qbot.inventory.list()))

# ---- a button was pressed on the tablet --------------------------------------------------

def obey(event):
    word = event.event
    try:
        said = ORDERS[word]()
    except QbotError as e:
        said = "Couldn't: " + str(e)
    print(word, "->", said)
    show(said)

for word in ORDERS:
    web.on(word, obey)

show("Ready. Press a button to drive me.")
print("On the tablet carried by", tab.holder)
events.run()
