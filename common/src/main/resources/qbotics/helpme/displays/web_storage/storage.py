# A storage browser on a Web Panel: every item in a chest with its picture, a search box,
# and a button that takes 16 out.
#
# You need: Web Panels (3 wide, 2 high is good) on this computer's network, a bus on your
# storage chest, and for "Take 16" a second bus named "out" on another chest.
# Run it from this folder:  run storage.py   (Ctrl+C stops it)
# Sneak + right-click the panel with an empty hand to scroll the list and type in the search.
from logistics import network, move
import events

CHEST = None             # the storage bus's name, or None for the only bus
OUT = "out"              # where "Take 16" puts things; None for no button

net = network()
web = net.web_panel()
chest = net.bus(CHEST)
out = None
if OUT:
    try:
        out = net.bus(OUT)
    except Exception:
        print("No bus called", OUT, "- the Take buttons are hidden.")

search = ""

def nice(item):
    words = item.split(":")[-1].split("_")
    return " ".join(w[:1].upper() + w[1:] for w in words)

def refresh(change=None):
    totals = {}
    for stack in chest.contents():
        totals[stack["item"]] = totals.get(stack["item"], 0) + stack["count"]
    items = []
    for item, count in totals.items():
        name = nice(item)
        if search.lower() in name.lower():
            items.append({"id": item, "name": name, "count": count})
    items.sort(key=lambda i: -i["count"])
    # One update for everything: players see the page change at once.
    web.state.update(items=items, total=sum(totals.values()), kinds=len(totals),
                     search=search, can_take=out is not None)

# The player typed in the search box (the field is named "search").
@web.on("search")
def searched(event):
    global search
    search = event.value
    refresh()

# A "Take 16" button: its name is the item's id.
@web.on("take")
def take(event):
    moved = move(chest, out, event.name, 16)
    print(event.player, "took", moved, nice(event.name))
    refresh()

web.show("index.html")        # the page, with style.css put in
refresh()
events.on_change(chest, refresh)   # the chest changed: show it
print("The storage page is up.")
events.run()
