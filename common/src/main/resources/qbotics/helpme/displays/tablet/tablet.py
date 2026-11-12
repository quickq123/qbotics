# A Tablet: a screen you carry. This network gets a tab of its own on it.
# First pair the tablet: sneak and right-click this network's antenna with it.
# Then right-click in the air to open it; hold it to see the screen in your hand.
# Run it:  run tablet.py   (Ctrl+C stops it)
from logistics import network
import events

net = network()
tab = net.tablet()                       # the only tablet paired; or net.tablet("PlayerName")
tab.title = "Base"                       # what this network's tab says
print("Drawing on the tablet carried by", tab.holder)

panel = tab.panel()                      # 12 x 8 cells (or tab.monitor(), tab.web_panel())
panel.clear()
panel.label("Base status", at=(0, 0), span=(12, 1), color="yellow")
clock = panel.readout("Uptime", at=(0, 1), span=(4, 2), unit="s")
count = panel.number("Count", 0, 64, at=(4, 1), span=(5, 1), value=8)
ping = panel.button("Ping", at=(9, 1), span=(3, 1), color="green")
stop = panel.button("Stop", at=(9, 2), span=(3, 1), color="red")
heard = panel.label("", at=(0, 4), span=(12, 1))

seconds = 0

def tick():
    global seconds
    seconds += 1
    clock.value = seconds

@ping.on_press
def pinged(event):
    heard.label = "%s pinged with count %d" % (event.player, count.value)

@stop.on_press
def finish(event):
    heard.label = "Stopped."
    events.stop()

events.every(1, tick)
print("The tablet is ready. Right-click in the air to open it.")
events.run()
