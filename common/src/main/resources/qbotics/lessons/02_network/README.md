# Lesson 2: Who's There?

**Earns:** *Who's There?*. **You need:** a computer, a cable, and one Item Serial Bus on a chest.

## The idea

A computer reaches blocks through **cables**. Everything on the same cables is one **network**,
and each device on it (a bus, a pin, a screen) has an address and, if you give it one, a name.
Programs find devices by name, so the name is how your program and your base agree.

## Try it

1. Put a chest down, a cable from the computer to it, and right-click the chest holding an
   **Item Serial Bus**: it fixes onto the cable, pointing at the chest.
2. Right-click the bus with the **Network Probe** and name it `inbox`.
3. At the computer's prompt:

       devices

   It lists your network: the computer, the cable's bus named `inbox`, their addresses.

## Your turn

`start.py` lists your network and looks for a bus named `inbox`. Run it with `run start.py`.
If you named your bus something else, `edit start.py` and change `NAME` at the top.

How it finds the bus, in four lines:

    from logistics import network

    net = network()            # this computer's network
    inbox = net.bus("inbox")   # the device named "inbox"
    print("Found", inbox.name, "at", inbox.ip)
    print("It holds", inbox.count(), "items")

## New Python here

- `from logistics import network` brings in something from a **module** (a file of ready-made
  code) so this program can use it. The game's modules are `logistics`, `events`, `network`...
- `network()` **calls** a function: the brackets make it run, and it gives back a value.
- A **dot** reaches inside something: `net.bus("inbox")` is the `bus` that belongs to `net`.
- `try:` ... `except NetworkError:` tries something, and if that kind of error happens, runs the
  lines under `except` instead of stopping.

## Hints

- `net.bus("inbox")` raises `NetworkError` if nothing has that name. `devices` shows the names.
- `net.bus()` with no name finds the only bus, if there is just one.
- Other finders: `net.interface()`, `net.pin()`, `net.monitor()`, `net.storage()`, and more.
  `net.devices()` lists everything.

**Next:** `cd /lessons/03_first_move`, or `cd /lessons/19_qbot` for robots. **More:** `/helpme/basics/devices.py`.
**Manual:** sections 3 and 6.1.
