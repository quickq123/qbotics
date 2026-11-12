# A background program (a "service") on a Server. A Server runs up to four of them while
# its terminal stays free, and starts them again whenever it comes back on.
#
# On a Server:   service start /helpme/server/heartbeat.py
#                service list           (light 1 on the rack turns green)
#                service log heartbeat  (what it printed)
#                service stop heartbeat
# On a computer it runs like any program:  run heartbeat.py
import time

beats = 0
while True:
    beats += 1
    print("Still here: beat", beats)
    time.sleep(10)
