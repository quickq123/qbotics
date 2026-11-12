# A status screen. Put Text Monitors side by side (they join into one screen) on this
# computer's network, and a bus on a chest. Every five seconds the screen shows what the chest
# holds, biggest first, with a bar for each.
# Run it:  run status.py   (Ctrl+C stops it)
from logistics import network
import events

CHEST = None           # the bus's name, or None for the only one

net = network()
mon = net.monitor()
chest = net.bus(CHEST)

def nice(item):
    # "minecraft:iron_ingot" -> "Iron Ingot"
    words = item.split(":")[-1].split("_")
    return " ".join(w[:1].upper() + w[1:] for w in words)

@events.every(5)
def draw():
    columns, rows = mon.size()
    totals = {}
    for stack in chest.contents():
        totals[stack["item"]] = totals.get(stack["item"], 0) + stack["count"]
    biggest = sorted(totals.items(), key=lambda pair: -pair[1])[: rows - 2]
    most = biggest[0][1] if biggest else 1

    mon.clear()
    mon.fill(0, 0, columns, 1, "blue")
    mon.write_at(1, 0, "STORAGE", "white", "blue")
    for row, (item, count) in enumerate(biggest):
        y = row + 2
        bar = max(1, (columns // 3) * count // most)
        mon.write_at(0, y, nice(item)[: columns // 2], "light_gray")
        mon.fill(columns // 2, y, bar, 1, "green")
        mon.write_at(columns - len(str(count)), y, str(count), "yellow")

draw()
events.run()
