# Every device the jobs use, in one place. A job imports what it needs:
#     from devices import inbox, ores
# When a chest moves or gets a new name, this is the only file to change.
#
# Devices are found by the names given with the Network Probe (addresses work too:
# net.bus("192.168.1.5")). A name that isn't on the network stops the program here, at the
# start, with a message saying which.
from logistics import network

net = network()

inbox = net.bus("inbox")        # where things arrive
ores = net.bus("ores")          # ores, ingots and coal; the furnaces take from and give back to it
store = net.bus("store")        # everything else

# The sorter's other chests are in sorter.py, beside their rules.
