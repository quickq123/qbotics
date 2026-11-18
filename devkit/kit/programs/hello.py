# Your first program. Press Ctrl+Shift+B to check it: the Problems panel (Ctrl+Shift+M) then
# shows any mistakes, and what it needs in the game to run (here, a bus named "store").
#
# Type "net." on a new line below to see what a network can find, with each one's help.
from logistics import network

net = network()
store = net.bus("store")       # an Item Serial Bus, named "store" with the Network Probe
print("The store holds", store.count(), "items")
