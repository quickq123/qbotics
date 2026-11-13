# Timers: the simplest events. Nothing to build: run it and watch.
# Run it:  run 1_timers.py   (it stops by itself after 5 seconds)
import events

count = 0

def tick():
    global count            # "global": change the count above, not a new one inside tick
    count += 1
    print("tick", count)

# Hand the function over: tick, not tick(). events calls it later.
events.every(1, tick)                                       # every second

events.after(2.5, lambda: print("2.5 seconds: this runs once"))

# every() and after() give back a timer, which can be cancelled.
fast = events.every(0.3, lambda: print("  fast"))
events.after(1, fast.cancel)                                # stop the fast one after a second

events.after(5, events.stop)                                # and everything after five

print("Nothing above has run yet. Now events.run():")
events.run()                # waits, and calls the functions when they are due
print("run() has returned, after", count, "ticks. The program carries on from here.")
