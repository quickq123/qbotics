# Lesson 14: In the Palm of Your Hand

**Earns:** *In the Palm of Your Hand*: a program reacts to a player using its screen on a
Tablet. **You need:** a **Tablet**, and an antenna on the computer's cables.

## The idea

A **Tablet** is a screen you carry, with an antenna inside that reaches anywhere. Pair it with
your base's antenna and the base's programs get a **tab** on it: a Text Monitor, a Control Panel
or a Web Panel, whichever the program makes it. Your base's controls, wherever you are.

1. Sneak and right-click your base's antenna holding the tablet: it pairs, and gets a tab.
2. Right-click in the air to open the tablet.

## Try it

The Web Panel program from lesson 13, but on the tablet. Only the first lines change:

    from logistics import network
    import events

    net = network()
    tab = net.tablet()              # the tablet paired with this network
    tab.title = "Store"             # what its tab says
    web = tab.web_panel()           # its screen, as a Web Panel
    store = net.bus("store")

    def count(event=None):
        web.state.update(total=store.count())

    web.on("refresh", count)
    web.show("/lessons/13_web/index.html")
    count()
    events.run()

Open the tablet and press **Count again**.

## Your turn

Put a Control Panel on the tablet instead (`tab.panel()`, 12 by 8 cells) with a button that
runs your sorter from lesson 4, so you can sort the base's inbox from anywhere.

## New Python here

- Setting a value on an object changes it: `tab.title = "Store"` renames the tab.
- One object can give you others: `tab.web_panel()` is the tablet's screen, as a Web Panel.

## Hints

- `net.tablet("Steve")` finds a tablet by the player carrying it, when several are paired.
- A Qbot can have a tab of its own too: `/helpme/qbots/remote` drives a Qbot from a tablet.

**Next:** `cd /lessons/15_always_on`. **More:** `/helpme/qbots/remote`. **Manual:** section 7c.7.
