# Keep handlers quick. Only one handler runs at a time, so one that sleeps holds up all the
# others. Watch the heartbeat stop while the slow handler sleeps, and carry on during the
# quick one. Nothing to build.
# Run it:  run 4_quick.py   (it stops by itself after 8 seconds)
import events
import time

start = time.ticks_ms()

def now():
    return "%4.1f s " % (time.ticks_diff(time.ticks_ms(), start) / 1000)

@events.every(0.5)
def heartbeat():
    print(now(), "heartbeat")

# Slow: sleeps inside the handler. No heartbeats for two seconds.
def slow():
    print(now(), "slow handler: sleeping 2 seconds...")
    time.sleep(2)
    print(now(), "slow handler: done")

events.after(1, slow)

# Quick: does part one, and asks for part two later with after(). The heartbeat carries on.
def part_one():
    print(now(), "quick handler: part one; part two in 2 seconds")
    events.after(2, part_two)

def part_two():
    print(now(), "quick handler: part two")

events.after(4, part_one)

events.after(8, events.stop)
events.run()
