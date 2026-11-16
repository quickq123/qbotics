# A guard: every few seconds, attack the monsters near an Entity Sensor with a sword from
# storage, then pick up what they dropped. A kill counts as yours, so they drop what they
# would for you (Looting too). An Experience Interface on the network banks the experience.
# Run it:  run 2_guard.py   (Ctrl+C stops it)
from logistics import network
import events

WEAPON = "#minecraft:swords"     # any sword in the store

net = network()
sensor = net.entity_sensor()
store = net.storage("chest*")
bank = []
try:
    bank = [net.experience_interface()]
except Exception:
    print("No Experience Interface: experience is left on the ground.")

def guard():
    for mob in sensor.entities():
        if mob.data.get("hostile"):                 # keys a kind lacks are missing: use get
            hit = mob.attack(WEAPON, store)         # a full swing each time
            if not hit["alive"]:
                print("Got a", mob.name)
    sensor.collect([store] + bank)                  # drops, and experience into the bank

guard()
events.every(3, guard)
print("Guarding. Ctrl+C stops it.")
events.run()
