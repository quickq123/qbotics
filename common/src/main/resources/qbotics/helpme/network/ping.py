# The asking computer. Start pong.py on the other computer first.
# Run it:  run ping.py
import network
import time

for number in range(1, 4):
    started = time.ticks_ms()
    if not network.send("pong", {"ping": number}):
        print("pong isn't switched on (open it, and run pong.py there).")
        break
    reply = network.receive(5)            # wait up to 5 seconds
    if reply is None:
        print("No answer.")
    else:
        took = time.ticks_diff(time.ticks_ms(), started)
        print("Answer", reply.data, "in", took, "ms")
    time.sleep(1)
