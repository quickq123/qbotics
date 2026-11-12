# The answering computer. Two computers on the same cables (or behind antennas): run this
# on one, and ping.py on the other. This one names itself "pong" so the other can find it.
# Run it:  run pong.py   (Ctrl+C stops it)
import network
import events

network.hostname("pong")
print("I am", network.hostname(), "- waiting for messages.")

@events.on_message
def answer(message):
    print("From", message.sender, ":", message.data)
    network.send(message.sender, {"pong": message.data.get("ping"), "from": network.hostname()})

events.run()
