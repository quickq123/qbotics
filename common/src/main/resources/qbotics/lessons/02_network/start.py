# Lists what is on this computer's network, then finds one device by its name.
# Run it:  run start.py

from logistics import network, NetworkError

NAME = "inbox"     # CHANGE ME: the name you gave your bus (Network Probe)

net = network()

print("On this computer's network:")
for device in net.devices():
    print("  ", device.kind, device.name or "(no name)", device.ip)

try:
    found = net.bus(NAME)
    print("Found", NAME, "- it holds", found.count(), "items")
except NetworkError:
    print("Nothing here is named", NAME, "yet.")
    print("Name your bus with the Network Probe, or change NAME at the top.")
