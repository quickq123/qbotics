# The network. Everything joined to this computer by cables is on its network: buses,
# interfaces, displays, other computers. Each has an address, and a name if you give it one
# (right-click it with the Network Probe). The shell command  devices  shows the same.
# Run it:  run devices.py
from logistics import network
import network as radio

net = network()
me = radio.ifconfig()
print("This computer:", me["name"] or "(no name)", "at", me["ip"])
print()

found = net.devices()
print(len(found), "devices on this network:")
for device in found:
    print("  %-14s %-16s %s" % (device.kind, device.name or "-", device.ip))

# Give this computer a name, so other computers can find it.
name = input("\nName this computer (Enter to leave it): ")
if name:
    radio.hostname(name)
    print("Now called", radio.hostname())
