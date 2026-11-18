# A Qbot's camera. This runs ON the Qbot (see tunnel.py for how). It takes a picture of
# what is in front of it every few seconds and shows it on a Web Panel touching the Qbot (or
# cabled to it), and says what it sees.
import qbot
import time
from logistics import network

web = network().web_panel()
web.html('<body style="margin: 0"><img src="image:cam" style="width: 100%; height: 100%"></body>')

while True:
    picture = qbot.view()                # 256 x 192 pixels (the most), up to four a second
    web.image("cam", picture)            # players' games draw it with the game's own blocks
    seen = picture.counts()
    seen.pop(None, None)                 # the sky
    if seen:
        most = max(seen, key=seen.get)
        print("Mostly", most, "- and", len(seen) - 1, "other things")
    middle = picture.creature_at(128, 96) # the creature in the middle, if any: "minecraft:cow"...
    if middle:
        print("A", middle.split(":")[-1], "is right in front of me!")
    time.sleep(3)
