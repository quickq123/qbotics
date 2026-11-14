# Remote control, the tablet's side. This runs on a COMPUTER AT YOUR BASE, and turns your
# tablet into the Qbot's controller: buttons to drive it, and its camera's view after every
# move. driver.py must be running on the Qbot.
#
# You need: this computer cabled to an antenna, and your tablet paired with that antenna
# (sneak and right-click the antenna with the tablet). Then:
#     cd /helpme/qbots/remote
#     run controller.py
# and right-click in the air with the tablet to open it.
#
# How it fits together: a button press on the tablet runs send_order() (web.on), which sends
# the order to the Qbot by radio. The Qbot's answer arrives as a message, and
# events.on_message runs show_answer(), which puts its picture and words on the tablet.
import events
import network
import logistics

# The Qbot's radio address: driver.py prints it when it starts (it shows on the Qbot's screen).
QBOT = "100.64.0.9:1001"

tab = logistics.network().tablet()   # the only tablet paired here; or tablet("YourName")
tab.title = "Qbot"
web = tab.web_panel()
web.show("index.html")               # the page: index.html, with style.css put in
web.state.update(said="Press a button to drive the Qbot.", where="", front="", items="", waiting=False)

ORDERS = ["forward", "back", "up", "down", "left", "right", "dig", "dig_up", "dig_down", "place", "look"]

def send_order(event):
    if web.state.get("waiting"):
        return                       # one order at a time: wait for the answer
    word = event.event               # which button: its data-event
    order = {"do": word}
    if word.startswith("dig_"):      # dig_up and dig_down are "dig" with a side
        order = {"do": "dig", "side": "top" if word == "dig_up" else "bottom"}
    try:
        delivered = network.send(QBOT, order)
    except network.NetworkError as e:
        web.state.update(said="Can't reach the Qbot: %s" % e)
        return
    if delivered:
        web.state.update(said="%s..." % word, waiting=True)
    else:
        web.state.update(said="The Qbot isn't listening: is driver.py running on it?")

for word in ORDERS:
    web.on(word, send_order)

@events.on_message
def show_answer(message):
    answer = message.data
    if not isinstance(answer, dict) or "picture" not in answer:
        return                       # not from the Qbot
    web.image("cam", answer["picture"])    # the picture's number is enough to show it
    web.state.update(said=("" if answer["ok"] else "Couldn't: ") + answer["said"], where=answer["where"],
                     front=answer["front"].split(":")[-1], items=answer["items"], waiting=False)

print("Controlling", QBOT, "from the tablet carried by", tab.holder)
events.run()
