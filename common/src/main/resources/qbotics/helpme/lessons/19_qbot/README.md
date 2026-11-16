# Lesson 19: Robot Worker

**Earns:** *Robot Worker*: a Qbot's program moves it **and** digs. **You need:** an **Iron
Qbot**, and something to dig in front of it. Qbots need no fuel.

## The idea

A **Qbot** is a computer that moves: it walks, turns, digs, places and carries things, running
a program of its own. Its program is its `/startup.py`, started with the **Run** button on its
screen (right-click it).

## Try it

Open the Qbot's terminal (right-click it, or `ssh` to it from a computer cabled to it) and
write its program:

    edit /startup.py

    import qbot

    for step in range(5):
        got = qbot.dig()        # what was in front: {"minecraft:stone": 1}
        print("Dug", got)
        qbot.forward()
    qbot.turn_around()
    for step in range(5):
        qbot.forward()

Save, close the editor, and press **Run** on its screen.

## Your turn

Dig a tunnel two blocks high (`qbot.dig(qbot.Side.TOP)` digs above) and come back. Then unload
what it carries into a chest behind its start with `inventory.drop(Side.FRONT)`
(`from qbot import inventory, Side`).

## Hints

- `qbot.inspect()` says what is in front before digging; `inventory.list()` what it carries.
- Stop it with **Stop** on its screen. To change it, edit `/startup.py` and press Run again.
- Drive it from your tablet: `/helpme/qbots/remote`.

**More:** `/helpme/qbots/tunnel.py`. **Manual:** section 7d.
