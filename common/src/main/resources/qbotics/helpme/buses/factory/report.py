# Job: a stock report. Every minute, says what is running low. A job that runs on a timer
# rather than when something changes.
import events
from devices import ores, store
from safely import safely

# What to keep an eye on: (chest, what, the least there should be)
LOW = [
    (ores, "coal | charcoal", 32),
    (ores, "#c:ingots/iron", 64),
    (store, "torch", 16),
]


def report():
    for chest, what, least in LOW:
        have = chest.count(what)
        if have < least:
            print("Low:", what, "-", have, "of", least)


def start():
    report()
    events.every(60, safely("report", report))
