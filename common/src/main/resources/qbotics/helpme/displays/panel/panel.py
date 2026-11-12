# A Control Panel: buttons, switches, sliders and gauges that players use. Put two Control
# Panels side by side on this computer's network (a 2 x 1 panel is 8 by 4 cells). For the
# switch to do something, add a Logic Pin named "lamp" pointing at a redstone lamp.
# Right-click to press; sneak + right-click with an empty hand to see it close up.
# Run it:  run panel.py   (Ctrl+C stops it)
from logistics import network
import events
import random

net = network()
panel = net.panel()
panel.clear()

panel.label("My first panel", at=(0, 0), span=(8, 1), color="yellow")
light = panel.toggle("Lamp", at=(0, 1), span=(3, 1))
dice = panel.button("Roll", at=(4, 1), span=(2, 1), color="green")
stop = panel.button("Stop", at=(6, 1), span=(2, 1), color="red")
level = panel.slider("Level", 0, 15, at=(0, 2), span=(5, 1))
shown = panel.gauge("Shown", 0, 15, at=(0, 3), span=(5, 1))
rolled = panel.readout("Dice", at=(6, 2), span=(2, 2))

try:
    lamp = net.pin("lamp")
except Exception:
    lamp = None                          # no lamp pin: the switch only shows

@light.on_change
def switched(event):
    print(event.player, "turned the lamp", "on" if event.value else "off")
    if lamp:
        lamp.write(level.value if event.value else 0)

@level.on_change
def moved(event):
    shown.value = event.value
    if lamp and light.on:
        lamp.write(event.value)

@dice.on_press
def roll(event):
    rolled.value = random.randint(1, 6)

@stop.on_press
def finish(event):
    print("Stopped by", event.player)
    events.stop()

print("The panel is ready.")
events.run()
