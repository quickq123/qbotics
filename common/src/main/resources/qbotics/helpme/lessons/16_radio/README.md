# Lesson 16: Over the Air

**Earns:** *Over the Air*: through antennas, send a message to another computer **and** work a
device on another network. **You need:** two networks, each with an **antenna** (Short-Range
reaches 24 blocks, Long-Range 100) and a computer. For the second half, a **Transport Upgrade**
in both antennas, and a chest with a bus on the far network.

## The idea

Cables join blocks that touch. **Antennas** join networks that don't: a mine and a base, a
farm across the river. Each antenna has an **external address** (`100.64.0.2`), and each
computer behind it a port (`100.64.0.2:1003`). Antennas on the same frequency, in range, hear
each other; who may reach in is the antenna's **access** (private, team or public).

A computer's external address is its antenna's external IP, then a port: 1000 plus the last
number of its internal IP. A computer at `192.168.1.3` behind antenna `100.64.0.2` is
`100.64.0.2:1003`. On the far computer, `devices` shows both numbers.

## Try it: a message

On the far computer (the mine), wait for messages:

    import network, events

    @events.on_message
    def got(message):
        print("From", message.sender, ":", message.data)
        network.send(message.sender, {"ore": 128})

    events.run()

On the base computer, send one, to the mine computer's external address and port:

    import network
    print(network.send("100.64.0.2:1003", {"ask": "ore"}))   # True: it arrived
    print(network.receive(5).data)                            # its answer

## Your turn: a device far away

With a Transport Upgrade in both antennas, the far network's devices work as if they were on
your cables:

    from logistics import network, move

    net = network()
    mine = net.remote("100.64.0.2")             # the far antenna's external IP
    print(mine.bus("ore_chest").count(), "items at the mine")
    move(mine.bus("ore_chest"), net.bus("store"), "#c:raw_materials", 64)

## Hints

- An antenna carries a limited number of items a second; a move asks for what fits.
- `network.Radio()` is this network's antenna: `.external_ip`, `.frequency`, `.tune(n)`.
- Only antennas on the same frequency hear each other. A new antenna is private: only your own
  devices reach it.

**Next:** `17_service`. **More:** `/helpme/network` (ping and pong), `/helpme/qbots/remote/base`.
**Manual:** sections 7.2 to 7.3b.
