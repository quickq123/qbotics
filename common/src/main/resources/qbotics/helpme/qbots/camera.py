# A Qbot's camera. This runs ON the Qbot (see tunnel.py for how). It takes a picture of
# what is in front of it every few seconds and shows it on a Web Panel touching the Qbot (or
# cabled to it), and says what it sees.
import qbot
import time
from logistics import network

web = network().web_panel()
web.html('<body style="margin: 0"><img src="image:cam" style="width: 100%; height: 100%"></body>')

while True:
    picture = qbot.snapshot(96, 64)      # up to 128 x 96 pixels, one a second
    web.image("cam", picture)            # players see it in the blocks' real colours
    seen = picture.counts()
    seen.pop(None, None)                 # the sky
    if seen:
        most = max(seen, key=seen.get)
        print("Mostly", most, "- and", len(seen) - 1, "other things")
    if picture.block_at(48, 32) in ("monster", "player", "animal"):
        print("Something is right in front of me!")
    time.sleep(3)
