# Lesson 12: Push the Button

**Earns:** *Push the Button*: a program reacts to a player using a Control Panel. **You need:**
two **Control Panels** side by side on the computer's cables (together, 8 by 4 cells), and if
you like the `lamp` pin from lesson 6.

## The idea

A **Control Panel** is a screen of widgets that players use: buttons, switches, sliders, and
gauges and readouts that show numbers. The program lays them out and says what each one does.
Players right-click to press.

## Try it

    from logistics import network
    import events

    net = network()
    panel = net.panel()
    panel.clear()

    panel.label("Base controls", at=(0, 0), span=(8, 1), color="yellow")
    hello = panel.button("Say hi", at=(0, 1), span=(3, 1), color="green")
    count = panel.readout("Pressed", at=(4, 1), span=(3, 1))
    pressed = 0

    @hello.on_press
    def said(event):
        global pressed
        pressed += 1
        count.value = pressed
        print(event.player, "said hi")

    events.run()

## Your turn

Add a switch that turns the lamp on and off, and a slider for how bright:

    light = panel.toggle("Lamp", at=(0, 2), span=(3, 1))
    level = panel.slider("Bright", 0, 15, at=(0, 3), span=(5, 1))
    lamp = net.pin("lamp")

    @light.on_change
    def switched(event):
        lamp.write(level.value if event.value else 0)

## Hints

- `at=(column, row)` places a widget, `span=(width, height)` sizes it.
- A handler gets an event with `event.player` and `event.value`.
- Sneak and right-click with an empty hand to see a panel close up.

**Next:** `13_web`. **More:** `/helpme/displays/panel/panel.py`. **Manual:** section 7c.5.
