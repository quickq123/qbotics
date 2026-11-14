# remote: drive a Qbot from your tablet

Your tablet becomes the Qbot's controller: a picture of what the Qbot sees, and buttons to
move, turn, dig and place. Everything runs **on the Qbot**: no base computer, no antenna,
no cables. The Qbot's radio reaches your tablet wherever you both are.

| File | What it is |
|---|---|
| `startup.py` | The program. A Qbot runs `/startup.py` when you press **Run** on its screen |
| `index.html` | The page on the tablet: the picture and the buttons |
| `style.css` | How the page looks: colours and sizes |
| `base/` | The same controller run from a computer at your base instead, talking to the Qbot by radio (more advanced: see `base/README.md`) |

## Setting it up

You need an Iron Qbot and a Tablet.

1. **Pair the tablet with the Qbot.** Sneak and right-click the Qbot with the tablet. A tab
   for the Qbot appears on the tablet.
2. **Put the program on the Qbot.** Open the Qbot's terminal: right-click it with your laptop,
   or from a computer cabled to it type `ssh <the Qbot's name or address>`. Then type:

       git clone helpme
       cp /helpme/qbots/remote/startup.py /startup.py
       exit

3. **Press Run** on the Qbot's screen (right-click the Qbot). It says `On the tablet carried by ...`.
4. **Drive.** Right-click in the air with the tablet to open it, and press the buttons. Each
   press moves the Qbot, then the picture shows what it sees now.

The words under the picture say what happened: `moved forward`, `dug 1 cobblestone`, or why
it couldn't (`Couldn't: It can't go north: minecraft:stone is in the way.`). Dig it first!

## How the program works

`startup.py` is in three parts, in the order they run:

1. **Set up:** find the tablet (`network().tablet()`) and show the page on it.
2. **Orders:** a table, `ORDERS`. Each button on the page has a word (`data-event="forward"`),
   and the table says what the Qbot does for that word (`qbot.forward()`).
3. **Events:** `web.on(word, obey)` asks for `obey()` to run whenever that button is pressed.
   `obey()` carries out the order and shows the new picture. `events.run()` at the end waits
   for presses, for ever.

## Making it your own

Edit `/startup.py` (your copy), then press **Stop** and **Run** on the Qbot's screen.

- **A new order,** say "turn around": add `"around": lambda: "now facing " + qbot.turn_around(),`
  to `ORDERS`, and a button to `index.html`: `<button data-event="around">Spin</button>`. For a
  page of your own, copy `index.html` and `style.css` next to your `/startup.py` and change
  `PAGE` to `"/index.html"`.
- **A bigger picture:** `qbot.snapshot(96, 64)` can take up to 128 x 96; change the size of
  `.cam` in `style.css` to match its shape.
