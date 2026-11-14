# remote: drive a Qbot from your tablet

Two programs that talk by radio. One runs on the **Qbot** and obeys orders; the other runs
on a **computer at your base** and turns your **tablet** into a controller: buttons to drive
it, and the Qbot's camera view after every move.

    tablet --(button press)--> controller.py --(radio: {"do": "forward"})--> driver.py on the Qbot
    tablet <--(picture, words)-- controller.py <--(radio: answer + picture)--   (moves, then looks)

| File | Runs on | What it does |
|---|---|---|
| `driver.py` | The Qbot | Waits for orders as messages (`events.on_message`), carries each out, and answers with what happened, where it is and a picture of what it sees |
| `controller.py` | A computer at your base | Shows the controller on the tablet (`index.html`, `style.css`), sends an order for each button, and shows each answer and picture |

## Setting it up

1. **The Qbot.** A Qbot is built from a laptop, so it has an Infinite antenna: your base
   reaches it anywhere. From a computer cabled to the Qbot (or your laptop, right-clicking
   the Qbot):

       ssh <the Qbot's name or address>
       git clone helpme
       cp /helpme/qbots/remote/driver.py /startup.py
       exit

   Press **Run** on the Qbot's screen. It shows `Ready. My radio address is 100.64.0.9:1001`:
   note that address.
2. **The base.** A computer cabled to an antenna. Pair your tablet with that antenna (sneak
   and right-click the antenna with the tablet). On the computer:

       git clone helpme
       cd /helpme/qbots/remote
       edit controller.py         (put the Qbot's address in QBOT at the top, and save)
       run controller.py
3. **Drive.** Right-click in the air with the tablet. Each button sends one order; the picture
   and the words under it change when the Qbot answers.

## Making it your own

- **A new order:** in `driver.py`, write what it does and add it to `ORDERS`; in
  `index.html`, add a button whose `data-event` is its word; in `controller.py`, add the word
  to `ORDERS`.
- **Only its owner can drive it:** a Qbot's radio takes messages only from its owner's
  computers and Qbots.
- **A bigger picture:** `qbot.snapshot(96, 64)` in `driver.py` takes up to 128 x 96; change
  the size of `.cam` in `style.css` to match its shape.
