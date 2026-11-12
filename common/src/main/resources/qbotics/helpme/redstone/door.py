# Redstone. A Logic Pin is a plate on a cable that reads or drives redstone on the block
# it points at. Put one pointing at a lever (name it "button") and one pointing at a lamp or a
# door (name it "door"). Flip the lever: the program opens the door for three seconds.
# Run it:  run door.py   (Ctrl+C stops it)
from logistics import network
import events

net = network()
button = net.pin("button")
door = net.pin("door")

def opened(event):
    print("Lever on: opening for 3 seconds")
    door.write(15)                       # full power
    events.after(3, lambda: door.write(0))

# Instead of checking the lever again and again, the game tells the program when it changes.
events.on_pin(button, "rising", opened)

@events.every(10)                        # a heartbeat, so you can see it is still running
def still_here():
    print("Waiting. The button reads", button.read())

print("Flip the lever.")
events.run()
