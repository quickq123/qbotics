# A live dashboard. The program owns the numbers (here, a pretend reactor that heats up);
# the page's JavaScript draws them, and its button sends a message back.
#
# You need: Web Panels, 3 wide and 2 high, on this computer's network.
# Run it from this folder:  run dashboard.py   (Ctrl+C stops it)
from logistics import network
import events
import random

net = network()
web = net.web_panel()
heat = 40

@events.every(1)
def tick():
    global heat
    heat = max(20, min(150, heat + random.randint(-2, 6)))
    note = "Too hot! Press Cool down." if heat > 100 else ""
    web.state.update(heat=heat, note=note)     # the page's panel.onState runs

# panel.send("cool", {"amount": 25}) in the page's script.
@web.on("cool")
def cool(event):
    global heat
    heat = max(20, heat - event.data["amount"])
    print(event.player, "cooled it to", heat)

# A script error in the page comes here too, so you can see it.
@web.on("error")
def page_error(event):
    print("The page's script:", event.value)

web.show("index.html")       # the page, with dashboard.js put in
print("The dashboard is up.")
events.run()
