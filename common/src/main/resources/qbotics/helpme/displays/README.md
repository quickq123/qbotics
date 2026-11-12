# displays: screens

Screens of one kind side by side, facing the same way, join into one big screen. They are
found over the network, like any device. Right-click a screen to touch it; **sneak +
right-click with an empty hand** to see it close up (on a Web Panel: hover, scroll and type).

| Folder | Screen | What it shows |
|---|---|---|
| `monitor` | Text Monitor | `status.py`: a storage report with coloured bars, redrawn every 5 seconds |
| `panel` | Control Panel | `panel.py`: a switch, buttons, a slider, a gauge and a readout that players use |
| `web_storage` | Web Panel | `storage.py`: a storage browser with item pictures, a search box and a scrolling list (HTML and CSS) |
| `web_dashboard` | Web Panel | `dashboard.py`: a live chart drawn by the page's JavaScript, and a button that talks back to the program |

Run each from its own folder, for example:

    cd /helpme/displays/web_storage
    run storage.py

**Which screen?** A Text Monitor is simple text and colours. A Control Panel is ready-made
widgets, no HTML. A Web Panel is a whole web page: HTML and CSS for the look, `{{name}}` in the
page for the program's values (`web.state`), and JavaScript for anything that moves.
